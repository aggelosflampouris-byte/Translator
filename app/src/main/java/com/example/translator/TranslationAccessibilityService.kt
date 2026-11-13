package com.example.translator

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
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
    private lateinit var learningStore: LearningStore
    private lateinit var pipeline: TranslationPipeline
    private lateinit var diagnostics: DiagnosticRecorder
    private lateinit var sessionTracker: ForegroundSessionTracker
    private lateinit var treeScanner: NodeTreeScanner
    private lateinit var coordinator: TranslationScanCoordinator
    private lateinit var draftController: DraftTranslationController

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

    @Volatile private var configuredSource: String = AppDefaults.SOURCE_LANGUAGE
    @Volatile private var configuredTarget: String = AppDefaults.TARGET_LANGUAGE
    @Volatile private var isKeyboardTranslatorEnabled: Boolean = true
    @Volatile private var isGlossaryAndValidationEnabled: Boolean = true
    @Volatile private var isLearningEnabled: Boolean = true
    @Volatile private var translationMode: String = AppDefaults.MODE_ALL

    private var overlayWatchdogJob: Job? = null
    @Volatile private var lastBgEventFgCheckMs: Long = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            AppDefaults.KEY_ENABLED_PACKAGES -> {
                val updated = TargetAppCatalog.getEnabledPackages(prefs, packageName)
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
            }
            AppDefaults.KEY_SOURCE_LANGUAGE -> {
                configuredSource = prefs.getString(key, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
                draftController.onLanguageSettingsChanged()
                coordinator.invalidateAndClearAllOverlays()
                notifyPairChangeAndScan()
            }
            AppDefaults.KEY_TARGET_LANGUAGE -> {
                configuredTarget = prefs.getString(key, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
                draftController.onLanguageSettingsChanged()
                coordinator.invalidateAndClearAllOverlays()
                notifyPairChangeAndScan()
            }
            AppDefaults.KEY_KEYBOARD_ENABLED -> {
                isKeyboardTranslatorEnabled = prefs.getBoolean(key, true)
                if (!isKeyboardTranslatorEnabled) {
                    draftController.cancelTypingAndHideDraft("disabled")
                }
            }
            AppDefaults.KEY_AI_ENABLED -> {
                isGlossaryAndValidationEnabled = prefs.getBoolean(key, true)
            }
            AppDefaults.KEY_LEARNING_ENABLED -> {
                isLearningEnabled = prefs.getBoolean(key, true)
            }
            AppDefaults.KEY_TRANSLATE_MODE -> {
                translationMode = prefs.getString(key, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL
                if (translationMode == AppDefaults.MODE_TAP) {
                    coordinator.cancelDebouncedWorkOnly()
                    mainHandler.post { overlayManager.removeAllMessageOverlays() }
                } else {
                    coordinator.scheduleContentScan(100L)
                }
            }
            AppDefaults.KEY_OVERLAY_THEME -> {
                val theme = prefs.getString(key, AppDefaults.OVERLAY_THEME_AUTO) ?: AppDefaults.OVERLAY_THEME_AUTO
                overlayManager.setOverlayTheme(theme)
                mainHandler.post { overlayManager.removeAllMessageOverlays() }
                coordinator.scheduleContentScan(100L)
            }
        }
    }

    private fun notifyPairChangeAndScan() {
        if (ServiceBridge.isNotificationActive) {
            TranslationNotificationManager.showNotification(this, ServiceBridge.isTranslatingActive)
        }
        serviceScope.launch(Dispatchers.Default) {
            TranslationPipeline.preloadModels(this@TranslationAccessibilityService)
        }
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
            }
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
            }
        )

        val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        configuredSource = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
        configuredTarget = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
        isKeyboardTranslatorEnabled = prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, true)
        isGlossaryAndValidationEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true)
        isLearningEnabled = prefs.getBoolean(AppDefaults.KEY_LEARNING_ENABLED, true)
        translationMode = prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL
        sessionTracker.enabledTargetPackages = TargetAppCatalog.getEnabledPackages(prefs, packageName)
        overlayManager.setOverlayTheme(
            prefs.getString(AppDefaults.KEY_OVERLAY_THEME, AppDefaults.OVERLAY_THEME_AUTO) ?: AppDefaults.OVERLAY_THEME_AUTO
        )
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        ServiceBridge.isAccessibilityActive = true
        diagnostics.connectCount++
        updateRuntimeTraceNow("conn")

        ServiceBridge.onTranslationStateChanged = { isActive ->
            diagnostics.stateChangeCount++
            if (!isActive) {
                coordinator.stopAll()
                draftController.resetOnDepartureOrStop()
                coordinator.invalidateAndClearAllOverlays()
                inFlightKeys.clear()
                updateRuntimeTraceNow("off")
            } else {
                val latestPrefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
                sessionTracker.enabledTargetPackages = TargetAppCatalog.getEnabledPackages(latestPrefs, packageName)
                sessionTracker.syncAccessibilityPackageScope()
                coordinator.startPeriodicLoop()
                updateRuntimeTraceNow("on")
            }
        }

        ServiceBridge.onScanRequested = {
            coordinator.requestManualScan(delayBeforeAttemptMs = 0L)
        }

        startOverlayWatchdog()

        serviceScope.launch(Dispatchers.Default) {
            TranslationPipeline.preloadModels(this@TranslationAccessibilityService)
        }

        sessionTracker.syncAccessibilityPackageScope()
        if (ServiceBridge.isTranslatingActive) {
            coordinator.startPeriodicLoop()
        }
        updateRuntimeTraceNow("ready")
        Log.d("Translator", "TranslationAccessibilityService connected")
    }

    private fun startOverlayWatchdog() {
        overlayWatchdogJob?.cancel()
        overlayWatchdogJob = serviceScope.launch(workerDispatcher) {
            while (true) {
                delay(250L)
                if (ServiceBridge.isTranslatingActive && !coordinator.isLoopAlive) {
                    coordinator.startPeriodicLoop()
                }
                val fg = sessionTracker.resolveForeground(captureExternalSnapshot = false)
                applyForegroundTransition(fg.packageName, fg.resolveReason)
                if (overlayManager.hasOverlays() && (fg.packageName == null || !ServiceBridge.isTranslatingActive)) {
                    coordinator.invalidateAndClearAllOverlays()
                }
            }
        }
    }

    private fun applyForegroundTransition(activeTargetPkg: String?, resolveReason: String) {
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
        val isStateChange = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val needSourceNode = isTargetEvt || isStateChange

        // Por qué: Android recicla `event` al retornar `onAccessibilityEvent`; leemos escalares y nodo fuente
        // síncronamente antes de delegar los recorridos Binder al hilo trabajador.
        val rawSourceNode = if (needSourceNode && (ServiceBridge.isTranslatingActive || ServiceBridge.isBubbleRunning)) {
            try { event.source } catch (_: Exception) { null }
        } else {
            null
        }
        val rawIsEditable = try { rawSourceNode?.isEditable == true } catch (_: Exception) { false }
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

                if (!scrollDecision.isRelevantVerticalScroll) {
                    if (scrollDecision.reason.startsWith("layout_jitter") && isScrollQuieted()) {
                        coordinator.scheduleContentScan(220L, settleSource)
                    }
                    return
                }
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

        if (draftController.isKeyboardVisible(preferredRoot = rootNode)) {
            val edit = draftController.findCurrentEditableNode(preferredRoot = rootNode)
            val editRect = Rect().also { edit?.getBoundsInScreen(it) }
            val kbDiag = "abort=kb_visible | fg=$fgPkg (${activeProfile.id}) | root=${diagnostics.lastRootOrigin} | scr=${screenWidth}x$screenHeight | editRect=${editRect.flattenToString()}"
            ServiceBridge.lastScanDebug = kbDiag
            if (ServiceBridge.lastTreeScanDebug == "No non-null root scan yet.") {
                ServiceBridge.lastTreeScanDebug = kbDiag
            }
            diagnostics.recordPerAppDiag(fgPkg, "kbVis(r=${diagnostics.lastRootOrigin},ed=${edit != null})")
            postToMain(overlayEpoch) {
                overlayManager.removeAllMessageOverlays()
            }
            if (isKeyboardTranslatorEnabled) {
                draftController.handleOutgoingTyping(
                    eventSource = null,
                    eventType = null,
                    configuredSource = configuredSource,
                    configuredTarget = configuredTarget,
                    isKeyboardTranslatorEnabled = true,
                    isLearningEnabled = isLearningEnabled
                )
            }
            return ScanOutcome.TRANSLATED_OR_EMPTY
        } else if (isKeyboardTranslatorEnabled) {
            draftController.handleOutgoingTyping(
                eventSource = null,
                eventType = null,
                configuredSource = configuredSource,
                configuredTarget = configuredTarget,
                isKeyboardTranslatorEnabled = true,
                isLearningEnabled = isLearningEnabled
            )
        } else {
            postToMain(null) { overlayManager.removeDraftOverlay() }
        }

        val topInset = (activeProfile.topInsetDp * density).toInt()
        val bottomInset = (activeProfile.bottomInsetDp * density).toInt()
        val inputNode = draftController.findCurrentEditableNode(preferredRoot = rootNode)
        val effectiveBottom = if (inputNode != null) {
            val inputBounds = Rect()
            inputNode.getBoundsInScreen(inputBounds)
            if (inputBounds.top > topInset) {
                inputBounds.top + (5 * density).toInt()
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
            "skW=${stats.skippedWidget} skF=${stats.skippedFilter} skTiny=${stats.skippedTinyDesc} skBnd=${stats.skippedBubbleBounds} " +
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

        val currentValidBounds = dedupedCandidates.map { it.bounds }
        postToMain(overlayEpoch) {
            overlayManager.purgeOrphans(currentValidBounds)
        }

        val immediateResults = mutableListOf<Pair<Rect?, String>>()
        val pendingCandidates = mutableListOf<MessageCandidate>()
        val msgSource = configuredSource
        val msgTarget = configuredTarget

        for (candidate in dedupedCandidates) {
            val fastTranslation = pipeline.resolveFast(candidate.text, msgSource, msgTarget)
            if (fastTranslation != null) {
                immediateResults.add(Pair(candidate.bounds, fastTranslation))
            } else {
                pendingCandidates.add(candidate)
            }
        }

        if (immediateResults.isNotEmpty()) {
            postToMain(overlayEpoch) {
                val fgOk = sessionTracker.state.lastForegroundTargetPackage != null
                val genOk = generation == coordinator.scanGeneration.get()
                if (ServiceBridge.isTranslatingActive && fgOk && genOk && !coordinator.isScreenScrolling) {
                    coordinator.recordOverlayDrawn(overlayEpoch)
                    overlayManager.updateOverlays(immediateResults, isFullSync = pendingCandidates.isEmpty(), profile = activeProfile)
                    diagnostics.appendTreeScanDiag("imm=${immediateResults.size}")
                    diagnostics.recordPerAppDiag(fgPkg, "imm=${immediateResults.size}(r=${diagnostics.lastRootOrigin})")
                } else {
                    diagnostics.appendTreeScanDiag("immAbort(act=${ServiceBridge.isTranslatingActive},fg=$fgOk,gen=$genOk,scr=${coordinator.isScreenScrolling})")
                    diagnostics.recordPerAppDiag(fgPkg, "immAbort(fg=$fgOk,gen=$genOk,scr=${coordinator.isScreenScrolling})")
                }
            }
        }

        val preferredTerms = if (pendingCandidates.isNotEmpty() && isLearningEnabled) {
            learningStore.promptTerms(msgSource, msgTarget)
        } else {
            emptyList()
        }
        val hints = TranslationHints(preferredTerms = preferredTerms)

        for (candidate in pendingCandidates) {
            val cleanText = candidate.text
            val rect = candidate.bounds
            val flightKey = cacheKey(msgSource, msgTarget, cleanText)
            if (!inFlightKeys.add(flightKey)) continue

            serviceScope.launch(workerDispatcher) {
                try {
                    val detectedLang = identifyLanguageAsync(cleanText)
                    val isEligible = TranslationSenseEngine.isEligibleSourceText(
                        text = cleanText,
                        sourceLang = msgSource,
                        targetLang = msgTarget,
                        identifiedLang = detectedLang
                    )
                    if (!isEligible) {
                        diagnostics.appendTreeScanDiag("mlkit=ineligible(det=$detectedLang,len=${cleanText.length})")
                        diagnostics.recordPerAppDiag(fgPkg, "mlkitNo($detectedLang,len=${cleanText.length})")
                        return@launch
                    }

                    val translated = pipeline.translate(cleanText, msgSource, msgTarget, hints)
                    if (translated.isNullOrBlank()) {
                        diagnostics.appendTreeScanDiag("mlkit=null_output")
                        return@launch
                    }

                    val timeSinceScroll = SystemClock.uptimeMillis() - coordinator.lastScrollTimestampMs
                    if (generation != coordinator.scanGeneration.get()) {
                        diagnostics.appendTreeScanDiag("pendGen")
                        val remainingQuiet = if (coordinator.isScreenScrolling) {
                            360L
                        } else {
                            (370L - timeSinceScroll).coerceAtLeast(80L)
                        }
                        coordinator.scheduleContentScan(remainingQuiet)
                        return@launch
                    }

                    val liveFg = sessionTracker.resolveForeground().packageName != null
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
                        val fgOkPost = sessionTracker.state.lastForegroundTargetPackage != null
                        if (genOk && ServiceBridge.isTranslatingActive && fgOkPost && !coordinator.isScreenScrolling) {
                            coordinator.recordOverlayDrawn(overlayEpoch)
                            overlayManager.addOrUpdateOverlay(rect, translated, profile = activeProfile)
                            diagnostics.appendTreeScanDiag("overlay=ok(rect=${rect.left},${rect.top}-${rect.right},${rect.bottom})")
                            diagnostics.recordPerAppDiag(fgPkg, "ov=${dedupedCandidates.size}(r=${diagnostics.lastRootOrigin})")
                        } else {
                            diagnostics.appendTreeScanDiag("postAbort(gen=$genOk,act=${ServiceBridge.isTranslatingActive},fg=$fgOkPost,scr=${coordinator.isScreenScrolling})")
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
            val nodeWithText = AccessibilityNodeSafeOps.findFirstTextChild(sourceNode) ?: sourceNode
            val text = nodeWithText.text?.toString()?.takeIf { it.isNotBlank() }
                ?: nodeWithText.contentDescription?.toString()
                ?: ""
            if (text.isBlank()) return

            val rect = Rect()
            nodeWithText.getBoundsInScreen(rect)
            val bounds = ScreenMetricsHelper.currentBounds(this)
            AccessibilityNodeSafeOps.normalizeFullWidthMessageBounds(rect, bounds.widthPx, bounds.heightPx, bounds.density)
            if (!TranslationSenseEngine.isMessageBubble(rect, bounds.widthPx, activeProfile.alignmentMode)) return

            val sendersForTap = if (SenderPrefixCleaner.supportsAccessibilitySenderPrefix(activeProfile.id)) {
                treeScanner.snapshotCachedMessengerSenders()
            } else {
                null
            }
            val cleanText = NodeCandidateEvaluator.cleanCandidateText(
                raw = text,
                isOutgoing = rect.right > bounds.widthPx * 0.72f,
                discoveredSenders = sendersForTap
            ).trim()
            if (cleanText.isBlank() || !cleanText.any { it.isLetter() }) return
            if (!TranslationFilter.shouldTranslate(cleanText, configuredTarget, configuredSource, deferUnknownLatinToMlKit = true)) return

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
                    identifiedLang = detectedLang
                )
                if (!isEligible) return@launch

                val corrected = pipeline.translate(cleanText, msgSource, msgTarget, hints)
                if (!corrected.isNullOrBlank()) {
                    postToMain(epochAtClick) {
                        if (ServiceBridge.isTranslatingActive) {
                            coordinator.recordOverlayDrawn(epochAtClick)
                            overlayManager.showSingleMessagePill(rect, corrected, targetCode)
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
        ServiceBridge.isAccessibilityActive = false
        ServiceBridge.onTranslationStateChanged = null
        ServiceBridge.onScanRequested = null
        overlayWatchdogJob?.cancel()
        coordinator.stopAll()
        draftController.resetOnDepartureOrStop()
        sessionTracker.reset()
        treeScanner.clearSenderCache()
        diagnostics.clearAll()
        TranslationNotificationManager.cancelNotification(this)
        mainHandler.post {
            if (::overlayManager.isInitialized) {
                overlayManager.removeAllOverlays()
            }
        }
        if (resourcesReleased.compareAndSet(false, true)) {
            try {
                val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            } catch (e: Exception) {
                Log.w("Translator", "Error unregistering prefListener", e)
            }
            try {
                languageIdentifier.close()
            } catch (_: Exception) {
                // Ignorar error de cierre de ML Kit
            }
            TranslatorPool.releaseAll()
            synchronized(translationCache) { translationCache.evictAll() }
        }
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
