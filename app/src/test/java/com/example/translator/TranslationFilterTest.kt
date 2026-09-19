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

    @Test
    fun cleanMessageText_stripsWhatsAppDeliveryStatusAndGreekTimestamps() {
        val raw = "La mulți ani și un An Nou fericit, Angelos., 7:27 μ.μ., Παραδόθηκε"
        val cleaned = TranslationFilter.cleanMessageText(raw)
        assertEquals("La mulți ani și un An Nou fericit, Angelos.", cleaned)

        val rawEnglish = "How are you doing today?, 10:15 am, Delivered"
        val cleanedEnglish = TranslationFilter.cleanMessageText(rawEnglish)
        assertEquals("How are you doing today?", cleanedEnglish)

        val rawRomanian = "Sunt pe drum, 18:30, Trimis"
        val cleanedRomanian = TranslationFilter.cleanMessageText(rawRomanian)
        assertEquals("Sunt pe drum", cleanedRomanian)
    }

    @Test
    fun shouldTranslate_acceptsRomanianWithTrailingGreekDeliveryStatus() {
        // Even if WhatsApp raw node text had ", 7:27 μ.μ., Παραδόθηκε", it should still be accepted
        val raw = "La mulți ani și un An Nou fericit, Angelos., 7:27 μ.μ., Παραδόθηκε"
        assertTrue(TranslationFilter.shouldTranslate(raw, TranslateLanguage.GREEK))

        val shortMsgWithStatus = "Salut, 12:00, Παραδόθηκε"
        assertTrue(TranslationFilter.shouldTranslate(shortMsgWithStatus, TranslateLanguage.GREEK))
    }

    @Test
    fun isMatchingSourceLanguage_acceptsShortLatinRomanianSentences() {
        assertTrue(
            TranslationFilter.isMatchingSourceLanguage(
                "Sunt pe drum",
                TranslateLanguage.ROMANIAN,
                emptyList()
            )
        )
        assertTrue(
            TranslationFilter.isMatchingSourceLanguage(
                "Vorbiți mai târziu",
                TranslateLanguage.ROMANIAN,
                emptyList()
            )
        )
    }

    @Test
    fun shouldTranslate_rejectsStandaloneAndCombinedStatusMarkers() {
        // Greek standalone delivery markers
        assertFalse(TranslationFilter.shouldTranslate("Διαβάστηκε", TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("Παραδόθηκε", TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("Στάλθηκε", TranslateLanguage.ROMANIAN))

        // Romanian standalone delivery markers
        assertFalse(TranslationFilter.shouldTranslate("Citit", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Citiți", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Livrat", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Trimis", TranslateLanguage.GREEK))

        // English standalone delivery markers
        assertFalse(TranslationFilter.shouldTranslate("Read", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Delivered", TranslateLanguage.GREEK))

        // Checkmarks
        assertFalse(TranslationFilter.shouldTranslate("✓✓", TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("✔", TranslateLanguage.GREEK))

        // Timestamp + status without message text
        assertFalse(TranslationFilter.shouldTranslate("6:51 μ.μ., Διαβάστηκε", TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("12:00, Παραδόθηκε", TranslateLanguage.ROMANIAN))
    }

    @Test
    fun shouldTranslate_rejectsTargetLanguageGreekMessages() {
        // When configured target is Greek, Greek messages should never be translated to Romanian in pills
        assertFalse(TranslationFilter.shouldTranslate("Σε γαμανε", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Αύριο οι δοκιμές", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Δοκιμάζω την εφαρμογή", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Σε καμω mute εδω", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Κανε ότι θες", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Στα υπόλοιπα σε αφήνω μόνο σοβαρά", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Οκοκ", TranslateLanguage.GREEK))
    }

    @Test
    fun shouldTranslate_rejectsWhatsAppSystemNotices() {
        assertFalse(TranslationFilter.shouldTranslate("Messages and calls are end-to-end encrypted", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Τα μηνύματα και οι κλήσεις είναι κρυπτογραφημένα από άκρο σε άκρο", TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("Mesajele și apelurile sunt criptate de la un capăt la altul", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Missed voice call", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Αναπάντητη φωνητική κλήση", TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("Waiting for this message. This may take a while.", TranslateLanguage.GREEK))
    }
}
