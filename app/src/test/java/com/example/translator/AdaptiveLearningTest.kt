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
    fun build_withoutHints_matchesOriginalTemplate() {
        val prompt = TranslationPromptBuilder.build("Γεια σου", "Romanian", TranslationHints.NONE)
        assertEquals(
            "Translate the following text into Romanian, output only the translated result without any explanation:\nΓεια σου",
            prompt
        )
    }

    @Test
    fun build_withHints_includesContextAndGlossary() {
        val hints = TranslationHints(
            contextMessages = listOf("m1", "m2", "m3", "m4"),
            preferredTerms = listOf(TermCorrection("companiei", "găștii"))
        )
        val prompt = TranslationPromptBuilder.build("Γεια", "Romanian", hints)

        assertFalse("Oldest context message must be dropped", prompt.contains("- m1"))
        assertTrue(prompt.contains("- m4"))
        assertTrue(prompt.contains("\"companiei\" -> \"găștii\""))
        assertTrue(prompt.endsWith("Γεια"))
    }

    @Test
    fun hasContextLeak_detectsEchoedContext() {
        val hints = TranslationHints(contextMessages = listOf("Acesta este un mesaj de test"))
        assertTrue(TranslationPromptBuilder.hasContextLeak("Acesta este un mesaj de test Bună", "Γεια", hints))
        assertFalse(TranslationPromptBuilder.hasContextLeak("Bună", "Γεια", hints))
    }
}

