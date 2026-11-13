package com.example.translator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class ForegroundResolutionAndRoot(
    val packageName: String?,
    val rootNode: AccessibilityNodeInfo?,
    val resolveReason: String
)

/**
 * Rastrea la sesión activa del usuario y resuelve de forma unificada el paquete y el nodo raíz.
 *
 * Por qué: Anteriormente `resolveForegroundTargetPackage` y `findActiveTargetRootNode` hacían pasadas
 * IPC separadas con reglas distintas y alternaban `serviceInfo` en caliente, rompiendo la caché de One UI
 * y perdiendo raíces envueltas en contenedores `null`/`android` antes del primer toque en teclado.
 */
class ForegroundSessionTracker(
    private val service: AccessibilityService,
    private val diagnostics: DiagnosticRecorder
) {
    val state = ForegroundSessionState()

    @Volatile var enabledTargetPackages: Set<String> = TargetAppCatalog.getDefaultEnabledPackages()
    @Volatile var cachedTargetRootPkg: String? = null
        private set
    @Volatile var cachedTargetRootNode: AccessibilityNodeInfo? = null
        private set
    @Volatile private var lastPartialTargetRootPkg: String? = null
    @Volatile private var lastPartialTargetRootNode: AccessibilityNodeInfo? = null
    @Volatile private var lastRootExtractAttemptMs: Long = 0L

    private val selfPackage: String
        get() = service.applicationContext.packageName

    fun isEnabledTargetPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return enabledTargetPackages.contains(pkg) &&
            !TargetAppCatalog.isBlockedSensitivePackage(pkg, selfPackage)
    }

    fun isKeyboardPackage(pkg: String?): Boolean = TargetAppCatalog.isKeyboardPackage(pkg)

    fun isTransientOverlayOrInputPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == selfPackage) return true
        if (ForegroundTargetResolver.isSystemTransientPackage(pkg)) return true
        return isKeyboardPackage(pkg)
    }

    fun syncAccessibilityPackageScope() {
        try {
            val currentInfo = service.serviceInfo
            val info = currentInfo ?: AccessibilityServiceInfo()
            // Por qué: Mantener `packageNames = null` cuando hay apps activas permite observar transiciones
            // del lanzador y del teclado OEM sin invalidar cachés internas de ventanas en One UI; las apps
            // no habilitadas se descartan en memoria al inicio de `onAccessibilityEvent`.
            val desiredPackages: Array<String>? = if (enabledTargetPackages.isEmpty()) {
                arrayOf("$selfPackage.none")
            } else {
                null
            }
            val desiredFlags = info.flags or
                AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            val desiredEventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            val desiredFeedback = AccessibilityServiceInfo.FEEDBACK_GENERIC

            val packagesMatch = (info.packageNames == null && desiredPackages == null) ||
                (info.packageNames != null && desiredPackages != null && info.packageNames.contentEquals(desiredPackages))
            if (currentInfo == null ||
                !packagesMatch ||
                info.flags != desiredFlags ||
                info.eventTypes != desiredEventTypes ||
                info.feedbackType != desiredFeedback
            ) {
                info.packageNames = desiredPackages
                info.flags = desiredFlags
                info.eventTypes = desiredEventTypes
                info.feedbackType = desiredFeedback
                service.serviceInfo = info
            }
        } catch (e: Exception) {
            Log.e("Translator", "Failed to configure serviceInfo programmatically", e)
        }
    }

    /**
     * Procesa señales ligeras de eventos externos/objetivo sobre el estado de sesión en primer plano.
     */
    fun recordEventSignals(
        packageName: String,
        eventType: Int,
        nowMs: Long,
        capturedSourceNode: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val bounds = ScreenMetricsHelper.currentBounds(service)
        val isTargetEvt = isEnabledTargetPackage(packageName)

        if (packageName.isNotBlank() &&
            !ForegroundTargetResolver.isSystemTransientPackage(packageName) &&
            (packageName != selfPackage || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ) {
            diagnostics.recordSeenExternalPackage(packageName)
            var isSmallTransientOverlay = false
            var hasSubstantialNonTargetBounds = false
            if (!isTargetEvt && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val srcBounds = try {
                    capturedSourceNode?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
                } catch (_: Exception) {
                    null
                }
                if (srcBounds != null && !srcBounds.isEmpty) {
                    val substantialW = srcBounds.width() >= bounds.widthPx / 3
                    val substantialH = srcBounds.height() >= bounds.heightPx / 4
                    isSmallTransientOverlay = !substantialW || !substantialH
                    hasSubstantialNonTargetBounds = substantialW && substantialH
                }
            }

            val cleared = state.recordNonTransientEvent(
                packageName = packageName,
                eventType = eventType,
                nowMs = nowMs,
                isEnabledTarget = isTargetEvt,
                isSmallTransientOverlay = isSmallTransientOverlay,
                hasSubstantialNonTargetBounds = hasSubstantialNonTargetBounds
            )
            if (cleared) {
                clearCachedRoots()
            }
        }

        if (!isTargetEvt || (!ServiceBridge.isTranslatingActive && !ServiceBridge.isBubbleRunning)) {
            return capturedSourceNode
        }

        var rawVisibleMeaningful = false
        var isVisibleOrSubstantial = false
        if (capturedSourceNode != null) {
            val srcBounds = Rect()
            val validBounds = try {
                capturedSourceNode.getBoundsInScreen(srcBounds)
                srcBounds.width() > 0 && srcBounds.height() > 0
            } catch (_: Exception) {
                false
            }
            val onScreenBounds = validBounds &&
                srcBounds.right > 0 &&
                srcBounds.bottom > 0 &&
                srcBounds.left < bounds.widthPx &&
                srcBounds.top < bounds.heightPx
            val substantialContainer = validBounds &&
                srcBounds.width() >= bounds.widthPx / 2 &&
                srcBounds.height() >= bounds.heightPx / 4
            val isVisible = try { capturedSourceNode.isVisibleToUser } catch (_: Exception) { false }
            isVisibleOrSubstantial = onScreenBounds && (isVisible || substantialContainer)
            val isFocusOrTextEvent = eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            rawVisibleMeaningful = isVisibleOrSubstantial || (validBounds && isFocusOrTextEvent)
        }

        val pkgReset = state.recordTargetSourceObservation(
            packageName = packageName,
            eventType = eventType,
            nowMs = nowMs,
            hasNonNullSource = capturedSourceNode != null,
            isMeaningfulVisibleSource = rawVisibleMeaningful,
            isVisibleOrSubstantialContainer = isVisibleOrSubstantial
        )
        if (pkgReset) {
            diagnostics.lastRootExtractDiag = "init"
            diagnostics.resetPerPackageProbes()
        }

        if (capturedSourceNode == null) {
            if (cachedTargetRootNode == null && lastPartialTargetRootNode == null && diagnostics.lastRootExtractDiag == "init") {
                diagnostics.lastRootExtractDiag = "srcNull(t=$eventType)"
            }
            return null
        }

        diagnostics.recordSrcSample(eventType, capturedSourceNode)
        val minExtractIntervalMs = when {
            cachedTargetRootPkg != packageName -> 0L
            cachedTargetRootNode == null -> 100L
            else -> 250L
        }
        if (nowMs - lastRootExtractAttemptMs >= minExtractIntervalMs) {
            lastRootExtractAttemptMs = nowMs
            return extractTargetRootFromEventSource(capturedSourceNode, packageName, allowPartial = true)
                ?: capturedSourceNode
        }
        return capturedSourceNode
    }

    /**
     * Resuelve el paquete activo en primer plano y, opcionalmente en la misma pasada IPC,
     * obtiene la raíz del árbol de mensajes para evitar lecturas duplicadas o divergentes.
     */
    fun resolveForeground(
        captureExternalSnapshot: Boolean = false,
        includeRoot: Boolean = false,
        eventSource: AccessibilityNodeInfo? = null
    ): ForegroundResolutionAndRoot {
        val displayBounds = ScreenMetricsHelper.currentBounds(service)
        val screenWidth = displayBounds.widthPx
        val screenHeight = displayBounds.heightPx

        var activePkg: String? = null
        var activeBounds: WindowRect? = null
        var activeInspection: WindowInspectionResult? = null
        var activeFetchMode = "-"

        try {
            val activeRoot = AccessibilityNodeSafeOps.safeRootInActiveWindow(service) { activeFetchMode = it }
            if (activeRoot != null) {
                diagnostics.lastRootFetchMode = activeFetchMode
                val inspection = AccessibilityNodeSafeOps.inspectWindowPackageAndRoot(
                    root = activeRoot,
                    enabledTargetPackages = enabledTargetPackages,
                    selfPackage = selfPackage
                )
                activeInspection = inspection
                activePkg = inspection.packageName
                val rect = Rect()
                activeRoot.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    activeBounds = WindowRect(rect.left, rect.top, rect.right, rect.bottom)
                }
            }
        } catch (_: Exception) {
            // Ignorar ventana reciclada
        }

        data class InspectedWindow(
            val type: Int,
            val layer: Int,
            val isActive: Boolean,
            val isFocused: Boolean,
            val fetchMode: String,
            val inspection: WindowInspectionResult
        )

        val inspectedWindows = if (includeRoot) mutableListOf<InspectedWindow>() else null
        val appSnapshots = mutableListOf<AppWindowSnapshot>()
        val extraFocusedSubstantialSnapshots = mutableListOf<AppWindowSnapshot>()
        val rawWinDiag = if (captureExternalSnapshot) mutableListOf<String>() else null

        try {
            val windowList = service.windows
            if (windowList != null) {
                for (window in windowList) {
                    var wFetch = "-"
                    val wRoot = AccessibilityNodeSafeOps.safeWindowRoot(window) { wFetch = it }
                    if (wFetch != "-") diagnostics.lastWinFetchMode = wFetch
                    val inspection = AccessibilityNodeSafeOps.inspectWindowPackageAndRoot(
                        root = wRoot,
                        enabledTargetPackages = enabledTargetPackages,
                        selfPackage = selfPackage
                    )
                    val wPkg = inspection.packageName ?: ""
                    val wRect = Rect()
                    window.getBoundsInScreen(wRect)
                    if (wRect.isEmpty && wRoot != null) {
                        try {
                            wRoot.getBoundsInScreen(wRect)
                        } catch (_: Exception) {
                            // Ignorar
                        }
                    }
                    if (rawWinDiag != null && rawWinDiag.size < 6) {
                        val flags = buildString {
                            if (window.isActive) append("A")
                            if (window.isFocused) append("F")
                        }.ifEmpty { "-" }
                        rawWinDiag.add("${wPkg.ifBlank { "null" }}:t${window.type}@${window.layer}($flags,${wRect.width()}x${wRect.height()})")
                    }
                    val boundsRect = if (wRect.isEmpty) WindowRect(0, 0, 0, 0) else WindowRect(wRect.left, wRect.top, wRect.right, wRect.bottom)
                    val snapshot = AppWindowSnapshot(
                        packageName = wPkg.ifBlank { null },
                        isActive = window.isActive,
                        isFocused = window.isFocused,
                        layer = window.layer,
                        bounds = boundsRect
                    )
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        appSnapshots.add(snapshot)
                    } else if ((window.isActive || window.isFocused) &&
                        (wPkg.isBlank() || !isTransientOverlayOrInputPackage(wPkg)) &&
                        ForegroundTargetResolver.isSubstantialWindow(boundsRect, screenWidth, screenHeight)
                    ) {
                        extraFocusedSubstantialSnapshots.add(snapshot)
                    }
                    inspectedWindows?.add(
                        InspectedWindow(
                            type = window.type,
                            layer = window.layer,
                            isActive = window.isActive,
                            isFocused = window.isFocused,
                            fetchMode = wFetch,
                            inspection = inspection
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Ignorar fallo transitorio de lista de ventanas
        }

        if (appSnapshots.none {
                !ForegroundTargetResolver.isSystemTransientPackage(it.packageName) &&
                    ForegroundTargetResolver.isSubstantialWindow(it.bounds, screenWidth, screenHeight)
            } && extraFocusedSubstantialSnapshots.isNotEmpty()
        ) {
            appSnapshots.addAll(extraFocusedSubstantialSnapshots)
        }

        val now = SystemClock.uptimeMillis()
        val eventAgeMs = if (state.lastTargetEventTimeMs > 0L) now - state.lastTargetEventTimeMs else Long.MAX_VALUE
        val hasVisibleSignal = state.hasActiveVisibleTargetSignal(now)

        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = activePkg,
            activeWindowBounds = activeBounds,
            appWindows = appSnapshots,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            recentEventPkg = state.lastTargetEventPkg,
            recentEventAgeMs = eventAgeMs,
            enabledTargetPackages = enabledTargetPackages,
            selfPackage = selfPackage,
            hasActiveVisibleTargetSignal = hasVisibleSignal
        )
        diagnostics.lastResolveReason = result.reason

        if (captureExternalSnapshot) {
            val isOnlySelfUi = (activePkg == selfPackage || activePkg == null) &&
                appSnapshots.isNotEmpty() &&
                appSnapshots.all {
                    it.packageName != null && (
                        it.packageName == selfPackage ||
                            ForegroundTargetResolver.isSystemTransientPackage(it.packageName)
                        )
                }
            val hasNonTrivialSnapshot = (activePkg != null && activePkg != selfPackage) ||
                (!isOnlySelfUi && rawWinDiag?.isNotEmpty() == true)
            if (hasNonTrivialSnapshot) {
                diagnostics.lastExternalWindowsSummary = "act=${activePkg ?: "null"} wins=[${rawWinDiag?.joinToString(",") ?: ""}]"
            }
        }

        val targetPkg = result.targetPackage
        if (!includeRoot || targetPkg == null) {
            if (targetPkg == null && includeRoot) {
                diagnostics.lastRootOrigin = "no_fg"
            }
            return ForegroundResolutionAndRoot(targetPkg, null, result.reason)
        }

        // Selección de raíz utilizando primero las inspecciones ya recopiladas en esta misma pasada.
        var hollowFallback: AccessibilityNodeInfo? = null
        var hollowDiag = ""

        if (activeInspection != null && activeInspection.packageName == targetPkg) {
            val activeTarget = activeInspection.targetRoot
            if (activeTarget != null) {
                val diag = "actWin(${diagnostics.lastRootFetchMode},ch=${activeTarget.childCount})"
                diagnostics.lastRootExtractDiag = diag
                diagnostics.lastRootOrigin = diag
                diagnostics.recordWindowRootProbe(diag, activeTarget)
                cacheFullRoot(targetPkg, activeTarget)
                return ForegroundResolutionAndRoot(targetPkg, activeTarget, result.reason)
            }
            if (activeInspection.hollowTargetRoot != null) {
                hollowFallback = activeInspection.hollowTargetRoot
                hollowDiag = "actWin(${diagnostics.lastRootFetchMode},ch=0)"
                diagnostics.lastRootExtractDiag = hollowDiag
            }
        }

        if (inspectedWindows != null) {
            val sorted = inspectedWindows
                .asSequence()
                .sortedWith(
                    compareByDescending<InspectedWindow> { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                        .thenByDescending { it.layer }
                        .thenByDescending { it.isActive || it.isFocused }
                )
            for (win in sorted) {
                if (win.inspection.packageName != targetPkg) continue
                val root = win.inspection.targetRoot
                val typeTag = if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) "" else ",t=${win.type}"
                if (root != null) {
                    val diag = "win@${win.layer}(${win.fetchMode},ch=${root.childCount}$typeTag)"
                    diagnostics.lastRootExtractDiag = diag
                    diagnostics.lastRootOrigin = diag
                    diagnostics.recordWindowRootProbe(diag, root)
                    cacheFullRoot(targetPkg, root)
                    return ForegroundResolutionAndRoot(targetPkg, root, result.reason)
                }
                if (hollowFallback == null && win.inspection.hollowTargetRoot != null) {
                    hollowFallback = win.inspection.hollowTargetRoot
                    hollowDiag = "win@${win.layer}(${win.fetchMode},ch=0$typeTag)"
                    diagnostics.lastRootExtractDiag = hollowDiag
                }
            }
        }

        extractTargetRootFromEventSource(eventSource, targetPkg, allowPartial = false)?.let {
            return ForegroundResolutionAndRoot(targetPkg, it, result.reason)
        }

        val minFullHeight = (screenHeight / 4).coerceAtLeast(200)
        val cached = cachedTargetRootNode
        var cacheStateDiag = if (cached != null) "valid" else "null"
        if (cached != null && cachedTargetRootPkg == targetPkg) {
            try {
                if (cached.refresh() && cached.packageName?.toString() == targetPkg) {
                    val rect = Rect()
                    cached.getBoundsInScreen(rect)
                    if (rect.height() >= minFullHeight) {
                        diagnostics.lastRootOrigin = "cache:${rect.height()}"
                        return ForegroundResolutionAndRoot(targetPkg, cached, result.reason)
                    }
                    cacheStateDiag = "h${rect.height()}"
                } else {
                    clearFullCachedRoot()
                    cacheStateDiag = "stale"
                }
            } catch (_: Exception) {
                clearFullCachedRoot()
                cacheStateDiag = "exc"
            }
        }

        extractTargetRootFromEventSource(eventSource, targetPkg, allowPartial = true)?.let {
            return ForegroundResolutionAndRoot(targetPkg, it, result.reason)
        }

        val cachedPartial = lastPartialTargetRootNode
        if (cachedPartial != null && lastPartialTargetRootPkg == targetPkg) {
            try {
                if (cachedPartial.refresh() && cachedPartial.packageName?.toString() == targetPkg) {
                    val rect = Rect()
                    cachedPartial.getBoundsInScreen(rect)
                    if (rect.height() >= 150) {
                        diagnostics.lastRootOrigin = "cachePart:${rect.height()}"
                        return ForegroundResolutionAndRoot(targetPkg, cachedPartial, result.reason)
                    }
                } else {
                    clearPartialCachedRoot()
                }
            } catch (_: Exception) {
                clearPartialCachedRoot()
            }
        }

        val focusInput = try { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        val focusInputPkg = try { focusInput?.packageName?.toString() } catch (_: Exception) { null }
        if (focusInput != null && (focusInputPkg == targetPkg || focusInputPkg == null)) {
            extractTargetRootFromEventSource(focusInput, targetPkg, allowPartial = true)?.let {
                return ForegroundResolutionAndRoot(targetPkg, it, result.reason)
            }
        }
        val focusA11y = try { service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) } catch (_: Exception) { null }
        val focusA11yPkg = try { focusA11y?.packageName?.toString() } catch (_: Exception) { null }
        if (focusA11y != null && (focusA11yPkg == targetPkg || focusA11yPkg == null)) {
            extractTargetRootFromEventSource(focusA11y, targetPkg, allowPartial = true)?.let {
                return ForegroundResolutionAndRoot(targetPkg, it, result.reason)
            }
        }

        if (hollowFallback != null) {
            diagnostics.lastRootOrigin = hollowDiag
            diagnostics.lastRootExtractDiag = hollowDiag
            return ForegroundResolutionAndRoot(targetPkg, hollowFallback, result.reason)
        }

        val focusDiag = "fI=${focusInput != null}(${focusInputPkg ?: "-"}),fA=${focusA11y != null}(${focusA11yPkg ?: "-"})"
        diagnostics.lastRootOrigin = "null(evt=${eventSource != null},cache=$cacheStateDiag,part=${cachedPartial != null},src=${state.meaningfulTargetSrcCount}/${state.targetSrcNullCount},$focusDiag,d=${diagnostics.lastRootExtractDiag})"
        return ForegroundResolutionAndRoot(targetPkg, null, result.reason)
    }

    fun extractTargetRootFromEventSource(
        source: AccessibilityNodeInfo?,
        expectedPkg: String,
        allowPartial: Boolean
    ): AccessibilityNodeInfo? {
        if (source == null) return null
        val screenHeight = ScreenMetricsHelper.currentBounds(service).heightPx
        val minFullHeight = (screenHeight / 4).coerceAtLeast(200)
        try {
            val winRoot = AccessibilityNodeSafeOps.safeWindowRoot(source.window) { diagnostics.lastWinFetchMode = it }
            if (winRoot != null) {
                val inspected = AccessibilityNodeSafeOps.inspectWindowPackageAndRoot(
                    root = winRoot,
                    enabledTargetPackages = enabledTargetPackages,
                    selfPackage = selfPackage,
                    requiredTargetPackage = expectedPkg
                )
                val targetWinRoot = inspected.targetRoot
                if (inspected.packageName == expectedPkg && targetWinRoot != null) {
                    val rect = Rect()
                    targetWinRoot.getBoundsInScreen(rect)
                    if (rect.height() >= minFullHeight) {
                        cacheFullRoot(expectedPkg, targetWinRoot)
                        diagnostics.lastRootOrigin = "evtWin:${rect.height()}"
                        diagnostics.lastRootExtractDiag = "winOk:${rect.height()}"
                        return targetWinRoot
                    } else if (rect.height() >= 150) {
                        cachePartialRoot(expectedPkg, targetWinRoot)
                        diagnostics.lastRootExtractDiag = "winPart:${rect.height()}"
                        if (allowPartial) {
                            diagnostics.lastRootOrigin = "evtWinPart:${rect.height()}"
                            return targetWinRoot
                        }
                    } else {
                        diagnostics.lastRootExtractDiag = "winSmall:${rect.height()}"
                    }
                } else {
                    diagnostics.lastRootExtractDiag = "winPkg:${inspected.packageName ?: "null"}"
                }
            }
        } catch (_: Exception) {
            // Ignora ventana reciclada del nodo fuente
        }

        try {
            var current: AccessibilityNodeInfo = source
            var topTargetNode: AccessibilityNodeInfo? =
                if (current.packageName?.toString() == expectedPkg) current else null
            var hops = 0
            var stoppedReason = "cap120"
            // Por qué: Jerarquías Litho/React/Compose profundas superan 40 niveles; subir hasta 120 ancestros
            // recupera el contenedor principal cuando window.root falla.
            while (hops < 120) {
                val parent = current.parent ?: run {
                    stoppedReason = "top"
                    break
                }
                hops++
                current = parent
                if (current.packageName?.toString() == expectedPkg) {
                    topTargetNode = current
                }
            }
            val candidate = topTargetNode ?: current
            val candPkg = candidate.packageName?.toString()
            if (candPkg == expectedPkg) {
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                val cls = candidate.className?.toString()?.substringAfterLast('.') ?: "?"
                if (bounds.height() >= minFullHeight) {
                    cacheFullRoot(expectedPkg, candidate)
                    diagnostics.lastRootOrigin = "evtTree:${bounds.height()}(h=$hops)"
                    diagnostics.lastRootExtractDiag = "treeOk:${bounds.height()}(h=$hops,$stoppedReason)"
                    return candidate
                }
                if (bounds.height() >= 150) {
                    cachePartialRoot(expectedPkg, candidate)
                    diagnostics.lastRootExtractDiag = "part:${bounds.height()}(h=$hops,$cls,$stoppedReason)"
                    if (allowPartial) {
                        diagnostics.lastRootOrigin = "evtPartial:${bounds.height()}(h=$hops)"
                        return candidate
                    }
                } else {
                    diagnostics.lastRootExtractDiag = "small:${bounds.height()}x${bounds.width()}(h=$hops,$cls,$stoppedReason)"
                }
            } else {
                diagnostics.lastRootExtractDiag = "diffPkg:${candPkg ?: "null"}(h=$hops,$stoppedReason)"
            }
        } catch (e: Exception) {
            diagnostics.lastRootExtractDiag = "exc:${e.javaClass.simpleName}"
        }
        return null
    }

    fun applyTransition(
        activeTargetPkg: String?,
        resolveReason: String,
        hasActiveOverlays: Boolean,
        isScrollQuieted: Boolean
    ): SessionTransitionEffect {
        val effect = state.onForegroundResolved(
            activeTargetPkg = activeTargetPkg,
            resolveReason = resolveReason,
            hasActiveOverlays = hasActiveOverlays,
            isScrollQuieted = isScrollQuieted
        )
        if (!effect.changed) return effect

        if (activeTargetPkg != null && cachedTargetRootPkg != activeTargetPkg) {
            clearCachedRoots()
        } else if (effect.shouldClearRootCaches) {
            clearCachedRoots()
        }
        return effect
    }

    fun cacheFullRoot(pkg: String, node: AccessibilityNodeInfo) {
        cachedTargetRootPkg = pkg
        cachedTargetRootNode = node
    }

    private fun cachePartialRoot(pkg: String, node: AccessibilityNodeInfo) {
        lastPartialTargetRootPkg = pkg
        lastPartialTargetRootNode = node
    }

    fun clearFullCachedRoot() {
        cachedTargetRootPkg = null
        cachedTargetRootNode = null
    }

    private fun clearPartialCachedRoot() {
        lastPartialTargetRootPkg = null
        lastPartialTargetRootNode = null
    }

    fun clearCachedRoots() {
        clearFullCachedRoot()
        clearPartialCachedRoot()
    }

    fun reset() {
        clearCachedRoots()
        state.reset()
    }
}

