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
                } else {
                    service.requestScan()
                }
            }
        }
    }

    data class MessageCandidate(
        val text: String,
        val bounds: Rect
    )

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
        if (!isWhatsAppPackage(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scanJob?.cancel()
                scanJob = serviceScope.launch {
                    delay(120)
                    scanAndTranslateVisibleMessages(event.source)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleOutgoingTyping(event)
            }
        }
    }

    private fun requestScan() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            val intervals = listOf(50L, 250L, 600L, 1200L)
            for (delayMs in intervals) {
                delay(delayMs)
                if (!FloatingBubbleService.isTranslatingActive) break
                val found = scanAndTranslateVisibleMessages()
                if (found > 0) {
                    break
                }
            }
        }
    }

    private fun findWhatsAppRootNode(eventSource: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        if (eventSource != null && isWhatsAppPackage(eventSource.packageName?.toString())) {
            var current: AccessibilityNodeInfo = eventSource
            while (true) {
                val parent = current.parent ?: break
                current = parent
            }
            return current
        }

        try {
            val windowList = windows
            for (window in windowList) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (isWhatsAppPackage(pkg)) {
                    return root
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying windows in accessibility service", e)
        }

        val active = rootInActiveWindow
        if (active != null && isWhatsAppPackage(active.packageName?.toString())) {
            return active
        }

        return null
    }

    private fun scanAndTranslateVisibleMessages(eventSource: AccessibilityNodeInfo? = null): Int {
        if (!FloatingBubbleService.isTranslatingActive) return 0
        val rootNode = findWhatsAppRootNode(eventSource) ?: return 0

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

            // Language validation and translation pipeline
            if (configuredSource != "AUTO") {
                // If text is already predominantly Greek (target), skip it
                if (TranslationFilter.isTargetLanguage(cleanText, configuredTarget, emptyList())) {
                    pendingCount--
                    checkBatchComplete(pendingCount, translations)
                    continue
                }

                // Explicitly configured source (e.g. Romanian) -> translate directly
                translateText(configuredSource, configuredTarget, cleanText) { translated ->
                    if (translated != null) {
                        translationCache.put(cleanText, translated)
                        translations.add(Pair(rect, translated))
                    }
                    pendingCount--
                    checkBatchComplete(pendingCount, translations)
                }
            } else {
                // AUTO mode: check for Romanian vocabulary/diacritics first
                if (TranslationFilter.isMatchingSourceLanguage(cleanText, TranslateLanguage.ROMANIAN, emptyList())) {
                    translateText(TranslateLanguage.ROMANIAN, configuredTarget, cleanText) { translated ->
                        if (translated != null) {
                            translationCache.put(cleanText, translated)
                            translations.add(Pair(rect, translated))
                        }
                        pendingCount--
                        checkBatchComplete(pendingCount, translations)
                    }
                } else {
                    languageIdentifier.identifyPossibleLanguages(cleanText)
                        .addOnSuccessListener { candidatesList ->
                            val isTarget = TranslationFilter.isTargetLanguage(cleanText, configuredTarget, candidatesList)
                            if (isTarget) {
                                pendingCount--
                                checkBatchComplete(pendingCount, translations)
                                return@addOnSuccessListener
                            }

                            val best = candidatesList.firstOrNull { it.languageTag != "und" && it.confidence >= 0.15f }
                            val bcpCode = best?.let { TranslateLanguage.fromLanguageTag(it.languageTag) } ?: TranslateLanguage.ROMANIAN

                            if (bcpCode != configuredTarget) {
                                translateText(bcpCode, configuredTarget, cleanText) { translated ->
                                    if (translated != null) {
                                        translationCache.put(cleanText, translated)
                                        translations.add(Pair(rect, translated))
                                    }
                                    pendingCount--
                                    checkBatchComplete(pendingCount, translations)
                                }
                            } else {
                                pendingCount--
                                checkBatchComplete(pendingCount, translations)
                            }
                        }
                        .addOnFailureListener {
                            // Fallback to Romanian translation if identification fails
                            translateText(TranslateLanguage.ROMANIAN, configuredTarget, cleanText) { translated ->
                                if (translated != null) {
                                    translationCache.put(cleanText, translated)
                                    translations.add(Pair(rect, translated))
                                }
                                pendingCount--
                                checkBatchComplete(pendingCount, translations)
                            }
                        }
                }
            }
        }
        return candidates.size
    }

    private fun checkBatchComplete(pending: Int, results: List<Pair<Rect?, String>>) {
        if (pending <= 0) {
            mainHandler.post {
                if (FloatingBubbleService.isTranslatingActive) {
                    overlayManager.updateOverlays(results)
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
                if (rect.width() > 10 && rect.height() > 10 && rect.top >= minY && rect.bottom <= maxY) {
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
