package com.example.translator

import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adaptador opcional para el traductor integrado del sistema operativo (Android 12+, API 31+).
 *
 * Por qué: Permite aprovechar paquetes de idiomas ya instalados en el dispositivo sin claves API,
 * descargas extra ni coste alguno, cediendo automáticamente a ML Kit cuando el fabricante no expone
 * el par solicitado.
 */
class SystemOnDeviceTranslationEngine(
    private val appContext: Context
) : TranslationEngine {

    companion object {
        private const val TRANSLATE_TIMEOUT_MS = 1_500L

        fun isSupportedApi(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun isPairOnDevice(context: Context, sourceLang: String, targetLang: String): Boolean {
            if (!isSupportedApi()) return false
            return Api31Impl.isPairOnDevice(context.applicationContext, sourceLang, targetLang)
        }

        fun probeSummary(context: Context, sourceLang: String, targetLang: String): String {
            if (!isSupportedApi()) return "api<31"
            return Api31Impl.probeSummary(context.applicationContext, sourceLang, targetLang)
        }
    }

    private val cachedTranslators = ConcurrentHashMap<Pair<String, String>, Any>()
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OverChat-SysTr").apply { isDaemon = true }
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        hints: TranslationHints
    ): String? {
        if (!isSupportedApi() || text.isBlank()) return null
        return Api31Impl.translate(
            context = appContext,
            cachedTranslators = cachedTranslators,
            callbackExecutor = callbackExecutor,
            text = text,
            sourceLang = sourceLang,
            targetLang = targetLang,
            timeoutMs = TRANSLATE_TIMEOUT_MS
        )
    }

    fun releaseAll() {
        if (isSupportedApi()) {
            Api31Impl.releaseAll(cachedTranslators)
        } else {
            cachedTranslators.clear()
        }
        try {
            callbackExecutor.shutdownNow()
        } catch (_: Exception) {
            // Ignorar error de cierre del ejecutor auxiliar
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Impl {

        fun isPairOnDevice(context: Context, sourceLang: String, targetLang: String): Boolean {
            return findOnDeviceCapability(context, sourceLang, targetLang) != null
        }

        fun probeSummary(context: Context, sourceLang: String, targetLang: String): String {
            val mgr = context.getSystemService(TranslationManager::class.java) ?: return "no_mgr"
            val caps = try {
                mgr.getOnDeviceTranslationCapabilities(
                    TranslationSpec.DATA_FORMAT_TEXT,
                    TranslationSpec.DATA_FORMAT_TEXT
                )
            } catch (_: Exception) {
                return "err"
            }
            var totalOnDevice = 0
            var pairReady = false
            for (cap in caps) {
                if (cap.state != TranslationCapability.STATE_ON_DEVICE) continue
                totalOnDevice++
                if (matchesPair(cap, sourceLang, targetLang)) {
                    pairReady = true
                }
            }
            return if (pairReady) "ready($totalOnDevice)" else "none($totalOnDevice)"
        }

        suspend fun translate(
            context: Context,
            cachedTranslators: ConcurrentHashMap<Pair<String, String>, Any>,
            callbackExecutor: ExecutorService,
            text: String,
            sourceLang: String,
            targetLang: String,
            timeoutMs: Long
        ): String? {
            val pair = sourceLang.lowercase() to targetLang.lowercase()
            val translator = getOrCreateTranslator(
                context = context,
                pair = pair,
                cachedTranslators = cachedTranslators,
                callbackExecutor = callbackExecutor,
                timeoutMs = timeoutMs
            ) ?: return null

            if (translator.isDestroyed) {
                cachedTranslators.remove(pair, translator)
                return null
            }

            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    val request = TranslationRequest.Builder()
                        .setTranslationRequestValues(listOf(TranslationRequestValue.forText(text)))
                        .build()
                    try {
                        translator.translate(request, signal, callbackExecutor) { response: TranslationResponse ->
                            val translated = if (response.translationStatus == TranslationResponse.TRANSLATION_STATUS_SUCCESS) {
                                response.translationResponseValues.get(0)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                            } else {
                                null
                            }
                            if (cont.isActive) cont.resume(translated)
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }

        fun releaseAll(cachedTranslators: ConcurrentHashMap<Pair<String, String>, Any>) {
            for ((_, obj) in cachedTranslators) {
                val translator = obj as? Translator ?: continue
                try {
                    if (!translator.isDestroyed) translator.destroy()
                } catch (_: Exception) {
                    // Ignorar fallo al liberar traductor del sistema
                }
            }
            cachedTranslators.clear()
        }

        private suspend fun getOrCreateTranslator(
            context: Context,
            pair: Pair<String, String>,
            cachedTranslators: ConcurrentHashMap<Pair<String, String>, Any>,
            callbackExecutor: ExecutorService,
            timeoutMs: Long
        ): Translator? {
            val existing = cachedTranslators[pair] as? Translator
            if (existing != null && !existing.isDestroyed) {
                return existing
            }
            val capability = findOnDeviceCapability(context, pair.first, pair.second) ?: return null
            val mgr = context.getSystemService(TranslationManager::class.java) ?: return null
            val txContext = TranslationContext.Builder(capability.sourceSpec, capability.targetSpec).build()

            val created = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Translator?> { cont ->
                    try {
                        mgr.createOnDeviceTranslator(txContext, callbackExecutor) { translator ->
                            if (cont.isActive) cont.resume(translator)
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            } ?: return null

            val prev = cachedTranslators.putIfAbsent(pair, created) as? Translator
            if (prev != null && !prev.isDestroyed) {
                try {
                    created.destroy()
                } catch (_: Exception) {
                    // Ignorar
                }
                return prev
            }
            cachedTranslators[pair] = created
            return created
        }

        private fun findOnDeviceCapability(
            context: Context,
            sourceLang: String,
            targetLang: String
        ): TranslationCapability? {
            val mgr = context.getSystemService(TranslationManager::class.java) ?: return null
            val caps = try {
                mgr.getOnDeviceTranslationCapabilities(
                    TranslationSpec.DATA_FORMAT_TEXT,
                    TranslationSpec.DATA_FORMAT_TEXT
                )
            } catch (_: Exception) {
                return null
            }
            return caps.firstOrNull { cap ->
                cap.state == TranslationCapability.STATE_ON_DEVICE && matchesPair(cap, sourceLang, targetLang)
            }
        }

        private fun matchesPair(cap: TranslationCapability, sourceLang: String, targetLang: String): Boolean {
            val src = normalizeLangTag(cap.sourceSpec.locale)
            val tgt = normalizeLangTag(cap.targetSpec.locale)
            return src.equals(sourceLang, ignoreCase = true) && tgt.equals(targetLang, ignoreCase = true)
        }

        private fun normalizeLangTag(locale: ULocale): String = locale.language.lowercase()
    }
}

