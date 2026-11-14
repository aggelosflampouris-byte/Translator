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

    /**
     * Cierra los clientes de ML Kit que no correspondan a los pares activos para liberar memoria nativa.
     */
    @Synchronized
    fun evictExcept(keepPairs: Set<Pair<String, String>>) {
        val keepKeys = keepPairs.mapTo(mutableSetOf()) { pairKey(it.first, it.second) }
        val iterator = translators.entries.iterator()
        while (iterator.hasNext()) {
            val (key, translator) = iterator.next()
            if (!keepKeys.contains(key)) {
                readyPairs.remove(key)
                closeSafely(translator)
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun activeCount(): Int = translators.size

    @Synchronized
    fun releaseAll() {
        readyPairs.clear()
        translators.values.forEach(::closeSafely)
        translators.clear()
    }

    private fun closeSafely(translator: Translator) {
        try {
            translator.close()
        } catch (e: Exception) {
            Log.w("TranslatorPool", "Error closing translator", e)
        }
    }
}
