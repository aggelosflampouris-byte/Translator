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

    // Por qué: Permite usar el traductor integrado de Android 12+ cuando el fabricante incluye el par en el dispositivo.
    const val KEY_PREFER_SYSTEM_TRANSLATOR = "prefer_system_translator"

    // Por qué: Límite máximo de términos aprendidos incluidos como preferencia por solicitud.
    const val MAX_PREFERRED_TERMS = 8

    // Por qué: Permite al usuario activar o desactivar el aprendizaje local a partir de sus correcciones
    const val KEY_LEARNING_ENABLED = "adaptive_learning_enabled"

    // Por qué: Controla si la app busca actualizaciones en GitHub al abrirse
    const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"

    // Por qué: Minutos de inactividad sin usar apps de chat antes de pausar automáticamente para ahorrar batería (0 = desactivado)
    const val KEY_IDLE_AUTO_PAUSE_MINUTES = "idle_auto_pause_minutes"
    const val DEFAULT_IDLE_AUTO_PAUSE_MINUTES = 15

    // Por qué: Conjunto de paquetes de apps de mensajería habilitados dinámicamente para traducción en pantalla
    const val KEY_ENABLED_PACKAGES = "enabled_target_packages"

    // Por qué: Tema visual de las burbujas traducidas para integrarse de forma natural en cualquier app
    const val KEY_OVERLAY_THEME = "overlay_theme"
    const val OVERLAY_THEME_AUTO = "AUTO"
    const val OVERLAY_THEME_WHATSAPP = "WHATSAPP"
    const val OVERLAY_THEME_LIGHT = "LIGHT"

    // Por qué: Permite seleccionar inglés, griego o rumano para la interfaz interna de la app o seguir el idioma del sistema.
    const val KEY_APP_LANGUAGE = "app_language"
    const val APP_LANGUAGE_SYSTEM = "system"
    val AVAILABLE_APP_LANGUAGES = listOf(
        APP_LANGUAGE_SYSTEM,
        TranslateLanguage.ENGLISH,
        TranslateLanguage.GREEK,
        TranslateLanguage.ROMANIAN
    )

    fun formatLanguageDisplayName(
        code: String,
        displayLocale: java.util.Locale = java.util.Locale.getDefault()
    ): String {
        return java.util.Locale.forLanguageTag(code).getDisplayLanguage(displayLocale).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(displayLocale) else it.toString()
        }
    }
}

