package com.example.translator

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Orquestador ligero del servicio de accesibilidad de OverChat.
 *
 * Por qué: Delega la sesión de primer plano, el escaneo de árboles, la coordinación de hilos,
 * el borrador de entrada y las métricas diagnósticas en componentes modulares, ejecutando Binder IPC
 * fuera del hilo principal y manteniendo sincronizadas las operaciones sobre `OverlayManager`.
 */
class TranslationAccessibilityService : AccessibilityService() {

    private data class CapturedScrollSnapshot(
        val hasDeltaApi: Boolean,
        val dx: Int,
        val dy: Int,
        val windowId: Int,
        val scrollY: Int,
        val maxScrollY: Int,
        val fromIndex: Int,
        val toIndex: Int,
        val itemCount: Int
    )

    private lateinit var overlayManager: OverlayManager
    private lateinit var floatingSphereController: FloatingSphereOverlayController
    private lateinit var learningStore: LearningStore
    private lateinit var pipeline: TranslationPipeline
    private lateinit var diagnostics: DiagnosticRecorder
    private lateinit var sessionTracker: ForegroundSessionTracker
    private lateinit var treeScanner: NodeTreeScanner
    private lateinit var coordinator: TranslationScanCoordinator
    private lateinit var draftController: DraftTranslationController
    private lateinit var settingsObserver: ServiceSettingsObserver
    private lateinit var powerMonitor: ScreenPowerMonitor

