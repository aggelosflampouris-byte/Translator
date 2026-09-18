package com.example.translator

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TranslationAccessibilityService : AccessibilityService() {
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                Log.d("Translator", "Scroll detected")
                if (ScreenCaptureService.isRunning) {
                    val intent = android.content.Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_CLEAR_OVERLAYS
                    }
                    startService(intent)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                Log.d("Translator", "Text changed: ${event.text}")
                // TODO: Process outgoing translation
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d("Translator", "Window content changed")
                // TODO: Trigger OCR capture if scrolling stopped
            }
        }
    }

    companion object {
        var isSharedInstanceActive = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("Translator", "Accessibility Service Connected")
        isSharedInstanceActive = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d("Translator", "Accessibility Service Unbound")
        isSharedInstanceActive = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.d("Translator", "Accessibility Service interrupted")
    }
}
