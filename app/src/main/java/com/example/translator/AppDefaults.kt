package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage

object AppDefaults {
    const val SOURCE_LANGUAGE = TranslateLanguage.ROMANIAN
    const val TARGET_LANGUAGE = TranslateLanguage.GREEK
    const val PREFS_NAME = "translator_prefs"
    const val KEY_SOURCE_LANGUAGE = "source_language"
    const val KEY_TARGET_LANGUAGE = "target_language"
    const val KEY_KEYBOARD_ENABLED = "keyboard_translator_enabled"
    
    // Por qué: Bandera para activar o desactivar la traducción neuronal avanzada en el dispositivo
    const val KEY_AI_ENABLED = "ai_translation_enabled"
    const val KEY_CUSTOM_MODEL_PATH = "custom_model_path"
    
    // Por qué: Modos de interacción: TAP para traducir mensajes específicos al tocarlos, ALL para overlay general
    const val KEY_TRANSLATE_MODE = "translate_mode"
    const val MODE_TAP = "TAP"
    const val MODE_ALL = "ALL"

    // Por qué: Tipo de interfaz de control preferida por el usuario: NOTIFICATION (menú sin obstrucción en la barra de notificaciones) o BUBBLE (esfera flotante)
    const val KEY_CONTROL_INTERFACE = "control_interface"
    const val INTERFACE_NOTIFICATION = "NOTIFICATION"
    const val INTERFACE_BUBBLE = "BUBBLE"
}