    private val languageIdentifier = LanguageIdentification.getClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OverChat-A11yWorker").apply { isDaemon = true }
    }
    private val workerDispatcher: CoroutineDispatcher = workerExecutor.asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(workerDispatcher + SupervisorJob())

    private val translationCache = LruCache<String, String>(500)
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val resourcesReleased = AtomicBoolean(false)

    private val configuredSource: String get() = settingsObserver.configuredSource
    private val configuredTarget: String get() = settingsObserver.configuredTarget
    private val isKeyboardTranslatorEnabled: Boolean get() = settingsObserver.isKeyboardTranslatorEnabled
    private val isGlossaryAndValidationEnabled: Boolean get() = settingsObserver.isGlossaryAndValidationEnabled
    private val isLearningEnabled: Boolean get() = settingsObserver.isLearningEnabled
    private val translationMode: String get() = settingsObserver.translationMode

    private var overlayWatchdogJob: Job? = null
    private var pendingBatchJob: Job? = null
    private val activeBatchId = AtomicLong(0L)
    private var cumulativeSmallScrollDy: Int = 0
    private var cumulativeScrollWindowStartMs: Long = 0L
    @Volatile private var lastBgEventFgCheckMs: Long = 0L

    private fun initSettingsAndPowerMonitors(): Set<String> {
        settingsObserver = ServiceSettingsObserver(
            context = this,
            onEnabledPackagesChanged = { updated ->
                sessionTracker.enabledTargetPackages = updated
                sessionTracker.syncAccessibilityPackageScope()
                serviceScope.launch {
                    val fg = sessionTracker.resolveForeground().packageName
                    if (fg == null) {
                        coordinator.invalidateAndClearAllOverlays()
                    } else {
                        coordinator.scheduleContentScan(100L)
                    }
                }
            },
            onLanguagePairChanged = { _, _ ->
                draftController.onLanguageSettingsChanged()
                coordinator.invalidateAndClearAllOverlays()
                notifyPairChangeAndScan()
            },
            onKeyboardTranslatorChanged = { enabled ->
                if (!enabled) {
                    draftController.cancelTypingAndHideDraft("disabled")
                }
            },
            onLearningOrGlossaryChanged = {
                synchronized(translationCache) { translationCache.evictAll() }
                coordinator.scheduleContentScan(100L)
            },
            onTranslationModeChanged = { mode ->
                if (mode == AppDefaults.MODE_TAP) {
                    coordinator.cancelDebouncedWorkOnly()
                    coordinator.stopAll()
                    mainHandler.post { overlayManager.removeAllMessageOverlays() }
                } else {
                    if (ServiceBridge.isTranslatingActive) {
                        ensureOverlayWatchdogRunning()
                        coordinator.startPeriodicLoop()
                    }
                    coordinator.scheduleContentScan(100L)
                }
                if (ServiceBridge.isNotificationActive) {
                    TranslationNotificationManager.showNotification(this, ServiceBridge.isTranslatingActive)
                }
            },
            onOverlayThemeChanged = { theme ->
                overlayManager.setOverlayTheme(theme)
                mainHandler.post { overlayManager.removeAllMessageOverlays() }
                coordinator.scheduleContentScan(100L)
            },
            onIdleAutoPauseChanged = { minutes ->
                powerMonitor.onIdleMinutesUpdated(minutes)
            }
        )
        val initialPackages = settingsObserver.register()
        powerMonitor = ScreenPowerMonitor(
            context = this,
            scope = serviceScope,
            workerDispatcher = workerDispatcher,
            isTranslatingActive = { ServiceBridge.isTranslatingActive },
            onScreenOff = { handleScreenOff() },
            onScreenInteractive = { handleScreenInteractive() },
            onIdleAutoPauseTriggered = { reason -> performIdleAutoPause(reason) }
        )
        powerMonitor.register(settingsObserver.idleAutoPauseMinutes)
        return initialPackages
    }

    private fun notifyPairChangeAndScan() {
        TranslatorPool.evictExcept(
            setOf(
                configuredSource to configuredTarget,
                configuredTarget to configuredSource
            )
        )
        if (ServiceBridge.isNotificationActive) {
            TranslationNotificationManager.showNotification(this, ServiceBridge.isTranslatingActive)
        }
        serviceScope.launch(Dispatchers.Default) {
            TranslationPipeline.preloadModels(this@TranslationAccessibilityService)
        }
        coordinator.markScreenDirty()
        coordinator.scheduleContentScan(100L)
    }

    private fun cacheKey(sourceLang: String, targetLang: String, text: String): String {
        return "${sourceLang}_${targetLang}_${text.trim()}"
    }

    private fun getCachedTranslation(sourceLang: String, targetLang: String, text: String): String? {
        return synchronized(translationCache) {
            translationCache.get(cacheKey(sourceLang, targetLang, text))
        }
    }

    private fun putCachedTranslation(sourceLang: String, targetLang: String, text: String, translation: String) {
        synchronized(translationCache) {
            translationCache.put(cacheKey(sourceLang, targetLang, text), translation)
        }
    }

    private fun postToMain(epochCheck: Long? = null, action: () -> Unit) {
        mainHandler.post {
            if (epochCheck == null || coordinator.isOverlayEpochCurrent(epochCheck)) {
                action()
            }
        }
    }

    private fun isScrollQuieted(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (coordinator.isScreenScrolling && (nowMs - coordinator.lastScrollTimestampMs >= 450L)) {
            coordinator.isScreenScrolling = false
        }
        return !coordinator.isScreenScrolling && (nowMs - coordinator.lastScrollTimestampMs >= 350L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        resourcesReleased.set(false)

        overlayManager = OverlayManager(this)
        floatingSphereController = FloatingSphereOverlayController(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager,
            onToggleActive = { toggleTranslationFromSphere() },
            onSwapLanguages = { swapLanguagesFromSphere() },
            onToggleTranslationMode = { toggleTranslationModeFromSphere() },
            onOpenApp = { openMainAppFromSphere() },
            onTurnOffBubble = { stopAllFromSphere() }
        )
        learningStore = LearningStore.get(this)
        pipeline = TranslationPipeline(
            engine = MLKitTranslationEngine(),
            isGlossaryAndValidationEnabled = { isGlossaryAndValidationEnabled },
            memoryLookup = { src, tgt, txt -> if (isLearningEnabled) learningStore.lookupMemory(src, tgt, txt) else null },
            cacheLookup = ::getCachedTranslation,
            cacheStore = ::putCachedTranslation,
            learnedCorrectionsApplier = { src, tgt, txt -> if (isLearningEnabled) learningStore.applyConfirmedCorrections(src, tgt, txt) else txt }
        )
        diagnostics = DiagnosticRecorder()
        sessionTracker = ForegroundSessionTracker(this, diagnostics)
        treeScanner = NodeTreeScanner { pkg -> sessionTracker.isEnabledTargetPackage(pkg) }
        coordinator = TranslationScanCoordinator(
            scope = serviceScope,
            workerDispatcher = workerDispatcher,
            postToMain = { block -> mainHandler.post(block) },
            isTranslatingActive = { ServiceBridge.isTranslatingActive },
            isTapMode = { translationMode == AppDefaults.MODE_TAP },
            isScrollQuieted = ::isScrollQuieted,
            uptimeMs = { SystemClock.uptimeMillis() },
            isKeyboardVisible = { draftController.isKeyboardVisible() },
            onClearAllOverlays = { overlayManager.removeAllOverlays() },
            onClearMessageOverlays = { clearDraftToo ->
                overlayManager.removeAllMessageOverlays()
                if (clearDraftToo) overlayManager.removeDraftOverlay()
            },
            executeScan = ::executeScanOnWorker,
            onLoopTick = { isInitial ->
                diagnostics.scanLoopTicks++
                updateRuntimeTraceNow(if (isInitial) "loopInit" else "loop")
            },
            isInTargetForeground = { sessionTracker.state.lastForegroundTargetPackage != null },
            isInteractive = { powerMonitor.isInteractiveNow() }
        )
        draftController = DraftTranslationController(
            service = this,
            sessionTracker = sessionTracker,
            diagnostics = diagnostics,
            overlayManager = overlayManager,
            learningStore = learningStore,
            pipeline = pipeline,
            scope = serviceScope,
            workerDispatcher = workerDispatcher,
            postToMain = ::postToMain,
            currentOverlayEpoch = { coordinator.overlayEpoch.get() },
            recordOverlayDrawn = { epoch -> coordinator.recordOverlayDrawn(epoch) },
            onCacheEvictRequired = {
                synchronized(translationCache) { translationCache.evictAll() }
            },
            identifyLanguage = ::identifyLanguageAsync
        )

        sessionTracker.enabledTargetPackages = initSettingsAndPowerMonitors()
        overlayManager.setOverlayTheme(settingsObserver.overlayTheme)

        ServiceBridge.isAccessibilityActive = true
        diagnostics.connectCount++
        updateRuntimeTraceNow("conn")

        ServiceBridge.onTranslationStateChanged = { isActive ->
            diagnostics.stateChangeCount++
            sessionTracker.syncAccessibilityPackageScope(isActive)
            floatingSphereController.syncAppearance(isActive)
            if (!isActive) {
                powerMonitor.cancelIdlePauseTimer()
                overlayWatchdogJob?.cancel()
                overlayWatchdogJob = null
                pendingBatchJob?.cancel()
                pendingBatchJob = null
                coordinator.stopAll()
                draftController.resetOnDepartureOrStop()
                coordinator.invalidateAndClearAllOverlays()
                inFlightKeys.clear()
                updateRuntimeTraceNow("off")
            } else {
                powerMonitor.markTargetInteraction()
                powerMonitor.cancelIdlePauseTimer()
                val latestPrefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
                sessionTracker.enabledTargetPackages = TargetAppCatalog.getEnabledPackages(latestPrefs, packageName)
                if (powerMonitor.isInteractiveNow()) {
                    ensureOverlayWatchdogRunning()
                    if (translationMode != AppDefaults.MODE_TAP) {
                        coordinator.startPeriodicLoop()
                    }
                } else {
                    powerMonitor.scheduleIdleAutoPauseAfterScreenOff()
                }
                updateRuntimeTraceNow("on")
            }
        }

        ServiceBridge.onBubbleAppearanceSync = { isActive ->
            floatingSphereController.syncAppearance(isActive)
        }

        ServiceBridge.onBubbleVisibilityChanged = { visible ->
            if (visible) {
                ServiceBridge.isBubbleRunning = true
                floatingSphereController.show(ServiceBridge.isTranslatingActive)
            } else {
                ServiceBridge.isBubbleRunning = false
                floatingSphereController.hide()
            }
            TranslationTileService.requestSync(this)
            ServiceBridge.notifyUiStateChanged()
        }

        ServiceBridge.onScanRequested = {
            powerMonitor.markTargetInteraction()
            ensureOverlayWatchdogRunning()
            coordinator.requestManualScan(delayBeforeAttemptMs = 0L)
        }

        ServiceBridge.onLearningDataCleared = {
            synchronized(translationCache) { translationCache.evictAll() }
        }

        serviceScope.launch(Dispatchers.Default) {
            TranslationPipeline.preloadModels(this@TranslationAccessibilityService)
        }

        sessionTracker.syncAccessibilityPackageScope(ServiceBridge.isTranslatingActive)
        if (ServiceBridge.isBubbleRunning) {
            floatingSphereController.show(ServiceBridge.isTranslatingActive)
        }
        if (ServiceBridge.isTranslatingActive) {
            if (powerMonitor.isInteractiveNow()) {
                ensureOverlayWatchdogRunning()
                if (translationMode != AppDefaults.MODE_TAP) {
                    coordinator.startPeriodicLoop()
                }
            } else {
                powerMonitor.scheduleIdleAutoPauseAfterScreenOff()
            }
        }
        updateRuntimeTraceNow("ready")
        Log.d("Translator", "TranslationAccessibilityService connected")
    }

    private fun ensureOverlayWatchdogRunning() {
        if (!powerMonitor.isInteractiveNow()) return
        if (overlayWatchdogJob?.isActive == true) return
        if (!ServiceBridge.isTranslatingActive && !overlayManager.hasOverlays()) return
        overlayWatchdogJob = serviceScope.launch(workerDispatcher) {
            while ((ServiceBridge.isTranslatingActive || overlayManager.hasOverlays()) &&
                powerMonitor.isInteractiveNow()
            ) {
                // Por qué: Acelera la detección de salida a 250ms solo mientras hay burbujas visibles en pantalla,
                // reduciendo 4x los despertares en reposo cuando la pantalla está limpia.
                val delayMs = if (overlayManager.hasOverlays()) 250L else 1000L
                delay(delayMs)
                if (!ServiceBridge.isTranslatingActive && !overlayManager.hasOverlays()) break
                if (!powerMonitor.isInteractiveNow()) break
                if (ServiceBridge.isTranslatingActive &&
                    translationMode != AppDefaults.MODE_TAP &&
                    !coordinator.isLoopAlive
                ) {
                    coordinator.startPeriodicLoop()
                }
                val fg = sessionTracker.resolveForeground(captureExternalSnapshot = false)
                applyForegroundTransition(fg.packageName, fg.resolveReason)
                if (fg.packageName != null) {
                    powerMonitor.markTargetInteraction()
                } else if (powerMonitor.shouldTriggerIdleAutoPause()) {
                    performIdleAutoPause("idle_away")
                    break
                }
                if (overlayManager.hasOverlays() && (fg.packageName == null || !ServiceBridge.isTranslatingActive)) {
                    coordinator.invalidateAndClearAllOverlays()
                }
            }
            overlayWatchdogJob = null
        }
    }

    private fun handleScreenOff() {
        overlayWatchdogJob?.cancel()
        overlayWatchdogJob = null
        pendingBatchJob?.cancel()
        pendingBatchJob = null
        coordinator.stopAll()
        draftController.resetOnDepartureOrStop()
        coordinator.invalidateAndClearAllOverlays()
        inFlightKeys.clear()
        updateRuntimeTraceNow("scrOff")
    }

    private fun handleScreenInteractive() {
        if (!ServiceBridge.isTranslatingActive) {
            updateRuntimeTraceNow("scrOnIdle")
            return
        }
        if (powerMonitor.shouldTriggerIdleAutoPause()) {
            performIdleAutoPause("idle_scroff")
            return
        }
        coordinator.markScreenDirty()
        ensureOverlayWatchdogRunning()
        if (translationMode != AppDefaults.MODE_TAP && !coordinator.isLoopAlive) {
            coordinator.startPeriodicLoop()
        }
        updateRuntimeTraceNow("scrOn")
    }

    private fun performIdleAutoPause(reason: String) {
        if (!ServiceBridge.isTranslatingActive) return
        mainHandler.post {
            if (!ServiceBridge.isTranslatingActive) return@post
            ServiceBridge.isTranslatingActive = false
            ServiceBridge.onTranslationStateChanged?.invoke(false)
            if (ServiceBridge.isNotificationActive) {
                TranslationNotificationManager.showNotification(this, isTranslating = false)
            }
            TranslationTileService.requestSync(this)
            ServiceBridge.notifyUiStateChanged()
            updateRuntimeTraceNow(reason)
        }
    }

    private fun applyForegroundTransition(activeTargetPkg: String?, resolveReason: String) {
        if (activeTargetPkg != null) {
            powerMonitor.markTargetInteraction()
        }
        val effect = sessionTracker.applyTransition(
            activeTargetPkg = activeTargetPkg,
            resolveReason = resolveReason,
            hasActiveOverlays = overlayManager.hasOverlays(),
            isScrollQuieted = isScrollQuieted()
        )
        if (!effect.changed) return

        if (effect.switchedToPackage != null && !effect.shouldClearOverlays) {
            coordinator.advanceForegroundEpoch()
        }
        if (effect.shouldCancelWorkAndResetCaches) {
            coordinator.cancelDebouncedWorkOnly()
            inFlightKeys.clear()
            draftController.resetOnDepartureOrStop()
        }
        if (effect.shouldClearOverlays) {
            coordinator.invalidateAndClearAllOverlays()
        } else if (effect.shouldScheduleRecoveryScan) {
            coordinator.advanceForegroundEpoch()
            coordinator.scheduleContentScan(80L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""
        val eventType = event.eventType
        val nowMs = SystemClock.uptimeMillis()

        diagnostics.eventCount++
        diagnostics.lastEventPkg = packageName
        diagnostics.lastEventType = eventType
        diagnostics.lastEventTimeMs = nowMs

        if (sessionTracker.isKeyboardPackage(packageName)) {
            return
        }

        val isTargetEvt = sessionTracker.isEnabledTargetPackage(packageName)
        if (isTargetEvt && ServiceBridge.isTranslatingActive) {
            powerMonitor.markTargetInteraction(nowMs)
        }
        val isStateChange = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val needSourceNode = isTargetEvt || isStateChange

        // Por qué: Android recicla `event` al retornar `onAccessibilityEvent`; leemos escalares y nodo fuente
        // síncronamente antes de delegar los recorridos Binder al hilo trabajador.
        val rawSourceNode = if (needSourceNode && (ServiceBridge.isTranslatingActive || ServiceBridge.isBubbleRunning)) {
            try { event.source } catch (_: Exception) { null }
        } else {
            null
        }
        val rawIsEditable = if (isTargetEvt && ServiceBridge.isTranslatingActive) {
            try { rawSourceNode?.isEditable == true } catch (_: Exception) { false }
        } else {
            false
        }
        val scrollSnapshot = if (isTargetEvt && eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && ServiceBridge.isTranslatingActive) {
            val hasDelta = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            CapturedScrollSnapshot(
                hasDeltaApi = hasDelta,
                dx = if (hasDelta) event.scrollDeltaX else 0,
                dy = if (hasDelta) event.scrollDeltaY else 0,
                windowId = event.windowId,
                scrollY = event.scrollY,
                maxScrollY = if (hasDelta) event.maxScrollY else -1,
                fromIndex = event.fromIndex,
                toIndex = event.toIndex,
                itemCount = event.itemCount
            )
        } else {
            null
        }

        if (!isTargetEvt && !isStateChange) {
            // Por qué: Evita saturar Binder cuando apps externas (Chrome/Launcher) emiten ráfagas 2048;
            // el vigilante cada 250ms y `STATE_CHANGED` cubren los cambios reales de ventana.
            if (!ServiceBridge.isTranslatingActive && nowMs - diagnostics.lastTraceUpdateMs >= 450L) {
                serviceScope.launch(workerDispatcher) {
                    updateRuntimeTraceNow("evt")
                }
            }
            return
        }

        serviceScope.launch(workerDispatcher) {
            val capturedSource = sessionTracker.recordEventSignals(
                packageName = packageName,
                eventType = eventType,
                nowMs = nowMs,
                capturedSourceNode = rawSourceNode
            )

            if (!ServiceBridge.isTranslatingActive) {
                updateRuntimeTraceThrottled()
                return@launch
            }

            if (isTargetEvt || isStateChange || nowMs - lastBgEventFgCheckMs >= 150L) {
                lastBgEventFgCheckMs = nowMs
                val shouldSnapshot = isTargetEvt || isStateChange || (nowMs - diagnostics.lastTraceUpdateMs >= 450L)
                val fg = sessionTracker.resolveForeground(captureExternalSnapshot = shouldSnapshot)
                applyForegroundTransition(fg.packageName, fg.resolveReason)
            }
            updateRuntimeTraceThrottled(throttled = !isTargetEvt)

            val currentFg = sessionTracker.state.lastForegroundTargetPackage
            if (!isTargetEvt) {
                if (currentFg == null) {
                    coordinator.invalidateAndClearAllOverlays()
                }
                return@launch
            }

            if (currentFg == null) {
                ServiceBridge.lastScanDebug =
                    "abort=evt_target_no_fg | pkg=$packageName | evt=$eventType | ${diagnostics.lastResolveReason} | ${diagnostics.lastExternalWindowsSummary}"
                diagnostics.recordPerAppDiag(packageName, "evtNoFg(${diagnostics.lastResolveReason})")
                return@launch
            }

            dispatchTargetEventOnWorker(
                eventType = eventType,
                capturedSource = capturedSource,
                rawSource = rawSourceNode,
                rawIsEditable = rawIsEditable,
                scrollSnapshot = scrollSnapshot
            )
        }
    }

    private fun dispatchTargetEventOnWorker(
        eventType: Int,
        capturedSource: AccessibilityNodeInfo?,
        rawSource: AccessibilityNodeInfo?,
        rawIsEditable: Boolean,
        scrollSnapshot: CapturedScrollSnapshot?
    ) {
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val settleSource = capturedSource ?: rawSource
                val snap = scrollSnapshot ?: return
                val sourceHeight = if (settleSource != null) {
                    val rect = Rect()
                    try {
                        settleSource.getBoundsInScreen(rect)
                        rect.height()
                    } catch (_: Exception) {
                        0
                    }
                } else {
                    0
                }
                val screenHeight = ScreenMetricsHelper.currentBounds(this).heightPx
                val scrollDecision = ScrollEventClassifier.classify(
                    hasDeltaApi = snap.hasDeltaApi,
                    scrollDeltaX = snap.dx,
                    scrollDeltaY = snap.dy,
                    sourceHeightPx = sourceHeight,
                    screenHeightPx = screenHeight,
                    windowId = snap.windowId,
                    scrollY = snap.scrollY,
                    maxScrollY = snap.maxScrollY,
                    fromIndex = snap.fromIndex,
                    toIndex = snap.toIndex,
                    itemCount = snap.itemCount,
                    prevSignature = diagnostics.lastScrollPositionSig
                )
                diagnostics.lastScrollPositionSig = scrollDecision.nextSignature
                diagnostics.lastScrollDiag = scrollDecision.reason
                if (scrollDecision.isRelevantVerticalScroll) {
                    diagnostics.acceptedScrollCount++
                } else {
                    diagnostics.ignoredScrollCount++
                }

                val nowMs = SystemClock.uptimeMillis()
                if (!scrollDecision.isRelevantVerticalScroll) {
                    if (snap.hasDeltaApi && abs(snap.dy) in 1..15) {
                        if (nowMs - cumulativeScrollWindowStartMs > 350L) {
                            cumulativeSmallScrollDy = 0
                        }
                        cumulativeScrollWindowStartMs = nowMs
                        cumulativeSmallScrollDy += snap.dy
                        if (abs(cumulativeSmallScrollDy) >= 18) {
                            cumulativeSmallScrollDy = 0
                            coordinator.onScrollStarted(settleSource)
                            return
                        }
                    } else if (scrollDecision.reason.startsWith("layout_jitter") && isScrollQuieted()) {
                        coordinator.scheduleContentScan(220L, settleSource)
                    }
                    return
                }
                cumulativeSmallScrollDy = 0
                if (!scrollDecision.shouldInterruptOverlays) {
                    if (isScrollQuieted()) {
                        coordinator.scheduleContentScan(220L, settleSource)
                    }
                    return
                }
                coordinator.onScrollStarted(settleSource)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Por qué: La pastilla individual de toque solo debe mostrarse en modo Tap to Translate,
                // evitando solapar traducciones individuales sobre burbujas completas en modo ALL.
                if (translationMode == AppDefaults.MODE_TAP) {
                    handleMessageClicked(rawSource ?: capturedSource)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isKeyboardTranslatorEnabled) {
                    if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                        rawIsEditable ||
                        overlayManager.currentDraftOverlayBounds != null ||
                        draftController.cachedEditableNode != null
                    ) {
                        draftController.handleOutgoingTyping(
                            eventSource = rawSource,
                            eventType = eventType,
                            configuredSource = configuredSource,
                            configuredTarget = configuredTarget,
                            isKeyboardTranslatorEnabled = true,
                            isLearningEnabled = isLearningEnabled
                        )
                    }
                } else {
                    postToMain(null) { overlayManager.removeDraftOverlay() }
                }
                coordinator.scheduleContentScan(360L, capturedSource ?: rawSource)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (isKeyboardTranslatorEnabled) {
                    draftController.handleOutgoingTyping(
                        eventSource = rawSource,
                        eventType = eventType,
                        configuredSource = configuredSource,
                        configuredTarget = configuredTarget,
                        isKeyboardTranslatorEnabled = true,
                        isLearningEnabled = isLearningEnabled
                    )
                } else {
                    postToMain(null) { overlayManager.removeDraftOverlay() }
                }
            }
        }
    }

    private suspend fun executeScanOnWorker(
        overlayEpoch: Long,
        generation: Long,
        eventSourceToken: Any?,
        isManualRequest: Boolean
    ): ScanOutcome {
        if (!ServiceBridge.isTranslatingActive) return ScanOutcome.SKIPPED_MODE_OR_INACTIVE
        if (!isManualRequest && translationMode == AppDefaults.MODE_TAP) return ScanOutcome.SKIPPED_MODE_OR_INACTIVE
        if (!isScrollQuieted()) return ScanOutcome.SKIPPED_MODE_OR_INACTIVE

        val batchId = activeBatchId.incrementAndGet()
        pendingBatchJob?.cancel()
        inFlightKeys.clear()

        val eventSource = eventSourceToken as? AccessibilityNodeInfo
        var resolution = sessionTracker.resolveForeground(
            captureExternalSnapshot = true,
            includeRoot = true,
            eventSource = eventSource
        )
        if (isManualRequest && resolution.packageName == null) {
            // Por qué: Al tocar "Translate Screen" en la notificación, SystemUI tarda ~300ms en cerrarse.
            delay(350L)
            resolution = sessionTracker.resolveForeground(
                captureExternalSnapshot = true,
                includeRoot = true,
                eventSource = eventSource
            )
        }
        applyForegroundTransition(resolution.packageName, resolution.resolveReason)

        val fgPkg = resolution.packageName
        if (fgPkg == null) {
            val activePkg = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            val prefix = if (isManualRequest) "abort=scanReq_no_fg" else "abort=no_fg_target"
            ServiceBridge.lastScanDebug =
                "$prefix | activeWin=${activePkg ?: "null"} | ${diagnostics.lastResolveReason} | ${diagnostics.lastExternalWindowsSummary}"
            val diagPkg = sessionTracker.state.lastTargetEventPkg ?: sessionTracker.state.lastForegroundTargetPackage
            if (sessionTracker.isEnabledTargetPackage(diagPkg)) {
                diagnostics.recordPerAppDiag(diagPkg ?: "", "scanNoFg(${diagnostics.lastResolveReason})")
            }
            coordinator.invalidateAndClearAllOverlays()
            if (isManualRequest) {
                mainHandler.post {
                    Toast.makeText(applicationContext, "Open a supported chat app to translate messages", Toast.LENGTH_SHORT).show()
                }
            }
            return ScanOutcome.ABORT_NO_FG
        }

        val rootNode = resolution.rootNode
        if (rootNode == null) {
            ServiceBridge.lastScanDebug =
                "abort=null_root(${diagnostics.lastRootOrigin}) | fg=$fgPkg | ${diagnostics.lastResolveReason} | ${diagnostics.lastExternalWindowsSummary}"
            diagnostics.recordPerAppDiag(fgPkg, "nullRoot(${diagnostics.lastRootOrigin}|${diagnostics.lastResolveReason})")
            coordinator.invalidateAndClearAllOverlays()
            return ScanOutcome.ABORT_NULL_ROOT
        }

        val activeProfile = TargetAppCatalog.profileForPackage(
            rootNode.packageName?.toString() ?: fgPkg
        )
        val displayBounds = ScreenMetricsHelper.currentBounds(this)
        val density = displayBounds.density
        val screenWidth = displayBounds.widthPx
        val screenHeight = displayBounds.heightPx

        val currentInputNode = draftController.findCurrentEditableNode(preferredRoot = rootNode)
        if (draftController.isKeyboardVisible(candidateNode = currentInputNode, preferredRoot = rootNode)) {
            val editRect = Rect().also { currentInputNode?.getBoundsInScreen(it) }
            val kbDiag = "abort=kb_visible | fg=$fgPkg (${activeProfile.id}) | root=${diagnostics.lastRootOrigin} | scr=${screenWidth}x$screenHeight | editRect=${editRect.flattenToString()}"
            ServiceBridge.lastScanDebug = kbDiag
            if (ServiceBridge.lastTreeScanDebug == "No non-null root scan yet.") {
                ServiceBridge.lastTreeScanDebug = kbDiag
            }
            diagnostics.recordPerAppDiag(fgPkg, "kbVis(r=${diagnostics.lastRootOrigin},ed=${currentInputNode != null})")
            postToMain(overlayEpoch) {
                overlayManager.removeAllMessageOverlays()
            }
            if (isKeyboardTranslatorEnabled) {
                draftController.handleOutgoingTyping(
                    eventSource = currentInputNode,
                    eventType = null,
                    configuredSource = configuredSource,
                    configuredTarget = configuredTarget,
                    isKeyboardTranslatorEnabled = true,
                    isLearningEnabled = isLearningEnabled
                )
            }
            return ScanOutcome.TRANSLATED_OR_EMPTY
        } else if (isKeyboardTranslatorEnabled && (currentInputNode != null || overlayManager.currentDraftOverlayBounds != null)) {
            draftController.handleOutgoingTyping(
                eventSource = currentInputNode,
                eventType = null,
                configuredSource = configuredSource,
                configuredTarget = configuredTarget,
                isKeyboardTranslatorEnabled = true,
                isLearningEnabled = isLearningEnabled
            )
        } else if (!isKeyboardTranslatorEnabled) {
            postToMain(null) { overlayManager.removeDraftOverlay() }
        }

        val topInset = (activeProfile.topInsetDp * density).toInt()
        val bottomInset = (activeProfile.bottomInsetDp * density).toInt()
        val effectiveBottom = if (currentInputNode != null) {
            val inputBounds = Rect()
            currentInputNode.getBoundsInScreen(inputBounds)
            if (inputBounds.top > topInset) {
                (inputBounds.top - (4 * density).toInt()).coerceAtLeast(topInset + (40 * density).toInt())
            } else {
                screenHeight - bottomInset
            }
        } else {
            screenHeight - bottomInset
        }

        val stats = ScanStats()
        val dedupedCandidates = treeScanner.scanVisibleMessages(
            rootNode = rootNode,
            minY = topInset,
            maxY = effectiveBottom,
            displayBounds = displayBounds,
            configuredSource = configuredSource,
            configuredTarget = configuredTarget,
            profile = activeProfile,
            stats = stats
        )

        val baseDiag = "fg=$fgPkg(${activeProfile.id}) root=${diagnostics.lastRootOrigin} src=$configuredSource->$configuredTarget " +
            "scr=${screenWidth}x$screenHeight d=${"%.2f".format(density)} y=$topInset..$effectiveBottom " +
            "vis=${stats.visitedNodes} ed=${stats.editableNodes} t=${stats.nodesWithText} dsc=${stats.nodesWithDesc} " +
            "skW=${stats.skippedWidget} skF=${stats.skippedFilter}${stats.formatFilterBreakdown()} skTiny=${stats.skippedTinyDesc} skBnd=${stats.skippedBubbleBounds} " +
            "cands=${stats.passedCandidates}/${dedupedCandidates.size} smp=${stats.formatSamples()}"

        if (dedupedCandidates.isEmpty()) {
            diagnostics.setTreeScanDiag("$baseDiag | out=empty")
            diagnostics.recordPerAppDiag(fgPkg, "empty(r=${diagnostics.lastRootOrigin},vis=${stats.visitedNodes},ed=${stats.editableNodes})")
            postToMain(overlayEpoch) {
                overlayManager.removeAllMessageOverlays()
            }
            return ScanOutcome.TRANSLATED_OR_EMPTY
        }

        diagnostics.setTreeScanDiag("$baseDiag | out=translating(${dedupedCandidates.size})")
        diagnostics.recordPerAppDiag(fgPkg, "tr(${dedupedCandidates.size},r=${diagnostics.lastRootOrigin})")

        val expectedSlots = dedupedCandidates.map {
            OverlayManager.ExpectedBubbleSlot(it.bounds, it.text)
        }
        val immediateResults = mutableListOf<OverlayManager.OverlayRenderItem>()
        val pendingCandidates = mutableListOf<MessageCandidate>()
        val msgSource = configuredSource
        val msgTarget = configuredTarget
        val conversationMatchesSource = TranslationSenseEngine.hasStrongConversationSourceContext(
            texts = dedupedCandidates.map { it.text },
            sourceLang = msgSource,
            targetLang = msgTarget
        )

        for (candidate in dedupedCandidates) {
            val fastTranslation = pipeline.resolveFast(candidate.text, msgSource, msgTarget)
            if (fastTranslation != null) {
                immediateResults.add(
                    OverlayManager.OverlayRenderItem(
                        rect = candidate.bounds,
                        sourceText = candidate.text,
                        translatedText = fastTranslation,
                        isQuoted = candidate.isQuoted,
                        maxBottomPx = candidate.maxBottomPx
                    )
                )
            } else {
                pendingCandidates.add(candidate)
            }
        }

        // Por qué: Reconciliar burbujas huérfanas y renderizar inmediatas en el mismo paso en el hilo principal
        // evita parpadeos y elimina al instante globos viejos cuyo texto fuente ya no coincide con la ranura.
        postToMain(overlayEpoch) {
            val fgOk = sessionTracker.state.lastForegroundTargetPackage != null
            val genOk = generation == coordinator.scanGeneration.get()
            val batchOk = batchId == activeBatchId.get()
            if (ServiceBridge.isTranslatingActive && fgOk && genOk && batchOk && !coordinator.isScreenScrolling) {
                overlayManager.reconcileActiveBubbles(expectedSlots)
                if (immediateResults.isNotEmpty()) {
                    coordinator.recordOverlayDrawn(overlayEpoch)
                    overlayManager.updateOverlays(
                        results = immediateResults,
                        isFullSync = pendingCandidates.isEmpty(),
                        profile = activeProfile,
                        scanId = batchId
                    )
                    diagnostics.appendTreeScanDiag("imm=${immediateResults.size}")
                    diagnostics.recordPerAppDiag(fgPkg, "imm=${immediateResults.size}(r=${diagnostics.lastRootOrigin})")
                }
            } else if (immediateResults.isNotEmpty()) {
                diagnostics.appendTreeScanDiag("immAbort(act=${ServiceBridge.isTranslatingActive},fg=$fgOk,gen=$genOk,bat=$batchOk,scr=${coordinator.isScreenScrolling})")
                diagnostics.recordPerAppDiag(fgPkg, "immAbort(fg=$fgOk,gen=$genOk,bat=$batchOk,scr=${coordinator.isScreenScrolling})")
            }
        }

        val preferredTerms = if (pendingCandidates.isNotEmpty() && isLearningEnabled) {
            learningStore.promptTerms(msgSource, msgTarget)
        } else {
            emptyList()
        }
        val hints = TranslationHints(preferredTerms = preferredTerms)

        val batchSupervisor = SupervisorJob(serviceScope.coroutineContext[Job])
        pendingBatchJob = batchSupervisor
        val rescheduledOnGenMismatch = AtomicBoolean(false)
        val pendingByText = pendingCandidates.groupBy { it.text }

        for ((cleanText, slotsForText) in pendingByText) {
            val flightKey = cacheKey(msgSource, msgTarget, cleanText)
            if (!inFlightKeys.add(flightKey)) continue

            serviceScope.launch(workerDispatcher + batchSupervisor) {
                try {
                    val detectedLang = identifyLanguageAsync(cleanText)
                    val isEligible = TranslationSenseEngine.isEligibleSourceText(
                        text = cleanText,
                        sourceLang = msgSource,
                        targetLang = msgTarget,
                        identifiedLang = detectedLang,
                        conversationMatchesSource = conversationMatchesSource
                    )
                    if (!isEligible) {
                        diagnostics.appendTreeScanDiag("mlkit=ineligible(det=$detectedLang,len=${cleanText.length})")
                        diagnostics.recordPerAppDiag(fgPkg, "mlkitNo($detectedLang,len=${cleanText.length})")
                        postToMain(overlayEpoch) {
                            if (generation == coordinator.scanGeneration.get() && batchId == activeBatchId.get()) {
                                for (slot in slotsForText) {
                                    overlayManager.removeBubbleForSlot(slot.bounds, cleanText)
                                }
                            }
                        }
                        return@launch
                    }

                    val translated = pipeline.translate(cleanText, msgSource, msgTarget, hints)
                    if (translated.isNullOrBlank()) {
                        diagnostics.appendTreeScanDiag("mlkit=null_output")
                        postToMain(overlayEpoch) {
                            if (generation == coordinator.scanGeneration.get() && batchId == activeBatchId.get()) {
                                for (slot in slotsForText) {
                                    overlayManager.removeBubbleForSlot(slot.bounds, cleanText)
                                }
                            }
                        }
                        return@launch
                    }

                    val timeSinceScroll = SystemClock.uptimeMillis() - coordinator.lastScrollTimestampMs
                    if (generation != coordinator.scanGeneration.get() || batchId != activeBatchId.get()) {
                        diagnostics.appendTreeScanDiag("pendGen")
                        if (generation != coordinator.scanGeneration.get() && rescheduledOnGenMismatch.compareAndSet(false, true)) {
                            val remainingQuiet = if (coordinator.isScreenScrolling) {
                                360L
                            } else {
                                (370L - timeSinceScroll).coerceAtLeast(80L)
                            }
                            coordinator.scheduleContentScan(remainingQuiet)
                        }
                        return@launch
                    }

                    val liveFg = sessionTracker.state.lastForegroundTargetPackage != null ||
                        sessionTracker.resolveForeground().packageName != null
                    if (coordinator.isScreenScrolling ||
                        timeSinceScroll < 350L ||
                        !liveFg ||
                        !ServiceBridge.isTranslatingActive
                    ) {
                        diagnostics.appendTreeScanDiag("pendAbort(scr=${coordinator.isScreenScrolling},t=$timeSinceScroll,fg=$liveFg,act=${ServiceBridge.isTranslatingActive})")
                        diagnostics.recordPerAppDiag(fgPkg, "pendAbort(scr=${coordinator.isScreenScrolling},t=$timeSinceScroll,fg=$liveFg)")
                        return@launch
                    }

                    postToMain(overlayEpoch) {
                        val genOk = generation == coordinator.scanGeneration.get()
                        val batchOk = batchId == activeBatchId.get()
                        val fgOkPost = sessionTracker.state.lastForegroundTargetPackage != null
                        if (genOk && batchOk && ServiceBridge.isTranslatingActive && fgOkPost && !coordinator.isScreenScrolling) {
                            coordinator.recordOverlayDrawn(overlayEpoch)
                            for (slot in slotsForText) {
                                overlayManager.addOrUpdateOverlay(
                                    item = OverlayManager.OverlayRenderItem(
                                        rect = slot.bounds,
                                        sourceText = cleanText,
                                        translatedText = translated,
                                        isQuoted = slot.isQuoted,
                                        maxBottomPx = slot.maxBottomPx
                                    ),
                                    profile = activeProfile,
                                    scanId = batchId
                                )
                            }
                            val firstRect = slotsForText.first().bounds
                            diagnostics.appendTreeScanDiag("overlay=ok(cnt=${slotsForText.size},rect=${firstRect.left},${firstRect.top}-${firstRect.right},${firstRect.bottom})")
                            diagnostics.recordPerAppDiag(fgPkg, "ov=${dedupedCandidates.size}(r=${diagnostics.lastRootOrigin})")
                        } else {
                            diagnostics.appendTreeScanDiag("postAbort(gen=$genOk,bat=$batchOk,act=${ServiceBridge.isTranslatingActive},fg=$fgOkPost,scr=${coordinator.isScreenScrolling})")
                        }
                    }
                } finally {
                    inFlightKeys.remove(flightKey)
                }
            }
        }

        return ScanOutcome.TRANSLATED_OR_EMPTY
    }

    private fun handleMessageClicked(sourceNode: AccessibilityNodeInfo?) {
        if (!ServiceBridge.isTranslatingActive || sourceNode == null) return
        val fgPkg = sessionTracker.resolveForeground().packageName ?: return
        val nodePkg = try { sourceNode.packageName?.toString() ?: "" } catch (_: Exception) { "" }
        if (nodePkg.isNotBlank() && !sessionTracker.isEnabledTargetPackage(nodePkg)) return
        val activeProfile = TargetAppCatalog.profileForPackage(nodePkg.ifBlank { fgPkg })
        val epochAtClick = coordinator.overlayEpoch.get()

        try {
            val bounds = ScreenMetricsHelper.currentBounds(this)
            val candidate = treeScanner.extractClickedCandidate(
                sourceNode = sourceNode,
                displayBounds = bounds,
                configuredSource = configuredSource,
                configuredTarget = configuredTarget,
                profile = activeProfile
            ) ?: return
            val cleanText = candidate.text
            val rect = candidate.bounds

            val msgSource = configuredSource
            val msgTarget = configuredTarget
            val targetCode = msgTarget.uppercase()
            val preferredTerms = if (isLearningEnabled) learningStore.promptTerms(msgSource, msgTarget) else emptyList()
            val hints = TranslationHints(preferredTerms = preferredTerms)

            serviceScope.launch(workerDispatcher) {
                val detectedLang = identifyLanguageAsync(cleanText)
                val isEligible = TranslationSenseEngine.isEligibleSourceText(
                    text = cleanText,
                    sourceLang = msgSource,
                    targetLang = msgTarget,
                    identifiedLang = detectedLang,
                    alreadyCleaned = true
                )
                if (!isEligible) return@launch

                val corrected = pipeline.translate(cleanText, msgSource, msgTarget, hints)
                if (!corrected.isNullOrBlank()) {
                    postToMain(epochAtClick) {
                        if (ServiceBridge.isTranslatingActive) {
                            coordinator.recordOverlayDrawn(epochAtClick)
                            overlayManager.showSingleMessagePill(
                                targetRect = rect,
                                translatedText = corrected,
                                targetLangCode = targetCode,
                                originalText = cleanText
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error handling clicked message", e)
        }
    }

    private suspend fun identifyLanguageAsync(text: String): String? = suspendCancellableCoroutine { cont ->
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                if (cont.isActive) cont.resume(if (langCode == "und") null else langCode)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
            .addOnCanceledListener {
                if (cont.isActive) cont.resume(null)
            }
    }

    private fun updateRuntimeTraceNow(tag: String) {
        diagnostics.updateRuntimeTrace(
            tag = tag,
            enabledTargetPackages = sessionTracker.enabledTargetPackages,
            loopAlive = coordinator.isLoopAlive,
            isScreenScrolling = coordinator.isScreenScrolling,
            sessionState = sessionTracker.state
        )
    }

    private fun updateRuntimeTraceThrottled(throttled: Boolean = true) {
        diagnostics.maybeUpdateRuntimeTrace(
            throttled = throttled,
            enabledTargetPackages = sessionTracker.enabledTargetPackages,
            loopAlive = coordinator.isLoopAlive,
            isScreenScrolling = coordinator.isScreenScrolling,
            sessionState = sessionTracker.state
        )
    }

    private fun releaseServiceResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return
        ServiceBridge.isAccessibilityActive = false
        ServiceBridge.isTranslatingActive = false
        ServiceBridge.isBubbleRunning = false
        ServiceBridge.onTranslationStateChanged = null
        ServiceBridge.onBubbleVisibilityChanged = null
        ServiceBridge.onBubbleAppearanceSync = null
        ServiceBridge.onScanRequested = null
        ServiceBridge.onLearningDataCleared = null
        TranslationTileService.requestSync(this)
        ServiceBridge.notifyUiStateChanged()
        pendingBatchJob?.cancel()
        pendingBatchJob = null
        overlayWatchdogJob?.cancel()
        overlayWatchdogJob = null
        coordinator.stopAll()
        draftController.resetOnDepartureOrStop()
        sessionTracker.reset()
        diagnostics.clearAll()
        TranslationNotificationManager.cancelNotification(this)
        mainHandler.post {
            if (::floatingSphereController.isInitialized) {
                floatingSphereController.hide()
            }
            if (::overlayManager.isInitialized) {
                overlayManager.removeAllOverlays()
            }
        }
        if (::powerMonitor.isInitialized) {
            powerMonitor.unregister()
        }
        if (::settingsObserver.isInitialized) {
            try {
                settingsObserver.unregister()
            } catch (e: Exception) {
                Log.w("Translator", "Error unregistering settingsObserver", e)
            }
        }
        try {
            languageIdentifier.close()
        } catch (_: Exception) {
            // Ignorar error de cierre de ML Kit
        }
        TranslatorPool.releaseAll()
        synchronized(translationCache) { translationCache.evictAll() }
    }

    private fun toggleTranslationFromSphere() {
        val next = !ServiceBridge.isTranslatingActive
        ServiceBridge.isTranslatingActive = next
        ServiceBridge.onTranslationStateChanged?.invoke(next)
        if (ServiceBridge.isNotificationActive) {
            TranslationNotificationManager.showNotification(this, isTranslating = next)
        }
        TranslationTileService.requestSync(this)
        ServiceBridge.notifyUiStateChanged()
        Toast.makeText(
            this,
            if (next) "Translation Active" else "Translation Paused (Idle)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun swapLanguagesFromSphere() {
        val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val newSource = configuredTarget
        val newTarget = configuredSource
        prefs.edit()
            .putString(AppDefaults.KEY_SOURCE_LANGUAGE, newSource)
            .putString(AppDefaults.KEY_TARGET_LANGUAGE, newTarget)
            .apply()
        Toast.makeText(
            this,
            "Swapped: ${newSource.uppercase()} -> ${newTarget.uppercase()}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleTranslationModeFromSphere() {
        val nextMode = if (translationMode == AppDefaults.MODE_TAP) {
            AppDefaults.MODE_ALL
        } else {
            AppDefaults.MODE_TAP
        }
        getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(AppDefaults.KEY_TRANSLATE_MODE, nextMode)
            .apply()
        Toast.makeText(
            this,
            if (nextMode == AppDefaults.MODE_TAP) "Mode: Tap Message" else "Mode: Auto-Translate",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openMainAppFromSphere() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("Translator", "Failed to open MainActivity from floating sphere", e)
        }
    }

    // Por qué: Evita que ocultar la esfera deje la traducción activa de forma invisible en segundo plano
    private fun stopAllFromSphere() {
        ServiceBridge.sessionController.stopAllSurfaces()
        ServiceBridge.onTranslationStateChanged?.invoke(false)
        floatingSphereController.hide()
        TranslationNotificationManager.cancelNotification(this)
        TranslationTileService.requestSync(this)
        ServiceBridge.notifyUiStateChanged()
        Toast.makeText(this, "OverChat stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        releaseServiceResources()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseServiceResources()
        serviceScope.cancel()
        workerExecutor.shutdownNow()
    }

    override fun onInterrupt() {
        coordinator.invalidateAndClearAllOverlays()
    }
}
