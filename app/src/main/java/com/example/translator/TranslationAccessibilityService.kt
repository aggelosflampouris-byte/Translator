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
    private var isGlossaryAndValidationEnabled: Boolean = true
    private var translationMode: String = AppDefaults.MODE_ALL
    private lateinit var pipeline: TranslationPipeline
    private var manualDraftTargetLang: String? = null
    private val resourcesReleased = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var enabledTargetPackages: Set<String> = TargetAppCatalog.getDefaultEnabledPackages()
    @Volatile private var lastForegroundTargetPackage: String? = null

    // Por qué: Memoria local de aprendizaje (frases enviadas y correcciones del usuario) y estado del bucle de retroalimentación
    private lateinit var learningStore: LearningStore
    private var isLearningEnabled: Boolean = true
    private val sentMessageDetector = SentMessageDetector()
    @Volatile private var pendingDraftSession: DraftSession? = null

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            AppDefaults.KEY_ENABLED_PACKAGES -> {
                enabledTargetPackages = TargetAppCatalog.getEnabledPackages(prefs, packageName)
                syncAccessibilityPackageScope()
                if (!isTargetAppInForeground()) {
                    mainHandler.post { overlayManager.removeAllOverlays() }
                } else {
                    scheduleScan(100L)
                }
            }
            AppDefaults.KEY_SOURCE_LANGUAGE -> {
                configuredSource = prefs.getString(key, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
                manualDraftTargetLang = null
                lastRenderedDraftSource = null
                mainHandler.post { overlayManager.removeAllOverlays() }
                if (ServiceBridge.isNotificationActive) {
                    TranslationNotificationManager.showNotification(this, ServiceBridge.isTranslatingActive)
                }
                serviceScope.launch(Dispatchers.Default) { TranslationPipeline.preloadModels(this@TranslationAccessibilityService) }
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
                serviceScope.launch(Dispatchers.Default) { TranslationPipeline.preloadModels(this@TranslationAccessibilityService) }
                scheduleScan(100L)
            }
            AppDefaults.KEY_KEYBOARD_ENABLED -> {
                isKeyboardTranslatorEnabled = prefs.getBoolean(key, true)
            }
            AppDefaults.KEY_AI_ENABLED -> {
                isGlossaryAndValidationEnabled = prefs.getBoolean(key, true)
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
            AppDefaults.KEY_OVERLAY_THEME -> {
                val theme = prefs.getString(key, AppDefaults.OVERLAY_THEME_AUTO) ?: AppDefaults.OVERLAY_THEME_AUTO
                overlayManager.setOverlayTheme(theme)
                mainHandler.post { overlayManager.removeAllMessageOverlays() }
                scheduleScan(100L)
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
        resourcesReleased.set(false)
        overlayManager = OverlayManager(this)
        learningStore = LearningStore.get(this)
        pipeline = TranslationPipeline(
            engine = MLKitTranslationEngine(),
            isGlossaryAndValidationEnabled = { isGlossaryAndValidationEnabled },
            memoryLookup = ::lookupLearnedMemory,
            cacheLookup = ::getCachedTranslation,
            cacheStore = ::putCachedTranslation,
            learnedCorrectionsApplier = ::applyLearnedCorrections
        )

        val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        configuredSource = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
        configuredTarget = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
        isKeyboardTranslatorEnabled = prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, true)
        isGlossaryAndValidationEnabled = prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true)
        isLearningEnabled = prefs.getBoolean(AppDefaults.KEY_LEARNING_ENABLED, true)
        translationMode = prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL
        enabledTargetPackages = TargetAppCatalog.getEnabledPackages(prefs, packageName)
        overlayManager.setOverlayTheme(
            prefs.getString(AppDefaults.KEY_OVERLAY_THEME, AppDefaults.OVERLAY_THEME_AUTO) ?: AppDefaults.OVERLAY_THEME_AUTO
        )
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
                val latestPrefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
                enabledTargetPackages = TargetAppCatalog.getEnabledPackages(latestPrefs, packageName)
                syncAccessibilityPackageScope()
                startActiveScanning()
            }
        }

        // Por qué: El vigilante se inicia de inmediato y permanece activo para garantizar que ninguna
        // pastilla huérfana sobreviva fuera de una app habilitada, incluso si el servicio cambia de estado.
        startOverlayWatchdog()

        // Por qué: Precarga el modelo neuronal en memoria en segundo plano para eliminar la latencia inicial al abrir un chat
        serviceScope.launch(Dispatchers.Default) {
            TranslationPipeline.preloadModels(this@TranslationAccessibilityService)
        }

        // Por qué: Callback ejecutado cuando el usuario pulsa "Translate Screen" en la notificación o mosaico
        ServiceBridge.onScanRequested = {
            serviceScope.launch {
                isScreenScrolling = false
                lastScrollTimestamp = 0L
                var activeTarget = resolveForegroundTargetPackage()
                if (activeTarget == null) {
                    // Por qué: Al pulsar la acción desde la barra de notificaciones, SystemUI tarda ~300ms en cerrarse
                    delay(350L)
                    activeTarget = resolveForegroundTargetPackage()
                }
                handleForegroundTargetTransition(activeTarget)
                if (activeTarget == null) {
                    mainHandler.post {
                        overlayManager.removeAllOverlays()
                        Toast.makeText(applicationContext, "Open a supported chat app to translate messages", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                scanAndTranslateVisibleMessages()
            }
        }

        syncAccessibilityPackageScope()

        Log.d("Translator", "TranslationAccessibilityService connected")
    }

    private fun syncAccessibilityPackageScope() {
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            val scope = enabledTargetPackages
            // Por qué: Cuando hay apps habilitadas dejamos packageNames en null a nivel de sistema para recibir
            // transiciones de ventana (lanzador, teclado, Messenger/Litho) sin bloqueos de caché de OEM,
            // aplicando el filtrado estricto de paquetes en isEnabledTargetPackage().
            info.packageNames = if (scope.isEmpty()) {
                arrayOf("${applicationContext.packageName}.none")
            } else {
                null
            }
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
    }

    private fun isEnabledTargetPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return enabledTargetPackages.contains(pkg) &&
            !TargetAppCatalog.isBlockedSensitivePackage(pkg, applicationContext.packageName)
    }

    private fun isBlockedNonTargetPackage(pkg: String?): Boolean {
        return TargetAppCatalog.isBlockedSensitivePackage(pkg, applicationContext.packageName)
    }

    // Por qué: Distingue superficies superpuestas transitorias (teclado IME, barra del sistema o burbuja propia)
    // para que resolveForegroundTargetPackage inspeccione la ventana TYPE_APPLICATION subyacente en lugar de abortar.
    private fun isTransientOverlayOrInputPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == applicationContext.packageName) return true
        if (pkg == "com.android.systemui") return true
        return isKeyboardPackage(pkg)
    }

    private fun isKeyboardPackage(pkg: String?): Boolean {
        return TargetAppCatalog.isKeyboardPackage(pkg)
    }

    // Por qué: Al cambiar de una app habilitada a otra (p. ej. WhatsApp a Telegram), limpia inmediatamente
    // las burbujas previas, trabajos en vuelo y estado de borrador para evitar superposiciones cruzadas.
    private fun handleForegroundTargetTransition(activeTargetPkg: String?) {
        if (activeTargetPkg == lastForegroundTargetPackage) return
        lastForegroundTargetPackage = activeTargetPkg
        scanGeneration.incrementAndGet()
        inFlightKeys.clear()
        typingJob?.cancel()
        scanDebounceJob?.cancel()
        lastRenderedDraftSource = null
        manualDraftTargetLang = null
        dismissedDraftText = null
        mainHandler.post {
            overlayManager.removeAllOverlays()
        }
    }

    // Por qué: Determina de forma estricta qué aplicación habilitada está en primer plano,
    // descartando lanzadores, teclados y apps bloqueadas para evitar falsos positivos.
    private fun resolveForegroundTargetPackage(): String? {
        try {
            val active = rootInActiveWindow
            if (active != null) {
                val pkg = active.packageName?.toString() ?: ""
                if (isEnabledTargetPackage(pkg)) {
                    return pkg
                }
                if (!isTransientOverlayOrInputPackage(pkg) && isBlockedNonTargetPackage(pkg)) {
                    return null
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val windowList = windows
            if (windowList != null && windowList.isNotEmpty()) {
                for (window in windowList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                        val root = window.root ?: continue
                        val pkg = root.packageName?.toString() ?: ""
                        if (isEnabledTargetPackage(pkg)) {
                            return pkg
                        }
                        if (!isTransientOverlayOrInputPackage(pkg) && isBlockedNonTargetPackage(pkg)) {
                            return null
                        }
                    }
                }

                // Por qué: Si el teclado IME o barra del sistema tiene el foco activo, inspecciona la ventana de app subyacente
                val appWindows = windowList.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                if (appWindows.isNotEmpty()) {
                    val activeApp = appWindows.firstOrNull { it.isActive } ?: appWindows.firstOrNull()
                    val pkg = activeApp?.root?.packageName?.toString() ?: ""
                    if (isEnabledTargetPackage(pkg)) {
                        return pkg
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return null
    }

    private fun isTargetAppInForeground(): Boolean = resolveForegroundTargetPackage() != null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!ServiceBridge.isTranslatingActive) return

        val packageName = event.packageName?.toString() ?: ""
        val activeForeground = resolveForegroundTargetPackage()
        handleForegroundTargetTransition(activeForeground)

        // Por qué: Limpieza inmediata de pantalla si el evento proviene de una app externa o del lanzador
        if (!isEnabledTargetPackage(packageName) && !isKeyboardPackage(packageName)) {
            if (activeForeground == null) {
                mainHandler.post {
                    overlayManager.removeAllOverlays()
                }
                return
            }
        }

        if (isBlockedNonTargetPackage(packageName) || isKeyboardPackage(packageName)) {
            return
        }

        if (!isEnabledTargetPackage(packageName)) {
            if (activeForeground == null) {
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
                    if (isTargetAppInForeground() && ServiceBridge.isTranslatingActive) {
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
        if (!isTargetAppInForeground()) {
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
            if (ServiceBridge.isTranslatingActive && isTargetAppInForeground() && !isScreenScrolling && elapsed >= 350L) {
                scanAndTranslateVisibleMessages(eventSource)
            }
        }
    }

    private var overlayWatchdogJob: Job? = null

    // Por qué: Vigila activamente si hay píldoras dibujadas en pantalla y las remueve inmediatamente
    // si el usuario sale de la app objetivo o cambia entre apps habilitadas sin generar evento previo.
    private fun startOverlayWatchdog() {
        overlayWatchdogJob?.cancel()
        overlayWatchdogJob = serviceScope.launch {
            while (true) {
                delay(250L)
                val activeTarget = resolveForegroundTargetPackage()
                handleForegroundTargetTransition(activeTarget)
                if (overlayManager.hasOverlays()) {
                    if (activeTarget == null || !ServiceBridge.isTranslatingActive) {
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
            if (isTargetAppInForeground() && !isScreenScrolling && elapsedInit >= 350L) {
                scanAndTranslateVisibleMessages()
            }

            while (ServiceBridge.isTranslatingActive) {
                delay(2000L)
                if (!ServiceBridge.isTranslatingActive) break
                val timeSinceScroll = android.os.SystemClock.uptimeMillis() - lastScrollTimestamp
                if (isTargetAppInForeground() && !isScreenScrolling && timeSinceScroll >= 350L) {
                    scanAndTranslateVisibleMessages()
                } else if (!isTargetAppInForeground() && overlayManager.hasOverlays()) {
                    mainHandler.post { overlayManager.removeAllOverlays() }
                }
            }
        }
    }

    private fun findActiveTargetRootNode(eventSource: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val activePkg = resolveForegroundTargetPackage() ?: return null
        handleForegroundTargetTransition(activePkg)

        // Por qué: Prioriza siempre la raíz real de la ventana (rootInActiveWindow / windows) sobre eventSource,
        // ya que en frameworks como Litho (Messenger) eventSource.parent puede detenerse en un subnodo parcial.
        try {
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() == activePkg) {
                return active
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying rootInActiveWindow", e)
        }

        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = window.root ?: continue
                        if (root.packageName?.toString() == activePkg) {
                            return root
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying windows in accessibility service", e)
        }

        if (eventSource != null) {
            try {
                var current: AccessibilityNodeInfo = eventSource
                while (true) {
                    val parent = current.parent ?: break
                    current = parent
                }
                if (current.packageName?.toString() == activePkg) {
                    val bounds = Rect()
                    current.getBoundsInScreen(bounds)
                    if (bounds.height() >= resources.displayMetrics.heightPixels / 2) {
                        return current
                    }
                }
            } catch (e: Exception) {
                // Ignore recycled node
            }
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

        // Por qué: Verifica estrictamente que una app habilitada esté en primer plano antes de iniciar cualquier escaneo
        if (!isTargetAppInForeground()) {
            mainHandler.post {
                overlayManager.removeAllOverlays()
            }
            return 0
        }

        val rootNode = findActiveTargetRootNode(eventSource)
        if (rootNode == null) {
            mainHandler.post {
                overlayManager.removeAllOverlays()
            }
            return 0
        }
        val activeProfile = TargetAppCatalog.profileForPackage(rootNode.packageName?.toString())

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
        val topInset = (activeProfile.topInsetDp * density).toInt()
        val bottomInset = (activeProfile.bottomInsetDp * density).toInt()
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

        collectMessageCandidates(
            node = rootNode,
            outList = candidates,
            minY = topInset,
            maxY = effectiveBottom,
            configuredSource = configuredSource,
            configuredTarget = configuredTarget,
            profile = activeProfile
        )

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

            val fastTranslation = pipeline.resolveFast(cleanText, msgSource, msgTarget)
            if (fastTranslation != null) {
                immediateResults.add(Pair(rect, fastTranslation))
                continue
            }

            pendingCandidates.add(candidate)
        }

        // Por qué: Renderizar de forma inmediata todas las traducciones ya resueltas (en caché o por modismos)
        // para que aparezcan en pantalla en menos de 2ms sin esperar a las peticiones de red.
        if (immediateResults.isNotEmpty()) {
            mainHandler.post {
                if (ServiceBridge.isTranslatingActive && isTargetAppInForeground() && currentGeneration == scanGeneration.get() && !isScreenScrolling) {
                    overlayManager.updateOverlays(immediateResults, isFullSync = pendingCandidates.isEmpty(), profile = activeProfile)
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

                    val hints = buildHints(msgSource, msgTarget, contextAbove(conversation, rect))
                    val senseCorrected = pipeline.translate(cleanText, msgSource, msgTarget, hints)
                    if (!senseCorrected.isNullOrBlank()) {
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
                            !isTargetAppInForeground() ||
                            !ServiceBridge.isTranslatingActive
                        ) {
                            return@launch
                        }

                        mainHandler.post {
                            if (currentGeneration == scanGeneration.get() &&
                                ServiceBridge.isTranslatingActive &&
                                isTargetAppInForeground() &&
                                !isScreenScrolling
                            ) {
                                overlayManager.addOrUpdateOverlay(rect, senseCorrected, profile = activeProfile)
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
        requireTranslatable: Boolean = true,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE
    ): Boolean {
        // Por qué: Ignora inmediatamente nodos fuera de las apps habilitadas para no leer pantallas ajenas
        val nodePkg = node.packageName?.toString() ?: ""
        if (nodePkg.isNotBlank() && !isEnabledTargetPackage(nodePkg)) {
            return false
        }

        if (node.isEditable) {
            // Skip input fields during message scan (handled by handleOutgoingTyping)
            return false
        }

        var childFound = false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = collectMessageCandidates(
                child,
                outList,
                minY,
                maxY,
                configuredSource,
                configuredTarget,
                requireTranslatable,
                profile
            )
            if (found) {
                childFound = true
            }
        }

        // If children already contained the message text, do not process container node
        if (childFound) {
            return true
        }

        // Por qué: Descarta controles no textuales e imágenes sin bloquear burbujas clicables de Litho (Messenger/Instagram)
        // que exponen AccessibilityRole.BUTTON ("android.widget.Button").
        val className = node.className?.toString() ?: ""
        if (className.contains("Image", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true) ||
            className.contains("SeekBar", ignoreCase = true) ||
            className.contains("ProgressBar", ignoreCase = true) ||
            className.contains("EditText", ignoreCase = true)) {
            return false
        }

        // Por qué: Frameworks como Litho (Messenger/Instagram) y celdas personalizadas suelen exponer el texto
        // del mensaje en contentDescription cuando no emplean TextView nativos.
        val isScrollableContainer = node.isScrollable ||
            className.contains("RecyclerView", ignoreCase = true) ||
            className.contains("ListView", ignoreCase = true) ||
            className.contains("ScrollView", ignoreCase = true) ||
            className.contains("ViewPager", ignoreCase = true) ||
            className.contains("Toolbar", ignoreCase = true)
        val directText = node.text?.toString()?.takeIf { it.isNotBlank() }
        val rawText = directText ?: if (!isScrollableContainer) {
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (rawText.isNullOrBlank()) {
            return false
        }

        val cleanText = TranslationFilter.cleanMessageText(rawText)
        val passesLanguageFilter = !requireTranslatable || TranslationFilter.shouldTranslate(cleanText, configuredTarget, configuredSource)
        if (cleanText.isNotBlank() && passesLanguageFilter) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val density = resources.displayMetrics.density

            // Por qué: Si el texto provino de contentDescription, descarta iconos cuadrados pequeños (botones de llamada/cámara)
            if (directText == null && rect.width() < (48 * density).toInt() && rect.height() < (48 * density).toInt()) {
                return false
            }

            // Por qué: Solo expande al contenedor padre en apps con burbujas aisladas; en filas anchas
            // (como Discord o Messenger) limita la expansión para no cubrir avatares ni cabeceras de usuario.
            var container = node.parent
            val maxLevels = if (profile.expandContainerToParent) 1 else 0
            val maxParentWidthRatio = if (profile.expandContainerToParent) 0.95f else 0.85f
            for (level in 0..maxLevels) {
                if (container != null) {
                    val pRect = Rect()
                    container.getBoundsInScreen(pRect)
                    val safeExpansion = profile.expandContainerToParent ||
                        (pRect.width() <= (rect.width() * 1.35f) && pRect.height() <= (rect.height() * 1.50f))
                    if (safeExpansion &&
                        pRect.width() <= screenWidth * maxParentWidthRatio &&
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

            // Por qué: Cuando Litho (Messenger) o ChatMessageCell (Telegram) aplana la fila del mensaje a todo el ancho
            // de la pantalla, aplica un margen horizontal limpio para pasar la validación de burbuja y no tocar bordes.
            if (rect.width() >= (screenWidth * 0.94f).toInt() && rect.height() <= (screenHeight * 0.45f).toInt()) {
                val horizontalMargin = (16 * density).toInt()
                rect.left = (rect.left + horizontalMargin).coerceAtMost(rect.right - 24)
                rect.right = (rect.right - horizontalMargin).coerceAtLeast(rect.left + 24)
            }

            // Validate chat message bubble based on active app alignment layout
            val isBubble = TranslationSenseEngine.isMessageBubble(rect.left, rect.right, screenWidth, profile.alignmentMode)
            val visibleMargin = (20 * density).toInt()

            if (isBubble && rect.width() > (22 * density).toInt() && rect.height() > (16 * density).toInt() &&
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

        val root = findActiveTargetRootNode() ?: return null
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
            "μήνυμα", "μηνυμα", "message", "message...", "type a message", "type a message...",
            "aa", "signal message", "add a comment", "add a comment...", "write a message", "reply..."
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

    private fun isPlaceholderText(
        text: String,
        hintText: String,
        node: AccessibilityNodeInfo? = null
    ): Boolean {
        // Por qué: En Android 8+ la API del sistema indica de forma fiable si el texto actual es un hint visual
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && node?.isShowingHintText == true) {
            return true
        }
        if (text.length < 2 || !text.any { it.isLetter() }) return true
        if (text.equals(hintText, ignoreCase = true)) return true
        val lower = text.lowercase()
        if (lower.startsWith("message #") || lower.startsWith("message @")) return true
        return PLACEHOLDER_TOKENS.contains(lower)
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

        val isPlaceholder = isPlaceholderText(rawText, hintText, node)
        if (isPlaceholder) {
            // Por qué: Si el campo está vacío o muestra un placeholder de la app,
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

            if (isPlaceholderText(liveText, liveHint, liveNode)) {
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
            val draftContext = findActiveTargetRootNode()
                ?.let { root -> collectVisibleConversation(root, freshRect.top) }
                ?.map { it.text }
                ?.takeLast(AppDefaults.MAX_CONTEXT_MESSAGES)
                ?: emptyList()
            val hints = buildHints(effectiveSourceLang, effectiveTargetLang, draftContext)

            // Process message line-by-line through the unified pipeline to preserve paragraphs and mixed lines
            val lines = liveText.split("\n")
            val translatedLines = mutableListOf<String>()

            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) {
                    translatedLines.add(line)
                    continue
                }
                val translated = pipeline.translate(trimmedLine, effectiveSourceLang, effectiveTargetLang, hints)
                translatedLines.add(translated ?: trimmedLine)
            }

            val finalTranslatedDraft = translatedLines.joinToString("\n")
            if (finalTranslatedDraft.isNotBlank() && finalTranslatedDraft != liveText) {
                mainHandler.post {
                    if (ServiceBridge.isTranslatingActive && isTargetAppInForeground() && !isPlaceholderText(liveText, liveHint, liveNode)) {
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
        val profile = TargetAppCatalog.profileForPackage(rootNode.packageName?.toString())
        val density = resources.displayMetrics.density
        val topInset = (profile.topInsetDp * density).toInt()
        val messages = mutableListOf<MessageCandidate>()
        collectMessageCandidates(
            node = rootNode,
            outList = messages,
            minY = topInset,
            maxY = maxY,
            configuredSource = configuredSource,
            configuredTarget = configuredTarget,
            requireTranslatable = false,
            profile = profile
        )
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
            val root = findActiveTargetRootNode() ?: return false
            val probe = sentText.lines().first().take(60)
            root.findAccessibilityNodeInfosByText(probe).any { !it.isEditable }
        } catch (e: Exception) {
            false
        }
    }

    // Por qué: Marca el contenido como sensible (Android 13+) para que el sistema no muestre el texto privado
    // en la vista previa del portapapeles ni lo ofrezca a teclados con historial de portapapeles.
    private fun setSensitiveClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Translated Text", text).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    private fun copyTranslationToClipboard(text: String) {
        setSensitiveClipboard(text)
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
                val directSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                val pastedSuccess = if (!directSuccess) {
                    // Por qué: En editores personalizados o WebView donde ACTION_SET_TEXT falla, intenta pegar vía portapapeles
                    setSensitiveClipboard(text)
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
                    mainHandler.post {
                        overlayManager.removeDraftOverlay()
                        Toast.makeText(this, "Replaced in chat", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    mainHandler.post {
                        overlayManager.removeDraftOverlay()
                        Toast.makeText(this, "Copied to clipboard (Tap Paste in chat)", Toast.LENGTH_SHORT).show()
                    }
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
        val hasDirectOrDescText = !node.text.isNullOrBlank() || (node.childCount <= 3 && !node.contentDescription.isNullOrBlank())
        if (!node.isEditable && hasDirectOrDescText) {
            val cls = node.className?.toString() ?: ""
            if (!cls.contains("Image", ignoreCase = true) &&
                !cls.contains("CheckBox", ignoreCase = true) &&
                !cls.contains("Switch", ignoreCase = true)) {
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
        if (!isTargetAppInForeground()) return
        val nodePkg = sourceNode.packageName?.toString() ?: ""
        if (nodePkg.isNotBlank() && !isEnabledTargetPackage(nodePkg)) return
        val activeProfile = TargetAppCatalog.profileForPackage(nodePkg.ifBlank { resolveForegroundTargetPackage() })

        try {
            val nodeWithText = findFirstTextChild(sourceNode) ?: sourceNode
            val text = nodeWithText.text?.toString()?.takeIf { it.isNotBlank() }
                ?: nodeWithText.contentDescription?.toString()
                ?: ""

            if (text.isBlank()) return
            val cleanText = TranslationFilter.cleanMessageText(text).trim()
            if (cleanText.isBlank() || !cleanText.any { it.isLetter() }) return
            if (!TranslationFilter.shouldTranslate(cleanText, configuredTarget, configuredSource)) return

            val rect = Rect()
            nodeWithText.getBoundsInScreen(rect)
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val density = resources.displayMetrics.density
            if (rect.width() >= (screenWidth * 0.94f).toInt() && rect.height() <= (screenHeight * 0.45f).toInt()) {
                val horizontalMargin = (16 * density).toInt()
                rect.left = (rect.left + horizontalMargin).coerceAtMost(rect.right - 24)
                rect.right = (rect.right - horizontalMargin).coerceAtLeast(rect.left + 24)
            }
            if (!TranslationSenseEngine.isMessageBubble(rect, screenWidth, activeProfile.alignmentMode)) return

            // Por qué: Usa estrictamente el par de idiomas configurado por el usuario en el menú
            val msgSource = if (configuredSource != "AUTO") configuredSource else AppDefaults.SOURCE_LANGUAGE
            val msgTarget = configuredTarget
            val targetCode = msgTarget.uppercase()

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

                val context = findActiveTargetRootNode()
                    ?.let { root -> contextAbove(collectVisibleConversation(root, rect.top), rect) }
                    ?: emptyList()
                val hints = buildHints(msgSource, msgTarget, context)
                val corrected = pipeline.translate(cleanText, msgSource, msgTarget, hints)
                if (!corrected.isNullOrBlank()) {
                    mainHandler.post {
                        overlayManager.showSingleMessagePill(rect, corrected, targetCode)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error handling clicked message", e)
        }
    }

    // Por qué: Rutina única de liberación de recursos compartida por onUnbind y onDestroy
    private fun releaseServiceResources() {
        ServiceBridge.isAccessibilityActive = false
        ServiceBridge.onTranslationStateChanged = null
        ServiceBridge.onScanRequested = null
        typingJob?.cancel()
        scanDebounceJob?.cancel()
        activeScanLoopJob?.cancel()
        scrollSettleJob?.cancel()
        overlayWatchdogJob?.cancel()
        isScreenScrolling = false
        TranslationNotificationManager.cancelNotification(this)
        overlayManager.removeAllOverlays()
        if (resourcesReleased.compareAndSet(false, true)) {
            try {
                val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            } catch (e: Exception) {
                Log.w("Translator", "Error unregistering prefListener", e)
            }
            TranslatorPool.releaseAll()
            translationCache.evictAll()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        releaseServiceResources()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseServiceResources()
    }

    override fun onInterrupt() {
        overlayManager.removeAllOverlays()
    }
}
