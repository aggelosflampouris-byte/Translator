package com.example.translator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
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
        const val ACTION_TOGGLE_ACTIVE = "com.example.translator.ACTION_TOGGLE_ACTIVE"
        const val ACTION_STOP_SERVICE = "com.example.translator.ACTION_STOP_SERVICE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceBridge.isBubbleRunning = true
        ServiceBridge.isTranslatingActive = false
        ServiceBridge.notifyUiStateChanged()
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

    /**
     * Recomputes the floating sphere's bounding rect from the current layout params
     * and publishes it to [ServiceBridge]. Called after every position change so that
     * [OverlayManager] can avoid drawing translation bubbles on top of the sphere.
     */
    private fun updateBridgeBounds() {
        val p = layoutParams ?: run {
            ServiceBridge.currentBubbleBounds = null
            return
        }
        val density = resources.displayMetrics.density
        val defaultSize = (52 * density).toInt()
        val w = sphereView?.width?.takeIf { it > 0 } ?: p.width.takeIf { it > 0 } ?: defaultSize
        val h = sphereView?.height?.takeIf { it > 0 } ?: p.height.takeIf { it > 0 } ?: defaultSize
        ServiceBridge.currentBubbleBounds = Rect(p.x, p.y, p.x + w, p.y + h)
    }

    private fun createFloatingSphere() {
        val density = resources.displayMetrics.density
        val sphereSize = (52 * density).toInt()

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
                        updateBridgeBounds()
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
                        updateBridgeBounds()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(view, params)
            updateBridgeBounds()
        } catch (e: Exception) {
            Log.e("Translator", "FloatingBubbleService: failed to add overlay view", e)
            stopSelf()
        }
    }

    private fun toggleTranslationState() {
        val root = sphereView?.findViewById<FrameLayout>(R.id.sphere_root)

        if (!ServiceBridge.isTranslatingActive) {
            // Verify accessibility service is connected before activating
            if (!ServiceBridge.isAccessibilityActive) {
                Toast.makeText(this, "⚠️ Accessibility Service is OFF. Please toggle it ON in Settings!", Toast.LENGTH_LONG).show()
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                return
            }
            ServiceBridge.isTranslatingActive = true
            root?.setBackgroundResource(R.drawable.bg_floating_sphere_active)
            // Por qué: Notifica de forma visible al usuario qué motor está activo (el modelo neuronal Index-Translate 2B o el respaldo ML Kit)
            val engineName = if (ModelAssetManager.isModelReady(this)) "Index-Translate 2B Neural" else "ML Kit Local"
            Toast.makeText(this, "Translation Active ($engineName)", Toast.LENGTH_SHORT).show()
            ServiceBridge.onTranslationStateChanged?.invoke(true)
            ServiceBridge.notifyUiStateChanged()
        } else {
            ServiceBridge.isTranslatingActive = false
            root?.setBackgroundResource(R.drawable.bg_floating_sphere_idle)
            Toast.makeText(this, "Translation Paused (Idle)", Toast.LENGTH_SHORT).show()
            ServiceBridge.onTranslationStateChanged?.invoke(false)
            ServiceBridge.notifyUiStateChanged()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceBridge.isBubbleRunning = false
        ServiceBridge.isTranslatingActive = false
        ServiceBridge.currentBubbleBounds = null
        // Notify the accessibility service that translation stopped
        ServiceBridge.onTranslationStateChanged?.invoke(false)
        ServiceBridge.notifyUiStateChanged()
        sphereView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w("Translator", "FloatingBubbleService: failed to removeView in onDestroy", e)
            }
        }
        sphereView = null
    }
}
