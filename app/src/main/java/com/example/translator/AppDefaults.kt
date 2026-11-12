package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage

object AppDefaults {
    const val SOURCE_LANGUAGE = TranslateLanguage.ROMANIAN
    const val TARGET_LANGUAGE = TranslateLanguage.GREEK
    // Por qué: Lista estricta de idiomas disponibles y soportados por el motor de traducción bidireccional
    val AVAILABLE_LANGUAGES = listOf(
        TranslateLanguage.ROMANIAN,
        TranslateLanguage.GREEK,
        TranslateLanguage.ENGLISH
    )
    const val PREFS_NAME = "translator_prefs"
    const val KEY_SOURCE_LANGUAGE = "source_language"
    const val KEY_TARGET_LANGUAGE = "target_language"
    const val KEY_KEYBOARD_ENABLED = "keyboard_translator_enabled"
    
    // Por qué: Bandera para activar o desactivar la aplicación del glosario aprendido y validación contextual
    const val KEY_AI_ENABLED = "ai_translation_enabled"

    // Por qué: Límites de contexto conversacional y términos aprendidos por inferencia
    const val MAX_CONTEXT_MESSAGES = 3
    const val MAX_PREFERRED_TERMS = 8

    // Por qué: Permite al usuario activar o desactivar el aprendizaje local a partir de sus correcciones
    const val KEY_LEARNING_ENABLED = "adaptive_learning_enabled"
    
    // Por qué: Modos de interacción: TAP para traducir mensajes específicos al tocarlos, ALL para overlay general
    const val KEY_TRANSLATE_MODE = "translate_mode"
    const val MODE_TAP = "TAP"
    const val MODE_ALL = "ALL"

    // Por qué: Tipo de interfaz de control preferida por el usuario: NOTIFICATION (menú sin obstrucción en la barra de notificaciones) o BUBBLE (esfera flotante)
    const val KEY_CONTROL_INTERFACE = "control_interface"
    const val INTERFACE_NOTIFICATION = "NOTIFICATION"
    const val INTERFACE_BUBBLE = "BUBBLE"

    // Por qué: Conjunto de paquetes de apps de mensajería habilitados dinámicamente para traducción en pantalla
    const val KEY_ENABLED_PACKAGES = "enabled_target_packages"

    // Por qué: Tema visual de las burbujas traducidas para integrarse de forma natural en cualquier app
    const val KEY_OVERLAY_THEME = "overlay_theme"
    const val OVERLAY_THEME_AUTO = "AUTO"
    const val OVERLAY_THEME_WHATSAPP = "WHATSAPP"
    const val OVERLAY_THEME_LIGHT = "LIGHT"
}

