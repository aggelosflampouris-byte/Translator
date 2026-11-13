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

    // ── TextSanitizer ────────────────────────────────────────────────────────

    @Test
    fun specialCharacterGuardrails_sanitizeControlTokensAndRejectCorruptedOutput() {
        val dirtyInput = "\u200EΓεια\u200B <|im_end|>[Source] σου!!!!!!!!"
        val sanitized = TextSanitizer.sanitizeSpecialCharacters(dirtyInput)
        assertEquals("Γεια  σου!!!", sanitized)

        // Preserves quotes when source had quotes, strips hallucinated quotes when source did not
        assertEquals("Bună", TextSanitizer.stripWrappingQuotes("\"Bună\"", "Γεια"))
        assertEquals("\"Bună\"", TextSanitizer.stripWrappingQuotes("\"Bună\"", "\"Γεια\""))

        // Rejects runaway special characters or leaked control tokens in output
        assertTrue(TextSanitizer.hasSpecialCharacterCorruption("Bună????????", "Γεια;"))
        assertTrue(TextSanitizer.hasSpecialCharacterCorruption("Bună <|im_start|>", "Γεια"))
        assertFalse(TextSanitizer.hasSpecialCharacterCorruption("Bună! Ce faci?", "Γεια! Τι κάνεις;"))
    }

    @Test
    fun isValidTranslation_enforcesTargetScriptAndRejectsNonTargetOutput() {
        assertTrue(
            TranslationPipeline.isValidTranslation(
                output = "Προσπαθώντας να καταλάβω πώς θα κάνω την εφαρμογή να δουλέψει",
                cleanInput = "Încercând să dau seama cum voi face aplicația să funcționeze",
                targetLang = "el"
            )
        )
        assertFalse(
            "English text must be rejected when target is Greek",
            TranslationPipeline.isValidTranslation(
                output = "Thinking Process: Translate Romanian to Greek",
                cleanInput = "Încercând să dau seama",
                targetLang = "el"
            )
        )
        assertFalse(
            "Unchanged source text must be rejected",
            TranslationPipeline.isValidTranslation(
                output = "Încercând să dau seama",
                cleanInput = "Încercând să dau seama",
                targetLang = "ro"
            )
        )
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
            "Πώς είσαι φίλε μου Γιάννη;",
            TranslationSenseEngine.applyPostTranslationSenseLogic(
                originalText = "Cum ești prietenul meu Giannis?",
                translatedText = "Πώς είσαι ο φίλος μου Γιάννης?",
                sourceLang = "ro",
                targetLang = "el"
            )
        )
    }

    @Test
    fun englishToGreek_acceptsEnglishAndMixedMessagesWhenSourceIsEnglish() {
        val jobOffer = """
            ΠΡΟΣΦΟΡΑ ΕΡΓΑΣΙΑΣ ΒΟΥΚΟΥΡΕΣΤΙ:
            Game Presenters with Greek Language

            No experience Needed
            Good knowledge (written and speaking)
            of Greek Language
            900€ Monthly Salary (net) (4500 RON)
        """.trimIndent()

        // When source is English ("en") and target is Greek ("el"), both must be eligible
        assertTrue(TranslationFilter.shouldTranslate(jobOffer, "el", "en"))
        assertTrue(TranslationSenseEngine.isEligibleSourceText(jobOffer, "en", "el", "en"))
        assertTrue(TranslationSenseEngine.isEligibleSourceText(jobOffer, "en", "el", "el"))

        val shortMixed = "Online καζίνο"
        assertTrue(TranslationFilter.shouldTranslate(shortMixed, "el", "en"))
        assertTrue(TranslationSenseEngine.isEligibleSourceText(shortMixed, "en", "el"))

        // When source is Romanian ("ro"), the English job offer is still rejected
        assertFalse(TranslationSenseEngine.isEligibleSourceText(jobOffer, "ro", "el", "en"))
    }

    @Test
    fun normalizeCustomCorrection_validatesAndNormalizesGlossaryEntries() {
        assertNull(LearningStore.normalizeCustomCorrection("ro", "ro", "foo", "bar"))
        assertNull(LearningStore.normalizeCustomCorrection("ro", "el", "   ", "bar"))
        assertNull(LearningStore.normalizeCustomCorrection("ro", "el", "casa", "CASA"))

        val pair = LearningStore.normalizeCustomCorrection("ro", "el", "  Compania   Mea ", "  Η   παρέα μας ")
        assertEquals("compania mea" to "Η παρέα μας", pair)
        val applied = CorrectionApplier.apply(
            "Compania Mea este gata",
            listOf(TermCorrection(pair!!.first, pair.second))
        )
        assertEquals("Η παρέα μας este gata", applied)
    }
}

