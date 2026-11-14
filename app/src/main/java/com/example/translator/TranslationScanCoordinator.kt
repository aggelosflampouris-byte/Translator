package com.example.translator

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    private val onLoopTick: (isInitial: Boolean) -> Unit = {},
    private val isInTargetForeground: () -> Boolean = { true },
    private val isInteractive: () -> Boolean = { true }
) {
    val scanGeneration = AtomicLong(0)
    val overlayEpoch = AtomicLong(0)
    private val dirtyVersion = AtomicLong(1)
    private val dirtyWakeSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var screenNeedsRescan: Boolean = true
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
     * Marca la pantalla actual como modificada y despierta el coordinador solo cuando hace falta reescanear.
     */
    fun markScreenDirty() {
        dirtyVersion.incrementAndGet()
        screenNeedsRescan = true
        dirtyWakeSignal.trySend(Unit)
    }

    /**
     * Limpia el estado pendiente al salir de apps de chat para evitar despertares innecesarios en reposo.
     */
    fun clearDirtyOnDeparture() {
        screenNeedsRescan = false
    }

    /**
     * Avanza la época tras entrar de nuevo a una app activa para aislar limpiezas antiguas en cola.
     */
    fun advanceForegroundEpoch(): Long {
        entryNullRootRetries = 0
        markScreenDirty()
        return overlayEpoch.incrementAndGet()
    }

    fun startPeriodicLoop() {
        activeScanLoopJob?.cancel()
        if (!isTranslatingActive() || isTapMode()) {
            return
        }
        markScreenDirty()
        activeScanLoopJob = scope.launch(workerDispatcher) {
            delay(50L)
            if (!isTranslatingActive() || isTapMode()) return@launch
            if (isInteractive()) {
                onLoopTick(true)
                val nowInit = uptimeMs()
                if (isScrollQuieted(nowInit)) {
                    runSingleFlightScan(eventSourceToken = null, isManualRequest = false, ignoreThrottle = true)
                }
            }

            var offTargetDirtyTicks = 0
            while (isTranslatingActive() && !isTapMode()) {
                // Por qué: Si la pantalla ya está limpia, suspendemos sin temporizadores (0 despertares)
                // hasta que `markScreenDirty` señale nuevo contenido o regreso a primer plano.
                if (!screenNeedsRescan) {
                    offTargetDirtyTicks = 0
                    dirtyWakeSignal.receive()
                    continue
                }
                withTimeoutOrNull(2000L) {
                    dirtyWakeSignal.receive()
                }
                if (!isTranslatingActive() || isTapMode()) break
                if (!screenNeedsRescan) {
                    offTargetDirtyTicks = 0
                    continue
                }
                if (!isInteractive() || !isInTargetForeground()) {
                    offTargetDirtyTicks++
                    if (offTargetDirtyTicks >= 5) {
                        screenNeedsRescan = false
                        offTargetDirtyTicks = 0
                    }
                    continue
                }
                offTargetDirtyTicks = 0
                onLoopTick(false)
                val nowLoop = uptimeMs()
                if (screenNeedsRescan && isScrollQuieted(nowLoop)) {
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
        markScreenDirty()
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
            delay(ScanTimingPolicy.SCROLL_SETTLE_DELAY_MS)
            isScreenScrolling = false
            if (!isTranslatingActive() || isTapMode()) return@launch
            runSingleFlightScan(eventSourceToken = eventSourceToken, isManualRequest = false, ignoreThrottle = true)
        }
    }

    fun scheduleContentScan(delayMs: Long, eventSourceToken: Any? = null) {
        if (!isTranslatingActive() || isTapMode()) return
        markScreenDirty()

        val now = uptimeMs()
        val scrollThreshold = ScanTimingPolicy.SCROLL_QUIET_THRESHOLD_MS + 20L
        val waitForScrollMs = if (!isScrollQuieted(now)) {
            val elapsedSinceScroll = (now - lastScrollTimestampMs).coerceAtLeast(0L)
            if (isScreenScrolling) {
                ScanTimingPolicy.SCROLL_SETTLE_DELAY_MS
            } else {
                (scrollThreshold - elapsedSinceScroll).coerceIn(80L, scrollThreshold)
            }
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
        // Por qué: Limita la inanición del debounce según la política centralizada para que ráfagas de UI
        // no pospongan indefinidamente el escaneo de burbujas visibles.
        if (scanDebounceJob?.isActive == true && elapsedSinceWindowStart >= ScanTimingPolicy.MAX_CONTENT_STARVATION_MS) {
            return
        }
        scanDebounceJob?.cancel()
        val cappedDelayMs = minOf(targetDelayMs, (ScanTimingPolicy.MAX_CONTENT_STARVATION_MS - elapsedSinceWindowStart).coerceAtLeast(80L))
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
                val retryWaitMs = if (isScreenScrolling) {
                    ScanTimingPolicy.SCROLL_SETTLE_DELAY_MS
                } else {
                    (scrollThreshold - age).coerceIn(80L, ScanTimingPolicy.SCROLL_SETTLE_DELAY_MS)
                }
                delay(retryWaitMs)
                if (!isTranslatingActive() || isTapMode() || !isScrollQuieted(uptimeMs())) return@launch
            }
            runSingleFlightScan(eventSourceToken = sourceForScan, isManualRequest = false)
        }
    }

    fun requestManualScan(delayBeforeAttemptMs: Long = 0L) {
        if (!isTranslatingActive()) return
        markScreenDirty()
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
            if (!ignoreThrottle && now - lastCompletedOrStartedScanMs < ScanTimingPolicy.SINGLE_FLIGHT_THROTTLE_MS) {
                return
            }
            lastCompletedOrStartedScanMs = now
            val epoch = overlayEpoch.get()
            val generation = scanGeneration.get()
            val dirtySnapshot = dirtyVersion.get()
            val outcome = executeScan(epoch, generation, eventSourceToken, isManualRequest)
            when (outcome) {
                ScanOutcome.TRANSLATED_OR_EMPTY -> {
                    entryNullRootRetries = 0
                    entryRetryJob?.cancel()
                    // Por qué: Solo limpia la bandera si no llegaron eventos nuevos durante el escaneo en curso.
                    if (dirtyVersion.get() == dirtySnapshot) {
                        screenNeedsRescan = false
                    }
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
