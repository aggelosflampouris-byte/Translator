package com.example.translator

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * Gestiona el idioma de la interfaz interna de OverChat (Sistema, Inglés, Griego, Rumano)
 * garantizando coherencia tanto en Compose como en notificaciones, superposiciones y Toasts de servicio.
 */
object AppLocaleManager {

    fun normalizeLanguageTag(raw: String?): String {
        val normalized = raw?.trim()?.lowercase(Locale.US).orEmpty()
        return if (normalized in AppDefaults.AVAILABLE_APP_LANGUAGES) {
            normalized
        } else {
            AppDefaults.APP_LANGUAGE_SYSTEM
        }
    }

    fun nativeLanguageLabel(tag: String, systemLabel: String): String {
        return when (normalizeLanguageTag(tag)) {
            TranslateLanguage.ENGLISH -> "English"
            TranslateLanguage.GREEK -> "Ελληνικά"
            TranslateLanguage.ROMANIAN -> "Română"
            else -> systemLabel
        }
    }

    fun resolveLocale(
        languageTag: String,
        systemLocale: Locale = Resources.getSystem().configuration.locales[0]
    ): Locale {
        return when (val normalized = normalizeLanguageTag(languageTag)) {
            AppDefaults.APP_LANGUAGE_SYSTEM -> systemLocale
            else -> Locale.forLanguageTag(normalized)
        }
    }

    fun getSavedLanguageTag(context: Context): String {
        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(AppDefaults.KEY_APP_LANGUAGE)) {
            return normalizeLanguageTag(prefs.getString(AppDefaults.KEY_APP_LANGUAGE, AppDefaults.APP_LANGUAGE_SYSTEM))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales != null && !locales.isEmpty) {
                return normalizeLanguageTag(locales[0]?.language)
            }
        }
        return AppDefaults.APP_LANGUAGE_SYSTEM
    }

    fun wrapContext(base: Context): Context {
        val savedTag = getSavedLanguageTag(base)
        if (savedTag == AppDefaults.APP_LANGUAGE_SYSTEM) {
            return base
        }
        val targetLocale = resolveLocale(savedTag, base.resources.configuration.locales[0])
        Locale.setDefault(targetLocale)
        val updatedConfig = Configuration(base.resources.configuration).apply {
            setLocale(targetLocale)
            setLocales(LocaleList(targetLocale))
        }
        return base.createConfigurationContext(updatedConfig)
    }

    /**
     * Devuelve un contexto localizado activo para lectores en servicios, notificaciones, WindowManager y Toasts.
     */
    fun localizedContext(context: Context): Context = wrapContext(context)

    fun currentDisplayLocale(context: Context): Locale {
        return localizedContext(context).resources.configuration.locales[0]
    }

    fun applyAppLanguage(context: Context, languageTag: String) {
        val normalized = normalizeLanguageTag(languageTag)
        val prefs = context.applicationContext.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(AppDefaults.KEY_APP_LANGUAGE, normalized).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeList = if (normalized == AppDefaults.APP_LANGUAGE_SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(normalized)
            }
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = localeList
        } else {
            val targetLocale = resolveLocale(normalized)
            Locale.setDefault(targetLocale)
            (context as? Activity)?.recreate()
        }

        if (ServiceBridge.isNotificationActive) {
            TranslationNotificationManager.showNotification(
                context = context.applicationContext,
                isTranslating = ServiceBridge.isTranslatingActive
            )
        }
    }
}

