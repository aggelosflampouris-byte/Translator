package com.example.translator

/**
 * Efecto resultante al resolver un cambio de ventana objetivo en primer plano.
 *
 * Por qué: Separa el ocultamiento visual inmediato de la cancelación de trabajos en curso
 * para que los parpadeos null de One UI no destruyan la raíz en caché ni aborten traducciones.
 */
data class SessionTransitionEffect(
    val changed: Boolean,
    val shouldClearOverlays: Boolean,
    val shouldCancelWorkAndResetCaches: Boolean,
    val shouldClearRootCaches: Boolean,
    val shouldScheduleRecoveryScan: Boolean,
    val switchedToPackage: String?
)

/**
 * Máquina de estados pura para la sesión activa en primer plano.
 *
 * Por qué: Centraliza la detección de entrada/salida y la resistencia frente a eventos `source=null`
 * de AOD/biometría/lanzador de One UI en código JVM verificable sin depender del framework Android.
 */
class ForegroundSessionState {

    companion object {
        const val EVT_WINDOW_STATE_CHANGED = 32
        const val EVT_WINDOW_CONTENT_CHANGED = 2048
        const val EVT_VIEW_SCROLLED = 4096
        const val EVT_VIEW_FOCUSED = 8
        const val EVT_VIEW_TEXT_CHANGED = 16
        const val EVT_VIEW_CLICKED = 1
    }

    @Volatile var lastForegroundTargetPackage: String? = null
        private set
    @Volatile var lastSettledTargetPackage: String? = null
        private set
    @Volatile var lastTargetEventPkg: String? = null
        private set
    @Volatile var lastTargetEventTimeMs: Long = 0L
        private set
    @Volatile var lastNonTargetStateChangeTimeMs: Long = 0L
        private set
    @Volatile var lastTargetVisibleSignalPkg: String? = null
        private set
    @Volatile var lastTargetVisibleSignalMs: Long = 0L
        private set

    @Volatile var currentDiagPkg: String? = null
        private set
    @Volatile var meaningfulTargetSrcCount: Int = 0
        private set
    @Volatile var targetSrcNullCount: Int = 0
        private set
    @Volatile var targetNullBurstAfterNonTargetCount: Int = 0
        private set

    fun recordNonTransientEvent(
        packageName: String,
        eventType: Int,
        nowMs: Long,
        isEnabledTarget: Boolean,
        isSmallTransientOverlay: Boolean,
        hasSubstantialNonTargetBounds: Boolean
    ): Boolean {
        if (isEnabledTarget) {
            if (eventType == EVT_WINDOW_STATE_CHANGED ||
                eventType == EVT_WINDOW_CONTENT_CHANGED ||
                eventType == EVT_VIEW_SCROLLED ||
                eventType == EVT_VIEW_FOCUSED ||
                eventType == EVT_VIEW_TEXT_CHANGED ||
                eventType == EVT_VIEW_CLICKED
            ) {
                lastTargetEventPkg = packageName
                lastTargetEventTimeMs = nowMs
            }
            if (eventType == EVT_WINDOW_STATE_CHANGED ||
                eventType == EVT_VIEW_TEXT_CHANGED ||
                eventType == EVT_VIEW_FOCUSED ||
                eventType == EVT_VIEW_CLICKED
            ) {
                lastTargetVisibleSignalPkg = packageName
                lastTargetVisibleSignalMs = nowMs
            }
            return false
        }

        // Por qué: Solo borramos el estado activo si la ventana no objetivo ocupa un tamaño real de pantalla.
        // Servicios en segundo plano o transiciones sin bounds (source=null) como AOD, biometría o paneles OEM
        // no deben invalidar un chat de Messenger/Viber que sigue debajo.
        if (eventType == EVT_WINDOW_STATE_CHANGED && !isSmallTransientOverlay && hasSubstantialNonTargetBounds) {
            lastTargetEventPkg = null
            lastTargetEventTimeMs = 0L
            lastNonTargetStateChangeTimeMs = nowMs
            lastTargetVisibleSignalPkg = null
            lastTargetVisibleSignalMs = 0L
            targetNullBurstAfterNonTargetCount = 0
            return true
        }
        return false
    }

