package com.example.translator

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * Por qué: Traduce líneas en paralelo bajo concurrencia acotada y protege cada llamada IPC con
 * tiempos límite y caché de preparación en [TranslatorPool] para reducir latencia y bloqueos.
 */
class MLKitTranslationEngine(
    private val callGuard: TranslationCallGuard = TranslationCallGuard.shared,
    private val maxConcurrentLines: Int = DEFAULT_MAX_CONCURRENT_LINES
) : TranslationEngine {

    companion object {
        private const val DEFAULT_MAX_CONCURRENT_LINES = 3
    }

    private val downloadConditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
    private val lineConcurrencyLimiter = Semaphore(maxConcurrentLines.coerceAtLeast(1))

    private fun isLineAlreadyInTargetScript(line: String, targetLang: String): Boolean {
        if (targetLang == "el" || targetLang == TranslateLanguage.GREEK) {
            return ScriptDetector.countGreek(line) > 0 && ScriptDetector.countLatin(line) == 0
        }
        return false
    }

    private suspend fun awaitModelReady(
        client: Translator,
        sourceLang: String,
        targetLang: String
    ): Boolean {
        if (TranslatorPool.isPairReady(sourceLang, targetLang)) return true
        val downloaded = callGuard.runGuarded(TranslationCallGuard.DEFAULT_DOWNLOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                client.downloadModelIfNeeded(downloadConditions)
                    .addOnSuccessListener {
                        TranslatorPool.markPairReady(sourceLang, targetLang)
                        if (continuation.isActive) continuation.resume(true)
                    }
                    .addOnFailureListener { error ->
                        Log.w("MLKitEngine", "ML Kit model download failed for $sourceLang->$targetLang", error)
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .addOnCanceledListener {
                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        }
        return downloaded == true
    }

    private suspend fun translateSingleLine(
        line: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        val client = TranslatorPool.get(sourceLang, targetLang)
        if (!awaitModelReady(client, sourceLang, targetLang)) return null

        return callGuard.runGuarded(TranslationCallGuard.DEFAULT_TRANSLATE_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { continuation ->
                client.translate(line)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        Log.w("MLKitEngine", "ML Kit line translation failed", error)
                        if (continuation.isActive) continuation.resume(null)
                    }
                    .addOnCanceledListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        }
    }

    private suspend fun translateMultiline(
        lines: List<String>,
        sourceLang: String,
        targetLang: String
    ): String? = coroutineScope {
        val resolvedLines = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.any { it.isLetter() } || isLineAlreadyInTargetScript(trimmed, targetLang)) {
                async { line to false }
            } else {
                async {
                    lineConcurrencyLimiter.withPermit {
                        val translated = translateSingleLine(trimmed, sourceLang, targetLang)
                        if (!translated.isNullOrBlank()) {
                            translated to !translated.trim().equals(trimmed, ignoreCase = true)
                        } else {
                            line to false
                        }
                    }
                }
            }
        }.awaitAll()

        if (resolvedLines.any { it.second }) {
            resolvedLines.joinToString("\n") { it.first }
        } else {
            null
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

        // Por qué: En mensajes multilínea, traducir líneas elegibles en paralelo acorta la espera
        // total a ~1 llamada en vez de N secuenciales, conservando saltos y encabezados en destino.
        return translateMultiline(text.split('\n'), sourceLang, targetLang)
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

        fun ensureModelsDownloaded(
            source: String,
            target: String,
            onSuccess: () -> Unit = {},
            onError: (Exception?) -> Unit = {}
        ) {
            val src = source.takeIf { it in AppDefaults.AVAILABLE_LANGUAGES } ?: AppDefaults.SOURCE_LANGUAGE
            val tgt = target.takeIf { it in AppDefaults.AVAILABLE_LANGUAGES } ?: AppDefaults.TARGET_LANGUAGE
            val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
            TranslatorPool.get(src, tgt).downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    TranslatorPool.markPairReady(src, tgt)
                    TranslatorPool.get(tgt, src).downloadModelIfNeeded(conditions)
                        .addOnSuccessListener {
                            TranslatorPool.markPairReady(tgt, src)
                            onSuccess()
                        }
                        .addOnFailureListener { err ->
                            Log.w("Translator", "Reverse model download failed ($tgt->$src): ${err.message}")
                            onError(err)
                        }
                }
                .addOnFailureListener { err ->
                    Log.w("Translator", "Forward model download failed ($src->$tgt): ${err.message}")
                    onError(err)
                }
        }

        fun preloadModels(context: Context) {
            val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            val src = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE)
                ?: AppDefaults.SOURCE_LANGUAGE
            val tgt = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE)
                ?: AppDefaults.TARGET_LANGUAGE
            ensureModelsDownloaded(src, tgt)
        }
    }

    /**
     * Resuelve de forma síncrona e instantánea (< 1 ms) si el texto ya está en la memoria aprendida,
     * en la caché LRU o coincide con una expresión idiomática conocida.
     */
    fun resolveFast(text: String, sourceLang: String, targetLang: String): String? {
        val clean = TextSanitizer.sanitizeSpecialCharacters(text)
        return resolveFastClean(clean, sourceLang, targetLang)
    }

    private fun resolveFastClean(clean: String, sourceLang: String, targetLang: String): String? {
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

        resolveFastClean(cleanInput, sourceLang, targetLang)?.let { return it }

        val normalized = if (sourceLang == TranslateLanguage.ROMANIAN) {
            TextNormalizer.normalizeRomanianChatForTranslation(cleanInput)
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
