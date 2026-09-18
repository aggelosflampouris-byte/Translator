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
    
    // Map of text hash to View to reuse bubbles
    private val activeOverlays = mutableMapOf<String, View>()

    // Called on Main Thread
    fun updateOverlays(results: List<Pair<Rect?, String>>) {
        val newHashes = mutableSetOf<String>()

        for ((rect, translatedText) in results) {
            if (rect == null || translatedText.isBlank()) continue
            
            val hash = translatedText.hashCode().toString() + rect.flattenToString()
            newHashes.add(hash)

            if (!activeOverlays.containsKey(hash)) {
                addBubble(rect, translatedText, hash)
            } else {
                // If view exists, just update position in case it shifted slightly
                updateBubblePosition(activeOverlays[hash]!!, rect)
            }
        }

        // Remove old overlays that are no longer on screen
        val iterator = activeOverlays.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!newHashes.contains(entry.key)) {
                try {
                    windowManager.removeView(entry.value)
                } catch (e: Exception) {
                    // Ignore if already removed
                }
                iterator.remove()
            }
        }
    }

    private fun addBubble(rect: Rect, text: String, hash: String) {
        val view = inflater.inflate(R.layout.bubble_overlay, null)
        view.findViewById<TextView>(R.id.translated_text).text = text
        
        view.alpha = 0.95f 

        val params = WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
        }

        windowManager.addView(view, params)
        activeOverlays[hash] = view
    }
    
    private fun updateBubblePosition(view: View, rect: Rect) {
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        windowManager.updateViewLayout(view, params)
    }
    
    fun removeAllOverlays() {
        for (view in activeOverlays.values) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // Ignore if already removed
            }
        }
        activeOverlays.clear()
    }
}
