package com.example.translator

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
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
 * Motor de traducción basado en Gemini 1.5 Flash (Google AI Studio - Gratuito).
 *
 * Por qué: Los modelos estadísticos locales de 30MB de ML Kit traducen mediante un pivote
 * en inglés y carecen de comprensión conversacional, arruinando jerga, groserías e idiotismos
 * (ej: 'du-te fuck', 'Κέικ φίλης girnis'). Gemini 1.5 Flash comprende de forma nativa el contexto
 * informal de WhatsApp entre griego y rumano sin costo (tier gratuito de 15 RPM / 1500 peticiones/día).
 */
class GeminiTranslationEngine(private val apiKey: String) : TranslationEngine {

    companion object {
        // Por qué: Limita las peticiones simultáneas a 4 para balancear rendimiento rápido con el límite de tasa de Google AI Studio (HTTP 503/429).
        private val requestSemaphore = Semaphore(4)
        private val candidateModels = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val cleanInput = text.trim()
        // Por qué: Si el texto está vacío o no contiene ninguna letra, no se envía a la API para evitar respuestas de error del modelo
        if (cleanInput.isBlank() || !cleanInput.any { it.isLetter() }) return@withContext null

        requestSemaphore.withPermit {
            val sourceName = if (sourceLang == "ro") "Romanian" else "Greek"
            val targetName = if (targetLang == "el") "Greek" else "Romanian"

            val prompt = "You are an expert chat translator between Greek and Romanian. " +
                "Translate this informal WhatsApp chat message from $sourceName to $targetName. " +
                "Preserve colloquial slang, natural idioms, swear words, and emotional tone accurately. " +
                "Preserve emojis, names, and punctuation. Output ONLY the raw translated text with no explanations or quotes:\n\n$cleanInput"

            val payload = mapOf(
                "contents" to listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to prompt)
                        )
                    )
                ),
                "generationConfig" to mapOf(
                    "temperature" to 0.2,
                    "maxOutputTokens" to 256
                )
            )

            val jsonBody = gson.toJson(payload)

            // Por qué: Itera sobre modelos candidatos y realiza reintentos con retraso ante respuestas 503 o 429
            // para garantizar alta disponibilidad sin degradar prematuramente a ML Kit.
            for (model in candidateModels) {
                for (attempt in 1..2) {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

                    // Por qué: Se envía la clave de API a través del encabezado HTTP 'x-goog-api-key' en lugar de la URL,
                    // garantizando que no quede expuesta en registros de red, proxies ni cachés.
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("x-goog-api-key", apiKey)
                        .post(jsonBody.toRequestBody(jsonMediaType))
                        .build()

                    try {
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: break
                            val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                            val candidates = jsonObject.getAsJsonArray("candidates") ?: break
                            if (candidates.size() == 0) break

                            val content = candidates[0].asJsonObject.getAsJsonObject("content") ?: break
                            val parts = content.getAsJsonArray("parts") ?: break
                            if (parts.size() == 0) break

                            val translated = parts[0].asJsonObject.get("text")?.asString?.trim()
                            if (!translated.isNullOrBlank() && isValidTranslation(translated)) {
                                return@withContext translated
                            }
                            break
                        } else if (response.code == 503 || response.code == 429) {
                            Log.w("GeminiEngine", "Model $model returned HTTP ${response.code}, attempt $attempt. Retrying...")
                            delay(200L * attempt)
                            continue
                        } else {
                            Log.w("GeminiEngine", "Gemini API failed with HTTP ${response.code} for $model")
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("GeminiEngine", "Network error with $model on attempt $attempt", e)
                        delay(200L * attempt)
                    }
                }
            }
            null
        }
    }

    // Por qué: Valida que la respuesta generada por Gemini sea realmente una traducción de texto
    // y no un mensaje conversacional de error, advertencia o negativa ("Error: No message was provided", etc.).
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
 * sirviendo como respaldo seguro cuando no hay conexión o no se ha configurado clave API.
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
 * Motor híbrido inteligente.
 *
 * Por qué: Combina la calidad conversacional superior de Gemini cuando hay clave API disponible
 * con la resiliencia inmediata y sin conexión de ML Kit como respaldo transparente.
 */
class HybridTranslationEngine(private val context: Context) : TranslationEngine {

    private val mlKitEngine = MLKitTranslationEngine()

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val isAiEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true)
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()

        // Por qué: Emplea la clave inyectada de forma segura desde las variables de compilación/entorno.
        // Nunca se consulta ni se almacena la clave en las preferencias del usuario para evitar fugas de seguridad.
        if (isAiEnabled && apiKey.isNotBlank()) {
            val geminiResult = GeminiTranslationEngine(apiKey).translate(text, sourceLang, targetLang)
            if (!geminiResult.isNullOrBlank()) {
                return geminiResult
            }
        }

        // Respaldo offline con ML Kit
        return mlKitEngine.translate(text, sourceLang, targetLang)
    }
}

