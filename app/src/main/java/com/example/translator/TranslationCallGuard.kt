package com.example.translator

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Protege llamadas IPC hacia ML Kit mediante tiempos límite estrictos y disyuntor temporal (`circuit breaker`).
 *
 * Por qué: Si Google Play Services o ML Kit quedan bloqueados por ahorro térmico o binder congestionado,
 * evita acumular corrutinas colgadas y recurre de inmediato a memoria aprendida/glosario local durante el enfriamiento.
 */
class TranslationCallGuard(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val failureWindowMs: Long = DEFAULT_FAILURE_WINDOW_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val uptimeMs: () -> Long = { android.os.SystemClock.uptimeMillis() }
) {
    companion object {
        const val DEFAULT_TRANSLATE_TIMEOUT_MS = 4_500L
        const val DEFAULT_IDENTIFY_TIMEOUT_MS = 1_500L
        const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 30_000L
        const val DEFAULT_FAILURE_THRESHOLD = 3
        const val DEFAULT_FAILURE_WINDOW_MS = 15_000L
        const val DEFAULT_COOLDOWN_MS = 10_000L

        val shared: TranslationCallGuard by lazy { TranslationCallGuard() }
    }

    private var consecutiveTimeouts: Int = 0
    private var firstFailureInWindowMs: Long = 0L
    private var circuitOpenUntilMs: Long = 0L

    @Synchronized
    fun isCircuitOpen(nowMs: Long = uptimeMs()): Boolean {
        if (circuitOpenUntilMs == 0L) return false
        if (nowMs < circuitOpenUntilMs) return true
        // Por qué: Al expirar el enfriamiento se cierra el circuito para permitir reintentar ML Kit.
        circuitOpenUntilMs = 0L
        consecutiveTimeouts = 0
        firstFailureInWindowMs = 0L
        return false
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveTimeouts = 0
        firstFailureInWindowMs = 0L
        circuitOpenUntilMs = 0L
    }

    @Synchronized
    fun recordTimeout(nowMs: Long = uptimeMs()) {
        if (firstFailureInWindowMs == 0L || (nowMs - firstFailureInWindowMs) > failureWindowMs) {
            firstFailureInWindowMs = nowMs
            consecutiveTimeouts = 1
        } else {
            consecutiveTimeouts++
        }
        if (consecutiveTimeouts >= failureThreshold) {
            circuitOpenUntilMs = nowMs + cooldownMs
        }
    }

    @Synchronized
    fun reset() {
        consecutiveTimeouts = 0
        firstFailureInWindowMs = 0L
        circuitOpenUntilMs = 0L
    }

    /**
     * Ejecuta [block] bajo [timeoutMs] y devuelve `null` si el circuito está abierto o vence el tiempo límite.
     */
    suspend fun <T> runGuarded(timeoutMs: Long, block: suspend () -> T?): T? {
        if (isCircuitOpen()) return null
        return try {
            val result = withTimeout(timeoutMs) {
                block()
            }
            recordSuccess()
            result
        } catch (timeout: TimeoutCancellationException) {
            // Por qué: Garantizamos propagar una cancelación del alcance padre si coincidió con el timeout.
            coroutineContext.ensureActive()
            recordTimeout()
            null
        }
    }
}

