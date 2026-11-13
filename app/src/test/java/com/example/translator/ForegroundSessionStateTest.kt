package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de la máquina de estados de sesión en primer plano [ForegroundSessionState].
 *
 * Por qué: Verifican que las transiciones `source=null` y superposiciones pequeñas de One UI
 * no borren la sesión activa de las apps compatibles mientras las salidas reales sí limpian la caché.
 */
class ForegroundSessionStateTest {

    @Test
    fun nonTargetEvent_withoutSubstantialBounds_preservesActiveTargetSession() {
        val state = ForegroundSessionState()

        state.recordNonTransientEvent(
            packageName = "com.instagram.android",
            eventType = ForegroundSessionState.EVT_WINDOW_STATE_CHANGED,
            nowMs = 1_000L,
            isEnabledTarget = true,
            isSmallTransientOverlay = false,
            hasSubstantialNonTargetBounds = false
        )

        val cleared = state.recordNonTransientEvent(
            packageName = "com.samsung.android.biometrics.app.setting",
            eventType = ForegroundSessionState.EVT_WINDOW_STATE_CHANGED,
            nowMs = 1_500L,
            isEnabledTarget = false,
            isSmallTransientOverlay = false,
            hasSubstantialNonTargetBounds = false
        )

        assertFalse(cleared)
        assertEquals("com.instagram.android", state.lastTargetEventPkg)
        assertTrue(state.hasActiveVisibleTargetSignal(1_600L))
    }

    @Test
    fun nonTargetEvent_smallTransientOverlay_preservesActiveTargetSession() {
        val state = ForegroundSessionState()

        state.recordNonTransientEvent(
            packageName = "com.viber.voip",
            eventType = ForegroundSessionState.EVT_WINDOW_STATE_CHANGED,
            nowMs = 1_000L,
            isEnabledTarget = true,
            isSmallTransientOverlay = false,
            hasSubstantialNonTargetBounds = false
        )

        val cleared = state.recordNonTransientEvent(
            packageName = "com.sec.android.app.launcher",
            eventType = ForegroundSessionState.EVT_WINDOW_STATE_CHANGED,
            nowMs = 1_800L,
            isEnabledTarget = false,
            isSmallTransientOverlay = true,
            hasSubstantialNonTargetBounds = true
        )

        assertFalse(cleared)
        assertEquals("com.viber.voip", state.lastTargetEventPkg)
        assertTrue(state.hasActiveVisibleTargetSignal(2_000L))
    }

    @Test
    fun nonTargetEvent_substantialNonTargetWindow_clearsTargetSession() {
        val state = ForegroundSessionState()

        state.recordNonTransientEvent(
            packageName = "com.whatsapp",
            eventType = ForegroundSessionState.EVT_WINDOW_STATE_CHANGED,
            nowMs = 1_000L,
            isEnabledTarget = true,
            isSmallTransientOverlay = false,
            hasSubstantialNonTargetBounds = false
        )

        val cleared = state.recordNonTransientEvent(
            packageName = "com.sec.android.app.launcher",
            eventType = ForegroundSessionState.EVT_WINDOW_STATE_CHANGED,
            nowMs = 2_000L,
            isEnabledTarget = false,
            isSmallTransientOverlay = false,
            hasSubstantialNonTargetBounds = true
        )

        assertTrue(cleared)
        assertNull(state.lastTargetEventPkg)
        assertFalse(state.hasActiveVisibleTargetSignal(2_000L))
    }

    @Test
    fun invisibleChromeTargetNodes_doNotDisableNullBurstFallback() {
        val state = ForegroundSessionState()

        // Por qué: Simula haber salido antes al launcher antes de abrir Viber sin exponer nodos visibles.
        state.recordNonTransientEvent(
            packageName = "com.sec.android.app.launcher",
            eventType = ForegroundSessionState.EVT_WINDOW_STATE_CHANGED,
            nowMs = 1_000L,
            isEnabledTarget = false,
            isSmallTransientOverlay = false,
            hasSubstantialNonTargetBounds = true
        )

        state.recordNonTransientEvent(
            packageName = "com.viber.voip",
            eventType = ForegroundSessionState.EVT_WINDOW_CONTENT_CHANGED,
            nowMs = 1_400L,
            isEnabledTarget = true,
            isSmallTransientOverlay = false,
            hasSubstantialNonTargetBounds = false
        )

        // Nodos no significativos o fuera de pantalla no deben desactivar el respaldo por ráfaga null.
        state.recordTargetSourceObservation(
            packageName = "com.viber.voip",
            eventType = ForegroundSessionState.EVT_WINDOW_CONTENT_CHANGED,
            nowMs = 1_410L,
            hasNonNullSource = true,
            isMeaningfulVisibleSource = false,
            isVisibleOrSubstantialContainer = false
        )
        assertEquals(0, state.meaningfulTargetSrcCount)

        repeat(3) { idx ->
            state.recordTargetSourceObservation(
                packageName = "com.viber.voip",
                eventType = ForegroundSessionState.EVT_WINDOW_CONTENT_CHANGED,
                nowMs = 1_420L + idx * 10L,
                hasNonNullSource = false,
                isMeaningfulVisibleSource = false,
                isVisibleOrSubstantialContainer = false
            )
        }
        assertFalse(state.hasActiveVisibleTargetSignal(1_450L))

        state.recordTargetSourceObservation(
            packageName = "com.viber.voip",
            eventType = ForegroundSessionState.EVT_WINDOW_CONTENT_CHANGED,
            nowMs = 1_460L,
            hasNonNullSource = false,
            isMeaningfulVisibleSource = false,
            isVisibleOrSubstantialContainer = false
        )
        assertEquals(4, state.targetNullBurstAfterNonTargetCount)
        assertTrue(state.hasActiveVisibleTargetSignal(1_500L))
    }

