package com.example.translator

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Interfaz unificada para motores de traducción.
 */
interface TranslationEngine {
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String?
}

/**
 * Motor de traducción basado en el modelo especializado local Index-Translate-2B (GGUF).
 *
 * Por qué: Index-Translate-2B está entrenado específicamente para traducción automática en 150 idiomas,
 * preservando jerga, emoticonos, puntuación y nombres sin generar respuestas conversacionales redundantes
 * ("Here is the translation...", "Sure", etc.). Funciona de forma 100% offline y privada en el teléfono.
 */
class IndexTranslationEngine(private val context: Context) : TranslationEngine {

    companion object {
        private const val TAG = "IndexTranslationEngine"
    }

    private var modelHandle: Long = 0L

    fun isReady(): Boolean {
        return ModelAssetManager.isModelReady(context)
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? = withContext(Dispatchers.Default) {
        val cleanInput = text.trim()
        if (cleanInput.isBlank() || !cleanInput.any { it.isLetter() }) return@withContext null

        val customPath = ModelAssetManager.getCustomModelPath(context)
        val modelFile = if (!customPath.isNullOrBlank()) File(customPath) else ModelAssetManager.getModelFile(context)

        if (!modelFile.exists() || modelFile.length() < 50_000_000L) {
            return@withContext null
        }

        val targetName = when (targetLang) {
            "el", TranslateLanguage.GREEK -> "Greek"
            "ro", TranslateLanguage.ROMANIAN -> "Romanian"
            "en", TranslateLanguage.ENGLISH -> "English"
            else -> targetLang
        }

        // Por qué: Plantilla de traducción formal directa recomendada por IndexTeam para evitar relleno conversacional
        val prompt = "Translate the following text into $targetName, output only the translated result without any explanation:\n$cleanInput"

        if (LlamaNativeBridge.isAvailable()) {
            try {
                if (modelHandle == 0L) {
                    modelHandle = LlamaNativeBridge.nativeInitModel(
                        modelPath = modelFile.absolutePath,
                        threads = 4,
                        contextSize = 1024
                    )
                }
                if (modelHandle != 0L) {
                    val rawOutput = LlamaNativeBridge.nativeGenerate(modelHandle, prompt, maxTokens = 256)
                    val cleaned = cleanOutput(rawOutput)
                    if (cleaned.isNotBlank() && isValidTranslation(cleaned)) {
                        return@withContext cleaned
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Native inference execution failed, delegating to fallback", e)
            }
        }

        null
    }

    private fun cleanOutput(output: String): String {
        return output.trim()
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("«").removeSuffix("»")
            .trim()
    }

    private fun isValidTranslation(output: String): Boolean {
        val trimmed = output.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase()

        if (trimmed.startsWith("[Error", ignoreCase = true) ||
            trimmed.startsWith("Error:", ignoreCase = true) ||
            lower.contains("no message was provided") ||
            lower.contains("please provide the") ||
            lower.contains("i cannot translate") ||
            lower.contains("as an ai") ||
            lower.contains("i am unable to") ||
            lower.contains("i'm sorry, but") ||
            lower.startsWith("sure, here is") ||
            lower.startsWith("here is the translation")) {
            return false
        }
        return true
    }
}

/**
 * Motor de traducción local basado en Google ML Kit.
 *
 * Por qué: Proporciona traducción 100% offline, gratuita y sin latencia de red,
 * sirviendo como respaldo seguro e inmediato en cualquier dispositivo o mientras
 * el modelo neuronal especializado se descarga en segundo plano.
 */
class MLKitTranslationEngine : TranslationEngine {

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
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
 * Por qué: Da prioridad al modelo especializado de traducción neuronal en el dispositivo (Index-Translate-2B)
 * para capturar expresiones idiomáticas y jerga informal cuando los pesos están instalados,
 * recurriendo instantáneamente a ML Kit local cuando el modelo pesado no está presente.
 */
class HybridTranslationEngine(private val context: Context) : TranslationEngine {

    private val mlKitEngine = MLKitTranslationEngine()
    private val indexEngine = IndexTranslationEngine(context)

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val isAiEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true)

        // Por qué: Utiliza el modelo neuronal local si el usuario lo tiene activo y descargado en el almacenamiento
        if (isAiEnabled && indexEngine.isReady()) {
            val result = indexEngine.translate(text, sourceLang, targetLang)
            if (!result.isNullOrBlank()) {
                return result
            }
        }

        // Respaldo inmediato con ML Kit local
        return mlKitEngine.translate(text, sourceLang, targetLang)
    }
}
