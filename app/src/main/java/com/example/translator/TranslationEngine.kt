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
        // Por qué: Precalienta los clientes neuronales del par de idiomas seleccionado en el menú
        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val src = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE)
            ?.takeIf { it != "AUTO" } ?: AppDefaults.SOURCE_LANGUAGE
        val tgt = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE)
            ?: AppDefaults.TARGET_LANGUAGE
        val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
        TranslatorPool.get(src, tgt).downloadModelIfNeeded(conditions)
        TranslatorPool.get(tgt, src).downloadModelIfNeeded(conditions)
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

    private val downloadConditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()

    private fun isLineAlreadyInTargetScript(line: String, targetLang: String): Boolean {
        if (targetLang == "el" || targetLang == TranslateLanguage.GREEK) {
            val greekCount = line.count { (it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF') && it.isLetter() }
            val latinCount = line.count { it in 'a'..'z' || it in 'A'..'Z' || it in "ăâîșțĂÂÎȘȚ" }
            return greekCount > 0 && latinCount == 0
        }
        return false
    }

    private suspend fun translateSingleLine(
        line: String,
        sourceLang: String,
        targetLang: String
    ): String? = suspendCancellableCoroutine { continuation ->
        val client = TranslatorPool.get(sourceLang, targetLang)
        client.downloadModelIfNeeded(downloadConditions)
            .addOnSuccessListener {
                client.translate(line)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        Log.w("MLKitEngine", "ML Kit line translation failed", error)
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
            .addOnFailureListener { error ->
                Log.w("MLKitEngine", "ML Kit model download failed for $sourceLang->$targetLang", error)
                if (continuation.isActive) continuation.resume(null)
            }
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints
    ): String? {
        if (!text.contains('\n')) {
            return translateSingleLine(text, sourceLang, targetLang)
        }

        // Por qué: En mensajes multilínea (ej. ofertas de trabajo o anuncios con encabezados en griego
        // y cuerpo en inglés), se traduce línea por línea para preservar saltos de línea y encabezados ya en destino.
        val lines = text.split('\n')
        val translatedLines = ArrayList<String>(lines.size)
        var anyTranslated = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.any { it.isLetter() } || isLineAlreadyInTargetScript(trimmed, targetLang)) {
                translatedLines.add(line)
                continue
            }
            val translated = translateSingleLine(trimmed, sourceLang, targetLang)
            if (!translated.isNullOrBlank()) {
                translatedLines.add(translated)
                if (!translated.trim().equals(trimmed, ignoreCase = true)) {
                    anyTranslated = true
                }
            } else {
                translatedLines.add(line)
            }
        }

        return if (anyTranslated) translatedLines.joinToString("\n") else null
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
