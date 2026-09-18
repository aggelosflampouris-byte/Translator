package com.example.translator

import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationFilterTest {

    @Test
    fun shouldTranslate_filtersUrlsAndPathFragments() {
        assertFalse(TranslationFilter.shouldTranslate("https://www.facebook.com/share", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("https://bit.ly/Getwaze", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("/1Dma5mMC39/", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("/hsw8Zs6vh4", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("/19LS2nuQVo/", TranslateLanguage.GREEK))
    }

    @Test
    fun shouldTranslate_filtersTimestampsAndBattery() {
        assertFalse(TranslationFilter.shouldTranslate("5:16 μ.μ.", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("12:55", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("3:23 pm", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("71%", TranslateLanguage.GREEK))
    }

    @Test
    fun shouldTranslate_filtersCommonUiTokens() {
        assertFalse(TranslationFilter.shouldTranslate("Μήνυμα", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Type a message", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Χθες", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Today", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Online", TranslateLanguage.GREEK))
    }

    @Test
    fun shouldTranslate_filtersGreekTextWhenTargetIsGreek() {
        assertFalse(TranslationFilter.shouldTranslate("Εδω παρκαρα εγβ", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Έχω σταθμεύσει εδώ: Γ. Μαρίνου, Ελληνικό.", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Κατέβασε το Waze", TranslateLanguage.GREEK))
    }

    @Test
    fun shouldTranslate_acceptsValidForeignMessages() {
        assertTrue(TranslationFilter.shouldTranslate("La mulți ani și un An Nou fericit, Angelos.", TranslateLanguage.GREEK))
        assertTrue(TranslationFilter.shouldTranslate("Ce faci prietene?", TranslateLanguage.GREEK))
        assertTrue(TranslationFilter.shouldTranslate("How are you doing today?", TranslateLanguage.GREEK))
    }

    @Test
    fun cleanMessageText_stripsTrailingTimestamps() {
        val raw = "La mulți ani și un An Nou fericit,\nAngelos.\n7:27 μ.μ."
        val cleaned = TranslationFilter.cleanMessageText(raw)
        assertEquals("La mulți ani și un An Nou fericit,\nAngelos.", cleaned)
    }

    @Test
    fun isTargetLanguage_detectsGreekScript() {
        assertTrue(TranslationFilter.isTargetLanguage("Εδω παρκαρα εγβ", TranslateLanguage.GREEK, emptyList()))
        assertFalse(TranslationFilter.isTargetLanguage("La mulți ani", TranslateLanguage.GREEK, emptyList()))
    }

    @Test
    fun isMatchingSourceLanguage_matchesRomanianWithDiacritics() {
        val result = TranslationFilter.isMatchingSourceLanguage(
            "La mulți ani și un An Nou fericit",
            TranslateLanguage.ROMANIAN,
            emptyList()
        )
        assertTrue(result)
    }

    @Test
    fun isMatchingSourceLanguage_rejectsMismatchedCandidates() {
        val candidates = listOf(
            IdentifiedLanguage("en", 0.95f),
            IdentifiedLanguage("ro", 0.05f)
        )
        val result = TranslationFilter.isMatchingSourceLanguage(
            "Hello my friend how are you",
            TranslateLanguage.ROMANIAN,
            candidates
        )
        assertFalse(result)
    }

    @Test
    fun isMatchingSourceLanguage_acceptsHighConfidenceCandidate() {
        val candidates = listOf(
            IdentifiedLanguage("ro", 0.85f),
            IdentifiedLanguage("it", 0.15f)
        )
        val result = TranslationFilter.isMatchingSourceLanguage(
            "La multi ani si un An Nou fericit",
            TranslateLanguage.ROMANIAN,
            candidates
        )
        assertTrue(result)
    }

    @Test
    fun isMatchingSourceLanguage_matchesRomanianWithoutDiacriticsWhenCommonWordsPresent() {
        val result = TranslationFilter.isMatchingSourceLanguage(
            "La multi ani si un An Nou fericit, Angelos",
            TranslateLanguage.ROMANIAN,
            emptyList()
        )
        assertTrue(result)

        val result2 = TranslationFilter.isMatchingSourceLanguage(
            "Ce faci prietene?",
            TranslateLanguage.ROMANIAN,
            emptyList()
        )
        assertTrue(result2)
    }
}
