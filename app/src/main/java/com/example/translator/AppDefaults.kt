package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage

object AppDefaults {
    const val SOURCE_LANGUAGE = TranslateLanguage.ROMANIAN
    const val TARGET_LANGUAGE = TranslateLanguage.GREEK
    const val PREFS_NAME = "translator_prefs"
    const val KEY_SOURCE_LANGUAGE = "source_language"
    const val KEY_TARGET_LANGUAGE = "target_language"
    const val KEY_KEYBOARD_ENABLED = "keyboard_translator_enabled"
    
    // Por qué: Clave para la API gratuita de Gemini (Google AI Studio) que proporciona traducciones conversacionales precisas
    const val KEY_GEMINI_API_KEY = "gemini_api_key"
    
    // Por qué: Modos de interacción: TAP para traducir mensajes específicos al tocarlos, ALL para overlay general
    const val KEY_TRANSLATE_MODE = "translate_mode"
    const val MODE_TAP = "TAP"
    const val MODE_ALL = "ALL"
}

