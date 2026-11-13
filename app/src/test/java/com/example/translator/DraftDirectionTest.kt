package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftDirectionTest {

    @Test
    fun resolveDraftDirection_autoDetectsScriptForGreekPairs() {
        assertEquals(
            TranslateLanguage.GREEK to TranslateLanguage.ROMANIAN,
            DraftTranslationController.resolveDraftDirection(
                text = "Καλημέρα φίλε",
                manualTarget = null,
                configuredSource = TranslateLanguage.ROMANIAN,
                configuredTarget = TranslateLanguage.GREEK
            )
        )
        assertEquals(
            TranslateLanguage.ROMANIAN to TranslateLanguage.GREEK,
            DraftTranslationController.resolveDraftDirection(
                text = "Bună dimineața",
                manualTarget = null,
                configuredSource = TranslateLanguage.ROMANIAN,
                configuredTarget = TranslateLanguage.GREEK
            )
        )
    }

    @Test
    fun resolveDraftDirection_usesMlKitHintForNonGreekPairsWhenUnpinned() {
        assertEquals(
            TranslateLanguage.ENGLISH to TranslateLanguage.ROMANIAN,
            DraftTranslationController.resolveDraftDirection(
                text = "Hello friend",
                manualTarget = null,
                configuredSource = TranslateLanguage.ENGLISH,
                configuredTarget = TranslateLanguage.ROMANIAN,
                detectedLang = TranslateLanguage.ENGLISH
            )
        )
        assertEquals(
            TranslateLanguage.ROMANIAN to TranslateLanguage.ENGLISH,
            DraftTranslationController.resolveDraftDirection(
                text = "Salut prietene",
                manualTarget = null,
                configuredSource = TranslateLanguage.ENGLISH,
                configuredTarget = TranslateLanguage.ROMANIAN,
                detectedLang = TranslateLanguage.ROMANIAN
            )
        )
    }

    @Test
    fun resolveDraftDirection_respectsManualOverride() {
        assertEquals(
            TranslateLanguage.ROMANIAN to TranslateLanguage.ENGLISH,
            DraftTranslationController.resolveDraftDirection(
                text = "Hello friend",
                manualTarget = TranslateLanguage.ENGLISH,
                configuredSource = TranslateLanguage.ENGLISH,
                configuredTarget = TranslateLanguage.ROMANIAN,
                detectedLang = TranslateLanguage.ENGLISH
            )
        )
    }
}