    @Test
    fun meaningfulVisibleTargetNode_resetsNullBurstAndActivatesVisibleSignal() {
        val state = ForegroundSessionState()

        state.recordNonTransientEvent(
            packageName = "com.instagram.android",
            eventType = ForegroundSessionState.EVT_WINDOW_CONTENT_CHANGED,
            nowMs = 5_000L,
            isEnabledTarget = true,
            isSmallTransientOverlay = false,
            hasSubstantialNonTargetBounds = false
        )

        state.recordTargetSourceObservation(
            packageName = "com.instagram.android",
            eventType = ForegroundSessionState.EVT_WINDOW_CONTENT_CHANGED,
            nowMs = 5_010L,
            hasNonNullSource = false,
            isMeaningfulVisibleSource = false,
            isVisibleOrSubstantialContainer = false
        )
        assertEquals(1, state.targetNullBurstAfterNonTargetCount)

        state.recordTargetSourceObservation(
            packageName = "com.instagram.android",
            eventType = ForegroundSessionState.EVT_WINDOW_CONTENT_CHANGED,
            nowMs = 5_050L,
            hasNonNullSource = true,
            isMeaningfulVisibleSource = true,
            isVisibleOrSubstantialContainer = true
        )

        assertEquals(1, state.meaningfulTargetSrcCount)
        assertEquals(0, state.targetNullBurstAfterNonTargetCount)
        assertTrue(state.hasActiveVisibleTargetSignal(5_100L))
    }

    @Test
    fun onForegroundResolved_distinguishesTransientNullFromGenuineDepartureAndRecovers() {
        val state = ForegroundSessionState()

        val enter = state.onForegroundResolved(
            activeTargetPkg = "com.instagram.android",
            resolveReason = "active_window",
            hasActiveOverlays = false,
            isScrollQuieted = true
        )
        assertTrue(enter.changed)
        assertFalse(enter.shouldClearOverlays)
        assertFalse(enter.shouldCancelWorkAndResetCaches)

        // Parpadeo null transitorio sin salida real: oculta superposiciones pero preserva caché de raíces.
        val transientNull = state.onForegroundResolved(
            activeTargetPkg = null,
            resolveReason = "no_recent_signal",
            hasActiveOverlays = true,
            isScrollQuieted = true
        )
        assertTrue(transientNull.changed)
        assertTrue(transientNull.shouldClearOverlays)
        assertFalse(transientNull.shouldCancelWorkAndResetCaches)
        assertFalse(transientNull.shouldClearRootCaches)

        // Recuperación tras volver al mismo chat sin burbujas activas -> solicita escaneo inmediato.
        val recover = state.onForegroundResolved(
            activeTargetPkg = "com.instagram.android",
            resolveReason = "active_window",
            hasActiveOverlays = false,
            isScrollQuieted = true
        )
        assertTrue(recover.changed)
        assertTrue(recover.shouldScheduleRecoveryScan)

        // Salida genuina hacia otra app sustancial -> limpia tanto superposiciones como cachés.
        val departure = state.onForegroundResolved(
            activeTargetPkg = null,
            resolveReason = "blocked_self_substantial",
            hasActiveOverlays = true,
            isScrollQuieted = true
        )
        assertTrue(departure.changed)
        assertTrue(departure.shouldClearOverlays)
        assertTrue(departure.shouldCancelWorkAndResetCaches)
        assertTrue(departure.shouldClearRootCaches)
    }

    @Test
    fun onForegroundResolved_switchingBetweenTwoEnabledTargetsClearsCaches() {
        val state = ForegroundSessionState()

        state.onForegroundResolved(
            activeTargetPkg = "com.whatsapp",
            resolveReason = "active_window",
            hasActiveOverlays = false,
            isScrollQuieted = true
        )

        val switchEffect = state.onForegroundResolved(
            activeTargetPkg = "com.viber.voip",
            resolveReason = "active_window",
            hasActiveOverlays = true,
            isScrollQuieted = true
        )

        assertTrue(switchEffect.changed)
        assertTrue(switchEffect.shouldClearOverlays)
        assertTrue(switchEffect.shouldCancelWorkAndResetCaches)
        assertTrue(switchEffect.shouldClearRootCaches)
        assertEquals("com.viber.voip", switchEffect.switchedToPackage)
    }
}

