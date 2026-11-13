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
        assertTrue(TranslationFilter.shouldTranslate("La mulți ani și un An Nou fericit, Angelos.", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
        assertTrue(TranslationFilter.shouldTranslate("Ce faci prietene?", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
        assertTrue(TranslationFilter.shouldTranslate("se depun la secretariat urmatoarele: Cerere Borderou completat și semnat", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
    }

    // Por qué: Verifica que los textos en inglés (como ofertas de trabajo o mensajes en inglés)
    // sean estrictamente ignorados cuando el idioma de origen configurado es rumano.
    @Test
    fun shouldTranslate_rejectsEnglishMessagesWhenSourceIsRomanian() {
        assertFalse(TranslationFilter.shouldTranslate("How are you doing today?", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("Game Presenters with Greek Language. No experience Needed. Good knowledge.", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("900€ Monthly Salary (net) (4500 RON). 300€ relocation bonus.", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
    }

    @Test
    fun cleanMessageText_stripsTrailingTimestamps() {
        val raw = "La mulți ani și un An Nou fericit,\nAngelos.\n7:27 μ.μ."
        val cleaned = TranslationFilter.cleanMessageText(raw)
        assertEquals("La mulți ani și un An Nou fericit,\nAngelos.", cleaned)
    }

    @Test
    fun shouldTranslate_detectsTargetGreekScript() {
        assertFalse(TranslationFilter.shouldTranslate("Εδω παρκαρα εγβ", TranslateLanguage.GREEK))
        assertTrue(TranslationFilter.shouldTranslate("La mulți ani", TranslateLanguage.GREEK))
    }

    @Test
    fun isEligibleSourceText_matchesRomanianWithDiacritics() {
        val result = TranslationSenseEngine.isEligibleSourceText(
            "La mulți ani și un An Nou fericit",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK,
            emptyList()
        )
        assertTrue(result)
    }

    @Test
    fun isEligibleSourceText_rejectsMismatchedCandidates() {
        val candidates = listOf(
            IdentifiedLanguage("en", 0.95f),
            IdentifiedLanguage("ro", 0.05f)
        )
        val result = TranslationSenseEngine.isEligibleSourceText(
            "Hello my friend how are you",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK,
            candidates
        )
        assertFalse(result)
    }

    @Test
    fun isEligibleSourceText_acceptsHighConfidenceCandidate() {
        val candidates = listOf(
            IdentifiedLanguage("ro", 0.85f),
            IdentifiedLanguage("it", 0.15f)
        )
        val result = TranslationSenseEngine.isEligibleSourceText(
            "La multi ani si un An Nou fericit",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK,
            candidates
        )
        assertTrue(result)
    }

    @Test
    fun isEligibleSourceText_matchesRomanianWithoutDiacriticsWhenCommonWordsPresent() {
        val result = TranslationSenseEngine.isEligibleSourceText(
            "La multi ani si un An Nou fericit, Angelos",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK,
            emptyList()
        )
        assertTrue(result)

        val result2 = TranslationSenseEngine.isEligibleSourceText(
            "Ce faci prietene?",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK,
            emptyList()
        )
        assertTrue(result2)
    }

    @Test
    fun cleanMessageText_stripsDeliveryStatusAndGreekTimestamps() {
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
    fun isEligibleSourceText_acceptsShortLatinRomanianSentences() {
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Sunt pe drum",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Vorbiți mai târziu",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
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

    // Por qué: Asegura que respuestas de error o negativas conversacionales ("Error: No message was provided")
    // sean descartadas inmediatamente y nunca se muestren como globos de traducción.
    @Test
    fun shouldTranslate_rejectsErrorAndRefusalMessages() {
        assertFalse(TranslationFilter.shouldTranslate("[Error: No message was provided to translate. Please provide the Romanian text you would like translated into Greek.]", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Error: Something went wrong", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("[Error: Empty prompt]", TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("You are an expert chat translator between Greek and Romanian.", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Translate this informal WhatsApp chat message from Romanian to Greek", TranslateLanguage.GREEK))
    }

    @Test
    fun cleanMessageText_andShouldTranslate_handlesTelegramDiscordAndMessengerMetadata() {
        assertEquals("Ne vedem diseară", TranslationFilter.cleanMessageText("Ne vedem diseară, 14:32, edited"))
        assertEquals("Salutare tuturor", TranslationFilter.cleanMessageText("Salutare tuturor (edited)"))
        assertEquals("Ajung imediat", TranslationFilter.cleanMessageText("Ajung imediat, Seen"))
        assertEquals(
            "Bună ziua mâine am o lecție în orele de dimineață. Apoi vom merge cu tovarășul la cafenea de lângă Mojo și apoi vom mânca în piață. Care va veni?",
            TranslationFilter.cleanMessageText(
                "Εσείς στείλατε: Bună ziua mâine am o lecție în orele de dimineață. Apoi vom merge cu tovarășul la cafenea de lângă Mojo și apoi vom mânca în piață. Care va veni?, Εστάλη"
            )
        )
        assertTrue(
            TranslationFilter.shouldTranslate(
                "Εσείς στείλατε: Bună ziua mâine am o lecție în orele de dimineață. Apoi vom merge cu tovarășul la cafenea de lângă Mojo și apoi vom mânca în piață. Care va veni?, Εστάλη",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN
            )
        )

        assertEquals(
            "Bună ziua mâine am o lecție",
            TranslationFilter.cleanMessageText("Εσείς στείλατε Bună ziua mâine am o lecție, Εστάλη")
        )
        // Por qué: "Ai trimis" sin dos puntos en rumano es una frase normal del chat ("¿Enviaste?") y no debe recortarse.
        assertEquals(
            "Ai trimis pozele?",
            TranslationFilter.cleanMessageText("Ai trimis pozele?")
        )

        assertFalse(TranslationFilter.shouldTranslate("Today at 2:30 PM", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("ΤΕΤ ΣΤΙΣ 6:02 Μ.Μ.", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Ενεργός/ή τώρα", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Φωνητική κλήση", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Conversation information", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("Απαντήσατε στο χρήστη Λευτέρης", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Εστάλη", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Απεστάλη", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("Εθεάθη", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN))
        assertFalse(TranslationFilter.shouldTranslate("Seen", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("Liked a message", TranslateLanguage.GREEK))
        assertFalse(TranslationFilter.shouldTranslate("BOT", TranslateLanguage.GREEK))
    }

    @Test
    fun cleanMessageText_andShouldTranslate_handlesGreekLocaleMessengerWrappersAndUnaccentedRomanian() {
        assertEquals(
            "Ce faci?",
            TranslationFilter.cleanMessageText("Απαντήσατε στο χρήστη Λευτέρης: Ce faci?")
        )
        assertTrue(
            TranslationFilter.shouldTranslate(
                "Απαντήσατε στο χρήστη Λευτέρης: Ce faci?",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN
            )
        )

        val wrappedIncoming = "Λευτέρης Φλαμπούρης έστειλε: Bună ziua, ΤΕΤ ΣΤΙΣ 6:12 Μ.Μ., Απεστάλη, Πατήστε δύο φορές για απάντηση"
        assertEquals("Bună ziua", TranslationFilter.cleanMessageText(wrappedIncoming))
        assertTrue(
            TranslationFilter.shouldTranslate(
                wrappedIncoming,
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN
            )
        )

        assertEquals(
            "Salut prietene",
            TranslationFilter.cleanMessageText("Λευτέρης Φλαμπούρης: Salut prietene")
        )
        assertTrue(
            TranslationFilter.shouldTranslate(
                "Λευτέρης Φλαμπούρης: Salut prietene",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN
            )
        )

        assertTrue(
            TranslationFilter.shouldTranslate(
                "Ajung imediat la cafenea",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN,
                deferUnknownLatinToMlKit = true
            )
        )
        assertFalse(
            TranslationFilter.shouldTranslate(
                "How are you doing today?",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN,
                deferUnknownLatinToMlKit = true
            )
        )

        // Messenger Greek-locale comma/newline sender prefixes before Latin body (e.g. "ΑΓΓΕΛΟΣ, ...")
        assertEquals(
            "bună ziua mâine am un curs dimineața.",
            TranslationFilter.cleanMessageText(
                "ΑΓΓΕΛΟΣ, bună ziua mâine am un curs dimineața., 1:25 μ.μ., Απεστάλη, Πατήστε δύο φορές για να απαντήσετε"
            )
        )
        assertEquals(
            "Bună ziua mâine am un curs",
            TranslationFilter.cleanMessageText("ΑΓΓΕΛΟΣ\nBună ziua mâine am un curs")
        )
        assertEquals(
            "Salut prietene",
            TranslationFilter.cleanMessageText("Λευτέρης Φλαμπούρης, Salut prietene")
        )
        assertEquals(
            "Ce faci?",
            TranslationFilter.cleanMessageText("ΑΓΓΕΛΟΣ, Απαντήσατε στον χρήστη Λευτέρης, Ce faci?")
        )
        assertEquals(
            "Bună ziua",
            TranslationFilter.cleanMessageText("Λευτέρης Φλαμπούρης έστειλε, Bună ziua")
        )

        // Ensure vocatives, pronouns, short uppercase words, and real English sentences are preserved
        assertEquals("Αγγελε, ce mai faci?", TranslationFilter.cleanMessageText("Αγγελε, ce mai faci?"))
        assertEquals("Tu, ce mai faci?", TranslationFilter.cleanMessageText("Tu, ce mai faci?"))
        assertEquals("ΝΑΙ, mergem", TranslationFilter.cleanMessageText("ΝΑΙ, mergem"))
        assertTrue(
            TranslationFilter.shouldTranslate(
                "I replied to your message yesterday",
                TranslateLanguage.GREEK,
                TranslateLanguage.ENGLISH
            )
        )
    }
}
