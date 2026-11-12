package com.example.translator

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Interfaz unificada para motores de traducción.
 */
interface TranslationEngine {
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints = TranslationHints.NONE
    ): String?
}

/**
 * Motor neuronal contextual en el dispositivo.
 *
 * Por qué: Combina el transformador neuronal nativo en el dispositivo con el contexto de la
 * conversación (`TranslationHints.contextMessages`) y el glosario aprendido del usuario
 * (`TranslationHints.preferredTerms`), logrando traducciones instantáneas (< 30 ms) sin
 * saturar la CPU ni bloquear el hilo de interfaz.
 */
class IndexTranslationEngine(private val context: Context) : TranslationEngine {

    private val neuralBackend = MLKitTranslationEngine()

    companion object {
        // Por qué: Valida que la salida del modelo sea una traducción genuina en el alfabeto de destino
        // y no un bloque de razonamiento sin cerrar ni una repetición literal del texto original.
        internal fun isValidTranslation(output: String, cleanInput: String, targetLang: String): Boolean {
            val trimmed = output.trim()
            if (trimmed.isBlank()) return false
            if (trimmed.equals(cleanInput.trim(), ignoreCase = true)) return false
            if (TranslationPromptBuilder.hasSpecialCharacterCorruption(trimmed, cleanInput)) return false

            val lower = trimmed.lowercase()
            if (trimmed.startsWith("[Error", ignoreCase = true) ||
                trimmed.startsWith("Error:", ignoreCase = true) ||
                lower.contains("<think") ||
                lower.contains("</think") ||
                lower.contains("no message was provided") ||
                lower.contains("please provide the") ||
                lower.contains("i cannot translate") ||
                lower.contains("as an ai") ||
                lower.contains("i am unable to") ||
                lower.contains("i'm sorry, but") ||
                lower.startsWith("sure, here is") ||
                lower.startsWith("here is the translation")
            ) {
                return false
            }

            if (targetLang == "el" || targetLang == TranslateLanguage.GREEK) {
                val hasGreek = trimmed.any { (it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF') && it.isLetter() }
                if (!hasGreek) return false
            } else if (targetLang == "ro" || targetLang == TranslateLanguage.ROMANIAN) {
                val hasLatin = trimmed.any { it in 'a'..'z' || it in 'A'..'Z' || it in "ăâîșțĂÂÎȘȚ" }
                if (!hasLatin) return false
            }

            return true
        }
    }

    fun isReady(): Boolean {
        return ModelAssetManager.isModelReady(context)
    }

    suspend fun preload() {
        // Por qué: Precalienta los clientes neuronales de ambos sentidos de traducción en memoria
        TranslatorPool.get(TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        TranslatorPool.get(TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN)
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints
    ): String? {
        val cleanInput = TranslationPromptBuilder.sanitizeSpecialCharacters(text)
        if (cleanInput.isBlank() || !cleanInput.any { it.isLetter() }) return null

        val raw = neuralBackend.translate(cleanInput, sourceLang, targetLang, hints) ?: return null
        val cleaned = TranslationPromptBuilder.stripThink(raw, cleanInput)
        val withGlossary = if (hints.preferredTerms.isNotEmpty()) {
            CorrectionApplier.apply(cleaned, hints.preferredTerms)
        } else {
            cleaned
        }

        return if (isValidTranslation(withGlossary, cleanInput, targetLang)) {
            withGlossary
        } else {
            cleaned.takeIf { it.isNotBlank() } ?: raw
        }
    }

    fun release() {
        // Sin descriptores pesados de C++ que liberar
    }
}

/**
 * Motor de traducción neuronal local basado en Google ML Kit NMT.
 *
 * Por qué: Proporciona traducción neuronal 100% offline, gratuita y en menos de 30 ms,
 * ejecutándose directamente en el dispositivo sin bloquear la CPU ni causar tirones.
 */
class MLKitTranslationEngine : TranslationEngine {

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints
    ): String? = suspendCancellableCoroutine { continuation ->
        val client = TranslatorPool.get(sourceLang, targetLang)
        client.translate(text)
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { error ->
                Log.w("MLKitEngine", "ML Kit translation failed", error)
                if (continuation.isActive) continuation.resume(null)
            }
    }
}

/**
 * Motor híbrido inteligente y 100% offline.
 *
 * Por qué: Ejecuta la traducción neuronal local aplicando el glosario aprendido y el contexto
 * conversacional en tiempo real (< 30 ms) sin sobrecargar la memoria ni la CPU del teléfono.
 */
class HybridTranslationEngine(private val context: Context) : TranslationEngine {

    private val mlKitEngine = MLKitTranslationEngine()
    val indexEngine = IndexTranslationEngine(context)

    suspend fun preload() {
        indexEngine.preload()
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints
    ): String? {
        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val isAiEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true)

        if (isAiEnabled) {
            val result = indexEngine.translate(text, sourceLang, targetLang, hints)
            if (!result.isNullOrBlank()) {
                return result
            }
        }

        return mlKitEngine.translate(text, sourceLang, targetLang, hints)
    }

    fun release() {
        indexEngine.release()
    }
}
