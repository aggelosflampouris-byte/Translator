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
 * Motor de traducción neuronal local basado en Google ML Kit NMT.
 *
 * Por qué: Proporciona traducción neuronal 100% offline en menos de 30 ms y consulta la caché
 * de preparación de [TranslatorPool] para evitar verificar la descarga en cada línea.
 */
class MLKitTranslationEngine : TranslationEngine {

    private val downloadConditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()

    private fun isLineAlreadyInTargetScript(line: String, targetLang: String): Boolean {
        if (targetLang == "el" || targetLang == TranslateLanguage.GREEK) {
            return ScriptDetector.countGreek(line) > 0 && ScriptDetector.countLatin(line) == 0
        }
        return false
    }

    private suspend fun translateSingleLine(
        line: String,
        sourceLang: String,
        targetLang: String
    ): String? = suspendCancellableCoroutine { continuation ->
        val client = TranslatorPool.get(sourceLang, targetLang)
        // Por qué: Si el modelo del par ya fue verificado, traduce directamente sin sobrecarga de IPC
        if (TranslatorPool.isPairReady(sourceLang, targetLang)) {
            client.translate(line)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { error ->
                    Log.w("MLKitEngine", "ML Kit line translation failed", error)
                    if (continuation.isActive) continuation.resume(null)
                }
            return@suspendCancellableCoroutine
        }

        client.downloadModelIfNeeded(downloadConditions)
            .addOnSuccessListener {
                TranslatorPool.markPairReady(sourceLang, targetLang)
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
 * Tubería unificada de traducción para escaneo automático, toque en burbuja y borrador de teclado.
 *
 * Por qué: Reúne en un solo flujo determinista la memoria aprendida, la caché en memoria,
 * la pre-traducción idiomática, la protección de emojis, el motor NMT, el post-procesado
 * de sentido, el glosario del usuario y la validación de alfabeto.
 */
class TranslationPipeline(
    private val engine: TranslationEngine = MLKitTranslationEngine(),
    private val isGlossaryAndValidationEnabled: () -> Boolean = { true },
    private val memoryLookup: (sourceLang: String, targetLang: String, text: String) -> String? = { _, _, _ -> null },
    private val cacheLookup: (sourceLang: String, targetLang: String, text: String) -> String? = { _, _, _ -> null },
    private val cacheStore: (sourceLang: String, targetLang: String, text: String, translation: String) -> Unit = { _, _, _, _ -> },
    private val learnedCorrectionsApplier: (sourceLang: String, targetLang: String, text: String) -> String = { _, _, t -> t }
) {

    companion object {
        // Por qué: Valida que la salida sea una traducción genuina en el alfabeto de destino y sin corrupción de símbolos
        fun isValidTranslation(output: String, cleanInput: String, targetLang: String): Boolean {
            val trimmed = output.trim()
            if (trimmed.isBlank()) return false
            if (trimmed.equals(cleanInput.trim(), ignoreCase = true)) return false
            if (TextSanitizer.hasSpecialCharacterCorruption(trimmed, cleanInput)) return false

            if (targetLang == "el" || targetLang == TranslateLanguage.GREEK) {
                if (!ScriptDetector.hasGreek(trimmed)) return false
            } else if (targetLang == "ro" || targetLang == TranslateLanguage.ROMANIAN || targetLang == "en" || targetLang == TranslateLanguage.ENGLISH) {
                if (!ScriptDetector.hasLatin(trimmed)) return false
            }

            return true
        }

        fun preloadModels(context: Context) {
            val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            val src = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE)
                ?.takeIf { it != "AUTO" } ?: AppDefaults.SOURCE_LANGUAGE
            val tgt = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE)
                ?: AppDefaults.TARGET_LANGUAGE
            val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
            TranslatorPool.get(src, tgt).downloadModelIfNeeded(conditions)
                .addOnSuccessListener { TranslatorPool.markPairReady(src, tgt) }
            TranslatorPool.get(tgt, src).downloadModelIfNeeded(conditions)
                .addOnSuccessListener { TranslatorPool.markPairReady(tgt, src) }
        }
    }

    /**
     * Resuelve de forma síncrona e instantánea (< 1 ms) si el texto ya está en la memoria aprendida,
     * en la caché LRU o coincide con una expresión idiomática conocida.
     */
    fun resolveFast(text: String, sourceLang: String, targetLang: String): String? {
        val clean = TextSanitizer.sanitizeSpecialCharacters(text)
        if (clean.isBlank()) return null

        memoryLookup(sourceLang, targetLang, clean)?.let { return it }
        cacheLookup(sourceLang, targetLang, clean)?.let { return it }

        val normalized = if (sourceLang == TranslateLanguage.ROMANIAN) {
            TextNormalizer.restoreRomanianDiacritics(clean)
        } else {
            clean
        }
        if (normalized != clean) {
            memoryLookup(sourceLang, targetLang, normalized)?.let { return it }
            cacheLookup(sourceLang, targetLang, normalized)?.let { return it }
        }

        val idiom = TranslationSenseEngine.resolveIdiomPreTranslation(normalized, sourceLang, targetLang)
        if (idiom != null) {
            cacheStore(sourceLang, targetLang, clean, idiom)
            return idiom
        }
        return null
    }

    /**
     * Ejecuta la traducción completa (incluyendo la vía rápida síncrona y el motor neuronal si es necesario).
     */
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints = TranslationHints.NONE
    ): String? {
        val cleanInput = TextSanitizer.sanitizeSpecialCharacters(text)
        if (cleanInput.isBlank() || !cleanInput.any { it.isLetter() }) return null

        resolveFast(cleanInput, sourceLang, targetLang)?.let { return it }

        val normalized = if (sourceLang == TranslateLanguage.ROMANIAN) {
            TextNormalizer.restoreRomanianDiacritics(cleanInput)
        } else {
            cleanInput
        }
        val protected = TextNormalizer.protectEmojis(normalized)
        val raw = engine.translate(protected.text, sourceLang, targetLang, hints) ?: return null
        val restored = TextNormalizer.restoreEmojis(raw, protected.tokens)
        val unwrapped = TextSanitizer.stripWrappingQuotes(restored, cleanInput)

        val senseAdjusted = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = normalized,
            translatedText = unwrapped,
            sourceLang = sourceLang,
            targetLang = targetLang
        )

        val applyExtras = isGlossaryAndValidationEnabled()
        val withGlossary = if (applyExtras) {
            val fromHints = if (hints.preferredTerms.isNotEmpty()) {
                CorrectionApplier.apply(senseAdjusted, hints.preferredTerms)
            } else {
                senseAdjusted
            }
            learnedCorrectionsApplier(sourceLang, targetLang, fromHints)
        } else {
            senseAdjusted
        }

        if (!isValidTranslation(withGlossary, cleanInput, targetLang)) {
            return null
        }

        cacheStore(sourceLang, targetLang, cleanInput, withGlossary)
        return withGlossary
    }
}
