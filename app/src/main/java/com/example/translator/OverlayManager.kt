package com.example.translator

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val bubbleIdCounter = AtomicLong(0)
    @Volatile private var overlayTheme: String = AppDefaults.OVERLAY_THEME_AUTO

    data class BubbleStyle(
        val backgroundRes: Int,
        val textColor: Int
    )

    companion object {
        private const val TEXT_COLOR_DARK_SURFACE = 0xFFE9EDEF.toInt()
        private const val TEXT_COLOR_LIGHT_SURFACE = 0xFF0F172A.toInt()

        /**
         * Resuelve el fondo y color de texto de la burbuja según el tema elegido y la app activa.
         */
        fun resolveBubbleStyle(
            overlayTheme: String,
            alignmentMode: ChatAlignmentMode,
            isRightSide: Boolean,
            isWhatsAppApp: Boolean
        ): BubbleStyle {
            val isOutgoing = alignmentMode == ChatAlignmentMode.LEFT_RIGHT_CHAT && isRightSide
            return when {
                overlayTheme == AppDefaults.OVERLAY_THEME_LIGHT -> BubbleStyle(
                    backgroundRes = if (isOutgoing) R.drawable.bg_translation_bubble_light_outgoing else R.drawable.bg_translation_bubble_light_incoming,
                    textColor = TEXT_COLOR_LIGHT_SURFACE
                )
                overlayTheme == AppDefaults.OVERLAY_THEME_WHATSAPP ||
                    (overlayTheme == AppDefaults.OVERLAY_THEME_AUTO && isWhatsAppApp) -> BubbleStyle(
                    backgroundRes = if (isOutgoing) R.drawable.bg_translation_bubble_outgoing else R.drawable.bg_translation_bubble_incoming,
                    textColor = TEXT_COLOR_DARK_SURFACE
                )
                else -> BubbleStyle(
                    backgroundRes = if (isOutgoing) R.drawable.bg_translation_bubble_neutral_outgoing else R.drawable.bg_translation_bubble_neutral_incoming,
                    textColor = TEXT_COLOR_DARK_SURFACE
                )
            }
        }
    }

    fun setOverlayTheme(theme: String) {
        overlayTheme = theme
    }

    private data class ActiveBubble(
        val view: View,
        var text: String,
        var targetRect: Rect,
        var overlayBounds: Rect
    )

    // Map of bubble ID to ActiveBubble
    private val activeBubbles = mutableMapOf<String, ActiveBubble>()

    private fun maxVerticalDriftPx(density: Float): Int = (30 * density).toInt()

    private fun addWindowViewWithFallback(
        view: View,
        params: WindowManager.LayoutParams,
        label: String
    ): Boolean {
        return try {
            params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            windowManager.addView(view, params)
            true
        } catch (e: Exception) {
            android.util.Log.w("Translator", "$label with TYPE_ACCESSIBILITY_OVERLAY failed, trying TYPE_APPLICATION_OVERLAY", e)
            try {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                windowManager.addView(view, params)
                true
            } catch (e2: Exception) {
                android.util.Log.e("Translator", "OverlayManager failed to addView for $label", e2)
                false
            }
        }
    }

    // Called on Main Thread
    @Synchronized
    fun addOrUpdateOverlay(
        rect: Rect,
        translatedText: String,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE
    ) {
        if (translatedText.isBlank()) return
        val draftBounds = currentDraftOverlayBounds
        if (draftBounds != null && Rect.intersects(draftBounds, rect)) return

        val matchedId = findMatchingBubble(rect, translatedText)
        if (matchedId != null) {
            val bubble = activeBubbles[matchedId]
            if (bubble != null) {
                if (draftBounds != null && Rect.intersects(draftBounds, bubble.overlayBounds)) return
                updateBubble(bubble, rect, translatedText, profile)
                return
            }
        }

        val view = inflater.inflate(R.layout.bubble_overlay, null)
        val (params, overlayBounds) = calculateBubbleLayout(rect, view, translatedText, profile)
        if (draftBounds != null && Rect.intersects(draftBounds, overlayBounds)) return
        val newId = "bubble_${bubbleIdCounter.incrementAndGet()}"
        addBubble(rect, translatedText, newId, view, params, overlayBounds, profile)
    }

    // Called on Main Thread
    @Synchronized
    fun updateOverlays(
        results: List<Pair<Rect?, String>>,
        isFullSync: Boolean = true,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE
    ) {
        val updatedIds = mutableSetOf<String>()

        val draftBounds = currentDraftOverlayBounds
        val acceptedBoundsInBatch = mutableListOf<Rect>()

        for (item in results) {
            val rect = item.first ?: continue
            val translatedText = item.second
            if (translatedText.isBlank()) continue

            // Por qué: No renderizar burbujas que colisionen con la pastilla de borrador de texto
            if (draftBounds != null && Rect.intersects(draftBounds, rect)) {
                continue
            }

            // Por qué: Evitar superposiciones entre burbujas calculadas en el mismo fotograma
            var overlapsBatch = false
            for (accepted in acceptedBoundsInBatch) {
                if (Rect.intersects(accepted, rect)) {
                    val intersection = Rect()
                    if (intersection.setIntersect(accepted, rect)) {
                        val intersectArea = intersection.width().toLong() * intersection.height()
                        val minArea = min(
                            accepted.width().toLong() * accepted.height(),
                            rect.width().toLong() * rect.height()
                        )
                        if (minArea > 0 && intersectArea > minArea * 0.20) {
                            overlapsBatch = true
                            break
                        }
                    }
                }
            }
            if (overlapsBatch) {
                continue
            }

            val matchedId = findMatchingBubble(rect, translatedText, updatedIds)
            if (matchedId != null) {
                // Actualizar burbuja existente
                val bubble = activeBubbles[matchedId]
                if (bubble != null) {
                    if (draftBounds != null && Rect.intersects(draftBounds, bubble.overlayBounds)) {
                        continue
                    }
                    updateBubble(bubble, rect, translatedText, profile)
                    acceptedBoundsInBatch.add(bubble.overlayBounds)
                }
                updatedIds.add(matchedId)
            } else {
                val view = inflater.inflate(R.layout.bubble_overlay, null)
                val (params, overlayBounds) = calculateBubbleLayout(rect, view, translatedText, profile)
                if (draftBounds != null && Rect.intersects(draftBounds, overlayBounds)) {
                    continue
                }
                acceptedBoundsInBatch.add(overlayBounds)
                // Por qué: Reutilizamos la vista ya inflada y las dimensiones calculadas para evitar doble inflación
                val newId = "bubble_${bubbleIdCounter.incrementAndGet()}"
                addBubble(rect, translatedText, newId, view, params, overlayBounds, profile)
                updatedIds.add(newId)
            }
        }

        // Por qué: Si es sincronización completa, eliminar overlays que ya no están visibles en pantalla.
        // Si no es sincronización completa (ej: renderizado incremental), conservar los activos no colisionantes.
        val screenHeight = ScreenMetricsHelper.currentBounds(context).heightPx
        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val shouldRemove = if (isFullSync) {
                !updatedIds.contains(entry.key)
            } else {
                entry.value.targetRect.bottom < 0 || entry.value.targetRect.top > screenHeight
            }
            if (shouldRemove) {
                try {
                    windowManager.removeView(entry.value.view)
                } catch (e: Exception) {
                    // Ignorar si la vista ya fue retirada
                }
                iterator.remove()
            }
        }
    }

    private fun findMatchingBubble(
        targetRect: Rect,
        text: String,
        claimedIds: Set<String> = emptySet()
    ): String? {
        val density = ScreenMetricsHelper.currentBounds(context).density

        // Por qué: Para asociar una pastilla existente al mismo mensaje físico durante pequeños ajustes
        // de desplazamiento (scroll settling), la distancia vertical debe ser muy reducida (máximo 30dp)
        // y la alineación horizontal debe conservarse (máximo 20dp). Si la distancia es mayor, se trata
        // de otro mensaje distinto (por ejemplo, múltiples saludos repetidos "Bună dimineața!" en días diferentes)
        // y debe recibir su propia pastilla independiente. Además, cualquier ID ya reclamado en la pasada actual
        // se excluye para impedir que un mensaje le robe el globo a otro mensaje idéntico.
        val maxVerticalDrift = maxVerticalDriftPx(density)
        val maxHorizontalDrift = (20 * density).toInt()

        // 1. Coincidencia por texto idéntico y proximidad espacial estricta
        var bestId: String? = null
        var minVerticalDist = Int.MAX_VALUE

        for ((id, bubble) in activeBubbles) {
            if (claimedIds.contains(id)) continue
            if (bubble.text == text) {
                val dTop = abs(bubble.targetRect.top - targetRect.top)
                val dLeft = abs(bubble.targetRect.left - targetRect.left)
                if (dTop <= maxVerticalDrift && dLeft <= maxHorizontalDrift && dTop < minVerticalDist) {
                    minVerticalDist = dTop
                    bestId = id
                }
            }
        }

        if (bestId != null) {
            return bestId
        }

        // 2. Coincidencia espacial estricta de respaldo si el texto difiere ligeramente (ej: corrección de puntuación)
        val proximityThreshold = (18 * density).toInt()
        for ((id, bubble) in activeBubbles) {
            if (claimedIds.contains(id)) continue
            val dTop = abs(bubble.targetRect.top - targetRect.top)
            val dLeft = abs(bubble.targetRect.left - targetRect.left)
            if (dTop <= proximityThreshold && dLeft <= proximityThreshold) {
                return id
            }
        }
        return null
    }

    private fun addBubble(
        targetRect: Rect,
        text: String,
        id: String,
        preInflatedView: View? = null,
        preCalculatedParams: WindowManager.LayoutParams? = null,
        preCalculatedBounds: Rect? = null,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE
    ) {
        val view = preInflatedView ?: inflater.inflate(R.layout.bubble_overlay, null)
        val (params, overlayBounds) = if (preCalculatedParams != null && preCalculatedBounds != null) {
            Pair(preCalculatedParams, preCalculatedBounds)
        } else {
            calculateBubbleLayout(targetRect, view, text, profile)
        }

        view.alpha = 0f
        if (addWindowViewWithFallback(view, params, "bubble $id")) {
            view.animate().alpha(1f).setDuration(150).start()
            activeBubbles[id] = ActiveBubble(view, text, targetRect, overlayBounds)
        } else {
            ServiceBridge.lastScanDebug = "${ServiceBridge.lastScanDebug} | wmErr=addBubbleFailed"
        }
    }

    private fun updateBubble(
        bubble: ActiveBubble,
        targetRect: Rect,
        text: String,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE
    ) {
        val (params, overlayBounds) = calculateBubbleLayout(targetRect, bubble.view, text, profile)
        bubble.overlayBounds = overlayBounds
        // Por qué: Mantener targetRect y text actualizados para que las comparaciones de proximidad en frames subsiguientes sean precisas
        bubble.targetRect = targetRect
        bubble.text = text

        params.type = (bubble.view.layoutParams as? WindowManager.LayoutParams)?.type
            ?: WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        try {
            windowManager.updateViewLayout(bubble.view, params)
        } catch (e: Exception) {
            android.util.Log.e("Translator", "OverlayManager failed to updateViewLayout", e)
        }
    }

    private fun calculateBubbleLayout(
        targetRect: Rect,
        view: View,
        text: String,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE
    ): Pair<WindowManager.LayoutParams, Rect> {
        val screenWidth = ScreenMetricsHelper.currentBounds(context).widthPx

        // Por qué: Adapta el color y contraste de la burbuja al tema seleccionado y al modo de alineación de la app
        val isRightSide = targetRect.right > (screenWidth * 0.65f)
        val style = resolveBubbleStyle(
            overlayTheme = overlayTheme,
            alignmentMode = profile.alignmentMode,
            isRightSide = isRightSide,
            isWhatsAppApp = profile.id == "whatsapp"
        )
        view.setBackgroundResource(style.backgroundRes)

        val posX = targetRect.left
        val posY = targetRect.top
        val bubbleWidth = targetRect.width()

        val textView = view.findViewById<TextView>(R.id.translated_text)
        textView.setTextColor(style.textColor)
        textView.text = text

        view.measure(
            View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        // Por qué: Cubre como mínimo la altura completa del contenedor original para evitar
        // que líneas de texto o marcas de tiempo previas queden descubiertas por debajo o por los lados.
        val bubbleHeight = max(targetRect.height(), view.measuredHeight)

        val params = (view.layoutParams as? WindowManager.LayoutParams) ?: WindowManager.LayoutParams(
            bubbleWidth,
            bubbleHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        params.width = bubbleWidth
        params.height = bubbleHeight
        params.x = posX
        params.y = posY

        val overlayBounds = Rect(posX, posY, posX + bubbleWidth, posY + bubbleHeight)
        return Pair(params, overlayBounds)
    }

    private var draftOverlayView: View? = null
    @Volatile
    var currentDraftOverlayBounds: Rect? = null
        private set

    private data class DraftLayout(
        val posX: Int,
        val posY: Int,
        val bubbleWidth: Int,
        val bubbleHeight: Int,
        val overlayBounds: Rect
    )

    private fun bindDraftView(
        view: View,
        text: String,
        targetLangCode: String,
        showReplace: Boolean,
        onInsertClicked: () -> Unit,
        onDismissClicked: () -> Unit,
        onBadgeClicked: (() -> Unit)? = null
    ) {
        val textView = view.findViewById<TextView>(R.id.draft_text)
        val badgeView = view.findViewById<TextView>(R.id.draft_badge)
        val replaceBtn = view.findViewById<View>(R.id.draft_action_replace)

        textView.text = text
        textView.setTextColor(if (showReplace) 0xFFF8FAFC.toInt() else 0xFF94A3B8.toInt())
        badgeView.text = "⇄ $targetLangCode"
        replaceBtn.visibility = if (showReplace) View.VISIBLE else View.GONE

        if (showReplace) {
            view.findViewById<View>(R.id.draft_container).setOnClickListener {
                onInsertClicked()
            }
            replaceBtn.setOnClickListener {
                onInsertClicked()
            }
        } else {
            view.findViewById<View>(R.id.draft_container).setOnClickListener(null)
        }
        view.findViewById<View>(R.id.draft_action_close).setOnClickListener {
            onDismissClicked()
        }

        // Por qué: Permite pulsar directamente sobre la insignia de idioma para alternar manualmente
        // la dirección de traducción (ej. cambiar de ⇄ RO a ⇄ EL) cuando la autodetección de alfabeto no sea la deseada.
        if (onBadgeClicked != null) {
            badgeView.setOnClickListener {
                onBadgeClicked()
            }
        } else {
            badgeView.setOnClickListener(null)
        }
    }

    private fun computeDraftLayout(
        view: View,
        inputRect: Rect,
        screenWidth: Int,
        density: Float
    ): DraftLayout {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth - (20 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val bubbleWidth = screenWidth - (20 * density).toInt()
        val bubbleHeight = view.measuredHeight.coerceAtLeast((42 * density).toInt())

        val spacing = (6 * density).toInt()
        val posX = (10 * density).toInt()
        val posY = (inputRect.top - bubbleHeight - spacing).coerceAtLeast((48 * density).toInt())

        val overlayBounds = Rect(posX, posY, posX + bubbleWidth, posY + bubbleHeight)
        return DraftLayout(posX, posY, bubbleWidth, bubbleHeight, overlayBounds)
    }

    @Synchronized
    private fun purgeCollidingBubbles(overlayBounds: Rect, inputRect: Rect) {
        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (Rect.intersects(overlayBounds, entry.value.overlayBounds) ||
                (inputRect.width() > 0 && Rect.intersects(inputRect, entry.value.overlayBounds))
            ) {
                try {
                    windowManager.removeView(entry.value.view)
                } catch (e: Exception) {
                    // Ignore
                }
                iterator.remove()
            }
        }
    }

    fun updateDraftOverlay(
        inputRect: Rect,
        text: String,
        targetLangCode: String = "RO",
        showReplace: Boolean = true,
        onInsertClicked: () -> Unit,
        onDismissClicked: () -> Unit,
        onBadgeClicked: (() -> Unit)? = null
    ) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post {
                updateDraftOverlay(inputRect, text, targetLangCode, showReplace, onInsertClicked, onDismissClicked, onBadgeClicked)
            }
            return
        }

        if (text.isBlank()) {
            removeDraftOverlay()
            return
        }

        val metrics = ScreenMetricsHelper.currentBounds(context)
        val screenWidth = metrics.widthPx
        val density = metrics.density

        // Por qué: Si la coordenada superior del campo de texto no es válida o está fuera de la zona inferior normal,
        // no se dibuja la píldora para evitar que flote sobre la barra de estado o encabezados.
        if (inputRect.top <= (100 * density).toInt()) {
            removeDraftOverlay()
            return
        }

        synchronized(this) {
            val view = draftOverlayView ?: inflater.inflate(R.layout.draft_preview_overlay, null)
            bindDraftView(view, text, targetLangCode, showReplace, onInsertClicked, onDismissClicked, onBadgeClicked)

            val layout = computeDraftLayout(view, inputRect, screenWidth, density)
            currentDraftOverlayBounds = layout.overlayBounds

            purgeCollidingBubbles(layout.overlayBounds, inputRect)

            if (draftOverlayView == null) {
                val params = WindowManager.LayoutParams(
                    layout.bubbleWidth,
                    layout.bubbleHeight,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = layout.posX
                    y = layout.posY
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                }

                if (addWindowViewWithFallback(view, params, "draft overlay")) {
                    draftOverlayView = view
                }
            } else {
                val params = view.layoutParams as WindowManager.LayoutParams
                params.width = layout.bubbleWidth
                params.height = layout.bubbleHeight
                params.x = layout.posX
                params.y = layout.posY

                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    // Ignore WindowManager errors
                }
            }
        }
    }

    fun removeDraftOverlay() {
        currentDraftOverlayBounds = null
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { removeDraftOverlay() }
            return
        }
        synchronized(this) {
            currentDraftOverlayBounds = null
            draftOverlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    // Ignore
                }
                draftOverlayView = null
            }
        }
    }

    private var singlePillView: View? = null

    /**
     * Muestra una pastilla flotante compacta para un mensaje específico seleccionado al tocarlo.
     * Por qué: Evita saturar la pantalla con 20 overlays simultáneos; el usuario traduce exactamente
     * el mensaje que desea leer con formato claro y acciones rápidas (Copiar / Cerrar).
     */
    fun showSingleMessagePill(
        targetRect: Rect,
        translatedText: String,
        targetLangCode: String = "EL",
        onDismiss: () -> Unit = {}
    ) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { showSingleMessagePill(targetRect, translatedText, targetLangCode, onDismiss) }
            return
        }

        if (translatedText.isBlank()) {
            removeSingleMessagePill()
            return
        }

        val metrics = ScreenMetricsHelper.currentBounds(context)
        val screenWidth = metrics.widthPx
        val density = metrics.density

        synchronized(this) {
            val view = singlePillView ?: inflater.inflate(R.layout.single_message_pill_overlay, null)

            val badgeView = view.findViewById<TextView>(R.id.pill_badge)
            val textView = view.findViewById<TextView>(R.id.pill_translated_text)
            val copyBtn = view.findViewById<View>(R.id.pill_btn_copy)
            val closeBtn = view.findViewById<View>(R.id.pill_btn_close)

            badgeView.text = "⇄ $targetLangCode"
            textView.text = translatedText

            copyBtn.setOnClickListener {
                ClipboardHelper.copySensitiveText(context, "Translation", translatedText)
                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            }

            closeBtn.setOnClickListener {
                removeSingleMessagePill()
                onDismiss()
            }

            val maxWidth = (screenWidth * 0.88f).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val pillWidth = view.measuredWidth.coerceAtLeast((180 * density).toInt())
            val pillHeight = view.measuredHeight

            val posX = targetRect.left.coerceIn((12 * density).toInt(), (screenWidth - pillWidth - (12 * density).toInt()).coerceAtLeast((12 * density).toInt()))
            val posY = if (targetRect.top - pillHeight - (8 * density).toInt() > (48 * density).toInt()) {
                targetRect.top - pillHeight - (8 * density).toInt()
            } else {
                targetRect.bottom + (8 * density).toInt()
            }

            val params = WindowManager.LayoutParams(
                pillWidth,
                pillHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = posX
                y = posY
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            if (singlePillView == null) {
                if (addWindowViewWithFallback(view, params, "single pill overlay")) {
                    singlePillView = view
                }
            } else {
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    @Synchronized
    fun removeSingleMessagePill() {
        singlePillView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            singlePillView = null
        }
    }

    @Synchronized
    fun removeAllMessageOverlays() {
        for (bubble in activeBubbles.values) {
            try {
                windowManager.removeView(bubble.view)
            } catch (e: Exception) {
                // Ignore if already removed
            }
        }
        activeBubbles.clear()
        removeSingleMessagePill()
    }

    // Por qué: Elimina de inmediato cualquier burbuja activa cuyas coordenadas ya no coincidan
    // con ninguno de los mensajes visibles actuales (por ejemplo, tras desplazarse o eliminarse),
    // usando exactamente el mismo umbral de tolerancia vertical que findMatchingBubble.
    @Synchronized
    fun purgeOrphans(currentValidRects: List<Rect>) {
        if (activeBubbles.isEmpty()) return
        val density = ScreenMetricsHelper.currentBounds(context).density
        val tolerance = maxVerticalDriftPx(density)

        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val bubbleRect = entry.value.targetRect
            val matchesAny = currentValidRects.any { valid ->
                abs(valid.top - bubbleRect.top) <= tolerance &&
                abs(valid.bottom - bubbleRect.bottom) <= tolerance
            }
            if (!matchesAny) {
                try {
                    windowManager.removeView(entry.value.view)
                } catch (e: Exception) {
                    // Ignorar si ya fue removida
                }
                iterator.remove()
            }
        }
    }

    // Por qué: Permite verificar de forma atómica si hay alguna superposición activa dibujada en pantalla
    // para que el vigilante en segundo plano pueda purgar la pantalla inmediatamente al salir del chat activo.
    @Synchronized
    fun hasOverlays(): Boolean {
        return activeBubbles.isNotEmpty() || singlePillView != null || draftOverlayView != null
    }

    @Synchronized
    fun removeAllOverlays() {
        removeAllMessageOverlays()
        removeDraftOverlay()
    }
}
