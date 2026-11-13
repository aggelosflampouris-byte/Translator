package com.example.translator

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot inmutable de las preferencias de usuario de OverChat.
 */
data class TranslatorPreferences(
    val sourceLanguage: String = AppDefaults.SOURCE_LANGUAGE,
    val targetLanguage: String = AppDefaults.TARGET_LANGUAGE,
    val translationMode: String = AppDefaults.MODE_ALL,
    val keyboardTranslatorEnabled: Boolean = true,
    val glossaryEnabled: Boolean = true,
    val learningEnabled: Boolean = true,
    val controlInterface: String = AppDefaults.INTERFACE_NOTIFICATION,
    val autoCheckUpdates: Boolean = true,
    val idleAutoPauseMinutes: Int = AppDefaults.DEFAULT_IDLE_AUTO_PAUSE_MINUTES,
    val enabledPackages: Set<String> = emptySet(),
    val overlayTheme: String = AppDefaults.OVERLAY_THEME_AUTO
)

/**
 * Repositorio reactivo sobre `SharedPreferences` para leer y actualizar ajustes desde ViewModel o servicios.
 */
class TranslatorPreferencesRepository(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(readSnapshot())
    val preferences: StateFlow<TranslatorPreferences> = _preferences.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _preferences.value = readSnapshot()
    }

    init {
        ensureLanguageDefaultsPersisted(_preferences.value)
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun refresh() {
        _preferences.value = readSnapshot()
    }

    fun setSourceLanguage(selected: String) {
        val current = _preferences.value
        val editor = prefs.edit().putString(AppDefaults.KEY_SOURCE_LANGUAGE, selected)
        if (selected == current.targetLanguage) {
            editor.putString(AppDefaults.KEY_TARGET_LANGUAGE, current.sourceLanguage)
        }
        editor.apply()
    }

    fun setTargetLanguage(selected: String) {
        val current = _preferences.value
        val editor = prefs.edit().putString(AppDefaults.KEY_TARGET_LANGUAGE, selected)
        if (selected == current.sourceLanguage) {
            editor.putString(AppDefaults.KEY_SOURCE_LANGUAGE, current.targetLanguage)
        }
        editor.apply()
    }

    fun swapLanguages() {
        val current = _preferences.value
        prefs.edit()
            .putString(AppDefaults.KEY_SOURCE_LANGUAGE, current.targetLanguage)
            .putString(AppDefaults.KEY_TARGET_LANGUAGE, current.sourceLanguage)
            .apply()
    }

    fun setTranslationMode(mode: String) {
        prefs.edit().putString(AppDefaults.KEY_TRANSLATE_MODE, mode).apply()
    }

    fun setGlossaryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(AppDefaults.KEY_AI_ENABLED, enabled).apply()
    }

    fun setKeyboardTranslatorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, enabled).apply()
    }

    fun setLearningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(AppDefaults.KEY_LEARNING_ENABLED, enabled).apply()
    }

    fun setControlInterface(controlInterface: String) {
        prefs.edit().putString(AppDefaults.KEY_CONTROL_INTERFACE, controlInterface).apply()
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(AppDefaults.KEY_AUTO_CHECK_UPDATES, enabled).apply()
    }

    fun setIdleAutoPauseEnabled(enabled: Boolean) {
        val minutes = if (enabled) AppDefaults.DEFAULT_IDLE_AUTO_PAUSE_MINUTES else 0
        prefs.edit().putInt(AppDefaults.KEY_IDLE_AUTO_PAUSE_MINUTES, minutes).apply()
    }

    fun setOverlayTheme(theme: String) {
        prefs.edit().putString(AppDefaults.KEY_OVERLAY_THEME, theme).apply()
    }

    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun readSnapshot(): TranslatorPreferences {
        val available = AppDefaults.AVAILABLE_LANGUAGES
        val savedSource = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE)
            ?: AppDefaults.SOURCE_LANGUAGE
        val savedTarget = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE)
            ?: AppDefaults.TARGET_LANGUAGE
        return TranslatorPreferences(
            sourceLanguage = if (available.contains(savedSource)) savedSource else AppDefaults.SOURCE_LANGUAGE,
            targetLanguage = if (available.contains(savedTarget)) savedTarget else AppDefaults.TARGET_LANGUAGE,
            translationMode = prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL)
                ?: AppDefaults.MODE_ALL,
            keyboardTranslatorEnabled = prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, true),
            glossaryEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true),
            learningEnabled = prefs.getBoolean(AppDefaults.KEY_LEARNING_ENABLED, true),
            controlInterface = prefs.getString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_NOTIFICATION)
                ?: AppDefaults.INTERFACE_NOTIFICATION,
            autoCheckUpdates = prefs.getBoolean(AppDefaults.KEY_AUTO_CHECK_UPDATES, true),
            idleAutoPauseMinutes = prefs.getInt(
                AppDefaults.KEY_IDLE_AUTO_PAUSE_MINUTES,
                AppDefaults.DEFAULT_IDLE_AUTO_PAUSE_MINUTES
            ),
            enabledPackages = TargetAppCatalog.getEnabledPackages(prefs, appContext.packageName),
            overlayTheme = prefs.getString(AppDefaults.KEY_OVERLAY_THEME, AppDefaults.OVERLAY_THEME_AUTO)
                ?: AppDefaults.OVERLAY_THEME_AUTO
        )
    }

    private fun ensureLanguageDefaultsPersisted(snapshot: TranslatorPreferences) {
        val editor = prefs.edit()
        var changed = false
        if (!prefs.contains(AppDefaults.KEY_SOURCE_LANGUAGE)) {
            editor.putString(AppDefaults.KEY_SOURCE_LANGUAGE, snapshot.sourceLanguage)
            changed = true
        }
        if (!prefs.contains(AppDefaults.KEY_TARGET_LANGUAGE)) {
            editor.putString(AppDefaults.KEY_TARGET_LANGUAGE, snapshot.targetLanguage)
            changed = true
        }
        if (changed) editor.apply()
    }
}

