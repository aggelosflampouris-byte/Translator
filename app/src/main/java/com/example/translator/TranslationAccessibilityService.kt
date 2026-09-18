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

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = this
        isSharedInstanceActive = true
        overlayManager = OverlayManager(this)
        Log.d("Translator", "TranslationAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!FloatingBubbleService.isTranslatingActive) return

        val packageName = event.packageName?.toString() ?: ""
        if (packageName != "com.whatsapp") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Debounce full window scans to prevent frame drops during rapid scrolling
                scanJob?.cancel()
                scanJob = serviceScope.launch {
                    delay(120)
                    scanAndTranslateVisibleMessages()
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
            delay(100)
            scanAndTranslateVisibleMessages()
        }
    }

    private fun scanAndTranslateVisibleMessages() {
        if (!FloatingBubbleService.isTranslatingActive) return
        val rootNode = rootInActiveWindow ?: return

        val prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
        val configuredSource = prefs.getString("source_language", "AUTO") ?: "AUTO"
        val configuredTarget = prefs.getString("target_language", TranslateLanguage.GREEK) ?: TranslateLanguage.GREEK

        val messageNodes = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        val density = resources.displayMetrics.density
        val topInset = (48 * density).toInt()
        val bottomInset = (40 * density).toInt()
        val screenHeight = resources.displayMetrics.heightPixels

        collectMessageNodes(rootNode, messageNodes, topInset, screenHeight - bottomInset, configuredTarget)

        if (messageNodes.isEmpty()) {
            overlayManager.removeAllOverlays()
            return
        }

        val translations = mutableListOf<Pair<Rect?, String>>()
        var pendingCount = messageNodes.size

        for ((node, rect) in messageNodes) {
            val rawText = node.text?.toString() ?: ""
            val cleanText = TranslationFilter.cleanMessageText(rawText)

            if (cleanText.isBlank() || !TranslationFilter.shouldTranslate(cleanText, configuredTarget)) {
                pendingCount--
                checkBatchComplete(pendingCount, translations)
                continue
            }

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
                languageIdentifier.identifyPossibleLanguages(cleanText)
                    .addOnSuccessListener { candidates ->
                        val isTarget = TranslationFilter.isTargetLanguage(cleanText, configuredTarget, candidates)
                        val isSource = !isTarget && TranslationFilter.isMatchingSourceLanguage(cleanText, configuredSource, candidates)

                        if (isSource) {
                            translateText(configuredSource, configuredTarget, cleanText) { translated ->
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
                        pendingCount--
                        checkBatchComplete(pendingCount, translations)
                    }
            } else {
                languageIdentifier.identifyPossibleLanguages(cleanText)
                    .addOnSuccessListener { candidates ->
                        val isTarget = TranslationFilter.isTargetLanguage(cleanText, configuredTarget, candidates)
                        if (isTarget) {
                            pendingCount--
                            checkBatchComplete(pendingCount, translations)
                            return@addOnSuccessListener
                        }

                        val best = candidates.firstOrNull { it.languageTag != "und" && it.confidence >= 0.25f }
                        val bcpCode = best?.let { TranslateLanguage.fromLanguageTag(it.languageTag) }

                        if (bcpCode != null && bcpCode != configuredTarget) {
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
                        pendingCount--
                        checkBatchComplete(pendingCount, translations)
                    }
            }
        }
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

    private fun collectMessageNodes(
        node: AccessibilityNodeInfo,
        outList: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
        minY: Int,
        maxY: Int,
        configuredTarget: String
    ) {
        if (node.isEditable) {
            // Skip input fields during message scan (handled by handleOutgoingTyping)
            return
        }

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && node.className == "android.widget.TextView") {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top >= minY && rect.bottom <= maxY && TranslationFilter.shouldTranslate(text, configuredTarget)) {
                outList.add(Pair(node, rect))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMessageNodes(child, outList, minY, maxY, configuredTarget)
        }
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
        val configuredSource = prefs.getString("source_language", "AUTO") ?: "AUTO"
        val configuredTarget = prefs.getString("target_language", TranslateLanguage.GREEK) ?: TranslateLanguage.GREEK

        // Symmetrical reverse translation: translate user draft into the recipient's language
        val draftSourceLang = if (configuredSource != "AUTO") configuredTarget else TranslateLanguage.GREEK
        val draftTargetLang = if (configuredSource != "AUTO") configuredSource else TranslateLanguage.ENGLISH

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

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        onResult(translated)
                    }
                    .addOnFailureListener {
                        onResult(null)
                    }
            }
            .addOnFailureListener {
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