    /**
     * Registra si un evento del paquete objetivo expuso un nodo útil o `source=null`.
     * Devuelve `true` si cambió de paquete diagnóstico respecto al evento anterior.
     */
    fun recordTargetSourceObservation(
        packageName: String,
        eventType: Int,
        nowMs: Long,
        hasNonNullSource: Boolean,
        isMeaningfulVisibleSource: Boolean,
        isVisibleOrSubstantialContainer: Boolean
    ): Boolean {
        var pkgReset = false
        if (currentDiagPkg != packageName) {
            currentDiagPkg = packageName
            meaningfulTargetSrcCount = 0
            targetSrcNullCount = 0
            targetNullBurstAfterNonTargetCount = 0
            pkgReset = true
        }

        if (hasNonNullSource && isMeaningfulVisibleSource) {
            // Por qué: Nodos invisibles/minúsculos (chrome oculto) no deben contar como nodos válidos de contenido
            // ni anular el respaldo por ráfagas null necesario en Viber/Messenger antes de enfocar el teclado.
            meaningfulTargetSrcCount++
            targetNullBurstAfterNonTargetCount = 0
            if ((eventType == EVT_WINDOW_CONTENT_CHANGED || eventType == EVT_VIEW_SCROLLED) &&
                isVisibleOrSubstantialContainer
            ) {
                lastTargetVisibleSignalPkg = packageName
                lastTargetVisibleSignalMs = nowMs
            }
        } else if (!hasNonNullSource) {
            targetSrcNullCount++
            if (eventType == EVT_WINDOW_CONTENT_CHANGED || eventType == EVT_VIEW_SCROLLED) {
                targetNullBurstAfterNonTargetCount++
                if (meaningfulTargetSrcCount == 0 &&
                    targetNullBurstAfterNonTargetCount >= 4 &&
                    (lastNonTargetStateChangeTimeMs == 0L || nowMs - lastNonTargetStateChangeTimeMs >= 300L)
                ) {
                    lastTargetVisibleSignalPkg = packageName
                    lastTargetVisibleSignalMs = nowMs
                }
            }
        }
        return pkgReset
    }

    fun hasActiveVisibleTargetSignal(nowMs: Long): Boolean {
        val evtPkg = lastTargetEventPkg ?: return false
        return evtPkg == lastTargetVisibleSignalPkg &&
            lastTargetVisibleSignalMs > lastNonTargetStateChangeTimeMs &&
            (nowMs - lastTargetVisibleSignalMs) <= ForegroundTargetResolver.UNATTRIBUTED_WINDOW_GRACE_MS
    }

    fun onForegroundResolved(
        activeTargetPkg: String?,
        resolveReason: String,
        hasActiveOverlays: Boolean,
        isScrollQuieted: Boolean
    ): SessionTransitionEffect {
        val prevFg = lastForegroundTargetPackage
        if (activeTargetPkg == prevFg) {
            return SessionTransitionEffect(
                changed = false,
                shouldClearOverlays = false,
                shouldCancelWorkAndResetCaches = false,
                shouldClearRootCaches = false,
                shouldScheduleRecoveryScan = false,
                switchedToPackage = null
            )
        }
        lastForegroundTargetPackage = activeTargetPkg
        val prevSettled = lastSettledTargetPackage
        val switchedToDifferentTarget =
            activeTargetPkg != null && prevSettled != null && prevSettled != activeTargetPkg
        val explicitBlockingApp =
            resolveReason == "blocked_self_substantial" ||
                resolveReason.startsWith("other_active=") ||
                resolveReason.startsWith("other_win=")
        val genuineNonTargetDeparture =
            activeTargetPkg == null && (
                explicitBlockingApp ||
                    (lastNonTargetStateChangeTimeMs > 0L && lastNonTargetStateChangeTimeMs >= lastTargetEventTimeMs)
                )

        if (activeTargetPkg != null) {
            lastSettledTargetPackage = activeTargetPkg
        } else if (genuineNonTargetDeparture) {
            lastSettledTargetPackage = null
        }

        val shouldClearRootCaches = genuineNonTargetDeparture || switchedToDifferentTarget
        val shouldClearOverlays = activeTargetPkg == null || genuineNonTargetDeparture || switchedToDifferentTarget
        val shouldScheduleRecovery =
            prevFg == null &&
                activeTargetPkg != null &&
                prevSettled == activeTargetPkg &&
                !hasActiveOverlays &&
                isScrollQuieted

        return SessionTransitionEffect(
            changed = true,
            shouldClearOverlays = shouldClearOverlays,
            shouldCancelWorkAndResetCaches = genuineNonTargetDeparture || switchedToDifferentTarget,
            shouldClearRootCaches = shouldClearRootCaches,
            shouldScheduleRecoveryScan = shouldScheduleRecovery,
            switchedToPackage = activeTargetPkg
        )
    }

    fun reset() {
        lastForegroundTargetPackage = null
        lastSettledTargetPackage = null
        lastTargetEventPkg = null
        lastTargetEventTimeMs = 0L
        lastNonTargetStateChangeTimeMs = 0L
        lastTargetVisibleSignalPkg = null
        lastTargetVisibleSignalMs = 0L
        currentDiagPkg = null
        meaningfulTargetSrcCount = 0
        targetSrcNullCount = 0
        targetNullBurstAfterNonTargetCount = 0
    }
}

