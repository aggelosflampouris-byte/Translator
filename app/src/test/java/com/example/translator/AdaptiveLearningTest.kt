package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLearningTest {

    // ── CorrectionExtractor ──────────────────────────────────────────────────

    @Test
    fun analyze_identicalDraft_isAccepted() {
        val feedback = CorrectionExtractor.analyze("Bună dimineața!", "bună dimineața")
        assertEquals(DraftFeedback.Accepted, feedback)
    }

    @Test
    fun analyze_singleWordFix_extractsCorrection() {
        val machine = "Încercând să dau seama cum voi face aplicația să funcționeze pentru idiotul companiei"
        val sent = "Încercând să dau seama cum voi face aplicația să funcționeze pentru idiotul găștii"

        val feedback = CorrectionExtractor.analyze(machine, sent)

        assertTrue(feedback is DraftFeedback.Edited)
        val corrections = (feedback as DraftFeedback.Edited).corrections
        assertEquals(listOf(TermCorrection("companiei", "găștii")), corrections)
    }

    @Test
    fun analyze_multipleFixes_extractsEachSubstitution() {
        val machine = "voi face cererea de a lucra pentru stupidul companiei"
        val sent = "voi face aplicația de a funcționa pentru stupidul găștii"

        val corrections = (CorrectionExtractor.analyze(machine, sent) as DraftFeedback.Edited).corrections

        assertEquals(
            listOf(
                TermCorrection("cererea", "aplicația"),
                TermCorrection("lucra", "funcționa"),
                TermCorrection("companiei", "găștii")
            ),
            corrections
        )
    }

    @Test
    fun analyze_pureInsertion_learnsNothing() {
        val corrections = (CorrectionExtractor.analyze("Ne vedem mâine", "Ne vedem mâine seară") as DraftFeedback.Edited).corrections
        assertTrue(corrections.isEmpty())
    }

    @Test
    fun analyze_completelyDifferentMessage_isUnrelated() {
        val feedback = CorrectionExtractor.analyze("Ne vedem mâine la cafenea", "Am uitat cheile acasă azi")
        assertEquals(DraftFeedback.Unrelated, feedback)
    }

    @Test
    fun tokenize_stripsPunctuationAndLowercases() {
        assertEquals(listOf("salut", "ce", "faci"), CorrectionExtractor.tokenize("Salut, ce faci?!"))
    }

    // ── CorrectionApplier ────────────────────────────────────────────────────

    @Test
    fun apply_replacesWholeWordsOnly_andKeepsCapitalization() {
        val corrections = listOf(TermCorrection("companiei", "găștii"))

        assertEquals("Idiotul găștii.", CorrectionApplier.apply("Idiotul companiei.", corrections))
        assertEquals("Găștii îi place", CorrectionApplier.apply("Companiei îi place", corrections))
        assertEquals("subcompaniei", CorrectionApplier.apply("subcompaniei", corrections))
    }

    @Test
    fun apply_handlesGreekText() {
        val corrections = listOf(TermCorrection("συμμορία", "παρέα"))
        assertEquals("για την ηλίθια παρέα", CorrectionApplier.apply("για την ηλίθια συμμορία", corrections))
    }

    // ── SentMessageDetector ──────────────────────────────────────────────────

    @Test
    fun detector_reportsSend_whenFieldClearsAtOnce() {
        val detector = SentMessageDetector()
        assertNull(detector.observe("Bună"))
        assertNull(detector.observe("Bună ziua"))
        assertEquals("Bună ziua", detector.observe(""))
    }

    @Test
    fun detector_ignoresBackspaceDeletion() {
        val detector = SentMessageDetector()
        detector.observe("ab")
        detector.observe("a")
        assertNull(detector.observe(""))
    }

    // ── TranslationPromptBuilder ─────────────────────────────────────────────

    @Test
    fun build_withoutHints_matchesOfficialIndexTranslateTemplateWithNoThinkPrefill() {
        val prompt = TranslationPromptBuilder.build("Γεια σου", "Romanian", TranslationHints.NONE)
        assertEquals(
            "<|im_start|>user\n" +
                "Translate the following text into Romanian. Preserve all numbers, emojis, @mentions, #hashtags, and special symbols intact, and output the translation directly, without quotes, markdown, or any explanation:\n\n" +
                "Γεια σου<|im_end|>\n" +
                "<|im_start|>assistant\n" +
                "<think>\n\n</think>\n\n",
            prompt
        )
    }

    @Test
    fun build_withHints_includesContextAndGlossaryInInstTransFormat() {
        val hints = TranslationHints(
            contextMessages = listOf("m1", "m2", "m3", "m4"),
            preferredTerms = listOf(TermCorrection("companiei", "găștii"))
        )
        val prompt = TranslationPromptBuilder.build("Γεια", "Romanian", hints)

        assertFalse("Oldest context message must be dropped", prompt.contains("m1"))
        assertTrue(prompt.contains("m2 | m3 | m4"))
        assertTrue(prompt.contains("[Hard Constraint] Terminology mapping: companiei -> găștii"))
        assertTrue(prompt.contains("[Guardrail] Special characters:"))
        assertTrue(prompt.contains("[Source]\nΓεια"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun specialCharacterGuardrails_sanitizeControlTokensAndRejectCorruptedOutput() {
        val dirtyInput = "\u200EΓεια\u200B <|im_end|>[Source] σου!!!!!!!!"
        val sanitized = TranslationPromptBuilder.sanitizeSpecialCharacters(dirtyInput)
        assertEquals("Γεια  σου!!!", sanitized)

        // Preserves quotes when source had quotes, strips hallucinated quotes when source did not
        assertEquals("Bună", TranslationPromptBuilder.stripThink("\"Bună\"", "Γεια"))
        assertEquals("\"Bună\"", TranslationPromptBuilder.stripThink("\"Bună\"", "\"Γεια\""))

        // Rejects runaway special characters or leaked control tokens in output
        assertTrue(TranslationPromptBuilder.hasSpecialCharacterCorruption("Bună????????", "Γεια;"))
        assertTrue(TranslationPromptBuilder.hasSpecialCharacterCorruption("Bună <|im_start|>", "Γεια"))
        assertFalse(TranslationPromptBuilder.hasSpecialCharacterCorruption("Bună! Ce faci?", "Γεια! Τι κάνεις;"))
    }

    @Test
    fun stripThink_removesClosedThinkBlockAndEndTokens() {
        val raw = "<think>\n\n</think>\n\nΠροσπαθώντας να καταλάβω<|im_end|>"
        assertEquals("Προσπαθώντας να καταλάβω", TranslationPromptBuilder.stripThink(raw))
    }

    @Test
    fun stripThink_rejectsUnclosedThinkBlock() {
        val raw = "<think>\nThinking Process:\n1. Analyze the input sentence in Romanian..."
        assertEquals("", TranslationPromptBuilder.stripThink(raw))
    }

    @Test
    fun isValidTranslation_enforcesTargetScriptAndRejectsThinkLeaks() {
        assertTrue(
            IndexTranslationEngine.isValidTranslation(
                output = "Προσπαθώντας να καταλάβω πώς θα κάνω την εφαρμογή να δουλέψει",
                cleanInput = "Încercând să dau seama cum voi face aplicația să funcționeze",
                targetLang = "el"
            )
        )
        assertFalse(
            "English thinking text must be rejected when target is Greek",
            IndexTranslationEngine.isValidTranslation(
                output = "Thinking Process: Translate Romanian to Greek",
                cleanInput = "Încercând să dau seama",
                targetLang = "el"
            )
        )
        assertFalse(
            "Unchanged source text must be rejected",
            IndexTranslationEngine.isValidTranslation(
                output = "Încercând să dau seama",
                cleanInput = "Încercând să dau seama",
                targetLang = "ro"
            )
        )
    }

    @Test
    fun hasContextLeak_detectsEchoedContextAndIgnoresClosedThinkBlock() {
        val hints = TranslationHints(contextMessages = listOf("Acesta este un mesaj de test"))
        assertTrue(TranslationPromptBuilder.hasContextLeak("Acesta este un mesaj de test Bună", "Γεια", hints))
        assertFalse(TranslationPromptBuilder.hasContextLeak("<think>\n\n</think>\n\nBună", "Γεια", hints))
    }

    @Test
    fun phoneticTextInTargetScript_isNotTranslated() {
        val incoming = "Μπουνα σέρα ρε μαλακα"
        assertFalse(TranslationFilter.shouldTranslate(incoming, "el", "ro"))
        assertFalse(TranslationSenseEngine.isEligibleSourceText(incoming, "ro", "el"))
        assertNull(TranslationSenseEngine.resolveIdiomPreTranslation(incoming, "ro", "el"))
    }

    @Test
    fun genuineRomanianAndSenseNormalization_producesConsistentGreekOutput() {
        assertEquals(
            "Bună seara Giannis Acesta este un test",
            TextNormalizer.restoreRomanianDiacritics("Buna seara Giannis Acesta este un test")
        )
        assertEquals(
            "Προσπαθώντας να καταλάβω πώς θα κάνω την εφαρμογή να δουλέψει για τον ηλίθιο της παρέας",
            TranslationSenseEngine.applyPostTranslationSenseLogic(
                originalText = "Încercând să dau seama că voi face cererea de a lucra pentru stupidul companiei",
                translatedText = "Προσπαθώντας να καταλάβω ότι θα κάνω το αίτημα να εργαστεί για το ηλίθιο",
                sourceLang = "ro",
                targetLang = "el"
            )
        )
    }
}

