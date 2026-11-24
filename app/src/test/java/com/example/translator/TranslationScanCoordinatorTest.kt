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
 * Por qué: Garantizan la exclusión mutua single-flight, el aislamiento de dibujos/limpiezas
 * mediante épocas monótonas y los 3 reintentos por `null_root`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationScanCoordinatorTest {

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

        // Si el usuario sale antes de procesarse un cambio, clearDirtyOnDeparture evita un escaneo fantasma al volver.
        inTargetApp = false
        coordinator.markScreenDirty()
        coordinator.clearDirtyOnDeparture()
        inTargetApp = true
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(2, scanCalls)

        coordinator.stopAll()
    }

    @Test
    fun scrollStarted_usesSoftHideCallbackWhenProvided_whileInvalidateStillCallsHardClear() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mainQueue = ArrayDeque<() -> Unit>()
        var softHideCalls = 0
        var hardMessageClearCalls = 0
        var fullClearCalls = 0

        val coordinator = TranslationScanCoordinator(
            scope = backgroundScope,
            workerDispatcher = dispatcher,
            postToMain = { mainQueue.addLast(it) },
            isTranslatingActive = { true },
            isScrollQuieted = { true },
            uptimeMs = { 10_000L + testScheduler.currentTime },
            isKeyboardVisible = { false },
            onClearAllOverlays = { fullClearCalls++ },
            onClearMessageOverlays = { hardMessageClearCalls++ },
            executeScan = { _, _, _, _ -> ScanOutcome.TRANSLATED_OR_EMPTY },
            onScrollHideMessageOverlays = { softHideCalls++ }
        )

        coordinator.onScrollStarted(eventSourceToken = null)
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().invoke()

        assertEquals(1, softHideCalls)
        assertEquals(0, hardMessageClearCalls)

        coordinator.invalidateAndClearAllOverlays()
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().invoke()

        assertEquals(1, fullClearCalls)
        coordinator.stopAll()
    }

    @Test
    fun deferredKeyboardVisible_keepsScreenDirtyAndRescansAfterKeyboardCloses() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var keyboardVisible = true
        var scanCalls = 0
        var completedTranslations = 0

        val coordinator = TranslationScanCoordinator(
            scope = backgroundScope,
            workerDispatcher = dispatcher,
            postToMain = { it.invoke() },
            isTranslatingActive = { true },
            isScrollQuieted = { true },
            uptimeMs = { 10_000L + testScheduler.currentTime },
            isKeyboardVisible = { keyboardVisible },
            onClearAllOverlays = {},
            onClearMessageOverlays = {},
            executeScan = { _, _, _, _ ->
                scanCalls++
                if (keyboardVisible) {
                    ScanOutcome.DEFERRED_KEYBOARD_VISIBLE
                } else {
                    completedTranslations++
                    ScanOutcome.TRANSLATED_OR_EMPTY
                }
            }
        )

        coordinator.startPeriodicLoop()
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(1, scanCalls)
        assertEquals(0, completedTranslations)

        // Tras cerrarse el teclado sin eventos adicionales en la lista, el ciclo de 500ms debe reescanear
        keyboardVisible = false
        advanceTimeBy(650L)
        runCurrent()
        assertTrue(scanCalls >= 2)
        assertEquals(1, completedTranslations)

        // Un ciclo adicional de 2500ms no vuelve a escanear porque la pantalla ya quedó limpia tras traducir
        val callsAfterTranslate = scanCalls
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals(callsAfterTranslate, scanCalls)

        coordinator.stopAll()
    }
}

