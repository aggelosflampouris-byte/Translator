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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!FloatingBubbleService.isTranslatingActive) return

        val packageName = event.packageName?.toString() ?: ""
        if (!isWhatsAppPackage(packageName)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                overlayManager.removeAllOverlays()
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scanJob?.cancel()
                scanJob = serviceScope.launch {
                    delay(100)
                    scanAndTranslateVisibleMessages()
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleOutgoingTyping(event)
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
                val pkg = eventSource.packageName?.toString() ?: ""
                if (isWhatsAppPackage(pkg)) {
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
            val windowList = windows
            for (window in windowList) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (isWhatsAppPackage(pkg)) {
                    return root
                }
                if (pkg.isEmpty() && root.childCount > 0) {
                    val childPkg = root.getChild(0)?.packageName?.toString() ?: ""
                    if (isWhatsAppPackage(childPkg)) {
                        return root
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying windows in accessibility service", e)
        }

        try {
            val active = rootInActiveWindow
            if (active != null) {
                val pkg = active.packageName?.toString() ?: ""
                if (isWhatsAppPackage(pkg)) {
                    return active
                }
                if (pkg.isEmpty() && active.childCount > 0) {
                    val childPkg = active.getChild(0)?.packageName?.toString() ?: ""
                    if (isWhatsAppPackage(childPkg)) {
                        return active
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying rootInActiveWindow", e)
        }

        // WhatsApp is NOT in the foreground! Strictly return null.
        return null
    }

    private fun scanAndTranslateVisibleMessages(eventSource: AccessibilityNodeInfo? = null): Int {
        if (!FloatingBubbleService.isTranslatingActive) return 0
        val rootNode = findWhatsAppRootNode(eventSource)
        if (rootNode == null) {
            // When user exits WhatsApp (e.g. to Home Screen, launcher, or another app), clear all overlays immediately
            overlayManager.removeAllOverlays()
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

        collectMessageCandidates(rootNode, candidates, topInset, screenHeight - bottomInset, configuredTarget)

        if (candidates.isEmpty()) {
            overlayManager.removeAllOverlays()
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

                // Strictly filter incoming messages (left-aligned) and exclude outgoing/centered pills
                val isIncoming = TranslationSenseEngine.isIncomingMessage(rect.left, rect.right, screenWidth)

                if (isIncoming && rect.width() > 10 && rect.height() > 10 && rect.top >= minY && rect.top <= maxY) {
                    outList.add(MessageCandidate(cleanText, rect))
                    return true
                }
            }
        }

        return false
    }

    private fun handleOutgoingTyping(event: AccessibilityEvent) {
        val node = event.source ?: return
        if (!node.isEditable) return

        val rawText = node.text?.toString()?.trim() ?: ""
        if (rawText.isBlank()) {
            overlayManager.removeDraftOverlay()
            return
        }

        val inputRect = Rect()
        node.getBoundsInScreen(inputRect)

        val prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
        val configuredSource = prefs.getString("source_language", TranslateLanguage.ROMANIAN) ?: TranslateLanguage.ROMANIAN
        val configuredTarget = prefs.getString("target_language", TranslateLanguage.GREEK) ?: TranslateLanguage.GREEK

        // Symmetrical reverse translation: translate user draft into the recipient's language
        val draftSourceLang = if (configuredSource != "AUTO") configuredTarget else TranslateLanguage.GREEK
        val draftTargetLang = if (configuredSource != "AUTO") configuredSource else TranslateLanguage.ROMANIAN

        typingJob?.cancel()
        typingJob = serviceScope.launch {
            delay(300) // 300ms debounce while user is actively typing

            translateText(draftSourceLang, draftTargetLang, rawText) { translatedDraft ->
                if (translatedDraft != null && translatedDraft.isNotBlank()) {
                    overlayManager.updateDraftOverlay(inputRect, translatedDraft) {
                        // On tap draft preview: replace WhatsApp input box with translated draft
                        insertTranslatedTextIntoInput(node, translatedDraft)
                    }
                } else {
                    overlayManager.removeDraftOverlay()
                }
            }
        }
    }

    private fun insertTranslatedTextIntoInput(node: AccessibilityNodeInfo, text: String) {
        try {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) {
                // Fallback to clipboard if direct action is restricted
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Translated Text", text))
                Toast.makeText(this, "Copied to clipboard (Tap Paste in chat)", Toast.LENGTH_SHORT).show()
            }
            overlayManager.removeDraftOverlay()
        } catch (e: Exception) {
            Log.e("Translator", "Failed to set text in input", e)
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
