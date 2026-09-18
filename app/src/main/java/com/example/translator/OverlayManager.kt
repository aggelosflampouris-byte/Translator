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

        for ((rect, translatedText) in results) {
            if (rect == null || translatedText.isBlank()) continue

            val matchedId = findMatchingBubble(rect)
            if (matchedId != null) {
                // Existing bubble near this message
                val bubble = activeBubbles[matchedId]!!
                bubble.targetRect = rect
                if (bubble.text != translatedText) {
                    bubble.text = translatedText
                    updateBubble(bubble, rect, translatedText)
                }
                updatedIds.add(matchedId)
            } else {
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

    private fun findMatchingBubble(targetRect: Rect): String? {
        val density = context.resources.displayMetrics.density
        val proximityThreshold = (40 * density).toInt()

        for ((id, bubble) in activeBubbles) {
            val dx = Math.abs(bubble.targetRect.centerX() - targetRect.centerX())
            val dy = Math.abs(bubble.targetRect.centerY() - targetRect.centerY())
            if (dx < proximityThreshold && dy < proximityThreshold) {
                return id
            }
        }
        return null
    }

    private fun addBubble(targetRect: Rect, text: String, id: String) {
        val view = inflater.inflate(R.layout.bubble_overlay, null)
        val (params, overlayBounds) = calculateBubbleLayout(targetRect, view, text)

        try {
            windowManager.addView(view, params)
            activeBubbles[id] = ActiveBubble(view, text, targetRect, overlayBounds)
        } catch (e: Exception) {
            // WindowManager addView error handling
        }
    }

    private fun updateBubble(bubble: ActiveBubble, targetRect: Rect, text: String) {
        val (params, overlayBounds) = calculateBubbleLayout(targetRect, bubble.view, text)
        bubble.overlayBounds = overlayBounds

        try {
            windowManager.updateViewLayout(bubble.view, params)
        } catch (e: Exception) {
            // WindowManager updateViewLayout error handling
        }
    }

    private fun calculateBubbleLayout(targetRect: Rect, view: View, text: String): Pair<WindowManager.LayoutParams, Rect> {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val density = metrics.density

        val maxBubbleWidth = (screenWidth * 0.85f).toInt()
        val minBubbleWidth = (80 * density).toInt()

        val textView = view.findViewById<TextView>(R.id.translated_text)
        textView.text = text
        textView.maxWidth = maxBubbleWidth

        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxBubbleWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val bubbleWidth = view.measuredWidth.coerceIn(minBubbleWidth, maxBubbleWidth)
        val bubbleHeight = view.measuredHeight.coerceAtLeast((32 * density).toInt())

        // Vertical spacing between original message and translated bubble
        val spacing = (6 * density).toInt()

        // Position ABOVE the original message bubble
        val safeMargin = (12 * density).toInt()
        val maxX = (screenWidth - bubbleWidth - safeMargin).coerceAtLeast(safeMargin)
        val posX = targetRect.left.coerceIn(safeMargin, maxX)
        var posY = targetRect.top - bubbleHeight - spacing

        // If too close to status bar (top < 48dp), position BELOW the message bubble instead
        val topSafetyMargin = (48 * density).toInt()
        if (posY < topSafetyMargin) {
            posY = targetRect.bottom + spacing
        }

        val params = WindowManager.LayoutParams(
            bubbleWidth,
            bubbleHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }

        val overlayBounds = Rect(posX, posY, posX + bubbleWidth, posY + bubbleHeight)
        return Pair(params, overlayBounds)
    }

    fun removeAllOverlays() {
        for (bubble in activeBubbles.values) {
            try {
                windowManager.removeView(bubble.view)
            } catch (e: Exception) {
                // Ignore if already removed
            }
        }
        activeBubbles.clear()
    }
}
