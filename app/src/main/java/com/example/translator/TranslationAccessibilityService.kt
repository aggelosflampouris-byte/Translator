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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TranslationAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager
    private val languageIdentifier = LanguageIdentification.getClient()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var typingJob: Job? = null
    private var scanJob: Job? = null

    // LRU cache for translated messages to prevent re-translating during scroll
    private val translationCache = LruCache<String, String>(200)
    private val translators = mutableMapOf<String, Translator>()

    companion object {
        var isSharedInstanceActive = false
            private set
        var currentInstance: TranslationAccessibilityService? = null
            private set

        fun onTranslationStateChanged(isActive: Boolean) {
            currentInstance?.let { service ->
                if (!isActive) {
                    service.overlayManager.removeAllOverlays()
                    service.typingJob?.cancel()
                    service.scanJob?.cancel()
                    service.activeScanLoopJob?.cancel()
                } else {
                    service.startActiveScanning()
                }
            }
        }
    }

    data class MessageCandidate(
        val text: String,
        val bounds: Rect
    )

    private var activeScanLoopJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = this
        isSharedInstanceActive = true
        overlayManager = OverlayManager(this)

        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
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
            if (active != null && isWhatsAppNodeTree(active)) {
                return true
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val windowList = windows
            for (window in windowList) {
                if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root ?: continue
                    if (isWhatsAppNodeTree(root)) {
                        return true
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
        if (!FloatingBubbleService.isTranslatingActive) return

        val packageName = event.packageName?.toString() ?: ""
        if (isIgnoredPackage(packageName)) {
            return
        }

        if (!isWhatsAppPackage(packageName)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (!isWhatsAppInForeground()) {
                    overlayManager.removeAllOverlays()
                }
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!isKeyboardVisible()) {
                    overlayManager.removeDraftOverlay()
                }
                scanJob?.cancel()
                scanJob = serviceScope.launch {
                    delay(100)
                    scanAndTranslateVisibleMessages(event.source)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (event.source?.isEditable == true) {
                    handleOutgoingTyping(event)
                }
            }
        }
    }

    private fun startActiveScanning() {
        activeScanLoopJob?.cancel()
        activeScanLoopJob = serviceScope.launch {
            // Immediate rapid scans
            val initialDelays = listOf(50L, 200L, 500L, 1000L)
            for (d in initialDelays) {
                delay(d)
                if (!FloatingBubbleService.isTranslatingActive) return@launch
                val count = scanAndTranslateVisibleMessages()
                if (count > 0) {
                    break
                }
            }

            // Continuous polling while active (every 1.5s) to catch new messages or delayed models
            while (FloatingBubbleService.isTranslatingActive) {
                delay(1500L)
                if (!FloatingBubbleService.isTranslatingActive) break
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

    private fun scanAndTranslateVisibleMessages(eventSource: AccessibilityNodeInfo? = null): Int {
        if (!FloatingBubbleService.isTranslatingActive) return 0
        val rootNode = findWhatsAppRootNode(eventSource)
        if (rootNode == null) {
            if (!isWhatsAppInForeground()) {
                overlayManager.removeAllOverlays()
            }
            return 0
        }

        // When the soft keyboard is open, hide chat message bubbles so the screen is not overflooded
        if (isKeyboardVisible()) {
            overlayManager.removeAllMessageOverlays()
            return 0
        }

        val prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
        val configuredSource = prefs.getString("source_language", TranslateLanguage.ROMANIAN) ?: TranslateLanguage.ROMANIAN
        val configuredTarget = prefs.getString("target_language", TranslateLanguage.GREEK) ?: TranslateLanguage.GREEK
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

        collectMessageCandidates(rootNode, candidates, topInset, effectiveBottom, configuredTarget)

        if (candidates.isEmpty()) {
            return 0
        }

        val translations = mutableListOf<Pair<Rect?, String>>()
        var pendingCount = candidates.size

        for (candidate in candidates) {
            val cleanText = candidate.text
            val rect = candidate.bounds

            // Check in-memory cache first for fast scroll rendering
            val cachedTranslation = translationCache.get(cleanText)
            if (cachedTranslation != null) {
                translations.add(Pair(rect, cachedTranslation))
                pendingCount--
                checkBatchComplete(pendingCount, translations)
                continue
            }

            // 1. Pre-translation idiom and sense resolution (e.g. holidays, slang, common questions)
            val idiomaticTranslation = TranslationSenseEngine.resolveIdiomPreTranslation(
                cleanText,
                configuredSource,
                configuredTarget
            )
            if (idiomaticTranslation != null) {
                translationCache.put(cleanText, idiomaticTranslation)
                translations.add(Pair(rect, idiomaticTranslation))
                pendingCount--
                checkBatchComplete(pendingCount, translations)
                continue
            }

            // 2. Strict language validation pipeline
            languageIdentifier.identifyPossibleLanguages(cleanText)
                .addOnSuccessListener { candidatesList ->
                    val isEligible = TranslationSenseEngine.isEligibleSourceText(
                        cleanText,
                        configuredSource,
                        configuredTarget,
                        candidatesList
                    )
                    if (!isEligible) {
                        // Drop non-source text (English links/previews, Greek text, etc.)
                        pendingCount--
                        checkBatchComplete(pendingCount, translations)
                        return@addOnSuccessListener
                    }

                    val sourceCode = if (configuredSource != "AUTO") configuredSource else TranslateLanguage.ROMANIAN
                    translateText(sourceCode, configuredTarget, cleanText) { rawTranslated ->
                        if (rawTranslated != null) {
                            val senseCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
                                cleanText,
                                rawTranslated,
                                sourceCode,
                                configuredTarget
                            )
                            translationCache.put(cleanText, senseCorrected)
                            translations.add(Pair(rect, senseCorrected))
                        }
                        pendingCount--
                        checkBatchComplete(pendingCount, translations)
                    }
                }
                .addOnFailureListener {
                    val isEligible = TranslationSenseEngine.isEligibleSourceText(
                        cleanText,
                        configuredSource,
                        configuredTarget,
                        emptyList()
                    )
                    if (!isEligible) {
                        pendingCount--
                        checkBatchComplete(pendingCount, translations)
                        return@addOnFailureListener
                    }

                    val sourceCode = if (configuredSource != "AUTO") configuredSource else TranslateLanguage.ROMANIAN
                    translateText(sourceCode, configuredTarget, cleanText) { rawTranslated ->
                        if (rawTranslated != null) {
                            val senseCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
                                cleanText,
                                rawTranslated,
                                sourceCode,
                                configuredTarget
                            )
                            translationCache.put(cleanText, senseCorrected)
                            translations.add(Pair(rect, senseCorrected))
                        }
                        pendingCount--
                        checkBatchComplete(pendingCount, translations)
                    }
                }
        }
        return candidates.size
    }

    private var lastFeedbackToastTime = 0L

    private fun checkBatchComplete(pending: Int, results: List<Pair<Rect?, String>>) {
        if (pending <= 0) {
            mainHandler.post {
                if (FloatingBubbleService.isTranslatingActive) {
                    overlayManager.updateOverlays(results)
                    val now = System.currentTimeMillis()
                    if (results.isNotEmpty() && now - lastFeedbackToastTime > 5000) {
                        lastFeedbackToastTime = now
                        Toast.makeText(this, "Translated ${results.size} message(s)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun collectMessageCandidates(
        node: AccessibilityNodeInfo,
        outList: MutableList<MessageCandidate>,
        minY: Int,
        maxY: Int,
        configuredTarget: String
    ): Boolean {
        if (node.isEditable) {
            // Skip input fields during message scan (handled by handleOutgoingTyping)
            return false
        }

        var childFound = false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = collectMessageCandidates(child, outList, minY, maxY, configuredTarget)
            if (found) {
                childFound = true
            }
        }

        // If children already contained the message text, do not process container node
        if (childFound) {
            return true
        }

        val rawText = node.text?.toString() ?: node.contentDescription?.toString()
        if (!rawText.isNullOrBlank()) {
            val cleanText = TranslationFilter.cleanMessageText(rawText)
            if (cleanText.isNotBlank() && TranslationFilter.shouldTranslate(cleanText, configuredTarget)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // Inspect parent message bubble container for accurate container bounds
                val parent = node.parent
                val screenWidth = resources.displayMetrics.widthPixels
                val density = resources.displayMetrics.density
                if (parent != null) {
                    val parentRect = Rect()
                    parent.getBoundsInScreen(parentRect)
                    if (parentRect.width() <= screenWidth * 0.92f &&
                        parentRect.height() <= (250 * density).toInt() &&
                        parentRect.contains(rect)) {
                        rect.set(parentRect)
                    }
                }

                // Validate chat message bubble (both incoming and sent outgoing messages, excluding centered date pills)
                val isBubble = TranslationSenseEngine.isMessageBubble(rect.left, rect.right, screenWidth)

                if (isBubble && rect.width() > 10 && rect.height() > 10 && rect.top >= minY && rect.bottom <= maxY) {
                    outList.add(MessageCandidate(cleanText, rect))
                    return true
                }
            }
        }

        return false
    }

    private fun isKeyboardVisible(): Boolean {
        try {
            val windowList = windows
            if (windowList != null) {
                for (w in windowList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val rect = Rect()
                        w.getBoundsInScreen(rect)
                        if (rect.height() > 100) return true
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        try {
            val input = findCurrentEditableNode()
            if (input != null) {
                val rect = Rect()
                input.getBoundsInScreen(rect)
                val screenHeight = resources.displayMetrics.heightPixels
                val density = resources.displayMetrics.density
                if (rect.bottom > 0 && (screenHeight - rect.bottom) > (180 * density).toInt()) {
                    return true
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

    private fun handleOutgoingTyping(event: AccessibilityEvent) {
        val prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
        val isKeyboardTranslatorEnabled = prefs.getBoolean("keyboard_translator_enabled", false)
        if (!isKeyboardTranslatorEnabled) {
            overlayManager.removeDraftOverlay()
            return
        }

        val node = event.source ?: return
        if (!node.isEditable) return

        val rawText = node.text?.toString()?.trim() ?: ""
        val hintText = node.hintText?.toString()?.trim() ?: ""

        if (rawText == lastInsertedText) {
            overlayManager.removeDraftOverlay()
            return
        }
        lastInsertedText = null

        // Ignore empty text and common hint/placeholder labels ("Μήνυμα", "message", "type a message")
        if (rawText.length < 2 || !rawText.any { it.isLetter() } ||
            rawText.equals(hintText, ignoreCase = true) ||
            rawText.equals("μήνυμα", ignoreCase = true) ||
            rawText.equals("μηνυμα", ignoreCase = true) ||
            rawText.equals("message", ignoreCase = true) ||
            rawText.equals("type a message", ignoreCase = true)) {
            overlayManager.removeDraftOverlay()
            return
        }

        val configuredSource = prefs.getString("source_language", TranslateLanguage.ROMANIAN) ?: TranslateLanguage.ROMANIAN
        val configuredTarget = prefs.getString("target_language", TranslateLanguage.GREEK) ?: TranslateLanguage.GREEK

        // Symmetrical reverse translation: translate user draft from target language into recipient's source language
        val draftSourceLang = if (configuredSource != "AUTO") configuredTarget else TranslateLanguage.GREEK
        val draftTargetLang = if (configuredSource != "AUTO") configuredSource else TranslateLanguage.ROMANIAN

        typingJob?.cancel()
        typingJob = serviceScope.launch {
            delay(200) // 200ms debounce while user is actively typing

            // When keyboard is active, ensure message overlays are removed so screen is not overflooded
            overlayManager.removeAllMessageOverlays()

            // Re-fetch fresh live editable node and bounds after keyboard animation has settled
            val liveNode = findCurrentEditableNode() ?: node
            val liveText = liveNode.text?.toString()?.trim() ?: rawText
            val liveHint = liveNode.hintText?.toString()?.trim() ?: hintText
            if (liveText.length < 2 || !liveText.any { it.isLetter() } ||
                liveText.equals(liveHint, ignoreCase = true) ||
                liveText.equals("μήνυμα", ignoreCase = true) ||
                liveText.equals("μηνυμα", ignoreCase = true) ||
                liveText.equals("message", ignoreCase = true) ||
                liveText.equals("type a message", ignoreCase = true)) {
                overlayManager.removeDraftOverlay()
                return@launch
            }

            val inputRect = Rect()
            liveNode.getBoundsInScreen(inputRect)
            if (inputRect.top <= 0) {
                node.getBoundsInScreen(inputRect)
            }

            // 1. Pre-translation idiomatic & conversational check
            val idiomaticDraft = TranslationSenseEngine.resolveIdiomPreTranslation(
                liveText,
                draftSourceLang,
                draftTargetLang
            )
            if (idiomaticDraft != null) {
                val langBadge = draftTargetLang.uppercase()
                overlayManager.updateDraftOverlay(
                    inputRect = inputRect,
                    text = idiomaticDraft,
                    targetLangCode = langBadge,
                    onInsertClicked = {
                        insertTranslatedTextIntoInput(idiomaticDraft, liveNode)
                    },
                    onDismissClicked = {
                        overlayManager.removeDraftOverlay()
                    }
                )
                return@launch
            }

            // 2. Machine translation with post-processing sense correction
            translateText(draftSourceLang, draftTargetLang, liveText) { rawTranslatedDraft ->
                if (rawTranslatedDraft != null && rawTranslatedDraft.isNotBlank() && rawTranslatedDraft != liveText) {
                    val translatedDraft = TranslationSenseEngine.applyPostTranslationSenseLogic(
                        originalText = liveText,
                        translatedText = rawTranslatedDraft,
                        sourceLang = draftSourceLang,
                        targetLang = draftTargetLang
                    )
                    val langBadge = draftTargetLang.uppercase()
                    overlayManager.updateDraftOverlay(
                        inputRect = inputRect,
                        text = translatedDraft,
                        targetLangCode = langBadge,
                        onInsertClicked = {
                            insertTranslatedTextIntoInput(translatedDraft, liveNode)
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

    private var isDownloadingModel = false

    private fun translateText(
        sourceLang: String,
        targetLang: String,
        text: String,
        onResult: (String?) -> Unit
    ) {
        val key = "$sourceLang-$targetLang"
        val translator = translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }

        val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                if (isDownloadingModel) {
                    isDownloadingModel = false
                    mainHandler.post {
                        Toast.makeText(this, "Language models ready. Translating...", Toast.LENGTH_SHORT).show()
                    }
                }
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        onResult(translated)
                    }
                    .addOnFailureListener { e ->
                        Log.e("Translator", "ML Kit translation failed for: $text", e)
                        onResult(null)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("Translator", "ML Kit model download failed for $key", e)
                mainHandler.post {
                    Toast.makeText(this, "⚠️ Model download failed. Check internet connection.", Toast.LENGTH_SHORT).show()
                }
                onResult(null)
            }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isSharedInstanceActive = false
        currentInstance = null
        overlayManager.removeAllOverlays()
        for (translator in translators.values) {
            try {
                translator.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        translators.clear()
        translationCache.evictAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isSharedInstanceActive = false
        currentInstance = null
        overlayManager.removeAllOverlays()
    }

    override fun onInterrupt() {
        overlayManager.removeAllOverlays()
    }
}
