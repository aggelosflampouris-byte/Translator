package com.example.translator

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class TranslationAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager
    private val languageIdentifier = LanguageIdentification.getClient()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var typingJob: Job? = null
    private var scanJob: Job? = null

    // In-memory LRU cache for fast scroll rendering without unbounded SharedPreferences growth
    private val translationCache = LruCache<String, String>(500)

    private fun getCachedTranslation(text: String): String? {
        return translationCache.get(text)
    }

    private fun putCachedTranslation(text: String, translation: String) {
        translationCache.put(text, translation)
    }

    private var configuredSource: String = AppDefaults.SOURCE_LANGUAGE
    private var configuredTarget: String = AppDefaults.TARGET_LANGUAGE
    private var isKeyboardTranslatorEnabled: Boolean = false
    private var translationMode: String = AppDefaults.MODE_TAP
    private lateinit var hybridEngine: HybridTranslationEngine

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            AppDefaults.KEY_SOURCE_LANGUAGE -> {
                configuredSource = prefs.getString(key, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
            }
            AppDefaults.KEY_TARGET_LANGUAGE -> {
                configuredTarget = prefs.getString(key, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
            }
            AppDefaults.KEY_KEYBOARD_ENABLED -> {
                isKeyboardTranslatorEnabled = prefs.getBoolean(key, false)
            }
            AppDefaults.KEY_TRANSLATE_MODE -> {
                translationMode = prefs.getString(key, AppDefaults.MODE_TAP) ?: AppDefaults.MODE_TAP
                if (translationMode == AppDefaults.MODE_TAP) {
                    overlayManager.removeAllMessageOverlays()
                }
            }
        }
    }

    // State is published via ServiceBridge — no Service reference held in statics.

    data class MessageCandidate(
        val text: String,
        val bounds: Rect
    )

    private var activeScanLoopJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = OverlayManager(this)
        hybridEngine = HybridTranslationEngine(this)

        val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        configuredSource = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
        configuredTarget = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
        isKeyboardTranslatorEnabled = prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, false)
        translationMode = prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_TAP) ?: AppDefaults.MODE_TAP
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        // Publish to ServiceBridge — no static Service reference stored.
        ServiceBridge.isAccessibilityActive = true

        // Register the state-change callback so FloatingBubbleService can drive us
        // without importing or holding a reference to this service.
        ServiceBridge.onTranslationStateChanged = { isActive ->
            if (!isActive) {
                overlayManager.removeAllOverlays()
                typingJob?.cancel()
                scanDebounceJob?.cancel()
                activeScanLoopJob?.cancel()
            } else {
                startActiveScanning()
            }
        }

        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            // Por qué: Restringir a WhatsApp y eventos requeridos para preservar la privacidad y rendimiento
            info.packageNames = arrayOf("com.whatsapp", "com.whatsapp.w4b")
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            serviceInfo = info
        } catch (e: Exception) {
            Log.e("Translator", "Failed to configure serviceInfo programmatically", e)
        }

        Log.d("Translator", "TranslationAccessibilityService connected")
    }

    private fun isWhatsAppPackage(pkg: String?): Boolean {
        return pkg != null && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" || pkg.startsWith("com.whatsapp"))
    }

    private fun isIgnoredPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == applicationContext.packageName) return true
        if (pkg == "android" || pkg == "com.android.systemui") return true
        val lower = pkg.lowercase()
        if (lower.contains("keyboard") || lower.contains("honeyboard") || lower.contains("inputmethod") || lower.contains("ime")) {
            return true
        }
        return false
    }

    private fun isKeyboardPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        return lower.contains("keyboard") || lower.contains("honeyboard") ||
               lower.contains("inputmethod") || lower.contains("ime")
    }

    private fun isWhatsAppNodeTree(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null) return false
        val pkg = node.packageName?.toString() ?: ""
        if (isWhatsAppPackage(pkg)) return true
        if (depth >= 3) return false
        for (i in 0 until Math.min(node.childCount, 6)) {
            val child = node.getChild(i) ?: continue
            if (isWhatsAppNodeTree(child, depth + 1)) {
                return true
            }
        }
        return false
    }

    private fun isWhatsAppInForeground(): Boolean {
        try {
            val active = rootInActiveWindow
            if (active != null) {
                val pkg = active.packageName?.toString() ?: ""
                if (isWhatsAppPackage(pkg) || isWhatsAppNodeTree(active)) {
                    return true
                }
                if (pkg.isNotBlank() && !isKeyboardPackage(pkg)) {
                    return false
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                        val root = window.root ?: continue
                        return isWhatsAppPackage(root.packageName?.toString()) || isWhatsAppNodeTree(root)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!ServiceBridge.isTranslatingActive) return

        val packageName = event.packageName?.toString() ?: ""

        // Instant app exit cleanup:
        // If event comes from an external package (Home launcher, other app, SystemUI, etc.),
        // check immediately whether WhatsApp is no longer in the foreground.
        if (!isWhatsAppPackage(packageName) && !isKeyboardPackage(packageName)) {
            if (!isWhatsAppInForeground()) {
                mainHandler.post {
                    overlayManager.removeAllOverlays()
                }
                return
            }
        }

        if (isIgnoredPackage(packageName)) {
            return
        }

        if (!isWhatsAppPackage(packageName)) {
            if (!isWhatsAppInForeground()) {
                mainHandler.post {
                    overlayManager.removeAllOverlays()
                }
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                overlayManager.removeSingleMessagePill()
                if (!isKeyboardVisible()) {
                    overlayManager.removeDraftOverlay()
                }
                scheduleScan(120L, event.source)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (translationMode == AppDefaults.MODE_TAP) {
                    handleMessageClicked(event.source)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!isKeyboardVisible()) {
                    overlayManager.removeDraftOverlay()
                } else if (isKeyboardTranslatorEnabled) {
                    handleOutgoingTyping()
                }
                scheduleScan(200L, event.source)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (!isKeyboardVisible()) {
                    overlayManager.removeDraftOverlay()
                } else {
                    handleOutgoingTyping(event)
                }
            }
        }
    }

    private var scanDebounceJob: Job? = null
    // Por qué: Aplica un retraso controlado para no saturar con cancelaciones constantes en cada fotograma de desplazamiento
    private fun scheduleScan(delayMs: Long, eventSource: AccessibilityNodeInfo? = null) {
        if (translationMode != AppDefaults.MODE_ALL) return
        scanDebounceJob?.cancel()
        scanDebounceJob = serviceScope.launch {
            delay(delayMs)
            scanAndTranslateVisibleMessages(eventSource)
        }
    }

    private fun startActiveScanning() {
        if (translationMode != AppDefaults.MODE_ALL) return

        activeScanLoopJob?.cancel()
        activeScanLoopJob = serviceScope.launch {
            delay(50L)
            if (!ServiceBridge.isTranslatingActive) return@launch
            scanAndTranslateVisibleMessages()

            while (ServiceBridge.isTranslatingActive) {
                delay(2000L)
                if (!ServiceBridge.isTranslatingActive) break
                scanAndTranslateVisibleMessages()
            }
        }
    }

    private fun findWhatsAppRootNode(eventSource: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        if (eventSource != null) {
            try {
                if (isWhatsAppNodeTree(eventSource)) {
                    var current: AccessibilityNodeInfo = eventSource
                    while (true) {
                        val parent = current.parent ?: break
                        current = parent
                    }
                    return current
                }
            } catch (e: Exception) {
                // Ignore recycled node
            }
        }

        try {
            val active = rootInActiveWindow
            if (active != null && isWhatsAppNodeTree(active)) {
                return active
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying rootInActiveWindow", e)
        }

        try {
            val windowList = windows
            for (window in windowList) {
                if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root ?: continue
                    if (isWhatsAppNodeTree(root)) {
                        return root
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying windows in accessibility service", e)
        }

        return null
    }

    // Called on serviceScope background coroutine
    private fun scanAndTranslateVisibleMessages(eventSource: AccessibilityNodeInfo? = null): Int {
        if (!ServiceBridge.isTranslatingActive) {
            return 0
        }

        val rootNode = eventSource ?: rootInActiveWindow ?: findWhatsAppRootNode()
        if (rootNode == null) {
            if (!isWhatsAppInForeground()) {
                overlayManager.removeAllOverlays()
            }
            return 0
        }

        // When the soft keyboard is open, hide chat message bubbles so the screen is not overflooded.
        // When the soft keyboard is closed, ensure the typing draft overlay is removed.
        if (isKeyboardVisible()) {
            overlayManager.removeAllMessageOverlays()
            if (isKeyboardTranslatorEnabled) {
                handleOutgoingTyping()
            }
            return 0
        } else {
            overlayManager.removeDraftOverlay()
        }

        val candidates = mutableListOf<MessageCandidate>()
        val density = resources.displayMetrics.density
        val topInset = (48 * density).toInt()
        val bottomInset = (40 * density).toInt()
        val screenHeight = resources.displayMetrics.heightPixels

        // When keyboard is closed, scan all messages up to the top of the input bar (with 5dp tolerance)
        val inputNode = findCurrentEditableNode()
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

        collectMessageCandidates(rootNode, candidates, topInset, effectiveBottom, configuredSource, configuredTarget)

        val dedupedCandidates = filterOverlappingCandidates(candidates)

        if (dedupedCandidates.isEmpty()) {
            return 0
        }

        val immediateResults = mutableListOf<Pair<Rect?, String>>()
        val pendingCandidates = mutableListOf<MessageCandidate>()

        for (candidate in dedupedCandidates) {
            val cleanText = candidate.text
            val rect = candidate.bounds

            val msgSource = if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
            val msgTarget = configuredTarget

            // Check cache first (in-memory + persistent) for instantaneous rendering
            val cachedTranslation = getCachedTranslation(cleanText)
            if (cachedTranslation != null) {
                immediateResults.add(Pair(rect, cachedTranslation))
                continue
            }

            // Pre-translation idiom and sense resolution (e.g. holidays, slang, common questions)
            val idiomaticTranslation = TranslationSenseEngine.resolveIdiomPreTranslation(
                cleanText,
                msgSource,
                msgTarget
            )
            if (idiomaticTranslation != null) {
                putCachedTranslation(cleanText, idiomaticTranslation)
                immediateResults.add(Pair(rect, idiomaticTranslation))
                continue
            }

            pendingCandidates.add(candidate)
        }

        // Por qué: Renderizar de forma inmediata todas las traducciones ya resueltas (en caché o por modismos)
        // para que aparezcan en pantalla en menos de 2ms sin esperar a las peticiones de red.
        if (immediateResults.isNotEmpty()) {
            mainHandler.post {
                if (ServiceBridge.isTranslatingActive) {
                    overlayManager.updateOverlays(immediateResults, isFullSync = pendingCandidates.isEmpty())
                }
            }
        }

        // Por qué: Traducir de forma asíncrona cada mensaje pendiente sin bloquearse mutuamente,
        // actualizando su pastilla de forma individual tan pronto como el motor responda.
        for (candidate in pendingCandidates) {
            val cleanText = candidate.text
            val rect = candidate.bounds
            val msgSource = if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
            val msgTarget = configuredTarget

            serviceScope.launch {
                val normalizedText = if (msgSource == TranslateLanguage.ROMANIAN) {
                    TextNormalizer.restoreRomanianDiacritics(cleanText)
                } else {
                    cleanText
                }
                val protected = TextNormalizer.protectEmojis(normalizedText)

                val rawTranslated = hybridEngine.translate(protected.text, msgSource, msgTarget)
                if (rawTranslated != null && rawTranslated.isNotBlank()) {
                    val restoredEmojis = TextNormalizer.restoreEmojis(rawTranslated, protected.tokens)
                    val senseCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
                        originalText = normalizedText,
                        translatedText = restoredEmojis,
                        sourceLang = msgSource,
                        targetLang = msgTarget
                    )
                    putCachedTranslation(cleanText, senseCorrected)
                    mainHandler.post {
                        if (ServiceBridge.isTranslatingActive) {
                            overlayManager.addOrUpdateOverlay(rect, senseCorrected)
                        }
                    }
                }
            }
        }

        return candidates.size
    }

    private fun collectMessageCandidates(
        node: AccessibilityNodeInfo,
        outList: MutableList<MessageCandidate>,
        minY: Int,
        maxY: Int,
        configuredSource: String,
        configuredTarget: String
    ): Boolean {
        if (node.isEditable) {
            // Skip input fields during message scan (handled by handleOutgoingTyping)
            return false
        }

        var childFound = false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = collectMessageCandidates(child, outList, minY, maxY, configuredSource, configuredTarget)
            if (found) {
                childFound = true
            }
        }

        // If children already contained the message text, do not process container node
        if (childFound) {
            return true
        }

        // Real chat messages in WhatsApp are TextViews with non-null text.
        // Ignore ImageViews, ImageButtons, and checkmark icons.
        val className = node.className?.toString() ?: ""
        if (className.contains("Image", ignoreCase = true) || className.contains("Button", ignoreCase = true)) {
            return false
        }

        val rawText = node.text?.toString()
        if (rawText.isNullOrBlank()) {
            return false
        }

        val cleanText = TranslationFilter.cleanMessageText(rawText)
        if (cleanText.isNotBlank() && TranslationFilter.shouldTranslate(cleanText, configuredTarget)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Por qué: Busca el contenedor visual de la burbuja (hasta 2 niveles en la jerarquía)
            // para abarcar los límites completos del mensaje con padding, marcas de tiempo y bordes.
            var container = node.parent
            val screenWidth = resources.displayMetrics.widthPixels
            val density = resources.displayMetrics.density
            for (level in 0..1) {
                if (container != null) {
                    val pRect = Rect()
                    container.getBoundsInScreen(pRect)
                    if (pRect.width() <= screenWidth * 0.95f &&
                        pRect.height() <= (600 * density).toInt() &&
                        pRect.width() >= rect.width() &&
                        pRect.height() >= rect.height() &&
                        Rect.intersects(pRect, rect)) {
                        rect.set(pRect)
                        break
                    }
                    container = container.parent
                }
            }

            // Validate chat message bubble (both incoming and sent outgoing messages, excluding centered date pills)
            val isBubble = TranslationSenseEngine.isMessageBubble(rect.left, rect.right, screenWidth)

            if (isBubble && rect.width() > (30 * density).toInt() && rect.height() > (16 * density).toInt() &&
                rect.top >= minY && rect.bottom <= maxY) {
                outList.add(MessageCandidate(cleanText, rect))
                return true
            }
        }

        return false
    }

    private fun filterOverlappingCandidates(candidates: List<MessageCandidate>): List<MessageCandidate> {
        if (candidates.size <= 1) return candidates

        val accepted = ArrayList<MessageCandidate>(candidates.size)
        // Longest text first so the full message body is accepted over any sub-fragments
        val sorted = candidates.sortedByDescending { it.text.length }
        val tempIntersection = Rect()

        for (candidate in sorted) {
            val cRect = candidate.bounds
            var overlaps = false
            for (acc in accepted) {
                val aRect = acc.bounds
                if (Rect.intersects(aRect, cRect)) {
                    if (tempIntersection.setIntersect(aRect, cRect)) {
                        val intersectArea = tempIntersection.width().toLong() * tempIntersection.height()
                        val cArea = cRect.width().toLong() * cRect.height()
                        val aArea = aRect.width().toLong() * aRect.height()
                        val minArea = Math.min(cArea, aArea)
                        if (minArea > 0 && intersectArea > minArea * 0.20f) {
                            overlaps = true
                            break
                        }
                    }
                }
                val verticalCenterDist = Math.abs(
                    (aRect.top + aRect.bottom) / 2 - (cRect.top + cRect.bottom) / 2
                )
                if (verticalCenterDist < 20 * resources.displayMetrics.density &&
                    (aRect.contains(cRect) || cRect.contains(aRect))) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) {
                accepted.add(candidate)
            }
        }
        return accepted
    }

    private fun isKeyboardVisible(): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density

        // 1. Primary check: If WhatsApp input field is found, verify its vertical elevation
        try {
            val input = findCurrentEditableNode()
            if (input != null) {
                val inputRect = Rect()
                input.getBoundsInScreen(inputRect)
                if (inputRect.bottom > 0) {
                    val bottomOffset = screenHeight - inputRect.bottom
                    // If input is docked near the bottom of the screen (above navigation bar only),
                    // the soft keyboard is definitely closed.
                    if (bottomOffset <= (120 * density).toInt()) {
                        return false
                    }
                    // If input is displaced upwards above the keyboard by more than 150dp,
                    // the soft keyboard is definitely open.
                    if (bottomOffset > (150 * density).toInt()) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        // 2. Secondary check: AccessibilityWindowInfo for TYPE_INPUT_METHOD visibly on screen
        try {
            val windowList = windows
            if (windowList != null) {
                for (w in windowList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val rect = Rect()
                        w.getBoundsInScreen(rect)
                        // Must be visibly on screen with top above the bottom navigation margin
                        if (rect.top in 1 until (screenHeight - (120 * density).toInt()) &&
                            rect.height() > (150 * density).toInt()) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return false
    }

    private var lastInsertedText: String? = null

    private fun findCurrentEditableNode(): AccessibilityNodeInfo? {
        try {
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                return focused
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val activeRoot = rootInActiveWindow
            val focused = activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                return focused
            }
        } catch (e: Exception) {
            // Ignore
        }

        val root = findWhatsAppRootNode() ?: return null
        return findFirstEditableChild(root)
    }

    private fun findFirstEditableChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableChild(child)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private val PLACEHOLDER_TOKENS = setOf(
            "μήνυμα", "μηνυμα", "message", "type a message"
        )
    }

    private fun isPlaceholderText(text: String, hintText: String): Boolean {
        if (text.length < 2 || !text.any { it.isLetter() }) return true
        if (text.equals(hintText, ignoreCase = true)) return true
        return PLACEHOLDER_TOKENS.contains(text.lowercase())
    }

    private fun handleOutgoingTyping(event: AccessibilityEvent? = null) {
        if (!isKeyboardTranslatorEnabled || !isKeyboardVisible()) {
            overlayManager.removeDraftOverlay()
            return
        }

        val node = findCurrentEditableNode() ?: (if (event?.source?.isEditable == true) event.source else null) ?: return

        val rawText = node.text?.toString()?.trim() ?: ""
        val hintText = node.hintText?.toString()?.trim() ?: ""

        if (rawText == lastInsertedText) {
            overlayManager.removeDraftOverlay()
            return
        }
        lastInsertedText = null

        // Auto-detect script direction for zero-friction bilingual typing
        val scriptType = TextNormalizer.detectScriptType(rawText)
        val draftSourceLang = when (scriptType) {
            TextNormalizer.ScriptType.GREEK -> com.google.mlkit.nl.translate.TranslateLanguage.GREEK
            TextNormalizer.ScriptType.LATIN -> com.google.mlkit.nl.translate.TranslateLanguage.ROMANIAN
            TextNormalizer.ScriptType.NEUTRAL -> if (configuredSource != "AUTO") configuredTarget else AppDefaults.TARGET_LANGUAGE
        }
        val draftTargetLang = when (scriptType) {
            TextNormalizer.ScriptType.GREEK -> com.google.mlkit.nl.translate.TranslateLanguage.ROMANIAN
            TextNormalizer.ScriptType.LATIN -> com.google.mlkit.nl.translate.TranslateLanguage.GREEK
            TextNormalizer.ScriptType.NEUTRAL -> if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
        }
        val langBadge = draftTargetLang.uppercase()

        val isPlaceholder = isPlaceholderText(rawText, hintText)

        val inputRect = Rect()
        node.getBoundsInScreen(inputRect)

        if (isPlaceholder) {
            if (inputRect.top > 0) {
                overlayManager.updateDraftOverlay(
                    inputRect = inputRect,
                    text = "Type message...",
                    targetLangCode = langBadge,
                    showReplace = false,
                    onInsertClicked = {},
                    onDismissClicked = {
                        overlayManager.removeDraftOverlay()
                    }
                )
            }
            return
        }

        typingJob?.cancel()
        typingJob = serviceScope.launch {
            delay(150) // 150ms debounce while user is typing

            overlayManager.removeAllMessageOverlays()

            val liveNode = findCurrentEditableNode() ?: node
            val liveText = liveNode.text?.toString()?.trim() ?: rawText
            val liveHint = liveNode.hintText?.toString()?.trim() ?: hintText

            val livePlaceholder = isPlaceholderText(liveText, liveHint)

            val freshRect = Rect()
            liveNode.getBoundsInScreen(freshRect)
            if (freshRect.top <= 0) {
                node.getBoundsInScreen(freshRect)
            }

            if (livePlaceholder) {
                if (freshRect.top > 0) {
                    overlayManager.updateDraftOverlay(
                        inputRect = freshRect,
                        text = "Type message...",
                        targetLangCode = langBadge,
                        showReplace = false,
                        onInsertClicked = {},
                        onDismissClicked = {
                            overlayManager.removeDraftOverlay()
                        }
                    )
                }
                return@launch
            }

            // Re-detect script direction on debounced text
            val liveScriptType = TextNormalizer.detectScriptType(liveText)
            val effectiveSourceLang = when (liveScriptType) {
                TextNormalizer.ScriptType.GREEK -> com.google.mlkit.nl.translate.TranslateLanguage.GREEK
                TextNormalizer.ScriptType.LATIN -> com.google.mlkit.nl.translate.TranslateLanguage.ROMANIAN
                TextNormalizer.ScriptType.NEUTRAL -> draftSourceLang
            }
            val effectiveTargetLang = when (liveScriptType) {
                TextNormalizer.ScriptType.GREEK -> com.google.mlkit.nl.translate.TranslateLanguage.ROMANIAN
                TextNormalizer.ScriptType.LATIN -> com.google.mlkit.nl.translate.TranslateLanguage.GREEK
                TextNormalizer.ScriptType.NEUTRAL -> draftTargetLang
            }
            val effectiveBadge = effectiveTargetLang.uppercase()

            // Process message line-by-line to preserve paragraphs, line breaks, and mixed lines
            val lines = liveText.split("\n")
            val translatedLines = mutableListOf<String>()

            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) {
                    translatedLines.add(line)
                    continue
                }

                val normalizedLine = if (effectiveSourceLang == com.google.mlkit.nl.translate.TranslateLanguage.ROMANIAN) {
                    TextNormalizer.restoreRomanianDiacritics(trimmedLine)
                } else {
                    trimmedLine
                }

                val lineIdiom = TranslationSenseEngine.resolveIdiomPreTranslation(
                    normalizedLine,
                    effectiveSourceLang,
                    effectiveTargetLang
                )
                if (lineIdiom != null) {
                    translatedLines.add(lineIdiom)
                } else {
                    val protected = TextNormalizer.protectEmojis(normalizedLine)
                    val rawTranslated = translateTextAsync(effectiveSourceLang, effectiveTargetLang, protected.text)
                    if (rawTranslated != null && rawTranslated.isNotBlank() && rawTranslated != protected.text) {
                        val restoredEmojis = TextNormalizer.restoreEmojis(rawTranslated, protected.tokens)
                        val senseCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
                            originalText = normalizedLine,
                            translatedText = restoredEmojis,
                            sourceLang = effectiveSourceLang,
                            targetLang = effectiveTargetLang
                        )
                        translatedLines.add(senseCorrected)
                    } else {
                        translatedLines.add(trimmedLine)
                    }
                }
            }

            val finalTranslatedDraft = translatedLines.joinToString("\n")
            if (finalTranslatedDraft.isNotBlank() && finalTranslatedDraft != liveText) {
                overlayManager.updateDraftOverlay(
                    inputRect = freshRect,
                    text = finalTranslatedDraft,
                    targetLangCode = effectiveBadge,
                    showReplace = true,
                    onInsertClicked = {
                        insertTranslatedTextIntoInput(finalTranslatedDraft, liveNode)
                    },
                    onDismissClicked = {
                        overlayManager.removeDraftOverlay()
                    }
                )
            } else {
                overlayManager.removeDraftOverlay()
            }
        }
    }

    private fun insertTranslatedTextIntoInput(text: String, fallbackNode: AccessibilityNodeInfo? = null) {
        try {
            val node = findCurrentEditableNode() ?: fallbackNode
            if (node != null) {
                lastInsertedText = text
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (success) {
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    mainHandler.post {
                        Toast.makeText(this, "Replaced in chat", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Fallback to clipboard if direct action is restricted
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Translated Text", text))
                    mainHandler.post {
                        Toast.makeText(this, "Copied to clipboard (Tap Paste in chat)", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Translated Text", text))
                mainHandler.post {
                    Toast.makeText(this, "Copied to clipboard (Tap Paste in chat)", Toast.LENGTH_SHORT).show()
                }
            }
            overlayManager.removeDraftOverlay()
        } catch (e: Exception) {
            Log.e("Translator", "Failed to set text in input", e)
            overlayManager.removeDraftOverlay()
        }
    }

    private fun translateText(
        sourceLang: String,
        targetLang: String,
        text: String,
        onResult: (String?) -> Unit
    ) {
        // Por qué: Usa el motor híbrido (Gemini si hay clave, o ML Kit local) de manera asíncrona
        serviceScope.launch {
            val result = hybridEngine.translate(text, sourceLang, targetLang)
            onResult(result)
        }
    }

    private suspend fun translateTextAsync(
        sourceLang: String,
        targetLang: String,
        text: String
    ): String? {
        return hybridEngine.translate(text, sourceLang, targetLang)
    }

    /**
     * Traduce únicamente el mensaje específico sobre el que el usuario ha pulsado.
     * Por qué: Proporciona la experiencia 'Tap to Translate', evitando cubrir toda la pantalla con overlays
     * y permitiendo traducir con alta fidelidad solo el texto seleccionado.
     */
    private fun handleMessageClicked(sourceNode: AccessibilityNodeInfo?) {
        if (!ServiceBridge.isTranslatingActive) return
        if (sourceNode == null) return

        try {
            var nodeWithText: AccessibilityNodeInfo = sourceNode
            var text = nodeWithText.text?.toString() ?: ""

            if (text.isBlank() && sourceNode.childCount > 0) {
                for (i in 0 until sourceNode.childCount) {
                    val child = sourceNode.getChild(i) ?: continue
                    val childText = child.text?.toString() ?: ""
                    if (childText.isNotBlank()) {
                        nodeWithText = child
                        text = childText
                        break
                    }
                }
            }

            if (text.isBlank()) return
            val cleanText = TranslationFilter.cleanMessageText(text).trim()
            if (cleanText.isBlank() || !cleanText.any { it.isLetter() }) return

            val rect = Rect()
            nodeWithText.getBoundsInScreen(rect)
            val screenWidth = resources.displayMetrics.widthPixels
            if (!TranslationSenseEngine.isMessageBubble(rect, screenWidth)) return

            val script = TextNormalizer.detectScriptType(cleanText)
            val msgSource: String
            val msgTarget: String

            when (script) {
                TextNormalizer.ScriptType.GREEK -> {
                    msgSource = TranslateLanguage.GREEK
                    msgTarget = TranslateLanguage.ROMANIAN
                }
                TextNormalizer.ScriptType.LATIN -> {
                    msgSource = TranslateLanguage.ROMANIAN
                    msgTarget = TranslateLanguage.GREEK
                }
                TextNormalizer.ScriptType.NEUTRAL -> {
                    msgSource = configuredSource
                    msgTarget = configuredTarget
                }
            }

            val normalizedText = if (msgSource == TranslateLanguage.ROMANIAN) {
                TextNormalizer.restoreRomanianDiacritics(cleanText)
            } else {
                cleanText
            }
            val protected = TextNormalizer.protectEmojis(normalizedText)

            serviceScope.launch {
                val translated = hybridEngine.translate(protected.text, msgSource, msgTarget)
                if (!translated.isNullOrBlank()) {
                    val restored = TextNormalizer.restoreEmojis(translated, protected.tokens)
                    val corrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
                        originalText = normalizedText,
                        translatedText = restored,
                        sourceLang = msgSource,
                        targetLang = msgTarget
                    )
                    val targetCode = if (msgTarget == TranslateLanguage.GREEK) "EL" else "RO"
                    mainHandler.post {
                        overlayManager.showSingleMessagePill(rect, corrected, targetCode)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error handling clicked message", e)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ServiceBridge.isAccessibilityActive = false
        ServiceBridge.onTranslationStateChanged = null
        overlayManager.removeAllOverlays()
        try {
            val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
            Log.w("Translator", "Error unregistering prefListener in onUnbind", e)
        }
        TranslatorPool.releaseAll()
        translationCache.evictAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceBridge.isAccessibilityActive = false
        ServiceBridge.onTranslationStateChanged = null
        overlayManager.removeAllOverlays()
        try {
            val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
            Log.w("Translator", "Error unregistering prefListener in onDestroy", e)
        }
    }

    override fun onInterrupt() {
        overlayManager.removeAllOverlays()
    }
}
