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
}
