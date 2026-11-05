package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage

object AppDefaults {
    const val SOURCE_LANGUAGE = TranslateLanguage.ROMANIAN
    const val TARGET_LANGUAGE = TranslateLanguage.GREEK
    const val PREFS_NAME = "translator_prefs"
    const val KEY_SOURCE_LANGUAGE = "source_language"
    const val KEY_TARGET_LANGUAGE = "target_language"
    const val KEY_KEYBOARD_ENABLED = "keyboard_translator_enabled"
}

