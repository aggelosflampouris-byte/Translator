package com.example.translator

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.text.TextUtils
import android.util.TypedValue
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
                    badgeTextColor = if (isUndoMode) 0xFF047857.toInt() else 0xFF059669.toInt(),
                    bodyTextColor = if (isUndoMode || isStalePending) 0xFF475569.toInt() else TEXT_COLOR_LIGHT_SURFACE,
                    actionBgRes = if (isUndoMode) R.drawable.bg_draft_undo_btn_light else R.drawable.bg_draft_action_btn_light,
                    actionTextColor = if (isUndoMode) 0xFF047857.toInt() else 0xFFFFFFFF.toInt(),
                    closeBgRes = R.drawable.bg_draft_close_btn_light,
                    closeTextColor = 0xFF64748B.toInt()
                )
            } else {
                DraftPillStyle(
                    containerBgRes = R.drawable.bg_draft_bubble_dark,
                    badgeBgRes = R.drawable.bg_draft_badge_dark,
                    badgeTextColor = 0xFF34D399.toInt(),
                    bodyTextColor = if (isUndoMode || isStalePending) 0xFFCBD5E1.toInt() else 0xFFF8FAFC.toInt(),
                    actionBgRes = if (isUndoMode) R.drawable.bg_draft_undo_btn_dark else R.drawable.bg_draft_action_btn,
                    actionTextColor = if (isUndoMode) 0xFF34D399.toInt() else 0xFFFFFFFF.toInt(),
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
        val current = activeBubbles.isNotEmpty() || singlePillView != null || draftOverlayView != null
        if (current != lastReportedHasOverlays) {
            lastReportedHasOverlays = current
            onOverlaysActiveChanged?.invoke(current)
        }
    }

    fun setOverlayTheme(theme: String) {
        overlayTheme = theme
    }

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
        excludeIds: Set<String> = emptySet()
    ) {
        val density = ScreenMetricsHelper.currentBounds(context).density
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
                try {
                    windowManager.removeView(existing.view)
                } catch (_: Exception) {
                    // Ignorar si ya fue removida
                }
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
        if (item.translatedText.isBlank()) return
        val draftBounds = currentDraftOverlayBounds
        if (draftBounds != null && Rect.intersects(draftBounds, item.rect)) return

        val matchedId = findMatchingBubble(item.rect, item.sourceText, item.translatedText)
        if (matchedId != null) {
            val bubble = activeBubbles[matchedId]
            if (bubble != null) {
                if (draftBounds != null && Rect.intersects(draftBounds, bubble.overlayBounds)) return
                updateBubble(
                    bubble = bubble,
                    targetRect = item.rect,
                    sourceText = item.sourceText,
                    text = item.translatedText,
                    profile = profile,
                    isQuoted = item.isQuoted,
                    maxBottomPx = item.maxBottomPx,
                    scanId = scanId
                )
                evictCollidingBubblesLocked(
                    targetRect = item.rect,
                    overlayBounds = bubble.overlayBounds,
                    sourceText = item.sourceText,
                    scanId = scanId,
                    excludeIds = setOf(matchedId)
                )
                return
            }
        }

        val view = inflater.inflate(R.layout.bubble_overlay, null)
        val (params, overlayBounds) = calculateBubbleLayout(
            targetRect = item.rect,
            view = view,
            text = item.translatedText,
            profile = profile,
            isQuoted = item.isQuoted,
            maxBottomPx = item.maxBottomPx
        )
        if (draftBounds != null && Rect.intersects(draftBounds, overlayBounds)) return
        evictCollidingBubblesLocked(
            targetRect = item.rect,
            overlayBounds = overlayBounds,
            sourceText = item.sourceText,
            scanId = scanId
        )
        val newId = "bubble_${bubbleIdCounter.incrementAndGet()}"
        addBubble(
            targetRect = item.rect,
            sourceText = item.sourceText,
            text = item.translatedText,
            id = newId,
            preInflatedView = view,
            preCalculatedParams = params,
            preCalculatedBounds = overlayBounds,
            profile = profile,
            isQuoted = item.isQuoted,
            maxBottomPx = item.maxBottomPx,
            scanId = scanId
        )
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

            val matchedId = findMatchingBubble(item.rect, item.sourceText, item.translatedText, updatedIds)
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
                        scanId = scanId
                    )
                    acceptedTargetRects.add(item.rect)
                }
                updatedIds.add(matchedId)
            } else {
                val view = inflater.inflate(R.layout.bubble_overlay, null)
                val (params, overlayBounds) = calculateBubbleLayout(
                    targetRect = item.rect,
                    view = view,
                    text = item.translatedText,
                    profile = profile,
                    isQuoted = item.isQuoted,
                    maxBottomPx = item.maxBottomPx
                )
                if (draftBounds != null && Rect.intersects(draftBounds, overlayBounds)) {
                    continue
                }
                evictCollidingBubblesLocked(
                    targetRect = item.rect,
                    overlayBounds = overlayBounds,
                    sourceText = item.sourceText,
                    scanId = scanId,
                    excludeIds = updatedIds
                )
                acceptedTargetRects.add(item.rect)
                // Por qué: Reutilizamos la vista ya inflada y las dimensiones calculadas para evitar doble inflación
                val newId = "bubble_${bubbleIdCounter.incrementAndGet()}"
                addBubble(
                    targetRect = item.rect,
                    sourceText = item.sourceText,
                    text = item.translatedText,
                    id = newId,
                    preInflatedView = view,
                    preCalculatedParams = params,
                    preCalculatedBounds = overlayBounds,
                    profile = profile,
                    isQuoted = item.isQuoted,
                    maxBottomPx = item.maxBottomPx,
                    scanId = scanId
                )
                updatedIds.add(newId)
            }
        }

        // Por qué: Si es sincronización completa, eliminar overlays que ya no están visibles en pantalla.
        // Si no es sincronización completa (ej: renderizado incremental), conservar los válidos recién reconciliados.
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
                } catch (_: Exception) {
                    // Ignorar si la vista ya fue retirada
                }
                iterator.remove()
            }
        }
        notifyOverlayPresenceLocked()
    }

    private fun findMatchingBubble(
        targetRect: Rect,
        sourceText: String,
        translatedText: String,
        claimedIds: Set<String> = emptySet()
    ): String? {
        val density = ScreenMetricsHelper.currentBounds(context).density
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
        preInflatedView: View? = null,
        preCalculatedParams: WindowManager.LayoutParams? = null,
        preCalculatedBounds: Rect? = null,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        isQuoted: Boolean = false,
        maxBottomPx: Int = Int.MAX_VALUE,
        scanId: Long = 0L
    ) {
        val view = preInflatedView ?: inflater.inflate(R.layout.bubble_overlay, null)
        val (params, overlayBounds) = if (preCalculatedParams != null && preCalculatedBounds != null) {
            Pair(preCalculatedParams, preCalculatedBounds)
        } else {
            calculateBubbleLayout(targetRect, view, text, profile, isQuoted, maxBottomPx)
        }

        view.alpha = 0f
        if (addWindowViewWithFallback(view, params, "bubble $id")) {
            view.animate().alpha(1f).setDuration(150).start()
            activeBubbles[id] = ActiveBubble(
                view = view,
                sourceText = sourceText,
                text = text,
                targetRect = Rect(targetRect),
                overlayBounds = overlayBounds,
                isQuoted = isQuoted,
                maxBottomPx = maxBottomPx,
                scanId = scanId
            )
            notifyOverlayPresenceLocked()
        } else {
            ServiceBridge.lastScanDebug = "${ServiceBridge.lastScanDebug} | wmErr=addBubbleFailed"
        }
    }

    private fun updateBubble(
        bubble: ActiveBubble,
        targetRect: Rect,
        sourceText: String,
        text: String,
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        isQuoted: Boolean = false,
        maxBottomPx: Int = Int.MAX_VALUE,
        scanId: Long = 0L
    ) {
        if (bubble.text == text &&
            bubble.sourceText == sourceText &&
            bubble.targetRect == targetRect &&
            bubble.isQuoted == isQuoted &&
            bubble.maxBottomPx == maxBottomPx
        ) {
            bubble.scanId = maxOf(bubble.scanId, scanId)
            return
        }
        val (params, overlayBounds) = calculateBubbleLayout(
            targetRect = targetRect,
            view = bubble.view,
            text = text,
            profile = profile,
            isQuoted = isQuoted,
            maxBottomPx = maxBottomPx
        )
        bubble.overlayBounds = overlayBounds
        // Por qué: Mantener metadatos y rectángulos actualizados para evitar reapilamiento en frames subsiguientes
        bubble.targetRect = Rect(targetRect)
        bubble.sourceText = sourceText
        bubble.text = text
        bubble.isQuoted = isQuoted
        bubble.maxBottomPx = maxBottomPx
        bubble.scanId = maxOf(bubble.scanId, scanId)

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
        profile: ChatAppProfile = TargetAppCatalog.GENERIC_DEFAULT_PROFILE,
        isQuoted: Boolean = false,
        maxBottomPx: Int = Int.MAX_VALUE
    ): Pair<WindowManager.LayoutParams, Rect> {
        val metrics = ScreenMetricsHelper.currentBounds(context)
        val screenWidth = metrics.widthPx
        val density = metrics.density

        // Por qué: Adapta el color y contraste al tema, modo día/noche, si es cita y alineación exacta de grupo
        val isRightSide = isOutgoingBubble(targetRect.left, targetRect.right, screenWidth, profile.alignmentMode)
        val style = resolveBubbleStyle(
            overlayTheme = overlayTheme,
            alignmentMode = profile.alignmentMode,
            isRightSide = isRightSide,
            isWhatsAppApp = profile.id == "whatsapp",
            isSystemDarkMode = isSystemDarkModeNow(),
            isQuoted = isQuoted
        )
        view.setBackgroundResource(style.backgroundRes)

        val horizPad = ((if (isQuoted) 8 else 12) * density).toInt()
        val vertPad = ((if (isQuoted) 4 else 7) * density).toInt()
        view.setPadding(horizPad, vertPad, horizPad, vertPad)

        val posX = targetRect.left
        val posY = targetRect.top
        val bubbleWidth = targetRect.width().coerceAtLeast((24 * density).toInt())

        val textView = view.findViewById<TextView>(R.id.translated_text)
        textView.setTextColor(style.textColor)
        textView.maxLines = Int.MAX_VALUE
        textView.ellipsize = null
        val baseSizeSp = if (isQuoted) 12.5f else 14f
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSizeSp)
        textView.text = text

        val widthSpec = View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.EXACTLY)
        val unspecHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, unspecHeight)

        val hasBottomLimit = maxBottomPx != Int.MAX_VALUE
        val availableHeight = if (hasBottomLimit) {
            (maxBottomPx - posY).coerceAtLeast((16 * density).toInt())
        } else {
            Int.MAX_VALUE
        }

        if (hasBottomLimit && view.measuredHeight > availableHeight) {
            val compactSizeSp = if (isQuoted) 11.5f else 12.5f
            if (compactSizeSp < baseSizeSp) {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, compactSizeSp)
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
                view.measure(widthSpec, unspecHeight)
            }
        }

        // Por qué: Cubre el contenedor original pero nunca invade mensajes inferiores ni el compositor
        val bubbleHeight = if (hasBottomLimit) {
            val clampedTargetHeight = min(targetRect.height(), availableHeight)
            max(clampedTargetHeight, min(view.measuredHeight, availableHeight))
        } else {
            max(targetRect.height(), view.measuredHeight)
        }

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

    private fun applyDraftStyleToViews(view: View, style: DraftPillStyle, isStalePending: Boolean) {
        view.setBackgroundResource(style.containerBgRes)
        val badgeView = view.findViewById<TextView>(R.id.draft_badge)
        val textView = view.findViewById<TextView>(R.id.draft_text)
        val actionBtn = view.findViewById<TextView>(R.id.draft_action_replace)
        val closeBtn = view.findViewById<TextView>(R.id.draft_action_close)

        badgeView.setBackgroundResource(style.badgeBgRes)
        badgeView.setTextColor(style.badgeTextColor)
        textView.setTextColor(style.bodyTextColor)
        textView.alpha = if (isStalePending) 0.65f else 1.0f
        actionBtn.setBackgroundResource(style.actionBgRes)
        actionBtn.setTextColor(style.actionTextColor)
        closeBtn.setBackgroundResource(style.closeBgRes)
        closeBtn.setTextColor(style.closeTextColor)
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
                notifyOverlayPresenceLocked()
            }
        } else {
            val params = view.layoutParams as WindowManager.LayoutParams
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
        originalText: String? = null,
        onDismiss: () -> Unit = {}
    ) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { showSingleMessagePill(targetRect, translatedText, targetLangCode, originalText, onDismiss) }
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
            val style = resolveDraftPillStyle(
                overlayTheme = overlayTheme,
                isSystemDarkMode = isSystemDarkModeNow(),
                isUndoMode = false,
                isStalePending = false
            )
            view.setBackgroundResource(style.containerBgRes)

            val badgeView = view.findViewById<TextView>(R.id.pill_badge)
            val textView = view.findViewById<TextView>(R.id.pill_translated_text)
            val peekBtn = view.findViewById<TextView>(R.id.pill_btn_peek)
            val copyBtn = view.findViewById<TextView>(R.id.pill_btn_copy)
            val closeBtn = view.findViewById<TextView>(R.id.pill_btn_close)

            badgeView.setBackgroundResource(style.badgeBgRes)
            badgeView.setTextColor(style.badgeTextColor)
            peekBtn.setBackgroundResource(style.badgeBgRes)
            peekBtn.setTextColor(style.closeTextColor)
            copyBtn.setBackgroundResource(style.badgeBgRes)
            copyBtn.setTextColor(style.closeTextColor)
            closeBtn.setBackgroundResource(style.closeBgRes)
            closeBtn.setTextColor(style.closeTextColor)
            textView.setTextColor(style.bodyTextColor)

            val canPeekOriginal = !originalText.isNullOrBlank() && originalText != translatedText
            var showingOriginal = false

            fun renderPillContent() {
                if (showingOriginal && canPeekOriginal) {
                    badgeView.text = "ORIG"
                    textView.text = originalText
                    peekBtn.text = "Translated"
                } else {
                    badgeView.text = "⇄ $targetLangCode"
                    textView.text = translatedText
                    peekBtn.text = "Original"
                }
            }

            peekBtn.visibility = if (canPeekOriginal) View.VISIBLE else View.GONE
            renderPillContent()

            val maxWidth = (screenWidth * 0.88f).toInt()
            val params = (view.layoutParams as? WindowManager.LayoutParams) ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            fun measureAndComputePosition() {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val pillWidth = view.measuredWidth.coerceAtLeast((180 * density).toInt())
                val pillHeight = view.measuredHeight
                params.width = pillWidth
                params.height = pillHeight
                params.x = targetRect.left.coerceIn(
                    (12 * density).toInt(),
                    (screenWidth - pillWidth - (12 * density).toInt()).coerceAtLeast((12 * density).toInt())
                )
                params.y = if (targetRect.top - pillHeight - (8 * density).toInt() > (48 * density).toInt()) {
                    targetRect.top - pillHeight - (8 * density).toInt()
                } else {
                    targetRect.bottom + (8 * density).toInt()
                }
            }

            measureAndComputePosition()

            peekBtn.setOnClickListener {
                if (!canPeekOriginal) return@setOnClickListener
                showingOriginal = !showingOriginal
                renderPillContent()
                measureAndComputePosition()
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                    // Ignorar si ya fue cerrada
                }
            }

            copyBtn.setOnClickListener {
                val activeText = if (showingOriginal && canPeekOriginal) originalText else translatedText
                ClipboardHelper.copySensitiveText(context, "Translation", activeText)
                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            }

            closeBtn.setOnClickListener {
                removeSingleMessagePill()
                onDismiss()
            }

            if (singlePillView == null) {
                if (addWindowViewWithFallback(view, params, "single pill overlay")) {
                    singlePillView = view
                    notifyOverlayPresenceLocked()
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
        notifyOverlayPresenceLocked()
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
                try {
                    windowManager.removeView(bubble.view)
                } catch (_: Exception) {
                    // Ignorar si ya fue removida
                }
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
                try {
                    windowManager.removeView(b.view)
                } catch (_: Exception) {
                    // Ignorar si ya fue removida
                }
                iterator.remove()
            }
        }
        notifyOverlayPresenceLocked()
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
