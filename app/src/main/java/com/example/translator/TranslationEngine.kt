package com.example.translator

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        val sourceName = if (sourceLang == "ro") "Romanian" else "Greek"
        val targetName = if (targetLang == "el") "Greek" else "Romanian"

        val prompt = "You are an expert chat translator between Greek and Romanian. " +
            "Translate this informal WhatsApp chat message from $sourceName to $targetName. " +
            "Preserve colloquial slang, natural idioms, swear words, and emotional tone accurately. " +
            "Preserve emojis, names, and punctuation. Output ONLY the raw translated text with no explanations or quotes:\n\n$text"

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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("GeminiEngine", "Gemini API failed with HTTP ${response.code}")
                return@withContext null
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
            val candidates = jsonObject.getAsJsonArray("candidates") ?: return@withContext null
            if (candidates.size() == 0) return@withContext null

            val content = candidates[0].asJsonObject.getAsJsonObject("content") ?: return@withContext null
            val parts = content.getAsJsonArray("parts") ?: return@withContext null
            if (parts.size() == 0) return@withContext null

            val translated = parts[0].asJsonObject.get("text")?.asString?.trim()
            if (!translated.isNullOrBlank()) {
                translated
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("GeminiEngine", "Error during Gemini translation", e)
            null
        }
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
        val userKey = prefs.getString(AppDefaults.KEY_GEMINI_API_KEY, "")?.trim() ?: ""
        // Por qué: Usa la clave de entorno/build si está presente para no exigir configuración al usuario,
        // o la clave personalizada si el usuario la suministró explícitamente en la configuración.
        val apiKey = if (userKey.isNotBlank()) userKey else BuildConfig.GEMINI_API_KEY.trim()

        if (apiKey.isNotBlank()) {
            val geminiResult = GeminiTranslationEngine(apiKey).translate(text, sourceLang, targetLang)
            if (!geminiResult.isNullOrBlank()) {
                return geminiResult
            }
        }

        // Respaldo offline con ML Kit
        return mlKitEngine.translate(text, sourceLang, targetLang)
    }
}

