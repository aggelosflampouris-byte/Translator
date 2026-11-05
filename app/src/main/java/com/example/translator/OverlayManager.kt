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

    private data class ActiveBubble(
        val view: View,
        var text: String,
        var targetRect: Rect,
        var overlayBounds: Rect
    )

    // Map of bubble ID to ActiveBubble
    private val activeBubbles = mutableMapOf<String, ActiveBubble>()

    @Synchronized
    fun getActiveOverlayRects(): List<Rect> {
        return activeBubbles.values.map { Rect(it.overlayBounds) }
    }

    // Called on Main Thread
    @Synchronized
    fun addOrUpdateOverlay(rect: Rect, translatedText: String) {
        if (translatedText.isBlank()) return
        val draftBounds = currentDraftOverlayBounds
        if (draftBounds != null && Rect.intersects(draftBounds, rect)) return

        val matchedId = findMatchingBubble(rect, translatedText)
        if (matchedId != null) {
            val bubble = activeBubbles[matchedId]
            if (bubble != null) {
                updateBubble(bubble, rect, translatedText)
                return
            }
        }

        val view = inflater.inflate(R.layout.bubble_overlay, null)
        val (params, overlayBounds) = calculateBubbleLayout(rect, view, translatedText)
        val newId = "bubble_${bubbleIdCounter.incrementAndGet()}"
        addBubble(rect, translatedText, newId, view, params, overlayBounds)
    }

    // Called on Main Thread
    @Synchronized
    fun updateOverlays(results: List<Pair<Rect?, String>>, isFullSync: Boolean = true) {
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

            val matchedId = findMatchingBubble(rect, translatedText)
            if (matchedId != null) {
                // Actualizar burbuja existente
                val bubble = activeBubbles[matchedId]
                if (bubble != null) {
                    if (draftBounds != null && Rect.intersects(draftBounds, bubble.overlayBounds)) {
                        continue
                    }
                    updateBubble(bubble, rect, translatedText)
                    acceptedBoundsInBatch.add(bubble.overlayBounds)
                }
                updatedIds.add(matchedId)
            } else {
                val view = inflater.inflate(R.layout.bubble_overlay, null)
                val (params, overlayBounds) = calculateBubbleLayout(rect, view, translatedText)
                if (draftBounds != null && Rect.intersects(draftBounds, overlayBounds)) {
                    continue
                }
                acceptedBoundsInBatch.add(overlayBounds)
                // Por qué: Reutilizamos la vista ya inflada y las dimensiones calculadas para evitar doble inflación
                val newId = "bubble_${bubbleIdCounter.incrementAndGet()}"
                addBubble(rect, translatedText, newId, view, params, overlayBounds)
                updatedIds.add(newId)
            }
        }

        // Por qué: Si es sincronización completa, eliminar overlays que ya no están visibles en pantalla.
        // Si no es sincronización completa (ej: renderizado incremental), conservar los activos no colisionantes.
        val screenHeight = context.resources.displayMetrics.heightPixels
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

    private fun findMatchingBubble(targetRect: Rect, text: String): String? {
        val density = context.resources.displayMetrics.density

        // Por qué: Si una burbuja activa ya contiene exactamente esta traducción,
        // corresponde al mismo mensaje moviéndose durante el scroll; reutilizamos su vista.
        var bestId: String? = null
        var minVerticalDist = Int.MAX_VALUE

        for ((id, bubble) in activeBubbles) {
            if (bubble.text == text) {
                val dTop = abs(bubble.targetRect.top - targetRect.top)
                if (dTop < minVerticalDist) {
                    minVerticalDist = dTop
                    bestId = id
                }
            }
        }

        if (bestId != null && minVerticalDist < (600 * density).toInt()) {
            return bestId
        }

        // 2. Coincidencia espacial de respaldo si el texto difiere ligeramente
        val proximityThreshold = (15 * density).toInt()
        for ((id, bubble) in activeBubbles) {
            val dTop = abs(bubble.targetRect.top - targetRect.top)
            val dLeft = abs(bubble.targetRect.left - targetRect.left)
            if (dTop < proximityThreshold && dLeft < proximityThreshold) {
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
        preCalculatedBounds: Rect? = null
    ) {
        val view = preInflatedView ?: inflater.inflate(R.layout.bubble_overlay, null)
        val (params, overlayBounds) = if (preCalculatedParams != null && preCalculatedBounds != null) {
            Pair(preCalculatedParams, preCalculatedBounds)
        } else {
            calculateBubbleLayout(targetRect, view, text)
        }

        view.alpha = 0f
        try {
            params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            windowManager.addView(view, params)
            view.animate().alpha(1f).setDuration(150).start()
            activeBubbles[id] = ActiveBubble(view, text, targetRect, overlayBounds)
        } catch (e: Exception) {
            android.util.Log.w("Translator", "addBubble with TYPE_ACCESSIBILITY_OVERLAY failed, trying TYPE_APPLICATION_OVERLAY", e)
            try {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                windowManager.addView(view, params)
                view.animate().alpha(1f).setDuration(150).start()
                activeBubbles[id] = ActiveBubble(view, text, targetRect, overlayBounds)
            } catch (e2: Exception) {
                android.util.Log.e("Translator", "OverlayManager failed to addView for bubble $id", e2)
            }
        }
    }

    private fun updateBubble(bubble: ActiveBubble, targetRect: Rect, text: String) {
        val (params, overlayBounds) = calculateBubbleLayout(targetRect, bubble.view, text)
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

    private fun calculateBubbleLayout(targetRect: Rect, view: View, text: String): Pair<WindowManager.LayoutParams, Rect> {
        val screenWidth = context.resources.displayMetrics.widthPixels

        // Por qué: Diferencia entre mensajes salientes (lado derecho) y entrantes (lado izquierdo)
        // para aplicar los colores nativos opacos de WhatsApp (verde esmeralda vs gris pizarra)
        // cubriendo 100% el fondo original sin distorsiones ni bordes extraños.
        val isOutgoing = targetRect.right > (screenWidth * 0.65f)
        val bgRes = if (isOutgoing) {
            R.drawable.bg_translation_bubble_outgoing
        } else {
            R.drawable.bg_translation_bubble_incoming
        }
        view.setBackgroundResource(bgRes)

        val posX = targetRect.left
        val posY = targetRect.top
        val bubbleWidth = targetRect.width()

        val textView = view.findViewById<TextView>(R.id.translated_text)
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
    private var currentDraftText: String? = null
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

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val density = metrics.density

        // Por qué: Si la coordenada superior del campo de texto no es válida o está fuera de la zona inferior normal,
        // no se dibuja la píldora para evitar que flote sobre la barra de estado o encabezados.
        if (inputRect.top <= (100 * density).toInt()) {
            removeDraftOverlay()
            return
        }

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

            try {
                windowManager.addView(view, params)
                draftOverlayView = view
                currentDraftText = text
            } catch (e: Exception) {
                try {
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    windowManager.addView(view, params)
                    draftOverlayView = view
                    currentDraftText = text
                } catch (e2: Exception) {
                    android.util.Log.e("Translator", "Failed to add draft overlay", e2)
                }
            }
        } else {
            val params = view.layoutParams as WindowManager.LayoutParams
            params.width = layout.bubbleWidth
            params.height = layout.bubbleHeight
            params.x = layout.posX
            params.y = layout.posY

            try {
                windowManager.updateViewLayout(view, params)
                currentDraftText = text
            } catch (e: Exception) {
                // Ignore WindowManager errors
            }
        }
    }

    fun removeDraftOverlay() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { removeDraftOverlay() }
            return
        }
        currentDraftOverlayBounds = null
        draftOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            draftOverlayView = null
            currentDraftText = null
        }
    }

    private var singlePillView: View? = null
    var currentSinglePillBounds: Rect? = null
        private set

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
        if (translatedText.isBlank()) {
            removeSingleMessagePill()
            return
        }

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val density = metrics.density

        val view = singlePillView ?: inflater.inflate(R.layout.single_message_pill_overlay, null)

        val badgeView = view.findViewById<TextView>(R.id.pill_badge)
        val textView = view.findViewById<TextView>(R.id.pill_translated_text)
        val copyBtn = view.findViewById<View>(R.id.pill_btn_copy)
        val closeBtn = view.findViewById<View>(R.id.pill_btn_close)

        badgeView.text = "⇄ $targetLangCode"
        textView.text = translatedText

        copyBtn.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Translation", translatedText))
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

        val pillBounds = Rect(posX, posY, posX + pillWidth, posY + pillHeight)
        currentSinglePillBounds = pillBounds

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
            try {
                windowManager.addView(view, params)
                singlePillView = view
            } catch (e: Exception) {
                try {
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    windowManager.addView(view, params)
                    singlePillView = view
                } catch (e2: Exception) {
                    android.util.Log.e("Translator", "Failed to add single pill overlay", e2)
                }
            }
        } else {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun removeSingleMessagePill() {
        currentSinglePillBounds = null
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
    // impidiendo que burbujas huérfanas queden congeladas cubriendo nuevos mensajes en pantalla.
    @Synchronized
    fun purgeOrphans(currentValidRects: List<Rect>) {
        if (activeBubbles.isEmpty()) return
        val density = context.resources.displayMetrics.density
        val tolerance = (25 * density).toInt()

        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val bubbleRect = entry.value.targetRect
            val matchesAny = currentValidRects.any { valid ->
                abs(valid.top - bubbleRect.top) < tolerance &&
                abs(valid.bottom - bubbleRect.bottom) < tolerance
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
    // para que el vigilante en segundo plano pueda purgar la pantalla inmediatamente al salir de WhatsApp.
    @Synchronized
    fun hasOverlays(): Boolean {
        return activeBubbles.isNotEmpty() || singlePillView != null || draftOverlayView != null
    }

    @Synchronized
    fun removeAllOverlays() {
        removeAllMessageOverlays()
        removeDraftOverlay()
        removeSingleMessagePill()
    }
}
