package com.example.translator

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ScanOutcome {
    TRANSLATED_OR_EMPTY,
    ABORT_NO_FG,
    ABORT_NULL_ROOT,
    SKIPPED_MODE_OR_INACTIVE
}

/**
 * Coordinador de un solo vuelo (`single-flight`) para escaneos periódicos, eventos de contenido,
 * reposo de desplazamiento y solicitudes manuales de escaneo.
 *
 * Por qué: Impide que el bucle de 2s, el debounce, el reposo de scroll y las pulsaciones del usuario
 * lancen recorridos paralelos sobre Binder en el hilo principal, ordena las limpiezas de pantalla
 * mediante épocas monótonas y respeta estrictamente el modo 'Tap to Translate'.
 */
class TranslationScanCoordinator(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val postToMain: (() -> Unit) -> Unit,
    private val isTranslatingActive: () -> Boolean,
    private val isTapMode: () -> Boolean,
    private val isScrollQuieted: (nowMs: Long) -> Boolean,
    private val uptimeMs: () -> Long,
    private val isKeyboardVisible: () -> Boolean,
    private val onClearAllOverlays: () -> Unit,
    private val onClearMessageOverlays: (clearDraftToo: Boolean) -> Unit,
    private val executeScan: suspend (overlayEpoch: Long, generation: Long, eventSourceToken: Any?, isManualRequest: Boolean) -> ScanOutcome,
    private val onLoopTick: (isInitial: Boolean) -> Unit = {}
) {
    val scanGeneration = AtomicLong(0)
    val overlayEpoch = AtomicLong(0)
    @Volatile private var lastDrawnOverlayEpoch: Long = -1L
    @Volatile private var lastClearedOverlayEpoch: Long = -1L

    @Volatile var isScreenScrolling: Boolean = false
    @Volatile var lastScrollTimestampMs: Long = 0L

    private val scanMutex = Mutex()
    private var scanDebounceJob: Job? = null
    private var scrollSettleJob: Job? = null
    private var activeScanLoopJob: Job? = null
    private var entryRetryJob: Job? = null
    private var manualScanJob: Job? = null

    @Volatile private var pendingScanEventSource: Any? = null
    @Volatile private var pendingScanWindowStartMs: Long = 0L
    @Volatile private var lastCompletedOrStartedScanMs: Long = 0L
    @Volatile private var entryNullRootRetries: Int = 0

    val isLoopAlive: Boolean
        get() = activeScanLoopJob?.isActive == true

    /**
     * Registra un dibujo completado en el hilo principal para impedir que una limpieza atrasada
     * en la cola lo sobrescriba si ya entró en vigor una sesión posterior.
     */
    fun recordOverlayDrawn(drawEpoch: Long) {
        if (drawEpoch > lastDrawnOverlayEpoch) {
            lastDrawnOverlayEpoch = drawEpoch
        }
    }

    /**
     * Verifica en el hilo principal si un dibujo capturado en `drawEpoch` sigue siendo vigente.
     */
    fun isOverlayEpochCurrent(drawEpoch: Long): Boolean {
        return overlayEpoch.get() == drawEpoch
    }

    /**
     * Invalida dibujos pendientes en épocas anteriores y encola una limpieza única en el hilo principal.
     */
    fun invalidateAndClearAllOverlays() {
        val clearEpoch = overlayEpoch.incrementAndGet()
        postToMain {
            if (lastDrawnOverlayEpoch < clearEpoch && lastClearedOverlayEpoch < clearEpoch) {
                lastClearedOverlayEpoch = clearEpoch
                onClearAllOverlays()
            }
        }
    }

    /**
     * Avanza la época tras entrar de nuevo a una app activa para aislar limpiezas antiguas en cola.
     */
    fun advanceForegroundEpoch(): Long {
        entryNullRootRetries = 0
        return overlayEpoch.incrementAndGet()
    }

    fun startPeriodicLoop() {
        activeScanLoopJob?.cancel()
        activeScanLoopJob = scope.launch(workerDispatcher) {
            delay(50L)
            if (!isTranslatingActive()) return@launch
            onLoopTick(true)
            val nowInit = uptimeMs()
            if (!isTapMode() && isScrollQuieted(nowInit)) {
                runSingleFlightScan(eventSourceToken = null, isManualRequest = false, ignoreThrottle = true)
            }

            while (isTranslatingActive()) {
                delay(2000L)
                if (!isTranslatingActive()) break
                onLoopTick(false)
                if (isTapMode()) continue
                val nowLoop = uptimeMs()
                if (isScrollQuieted(nowLoop)) {
                    runSingleFlightScan(eventSourceToken = null, isManualRequest = false)
                }
            }
        }
    }

    fun stopAll() {
        activeScanLoopJob?.cancel()
        scanDebounceJob?.cancel()
        scrollSettleJob?.cancel()
        entryRetryJob?.cancel()
        manualScanJob?.cancel()
        pendingScanWindowStartMs = 0L
        pendingScanEventSource = null
        entryNullRootRetries = 0
        isScreenScrolling = false
    }

    fun cancelDebouncedWorkOnly() {
        scanDebounceJob?.cancel()
        scrollSettleJob?.cancel()
        entryRetryJob?.cancel()
        pendingScanWindowStartMs = 0L
        pendingScanEventSource = null
        entryNullRootRetries = 0
        isScreenScrolling = false
        scanGeneration.incrementAndGet()
    }

    fun onScrollStarted(eventSourceToken: Any?) {
        scanGeneration.incrementAndGet()
        val now = uptimeMs()
        lastScrollTimestampMs = now
        isScreenScrolling = true
        pendingScanWindowStartMs = 0L
        scanDebounceJob?.cancel()
        scrollSettleJob?.cancel()
        entryRetryJob?.cancel()

        val clearDraft = !isKeyboardVisible()
        postToMain {
            onClearMessageOverlays(clearDraft)
        }

        if (isTapMode()) {
            isScreenScrolling = false
            return
        }

        scrollSettleJob = scope.launch(workerDispatcher) {
            delay(360L)
            isScreenScrolling = false
            if (!isTranslatingActive() || isTapMode()) return@launch
            runSingleFlightScan(eventSourceToken = eventSourceToken, isManualRequest = false, ignoreThrottle = true)
        }
    }

    fun scheduleContentScan(delayMs: Long, eventSourceToken: Any? = null) {
        if (!isTranslatingActive() || isTapMode()) return

        val now = uptimeMs()
        val waitForScrollMs = if (!isScrollQuieted(now)) {
            val elapsedSinceScroll = (now - lastScrollTimestampMs).coerceAtLeast(0L)
            if (isScreenScrolling) 360L else (370L - elapsedSinceScroll).coerceIn(80L, 370L)
        } else {
            0L
        }
        val targetDelayMs = maxOf(delayMs, waitForScrollMs)
        if (eventSourceToken != null) {
            pendingScanEventSource = eventSourceToken
        }
        if (pendingScanWindowStartMs == 0L || scanDebounceJob?.isActive != true) {
            pendingScanWindowStartMs = now
        }
        val elapsedSinceWindowStart = now - pendingScanWindowStartMs
        // Por qué: Limita la inanición del debounce a 750ms para que actualizaciones continuas
        // de Messenger no pospongan indefinidamente el escaneo de burbujas visibles.
        if (scanDebounceJob?.isActive == true && elapsedSinceWindowStart >= 750L) {
            return
        }
        scanDebounceJob?.cancel()
        val cappedDelayMs = minOf(targetDelayMs, (750L - elapsedSinceWindowStart).coerceAtLeast(80L))
        val effectiveDelayMs = maxOf(waitForScrollMs, cappedDelayMs)
        scanDebounceJob = scope.launch(workerDispatcher) {
            delay(effectiveDelayMs)
            pendingScanWindowStartMs = 0L
            val sourceForScan = pendingScanEventSource ?: eventSourceToken
            pendingScanEventSource = null
            if (!isTranslatingActive() || isTapMode()) return@launch

            val nowAfterDelay = uptimeMs()
            if (!isScrollQuieted(nowAfterDelay)) {
                val age = (nowAfterDelay - lastScrollTimestampMs).coerceAtLeast(0L)
                val retryWaitMs = if (isScreenScrolling) 360L else (370L - age).coerceIn(80L, 360L)
                delay(retryWaitMs)
                if (!isTranslatingActive() || isTapMode() || !isScrollQuieted(uptimeMs())) return@launch
            }
            runSingleFlightScan(eventSourceToken = sourceForScan, isManualRequest = false)
        }
    }

    fun requestManualScan(delayBeforeAttemptMs: Long = 0L) {
        if (!isTranslatingActive()) return
        manualScanJob?.cancel()
        manualScanJob = scope.launch(workerDispatcher) {
            if (delayBeforeAttemptMs > 0L) delay(delayBeforeAttemptMs)
            isScreenScrolling = false
            lastScrollTimestampMs = 0L
            entryNullRootRetries = 0
            runSingleFlightScan(eventSourceToken = null, isManualRequest = true, ignoreThrottle = true)
        }
    }

    private suspend fun runSingleFlightScan(
        eventSourceToken: Any?,
        isManualRequest: Boolean,
        ignoreThrottle: Boolean = false
    ) {
        scanMutex.withLock {
            if (!isTranslatingActive()) return
            if (!isManualRequest && isTapMode()) return
            val now = uptimeMs()
            if (!ignoreThrottle && now - lastCompletedOrStartedScanMs < 120L) {
                return
            }
            lastCompletedOrStartedScanMs = now
            val epoch = overlayEpoch.get()
            val generation = scanGeneration.get()
            val outcome = executeScan(epoch, generation, eventSourceToken, isManualRequest)
            when (outcome) {
                ScanOutcome.TRANSLATED_OR_EMPTY -> {
                    entryNullRootRetries = 0
                    entryRetryJob?.cancel()
                }
                ScanOutcome.ABORT_NO_FG -> {
                    entryNullRootRetries = 0
                }
                ScanOutcome.ABORT_NULL_ROOT -> {
                    scheduleNullRootEntryRetryIfNeeded()
                }
                ScanOutcome.SKIPPED_MODE_OR_INACTIVE -> Unit
            }
        }
    }

    private fun scheduleNullRootEntryRetryIfNeeded() {
        if (isTapMode() || !isTranslatingActive()) return
        val attempt = entryNullRootRetries
        if (attempt >= 3) return
        entryNullRootRetries = attempt + 1
        val retryDelays = longArrayOf(150L, 350L, 700L)
        val waitMs = retryDelays[attempt]
        entryRetryJob?.cancel()
        entryRetryJob = scope.launch(workerDispatcher) {
            delay(waitMs)
            if (isTranslatingActive() && !isTapMode() && isScrollQuieted(uptimeMs())) {
                runSingleFlightScan(eventSourceToken = null, isManualRequest = false, ignoreThrottle = true)
            }
        }
    }
}
