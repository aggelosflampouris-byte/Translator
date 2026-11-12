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

    // Por qué: Control de estabilidad de pantalla para ocultar las píldoras durante el desplazamiento
    // y traducir únicamente cuando la pantalla se encuentre completamente estática.
    private var lastScrollTimestamp: Long = 0L
    @Volatile private var isScreenScrolling: Boolean = false
    private var scrollSettleJob: Job? = null
    // Por qué: Identificador de generación de escaneo para invalidar dibujos asíncronos si el usuario se desplazó
    private val scanGeneration = java.util.concurrent.atomic.AtomicLong(0)

    // In-memory LRU cache for fast scroll rendering without unbounded SharedPreferences growth
    private val translationCache = LruCache<String, String>(500)
    // Por qué: Evita encolar múltiples inferencias duplicadas sobre el mismo mensaje durante el escaneo periódico
    private val inFlightKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun cacheKey(sourceLang: String, targetLang: String, text: String): String {
        return "${sourceLang}_${targetLang}_${text.trim()}"
    }

    private fun getCachedTranslation(sourceLang: String, targetLang: String, text: String): String? {
        return translationCache.get(cacheKey(sourceLang, targetLang, text))
    }

    private fun putCachedTranslation(sourceLang: String, targetLang: String, text: String, translation: String) {
        translationCache.put(cacheKey(sourceLang, targetLang, text), translation)
    }

    private var configuredSource: String = AppDefaults.SOURCE_LANGUAGE
    private var configuredTarget: String = AppDefaults.TARGET_LANGUAGE
    private var isKeyboardTranslatorEnabled: Boolean = true
    private var translationMode: String = AppDefaults.MODE_ALL
    private lateinit var hybridEngine: HybridTranslationEngine
    private var manualDraftTargetLang: String? = null

    // Por qué: Memoria local de aprendizaje (frases enviadas y correcciones del usuario) y estado del bucle de retroalimentación
    private lateinit var learningStore: LearningStore
    private var isLearningEnabled: Boolean = true
    private val sentMessageDetector = SentMessageDetector()
    @Volatile private var pendingDraftSession: DraftSession? = null

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            AppDefaults.KEY_SOURCE_LANGUAGE -> {
                configuredSource = prefs.getString(key, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
                manualDraftTargetLang = null
                lastRenderedDraftSource = null
                mainHandler.post { overlayManager.removeAllOverlays() }
                if (ServiceBridge.isNotificationActive) {
                    TranslationNotificationManager.showNotification(this, ServiceBridge.isTranslatingActive)
                }
                serviceScope.launch(Dispatchers.Default) { hybridEngine.preload() }
                scheduleScan(100L)
            }
            AppDefaults.KEY_TARGET_LANGUAGE -> {
                configuredTarget = prefs.getString(key, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
                manualDraftTargetLang = null
                lastRenderedDraftSource = null
                mainHandler.post { overlayManager.removeAllOverlays() }
                if (ServiceBridge.isNotificationActive) {
                    TranslationNotificationManager.showNotification(this, ServiceBridge.isTranslatingActive)
                }
                serviceScope.launch(Dispatchers.Default) { hybridEngine.preload() }
                scheduleScan(100L)
            }
            AppDefaults.KEY_KEYBOARD_ENABLED -> {
                isKeyboardTranslatorEnabled = prefs.getBoolean(key, true)
            }
            AppDefaults.KEY_LEARNING_ENABLED -> {
                isLearningEnabled = prefs.getBoolean(key, true)
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
        learningStore = LearningStore.get(this)

        val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        configuredSource = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
        configuredTarget = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
        isKeyboardTranslatorEnabled = prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, true)
        isLearningEnabled = prefs.getBoolean(AppDefaults.KEY_LEARNING_ENABLED, true)
        translationMode = prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        // Publish to ServiceBridge — no static Service reference stored.
        ServiceBridge.isAccessibilityActive = true

        // Register the state-change callback so FloatingBubbleService can drive us
        // without importing or holding a reference to this service.
        ServiceBridge.onTranslationStateChanged = { isActive ->
            if (!isActive) {
                mainHandler.post {
                    overlayManager.removeAllOverlays()
                }
                typingJob?.cancel()
                scanDebounceJob?.cancel()
                scrollSettleJob?.cancel()
                activeScanLoopJob?.cancel()
                isScreenScrolling = false
            } else {
                startActiveScanning()
            }
        }

        // Por qué: El vigilante se inicia de inmediato y permanece activo para garantizar que ninguna
        // pastilla huérfana sobreviva fuera de WhatsApp, incluso si el servicio cambia de estado.
        startOverlayWatchdog()

        // Por qué: Precarga el modelo neuronal en memoria en segundo plano para eliminar la latencia inicial al abrir un chat
        serviceScope.launch(Dispatchers.Default) {
            hybridEngine.preload()
        }

        // Por qué: Callback ejecutado cuando el usuario pulsa "Translate Screen" en la notificación o mosaico
        ServiceBridge.onScanRequested = {
            serviceScope.launch {
                isScreenScrolling = false
                lastScrollTimestamp = 0L
                if (!isWhatsAppInForeground()) {
                    mainHandler.post {
                        overlayManager.removeAllOverlays()
                        Toast.makeText(applicationContext, "Open a WhatsApp chat to translate messages", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                scanAndTranslateVisibleMessages()
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
        return pkg != null && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")
    }

    // Por qué: Identifica lanzadores del sistema, interfaces de inicio y la propia app
    // para descartar inmediatamente que WhatsApp esté en primer plano y evitar falsos positivos
    // producidos por accesos directos o iconos de WhatsApp en la pantalla de inicio.
    private fun isKnownNonWhatsAppPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        return lower.contains("launcher") ||
               lower.contains("quickstep") ||
               lower.contains("home") ||
               pkg == "android" ||
               pkg == "com.android.systemui" ||
               pkg == applicationContext.packageName
    }

    private fun isIgnoredPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (isKnownNonWhatsAppPackage(pkg)) return true
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

    // Por qué: Determina de forma estricta y sin ambigüedades si WhatsApp es la app activa en primer plano,
    // comprobando el paquete raíz de la ventana y evitando examinar árboles de nodos secundarios que puedan
    // contener accesos directos o widgets de WhatsApp en el escritorio del teléfono.
    private fun isWhatsAppInForeground(): Boolean {
        try {
            val active = rootInActiveWindow
            if (active != null) {
                val pkg = active.packageName?.toString() ?: ""
                if (isKnownNonWhatsAppPackage(pkg)) {
                    return false
                }
                if (isWhatsAppPackage(pkg)) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val windowList = windows
            if (windowList != null && windowList.isNotEmpty()) {
                for (window in windowList) {
                    if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                        val root = window.root ?: continue
                        val pkg = root.packageName?.toString() ?: ""
                        if (isKnownNonWhatsAppPackage(pkg)) {
                            return false
                        }
                        if (isWhatsAppPackage(pkg)) {
                            return true
                        }
                    }
                }

                // Por qué: Si el teclado IME o una superposición del sistema tiene el foco activo,
                // verificamos si la ventana de aplicación principal subyacente pertenece a WhatsApp.
                val appWindows = windowList.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                if (appWindows.isNotEmpty()) {
                    val activeApp = appWindows.firstOrNull { it.isActive }
                    if (activeApp != null) {
                        val pkg = activeApp.root?.packageName?.toString() ?: ""
                        return isWhatsAppPackage(pkg)
                    }
                    val firstApp = appWindows.firstOrNull()
                    val pkg = firstApp?.root?.packageName?.toString() ?: ""
                    return isWhatsAppPackage(pkg)
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

        // Por qué: Limpieza inmediata de pantalla si el evento proviene de una app externa o del lanzador
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
                scanGeneration.incrementAndGet()
                lastScrollTimestamp = android.os.SystemClock.uptimeMillis()
                isScreenScrolling = true
                scanDebounceJob?.cancel()
                scrollSettleJob?.cancel()

                // Por qué: Oculta inmediatamente las píldoras de traducción durante el desplazamiento del chat
                // para evitar que se queden atascadas o flotando sobre mensajes en movimiento.
                mainHandler.post {
                    overlayManager.removeAllMessageOverlays()
                    if (!isKeyboardVisible()) {
                        overlayManager.removeDraftOverlay()
                    }
                }

                scrollSettleJob = serviceScope.launch {
                    // Por qué: Espera 350ms de quietud absoluta tras finalizar el desplazamiento
                    // antes de traducir, garantizando que la pantalla esté 100% estable.
                    delay(350L)
                    isScreenScrolling = false
                    if (isWhatsAppInForeground() && ServiceBridge.isTranslatingActive) {
                        scanAndTranslateVisibleMessages()
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleMessageClicked(event.source)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isKeyboardTranslatorEnabled) {
                    handleOutgoingTyping(event)
                } else {
                    overlayManager.removeDraftOverlay()
                }
                // Por qué: Ignora cambios de contenido si la lista aún se encuentra en movimiento por desplazamiento
                val now = android.os.SystemClock.uptimeMillis()
                if (!isScreenScrolling && (now - lastScrollTimestamp >= 350L)) {
                    scheduleScan(350L, event.source)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (isKeyboardTranslatorEnabled) {
                    handleOutgoingTyping(event)
                } else {
                    overlayManager.removeDraftOverlay()
                }
            }
        }
    }

    private var scanDebounceJob: Job? = null
    // Por qué: Aplica un retraso controlado para no saturar con cancelaciones constantes en cada fotograma de desplazamiento
    private fun scheduleScan(delayMs: Long, eventSource: AccessibilityNodeInfo? = null) {
        if (!ServiceBridge.isTranslatingActive) return
        if (!isWhatsAppInForeground()) {
            if (overlayManager.hasOverlays()) {
                mainHandler.post { overlayManager.removeAllOverlays() }
            }
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (isScreenScrolling || (now - lastScrollTimestamp < 350L)) {
            return
        }
        scanDebounceJob?.cancel()
        scanDebounceJob = serviceScope.launch {
            delay(delayMs)
            val elapsed = android.os.SystemClock.uptimeMillis() - lastScrollTimestamp
            if (ServiceBridge.isTranslatingActive && isWhatsAppInForeground() && !isScreenScrolling && elapsed >= 350L) {
                scanAndTranslateVisibleMessages(eventSource)
            }
        }
    }

    private var overlayWatchdogJob: Job? = null

    // Por qué: Vigila activamente si hay píldoras dibujadas en pantalla y las remueve inmediatamente
    // si el usuario minimiza WhatsApp o vuelve a la pantalla de inicio, superando las limitaciones
    // del filtrado de eventos por paquete de accesibilidad del sistema operativo.
    private fun startOverlayWatchdog() {
        overlayWatchdogJob?.cancel()
        overlayWatchdogJob = serviceScope.launch {
            while (true) {
                delay(250L)
                if (overlayManager.hasOverlays()) {
                    if (!isWhatsAppInForeground() || !ServiceBridge.isTranslatingActive) {
                        mainHandler.post {
                            overlayManager.removeAllOverlays()
                        }
                    }
                }
            }
        }
    }

    private fun startActiveScanning() {
        activeScanLoopJob?.cancel()
        activeScanLoopJob = serviceScope.launch {
            delay(50L)
            if (!ServiceBridge.isTranslatingActive) return@launch
            val elapsedInit = android.os.SystemClock.uptimeMillis() - lastScrollTimestamp
            if (isWhatsAppInForeground() && !isScreenScrolling && elapsedInit >= 350L) {
                scanAndTranslateVisibleMessages()
            }

            while (ServiceBridge.isTranslatingActive) {
                delay(2000L)
                if (!ServiceBridge.isTranslatingActive) break
                val timeSinceScroll = android.os.SystemClock.uptimeMillis() - lastScrollTimestamp
                if (isWhatsAppInForeground() && !isScreenScrolling && timeSinceScroll >= 350L) {
                    scanAndTranslateVisibleMessages()
                } else if (!isWhatsAppInForeground() && overlayManager.hasOverlays()) {
                    mainHandler.post { overlayManager.removeAllOverlays() }
                }
            }
        }
    }

    private fun findWhatsAppRootNode(eventSource: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        if (!isWhatsAppInForeground()) {
            return null
        }

        if (eventSource != null) {
            try {
                var current: AccessibilityNodeInfo = eventSource
                while (true) {
                    val parent = current.parent ?: break
                    current = parent
                }
                if (isWhatsAppPackage(current.packageName?.toString())) {
                    return current
                }
            } catch (e: Exception) {
                // Ignore recycled node
            }
        }

        try {
            val active = rootInActiveWindow
            if (active != null && isWhatsAppPackage(active.packageName?.toString())) {
                return active
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying rootInActiveWindow", e)
        }

        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = window.root ?: continue
                        if (isWhatsAppPackage(root.packageName?.toString())) {
                            return root
                        }
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

        // Por qué: Valida que la pantalla esté completamente quieta y no en pleno desplazamiento antes de escanear
        val timeSinceScroll = android.os.SystemClock.uptimeMillis() - lastScrollTimestamp
        if (isScreenScrolling || timeSinceScroll < 350L) {
            return 0
        }

        // Por qué: Verifica estrictamente que WhatsApp esté en primer plano antes de iniciar cualquier escaneo
        // para garantizar que nunca se lean ni se superpongan píldoras sobre la pantalla de inicio u otras apps.
        if (!isWhatsAppInForeground()) {
            mainHandler.post {
                overlayManager.removeAllOverlays()
            }
            return 0
        }

        val rootNode = findWhatsAppRootNode(eventSource)
        if (rootNode == null) {
            mainHandler.post {
                overlayManager.removeAllOverlays()
            }
            return 0
        }

        // When the soft keyboard is open, hide chat message bubbles so the screen is not overflooded.
        // When the soft keyboard is closed, still evaluate the input bar in case a draft is present.
        if (isKeyboardVisible()) {
            overlayManager.removeAllMessageOverlays()
            if (isKeyboardTranslatorEnabled) {
                handleOutgoingTyping()
            }
            return 0
        } else if (isKeyboardTranslatorEnabled) {
            handleOutgoingTyping()
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
            mainHandler.post {
                overlayManager.removeAllMessageOverlays()
            }
            return 0
        }

        // Por qué: Purgar inmediatamente cualquier burbuja previa que ya no coincida con los candidatos visibles actuales
        val currentValidBounds = dedupedCandidates.map { it.bounds }
        mainHandler.post {
            overlayManager.purgeOrphans(currentValidBounds)
        }

        val immediateResults = mutableListOf<Pair<Rect?, String>>()
        val pendingCandidates = mutableListOf<MessageCandidate>()
        val currentGeneration = scanGeneration.get()

        for (candidate in dedupedCandidates) {
            val cleanText = candidate.text
            val rect = candidate.bounds

            val msgSource = if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
            val msgTarget = configuredTarget

            // Por qué: Las frases verificadas por el usuario tienen prioridad absoluta sobre cualquier motor
            val rememberedTranslation = lookupLearnedMemory(msgSource, msgTarget, cleanText)
            if (rememberedTranslation != null) {
                immediateResults.add(Pair(rect, rememberedTranslation))
                continue
            }

            // Check cache first (in-memory + persistent) for instantaneous rendering
            val cachedTranslation = getCachedTranslation(msgSource, msgTarget, cleanText)
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
                putCachedTranslation(msgSource, msgTarget, cleanText, idiomaticTranslation)
                immediateResults.add(Pair(rect, idiomaticTranslation))
                continue
            }

            pendingCandidates.add(candidate)
        }

        // Por qué: Renderizar de forma inmediata todas las traducciones ya resueltas (en caché o por modismos)
        // para que aparezcan en pantalla en menos de 2ms sin esperar a las peticiones de red.
        if (immediateResults.isNotEmpty()) {
            mainHandler.post {
                if (ServiceBridge.isTranslatingActive && isWhatsAppInForeground() && currentGeneration == scanGeneration.get() && !isScreenScrolling) {
                    overlayManager.updateOverlays(immediateResults, isFullSync = pendingCandidates.isEmpty())
                }
            }
        }

        // Por qué: Instantánea de la conversación visible (ambos idiomas) para dar contexto al modelo neuronal
        val conversation = if (pendingCandidates.isNotEmpty()) collectVisibleConversation(rootNode, effectiveBottom) else emptyList()

        // Por qué: Traducir de forma asíncrona cada mensaje pendiente sin bloquearse mutuamente,
        // actualizando su pastilla de forma individual tan pronto como el motor responda.
        for (candidate in pendingCandidates) {
            val cleanText = candidate.text
            val rect = candidate.bounds
            val msgSource = if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
            val msgTarget = configuredTarget
            val flightKey = cacheKey(msgSource, msgTarget, cleanText)
            if (!inFlightKeys.add(flightKey)) {
                continue
            }

            serviceScope.launch {
                try {
                    val detectedLang = identifyLanguageAsync(cleanText)
                    val isEligible = TranslationSenseEngine.isEligibleSourceText(
                        text = cleanText,
                        sourceLang = msgSource,
                        targetLang = msgTarget,
                        identifiedLang = detectedLang
                    )
                    if (!isEligible) {
                        return@launch
                    }

                    val normalizedText = if (msgSource == TranslateLanguage.ROMANIAN) {
                        TextNormalizer.restoreRomanianDiacritics(cleanText)
                    } else {
                        cleanText
                    }
                    val protected = TextNormalizer.protectEmojis(normalizedText)

                    val hints = buildHints(msgSource, msgTarget, contextAbove(conversation, rect))
                    val rawTranslated = hybridEngine.translate(protected.text, msgSource, msgTarget, hints)
                    if (rawTranslated != null && rawTranslated.isNotBlank()) {
                        val restoredEmojis = TextNormalizer.restoreEmojis(rawTranslated, protected.tokens)
                        val senseCorrected = applyLearnedCorrections(
                            msgSource,
                            msgTarget,
                            TranslationSenseEngine.applyPostTranslationSenseLogic(
                                originalText = normalizedText,
                                translatedText = restoredEmojis,
                                sourceLang = msgSource,
                                targetLang = msgTarget
                            )
                        )

                        // Por qué: Descartar respuestas de error o negativas generadas por la IA
                        if (senseCorrected.startsWith("[Error", ignoreCase = true) ||
                            senseCorrected.startsWith("Error:", ignoreCase = true) ||
                            senseCorrected.contains("no message was provided", ignoreCase = true) ||
                            senseCorrected.contains("please provide the", ignoreCase = true)
                        ) {
                            return@launch
                        }

                        // Por qué: Si la traducción resultante es idéntica al texto original (ej. inglés a inglés)
                        // o no contiene caracteres en el idioma de destino (ej. griego), se descarta el globo.
                        if (senseCorrected.trim().equals(cleanText.trim(), ignoreCase = true)) {
                            return@launch
                        }
                        if (msgTarget == TranslateLanguage.GREEK && senseCorrected.none { (it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF') && it.isLetter() }) {
                            return@launch
                        }

                        putCachedTranslation(msgSource, msgTarget, cleanText, senseCorrected)

                        // Por qué: Si la pantalla se desplazó durante la traducción asíncrona,
                        // las coordenadas 'rect' capturadas antes del scroll son obsoletas; se programa
                        // un escaneo rápido para pintar la traducción recién cacheada en su nueva posición.
                        val timeSinceScroll = android.os.SystemClock.uptimeMillis() - lastScrollTimestamp
                        if (currentGeneration != scanGeneration.get()) {
                            scheduleScan(80L)
                            return@launch
                        }
                        if (isScreenScrolling ||
                            timeSinceScroll < 350L ||
                            !isWhatsAppInForeground() ||
                            !ServiceBridge.isTranslatingActive
                        ) {
                            return@launch
                        }

                        mainHandler.post {
                            if (currentGeneration == scanGeneration.get() &&
                                ServiceBridge.isTranslatingActive &&
                                isWhatsAppInForeground() &&
                                !isScreenScrolling
                            ) {
                                overlayManager.addOrUpdateOverlay(rect, senseCorrected)
                            }
                        }
                    }
                } finally {
                    inFlightKeys.remove(flightKey)
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
        configuredTarget: String,
        requireTranslatable: Boolean = true
    ): Boolean {
        // Por qué: Ignora inmediatamente nodos que no pertenezcan a WhatsApp para evitar procesar la pantalla del lanzador o ajustes del sistema
        val nodePkg = node.packageName?.toString() ?: ""
        if (nodePkg.isNotBlank() && !isWhatsAppPackage(nodePkg)) {
            return false
        }

        if (node.isEditable) {
            // Skip input fields during message scan (handled by handleOutgoingTyping)
            return false
        }

        var childFound = false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = collectMessageCandidates(child, outList, minY, maxY, configuredSource, configuredTarget, requireTranslatable)
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
        val passesLanguageFilter = !requireTranslatable || TranslationFilter.shouldTranslate(cleanText, configuredTarget, configuredSource)
        if (cleanText.isNotBlank() && passesLanguageFilter) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Por qué: Busca el contenedor visual de la burbuja (hasta 2 niveles en la jerarquía)
            // para abarcar los límites completos del mensaje con padding, marcas de tiempo y bordes.
            var container = node.parent
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val density = resources.displayMetrics.density
            for (level in 0..1) {
                if (container != null) {
                    val pRect = Rect()
                    container.getBoundsInScreen(pRect)
                    if (pRect.width() <= screenWidth * 0.95f &&
                        pRect.height() <= screenHeight &&
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
            val visibleMargin = (20 * density).toInt()

            if (isBubble && rect.width() > (30 * density).toInt() && rect.height() > (16 * density).toInt() &&
                rect.bottom > minY + visibleMargin && rect.top < maxY - visibleMargin) {
                val clampedRect = Rect(
                    rect.left,
                    rect.top.coerceAtLeast(minY),
                    rect.right,
                    rect.bottom.coerceAtMost(maxY)
                )
                outList.add(MessageCandidate(cleanText, clampedRect))
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

    // Por qué: Verifica la elevación del campo de texto de WhatsApp sobre la barra de navegación
    // para confirmar con certeza si el teclado está visible sin depender de ventanas restringidas de otros paquetes.
    private fun isKeyboardVisible(candidateNode: AccessibilityNodeInfo? = null): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density

        // 1. Verificación primaria: Si el campo de entrada de WhatsApp está activo, medir su posición vertical
        try {
            val input = candidateNode ?: findCurrentEditableNode()
            if (input != null) {
                val inputRect = Rect()
                input.getBoundsInScreen(inputRect)
                if (inputRect.bottom > 0) {
                    val bottomOffset = screenHeight - inputRect.bottom
                    // Por qué: Con el teclado cerrado el campo descansa a ras de la barra de navegación (<= 95dp).
                    // Con el teclado desplegado se eleva de forma inmediata (> 100dp).
                    if (bottomOffset <= (95 * density).toInt()) {
                        return false
                    }
                    if (bottomOffset > (100 * density).toInt()) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar y pasar a verificación secundaria
        }

        // 2. Verificación secundaria: Inspeccionar ventanas del sistema para ventana de método de entrada (IME)
        try {
            val windowList = windows
            if (windowList != null) {
                for (w in windowList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val rect = Rect()
                        w.getBoundsInScreen(rect)
                        if (rect.top in 1 until (screenHeight - (95 * density).toInt()) &&
                            rect.height() > (100 * density).toInt()) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar
        }

        return false
    }

    private var lastInsertedText: String? = null
    private var dismissedDraftText: String? = null
    private var lastRenderedDraftSource: String? = null
    private var lastRenderedDraftTop: Int = 0
    private var lastRenderedDraftManualLang: String? = null

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

    // Por qué: Determina el par (origen, destino) para el borrador usando los idiomas elegidos en el menú
    private fun resolveDraftDirection(text: String, manualTarget: String?): Pair<String, String> {
        val menuSource = if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
        val menuTarget = configuredTarget
        if (manualTarget != null) {
            val source = if (manualTarget == menuTarget) menuSource else menuTarget
            return source to manualTarget
        }
        val greekLang = TranslateLanguage.GREEK
        val hasGreekAndLatinPair = (menuSource == greekLang) xor (menuTarget == greekLang)
        if (hasGreekAndLatinPair) {
            val otherLang = if (menuSource == greekLang) menuTarget else menuSource
            return when (TextNormalizer.detectScriptType(text)) {
                TextNormalizer.ScriptType.GREEK -> greekLang to otherLang
                TextNormalizer.ScriptType.LATIN -> otherLang to greekLang
                TextNormalizer.ScriptType.NEUTRAL -> menuTarget to menuSource
            }
        }
        return menuTarget to menuSource
    }

    private fun isPlaceholderText(text: String, hintText: String): Boolean {
        if (text.length < 2 || !text.any { it.isLetter() }) return true
        if (text.equals(hintText, ignoreCase = true)) return true
        return PLACEHOLDER_TOKENS.contains(text.lowercase())
    }

    private fun handleOutgoingTyping(event: AccessibilityEvent? = null) {
        val node = (if (event?.source?.isEditable == true) event.source else null) ?: findCurrentEditableNode()

        if (node != null) {
            observeInputForLearning(node)
        }

        if (!isKeyboardTranslatorEnabled || node == null) {
            overlayManager.removeDraftOverlay()
            return
        }

        val rawText = node.text?.toString()?.trim() ?: ""
        val hintText = node.hintText?.toString()?.trim() ?: ""

        if (rawText == lastInsertedText) {
            overlayManager.removeDraftOverlay()
            return
        }
        lastInsertedText = null

        val isPlaceholder = isPlaceholderText(rawText, hintText)
        if (isPlaceholder) {
            // Por qué: Si el campo está vacío o muestra el placeholder de WhatsApp,
            // se limpia de inmediato la pastilla para mantener la pantalla 100% limpia sin textos distractores.
            typingJob?.cancel()
            manualDraftTargetLang = null
            dismissedDraftText = null
            overlayManager.removeDraftOverlay()
            return
        }

        if (rawText == dismissedDraftText) {
            overlayManager.removeDraftOverlay()
            return
        }
        dismissedDraftText = null

        val currentRect = Rect()
        node.getBoundsInScreen(currentRect)
        // Por qué: Si el texto del borrador y la posición vertical del campo no han cambiado
        // y la píldora ya está visible, se evita reiniciar el trabajo de traducción en cada escaneo.
        if (overlayManager.currentDraftOverlayBounds != null &&
            rawText == lastRenderedDraftSource &&
            manualDraftTargetLang == lastRenderedDraftManualLang &&
            kotlin.math.abs(currentRect.top - lastRenderedDraftTop) <= (4 * resources.displayMetrics.density).toInt()
        ) {
            return
        }

        val (_, draftTargetLang) = resolveDraftDirection(rawText, manualDraftTargetLang)

        typingJob?.cancel()
        typingJob = serviceScope.launch {
            // Por qué: Debounce de 200ms para responder de forma ágil a la escritura fluida sin saturar ejecuciones redundantes
            delay(200L)

            val liveNode = (if (event?.source?.isEditable == true) event.source else null) ?: findCurrentEditableNode() ?: node
            val liveText = liveNode.text?.toString()?.trim() ?: rawText
            val liveHint = liveNode.hintText?.toString()?.trim() ?: hintText

            if (isPlaceholderText(liveText, liveHint)) {
                manualDraftTargetLang = null
                overlayManager.removeDraftOverlay()
                return@launch
            }

            val freshRect = Rect()
            liveNode.getBoundsInScreen(freshRect)
            if (freshRect.top <= 0) {
                node.getBoundsInScreen(freshRect)
            }

            // Re-evaluar idioma según el texto actualizado respetando los idiomas elegidos en el menú
            val (effectiveSourceLang, effectiveTargetLang) = resolveDraftDirection(
                liveText,
                manualDraftTargetLang ?: draftTargetLang.takeIf { TextNormalizer.detectScriptType(liveText) == TextNormalizer.ScriptType.NEUTRAL }
            )
            val effectiveBadge = effectiveTargetLang.uppercase()

            // Por qué: Los mensajes visibles sobre el campo de texto dan contexto conversacional al borrador
            val draftContext = findWhatsAppRootNode()
                ?.let { root -> collectVisibleConversation(root, freshRect.top) }
                ?.map { it.text }
                ?.takeLast(AppDefaults.MAX_CONTEXT_MESSAGES)
                ?: emptyList()

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

                // 1. Pre-traducción idiomática instantánea
                val lineIdiom = TranslationSenseEngine.resolveIdiomPreTranslation(
                    normalizedLine,
                    effectiveSourceLang,
                    effectiveTargetLang
                )
                if (lineIdiom != null) {
                    putCachedTranslation(effectiveSourceLang, effectiveTargetLang, normalizedLine, lineIdiom)
                    translatedLines.add(lineIdiom)
                    continue
                }

                // 2. Memoria de frases verificadas por el usuario (aprendizaje local)
                val remembered = lookupLearnedMemory(effectiveSourceLang, effectiveTargetLang, normalizedLine)
                if (remembered != null) {
                    translatedLines.add(remembered)
                    continue
                }

                // 3. Caché local instantánea con espacio de nombres por par de idiomas
                val cached = getCachedTranslation(effectiveSourceLang, effectiveTargetLang, normalizedLine)
                if (cached != null) {
                    translatedLines.add(cached)
                    continue
                }

                // 4. Traducción unificada utilizando hybridEngine (el mismo motor que traduce los mensajes en pantalla)
                val protected = TextNormalizer.protectEmojis(normalizedLine)
                val hints = buildHints(effectiveSourceLang, effectiveTargetLang, draftContext)
                val rawTranslated = hybridEngine.translate(protected.text, effectiveSourceLang, effectiveTargetLang, hints)

                if (rawTranslated != null && rawTranslated.isNotBlank() && rawTranslated != protected.text) {
                    val restoredEmojis = TextNormalizer.restoreEmojis(rawTranslated, protected.tokens)
                    val senseCorrected = applyLearnedCorrections(
                        effectiveSourceLang,
                        effectiveTargetLang,
                        TranslationSenseEngine.applyPostTranslationSenseLogic(
                            originalText = normalizedLine,
                            translatedText = restoredEmojis,
                            sourceLang = effectiveSourceLang,
                            targetLang = effectiveTargetLang
                        )
                    )
                    putCachedTranslation(effectiveSourceLang, effectiveTargetLang, normalizedLine, senseCorrected)
                    translatedLines.add(senseCorrected)
                } else {
                    translatedLines.add(trimmedLine)
                }
            }

            val finalTranslatedDraft = translatedLines.joinToString("\n")
            if (finalTranslatedDraft.isNotBlank() && finalTranslatedDraft != liveText) {
                mainHandler.post {
                    if (ServiceBridge.isTranslatingActive && isWhatsAppInForeground() && !isPlaceholderText(liveText, liveHint)) {
                        lastRenderedDraftSource = liveText
                        lastRenderedDraftTop = freshRect.top
                        lastRenderedDraftManualLang = manualDraftTargetLang
                        overlayManager.updateDraftOverlay(
                            inputRect = freshRect,
                            text = finalTranslatedDraft,
                            targetLangCode = effectiveBadge,
                            showReplace = true,
                            onInsertClicked = {
                                // Por qué: Se recuerda el borrador insertado para comparar después con lo que realmente se envía
                                pendingDraftSession = DraftSession(
                                    sourceText = liveText,
                                    machineTranslation = finalTranslatedDraft,
                                    sourceLang = effectiveSourceLang,
                                    targetLang = effectiveTargetLang,
                                    createdAtMillis = android.os.SystemClock.uptimeMillis()
                                )
                                insertTranslatedTextIntoInput(finalTranslatedDraft, liveNode)
                            },
                            onDismissClicked = {
                                dismissedDraftText = liveText
                                manualDraftTargetLang = null
                                overlayManager.removeDraftOverlay()
                            },
                            onBadgeClicked = {
                                // Por qué: Alterna manualmente la dirección entre los dos idiomas elegidos en el menú
                                val menuSource = if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
                                val menuTarget = configuredTarget
                                manualDraftTargetLang = if (effectiveTargetLang == menuTarget) {
                                    menuSource
                                } else {
                                    menuTarget
                                }
                                lastRenderedDraftSource = null
                                handleOutgoingTyping(event)
                            }
                        )
                    } else {
                        overlayManager.removeDraftOverlay()
                    }
                }
            } else {
                mainHandler.post {
                    overlayManager.removeDraftOverlay()
                }
            }
        }
    }

    // ── Aprendizaje adaptativo local ─────────────────────────────────────────

    private fun lookupLearnedMemory(sourceLang: String, targetLang: String, text: String): String? {
        if (!isLearningEnabled) return null
        return learningStore.lookupMemory(sourceLang, targetLang, text)
    }

    private fun applyLearnedCorrections(sourceLang: String, targetLang: String, text: String): String {
        if (!isLearningEnabled) return text
        return learningStore.applyConfirmedCorrections(sourceLang, targetLang, text)
    }

    // Por qué: El contexto conversacional siempre ayuda a desambiguar; el glosario solo si el aprendizaje está activo
    private fun buildHints(sourceLang: String, targetLang: String, context: List<String>): TranslationHints {
        val terms = if (isLearningEnabled) learningStore.promptTerms(sourceLang, targetLang) else emptyList()
        return TranslationHints(contextMessages = context, preferredTerms = terms)
    }

    // Por qué: Recoge todos los mensajes visibles (en ambos idiomas) ordenados de arriba abajo como historial del chat
    private fun collectVisibleConversation(rootNode: AccessibilityNodeInfo, maxY: Int): List<MessageCandidate> {
        val density = resources.displayMetrics.density
        val topInset = (48 * density).toInt()
        val messages = mutableListOf<MessageCandidate>()
        collectMessageCandidates(rootNode, messages, topInset, maxY, configuredSource, configuredTarget, requireTranslatable = false)
        return filterOverlappingCandidates(messages).sortedBy { it.bounds.top }
    }

    private fun contextAbove(conversation: List<MessageCandidate>, messageBounds: Rect): List<String> {
        return conversation
            .filter { it.bounds.bottom <= messageBounds.top + (4 * resources.displayMetrics.density).toInt() && it.bounds != messageBounds }
            .map { it.text }
            .takeLast(AppDefaults.MAX_CONTEXT_MESSAGES)
    }

    // Por qué: Observa el campo de texto para detectar el envío de un borrador insertado y aprender de las ediciones
    private fun observeInputForLearning(inputNode: AccessibilityNodeInfo) {
        val rawText = inputNode.text?.toString()?.trim() ?: ""
        val hintText = inputNode.hintText?.toString()?.trim() ?: ""
        val isEmptyField = rawText.isEmpty() ||
            rawText.equals(hintText, ignoreCase = true) ||
            PLACEHOLDER_TOKENS.contains(rawText.lowercase())

        val sentText = sentMessageDetector.observe(if (isEmptyField) "" else rawText) ?: return
        val session = pendingDraftSession ?: return
        pendingDraftSession = null

        val sessionAge = android.os.SystemClock.uptimeMillis() - session.createdAtMillis
        if (!isLearningEnabled || sessionAge > LearningStore.DRAFT_SESSION_TTL_MS) return

        serviceScope.launch {
            // Por qué: Confirma que el texto aparece como burbuja enviada (y no que el usuario borró todo de golpe)
            delay(700L)
            if (!isSentBubbleVisible(sentText)) {
                Log.d("Translator", "Learning skipped: sent bubble not confirmed")
                return@launch
            }
            val learned = kotlinx.coroutines.withContext(Dispatchers.Default) {
                learningStore.learnFromSentDraft(session, sentText)
            }
            if (learned) {
                // Por qué: Invalida traducciones en caché que podrían contradecir lo recién aprendido
                translationCache.evictAll()
            }
        }
    }

    private fun isSentBubbleVisible(sentText: String): Boolean {
        return try {
            val root = findWhatsAppRootNode() ?: return false
            val probe = sentText.lines().first().take(60)
            root.findAccessibilityNodeInfosByText(probe).any { !it.isEditable }
        } catch (e: Exception) {
            false
        }
    }

    // Por qué: Marca el contenido como sensible (Android 13+) para que el sistema no muestre el texto privado
    // en la vista previa del portapapeles ni lo ofrezca a teclados con historial de portapapeles.
    private fun copyTranslationToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Translated Text", text).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        mainHandler.post {
            overlayManager.removeDraftOverlay()
            Toast.makeText(this, "Copied to clipboard (Tap Paste in chat)", Toast.LENGTH_SHORT).show()
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
                        overlayManager.removeDraftOverlay()
                        Toast.makeText(this, "Replaced in chat", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Fallback to clipboard if direct action is restricted
                    copyTranslationToClipboard(text)
                }
            } else {
                copyTranslationToClipboard(text)
            }
            mainHandler.post {
                overlayManager.removeDraftOverlay()
            }
        } catch (e: Exception) {
            Log.e("Translator", "Failed to set text in input", e)
            mainHandler.post {
                overlayManager.removeDraftOverlay()
            }
        }
    }

    // Por qué: Identifica de forma asíncrona el idioma de un texto mediante ML Kit
    // para confirmar que el mensaje pertenezca al idioma de origen configurado antes de traducir.
    private suspend fun identifyLanguageAsync(text: String): String? = suspendCancellableCoroutine { cont ->
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                if (cont.isActive) cont.resume(if (langCode == "und") null else langCode)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
    }

    private fun findFirstTextChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!node.isEditable && !node.text.isNullOrBlank()) {
            val cls = node.className?.toString() ?: ""
            if (!cls.contains("Image", ignoreCase = true) && !cls.contains("Button", ignoreCase = true)) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstTextChild(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Traduce únicamente el mensaje específico sobre el que el usuario ha pulsado.
     * Por qué: Proporciona la experiencia 'Tap to Translate', evitando cubrir toda la pantalla con overlays
     * y permitiendo traducir con alta fidelidad solo el texto seleccionado en los idiomas del menú.
     */
    private fun handleMessageClicked(sourceNode: AccessibilityNodeInfo?) {
        if (!ServiceBridge.isTranslatingActive) return
        if (sourceNode == null) return
        if (!isWhatsAppInForeground()) return
        val nodePkg = sourceNode.packageName?.toString() ?: ""
        if (nodePkg.isNotBlank() && !isWhatsAppPackage(nodePkg)) return

        try {
            val nodeWithText = findFirstTextChild(sourceNode) ?: sourceNode
            val text = nodeWithText.text?.toString() ?: ""

            if (text.isBlank()) return
            val cleanText = TranslationFilter.cleanMessageText(text).trim()
            if (cleanText.isBlank() || !cleanText.any { it.isLetter() }) return
            if (!TranslationFilter.shouldTranslate(cleanText, configuredTarget, configuredSource)) return

            val rect = Rect()
            nodeWithText.getBoundsInScreen(rect)
            val screenWidth = resources.displayMetrics.widthPixels
            if (!TranslationSenseEngine.isMessageBubble(rect, screenWidth)) return

            // Por qué: Usa estrictamente el par de idiomas configurado por el usuario en el menú
            val msgSource = if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
            val msgTarget = configuredTarget
            val targetCode = msgTarget.uppercase()

            val normalizedText = if (msgSource == TranslateLanguage.ROMANIAN) {
                TextNormalizer.restoreRomanianDiacritics(cleanText)
            } else {
                cleanText
            }
            val protected = TextNormalizer.protectEmojis(normalizedText)

            serviceScope.launch {
                val detectedLang = identifyLanguageAsync(cleanText)
                val isEligible = TranslationSenseEngine.isEligibleSourceText(
                    text = cleanText,
                    sourceLang = msgSource,
                    targetLang = msgTarget,
                    identifiedLang = detectedLang
                )
                if (!isEligible) {
                    return@launch
                }

                val remembered = lookupLearnedMemory(msgSource, msgTarget, cleanText)
                if (remembered != null) {
                    mainHandler.post { overlayManager.showSingleMessagePill(rect, remembered, targetCode) }
                    return@launch
                }

                val cached = getCachedTranslation(msgSource, msgTarget, cleanText)
                if (cached != null) {
                    mainHandler.post { overlayManager.showSingleMessagePill(rect, cached, targetCode) }
                    return@launch
                }

                val context = findWhatsAppRootNode()
                    ?.let { root -> contextAbove(collectVisibleConversation(root, rect.top), rect) }
                    ?: emptyList()
                val hints = buildHints(msgSource, msgTarget, context)
                val translated = hybridEngine.translate(protected.text, msgSource, msgTarget, hints)
                if (!translated.isNullOrBlank()) {
                    val restored = TextNormalizer.restoreEmojis(translated, protected.tokens)
                    val corrected = applyLearnedCorrections(
                        msgSource,
                        msgTarget,
                        TranslationSenseEngine.applyPostTranslationSenseLogic(
                            originalText = normalizedText,
                            translatedText = restored,
                            sourceLang = msgSource,
                            targetLang = msgTarget
                        )
                    )
                    if (corrected.trim().equals(cleanText.trim(), ignoreCase = true)) {
                        return@launch
                    }
                    if (msgTarget == TranslateLanguage.GREEK && corrected.none { (it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF') && it.isLetter() }) {
                        return@launch
                    }
                    putCachedTranslation(msgSource, msgTarget, cleanText, corrected)
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
        ServiceBridge.onScanRequested = null
        scrollSettleJob?.cancel()
        overlayWatchdogJob?.cancel()
        isScreenScrolling = false
        TranslationNotificationManager.cancelNotification(this)
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
        ServiceBridge.onScanRequested = null
        scrollSettleJob?.cancel()
        overlayWatchdogJob?.cancel()
        isScreenScrolling = false
        TranslationNotificationManager.cancelNotification(this)
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
