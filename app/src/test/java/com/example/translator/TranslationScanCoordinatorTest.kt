package com.example.translator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias del coordinador de escaneo [TranslationScanCoordinator].
 *
 * Por qué: Garantizan la exclusión mutua single-flight, la subordinación estricta de `MODE_TAP`,
 * el aislamiento de dibujos/limpiezas mediante épocas monótonas y los 3 reintentos por `null_root`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationScanCoordinatorTest {

    @Test
    fun tapMode_skipsPeriodicLoopContentDebounceAndScrollSettle_butAllowsManualScan() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mainQueue = ArrayDeque<() -> Unit>()
        var autoScans = 0
        var manualScans = 0
        var clearedMessageCount = 0

        val coordinator = TranslationScanCoordinator(
            scope = backgroundScope,
            workerDispatcher = dispatcher,
            postToMain = { mainQueue.addLast(it) },
            isTranslatingActive = { true },
            isTapMode = { true },
            isScrollQuieted = { true },
            uptimeMs = { 10_000L + testScheduler.currentTime },
            isKeyboardVisible = { false },
            onClearAllOverlays = {},
            onClearMessageOverlays = { clearedMessageCount++ },
            executeScan = { _, _, _, isManual ->
                if (isManual) manualScans++ else autoScans++
                ScanOutcome.TRANSLATED_OR_EMPTY
            }
        )

        coordinator.startPeriodicLoop()
        coordinator.scheduleContentScan(delayMs = 100L)
        coordinator.onScrollStarted(eventSourceToken = "scroll-node")

        advanceTimeBy(4_500L)
        runCurrent()
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().invoke()

        assertEquals(0, autoScans)
        assertEquals(1, clearedMessageCount)

        coordinator.requestManualScan(delayBeforeAttemptMs = 0L)
        runCurrent()
        coordinator.stopAll()

        assertEquals(1, manualScans)
        assertEquals(0, autoScans)
    }

    @Test
    fun invalidateAndClearAllOverlays_doesNotOverwriteNewerDrawnEpoch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mainQueue = ArrayDeque<() -> Unit>()
        var clearCalls = 0

        val coordinator = TranslationScanCoordinator(
            scope = backgroundScope,
            workerDispatcher = dispatcher,
            postToMain = { mainQueue.addLast(it) },
            isTranslatingActive = { true },
            isTapMode = { false },
            isScrollQuieted = { true },
            uptimeMs = { 10_000L + testScheduler.currentTime },
            isKeyboardVisible = { false },
            onClearAllOverlays = { clearCalls++ },
            onClearMessageOverlays = {},
            executeScan = { _, _, _, _ -> ScanOutcome.TRANSLATED_OR_EMPTY }
        )

        val oldEpoch = coordinator.overlayEpoch.get()
        // Limpieza encolada aún sin ejecutar en Main.
        coordinator.invalidateAndClearAllOverlays()
        assertFalse(coordinator.isOverlayEpochCurrent(oldEpoch))

        // Antes de vaciar la cola de Main, la sesión avanza de época y completa un dibujo nuevo.
        val newerEpoch = coordinator.advanceForegroundEpoch()
        assertTrue(coordinator.isOverlayEpochCurrent(newerEpoch))
        coordinator.recordOverlayDrawn(newerEpoch)

        // Al drenar la cola de Main, la limpieza vieja detecta lastDrawnOverlayEpoch == newerEpoch y no borra el chat actual.
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().invoke()
        assertEquals(0, clearCalls)

        // Una nueva invalidación posterior al último dibujo sí ejecuta la limpieza una sola vez.
        coordinator.invalidateAndClearAllOverlays()
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().invoke()
        assertEquals(1, clearCalls)
    }

    @Test
    fun nullRootEntryRetry_schedulesUpToThreeBackoffRetriesAndStops() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var scanCalls = 0
        val callTimes = mutableListOf<Long>()

        val coordinator = TranslationScanCoordinator(
            scope = backgroundScope,
            workerDispatcher = dispatcher,
            postToMain = { it.invoke() },
            isTranslatingActive = { true },
            isTapMode = { false },
            isScrollQuieted = { true },
            uptimeMs = { 10_000L + testScheduler.currentTime },
            isKeyboardVisible = { false },
            onClearAllOverlays = {},
            onClearMessageOverlays = {},
            executeScan = { _, _, _, _ ->
                scanCalls++
                callTimes.add(testScheduler.currentTime)
                ScanOutcome.ABORT_NULL_ROOT
            }
        )

        coordinator.scheduleContentScan(delayMs = 50L)

        // Intento inicial a los 50ms, más reintentos a +150ms (200ms), +350ms (550ms) y +700ms (1250ms).
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(listOf(50L, 200L, 550L, 1250L), callTimes)
        assertEquals(4, scanCalls)
    }

    @Test
    fun singleFlightMutex_serializesConcurrentManualAndDebouncedScans() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var activeConcurrent = 0
        var maxConcurrent = 0
        var completed = 0

        val coordinator = TranslationScanCoordinator(
            scope = backgroundScope,
            workerDispatcher = dispatcher,
            postToMain = { it.invoke() },
            isTranslatingActive = { true },
            isTapMode = { false },
            isScrollQuieted = { true },
            uptimeMs = { 10_000L + testScheduler.currentTime },
            isKeyboardVisible = { false },
            onClearAllOverlays = {},
            onClearMessageOverlays = {},
            executeScan = { _, _, _, _ ->
                activeConcurrent++
                if (activeConcurrent > maxConcurrent) {
                    maxConcurrent = activeConcurrent
                }
                delay(200L)
                activeConcurrent--
                completed++
                ScanOutcome.TRANSLATED_OR_EMPTY
            }
        )

        coordinator.scheduleContentScan(delayMs = 10L)
        advanceTimeBy(50L)
        // Por qué: Dispara un escaneo manual mientras el escaneo por debounce aún mantiene el mutex de 200ms.
        coordinator.requestManualScan(delayBeforeAttemptMs = 0L)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, maxConcurrent)
        assertEquals(2, completed)
    }

    @Test
    fun tapMode_doesNotStartPeriodicLoopJob() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = TranslationScanCoordinator(
            scope = backgroundScope,
            workerDispatcher = dispatcher,
            postToMain = { it.invoke() },
            isTranslatingActive = { true },
            isTapMode = { true },
            isScrollQuieted = { true },
            uptimeMs = { 10_000L + testScheduler.currentTime },
            isKeyboardVisible = { false },
            onClearAllOverlays = {},
            onClearMessageOverlays = {},
            executeScan = { _, _, _, _ -> ScanOutcome.TRANSLATED_OR_EMPTY }
        )

        coordinator.startPeriodicLoop()
        runCurrent()

        assertFalse(coordinator.isLoopAlive)
    }

    @Test
    fun periodicLoop_skipsStationaryOrBackgroundTicks_untilDirtyInTargetApp() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var inTargetApp = true
        var interactive = true
        var scanCalls = 0

        val coordinator = TranslationScanCoordinator(
            scope = backgroundScope,
            workerDispatcher = dispatcher,
            postToMain = { it.invoke() },
            isTranslatingActive = { true },
            isTapMode = { false },
            isScrollQuieted = { true },
            uptimeMs = { 10_000L + testScheduler.currentTime },
            isKeyboardVisible = { false },
            onClearAllOverlays = {},
            onClearMessageOverlays = {},
            executeScan = { _, _, _, _ ->
                scanCalls++
                ScanOutcome.TRANSLATED_OR_EMPTY
            },
            isInTargetForeground = { inTargetApp },
            isInteractive = { interactive }
        )

        coordinator.startPeriodicLoop()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, scanCalls)

        // Pantalla sin cambios: los siguientes ticks de 2s no vuelven a escanear.
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(1, scanCalls)

        // Fuera de app objetivo o pantalla apagada: aunque se marque sucia, el bucle periódico no escanea.
        inTargetApp = false
        coordinator.markScreenDirty()
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals(1, scanCalls)

        interactive = false
        inTargetApp = true
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals(1, scanCalls)

        // Al volver a pantalla interactiva en app objetivo, el estado sucio pendiente dispara un único escaneo.
        interactive = true
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals(2, scanCalls)

        coordinator.stopAll()
    }
}

