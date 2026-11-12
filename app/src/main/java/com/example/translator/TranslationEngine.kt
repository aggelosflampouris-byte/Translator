package com.example.translator

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
 * Motor de traducción basado en el modelo especializado local Index-Translate-2B (GGUF)
 * ejecutado mediante llama.cpp en ARM64 de forma 100% offline y privada.
 *
 * Por qué: Index-Translate-2B está entrenado específicamente para traducción automática en 150 idiomas,
 * preservando jerga, emoticonos, puntuación y nombres sin generar respuestas conversacionales redundantes
 * ("Here is the translation...", "Sure", etc.). Funciona de forma 100% offline y privada en el teléfono.
 */
class IndexTranslationEngine(private val context: Context) : TranslationEngine {

    companion object {
        private const val TAG = "IndexTranslationEngine"
        private const val INFERENCE_TIMEOUT_MS = 15_000L
        private const val MAX_GENERATION_TOKENS = 128

        // Por qué: Valida que la salida del modelo sea una traducción genuina en el alfabeto de destino
        // y no un bloque de razonamiento sin cerrar ni una repetición literal del texto original.
        internal fun isValidTranslation(output: String, cleanInput: String, targetLang: String): Boolean {
            val trimmed = output.trim()
            if (trimmed.isBlank()) return false
            if (trimmed.equals(cleanInput.trim(), ignoreCase = true)) return false

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

    private var activeModel: LlamaModel? = null
    private var loadedModelPath: String? = null
    // Por qué: El contexto nativo de llama.cpp no permite inferencia concurrente en múltiples hilos simultáneos
    private val modelMutex = Mutex()

    fun isReady(): Boolean {
        return ModelAssetManager.isModelReady(context)
    }

    // Por qué: Permite cargar los 1.3 GB del modelo en memoria antes de que el usuario abra una conversación
    suspend fun preload() = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext
        modelMutex.withLock {
            ensureModelLoaded()
        }
    }

    private suspend fun ensureModelLoaded(): LlamaModel? {
        val customPath = ModelAssetManager.getCustomModelPath(context)
        val modelFile = if (!customPath.isNullOrBlank()) File(customPath) else ModelAssetManager.getModelFile(context)

        if (!modelFile.exists() || modelFile.length() < 50_000_000L) {
            return null
        }

        val current = activeModel
        if (current != null && loadedModelPath == modelFile.absolutePath && current.isLoaded) {
            return current
        }

        try {
            current?.let {
                try {
                    Llama.releaseModel(it)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error releasing old model instance", e)
                }
            }
            activeModel = null

            // Por qué: Se configuran 4 hilos y decodificación determinista (temperature = 0) para traducción precisa
            val config = LlamaConfig(
                contextSize = 1024,
                threads = 4,
                temperature = 0.0f
            )
            Log.i(TAG, "Loading local GGUF model: ${modelFile.absolutePath}")
            val model = Llama.loadModel(modelFile.absolutePath, config)
            activeModel = model
            loadedModelPath = modelFile.absolutePath
            Log.i(TAG, "GGUF model loaded successfully into native memory via llama.cpp")
            return model
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load GGUF model into llama.cpp", e)
            activeModel = null
            loadedModelPath = null
            return null
        }
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints
    ): String? = withContext(Dispatchers.Default) {
        val cleanInput = text.trim()
        if (cleanInput.isBlank() || !cleanInput.any { it.isLetter() }) return@withContext null

        val targetName = when (targetLang) {
            "el", TranslateLanguage.GREEK -> "Greek"
            "ro", TranslateLanguage.ROMANIAN -> "Romanian"
            "en", TranslateLanguage.ENGLISH -> "English"
            else -> targetLang
        }

        // Por qué: El prompt incorpora el contexto del chat y el glosario aprendido del usuario (aprendizaje en contexto)
        val prompt = TranslationPromptBuilder.build(cleanInput, targetName, hints)

        modelMutex.withLock {
            val model = ensureModelLoaded() ?: return@withContext null

            try {
                val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    Llama.complete(
                        model = model,
                        prompt = prompt,
                        systemPrompt = "",
                        maxTokens = MAX_GENERATION_TOKENS
                    )
                } ?: return@withContext null

                var cleaned = cleanOutput(result.text)

                // Por qué: Si el modelo tradujo también el contexto, se repite la inferencia con el prompt simple
                if (!hints.isEmpty && TranslationPromptBuilder.hasContextLeak(cleaned, cleanInput, hints)) {
                    Log.w(TAG, "Context leak detected, retrying without hints")
                    val plainPrompt = TranslationPromptBuilder.build(cleanInput, targetName, TranslationHints.NONE)
                    val retryResult = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                        Llama.complete(
                            model = model,
                            prompt = plainPrompt,
                            systemPrompt = "",
                            maxTokens = MAX_GENERATION_TOKENS
                        )
                    }
                    if (retryResult != null) {
                        cleaned = cleanOutput(retryResult.text)
                    }
                }

                if (isValidTranslation(cleaned, cleanInput, targetLang)) {
                    Log.d(TAG, "Neural model output (${result.tokensPerSecond} tok/s): '$cleaned'")
                    return@withContext cleaned
                } else {
                    Log.w(TAG, "Neural model output rejected by validation: '$cleaned'")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Native llama.cpp inference execution failed", e)
            }
        }

        null
    }

    // Por qué: Libera la memoria nativa mapeada cuando el servicio se detiene o el usuario elimina el modelo
    fun release() {
        activeModel?.let {
            try {
                Llama.releaseModel(it)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to release model", e)
            }
        }
        activeModel = null
        loadedModelPath = null
    }

    private fun cleanOutput(output: String): String {
        return TranslationPromptBuilder.stripThink(output)
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
 * Por qué: Da prioridad al modelo especializado de traducción neuronal en el dispositivo (Index-Translate-2B)
 * para capturar expresiones idiomáticas y jerga informal cuando los pesos están instalados,
 * recurriendo instantáneamente a ML Kit local cuando el modelo pesado no está presente.
 */
class HybridTranslationEngine(private val context: Context) : TranslationEngine {

    private val mlKitEngine = MLKitTranslationEngine()
    val indexEngine = IndexTranslationEngine(context)

    suspend fun preload() {
        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val isAiEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true)
        if (isAiEnabled && indexEngine.isReady()) {
            indexEngine.preload()
        }
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints
    ): String? {
        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val isAiEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true)

        // Por qué: Utiliza el modelo neuronal local si el usuario lo tiene activo y descargado en el almacenamiento
        if (isAiEnabled && indexEngine.isReady()) {
            val result = indexEngine.translate(text, sourceLang, targetLang, hints)
            if (!result.isNullOrBlank()) {
                return result
            }
            Log.w("HybridEngine", "Index-Translate neural model returned null/invalid, falling back to ML Kit")
        }

        // Respaldo inmediato con ML Kit local
        return mlKitEngine.translate(text, sourceLang, targetLang, hints)
    }

    // Por qué: Asegura la liberación de los descriptores de archivo y memoria mapeada en C++
    fun release() {
        indexEngine.release()
    }
}
