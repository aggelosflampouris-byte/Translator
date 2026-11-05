package com.example.translator

import android.util.Log
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Pool thread-safe de instancias [Translator] de ML Kit.
 *
 * Por qué: Reutiliza los modelos de traducción entre servicios para minimizar el consumo
 * de memoria y evitar inicializaciones redundantes en cada llamada.
 */
object TranslatorPool {
    private val translators = mutableMapOf<String, Translator>()

    @Synchronized
    fun get(sourceLang: String, targetLang: String): Translator {
        val key = "$sourceLang-$targetLang"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }
    }

    @Synchronized
    fun releaseAll() {
        translators.values.forEach {
            try {
                it.close()
            } catch (e: Exception) {
                Log.w("TranslatorPool", "Error closing translator", e)
            }
        }
        translators.clear()
    }
}

