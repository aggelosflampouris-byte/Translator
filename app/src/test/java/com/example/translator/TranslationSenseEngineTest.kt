package com.example.translator

import android.graphics.Rect
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
                "La mulți ani și un An Nou fericit, Angelos.",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Angel, ai fost futut de negri.",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Ingerule, ce faci;",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
    }

    @Test
    fun isEligibleSourceText_rejectsEnglishTextWhenSourceIsRomanian() {
        val candidates = listOf(
            IdentifiedLanguage("en", 0.98f)
        )
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
                "Clamavi de Profundis",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                listOf(IdentifiedLanguage("la", 0.88f))
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
                "la",
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
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "ce faci de mâncare",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    @Test
    fun isIncomingMessage_identifiesLeftAndRightMessages() {
        val screenWidth = 1080
        assertTrue(TranslationSenseEngine.isIncomingMessage(36, 750, screenWidth))
        assertFalse(TranslationSenseEngine.isIncomingMessage(380, 1040, screenWidth))
        assertFalse(TranslationSenseEngine.isIncomingMessage(420, 660, screenWidth))
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesHolidayGreetingsAccurately() {
        val greetingWithAddress = TranslationSenseEngine.resolveIdiomPreTranslation(
            "La mulți ani și un An Nou fericit, Angelos.",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(greetingWithAddress)
        assertTrue(greetingWithAddress!!.contains("Χρόνια πολλά"))
        assertTrue(greetingWithAddress.contains("ευτυχισμένο το νέο έτος"))
        assertTrue(greetingWithAddress.contains("Άγγελε") || greetingWithAddress.contains("Άγγελος"))

        val simpleGreeting = TranslationSenseEngine.resolveIdiomPreTranslation(
            "La mulți ani și un An Nou fericit",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertEquals("Χρόνια πολλά και ευτυχισμένο το νέο έτος!", simpleGreeting)
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesSlangAndColloquialPhrases() {
        val slangTranslation = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Angel, ai fost futut de negri.",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(slangTranslation)
        assertTrue(
            slangTranslation!!.contains("μαύροι") || slangTranslation.contains("από μαύρους")
        )
        assertTrue(
            slangTranslation.contains("πήδηξαν") || slangTranslation.contains("γάμησαν")
        )
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesConversationalQuestionsAndStatements() {
        val questionResult = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Îngerule, ce faci?",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(questionResult)
        assertEquals("Άγγελε, τι κάνεις;", questionResult)

        val statementResult = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Îngerule, totul e bine.",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(statementResult)
        assertEquals("Άγγελε, όλα είναι καλά.", statementResult)
    }

    @Test
    fun resolveIdiomPreTranslation_returnsNullForRegularSentences() {
        val result = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Autobuzul ajunge la ora cinci dupa-amiaza la statie.",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNull(result)
    }

    @Test
    fun applyPostTranslationSenseLogic_correctsBirthdayMistranslationForNewYear() {
        val corrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "La mulți ani și un An Nou fericit, Angelos.",
            translatedText = "Ευτυχισμένα γενέθλια και ένα ευτυχισμένο νέο έτος, ο Άγγελος.",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertFalse(corrected.contains("γενέθλια"))
        assertTrue(corrected.contains("Χρόνια πολλά"))
        assertFalse(corrected.contains("ο Άγγελος"))
    }

    @Test
    fun applyPostTranslationSenseLogic_correctsNonsensicalColorTranslations() {
        val corrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Angel, ai fost futut de negri.",
            translatedText = "Άγγελος, είσαι πατήσαμε με μαύρο χρώμα.",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertFalse(corrected.contains("μαύρο χρώμα"))
        assertFalse(corrected.contains("είσαι πατήσαμε"))
        assertTrue(corrected.contains("μαύροι") || corrected.contains("από μαύρους"))
    }

    @Test
    fun isIncomingMessage_rejectsWideOutgoingMessages() {
        val screenWidth = 1080
        // Outgoing message bubble: "Cum esti prietenul meu Giannis" (left ~340, right ~1036)
        assertFalse(TranslationSenseEngine.isIncomingMessage(340, 1036, screenWidth))
        // Long outgoing message expanding left (left ~220, right ~1045)
        assertFalse(TranslationSenseEngine.isIncomingMessage(220, 1045, screenWidth))
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesGreekToRomanianConversationalExpressions() {
        val result1 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Πως εισαι φιλε μου Γιαννη",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result1)
        assertEquals("Cum ești, prietene Giannis?", result1)

        val result2 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Τι κανεις φιλε μου",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result2)
        assertEquals("Ce faci, prietene?", result2)

        val result3 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Καλημερα",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result3)
        assertEquals("Bună dimineața!", result3)

        val result4 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Χρονια πολλα και καλη χρονια, Γιαννη",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result4)
        assertEquals("La mulți ani și un An Nou fericit, Giannis!", result4)
    }

    @Test
    fun applyPostTranslationSenseLogic_correctsVocativeForms() {
        // Greek -> Romanian: "prietenul meu" -> "prietene" in direct address
        val roCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Πως εισαι φιλε μου Γιαννη",
            translatedText = "Cum esti prietenul meu Giannis",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Cum ești, prietene Giannis?", roCorrected)

        // Romanian -> Greek: "ο φίλος μου Γιάννης" -> "φίλε μου Γιάννη"
        val elCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Cum esti prietenul meu Giannis?",
            translatedText = "Πώς είσαι ο φίλος μου Γιάννης;",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertEquals("Πώς είσαι φίλε μου Γιάννη;", elCorrected)
    }

    @Test
    fun isMessageBubble_acceptsIncomingAndOutgoingBubblesAndRejectsDatePills() {
        val screenWidth = 1080
        // Incoming message (left margin)
        assertTrue(TranslationSenseEngine.isMessageBubble(36, 750, screenWidth))
        // Outgoing message (wide, e.g. "Cum ești prietenul meu Giannis")
        assertTrue(TranslationSenseEngine.isMessageBubble(340, 1036, screenWidth))
        // Outgoing message (medium, e.g. "Salut, Giannis!")
        assertTrue(TranslationSenseEngine.isMessageBubble(500, 1036, screenWidth))
        // Outgoing message (short, e.g. "Ce faci?")
        assertTrue(TranslationSenseEngine.isMessageBubble(650, 1036, screenWidth))
        // Centered date pill (e.g. "Σήμερα")
        assertFalse(TranslationSenseEngine.isMessageBubble(450, 630, screenWidth))
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesRomanianSentMessagesToGreek() {
        val result1 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Cum ești prietenul meu Giannis",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result1)
        assertEquals("Πώς είσαι, φίλε μου Γιάννη;", result1)

        val result2 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Salut, Giannis!",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result2)
        assertEquals("Γεια σου, Γιάννη!", result2)

        val result3 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Ce faci?",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result3)
        assertEquals("Τι κάνεις;", result3)
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesGreekSlangAndCurses() {
        val result1 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Άντε γαμήσου Γιάννη",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result1)
        assertEquals("Du-te dracului, Giannis!", result1)

        val result2 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Άντε ρε μαλάκα",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result2)
        assertEquals("Hai mă, prostule!", result2)

        val result3 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Γαμώτο",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result3)
        assertEquals("La dracu!", result3)

        val result4 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Σε πήδηξαν μαύροι",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result4)
        assertEquals("Te-au futut negrii!", result4)
    }

    @Test
    fun isEligibleSourceText_acceptsMesajAndBunaSeara() {
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Mesaj",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Bună seara!",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesMesajAndBunaSeara() {
        val result1 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Mesaj",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result1)
        assertEquals("Μήνυμα", result1)

        val result2 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Bună seara!",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result2)
        assertEquals("Καλησπέρα!", result2)

        val result3 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Scuze",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result3)
        assertEquals("Συγγνώμη!", result3)

        val result4 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Mersi",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result4)
        assertEquals("Ευχαριστώ!", result4)
    }

    @Test
    fun applyPostTranslationSenseLogic_fixesGreekAnteAndCurses() {
        val corrected1 = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Άντε γαμήσου Γιάννη",
            translatedText = "Ante giannis",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Du-te dracului, Giannis!", corrected1)

        val corrected2 = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Άντε φύγε τώρα",
            translatedText = "Ante pleacă acum",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Hai pleacă acum", corrected2)
    }
}

