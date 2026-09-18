package com.example.translator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var sphereView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    companion object {
        var isRunning: Boolean = false
            private set
        var isTranslatingActive: Boolean = false
            private set
        var instance: FloatingBubbleService? = null
            private set

        const val ACTION_TOGGLE_ACTIVE = "com.example.translator.ACTION_TOGGLE_ACTIVE"
        const val ACTION_STOP_SERVICE = "com.example.translator.ACTION_STOP_SERVICE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        isTranslatingActive = false
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingSphere()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_ACTIVE -> {
                toggleTranslationState()
            }
        }
        return START_STICKY
    }

    private fun createFloatingSphere() {
        val density = resources.displayMetrics.density
        val sphereSize = (54 * density).toInt()

        val params = WindowManager.LayoutParams(
            sphereSize,
            sphereSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - sphereSize - (16 * density).toInt())
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }
        layoutParams = params

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.floating_sphere_menu, null)
        sphereView = view

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            val p = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        p.x = initialX + dx
                        p.y = initialY + dy
                        windowManager?.updateViewLayout(view, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Click detected: toggle translation active state
                        toggleTranslationState()
                    } else {
                        // Drag released: snap to nearest screen edge (left or right)
                        val screenWidth = resources.displayMetrics.widthPixels
                        val margin = (12 * density).toInt()
                        p.x = if (p.x + sphereSize / 2 < screenWidth / 2) {
                            margin
                        } else {
                            screenWidth - sphereSize - margin
                        }
                        windowManager?.updateViewLayout(view, p)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun toggleTranslationState() {
        val root = sphereView?.findViewById<FrameLayout>(R.id.sphere_root)

        if (!isTranslatingActive) {
            // Verify accessibility service instance is connected
            if (!TranslationAccessibilityService.isSharedInstanceActive) {
                Toast.makeText(this, "⚠️ Accessibility Service is OFF. Please toggle it ON in Settings!", Toast.LENGTH_LONG).show()
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                return
            }
            isTranslatingActive = true
            root?.setBackgroundResource(R.drawable.bg_floating_sphere_active)
            Toast.makeText(this, "Translation Active (Scanning WhatsApp)", Toast.LENGTH_SHORT).show()
            TranslationAccessibilityService.onTranslationStateChanged(true)
        } else {
            isTranslatingActive = false
            root?.setBackgroundResource(R.drawable.bg_floating_sphere_idle)
            Toast.makeText(this, "Translation Paused (Idle)", Toast.LENGTH_SHORT).show()
            TranslationAccessibilityService.onTranslationStateChanged(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isTranslatingActive = false
        instance = null
        TranslationAccessibilityService.onTranslationStateChanged(false)
        sphereView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // View may already be removed
            }
        }
        sphereView = null
    }
}
