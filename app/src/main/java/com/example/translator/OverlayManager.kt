package com.example.translator

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)

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
    fun updateOverlays(results: List<Pair<Rect?, String>>) {
        val updatedIds = mutableSetOf<String>()

        val draftBounds = currentDraftOverlayBounds
        val acceptedBoundsInBatch = mutableListOf<Rect>()

        for (item in results) {
            val rect = item.first ?: continue
            val translatedText = item.second
            if (translatedText.isBlank()) continue

            // Never render message translation bubbles that overlap the active draft action pill or input bar
            if (draftBounds != null && Rect.intersects(draftBounds, rect)) {
                continue
            }

            // Never render message translation bubbles that overlap already placed bubbles in this frame
            var overlapsBatch = false
            for (accepted in acceptedBoundsInBatch) {
                if (Rect.intersects(accepted, rect)) {
                    val intersection = Rect()
                    if (intersection.setIntersect(accepted, rect)) {
                        val intersectArea = intersection.width().toLong() * intersection.height()
                        val minArea = Math.min(
                            accepted.width().toLong() * accepted.height(),
                            rect.width().toLong() * rect.height()
                        )
                        if (minArea > 0 && intersectArea > minArea * 0.20f) {
                            overlapsBatch = true
                            break
                        }
                    }
                }
            }
            if (overlapsBatch) {
                continue
            }

            val mainBubbleBounds = FloatingBubbleService.currentBubbleBounds
            if (mainBubbleBounds != null && Rect.intersects(mainBubbleBounds, rect)) {
                // If targetRect directly overlaps main floating bubble, avoid placing bubble over it
                val screenWidth = context.resources.displayMetrics.widthPixels
                if (mainBubbleBounds.left > screenWidth / 2 && rect.left >= mainBubbleBounds.left) {
                    continue
                }
            }

            val matchedId = findMatchingBubble(rect, translatedText)
            if (matchedId != null) {
                // Update existing bubble
                val bubble = activeBubbles[matchedId]
                if (bubble != null) {
                    if (draftBounds != null && Rect.intersects(draftBounds, bubble.overlayBounds)) {
                        continue
                    }
                    if (mainBubbleBounds != null && Rect.intersects(mainBubbleBounds, bubble.overlayBounds)) {
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
                if (mainBubbleBounds != null && Rect.intersects(mainBubbleBounds, overlayBounds)) {
                    continue
                }
                acceptedBoundsInBatch.add(overlayBounds)
                // New bubble
                val newId = System.currentTimeMillis().toString() + "_" + activeBubbles.size
                addBubble(rect, translatedText, newId)
                updatedIds.add(newId)
            }
        }

        // Remove old overlays that are no longer present on screen
        val iterator = activeBubbles.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!updatedIds.contains(entry.key)) {
                try {
                    windowManager.removeView(entry.value.view)
                } catch (e: Exception) {
                    // Ignore if already removed
                }
                iterator.remove()
            }
        }
    }

    private fun findMatchingBubble(targetRect: Rect, text: String): String? {
        val density = context.resources.displayMetrics.density

        // 1. Text match: If an active bubble already displays this exact translation,
        // it is the same message moving during scroll - reuse its view!
        var bestId: String? = null
        var minVerticalDist = Int.MAX_VALUE

        for ((id, bubble) in activeBubbles) {
            if (bubble.text == text) {
                val dTop = Math.abs(bubble.targetRect.top - targetRect.top)
                if (dTop < minVerticalDist) {
                    minVerticalDist = dTop
                    bestId = id
                }
            }
        }

        if (bestId != null && minVerticalDist < (600 * density).toInt()) {
            return bestId
        }

        // 2. Spatial match fallback
        val proximityThreshold = (15 * density).toInt()
        for ((id, bubble) in activeBubbles) {
            val dTop = Math.abs(bubble.targetRect.top - targetRect.top)
            val dLeft = Math.abs(bubble.targetRect.left - targetRect.left)
            if (dTop < proximityThreshold && dLeft < proximityThreshold) {
                return id
            }
        }
        return null
    }

    private fun addBubble(targetRect: Rect, text: String, id: String) {
        val view = inflater.inflate(R.layout.bubble_overlay, null)
        val (params, overlayBounds) = calculateBubbleLayout(targetRect, view, text)

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

        params.type = (bubble.view.layoutParams as? WindowManager.LayoutParams)?.type
            ?: WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        try {
            windowManager.updateViewLayout(bubble.view, params)
        } catch (e: Exception) {
            android.util.Log.e("Translator", "OverlayManager failed to updateViewLayout", e)
        }
    }

    private fun calculateBubbleLayout(targetRect: Rect, view: View, text: String): Pair<WindowManager.LayoutParams, Rect> {
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels

        // Fit directly ON the incoming message bubble
        var bubbleWidth = targetRect.width()
        var posX = targetRect.left
        val posY = targetRect.top

        // Avoid overlapping the floating translator main bubble
        val mainBubbleBounds = FloatingBubbleService.currentBubbleBounds
        if (mainBubbleBounds != null) {
            val safetyMargin = (8 * density).toInt()
            val reservedRect = Rect(
                mainBubbleBounds.left - safetyMargin,
                mainBubbleBounds.top - safetyMargin,
                mainBubbleBounds.right + safetyMargin,
                mainBubbleBounds.bottom + safetyMargin
            )

            // Check if vertical ranges overlap
            val verticalOverlap = (posY < reservedRect.bottom && posY + targetRect.height() > reservedRect.top)
            if (verticalOverlap) {
                if (mainBubbleBounds.left > screenWidth / 2) {
                    val maxRight = reservedRect.left
                    if (posX + bubbleWidth > maxRight) {
                        val adjustedWidth = maxRight - posX
                        if (adjustedWidth >= (60 * density).toInt()) {
                            bubbleWidth = adjustedWidth
                        }
                    }
                } else {
                    val minLeft = reservedRect.right
                    if (posX < minLeft) {
                        val adjustedWidth = (posX + bubbleWidth) - minLeft
                        if (adjustedWidth >= (60 * density).toInt()) {
                            posX = minLeft
                            bubbleWidth = adjustedWidth
                        }
                    }
                }
            }
        }

        view.layoutParams = android.view.ViewGroup.LayoutParams(
            bubbleWidth,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val textView = view.findViewById<TextView>(R.id.translated_text)
        textView.text = text
        textView.maxWidth = bubbleWidth

        view.measure(
            View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        // Calculate exact multi-line text height using StaticLayout
        val textPaint = textView.paint
        val horizontalPadding = view.paddingLeft + view.paddingRight + textView.paddingLeft + textView.paddingRight
        val availableTextWidth = (bubbleWidth - horizontalPadding).coerceAtLeast((60 * density).toInt())
        val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.text.StaticLayout.Builder.obtain(text, 0, text.length, textPaint, availableTextWidth)
                .setLineSpacing(textView.lineSpacingExtra, textView.lineSpacingMultiplier)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.text.StaticLayout(
                text,
                textPaint,
                availableTextWidth,
                android.text.Layout.Alignment.ALIGN_NORMAL,
                textView.lineSpacingMultiplier,
                textView.lineSpacingExtra,
                false
            )
        }

        val verticalPadding = view.paddingTop + view.paddingBottom + textView.paddingTop + textView.paddingBottom
        val trueContentHeight = staticLayout.height + verticalPadding

        // Height fits on the incoming message text box (at least matching the original bubble height)
        val bubbleHeight = Math.max(targetRect.height(), trueContentHeight)

        val params = WindowManager.LayoutParams(
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
            x = posX
            y = posY
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val overlayBounds = Rect(posX, posY, posX + bubbleWidth, posY + bubbleHeight)
        return Pair(params, overlayBounds)
    }

    private var draftOverlayView: View? = null
    private var currentDraftText: String? = null
    var currentDraftOverlayBounds: Rect? = null
        private set

    fun updateDraftOverlay(
        inputRect: Rect,
        text: String,
        targetLangCode: String = "RO",
        showReplace: Boolean = true,
        onInsertClicked: () -> Unit,
        onDismissClicked: () -> Unit
    ) {
        if (text.isBlank()) {
            removeDraftOverlay()
            return
        }

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val density = metrics.density

        if (draftOverlayView == null) {
            val view = inflater.inflate(R.layout.draft_preview_overlay, null)
            val textView = view.findViewById<TextView>(R.id.draft_text)
            val badgeView = view.findViewById<TextView>(R.id.draft_badge)
            val labelView = view.findViewById<TextView>(R.id.draft_label)
            val replaceBtn = view.findViewById<View>(R.id.draft_action_replace)

            textView.text = text
            textView.setTextColor(if (showReplace) 0xFFF8FAFC.toInt() else 0xFF94A3B8.toInt())
            badgeView.text = "⇄ $targetLangCode"
            labelView.text = if (showReplace) "Tap to replace in chat" else "Translate to $targetLangCode"
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
            currentDraftOverlayBounds = overlayBounds

            // Immediately purge any message bubbles that collide with the active draft action pill or input bar
            val iterator = activeBubbles.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (Rect.intersects(overlayBounds, entry.value.overlayBounds) ||
                    (inputRect.width() > 0 && Rect.intersects(inputRect, entry.value.overlayBounds))) {
                    try {
                        windowManager.removeView(entry.value.view)
                    } catch (e: Exception) {
                        // Ignore
                    }
                    iterator.remove()
                }
            }

            val params = WindowManager.LayoutParams(
                bubbleWidth,
                bubbleHeight,
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
            val view = draftOverlayView ?: return
            val textView = view.findViewById<TextView>(R.id.draft_text)
            val badgeView = view.findViewById<TextView>(R.id.draft_badge)
            val labelView = view.findViewById<TextView>(R.id.draft_label)
            val replaceBtn = view.findViewById<View>(R.id.draft_action_replace)

            textView.text = text
            textView.setTextColor(if (showReplace) 0xFFF8FAFC.toInt() else 0xFF94A3B8.toInt())
            badgeView.text = "⇄ $targetLangCode"
            labelView.text = if (showReplace) "Tap to replace in chat" else "Translate to $targetLangCode"
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
            currentDraftOverlayBounds = overlayBounds

            // Immediately purge any message bubbles that collide with the active draft action pill or input bar
            val iterator = activeBubbles.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (Rect.intersects(overlayBounds, entry.value.overlayBounds) ||
                    (inputRect.width() > 0 && Rect.intersects(inputRect, entry.value.overlayBounds))) {
                    try {
                        windowManager.removeView(entry.value.view)
                    } catch (e: Exception) {
                        // Ignore
                    }
                    iterator.remove()
                }
            }

            val params = view.layoutParams as WindowManager.LayoutParams
            params.width = bubbleWidth
            params.height = bubbleHeight
            params.x = posX
            params.y = posY

            try {
                windowManager.updateViewLayout(view, params)
                currentDraftText = text
            } catch (e: Exception) {
                // Ignore WindowManager errors
            }
        }
    }

    fun removeDraftOverlay() {
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

    fun removeAllMessageOverlays() {
        for (bubble in activeBubbles.values) {
            try {
                windowManager.removeView(bubble.view)
            } catch (e: Exception) {
                // Ignore if already removed
            }
        }
        activeBubbles.clear()
    }

    fun removeAllOverlays() {
        removeAllMessageOverlays()
        removeDraftOverlay()
    }
}
