package com.example.translator

import android.content.Context
import android.content.SharedPreferences

/**
 * Mantiene sincronizada la configuración activa del servicio de accesibilidad desde `SharedPreferences`.
 *
 * Por qué: Centraliza lectura inicial y cambios en caliente fuera de `TranslationAccessibilityService`.
 */
class ServiceSettingsObserver(
    private val context: Context,
    private val onEnabledPackagesChanged: (Set<String>) -> Unit,
    private val onLanguagePairChanged: (source: String, target: String) -> Unit,
    private val onKeyboardTranslatorChanged: (enabled: Boolean) -> Unit,
    private val onLearningOrGlossaryChanged: () -> Unit,
    private val onTranslationModeChanged: (mode: String) -> Unit,
    private val onOverlayThemeChanged: (theme: String) -> Unit,
    private val onIdleAutoPauseChanged: (minutes: Int) -> Unit
) {
    @Volatile var configuredSource: String = AppDefaults.SOURCE_LANGUAGE
        private set
    @Volatile var configuredTarget: String = AppDefaults.TARGET_LANGUAGE
        private set
    @Volatile var isKeyboardTranslatorEnabled: Boolean = true
        private set
    @Volatile var isGlossaryAndValidationEnabled: Boolean = true
        private set
    @Volatile var isLearningEnabled: Boolean = true
        private set
    @Volatile var translationMode: String = AppDefaults.MODE_ALL
        private set
    @Volatile var overlayTheme: String = AppDefaults.OVERLAY_THEME_AUTO
        private set
    @Volatile var idleAutoPauseMinutes: Int = AppDefaults.DEFAULT_IDLE_AUTO_PAUSE_MINUTES
        private set

    private var registeredPrefs: SharedPreferences? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            AppDefaults.KEY_ENABLED_PACKAGES -> {
                onEnabledPackagesChanged(TargetAppCatalog.getEnabledPackages(prefs, context.packageName))
            }
            AppDefaults.KEY_SOURCE_LANGUAGE -> {
                configuredSource = prefs.getString(key, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
                onLanguagePairChanged(configuredSource, configuredTarget)
            }
            AppDefaults.KEY_TARGET_LANGUAGE -> {
                configuredTarget = prefs.getString(key, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
                onLanguagePairChanged(configuredSource, configuredTarget)
            }
            AppDefaults.KEY_KEYBOARD_ENABLED -> {
                isKeyboardTranslatorEnabled = prefs.getBoolean(key, true)
                onKeyboardTranslatorChanged(isKeyboardTranslatorEnabled)
            }
            AppDefaults.KEY_AI_ENABLED -> {
                isGlossaryAndValidationEnabled = prefs.getBoolean(key, true)
                onLearningOrGlossaryChanged()
            }
            AppDefaults.KEY_LEARNING_ENABLED -> {
                isLearningEnabled = prefs.getBoolean(key, true)
                onLearningOrGlossaryChanged()
            }
            AppDefaults.KEY_TRANSLATE_MODE -> {
                translationMode = prefs.getString(key, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL
                onTranslationModeChanged(translationMode)
            }
            AppDefaults.KEY_OVERLAY_THEME -> {
                overlayTheme = prefs.getString(key, AppDefaults.OVERLAY_THEME_AUTO) ?: AppDefaults.OVERLAY_THEME_AUTO
                onOverlayThemeChanged(overlayTheme)
            }
            AppDefaults.KEY_IDLE_AUTO_PAUSE_MINUTES -> {
                idleAutoPauseMinutes = prefs.getInt(key, AppDefaults.DEFAULT_IDLE_AUTO_PAUSE_MINUTES)
                onIdleAutoPauseChanged(idleAutoPauseMinutes)
            }
        }
    }

    fun register(): Set<String> {
        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        configuredSource = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE)
            ?: AppDefaults.SOURCE_LANGUAGE
        configuredTarget = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE)
            ?: AppDefaults.TARGET_LANGUAGE
        isKeyboardTranslatorEnabled = prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, true)
        isGlossaryAndValidationEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true)
        isLearningEnabled = prefs.getBoolean(AppDefaults.KEY_LEARNING_ENABLED, true)
        translationMode = prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL)
            ?: AppDefaults.MODE_ALL
        overlayTheme = prefs.getString(AppDefaults.KEY_OVERLAY_THEME, AppDefaults.OVERLAY_THEME_AUTO)
            ?: AppDefaults.OVERLAY_THEME_AUTO
        idleAutoPauseMinutes = prefs.getInt(
            AppDefaults.KEY_IDLE_AUTO_PAUSE_MINUTES,
            AppDefaults.DEFAULT_IDLE_AUTO_PAUSE_MINUTES
        )
        prefs.registerOnSharedPreferenceChangeListener(listener)
        registeredPrefs = prefs
        return TargetAppCatalog.getEnabledPackages(prefs, context.packageName)
    }

    fun unregister() {
        registeredPrefs?.unregisterOnSharedPreferenceChangeListener(listener)
        registeredPrefs = null
    }
}

