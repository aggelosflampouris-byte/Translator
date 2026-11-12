package com.example.translator

import android.util.Log
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Pool thread-safe de instancias [Translator] de ML Kit con caché de preparación de modelos.
 *
 * Por qué: Reutiliza los clientes de traducción y recuerda qué pares de idiomas ya están
 * descargados para no invocar `downloadModelIfNeeded` antes de cada línea traducida.
 */
object TranslatorPool {
    private val translators = mutableMapOf<String, Translator>()
    private val readyPairs = ConcurrentHashMap.newKeySet<String>()

    private fun pairKey(sourceLang: String, targetLang: String): String = "$sourceLang-$targetLang"

    @Synchronized
    fun get(sourceLang: String, targetLang: String): Translator {
        val key = pairKey(sourceLang, targetLang)
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }
    }

    fun isPairReady(sourceLang: String, targetLang: String): Boolean {
        return readyPairs.contains(pairKey(sourceLang, targetLang))
    }

    fun markPairReady(sourceLang: String, targetLang: String) {
        readyPairs.add(pairKey(sourceLang, targetLang))
    }

    @Synchronized
    fun releaseAll() {
        readyPairs.clear()
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
