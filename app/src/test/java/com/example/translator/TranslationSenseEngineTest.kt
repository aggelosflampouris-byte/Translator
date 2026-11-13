package com.example.translator

import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSenseEngineTest {

    @Test
    fun isEligibleSourceText_acceptsRomanianMessages() {
        val candidates = emptyList<IdentifiedLanguage>()
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "La mulți ani și un An Nou fericit!",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Îngerule, ce faci;",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
    }

    @Test
    fun isEligibleSourceText_rejectsEnglishTextWhenSourceIsRomanian() {
        val candidates = listOf(IdentifiedLanguage("en", 0.98f))
        val wazeTitle = "Driving directions, live traffic & road conditions updates - Waze"
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                wazeTitle,
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )

        val wazeBody = "Realtime driving directions based on live traffic updates from Waze - Get the best route to your destination from fellow drivers"
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                wazeBody,
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
    }

    @Test
    fun isEligibleSourceText_rejectsGreekTextWhenTargetIsGreek() {
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Έχω σταθμεύσει εδώ: Γ. Μαρίνου, Ελληνικό.",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    @Test
    fun isEligibleSourceText_rejectsLatinAndGenericPrepositionsWhenSourceIsRomanian() {
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Clamavi de Profundis",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "de",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Stremio",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                listOf(IdentifiedLanguage("it", 0.75f))
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "De ce nu răspunzi?",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesExactShortGreetings() {
        assertEquals(
            "Χρόνια πολλά και ευτυχισμένο το νέο έτος!",
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "La mulți ani și un An Nou fericit",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        assertEquals(
            "Καλημέρα!",
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Bună dimineața!",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        assertEquals(
            "Καλησπέρα!",
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Bună seara!",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        assertEquals(
            "Ευχαριστώ!",
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Mersi",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        assertEquals(
            "Bună dimineața!",
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Καλημέρα",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN
            )
        )
        assertEquals(
            "Ce faci, prietene?",
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Τι κάνεις φίλε μου;",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN
            )
        )
        assertEquals(
            "Totul e bine?",
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Όλα καλά;",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN
            )
        )
    }

    // Por qué: Regresión Q1 y Q4 — frases largas que contienen "μαλάκα", "mersi" o "scuze" nunca deben
    // ser colapsadas ni descartadas por la tabla de modismos ni por el post-procesado.
    @Test
    fun longSentencesWithSlangOrThanks_areNeverCollapsedOrOverwritten() {
        // Contains "mersi" inside a real sentence -> must NOT collapse to "Ευχαριστώ!"
        assertNull(
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Mersi pentru ajutor, ne vedem mâine la birou",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        // Contains "scuze" inside a real sentence -> must NOT collapse to "Συγγνώμη!"
        assertNull(
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Scuze că întârzii zece minute",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        // Contains "μαλάκα" inside a longer Greek question -> must NOT be replaced with "Prostule!"
        assertNull(
            TranslationSenseEngine.resolveIdiomPreTranslation(
                "Έλα ρε μαλάκα τι ώρα θα έρθεις σήμερα;",
                TranslateLanguage.GREEK,
                TranslateLanguage.ROMANIAN
            )
        )
        val preservedQuestion = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Έλα ρε μαλάκα τι ώρα θα έρθεις σήμερα;",
            translatedText = "Hai mă prostule la ce oră vii azi",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Hai mă prostule la ce oră vii azi?", preservedQuestion)
    }

    // Por qué: Regresión Q2 — referencias legítimas a una empresa ("companiei") no se reescriben como "παρέας" ni "grupului"
    @Test
    fun legitimateBusinessWords_areNotRewrittenToFriendGroup() {
        val roToEl = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Directorul companiei a semnat contractul.",
            translatedText = "Ο διευθυντής της εταιρείας υπέγραψε τη σύμβαση.",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertEquals("Ο διευθυντής της εταιρείας υπέγραψε τη σύμβαση.", roToEl)

        val elToRo = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Η εφαρμογή της εταιρείας λειτουργεί σωστά.",
            translatedText = "Aplicația companiei funcționează corect.",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Aplicația companiei funcționează corect.", elToRo)
    }

    @Test
    fun applyPostTranslationSenseLogic_correctsBirthdayMistranslationAndVocatives() {
        val corrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "La mulți ani și un An Nou fericit, Angelos.",
            translatedText = "Ευτυχισμένα γενέθλια και ένα ευτυχισμένο νέο έτος, ο Άγγελος.",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertFalse(corrected.contains("γενέθλια"))
        assertTrue(corrected.contains("Χρόνια πολλά"))
        assertFalse(corrected.contains("ο Άγγελος"))

        val roCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Πως εισαι φιλε μου Γιαννη",
            translatedText = "Cum esti prietenul meu Giannis",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Cum ești, prietene Giannis?", roCorrected)

        val elCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Cum esti prietenul meu Giannis?",
            translatedText = "Πώς είσαι ο φίλος μου Γιάννης;",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertEquals("Πώς είσαι φίλε μου Γιάννη;", elCorrected)

        val anteFixed = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Άντε φύγε τώρα",
            translatedText = "Ante pleacă acum",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Hai pleacă acum", anteFixed)
    }

    @Test
    fun isMessageBubble_acceptsIncomingAndOutgoingBubblesAndRejectsDatePills() {
        val screenWidth = 1080
        assertTrue(TranslationSenseEngine.isMessageBubble(36, 750, screenWidth))
        assertTrue(TranslationSenseEngine.isMessageBubble(340, 1036, screenWidth))
        assertTrue(TranslationSenseEngine.isMessageBubble(500, 1036, screenWidth))
        assertTrue(TranslationSenseEngine.isMessageBubble(650, 1036, screenWidth))
        assertTrue(TranslationSenseEngine.isMessageBubble(700, 800, screenWidth))
        assertFalse(TranslationSenseEngine.isMessageBubble(450, 630, screenWidth))
    }

    @Test
    fun isMessageBubble_supportsLeftStreamChatAlignment() {
        val screenWidth = 1080
        // Discord/Slack messages indent ~160-340px past user avatar
        assertTrue(TranslationSenseEngine.isMessageBubble(180, 900, screenWidth, ChatAlignmentMode.LEFT_STREAM_CHAT))
        assertTrue(TranslationSenseEngine.isMessageBubble(330, 820, screenWidth, ChatAlignmentMode.LEFT_STREAM_CHAT))
        // Right-aligned chrome / centered tags are rejected in LEFT_STREAM_CHAT
        assertFalse(TranslationSenseEngine.isMessageBubble(500, 1020, screenWidth, ChatAlignmentMode.LEFT_STREAM_CHAT))
    }

    @Test
    fun isEligibleSourceText_strictlyRejectsEnglishJobOfferWhenSourceIsRomanian() {
        val jobOfferEnglish = """
            Game Presenters with Greek Language
            No experience Needed
            Good knowledge (written and speaking) of Greek Language
            900€ Monthly Salary (net) (4500 RON)
            90€ performance bonus (450 RON)
            120€ meal tickets (600 RON)
            300€ relocation bonus (One time payment)
            Nightshift bonus (25%), overtime (75%), bank holidays(100%) weekends(5%).
            Public transportation subscription
            Unidentified period contract
            Christmas, Easter bonus
            Private Clinic Medical Subscription
            3 weeks paid training
            Team activities
        """.trimIndent()

        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                jobOfferEnglish,
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    @Test
    fun isEligibleSourceText_acceptsShortUnaccentedRomanianChatWords() {
        val shortRomanianMessages = listOf("Neata", "Vii?", "Suna", "Pleci", "Ajungem", "Serios")
        for (msg in shortRomanianMessages) {
            assertTrue(
                "Expected short Romanian chat message '$msg' to be eligible",
                TranslationSenseEngine.isEligibleSourceText(
                    msg,
                    TranslateLanguage.ROMANIAN,
                    TranslateLanguage.GREEK,
                    identifiedLang = null
                )
            )
        }
    }

    @Test
    fun isEligibleSourceText_acceptsUnaccentedRomanianEvenWhenMlKitGuessesCloseLanguages() {
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "buna ziua",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                identifiedLang = "da"
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "unde esti?",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                identifiedLang = "et"
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "ce faci?",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                identifiedLang = "it"
            )
        )
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Take care with the pot and drum",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                identifiedLang = "en"
            )
        )
    }
}
