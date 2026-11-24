package com.example.translator

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
    @Volatile private var lastReportedHasOverlays: Boolean = false
    @Volatile var onOverlaysActiveChanged: ((Boolean) -> Unit)? = null

    data class BubbleStyle(
        val backgroundRes: Int,
        val textColor: Int
    )

    data class DraftPillStyle(
        val containerBgRes: Int,
        val badgeBgRes: Int,
        val badgeTextColor: Int,
        val bodyTextColor: Int,
        val actionBgRes: Int,
        val actionTextColor: Int,
        val closeBgRes: Int,
        val closeTextColor: Int
    )

    data class DraftFrame(
        val posX: Int,
        val posY: Int,
        val bubbleWidth: Int,
        val bubbleHeight: Int
    )

    data class DraftLayout(
        val posX: Int,
        val posY: Int,
        val bubbleWidth: Int,
        val bubbleHeight: Int,
        val overlayBounds: Rect
    )

    data class ExpectedBubbleSlot(
        val rect: Rect,
        val sourceText: String
    )

    data class OverlayRenderItem(
        val rect: Rect,
        val sourceText: String,
        val translatedText: String,
        val isQuoted: Boolean = false,
        val maxBottomPx: Int = Int.MAX_VALUE
    )

    companion object {
        private const val TEXT_COLOR_DARK_SURFACE = 0xFFE9EDEF.toInt()
        private const val TEXT_COLOR_LIGHT_SURFACE = 0xFF0F172A.toInt()
        private const val TEXT_COLOR_QUOTED_DARK = 0xFFD1D7DB.toInt()
        private const val TEXT_COLOR_QUOTED_LIGHT = 0xFF334155.toInt()

        /**
         * Determina si un globo pertenece al lado saliente (usuario) sin confundir mensajes entrantes
         * anchos de grupos cuyo borde derecho supera el 65% de pantalla.
         */
        fun isOutgoingBubble(
            targetLeft: Int,
            targetRight: Int,
            screenWidth: Int,
            alignmentMode: ChatAlignmentMode
        ): Boolean {
            if (alignmentMode != ChatAlignmentMode.LEFT_RIGHT_CHAT || screenWidth <= 0) return false
            val leftRatio = targetLeft.toFloat() / screenWidth.toFloat()
            val rightRatio = targetRight.toFloat() / screenWidth.toFloat()
            val centerRatio = (leftRatio + rightRatio) * 0.5f
            return (leftRatio >= 0.34f && rightRatio > 0.72f) ||
                (leftRatio >= 0.17f && rightRatio >= 0.89f && centerRatio >= 0.53f)
        }

        /**
         * Resuelve el fondo y color de texto de la burbuja según el tema elegido, si es cita y la app activa.
         */
        fun resolveBubbleStyle(
            overlayTheme: String,
            alignmentMode: ChatAlignmentMode,
            isRightSide: Boolean,
            isWhatsAppApp: Boolean,
            isSystemDarkMode: Boolean = true,
            isQuoted: Boolean = false
        ): BubbleStyle {
            val useLightSurface = overlayTheme == AppDefaults.OVERLAY_THEME_LIGHT ||
                (overlayTheme == AppDefaults.OVERLAY_THEME_AUTO && !isSystemDarkMode)
            if (isQuoted) {
                return if (useLightSurface) {
                    BubbleStyle(
                        backgroundRes = R.drawable.bg_translation_bubble_quoted_light,
                        textColor = TEXT_COLOR_QUOTED_LIGHT
                    )
                } else {
                    BubbleStyle(
                        backgroundRes = R.drawable.bg_translation_bubble_quoted_dark,
                        textColor = TEXT_COLOR_QUOTED_DARK
                    )
                }
            }
            val isOutgoing = alignmentMode == ChatAlignmentMode.LEFT_RIGHT_CHAT && isRightSide
            return when {
                useLightSurface -> BubbleStyle(
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

        /**
         * Resuelve los colores y drawables de las píldoras flotantes (borrador y toque individual) según el tema.
         *
         * Por qué: Alinea la píldora de teclado con el tema elegido y separa visualmente el estado
         * de reemplazo (`Replace`) del estado tonal de confirmación (`✓ Applied · Undo`).
         */
        fun resolveDraftPillStyle(
            overlayTheme: String,
            isSystemDarkMode: Boolean,
            isUndoMode: Boolean = false,
            isStalePending: Boolean = false
        ): DraftPillStyle {
            val useLightSurface = overlayTheme == AppDefaults.OVERLAY_THEME_LIGHT ||
                (overlayTheme == AppDefaults.OVERLAY_THEME_AUTO && !isSystemDarkMode)
            return if (useLightSurface) {
                DraftPillStyle(
                    containerBgRes = R.drawable.bg_draft_bubble_light,
                    badgeBgRes = R.drawable.bg_draft_badge_light,
                    badgeTextColor = if (isUndoMode) 0xFF1D4ED8.toInt() else 0xFF2563EB.toInt(),
                    bodyTextColor = if (isUndoMode || isStalePending) 0xFF475569.toInt() else TEXT_COLOR_LIGHT_SURFACE,
                    actionBgRes = if (isUndoMode) R.drawable.bg_draft_undo_btn_light else R.drawable.bg_draft_action_btn_light,
                    actionTextColor = if (isUndoMode) 0xFF1D4ED8.toInt() else 0xFFFFFFFF.toInt(),
                    closeBgRes = R.drawable.bg_draft_close_btn_light,
                    closeTextColor = 0xFF64748B.toInt()
                )
            } else {
                DraftPillStyle(
                    containerBgRes = R.drawable.bg_draft_bubble_dark,
                    badgeBgRes = R.drawable.bg_draft_badge_dark,
                    badgeTextColor = 0xFF60A5FA.toInt(),
                    bodyTextColor = if (isUndoMode || isStalePending) 0xFFCBD5E1.toInt() else 0xFFF8FAFC.toInt(),
                    actionBgRes = if (isUndoMode) R.drawable.bg_draft_undo_btn_dark else R.drawable.bg_draft_action_btn,
                    actionTextColor = if (isUndoMode) 0xFF60A5FA.toInt() else 0xFFFFFFFF.toInt(),
                    closeBgRes = R.drawable.bg_draft_close_btn_dark,
                    closeTextColor = 0xFF94A3B8.toInt()
                )
            }
        }

        /**
         * Calcula dimensiones compactas y coordenadas seguras en enteros puros para pruebas JVM
         * sin instanciar `android.graphics.Rect`.
         */
        fun resolveDraftFrame(
            measuredWidth: Int,
            measuredHeight: Int,
            anchorTop: Int,
            anchorLeft: Int,
            screenWidth: Int,
            screenHeight: Int,
            density: Float,
            isUndoMode: Boolean = false
        ): DraftFrame {
            val horizMargin = (12 * density).toInt()
            val maxWidth = min(
                (screenWidth - horizMargin * 2).coerceAtLeast((180 * density).toInt()),
                (420 * density).toInt()
            )
            val minDp = if (isUndoMode) 220f else 200f
            val minWidth = min((minDp * density).toInt(), maxWidth)
            val bubbleWidth = measuredWidth.coerceIn(minWidth, maxWidth)
            val bubbleHeight = measuredHeight.coerceAtLeast((44 * density).toInt())

            val spacing = (8 * density).toInt()
            val minTop = (48 * density).toInt()
            val maxTop = (screenHeight - bubbleHeight - horizMargin).coerceAtLeast(minTop)
            val maxLeft = (screenWidth - bubbleWidth - horizMargin).coerceAtLeast(horizMargin)

            val posX = anchorLeft.coerceIn(horizMargin, maxLeft)
            val posY = (anchorTop - bubbleHeight - spacing).coerceIn(minTop, maxTop)
            return DraftFrame(posX, posY, bubbleWidth, bubbleHeight)
        }

        /**
         * Calcula dimensiones compactas y coordenadas seguras sobre el compositor para la píldora de borrador.
         */
        fun resolveDraftBounds(
            measuredWidth: Int,
            measuredHeight: Int,
            anchorTop: Int,
            anchorLeft: Int,
            screenWidth: Int,
            screenHeight: Int,
            density: Float,
            isUndoMode: Boolean = false
        ): DraftLayout {
            val frame = resolveDraftFrame(
                measuredWidth = measuredWidth,
                measuredHeight = measuredHeight,
                anchorTop = anchorTop,
                anchorLeft = anchorLeft,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                density = density,
                isUndoMode = isUndoMode
            )
            val overlayBounds = Rect(
                frame.posX,
                frame.posY,
                frame.posX + frame.bubbleWidth,
                frame.posY + frame.bubbleHeight
            )
            return DraftLayout(frame.posX, frame.posY, frame.bubbleWidth, frame.bubbleHeight, overlayBounds)
        }
    }

    private fun isSystemDarkModeNow(): Boolean {
        val nightMask = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMask != android.content.res.Configuration.UI_MODE_NIGHT_NO
    }

    private fun notifyOverlayPresenceLocked() {
        val current = activeBubbles.isNotEmpty() || bubbleCanvas != null || draftOverlayView != null
        if (current != lastReportedHasOverlays) {
            lastReportedHasOverlays = current
            onOverlaysActiveChanged?.invoke(current)
        }
    }

    fun setOverlayTheme(theme: String) {
        overlayTheme = theme
    }

    private class BubbleViewHolder(val textView: TextView) {
        var backgroundRes: Int = 0
        var textColor: Int = 0
        var padH: Int = -1
        var padV: Int = -1
        var textSizeSp: Float = -1f
        var maxLines: Int = Int.MAX_VALUE
        var ellipsizeEnd: Boolean = false
        var lastText: String? = null
        var lastWidthPx: Int = -1
        var measuredHeightPx: Int = 0
    }

    private data class BubbleLayoutSpec(
        val overlayBounds: Rect,
        val widthPx: Int,
        val heightPx: Int
    )

    private data class ActiveBubble(
        val view: View,
        var sourceText: String,
        var text: String,
        var targetRect: Rect,
        var overlayBounds: Rect,
        var isQuoted: Boolean,
        var maxBottomPx: Int,
        var scanId: Long
    )

    // Por qué: Un único lienzo transparente pasa-toques elimina decenas de transacciones addView/removeView
    // en SurfaceFlinger y Binder durante el scroll, habilitando reciclaje de vistas sin parpadeo.
    private var bubbleCanvas: FrameLayout? = null
    private val bubbleViewPool = ArrayDeque<View>()
    private var isCanvasScrollHidden: Boolean = false
    private val canvasScreenLocation = IntArray(2)
    private val maxPooledBubbleViews = 24

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

    private fun ensureBubbleCanvasAttachedLocked(): FrameLayout? {
        bubbleCanvas?.let { return it }
        val canvas = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
            clipChildren = true
            clipToPadding = false
            alpha = if (isCanvasScrollHidden) 0f else 1f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        return if (addWindowViewWithFallback(canvas, params, "bubble canvas")) {
            bubbleCanvas = canvas
            canvas
        } else {
            null
        }
    }

    private fun detachBubbleCanvasLocked() {
        bubbleCanvas?.let { canvas ->
            canvas.animate().cancel()
            try {
                canvas.removeAllViews()
                windowManager.removeView(canvas)
            } catch (_: Exception) {
                // Ignorar si WindowManager ya retiró el lienzo
            }
        }
        bubbleCanvas = null
        isCanvasScrollHidden = false
        bubbleViewPool.clear()
    }

    private fun holderFor(view: View): BubbleViewHolder {
        val existing = view.tag as? BubbleViewHolder
        if (existing != null) return existing
        return BubbleViewHolder(view.findViewById(R.id.translated_text)).also { view.tag = it }
    }

    private fun obtainBubbleViewLocked(): View {
        val pooled = bubbleViewPool.removeFirstOrNull()
        if (pooled != null) {
            pooled.animate().cancel()
            pooled.alpha = 1f
            pooled.translationX = 0f
            pooled.translationY = 0f
            return pooled
        }
        val inflated = inflater.inflate(R.layout.bubble_overlay, null)
        holderFor(inflated)
        return inflated
    }

    private fun recycleBubbleViewLocked(view: View) {
        view.animate().cancel()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        if (bubbleViewPool.size < maxPooledBubbleViews) {
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            bubbleViewPool.addLast(view)
        }
    }

    private fun positionBubbleViewOnCanvasLocked(canvas: FrameLayout, view: View, spec: BubbleLayoutSpec) {
        val (offsetX, offsetY) = if (canvas.isLaidOut) {
            canvas.getLocationOnScreen(canvasScreenLocation)
            canvasScreenLocation[0] to canvasScreenLocation[1]
        } else {
            0 to 0
        }
        val existingLp = view.layoutParams as? FrameLayout.LayoutParams
        if (existingLp == null || existingLp.width != spec.widthPx || existingLp.height != spec.heightPx) {
            view.layoutParams = FrameLayout.LayoutParams(
                spec.widthPx,
                spec.heightPx,
                Gravity.TOP or Gravity.START
            )
        }
        view.translationX = (spec.overlayBounds.left - offsetX).toFloat()
        view.translationY = (spec.overlayBounds.top - offsetY).toFloat()
        if (view.parent !== canvas) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            canvas.addView(view)
        }
    }

    private fun revealCanvasIfNeededLocked() {
        val canvas = bubbleCanvas ?: return
        if (activeBubbles.isEmpty()) return
        if (isCanvasScrollHidden || canvas.alpha < 0.99f) {
            isCanvasScrollHidden = false
            canvas.animate().cancel()
            canvas.animate().alpha(1f).setDuration(90L).start()
        }
    }

    /**
     * Desvanece suavemente el lienzo único al iniciar un scroll sin destruir ventanas ni vistas.
     */
    @Synchronized
    fun beginScrollHide(clearDraftToo: Boolean) {
        bubbleCanvas?.let { canvas ->
            isCanvasScrollHidden = true
            canvas.animate().cancel()
            canvas.animate().alpha(0f).setDuration(70L).start()
        }
        if (clearDraftToo) {
            removeDraftOverlay()
        }
    }

    /**
     * Libera el lienzo oculto si el escaneo tras scroll no dejó ninguna burbuja activa visible.
     */
    @Synchronized
    fun cleanupEmptyScrollCanvas() {
        if (activeBubbles.isEmpty() && bubbleCanvas != null) {
            detachBubbleCanvasLocked()
            notifyOverlayPresenceLocked()
        }
    }

    @Synchronized
    fun hasActiveMessageBubbles(): Boolean = activeBubbles.isNotEmpty()

    private fun overlapRatioOfSmaller(a: Rect, b: Rect): Float {
        val intersection = Rect()
        if (!intersection.setIntersect(a, b)) return 0f
        val intersectArea = intersection.width().toLong() * intersection.height()
        val minArea = min(
            a.width().toLong() * a.height(),
            b.width().toLong() * b.height()
        )
        return if (minArea > 0L) intersectArea.toFloat() / minArea.toFloat() else 0f
    }

    /**
     * Retira burbujas activas que colisionan significativamente o provienen de un escaneo anterior
     * en la misma posición antes de agregar o actualizar la nueva traducción.
     */
    private fun evictCollidingBubblesLocked(
        targetRect: Rect,
        overlayBounds: Rect,
        sourceText: String,
        scanId: Long,
        density: Float,
        excludeIds: Set<String> = emptySet()
    ) {
        val maxVert = maxVerticalDriftPx(density)
        val maxHoriz = (20 * density).toInt()
        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val (id, existing) = iterator.next()
            if (excludeIds.contains(id)) continue
            val sameSlotDuplicate = existing.sourceText == sourceText &&
                abs(existing.targetRect.top - targetRect.top) <= maxVert &&
                abs(existing.targetRect.left - targetRect.left) <= maxHoriz
            val spatialCollision = overlapRatioOfSmaller(existing.overlayBounds, overlayBounds) > 0.25f &&
                (existing.scanId < scanId || existing.sourceText != sourceText || sameSlotDuplicate)
            if (sameSlotDuplicate || spatialCollision) {
                recycleBubbleViewLocked(existing.view)
                iterator.remove()
            }
        }
    }

    // Called on Main Thread
    @Synchronized
    fun addOrUpdateOverlay(
        item: OverlayRenderItem,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        scanId: Long = 0L
    ) {
        addOrUpdateOverlaysBatch(listOf(item), profile, scanId)
    }

    /**
     * Renderiza un lote coalescido de traducciones sobre el lienzo único con una sola lectura de métricas.
     */
    @Synchronized
    fun addOrUpdateOverlaysBatch(
        items: List<OverlayRenderItem>,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        scanId: Long = 0L
    ) {
        if (items.isEmpty()) return
        val draftBounds = currentDraftOverlayBounds
        val metrics = ScreenMetricsHelper.currentBounds(context)
        val systemDarkMode = isSystemDarkModeNow()
        var drewAny = false

        for (item in items) {
            if (item.translatedText.isBlank()) continue
            if (draftBounds != null && Rect.intersects(draftBounds, item.rect)) continue

            val matchedId = findMatchingBubble(item.rect, item.sourceText, item.translatedText, metrics.density)
            if (matchedId != null) {
                val bubble = activeBubbles[matchedId]
                if (bubble != null) {
                    if (draftBounds != null && Rect.intersects(draftBounds, bubble.overlayBounds)) continue
                    updateBubble(
                        bubble = bubble,
                        targetRect = item.rect,
                        sourceText = item.sourceText,
                        text = item.translatedText,
                        profile = profile,
                        isQuoted = item.isQuoted,
                        maxBottomPx = item.maxBottomPx,
                        scanId = scanId,
                        metrics = metrics,
                        systemDarkMode = systemDarkMode
                    )
                    evictCollidingBubblesLocked(
                        targetRect = item.rect,
                        overlayBounds = bubble.overlayBounds,
                        sourceText = item.sourceText,
                        scanId = scanId,
                        density = metrics.density,
                        excludeIds = setOf(matchedId)
                    )
                    drewAny = true
                    continue
                }
            }

            val view = obtainBubbleViewLocked()
            val layoutSpec = calculateBubbleLayout(
                targetRect = item.rect,
                view = view,
                text = item.translatedText,
                profile = profile,
                isQuoted = item.isQuoted,
                maxBottomPx = item.maxBottomPx,
                metrics = metrics,
                systemDarkMode = systemDarkMode
            )
            if (draftBounds != null && Rect.intersects(draftBounds, layoutSpec.overlayBounds)) {
                recycleBubbleViewLocked(view)
                continue
            }
            evictCollidingBubblesLocked(
                targetRect = item.rect,
                overlayBounds = layoutSpec.overlayBounds,
                sourceText = item.sourceText,
                scanId = scanId,
                density = metrics.density
            )
            val newId = "bubble_${bubbleIdCounter.incrementAndGet()}"
            if (addBubble(
                    targetRect = item.rect,
                    sourceText = item.sourceText,
                    text = item.translatedText,
                    id = newId,
                    preConfiguredView = view,
                    preCalculatedSpec = layoutSpec,
                    profile = profile,
                    isQuoted = item.isQuoted,
                    maxBottomPx = item.maxBottomPx,
                    scanId = scanId,
                    metrics = metrics,
                    systemDarkMode = systemDarkMode
                )
            ) {
                drewAny = true
            }
        }

        if (drewAny) {
            revealCanvasIfNeededLocked()
        }
        notifyOverlayPresenceLocked()
    }

    // Called on Main Thread
    @Synchronized
    fun updateOverlays(
        results: List<OverlayRenderItem>,
        isFullSync: Boolean = true,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        scanId: Long = 0L
    ) {
        val updatedIds = mutableSetOf<String>()
        val draftBounds = currentDraftOverlayBounds
        val acceptedTargetRects = mutableListOf<Rect>()
        val metrics = ScreenMetricsHelper.currentBounds(context)
        val systemDarkMode = isSystemDarkModeNow()

        for (item in results) {
            if (item.translatedText.isBlank()) continue

            // Por qué: No renderizar burbujas que colisionen con la pastilla de borrador de texto
            if (draftBounds != null && Rect.intersects(draftBounds, item.rect)) {
                continue
            }

            // Por qué: Evitar superposiciones entre objetivos distintos calculados en el mismo fotograma
            var overlapsBatch = false
            for (accepted in acceptedTargetRects) {
                if (overlapRatioOfSmaller(accepted, item.rect) > 0.25f) {
                    overlapsBatch = true
                    break
                }
            }
            if (overlapsBatch) {
                continue
            }

            val matchedId = findMatchingBubble(item.rect, item.sourceText, item.translatedText, metrics.density, updatedIds)
            if (matchedId != null) {
                val bubble = activeBubbles[matchedId]
                if (bubble != null) {
                    if (draftBounds != null && Rect.intersects(draftBounds, bubble.overlayBounds)) {
                        continue
                    }
                    updateBubble(
                        bubble = bubble,
                        targetRect = item.rect,
                        sourceText = item.sourceText,
                        text = item.translatedText,
                        profile = profile,
                        isQuoted = item.isQuoted,
                        maxBottomPx = item.maxBottomPx,
                        scanId = scanId,
                        metrics = metrics,
                        systemDarkMode = systemDarkMode
                    )
                    acceptedTargetRects.add(item.rect)
                }
                updatedIds.add(matchedId)
            } else {
                val view = obtainBubbleViewLocked()
                val layoutSpec = calculateBubbleLayout(
                    targetRect = item.rect,
                    view = view,
                    text = item.translatedText,
                    profile = profile,
                    isQuoted = item.isQuoted,
                    maxBottomPx = item.maxBottomPx,
                    metrics = metrics,
                    systemDarkMode = systemDarkMode
                )
                if (draftBounds != null && Rect.intersects(draftBounds, layoutSpec.overlayBounds)) {
                    recycleBubbleViewLocked(view)
                    continue
                }
                evictCollidingBubblesLocked(
                    targetRect = item.rect,
                    overlayBounds = layoutSpec.overlayBounds,
                    sourceText = item.sourceText,
                    scanId = scanId,
                    density = metrics.density,
                    excludeIds = updatedIds
                )
                // Por qué: Reutilizamos la vista del pool y las dimensiones calculadas para evitar inflación repetida
                val newId = "bubble_${bubbleIdCounter.incrementAndGet()}"
                if (addBubble(
                        targetRect = item.rect,
                        sourceText = item.sourceText,
                        text = item.translatedText,
                        id = newId,
                        preConfiguredView = view,
                        preCalculatedSpec = layoutSpec,
                        profile = profile,
                        isQuoted = item.isQuoted,
                        maxBottomPx = item.maxBottomPx,
                        scanId = scanId,
                        metrics = metrics,
                        systemDarkMode = systemDarkMode
                    )
                ) {
                    acceptedTargetRects.add(item.rect)
                    updatedIds.add(newId)
                }
            }
        }

        // Por qué: Si es sincronización completa, eliminar overlays que ya no están visibles en pantalla.
        // Si no es sincronización completa (ej: renderizado incremental), conservar los válidos recién reconciliados.
        val screenHeight = metrics.heightPx
        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val shouldRemove = if (isFullSync) {
                !updatedIds.contains(entry.key)
            } else {
                entry.value.targetRect.bottom < 0 || entry.value.targetRect.top > screenHeight
            }
            if (shouldRemove) {
                recycleBubbleViewLocked(entry.value.view)
                iterator.remove()
            }
        }
        if (activeBubbles.isEmpty() && isFullSync) {
            detachBubbleCanvasLocked()
        } else {
            revealCanvasIfNeededLocked()
        }
        notifyOverlayPresenceLocked()
    }

    private fun findMatchingBubble(
        targetRect: Rect,
        sourceText: String,
        translatedText: String,
        density: Float,
        claimedIds: Set<String> = emptySet()
    ): String? {
        val maxVerticalDrift = maxVerticalDriftPx(density)
        val maxHorizontalDrift = (20 * density).toInt()

        // 1. Prioridad máxima: mismo texto fuente y proximidad espacial (evita apilar sobre el mismo mensaje)
        var bestId: String? = null
        var minVerticalDist = Int.MAX_VALUE
        for ((id, bubble) in activeBubbles) {
            if (claimedIds.contains(id)) continue
            if (bubble.sourceText == sourceText) {
                val dTop = abs(bubble.targetRect.top - targetRect.top)
                val dLeft = abs(bubble.targetRect.left - targetRect.left)
                if (dTop <= maxVerticalDrift && dLeft <= maxHorizontalDrift && dTop < minVerticalDist) {
                    minVerticalDist = dTop
                    bestId = id
                }
            }
        }
        if (bestId != null) return bestId

        // 2. Coincidencia por texto traducido idéntico y proximidad espacial estricta
        for ((id, bubble) in activeBubbles) {
            if (claimedIds.contains(id)) continue
            if (bubble.text == translatedText) {
                val dTop = abs(bubble.targetRect.top - targetRect.top)
                val dLeft = abs(bubble.targetRect.left - targetRect.left)
                if (dTop <= maxVerticalDrift && dLeft <= maxHorizontalDrift && dTop < minVerticalDist) {
                    minVerticalDist = dTop
                    bestId = id
                }
            }
        }
        if (bestId != null) return bestId

        // 3. Coincidencia espacial estricta de respaldo si el texto difiere ligeramente
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
        sourceText: String,
        text: String,
        id: String,
        preConfiguredView: View? = null,
        preCalculatedSpec: BubbleLayoutSpec? = null,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        isQuoted: Boolean = false,
        maxBottomPx: Int = Int.MAX_VALUE,
        scanId: Long = 0L,
        metrics: DisplayBounds = ScreenMetricsHelper.currentBounds(context),
        systemDarkMode: Boolean = isSystemDarkModeNow()
    ): Boolean {
        val canvas = ensureBubbleCanvasAttachedLocked()
        val view = preConfiguredView ?: obtainBubbleViewLocked()
        if (canvas == null) {
            recycleBubbleViewLocked(view)
            ServiceBridge.lastScanDebug = "${ServiceBridge.lastScanDebug} | wmErr=addCanvasFailed"
            return false
        }
        val layoutSpec = preCalculatedSpec ?: calculateBubbleLayout(
            targetRect = targetRect,
            view = view,
            text = text,
            profile = profile,
            isQuoted = isQuoted,
            maxBottomPx = maxBottomPx,
            metrics = metrics,
            systemDarkMode = systemDarkMode
        )

        positionBubbleViewOnCanvasLocked(canvas, view, layoutSpec)
        if (isCanvasScrollHidden) {
            // Por qué: Cuando el lienzo entero está oculto por scroll, el reveal global anima 0 -> 1 de forma uniforme.
            view.alpha = 1f
        } else {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(110L).start()
        }
        activeBubbles[id] = ActiveBubble(
            view = view,
            sourceText = sourceText,
            text = text,
            targetRect = Rect(targetRect),
            overlayBounds = layoutSpec.overlayBounds,
            isQuoted = isQuoted,
            maxBottomPx = maxBottomPx,
            scanId = scanId
        )
        notifyOverlayPresenceLocked()
        return true
    }

    private fun updateBubble(
        bubble: ActiveBubble,
        targetRect: Rect,
        sourceText: String,
        text: String,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        isQuoted: Boolean = false,
        maxBottomPx: Int = Int.MAX_VALUE,
        scanId: Long = 0L,
        metrics: DisplayBounds = ScreenMetricsHelper.currentBounds(context),
        systemDarkMode: Boolean = isSystemDarkModeNow()
    ) {
        val canvas = ensureBubbleCanvasAttachedLocked() ?: return
        if (bubble.text == text &&
            bubble.sourceText == sourceText &&
            bubble.targetRect == targetRect &&
            bubble.isQuoted == isQuoted &&
            bubble.maxBottomPx == maxBottomPx
        ) {
            bubble.scanId = maxOf(bubble.scanId, scanId)
            return
        }
        val layoutSpec = calculateBubbleLayout(
            targetRect = targetRect,
            view = bubble.view,
            text = text,
            profile = profile,
            isQuoted = isQuoted,
            maxBottomPx = maxBottomPx,
            metrics = metrics,
            systemDarkMode = systemDarkMode
        )
        bubble.overlayBounds = layoutSpec.overlayBounds
        // Por qué: Mantener metadatos y rectángulos actualizados para evitar reapilamiento en frames subsiguientes
        bubble.targetRect = Rect(targetRect)
        bubble.sourceText = sourceText
        bubble.text = text
        bubble.isQuoted = isQuoted
        bubble.maxBottomPx = maxBottomPx
        bubble.scanId = maxOf(bubble.scanId, scanId)

        positionBubbleViewOnCanvasLocked(canvas, bubble.view, layoutSpec)
    }

    private fun calculateBubbleLayout(
        targetRect: Rect,
        view: View,
        text: String,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        isQuoted: Boolean = false,
        maxBottomPx: Int = Int.MAX_VALUE,
        metrics: DisplayBounds = ScreenMetricsHelper.currentBounds(context),
        systemDarkMode: Boolean = isSystemDarkModeNow()
    ): BubbleLayoutSpec {
        val screenWidth = metrics.widthPx
        val density = metrics.density

        // Por qué: Adapta el color y contraste al tema, modo día/noche, si es cita y alineación exacta de grupo
        val isRightSide = isOutgoingBubble(targetRect.left, targetRect.right, screenWidth, profile.alignmentMode)
        val style = resolveBubbleStyle(
            overlayTheme = overlayTheme,
            alignmentMode = profile.alignmentMode,
            isRightSide = isRightSide,
            isWhatsAppApp = profile.id == "whatsapp",
            isSystemDarkMode = systemDarkMode,
            isQuoted = isQuoted
        )
        val holder = holderFor(view)
        if (holder.backgroundRes != style.backgroundRes) {
            view.setBackgroundResource(style.backgroundRes)
            holder.backgroundRes = style.backgroundRes
        }

        val horizPad = (when {
            isQuoted -> 8
            targetRect.width() < (210 * density).toInt() -> 8
            else -> 10
        } * density).toInt()
        val vertPad = ((if (isQuoted) 4 else 6) * density).toInt()
        val paddingChanged = holder.padH != horizPad || holder.padV != vertPad
        if (paddingChanged) {
            view.setPadding(horizPad, vertPad, horizPad, vertPad)
            holder.padH = horizPad
            holder.padV = vertPad
        }

        val posX = targetRect.left
        val posY = targetRect.top
        val bubbleWidth = targetRect.width().coerceAtLeast((24 * density).toInt())

        val textView = holder.textView
        if (holder.textColor != style.textColor) {
            textView.setTextColor(style.textColor)
            holder.textColor = style.textColor
        }
        val baseSizeSp = if (isQuoted) 12.5f else 14f
        val hasBottomLimit = maxBottomPx != Int.MAX_VALUE
        val availableHeight = if (hasBottomLimit) {
            (maxBottomPx - posY).coerceAtLeast((16 * density).toInt())
        } else {
            Int.MAX_VALUE
        }

        val canReuseMeasure = holder.lastText == text &&
            holder.lastWidthPx == bubbleWidth &&
            !paddingChanged &&
            !hasBottomLimit &&
            holder.measuredHeightPx > 0

        val measuredHeightPx = if (canReuseMeasure) {
            holder.measuredHeightPx
        } else {
            if (holder.maxLines != Int.MAX_VALUE) {
                textView.maxLines = Int.MAX_VALUE
                holder.maxLines = Int.MAX_VALUE
            }
            if (holder.ellipsizeEnd) {
                textView.ellipsize = null
                holder.ellipsizeEnd = false
            }
            if (holder.textSizeSp != baseSizeSp) {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSizeSp)
                holder.textSizeSp = baseSizeSp
            }
            if (holder.lastText != text) {
                textView.text = text
                holder.lastText = text
            }

            val widthSpec = View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.EXACTLY)
            val unspecHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(widthSpec, unspecHeight)

            if (hasBottomLimit && view.measuredHeight > availableHeight) {
                val compactSizeSp = if (isQuoted) 11.5f else 12.5f
                if (compactSizeSp < baseSizeSp) {
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, compactSizeSp)
                    holder.textSizeSp = compactSizeSp
                    view.measure(widthSpec, unspecHeight)
                }
                if (view.measuredHeight > availableHeight) {
                    val maxTextHeight = (availableHeight - vertPad * 2).coerceAtLeast(textView.lineHeight.coerceAtLeast(1))
                    val layout = textView.layout
                    val fittingLines = if (layout != null) {
                        var count = 0
                        for (lineIdx in 0 until layout.lineCount) {
                            if (layout.getLineBottom(lineIdx) <= maxTextHeight) {
                                count = lineIdx + 1
                            } else {
                                break
                            }
                        }
                        count.coerceAtLeast(1)
                    } else {
                        (maxTextHeight / textView.lineHeight.coerceAtLeast(1)).coerceAtLeast(1)
                    }
                    textView.maxLines = fittingLines
                    textView.ellipsize = TextUtils.TruncateAt.END
                    holder.maxLines = fittingLines
                    holder.ellipsizeEnd = true
                    view.measure(widthSpec, unspecHeight)
                }
            }
            holder.lastWidthPx = bubbleWidth
            holder.measuredHeightPx = view.measuredHeight
            view.measuredHeight
        }

        // Por qué: Cubre el contenedor original pero nunca invade mensajes inferiores ni el compositor
        val bubbleHeight = if (hasBottomLimit) {
            val clampedTargetHeight = min(targetRect.height(), availableHeight)
            max(clampedTargetHeight, min(measuredHeightPx, availableHeight))
        } else {
            max(targetRect.height(), measuredHeightPx)
        }

        val overlayBounds = Rect(posX, posY, posX + bubbleWidth, posY + bubbleHeight)
        return BubbleLayoutSpec(overlayBounds = overlayBounds, widthPx = bubbleWidth, heightPx = bubbleHeight)
    }

    private var draftOverlayView: View? = null
    @Volatile
    var currentDraftOverlayBounds: Rect? = null
        private set

    private fun animatePillEntrance(view: View, density: Float) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 8f * density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(120L)
            .start()
    }

    private fun applyDraftStyleToViews(view: View, style: DraftPillStyle, isStalePending: Boolean) {
        view.setBackgroundResource(style.containerBgRes)
        val badgeView = view.findViewById<TextView>(R.id.draft_badge)
        val textView = view.findViewById<TextView>(R.id.draft_text)
        val actionBtn = view.findViewById<TextView>(R.id.draft_action_replace)
        val closeBtn = view.findViewById<ImageView>(R.id.draft_action_close)

        badgeView.setBackgroundResource(style.badgeBgRes)
        badgeView.setTextColor(style.badgeTextColor)
        textView.setTextColor(style.bodyTextColor)
        textView.alpha = if (isStalePending) 0.65f else 1.0f
        actionBtn.setBackgroundResource(style.actionBgRes)
        actionBtn.setTextColor(style.actionTextColor)
        closeBtn.setBackgroundResource(style.closeBgRes)
        closeBtn.setColorFilter(style.closeTextColor)
    }

    private fun bindDraftView(
        view: View,
        text: String,
        targetLangCode: String,
        sourceLangCode: String?,
        showReplace: Boolean,
        isStalePending: Boolean,
        onInsertClicked: () -> Unit,
        onDismissClicked: () -> Unit,
        onBadgeClicked: (() -> Unit)? = null
    ) {
        val style = resolveDraftPillStyle(
            overlayTheme = overlayTheme,
            isSystemDarkMode = isSystemDarkModeNow(),
            isUndoMode = false,
            isStalePending = isStalePending
        )
        applyDraftStyleToViews(view, style, isStalePending = isStalePending)

        val textView = view.findViewById<TextView>(R.id.draft_text)
        val badgeView = view.findViewById<TextView>(R.id.draft_badge)
        val replaceBtn = view.findViewById<TextView>(R.id.draft_action_replace)

        textView.text = text
        badgeView.text = if (!sourceLangCode.isNullOrBlank()) {
            "$sourceLangCode → $targetLangCode"
        } else {
            "⇄ $targetLangCode"
        }
        replaceBtn.setText(R.string.draft_action_replace)
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
        // la dirección de traducción (ej. cambiar de EL → RO a RO → EL) cuando se prefiera invertirla.
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
        screenHeight: Int,
        density: Float,
        isUndoMode: Boolean = false
    ): DraftLayout {
        val horizMargin = (12 * density).toInt()
        val maxWidth = min(
            (screenWidth - horizMargin * 2).coerceAtLeast((180 * density).toInt()),
            (420 * density).toInt()
        )
        val headerRow = view.findViewById<View>(R.id.draft_header_row)
        val spacer = view.findViewById<View>(R.id.draft_header_spacer)

        // Pasada 1: Mide el ancho intrínseco compacto evitando que el espaciador flexible expanda píldoras cortas.
        if (headerRow != null && spacer != null) {
            headerRow.layoutParams = headerRow.layoutParams.apply {
                width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            (spacer.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
                width = (12 * density).toInt()
                weight = 0f
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val initialLayout = resolveDraftBounds(
            measuredWidth = view.measuredWidth,
            measuredHeight = view.measuredHeight,
            anchorTop = inputRect.top,
            anchorLeft = inputRect.left,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
            isUndoMode = isUndoMode
        )

        // Pasada 2: Fija el ancho acotado exacto y alinea los botones en los extremos de la cabecera.
        if (headerRow != null && spacer != null) {
            headerRow.layoutParams = headerRow.layoutParams.apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
            (spacer.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
                width = 0
                weight = 1f
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(initialLayout.bubbleWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return resolveDraftBounds(
            measuredWidth = initialLayout.bubbleWidth,
            measuredHeight = view.measuredHeight,
            anchorTop = inputRect.top,
            anchorLeft = inputRect.left,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
            isUndoMode = isUndoMode
        )
    }

    private fun attachOrUpdateDraftWindow(
        view: View,
        layout: DraftLayout,
        label: String
    ) {
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
            if (addWindowViewWithFallback(view, params, label)) {
                draftOverlayView = view
                animatePillEntrance(view, context.resources.displayMetrics.density)
                notifyOverlayPresenceLocked()
            }
        } else {
            val params = view.layoutParams as WindowManager.LayoutParams
            if (params.width != layout.bubbleWidth ||
                params.height != layout.bubbleHeight ||
                params.x != layout.posX ||
                params.y != layout.posY
            ) {
                params.width = layout.bubbleWidth
                params.height = layout.bubbleHeight
                params.x = layout.posX
                params.y = layout.posY
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                    // Ignorar errores transitorios de WindowManager
                }
            }
        }
    }

    @Synchronized
    private fun purgeCollidingBubbles(overlayBounds: Rect, inputRect: Rect) {
        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (Rect.intersects(overlayBounds, entry.value.overlayBounds) ||
                (inputRect.width() > 0 && Rect.intersects(inputRect, entry.value.overlayBounds))
            ) {
                recycleBubbleViewLocked(entry.value.view)
                iterator.remove()
            }
        }
    }

    fun updateDraftOverlay(
        inputRect: Rect,
        text: String,
        targetLangCode: String = "RO",
        sourceLangCode: String? = null,
        showReplace: Boolean = true,
        isStalePending: Boolean = false,
        onInsertClicked: () -> Unit,
        onDismissClicked: () -> Unit,
        onBadgeClicked: (() -> Unit)? = null
    ) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post {
                updateDraftOverlay(
                    inputRect, text, targetLangCode, sourceLangCode, showReplace, isStalePending,
                    onInsertClicked, onDismissClicked, onBadgeClicked
                )
            }
            return
        }

        if (text.isBlank()) {
            removeDraftOverlay()
            return
        }

        val metrics = ScreenMetricsHelper.currentBounds(context)
        val screenWidth = metrics.widthPx
        val screenHeight = metrics.heightPx
        val density = metrics.density

        // Por qué: Si la coordenada superior del campo de texto no es válida o está fuera de la zona inferior normal,
        // no se dibuja la píldora para evitar que flote sobre la barra de estado o encabezados.
        if (inputRect.top <= (100 * density).toInt()) {
            removeDraftOverlay()
            return
        }

        synchronized(this) {
            val view = draftOverlayView ?: inflater.inflate(R.layout.draft_preview_overlay, null)
            bindDraftView(
                view = view,
                text = text,
                targetLangCode = targetLangCode,
                sourceLangCode = sourceLangCode,
                showReplace = showReplace,
                isStalePending = isStalePending,
                onInsertClicked = onInsertClicked,
                onDismissClicked = onDismissClicked,
                onBadgeClicked = onBadgeClicked
            )

            val layout = computeDraftLayout(
                view = view,
                inputRect = inputRect,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                density = density,
                isUndoMode = false
            )
            currentDraftOverlayBounds = layout.overlayBounds

            purgeCollidingBubbles(layout.overlayBounds, inputRect)
            attachOrUpdateDraftWindow(view, layout, "draft overlay")
        }
    }

    fun showDraftUndoOverlay(
        inputRect: Rect,
        onUndoClicked: () -> Unit,
        onDismissClicked: () -> Unit
    ) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { showDraftUndoOverlay(inputRect, onUndoClicked, onDismissClicked) }
            return
        }

        val metrics = ScreenMetricsHelper.currentBounds(context)
        val screenWidth = metrics.widthPx
        val screenHeight = metrics.heightPx
        val density = metrics.density
        val fallbackBottom = (screenHeight - (120 * density).toInt()).coerceAtLeast((220 * density).toInt())
        val anchorRect = if (inputRect.top > (100 * density).toInt()) {
            inputRect
        } else {
            Rect((12 * density).toInt(), fallbackBottom, screenWidth - (12 * density).toInt(), fallbackBottom + (48 * density).toInt())
        }

        synchronized(this) {
            val view = draftOverlayView ?: inflater.inflate(R.layout.draft_preview_overlay, null)
            val style = resolveDraftPillStyle(
                overlayTheme = overlayTheme,
                isSystemDarkMode = isSystemDarkModeNow(),
                isUndoMode = true,
                isStalePending = false
            )
            applyDraftStyleToViews(view, style, isStalePending = false)

            val textView = view.findViewById<TextView>(R.id.draft_text)
            val badgeView = view.findViewById<TextView>(R.id.draft_badge)
            val actionBtn = view.findViewById<TextView>(R.id.draft_action_replace)

            badgeView.setText(R.string.draft_undo_applied_badge)
            badgeView.setOnClickListener(null)
            textView.setText(R.string.draft_undo_caption)
            actionBtn.setText(R.string.draft_action_undo)
            actionBtn.visibility = View.VISIBLE

            view.findViewById<View>(R.id.draft_container).setOnClickListener { onUndoClicked() }
            actionBtn.setOnClickListener { onUndoClicked() }
            view.findViewById<View>(R.id.draft_action_close).setOnClickListener { onDismissClicked() }

            val layout = computeDraftLayout(
                view = view,
                inputRect = anchorRect,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                density = density,
                isUndoMode = true
            )
            currentDraftOverlayBounds = layout.overlayBounds
            attachOrUpdateDraftWindow(view, layout, "draft undo overlay")
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
            notifyOverlayPresenceLocked()
        }
    }

    @Synchronized
    fun removeAllMessageOverlays() {
        for (bubble in activeBubbles.values) {
            recycleBubbleViewLocked(bubble.view)
        }
        activeBubbles.clear()
        detachBubbleCanvasLocked()
        notifyOverlayPresenceLocked()
    }

    // Por qué: Reconcilia burbujas activas 1 a 1 contra los ranurados actuales verificando texto fuente
    // y proximidad horizontal/vertical estricta; elimina de inmediato cualquier globo huérfano o desplazado.
    @Synchronized
    fun reconcileActiveBubbles(expectedSlots: List<ExpectedBubbleSlot>) {
        if (activeBubbles.isEmpty()) return
        val density = ScreenMetricsHelper.currentBounds(context).density
        val maxVert = (16 * density).toInt()
        val maxHoriz = (20 * density).toInt()
        val claimedSlots = BooleanArray(expectedSlots.size)

        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val bubble = entry.value
            val bubbleRect = bubble.targetRect
            var matchedIndex = -1
            for (i in expectedSlots.indices) {
                if (claimedSlots[i]) continue
                val slot = expectedSlots[i]
                if (bubble.sourceText == slot.sourceText &&
                    abs(slot.rect.top - bubbleRect.top) <= maxVert &&
                    abs(slot.rect.bottom - bubbleRect.bottom) <= maxVert &&
                    abs(slot.rect.left - bubbleRect.left) <= maxHoriz
                ) {
                    matchedIndex = i
                    break
                }
            }
            if (matchedIndex >= 0) {
                claimedSlots[matchedIndex] = true
            } else {
                recycleBubbleViewLocked(bubble.view)
                iterator.remove()
            }
        }
        notifyOverlayPresenceLocked()
    }

    @Synchronized
    fun removeBubbleForSlot(rect: Rect, sourceText: String) {
        if (activeBubbles.isEmpty()) return
        val density = ScreenMetricsHelper.currentBounds(context).density
        val maxVert = (16 * density).toInt()
        val maxHoriz = (20 * density).toInt()
        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val b = entry.value
            if (b.sourceText == sourceText &&
                abs(b.targetRect.top - rect.top) <= maxVert &&
                abs(b.targetRect.left - rect.left) <= maxHoriz
            ) {
                recycleBubbleViewLocked(b.view)
                iterator.remove()
            }
        }
        notifyOverlayPresenceLocked()
    }

    // Por qué: Permite verificar de forma atómica si hay alguna superposición activa dibujada en pantalla
    // para que el vigilante en segundo plano pueda purgar la pantalla inmediatamente al salir del chat activo.
    @Synchronized
    fun hasOverlays(): Boolean {
        return activeBubbles.isNotEmpty() || bubbleCanvas != null || draftOverlayView != null
    }

    @Synchronized
    fun removeAllOverlays() {
        removeAllMessageOverlays()
        removeDraftOverlay()
    }
}
