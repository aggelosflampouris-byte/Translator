package com.example.translator

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controla la detección del compositor de mensajes, la visibilidad del teclado, la traducción
 * en vivo del borrador y la captura de correcciones enviadas para el aprendizaje local.
 *
 * Por qué: Desacopla toda la lógica del campo de entrada (`EditText`) respecto al servicio de
 * accesibilidad y elimina la división manual por saltos de línea duplicada con `MLKitTranslationEngine`.
 */
class DraftTranslationController(
    private val service: AccessibilityService,
    private val sessionTracker: ForegroundSessionTracker,
    private val diagnostics: DiagnosticRecorder,
    private val overlayManager: OverlayManager,
    private val learningStore: LearningStore,
    private val pipeline: TranslationPipeline,
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val postToMain: (epochCheck: Long?, () -> Unit) -> Unit,
    private val currentOverlayEpoch: () -> Long,
    private val recordOverlayDrawn: (epoch: Long) -> Unit,
    private val onCacheEvictRequired: () -> Unit,
    private val identifyLanguage: suspend (String) -> String? = { null }
) {
    companion object {
        private val PLACEHOLDER_TOKENS = setOf(
            "μήνυμα", "μηνυμα", "message", "message...", "type a message", "type a message...",
            "aa", "add a comment", "add a comment...", "write a message", "reply..."
        )

        /**
         * Resuelve la dirección de traducción del borrador sin exigir par con griego cuando hay autodetección.
         */
        internal fun resolveDraftDirection(
            text: String,
            manualTarget: String?,
            configuredSource: String,
            configuredTarget: String,
            detectedLang: String? = null
        ): Pair<String, String> {
            if (manualTarget != null) {
                val source = if (manualTarget == configuredTarget) configuredSource else configuredTarget
                return source to manualTarget
            }
            val greekLang = TranslateLanguage.GREEK
            val hasGreekAndLatinPair = (configuredSource == greekLang) xor (configuredTarget == greekLang)
            if (hasGreekAndLatinPair) {
                val otherLang = if (configuredSource == greekLang) configuredTarget else configuredSource
                return when (TextNormalizer.detectScriptType(text)) {
                    TextNormalizer.ScriptType.GREEK -> greekLang to otherLang
                    TextNormalizer.ScriptType.LATIN -> otherLang to greekLang
                    TextNormalizer.ScriptType.NEUTRAL -> configuredTarget to configuredSource
                }
            }
            if (!detectedLang.isNullOrBlank() &&
                detectedLang != "und" &&
                detectedLang == configuredSource &&
                detectedLang != configuredTarget
            ) {
                return configuredSource to configuredTarget
            }
            return configuredTarget to configuredSource
        }
    }

    @Volatile var cachedEditableNode: AccessibilityNodeInfo? = null
        private set
    @Volatile var cachedEditablePkg: String? = null
        private set
    @Volatile var pendingDraftSession: DraftSession? = null
        private set

    @Volatile private var typingJob: Job? = null
    @Volatile private var pendingTypingSource: String? = null
    @Volatile private var pendingTypingTargetLang: String? = null
    @Volatile private var pendingTypingWindowStartMs: Long = 0L

    @Volatile private var lastInsertedText: String? = null
    @Volatile private var lastInsertedAtMs: Long = 0L
    @Volatile private var lastInsertedObserved: Boolean = false
    @Volatile private var lastReplacedOriginalText: String? = null
    @Volatile private var undoBannerActive: Boolean = false
    private var undoBannerDismissJob: Job? = null
    @Volatile private var dismissedDraftText: String? = null
    @Volatile private var lastRenderedDraftSource: String? = null
    @Volatile private var lastRenderedDraftTop: Int = 0
    @Volatile private var lastRenderedDraftManualLang: String? = null
    @Volatile private var manualDraftTargetLang: String? = null

    private val sentMessageDetector = SentMessageDetector()

    fun isKeyboardVisible(
        candidateNode: AccessibilityNodeInfo? = null,
        preferredRoot: AccessibilityNodeInfo? = null
    ): Boolean {
        val displayBounds = ScreenMetricsHelper.currentBounds(service)
        val screenHeight = displayBounds.heightPx
        val screenWidth = displayBounds.widthPx
        val density = displayBounds.density

        try {
            val windowList = service.windows
            if (windowList != null) {
                for (w in windowList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val rect = Rect()
                        w.getBoundsInScreen(rect)
                        if (rect.top in 1 until (screenHeight - (95 * density).toInt()) &&
                            rect.height() > (120 * density).toInt()
                        ) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignorar
        }

        try {
            val input = candidateNode ?: findCurrentEditableNode(preferredRoot)
            if (input != null && isEditableNodeOnScreen(input, screenWidth, screenHeight)) {
                val inputRect = Rect()
                input.getBoundsInScreen(inputRect)
                val bottomOffset = screenHeight - inputRect.bottom
                // Por qué: Un buscador en la parte superior no indica teclado de composición activo;
                // solo evaluamos compositores en la mitad inferior o enfocados activamente.
                val isLowerHalfOrFocused = input.isFocused || inputRect.top >= (screenHeight * 0.35f).toInt()
                if (isLowerHalfOrFocused) {
                    if (bottomOffset <= (110 * density).toInt()) return false
                    if (bottomOffset > (120 * density).toInt()) return true
                }
            }
        } catch (_: Exception) {
            // Ignorar
        }

        return false
    }

    fun findCurrentEditableNode(preferredRoot: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val displayBounds = ScreenMetricsHelper.currentBounds(service)
        val screenWidth = displayBounds.widthPx
        val screenHeight = displayBounds.heightPx

        try {
            val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusedPkg = focused?.packageName?.toString()
            if (focused != null && sessionTracker.isEnabledTargetPackage(focusedPkg) && isEditableNodeOnScreen(focused, screenWidth, screenHeight)) {
                diagnostics.lastEditableFindVia = "focusInput"
                cachedEditableNode = focused
                cachedEditablePkg = focusedPkg
                return focused
            }
        } catch (_: Exception) {
            // Ignorar
        }

        try {
            val activeRoot = AccessibilityNodeSafeOps.safeRootInActiveWindow(service)
            val focused = activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusedPkg = focused?.packageName?.toString()
            if (focused != null && sessionTracker.isEnabledTargetPackage(focusedPkg) && isEditableNodeOnScreen(focused, screenWidth, screenHeight)) {
                diagnostics.lastEditableFindVia = "actFocus"
                cachedEditableNode = focused
                cachedEditablePkg = focusedPkg
                return focused
            }
        } catch (_: Exception) {
            // Ignorar
        }

        val root = preferredRoot ?: sessionTracker.resolveForeground(includeRoot = true).rootNode
        if (root != null) {
            diagnostics.lastEditableFindVia = "tree(${if (preferredRoot != null) "pref" else diagnostics.lastRootOrigin})"
            val treeChild = findBottomMostVisibleEditableChild(root, screenWidth, screenHeight)
            if (treeChild != null) {
                cachedEditableNode = treeChild
                cachedEditablePkg = treeChild.packageName?.toString() ?: root.packageName?.toString()
                return treeChild
            }
        }

        val cached = cachedEditableNode
        val cachedPkg = cachedEditablePkg
        val currentFg = sessionTracker.state.lastForegroundTargetPackage
        if (cached != null &&
            sessionTracker.isEnabledTargetPackage(cachedPkg) &&
            (currentFg == null || cachedPkg == currentFg)
        ) {
            try {
                if (cached.refresh() && isEditableNodeOnScreen(cached, screenWidth, screenHeight)) {
                    diagnostics.lastEditableFindVia = "cachedEdit"
                    return cached
                }
            } catch (_: Exception) {
                // Ignorar nodo cacheado reciclado
            }
        }
        return null
    }

    fun handleOutgoingTyping(
        eventSource: AccessibilityNodeInfo? = null,
        eventType: Int? = null,
        configuredSource: String,
        configuredTarget: String,
        isKeyboardTranslatorEnabled: Boolean,
        isLearningEnabled: Boolean
    ) {
        val eventEditable = try {
            eventSource?.takeIf { it.isEditable && it.refresh() }
        } catch (_: Exception) {
            null
        }
        val node = eventEditable ?: findCurrentEditableNode()

        if (node != null) {
            observeInputForLearning(node, isLearningEnabled)
            val editPkg = try { node.packageName?.toString() } catch (_: Exception) { null }
                ?: sessionTracker.state.lastForegroundTargetPackage
            if (sessionTracker.isEnabledTargetPackage(editPkg) && editPkg != null) {
                cachedEditableNode = node
                cachedEditablePkg = editPkg
                val via = if (eventEditable != null) "evtSrc(${eventType ?: 0})" else diagnostics.lastEditableFindVia
                diagnostics.recordEditableProbe(via, node)
                if (sessionTracker.cachedTargetRootNode == null || sessionTracker.cachedTargetRootPkg != editPkg) {
                    sessionTracker.extractTargetRootFromEventSource(node, editPkg, allowPartial = true)
                }
            }
        }

        if (!isKeyboardTranslatorEnabled) {
            cancelTypingAndHideDraft("disabled")
            return
        }

        if (node == null) {
            val isPassiveContentOrScan = eventType == null || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            val rootUnavailable = diagnostics.lastRootOrigin.startsWith("null") || diagnostics.lastRootOrigin.startsWith("evtPartial")
            if (isPassiveContentOrScan && overlayManager.currentDraftOverlayBounds != null && (rootUnavailable || isKeyboardVisible())) {
                diagnostics.setDraftDiag("keep(noEdit,via=${diagnostics.lastEditableFindVia},r=${diagnostics.lastRootOrigin})")
                return
            }
            diagnostics.setDraftDiag("clear(noEdit,via=${diagnostics.lastEditableFindVia},r=${diagnostics.lastRootOrigin})")
            postToMain(null) { overlayManager.removeDraftOverlay() }
            return
        }

        val rawText = try { node.text?.toString()?.trim() ?: "" } catch (_: Exception) { "" }
        val hintText = safeHintText(node) ?: ""

        val activeInserted = lastInsertedText
        if (activeInserted != null) {
            if (rawText == activeInserted) {
                lastInsertedObserved = true
                pendingTypingSource = null
                pendingTypingTargetLang = null
                pendingTypingWindowStartMs = 0L
                diagnostics.setDraftDiag("skip=inserted")
                if (!undoBannerActive) {
                    postToMain(null) { overlayManager.removeDraftOverlay() }
                }
                return
            }
            val elapsedSinceInsert = SystemClock.uptimeMillis() - lastInsertedAtMs
            // Por qué: Evita limpiar el escudo ante eventos rezagados con el borrador original justo tras pulsar Replace.
            if (!lastInsertedObserved && elapsedSinceInsert < 1200L) {
                return
            }
            lastInsertedText = null
            lastInsertedObserved = false
            dismissUndoBanner()
        }

        if (isPlaceholderText(rawText, hintText, node)) {
            typingJob?.cancel()
            dismissUndoBanner()
            pendingTypingSource = null
            pendingTypingTargetLang = null
            pendingTypingWindowStartMs = 0L
            manualDraftTargetLang = null
            dismissedDraftText = null
            diagnostics.setDraftDiag("empty_placeholder")
            postToMain(null) { overlayManager.removeDraftOverlay() }
            return
        }

        if (rawText == dismissedDraftText) {
            diagnostics.setDraftDiag("skip=dismissed")
            if (!undoBannerActive) {
                postToMain(null) { overlayManager.removeDraftOverlay() }
            }
            return
        }
        dismissedDraftText = null

        val currentRect = Rect()
        try {
            node.getBoundsInScreen(currentRect)
        } catch (_: Exception) {
            return
        }
        val density = ScreenMetricsHelper.currentBounds(service).density
        if (overlayManager.currentDraftOverlayBounds != null &&
            rawText == lastRenderedDraftSource &&
            manualDraftTargetLang == lastRenderedDraftManualLang &&
            kotlin.math.abs(currentRect.top - lastRenderedDraftTop) <= (4 * density).toInt()
        ) {
            diagnostics.setDraftDiag("rendered(len=${rawText.length},top=${currentRect.top})")
            return
        }

        val (_, draftTargetLang) = resolveDraftDirection(rawText, manualDraftTargetLang, configuredSource, configuredTarget)
        val nowTypingMs = SystemClock.uptimeMillis()

        if (typingJob?.isActive == true &&
            rawText == pendingTypingSource &&
            draftTargetLang == pendingTypingTargetLang
        ) {
            diagnostics.setDraftDiag("debounce(same,len=${rawText.length})")
            return
        }
        if (pendingTypingWindowStartMs == 0L || typingJob?.isActive != true) {
            pendingTypingWindowStartMs = nowTypingMs
        }
        val elapsedTypingMs = nowTypingMs - pendingTypingWindowStartMs
        if (typingJob?.isActive == true && elapsedTypingMs >= 450L) {
            return
        }

        pendingTypingSource = rawText
        pendingTypingTargetLang = draftTargetLang
        val effectiveTypingDelayMs = minOf(200L, (450L - elapsedTypingMs).coerceAtLeast(60L))

        diagnostics.setDraftDiag("queued(len=${rawText.length},dir=$draftTargetLang,via=${diagnostics.lastEditableFindVia})")
        typingJob?.cancel()
        val scanEpoch = currentOverlayEpoch()
        typingJob = scope.launch(workerDispatcher) {
            delay(effectiveTypingDelayMs)
            pendingTypingWindowStartMs = 0L

            val refreshedPassedNode = try { node.takeIf { it.refresh() } } catch (_: Exception) { null }
            val refreshedEventNode = try { eventSource?.takeIf { it.isEditable && it.refresh() } } catch (_: Exception) { null }
            val liveNode = findCurrentEditableNode() ?: refreshedPassedNode ?: refreshedEventNode ?: node
            val liveText = try { liveNode.text?.toString()?.trim() } catch (_: Exception) { null } ?: rawText
            val liveHint = safeHintText(liveNode) ?: hintText
            pendingTypingSource = null
            pendingTypingTargetLang = null

            if (isPlaceholderText(liveText, liveHint, liveNode)) {
                manualDraftTargetLang = null
                diagnostics.setDraftDiag("empty_placeholder_live")
                postToMain(null) { overlayManager.removeDraftOverlay() }
                return@launch
            }

            val freshRect = Rect()
            try { liveNode.getBoundsInScreen(freshRect) } catch (_: Exception) {}
            if (freshRect.isEmpty || freshRect.top <= 0) {
                try { node.getBoundsInScreen(freshRect) } catch (_: Exception) {}
            }
            if (freshRect.isEmpty || freshRect.top <= 0) {
                freshRect.set(currentRect)
            }

            val greekLang = TranslateLanguage.GREEK
            val hasGreekAndLatinPair = (configuredSource == greekLang) xor (configuredTarget == greekLang)
            val detectedNonGreek = if (manualDraftTargetLang == null && !hasGreekAndLatinPair) {
                identifyLanguage(liveText)
            } else {
                null
            }
            val (effectiveSourceLang, effectiveTargetLang) = resolveDraftDirection(
                text = liveText,
                manualTarget = manualDraftTargetLang ?: draftTargetLang.takeIf {
                    hasGreekAndLatinPair && TextNormalizer.detectScriptType(liveText) == TextNormalizer.ScriptType.NEUTRAL
                },
                configuredSource = configuredSource,
                configuredTarget = configuredTarget,
                detectedLang = detectedNonGreek
            )
            val effectiveBadge = effectiveTargetLang.uppercase()
            val preferredTerms = if (isLearningEnabled) {
                learningStore.promptTerms(effectiveSourceLang, effectiveTargetLang)
            } else {
                emptyList()
            }
            val hints = TranslationHints(preferredTerms = preferredTerms)

            // Por qué: `MLKitTranslationEngine.translate` ya gestiona párrafos multilínea y conserva
            // las líneas ya escritas en destino; no duplicamos un bucle manual por `\n`.
            val finalTranslatedDraft = pipeline.translate(liveText, effectiveSourceLang, effectiveTargetLang, hints)
            if (!finalTranslatedDraft.isNullOrBlank() && finalTranslatedDraft != liveText) {
                val liveNodePkg = try { liveNode.packageName?.toString() } catch (_: Exception) { null }
                val fgOrLiveTarget = sessionTracker.resolveForeground().packageName != null ||
                    sessionTracker.isEnabledTargetPackage(liveNodePkg)
                postToMain(scanEpoch) {
                    if (ServiceBridge.isTranslatingActive && fgOrLiveTarget && !isPlaceholderText(liveText, liveHint, liveNode)) {
                        recordOverlayDrawn(scanEpoch)
                        lastRenderedDraftSource = liveText
                        lastRenderedDraftTop = freshRect.top
                        lastRenderedDraftManualLang = manualDraftTargetLang
                        diagnostics.setDraftDiag("shown(len=${liveText.length}->${finalTranslatedDraft.length},dir=$effectiveTargetLang,top=${freshRect.top})")
                        overlayManager.updateDraftOverlay(
                            inputRect = freshRect,
                            text = finalTranslatedDraft,
                            targetLangCode = effectiveBadge,
                            showReplace = true,
                            onInsertClicked = {
                                pendingDraftSession = DraftSession(
                                    sourceText = liveText,
                                    machineTranslation = finalTranslatedDraft,
                                    sourceLang = effectiveSourceLang,
                                    targetLang = effectiveTargetLang,
                                    createdAtMillis = SystemClock.uptimeMillis()
                                )
                                scope.launch(workerDispatcher) {
                                    insertTranslatedTextIntoInput(
                                        text = finalTranslatedDraft,
                                        fallbackNode = liveNode,
                                        originalSourceText = liveText,
                                        fallbackInputRect = freshRect
                                    )
                                }
                            },
                            onDismissClicked = {
                                dismissedDraftText = liveText
                                manualDraftTargetLang = null
                                overlayManager.removeDraftOverlay()
                            },
                            onBadgeClicked = {
                                manualDraftTargetLang = if (effectiveTargetLang == configuredTarget) {
                                    configuredSource
                                } else {
                                    configuredTarget
                                }
                                lastRenderedDraftSource = null
                                dismissedDraftText = null
                                typingJob?.cancel()
                                scope.launch(workerDispatcher) {
                                    handleOutgoingTyping(
                                        eventSource = eventSource,
                                        eventType = eventType,
                                        configuredSource = configuredSource,
                                        configuredTarget = configuredTarget,
                                        isKeyboardTranslatorEnabled = isKeyboardTranslatorEnabled,
                                        isLearningEnabled = isLearningEnabled
                                    )
                                }
                            }
                        )
                    } else {
                        diagnostics.setDraftDiag("abort_post(act=${ServiceBridge.isTranslatingActive},fg=$fgOrLiveTarget)")
                        overlayManager.removeDraftOverlay()
                    }
                }
            } else {
                diagnostics.setDraftDiag("skip_tr(blank=${finalTranslatedDraft.isNullOrBlank()},same=${finalTranslatedDraft == liveText})")
                postToMain(null) {
                    overlayManager.removeDraftOverlay()
                }
            }
        }
    }

    fun onLanguageSettingsChanged() {
        manualDraftTargetLang = null
        lastRenderedDraftSource = null
    }

    fun resetOnDepartureOrStop() {
        typingJob?.cancel()
        undoBannerDismissJob?.cancel()
        undoBannerDismissJob = null
        undoBannerActive = false
        lastReplacedOriginalText = null
        cachedEditableNode = null
        cachedEditablePkg = null
        pendingTypingSource = null
        pendingTypingTargetLang = null
        pendingTypingWindowStartMs = 0L
        lastRenderedDraftSource = null
        manualDraftTargetLang = null
        dismissedDraftText = null
        lastInsertedText = null
        lastInsertedAtMs = 0L
        lastInsertedObserved = false
    }

    fun cancelTypingAndHideDraft(diagReason: String? = null) {
        typingJob?.cancel()
        undoBannerDismissJob?.cancel()
        undoBannerDismissJob = null
        undoBannerActive = false
        lastReplacedOriginalText = null
        pendingTypingSource = null
        pendingTypingTargetLang = null
        pendingTypingWindowStartMs = 0L
        if (diagReason != null) diagnostics.setDraftDiag(diagReason)
        postToMain(null) { overlayManager.removeDraftOverlay() }
    }

    private fun safeHintText(node: AccessibilityNodeInfo?): String? {
        if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            node.hintText?.toString()?.trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun isPlaceholderText(
        text: String,
        hintText: String,
        node: AccessibilityNodeInfo? = null
    ): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node?.isShowingHintText == true) {
                return true
            }
        } catch (_: Exception) {
            // Ignorar nodo reciclado
        }
        if (text.length < 2 || !text.any { it.isLetter() }) return true
        if (text.equals(hintText, ignoreCase = true)) return true
        val lower = text.lowercase()
        if (lower.startsWith("message #") || lower.startsWith("message @")) return true
        return PLACEHOLDER_TOKENS.contains(lower)
    }

    private fun isEditableNodeOnScreen(node: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Boolean {
        if (!node.isEditable || !node.isVisibleToUser) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width() > 0 &&
            rect.height() > 0 &&
            rect.right > 0 &&
            rect.left < screenWidth &&
            rect.bottom > 0 &&
            rect.top < screenHeight
    }

    private fun findBottomMostVisibleEditableChild(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE

        fun walk(current: AccessibilityNodeInfo, d: Int) {
            if (d > 64) return
            val isEditable = try { current.isEditable } catch (_: Exception) { false }
            if (isEditable) {
                if (isEditableNodeOnScreen(current, screenWidth, screenHeight)) {
                    val rect = Rect()
                    current.getBoundsInScreen(rect)
                    if (rect.top >= (screenHeight * 0.30f).toInt() && rect.bottom > bestBottom) {
                        bestBottom = rect.bottom
                        bestNode = current
                    }
                }
                return
            }
            val ch = try { current.childCount } catch (_: Exception) { 0 }
            for (i in 0 until ch) {
                val child = AccessibilityNodeSafeOps.safeChild(current, i) ?: continue
                walk(child, d + 1)
            }
        }

        walk(node, depth)
        return bestNode
    }

    private fun observeInputForLearning(inputNode: AccessibilityNodeInfo, isLearningEnabled: Boolean) {
        val rawText = try { inputNode.text?.toString()?.trim() ?: "" } catch (_: Exception) { "" }
        val hintText = safeHintText(inputNode) ?: ""
        val isEmptyField = rawText.isEmpty() ||
            rawText.equals(hintText, ignoreCase = true) ||
            PLACEHOLDER_TOKENS.contains(rawText.lowercase())

        val sentText = sentMessageDetector.observe(if (isEmptyField) "" else rawText) ?: return
        val session = pendingDraftSession ?: return
        pendingDraftSession = null

        val sessionAge = SystemClock.uptimeMillis() - session.createdAtMillis
        if (!isLearningEnabled || sessionAge > LearningStore.DRAFT_SESSION_TTL_MS) return

        scope.launch(workerDispatcher) {
            delay(700L)
            if (!isSentBubbleVisible(sentText)) {
                Log.d("Translator", "Learning skipped: sent bubble not confirmed")
                return@launch
            }
            val learned = learningStore.learnFromSentDraft(session, sentText)
            if (learned) {
                onCacheEvictRequired()
            }
        }
    }

    private fun isSentBubbleVisible(sentText: String): Boolean {
        return try {
            val root = sessionTracker.resolveForeground(includeRoot = true).rootNode ?: return false
            val probe = sentText.lines().first().take(60)
            root.findAccessibilityNodeInfosByText(probe).any { !it.isEditable }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun insertTranslatedTextIntoInput(
        text: String,
        fallbackNode: AccessibilityNodeInfo? = null,
        originalSourceText: String? = null,
        fallbackInputRect: Rect? = null
    ) {
        try {
            val node = findCurrentEditableNode() ?: fallbackNode
            if (node != null) {
                typingJob?.cancel()
                undoBannerDismissJob?.cancel()
                undoBannerActive = false
                lastReplacedOriginalText = originalSourceText
                lastInsertedObserved = false
                lastInsertedAtMs = SystemClock.uptimeMillis()
                lastInsertedText = text
                val nodeRect = Rect().apply {
                    try { node.getBoundsInScreen(this) } catch (_: Exception) {}
                }
                val anchorRect = if (nodeRect.top > 0) nodeRect else (fallbackInputRect ?: Rect())
                val inserted = applyTextToEditableNode(node, text)

                if (inserted) {
                    if (!originalSourceText.isNullOrBlank()) {
                        undoBannerActive = true
                        postToMain(null) {
                            overlayManager.showDraftUndoOverlay(
                                inputRect = anchorRect,
                                onUndoClicked = {
                                    scope.launch(workerDispatcher) {
                                        undoLastDraftInsert(node)
                                    }
                                },
                                onDismissClicked = { dismissUndoBanner() }
                            )
                        }
                        undoBannerDismissJob = scope.launch(workerDispatcher) {
                            delay(3800L)
                            dismissUndoBanner()
                        }
                    } else {
                        postToMain(null) {
                            overlayManager.removeDraftOverlay()
                            Toast.makeText(service, "Replaced in chat", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    lastInsertedText = null
                    lastReplacedOriginalText = null
                    withContext(Dispatchers.Main) {
                        ClipboardHelper.copySensitiveText(service, "Translated Text", text)
                        overlayManager.removeDraftOverlay()
                        Toast.makeText(service, "Copied to clipboard (Tap Paste in chat)", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    ClipboardHelper.copySensitiveText(service, "Translated Text", text)
                    overlayManager.removeDraftOverlay()
                    Toast.makeText(service, "Copied to clipboard (Tap Paste in chat)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("Translator", "Failed to set text in input", e)
            lastInsertedText = null
            lastReplacedOriginalText = null
            postToMain(null) {
                overlayManager.removeDraftOverlay()
            }
        }
    }

    private suspend fun applyTextToEditableNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val directSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        val pastedSuccess = if (!directSuccess) {
            withContext(Dispatchers.Main) {
                ClipboardHelper.copySensitiveText(service, "Translated Text", text)
            }
            val allRange = Bundle().apply {
                val existingLen = node.text?.length ?: 0
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingLen)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, allRange)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } else {
            false
        }
        if (directSuccess || pastedSuccess) {
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return true
        }
        return false
    }

    private fun dismissUndoBanner() {
        undoBannerDismissJob?.cancel()
        undoBannerDismissJob = null
        if (undoBannerActive) {
            undoBannerActive = false
            lastReplacedOriginalText = null
            postToMain(null) { overlayManager.removeDraftOverlay() }
        }
    }

    private suspend fun undoLastDraftInsert(fallbackNode: AccessibilityNodeInfo?) {
        val original = lastReplacedOriginalText ?: return
        undoBannerDismissJob?.cancel()
        undoBannerDismissJob = null
        undoBannerActive = false
        lastReplacedOriginalText = null
        // Por qué: Cancela la sesión pendiente para que Undo no sea aprendido como edición enviada ni dispare traducción inmediata.
        pendingDraftSession = null
        lastInsertedText = null
        lastInsertedObserved = false
        dismissedDraftText = original
        lastRenderedDraftSource = original
        val node = findCurrentEditableNode() ?: fallbackNode
        val restored = if (node != null) applyTextToEditableNode(node, original) else false
        postToMain(null) {
            overlayManager.removeDraftOverlay()
            val message = if (restored) "Original text restored" else "Could not restore draft"
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        }
    }
}

