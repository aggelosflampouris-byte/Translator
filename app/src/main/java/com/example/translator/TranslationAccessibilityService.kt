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
                translationMode = prefs.getString(key, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL
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

    @Volatile private var connectCount: Int = 0
    @Volatile private var stateChangeCount: Int = 0
    @Volatile private var scanLoopTicks: Int = 0
    @Volatile private var eventCount: Int = 0
    @Volatile private var lastEventPkg: String? = null
    @Volatile private var lastEventType: Int = 0
    @Volatile private var lastEventTimeMs: Long = 0L
    @Volatile private var lastNonTransientEventPkg: String? = null
    @Volatile private var lastNonTransientEventTimeMs: Long = 0L
    @Volatile private var lastTargetEventPkg: String? = null
    @Volatile private var lastTargetEventTimeMs: Long = 0L
    @Volatile private var cachedTargetRootPkg: String? = null
    @Volatile private var cachedTargetRootNode: AccessibilityNodeInfo? = null
    @Volatile private var lastPartialTargetRootPkg: String? = null
    @Volatile private var lastPartialTargetRootNode: AccessibilityNodeInfo? = null
    @Volatile private var lastRootExtractAttemptMs: Long = 0L
    @Volatile private var lastRootExtractDiag: String = "init"
    @Volatile private var targetSrcNonNullCount: Int = 0
    @Volatile private var targetSrcNullCount: Int = 0
    @Volatile private var lastDiagTargetPkg: String? = null
    @Volatile private var lastRootOrigin: String = "none"
    @Volatile private var pendingScanWindowStartMs: Long = 0L
    @Volatile private var acceptedScrollCount: Int = 0
    @Volatile private var ignoredScrollCount: Int = 0
    @Volatile private var lastScrollDiag: String = "none"
    @Volatile private var lastScrollPositionSig: ScrollPositionSignature? = null
    @Volatile private var lastExternalWindowsSummary: String = "none"
    @Volatile private var lastResolveReason: String = "init"
    @Volatile private var lastTraceUpdateMs: Long = 0L
    private val seenExternalPackages = LinkedHashMap<String, Int>(8)

    private fun recordSeenExternalPackage(pkg: String) {
        synchronized(seenExternalPackages) {
            if (!seenExternalPackages.containsKey(pkg) && seenExternalPackages.size >= 8) {
                val oldestKey = seenExternalPackages.keys.firstOrNull()
                if (oldestKey != null) seenExternalPackages.remove(oldestKey)
            }
            seenExternalPackages[pkg] = (seenExternalPackages[pkg] ?: 0) + 1
        }
    }

    private fun formatSeenPackages(): String {
        synchronized(seenExternalPackages) {
            if (seenExternalPackages.isEmpty()) return "none"
            return seenExternalPackages.entries.joinToString(",") { (pkg, count) ->
                val shortPkg = pkg.removePrefix("com.facebook.").removePrefix("com.samsung.android.")
                "$shortPkg#$count"
            }
        }
    }

    private fun updateRuntimeTrace(tag: String) {
        lastTraceUpdateMs = android.os.SystemClock.uptimeMillis()
        val msgEnabled = enabledTargetPackages.contains("com.facebook.orca")
        val loopAlive = activeScanLoopJob?.isActive == true
        ServiceBridge.lastRuntimeTrace =
            "$tag | c=$connectCount s=$stateChangeCount l=$scanLoopTicks(alive=$loopAlive) e=$eventCount " +
            "svcPkgs=${enabledTargetPackages.size}(msg=$msgEnabled) scr=$isScreenScrolling($acceptedScrollCount/$ignoredScrollCount:$lastScrollDiag) " +
            "fg=${lastForegroundTargetPackage ?: "null"} root=$lastRootOrigin($lastRootExtractDiag,src=$targetSrcNonNullCount/$targetSrcNullCount) res=$lastResolveReason " +
            "tgtEvt=${lastTargetEventPkg ?: "none"} lastEvt=${lastEventPkg ?: "none"}(${lastEventType}) seen=[${formatSeenPackages()}] " +
            "ext=[$lastExternalWindowsSummary]"
    }

    private fun maybeUpdateRuntimeTrace(throttled: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!throttled || now - lastTraceUpdateMs >= 450L) {
            updateRuntimeTrace("evt")
        }
    }

    // State is published via ServiceBridge — no Service reference held in statics.

    data class MessageCandidate(
        val text: String,
        val bounds: Rect
    )

    // Por qué: Acumula métricas no sensibles (sin texto privado) del recorrido del árbol de accesibilidad
    // para diagnosticar fallos de detección en aplicaciones específicas.
    private class ScanStats {
        var visitedNodes: Int = 0
        var editableNodes: Int = 0
        var nodesWithText: Int = 0
        var nodesWithDesc: Int = 0
        var skippedWidget: Int = 0
        var skippedFilter: Int = 0
        var skippedTinyDesc: Int = 0
        var skippedBubbleBounds: Int = 0
        var passedCandidates: Int = 0
        val samples = ArrayList<String>(6)

        fun addSample(entry: String) {
            if (samples.size < 6) samples.add(entry)
        }
    }

    private fun getScreenWidthPx(): Int {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val bounds = wm.currentWindowMetrics.bounds
                if (bounds.width() > 0) return bounds.width()
            } catch (_: Exception) {
                // Ignorar y usar displayMetrics
            }
        }
        return resources.displayMetrics.widthPixels
    }

    private fun getScreenHeightPx(): Int {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val bounds = wm.currentWindowMetrics.bounds
                if (bounds.height() > 0) return bounds.height()
            } catch (_: Exception) {
                // Ignorar y usar displayMetrics
            }
        }
        return resources.displayMetrics.heightPixels
    }

    private fun isIgnoredWidgetClass(className: String): Boolean {
        return className.contains("Image", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true) ||
            className.contains("SeekBar", ignoreCase = true) ||
            className.contains("ProgressBar", ignoreCase = true) ||
            className.contains("EditText", ignoreCase = true)
    }

    // Por qué: Cuando Litho (Messenger) o ChatMessageCell (Telegram) aplana la fila del mensaje a todo el ancho
    // de la pantalla, aplica al menos 4% de margen por lado para quedar siempre por debajo del umbral de 95%.
    private fun normalizeFullWidthMessageBounds(
        rect: Rect,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ) {
        if (rect.width() >= (screenWidth * 0.94f).toInt() && rect.height() <= (screenHeight * 0.45f).toInt()) {
            val horizontalMargin = maxOf((16 * density).toInt(), (screenWidth * 0.04f).toInt())
            rect.left = (rect.left + horizontalMargin).coerceAtMost(rect.right - 24)
            rect.right = (rect.right - horizontalMargin).coerceAtLeast(rect.left + 24)
        }
    }

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
        connectCount++
        updateRuntimeTrace("conn")

        // Register the state-change callback so FloatingBubbleService can drive us
        // without importing or holding a reference to this service.
        ServiceBridge.onTranslationStateChanged = { isActive ->
            stateChangeCount++
            if (!isActive) {
                mainHandler.post {
                    overlayManager.removeAllOverlays()
                }
                typingJob?.cancel()
                scanDebounceJob?.cancel()
                scrollSettleJob?.cancel()
                activeScanLoopJob?.cancel()
                isScreenScrolling = false
                updateRuntimeTrace("off")
            } else {
                val latestPrefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
                enabledTargetPackages = TargetAppCatalog.getEnabledPackages(latestPrefs, packageName)
                syncAccessibilityPackageScope()
                startActiveScanning()
                updateRuntimeTrace("on")
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
                var activeTarget = resolveForegroundTargetPackage(captureExternalSnapshot = true)
                if (activeTarget == null) {
                    // Por qué: Al pulsar la acción desde la barra de notificaciones, SystemUI tarda ~300ms en cerrarse
                    delay(350L)
                    activeTarget = resolveForegroundTargetPackage(captureExternalSnapshot = true)
                }
                handleForegroundTargetTransition(activeTarget)
                updateRuntimeTrace("scanReq")
                if (activeTarget == null) {
                    ServiceBridge.lastScanDebug = "abort=scanReq_no_fg | $lastResolveReason | $lastExternalWindowsSummary"
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

        // Por qué: Si la traducción ya se activó desde la interfaz o notificación antes de conectar el servicio,
        // reanuda el bucle de escaneo inmediatamente sin exigir reiniciar al usuario.
        if (ServiceBridge.isTranslatingActive) {
            startActiveScanning()
        }
        updateRuntimeTrace("ready")

        Log.d("Translator", "TranslationAccessibilityService connected")
    }

    private fun syncAccessibilityPackageScope() {
        try {
            val currentInfo = serviceInfo
            val info = currentInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            val scope = enabledTargetPackages
            // Por qué: Cuando hay apps habilitadas dejamos packageNames en null a nivel de sistema para recibir
            // transiciones de ventana (lanzador, teclado, Messenger/Litho) sin bloqueos de caché de OEM,
            // aplicando el filtrado estricto de paquetes en isEnabledTargetPackage().
            val desiredPackages: Array<String>? = if (scope.isEmpty()) {
                arrayOf("${applicationContext.packageName}.none")
            } else {
                null
            }
            val desiredFlags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.DEFAULT or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            val desiredEventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            val desiredFeedback = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC

            val packagesMatch = (info.packageNames == null && desiredPackages == null) ||
                (info.packageNames != null && desiredPackages != null && info.packageNames.contentEquals(desiredPackages))
            // Por qué: Solo actualiza serviceInfo cuando algún parámetro cambia para no reiniciar
            // innecesariamente los enlaces de ventanas interactivas en Android/One UI.
            if (currentInfo == null || !packagesMatch || info.flags != desiredFlags || info.eventTypes != desiredEventTypes || info.feedbackType != desiredFeedback) {
                info.packageNames = desiredPackages
                info.flags = desiredFlags
                info.eventTypes = desiredEventTypes
                info.feedbackType = desiredFeedback
                serviceInfo = info
            }
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
        if (activeTargetPkg == null || cachedTargetRootPkg != activeTargetPkg) {
            cachedTargetRootPkg = null
            cachedTargetRootNode = null
            lastPartialTargetRootPkg = null
            lastPartialTargetRootNode = null
        }
        pendingScanWindowStartMs = 0L
        pendingScanEventSource = null
        scrollSettleJob?.cancel()
        isScreenScrolling = false
        lastScrollPositionSig = null
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

    private fun toWindowRect(rect: Rect): WindowRect {
        return WindowRect(rect.left, rect.top, rect.right, rect.bottom)
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun safeRootInActiveWindow(): AccessibilityNodeInfo? {
        try {
            val direct = rootInActiveWindow
            if (direct != null) return direct
            // Por qué: En árboles Litho muy profundos (Messenger), el prefetch híbrido por defecto puede fallar
            // o agotar el límite Binder; pedir el nodo raíz sin prefetch (0) o ininterrumpible evita el bloqueo.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val noPrefetch = getRootInActiveWindow(0)
                if (noPrefetch != null) return noPrefetch
                val uninterruptible = getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE)
                if (uninterruptible != null) return uninterruptible
            }
        } catch (_: Exception) {
            // Ignore
        }
        return null
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun safeWindowRoot(window: AccessibilityWindowInfo?): AccessibilityNodeInfo? {
        if (window == null) return null
        try {
            val direct = window.root
            if (direct != null) return direct
            // Por qué: Algunas ventanas complejas devuelven null en window.root con prefetch híbrido;
            // reintentar con estrategia 0 (solo nodo raíz) e ininterrumpible recupera la jerarquía sin exceder 1MB IPC.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val noPrefetch = window.getRoot(0)
                if (noPrefetch != null) return noPrefetch
                val uninterruptible = window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE)
                if (uninterruptible != null) return uninterruptible
            }
        } catch (_: Exception) {
            // Ignore
        }
        return null
    }

    // Por qué: Determina de forma estricta qué aplicación habilitada está en primer plano,
    // descartando lanzadores, teclados, tiras Edge delgadas y apps bloqueadas sin perder trazabilidad.
    private fun resolveForegroundTargetPackage(captureExternalSnapshot: Boolean = false): String? {
        var activePkg: String? = null
        var activeBounds: WindowRect? = null
        try {
            val active = safeRootInActiveWindow()
            if (active != null) {
                activePkg = active.packageName?.toString()
                val rect = Rect()
                active.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    activeBounds = toWindowRect(rect)
                }
            }
        } catch (_: Exception) {
            // Ignore
        }

        val appSnapshots = mutableListOf<AppWindowSnapshot>()
        val rawWinDiag = if (captureExternalSnapshot) mutableListOf<String>() else null
        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    val wRoot = safeWindowRoot(window)
                    val wPkg = wRoot?.packageName?.toString() ?: ""
                    val wRect = Rect()
                    window.getBoundsInScreen(wRect)
                    if (wRect.isEmpty && wRoot != null) {
                        try {
                            wRoot.getBoundsInScreen(wRect)
                        } catch (_: Exception) {
                            // Ignore
                        }
                    }
                    if (rawWinDiag != null && rawWinDiag.size < 6) {
                        val flags = buildString {
                            if (window.isActive) append("A")
                            if (window.isFocused) append("F")
                        }.ifEmpty { "-" }
                        rawWinDiag.add("${wPkg.ifBlank { "null" }}:t${window.type}@${window.layer}($flags,${wRect.width()}x${wRect.height()})")
                    }
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        appSnapshots.add(
                            AppWindowSnapshot(
                                packageName = wPkg.ifBlank { null },
                                isActive = window.isActive,
                                isFocused = window.isFocused,
                                layer = window.layer,
                                bounds = if (wRect.isEmpty) WindowRect(0, 0, 0, 0) else toWindowRect(wRect)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }

        val screenWidth = getScreenWidthPx()
        val screenHeight = getScreenHeightPx()
        val now = android.os.SystemClock.uptimeMillis()
        val eventAgeMs = if (lastTargetEventTimeMs > 0L) now - lastTargetEventTimeMs else Long.MAX_VALUE

        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = activePkg,
            activeWindowBounds = activeBounds,
            appWindows = appSnapshots,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            recentEventPkg = lastTargetEventPkg,
            recentEventAgeMs = eventAgeMs,
            enabledTargetPackages = enabledTargetPackages,
            selfPackage = applicationContext.packageName
        )

        lastResolveReason = result.reason

        // Por qué: Conserva la instantánea de ventanas mientras hay una app externa visible para que,
        // al regresar a OverChat y abrir Debug, no se pierda el diagnóstico de Messenger.
        if (captureExternalSnapshot) {
            val isOnlySelfUi = (activePkg == applicationContext.packageName || activePkg == null) &&
                appSnapshots.isNotEmpty() &&
                appSnapshots.all {
                    it.packageName != null && (
                        it.packageName == applicationContext.packageName ||
                            ForegroundTargetResolver.isSystemTransientPackage(it.packageName)
                        )
                }
            val hasNonTrivialSnapshot = (activePkg != null && activePkg != applicationContext.packageName) ||
                (!isOnlySelfUi && rawWinDiag?.isNotEmpty() == true)
            if (hasNonTrivialSnapshot) {
                lastExternalWindowsSummary = "act=${activePkg ?: "null"} wins=[${rawWinDiag?.joinToString(",") ?: ""}]"
            }
        }

        return result.targetPackage
    }

    // Por qué: Centraliza la comprobación de reposo de desplazamiento y libera bloqueos expirados (> 450ms).
    private fun isScrollQuieted(nowMs: Long = android.os.SystemClock.uptimeMillis()): Boolean {
        if (isScreenScrolling && (nowMs - lastScrollTimestamp >= 450L)) {
            isScreenScrolling = false
        }
        return !isScreenScrolling && (nowMs - lastScrollTimestamp >= 350L)
    }

    private fun evaluateVerticalScrollEvent(event: AccessibilityEvent, sourceNode: AccessibilityNodeInfo?): Boolean {
        val sourceHeight = if (sourceNode != null) {
            val rect = Rect()
            try {
                sourceNode.getBoundsInScreen(rect)
                rect.height()
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
        val hasDeltaApi = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
        val dx = if (hasDeltaApi) event.scrollDeltaX else 0
        val dy = if (hasDeltaApi) event.scrollDeltaY else 0
        val decision = ScrollEventClassifier.classify(
            hasDeltaApi = hasDeltaApi,
            scrollDeltaX = dx,
            scrollDeltaY = dy,
            sourceHeightPx = sourceHeight,
            screenHeightPx = getScreenHeightPx(),
            windowId = event.windowId,
            scrollY = event.scrollY,
            maxScrollY = if (hasDeltaApi) event.maxScrollY else -1,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount,
            prevSignature = lastScrollPositionSig
        )
        lastScrollPositionSig = decision.nextSignature
        lastScrollDiag = decision.reason
        if (decision.isRelevantVerticalScroll) {
            acceptedScrollCount++
        } else {
            ignoredScrollCount++
        }
        return decision.isRelevantVerticalScroll
    }

    private fun isTargetAppInForeground(): Boolean = resolveForegroundTargetPackage() != null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""
        val nowMs = android.os.SystemClock.uptimeMillis()
        eventCount++
        lastEventPkg = packageName
        lastEventType = event.eventType
        lastEventTimeMs = nowMs

        if (packageName.isNotBlank() &&
            !ForegroundTargetResolver.isSystemTransientPackage(packageName) &&
            (packageName != applicationContext.packageName || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ) {
            recordSeenExternalPackage(packageName)
            if (isEnabledTargetPackage(packageName)) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
                ) {
                    lastTargetEventPkg = packageName
                    lastTargetEventTimeMs = nowMs
                }
            } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastTargetEventPkg = null
                lastTargetEventTimeMs = 0L
            }
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
            ) {
                lastNonTransientEventPkg = packageName
                lastNonTransientEventTimeMs = nowMs
            }
        }

        val isTargetEvt = isEnabledTargetPackage(packageName)
        var capturedSourceNode: AccessibilityNodeInfo? = null
        if (isTargetEvt && (ServiceBridge.isTranslatingActive || ServiceBridge.isBubbleRunning)) {
            if (lastDiagTargetPkg != packageName) {
                lastDiagTargetPkg = packageName
                targetSrcNonNullCount = 0
                targetSrcNullCount = 0
                lastRootExtractDiag = "init"
            }
            capturedSourceNode = event.source
            // Por qué: Si aún no tenemos root en caché, capturamos incluso mientras solo la burbuja está activa
            // para aprovechar el evento inicial TYPE_WINDOW_STATE_CHANGED antes de que el usuario pulse Start.
            val minExtractIntervalMs = when {
                cachedTargetRootPkg != packageName -> 0L
                cachedTargetRootNode == null -> 100L
                else -> 250L
            }
            if (capturedSourceNode != null) {
                targetSrcNonNullCount++
                if (nowMs - lastRootExtractAttemptMs >= minExtractIntervalMs) {
                    lastRootExtractAttemptMs = nowMs
                    capturedSourceNode = extractTargetRootFromEventSource(capturedSourceNode, packageName, allowPartial = true)
                        ?: capturedSourceNode
                }
            } else {
                targetSrcNullCount++
                if (cachedTargetRootNode == null && lastPartialTargetRootNode == null && lastRootExtractDiag == "init") {
                    lastRootExtractDiag = "srcNull(t=${event.eventType})"
                }
            }
        }

        if (!ServiceBridge.isTranslatingActive) {
            maybeUpdateRuntimeTrace(throttled = true)
            return
        }

        val shouldCaptureSnapshot = isTargetEvt || (nowMs - lastTraceUpdateMs >= 450L)
        val activeForeground = resolveForegroundTargetPackage(captureExternalSnapshot = shouldCaptureSnapshot)
        handleForegroundTargetTransition(activeForeground)
        maybeUpdateRuntimeTrace(throttled = !isTargetEvt)

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

        if (activeForeground == null) {
            ServiceBridge.lastScanDebug = "abort=evt_target_no_fg | pkg=$packageName | evt=${event.eventType} | $lastResolveReason | $lastExternalWindowsSummary"
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val settleSource = capturedSourceNode ?: event.source
                if (!evaluateVerticalScrollEvent(event, settleSource)) {
                    return
                }
                scanGeneration.incrementAndGet()
                lastScrollTimestamp = android.os.SystemClock.uptimeMillis()
                isScreenScrolling = true
                pendingScanWindowStartMs = 0L
                if (settleSource != null) {
                    pendingScanEventSource = settleSource
                }
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
                    // Por qué: Espera 360ms (> 350ms con margen contra jitter) tras finalizar el desplazamiento
                    // antes de traducir, garantizando que la pantalla esté 100% estable.
                    delay(360L)
                    isScreenScrolling = false
                    val sourceForSettle = settleSource ?: pendingScanEventSource
                    pendingScanEventSource = null
                    if (isTargetAppInForeground() && ServiceBridge.isTranslatingActive) {
                        scanAndTranslateVisibleMessages(sourceForSettle)
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
                if (isScrollQuieted(now)) {
                    scheduleScan(360L, capturedSourceNode ?: event.source)
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
    @Volatile private var pendingScanEventSource: AccessibilityNodeInfo? = null
    // Por qué: Aplica un retraso controlado para no saturar con cancelaciones constantes en cada fotograma de desplazamiento
    private fun scheduleScan(delayMs: Long, eventSource: AccessibilityNodeInfo? = null) {
        if (!ServiceBridge.isTranslatingActive) return
        if (!isTargetAppInForeground()) {
            if (isEnabledTargetPackage(lastEventPkg)) {
                ServiceBridge.lastScanDebug = "abort=sched_no_fg | pkg=$lastEventPkg | $lastResolveReason | $lastExternalWindowsSummary"
            }
            if (overlayManager.hasOverlays()) {
                mainHandler.post { overlayManager.removeAllOverlays() }
            }
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (!isScrollQuieted(now)) {
            return
        }
        if (eventSource != null) {
            pendingScanEventSource = eventSource
        }
        if (pendingScanWindowStartMs == 0L || scanDebounceJob?.isActive != true) {
            pendingScanWindowStartMs = now
        }
        val elapsedSinceWindowStart = now - pendingScanWindowStartMs
        // Por qué: Si una app como Messenger emite cambios continuos sin pausa de 360ms, limitar el aplazamiento a 750ms
        // evita postergar el escaneo indefinidamente por cancelaciones consecutivas del debounce.
        if (scanDebounceJob?.isActive == true && elapsedSinceWindowStart >= 750L) {
            return
        }
        scanDebounceJob?.cancel()
        val effectiveDelayMs = minOf(delayMs, (750L - elapsedSinceWindowStart).coerceAtLeast(80L))
        scanDebounceJob = serviceScope.launch {
            delay(effectiveDelayMs)
            pendingScanWindowStartMs = 0L
            val sourceForScan = pendingScanEventSource ?: eventSource
            pendingScanEventSource = null
            val nowAfterDelay = android.os.SystemClock.uptimeMillis()
            if (ServiceBridge.isTranslatingActive && isTargetAppInForeground() && isScrollQuieted(nowAfterDelay)) {
                scanAndTranslateVisibleMessages(sourceForScan)
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
                // Por qué: Autorrepara el bucle de escaneo periódico si la traducción está activa pero el trabajo no corre.
                if (ServiceBridge.isTranslatingActive && (activeScanLoopJob == null || activeScanLoopJob?.isActive != true)) {
                    startActiveScanning()
                }
                val activeTarget = resolveForegroundTargetPackage(captureExternalSnapshot = false)
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
            scanLoopTicks++
            val initFg = resolveForegroundTargetPackage(captureExternalSnapshot = true)
            updateRuntimeTrace("loopInit")
            val nowInit = android.os.SystemClock.uptimeMillis()
            if (initFg != null && isScrollQuieted(nowInit)) {
                scanAndTranslateVisibleMessages()
            } else if (initFg == null && isEnabledTargetPackage(lastTargetEventPkg)) {
                ServiceBridge.lastScanDebug = "abort=loopInit_no_fg | $lastResolveReason | $lastExternalWindowsSummary"
            }

            while (ServiceBridge.isTranslatingActive) {
                delay(2000L)
                if (!ServiceBridge.isTranslatingActive) break
                scanLoopTicks++
                val loopFg = resolveForegroundTargetPackage(captureExternalSnapshot = true)
                updateRuntimeTrace("loop")
                val nowLoop = android.os.SystemClock.uptimeMillis()
                if (loopFg != null && isScrollQuieted(nowLoop)) {
                    scanAndTranslateVisibleMessages()
                } else {
                    if (loopFg != null && ServiceBridge.lastScanDebug == "No scan executed yet.") {
                        ServiceBridge.lastScanDebug = "abort=loop_scrolling($acceptedScrollCount/$ignoredScrollCount,$lastScrollDiag,age=${nowLoop - lastScrollTimestamp}) | fg=$loopFg"
                    } else if (loopFg == null && isEnabledTargetPackage(lastTargetEventPkg)) {
                        ServiceBridge.lastScanDebug = "abort=loop_no_fg | $lastResolveReason | $lastExternalWindowsSummary"
                    }
                    if (loopFg == null && overlayManager.hasOverlays()) {
                        mainHandler.post { overlayManager.removeAllOverlays() }
                    }
                }
            }
        }
    }

    private fun extractTargetRootFromEventSource(
        source: AccessibilityNodeInfo?,
        expectedPkg: String,
        allowPartial: Boolean
    ): AccessibilityNodeInfo? {
        if (source == null) return null
        val minFullHeight = (getScreenHeightPx() / 4).coerceAtLeast(200)
        try {
            val winRoot = safeWindowRoot(source.window)
            if (winRoot != null) {
                val winPkg = winRoot.packageName?.toString()
                if (winPkg == expectedPkg) {
                    val rect = Rect()
                    winRoot.getBoundsInScreen(rect)
                    if (rect.height() >= minFullHeight) {
                        cachedTargetRootPkg = expectedPkg
                        cachedTargetRootNode = winRoot
                        lastRootOrigin = "evtWin:${rect.height()}"
                        lastRootExtractDiag = "winOk:${rect.height()}"
                        return winRoot
                    } else if (rect.height() >= 150) {
                        lastPartialTargetRootPkg = expectedPkg
                        lastPartialTargetRootNode = winRoot
                        lastRootExtractDiag = "winPart:${rect.height()}"
                        if (allowPartial) {
                            lastRootOrigin = "evtWinPart:${rect.height()}"
                            return winRoot
                        }
                    } else {
                        lastRootExtractDiag = "winSmall:${rect.height()}"
                    }
                } else {
                    lastRootExtractDiag = "winPkg:${winPkg ?: "null"}"
                }
            }
        } catch (_: Exception) {
            // Ignora ventana del nodo fuente ya reciclada
        }

        try {
            var current: AccessibilityNodeInfo = source
            var topTargetNode: AccessibilityNodeInfo? = if (current.packageName?.toString() == expectedPkg) current else null
            var hops = 0
            var stoppedReason = "cap120"
            // Por qué: Jerarquías Litho/React/Compose profundas (como Messenger) superan 40 niveles;
            // subir hasta 120 ancestros evita quedar atrapados en un subnodo pequeño cuando window.root es null.
            while (hops < 120) {
                val parent = current.parent
                if (parent == null) {
                    stoppedReason = "top"
                    break
                }
                hops++
                current = parent
                if (current.packageName?.toString() == expectedPkg) {
                    topTargetNode = current
                }
            }
            val candidate = topTargetNode ?: current
            val candPkg = candidate.packageName?.toString()
            if (candPkg == expectedPkg) {
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                val cls = candidate.className?.toString()?.substringAfterLast('.') ?: "?"
                if (bounds.height() >= minFullHeight) {
                    cachedTargetRootPkg = expectedPkg
                    cachedTargetRootNode = candidate
                    lastRootOrigin = "evtTree:${bounds.height()}(h=$hops)"
                    lastRootExtractDiag = "treeOk:${bounds.height()}(h=$hops,$stoppedReason)"
                    return candidate
                }
                if (bounds.height() >= 150) {
                    // Por qué: Conserva subárboles útiles (>=150px) aunque sean menores a un cuarto de pantalla
                    // para que el bucle periódico tenga un contenedor traducible si window.root sigue siendo null.
                    lastPartialTargetRootPkg = expectedPkg
                    lastPartialTargetRootNode = candidate
                    lastRootExtractDiag = "part:${bounds.height()}(h=$hops,$cls,$stoppedReason)"
                    if (allowPartial) {
                        lastRootOrigin = "evtPartial:${bounds.height()}(h=$hops)"
                        return candidate
                    }
                } else {
                    lastRootExtractDiag = "small:${bounds.height()}x${bounds.width()}(h=$hops,$cls,$stoppedReason)"
                }
            } else {
                lastRootExtractDiag = "diffPkg:${candPkg ?: "null"}(h=$hops,$stoppedReason)"
            }
        } catch (e: Exception) {
            lastRootExtractDiag = "exc:${e.javaClass.simpleName}"
        }
        return null
    }

    private fun findActiveTargetRootNode(eventSource: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val activePkg = resolveForegroundTargetPackage() ?: return null
        handleForegroundTargetTransition(activePkg)

        // Por qué: Prioriza siempre la raíz real de la ventana (rootInActiveWindow / windows) sobre eventSource,
        // ya que en frameworks como Litho (Messenger) eventSource.parent puede detenerse en un subnodo parcial.
        try {
            val active = safeRootInActiveWindow()
            if (active != null && active.packageName?.toString() == activePkg) {
                cachedTargetRootPkg = activePkg
                cachedTargetRootNode = active
                lastRootOrigin = "actWin"
                lastRootExtractDiag = "actWin"
                return active
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying rootInActiveWindow", e)
        }

        try {
            val windowList = windows
            if (windowList != null) {
                val sortedWindows = windowList
                    .asSequence()
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedWith(
                        compareByDescending<AccessibilityWindowInfo> { it.layer }
                            .thenByDescending { it.isActive || it.isFocused }
                    )
                for (window in sortedWindows) {
                    val root = safeWindowRoot(window) ?: continue
                    if (root.packageName?.toString() == activePkg) {
                        cachedTargetRootPkg = activePkg
                        cachedTargetRootNode = root
                        lastRootOrigin = "win@${window.layer}"
                        lastRootExtractDiag = "win@${window.layer}"
                        return root
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Translator", "Error querying windows in accessibility service", e)
        }

        val fromEvent = extractTargetRootFromEventSource(eventSource, activePkg, allowPartial = false)
        if (fromEvent != null) {
            return fromEvent
        }

        val minFullHeight = (getScreenHeightPx() / 4).coerceAtLeast(200)
        // Por qué: En escaneos periódicos sin eventSource (bucle de 2s) cuando window.root es null en One UI,
        // reutiliza la última raíz válida de Messenger tras refrescarla contra el árbol vivo.
        val cached = cachedTargetRootNode
        var cacheStateDiag = if (cached != null) "valid" else "null"
        if (cached != null && cachedTargetRootPkg == activePkg) {
            try {
                if (cached.refresh() && cached.packageName?.toString() == activePkg) {
                    val rect = Rect()
                    cached.getBoundsInScreen(rect)
                    if (rect.height() >= minFullHeight) {
                        lastRootOrigin = "cache:${rect.height()}"
                        return cached
                    }
                    cacheStateDiag = "h${rect.height()}"
                } else {
                    cachedTargetRootPkg = null
                    cachedTargetRootNode = null
                    cacheStateDiag = "stale"
                }
            } catch (_: Exception) {
                cachedTargetRootPkg = null
                cachedTargetRootNode = null
                cacheStateDiag = "exc"
            }
        }

        val partialFallback = extractTargetRootFromEventSource(eventSource, activePkg, allowPartial = true)
        if (partialFallback != null) {
            return partialFallback
        }

        val cachedPartial = lastPartialTargetRootNode
        if (cachedPartial != null && lastPartialTargetRootPkg == activePkg) {
            try {
                if (cachedPartial.refresh() && cachedPartial.packageName?.toString() == activePkg) {
                    val rect = Rect()
                    cachedPartial.getBoundsInScreen(rect)
                    if (rect.height() >= 150) {
                        lastRootOrigin = "cachePart:${rect.height()}"
                        return cachedPartial
                    }
                } else {
                    lastPartialTargetRootPkg = null
                    lastPartialTargetRootNode = null
                }
            } catch (_: Exception) {
                lastPartialTargetRootPkg = null
                lastPartialTargetRootNode = null
            }
        }

        // Por qué: Último respaldo vía foco activo de entrada o accesibilidad; si Messenger expone
        // algún subnodo enfocado aunque window.root sea null, podemos escalar desde él al contenedor del chat.
        val focusInput = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        val focusInputPkg = try { focusInput?.packageName?.toString() } catch (_: Exception) { null }
        if (focusInput != null && (focusInputPkg == activePkg || focusInputPkg == null)) {
            extractTargetRootFromEventSource(focusInput, activePkg, allowPartial = true)?.let { return it }
        }
        val focusA11y = try { findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) } catch (_: Exception) { null }
        val focusA11yPkg = try { focusA11y?.packageName?.toString() } catch (_: Exception) { null }
        if (focusA11y != null && (focusA11yPkg == activePkg || focusA11yPkg == null)) {
            extractTargetRootFromEventSource(focusA11y, activePkg, allowPartial = true)?.let { return it }
        }

        val focusDiag = "fI=${focusInput != null}(${focusInputPkg ?: "-"}),fA=${focusA11y != null}(${focusA11yPkg ?: "-"})"
        lastRootOrigin = "null(evt=${eventSource != null},cache=$cacheStateDiag,part=${cachedPartial != null},src=$targetSrcNonNullCount/$targetSrcNullCount,$focusDiag,d=$lastRootExtractDiag)"
        return null
    }

    // Called on serviceScope background coroutine
    private fun scanAndTranslateVisibleMessages(eventSource: AccessibilityNodeInfo? = null): Int {
        if (!ServiceBridge.isTranslatingActive) {
            return 0
        }

        // Por qué: Valida que la pantalla esté completamente quieta y no en pleno desplazamiento antes de escanear
        if (!isScrollQuieted()) {
            return 0
        }

        // Por qué: Verifica estrictamente que una app habilitada esté en primer plano antes de iniciar cualquier escaneo
        val fgPkg = resolveForegroundTargetPackage(captureExternalSnapshot = true)
        if (fgPkg == null) {
            val activePkg = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            ServiceBridge.lastScanDebug = "abort=no_fg_target | activeWin=${activePkg ?: "null"} | $lastResolveReason | $lastExternalWindowsSummary"
            mainHandler.post {
                overlayManager.removeAllOverlays()
            }
            return 0
        }

        val rootNode = findActiveTargetRootNode(eventSource)
        if (rootNode == null) {
            ServiceBridge.lastScanDebug = "abort=null_root($lastRootOrigin) | fg=$fgPkg | $lastResolveReason | $lastExternalWindowsSummary"
            mainHandler.post {
                overlayManager.removeAllOverlays()
            }
            return 0
        }
        val activeProfile = TargetAppCatalog.profileForPackage(rootNode.packageName?.toString())
        val density = resources.displayMetrics.density
        val screenWidth = getScreenWidthPx()
        val screenHeight = getScreenHeightPx()

        // When the soft keyboard is open, hide chat message bubbles so the screen is not overflooded.
        // When the soft keyboard is closed, still evaluate the input bar in case a draft is present.
        if (isKeyboardVisible(preferredRoot = rootNode)) {
            val edit = findCurrentEditableNode(preferredRoot = rootNode)
            val editRect = Rect().also { edit?.getBoundsInScreen(it) }
            ServiceBridge.lastScanDebug = "abort=kb_visible | fg=$fgPkg (${activeProfile.id}) | scr=${screenWidth}x$screenHeight | editRect=${editRect.flattenToString()}"
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
        val topInset = (activeProfile.topInsetDp * density).toInt()
        val bottomInset = (activeProfile.bottomInsetDp * density).toInt()

        // When keyboard is closed, scan all messages up to the top of the input bar (with 5dp tolerance)
        val inputNode = findCurrentEditableNode(preferredRoot = rootNode)
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
        collectMessageCandidates(
            node = rootNode,
            outList = candidates,
            minY = topInset,
            maxY = effectiveBottom,
            configuredSource = configuredSource,
            configuredTarget = configuredTarget,
            profile = activeProfile,
            stats = stats
        )

        val dedupedCandidates = filterOverlappingCandidates(candidates)
        val baseDiag = "fg=$fgPkg(${activeProfile.id}) root=$lastRootOrigin src=$configuredSource->$configuredTarget scr=${screenWidth}x$screenHeight d=${"%.2f".format(density)} y=$topInset..$effectiveBottom " +
            "vis=${stats.visitedNodes} ed=${stats.editableNodes} t=${stats.nodesWithText} dsc=${stats.nodesWithDesc} " +
            "skW=${stats.skippedWidget} skF=${stats.skippedFilter} skTiny=${stats.skippedTinyDesc} skBnd=${stats.skippedBubbleBounds} " +
            "cands=${candidates.size}/${dedupedCandidates.size} smp=${stats.samples.joinToString(";")}"

        if (dedupedCandidates.isEmpty()) {
            ServiceBridge.lastScanDebug = "$baseDiag | out=empty"
            mainHandler.post {
                overlayManager.removeAllMessageOverlays()
            }
            return 0
        }
        ServiceBridge.lastScanDebug = "$baseDiag | out=translating(${dedupedCandidates.size})"

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
                        ServiceBridge.lastScanDebug = "${ServiceBridge.lastScanDebug} | mlkit=ineligible(det=$detectedLang,len=${cleanText.length})"
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
                                ServiceBridge.lastScanDebug = "${ServiceBridge.lastScanDebug} | overlay=ok(rect=${rect.left},${rect.top}-${rect.right},${rect.bottom})"
                            }
                        }
                    } else {
                        ServiceBridge.lastScanDebug = "${ServiceBridge.lastScanDebug} | mlkit=null_output"
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
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        stats: ScanStats? = null
    ): Int {
        stats?.let { it.visitedNodes++ }
        // Por qué: Ignora inmediatamente nodos fuera de las apps habilitadas para no leer pantallas ajenas
        val nodePkg = node.packageName?.toString() ?: ""
        if (nodePkg.isNotBlank() && !isEnabledTargetPackage(nodePkg)) {
            return 0
        }

        if (node.isEditable) {
            stats?.let { it.editableNodes++ }
            // Skip input fields during message scan (handled by handleOutgoingTyping)
            return 0
        }

        var maxChildTextLen = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childLen = collectMessageCandidates(
                child,
                outList,
                minY,
                maxY,
                configuredSource,
                configuredTarget,
                requireTranslatable,
                profile,
                stats
            )
            if (childLen > maxChildTextLen) {
                maxChildTextLen = childLen
            }
        }

        // Por qué: Descarta controles no textuales e imágenes sin bloquear burbujas clicables de Litho (Messenger/Instagram)
        // que exponen AccessibilityRole.BUTTON ("android.widget.Button").
        val className = node.className?.toString() ?: ""
        if (isIgnoredWidgetClass(className)) {
            stats?.let { it.skippedWidget++ }
            return maxChildTextLen
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
        val descText = if (!isScrollableContainer) {
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (directText != null) stats?.let { it.nodesWithText++ }
        if (descText != null) stats?.let { it.nodesWithDesc++ }

        // Por qué: Si los hijos ya aportaron el texto del mensaje, evita duplicar el nodo padre salvo que este
        // contenga una descripción mucho más larga (ej. burbuja Litho cuya cita/reacción era un subnodo menor).
        if (maxChildTextLen > 0) {
            val outerLen = (directText ?: descText)?.length ?: 0
            if (isScrollableContainer || node.childCount > 6 || outerLen <= maxChildTextLen + 12) {
                return maxChildTextLen
            }
        }

        val rawText = directText ?: descText
        if (rawText.isNullOrBlank()) {
            return maxChildTextLen
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val screenWidth = getScreenWidthPx()
        val screenHeight = getScreenHeightPx()
        val density = resources.displayMetrics.density
        val shortClass = className.substringAfterLast('.')

        val cleanText = TranslationFilter.cleanMessageText(rawText)
        val passesLanguageFilter = !requireTranslatable || TranslationFilter.shouldTranslate(cleanText, configuredTarget, configuredSource)
        if (cleanText.isBlank() || !passesLanguageFilter) {
            stats?.let {
                it.skippedFilter++
                it.addSample("$shortClass(t=${directText?.length ?: 0},d=${descText?.length ?: 0},[${rect.left},${rect.top}-${rect.right},${rect.bottom}],flt)")
            }
            return maxChildTextLen
        }

        // Por qué: Si el texto provino de contentDescription, descarta únicamente micro-iconos sin perder burbujas cortas
        if (directText == null && rect.width() < (22 * density).toInt() && rect.height() < (18 * density).toInt()) {
            stats?.let {
                it.skippedTinyDesc++
                it.addSample("$shortClass(d=${descText?.length ?: 0},[${rect.left},${rect.top}-${rect.right},${rect.bottom}],tiny)")
            }
            return maxChildTextLen
        }

        // Por qué: Ignora nodos fuera del área horizontal visible (p. ej. pestañas ocultas en ViewPager).
        if (rect.right <= 0 || rect.left >= screenWidth) {
            stats?.let { it.skippedBubbleBounds++ }
            return maxChildTextLen
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

        normalizeFullWidthMessageBounds(rect, screenWidth, screenHeight, density)

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
            stats?.let {
                it.passedCandidates++
                it.addSample("$shortClass(t=${directText?.length ?: 0},d=${descText?.length ?: 0},[${clampedRect.left},${clampedRect.top}-${clampedRect.right},${clampedRect.bottom}],ok)")
            }
            return maxOf(maxChildTextLen, rawText.length)
        }

        stats?.let {
            it.skippedBubbleBounds++
            it.addSample("$shortClass(b=$isBubble,[${rect.left},${rect.top}-${rect.right},${rect.bottom}],bnd)")
        }
        return maxChildTextLen
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

    // Por qué: Verifica la elevación del campo de composición inferior visible sobre la barra de navegación
    // para confirmar si el teclado está abierto, ignorando campos de búsqueda o vistas ocultas fuera de pantalla.
    private fun isKeyboardVisible(
        candidateNode: AccessibilityNodeInfo? = null,
        preferredRoot: AccessibilityNodeInfo? = null
    ): Boolean {
        val screenHeight = getScreenHeightPx()
        val screenWidth = getScreenWidthPx()
        val density = resources.displayMetrics.density

        // 1. Verificación secundaria de ventana IME del sistema cuando esté disponible
        try {
            val windowList = windows
            if (windowList != null) {
                for (w in windowList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val rect = Rect()
                        w.getBoundsInScreen(rect)
                        if (rect.top in 1 until (screenHeight - (95 * density).toInt()) &&
                            rect.height() > (120 * density).toInt()) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar
        }

        // 2. Verificación por posición vertical del compositor inferior visible dentro de la pantalla
        try {
            val input = candidateNode ?: findCurrentEditableNode(preferredRoot)
            if (input != null && isEditableNodeOnScreen(input, screenWidth, screenHeight)) {
                val inputRect = Rect()
                input.getBoundsInScreen(inputRect)
                val bottomOffset = screenHeight - inputRect.bottom
                // Por qué: Un buscador superior oculto en otro fragmento no es el compositor del chat;
                // solo evaluamos nodos editables en la mitad inferior o enfocados activamente.
                val isLowerHalfOrFocused = input.isFocused || inputRect.top >= (screenHeight * 0.35f).toInt()
                if (isLowerHalfOrFocused) {
                    if (bottomOffset <= (110 * density).toInt()) {
                        return false
                    }
                    if (bottomOffset > (120 * density).toInt()) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar
        }

        return false
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

    private var lastInsertedText: String? = null
    private var dismissedDraftText: String? = null
    private var lastRenderedDraftSource: String? = null
    private var lastRenderedDraftTop: Int = 0
    private var lastRenderedDraftManualLang: String? = null

    private fun findCurrentEditableNode(preferredRoot: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val screenWidth = getScreenWidthPx()
        val screenHeight = getScreenHeightPx()

        try {
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusedPkg = focused?.packageName?.toString()
            if (focused != null && isEnabledTargetPackage(focusedPkg) && isEditableNodeOnScreen(focused, screenWidth, screenHeight)) {
                return focused
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val activeRoot = rootInActiveWindow
            val focused = activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusedPkg = focused?.packageName?.toString()
            if (focused != null && isEnabledTargetPackage(focusedPkg) && isEditableNodeOnScreen(focused, screenWidth, screenHeight)) {
                return focused
            }
        } catch (e: Exception) {
            // Ignore
        }

        val root = preferredRoot ?: findActiveTargetRootNode() ?: return null
        return findBottomMostVisibleEditableChild(root, screenWidth, screenHeight)
    }

    // Por qué: En apps con múltiples fragmentos en memoria (como Messenger), selecciona el campo editable visible
    // más cercano al borde inferior (barra de mensaje) y descarta buscadores superiores o pestañas ocultas.
    private fun findBottomMostVisibleEditableChild(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE

        fun walk(current: AccessibilityNodeInfo) {
            if (current.isEditable) {
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
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                walk(child)
            }
        }

        walk(node)
        return bestNode
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
            if (!isIgnoredWidgetClass(cls)) {
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
            val screenWidth = getScreenWidthPx()
            val screenHeight = getScreenHeightPx()
            val density = resources.displayMetrics.density
            normalizeFullWidthMessageBounds(rect, screenWidth, screenHeight, density)
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
        cachedTargetRootPkg = null
        cachedTargetRootNode = null
        lastPartialTargetRootPkg = null
        lastPartialTargetRootNode = null
        pendingScanWindowStartMs = 0L
        pendingScanEventSource = null
        lastDiagTargetPkg = null
        targetSrcNonNullCount = 0
        targetSrcNullCount = 0
        lastTargetEventPkg = null
        lastTargetEventTimeMs = 0L
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
