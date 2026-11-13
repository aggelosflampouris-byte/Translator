package com.example.translator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_TOGGLE_ACTIVE = "com.example.translator.ACTION_TOGGLE_ACTIVE"
        const val ACTION_STOP_SERVICE = "com.example.translator.ACTION_STOP_SERVICE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Log.w("Translator", "FloatingBubbleService: missing overlay permission")
            ServiceBridge.isBubbleRunning = false
            ServiceBridge.notifyUiStateChanged()
            stopSelf()
            return
        }
        ServiceBridge.isBubbleRunning = true
        ServiceBridge.onBubbleAppearanceSync = { active ->
            mainHandler.post { syncSphereAppearance(active) }
        }
        ServiceBridge.notifyUiStateChanged()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingSphere()
        syncSphereAppearance(ServiceBridge.isTranslatingActive)
        TranslationTileService.requestSync(this)
    }

    private fun syncSphereAppearance(isActive: Boolean) {
        val root = sphereView?.findViewById<FrameLayout>(R.id.sphere_root) ?: return
        root.setBackgroundResource(
            if (isActive) R.drawable.bg_floating_sphere_active else R.drawable.bg_floating_sphere_idle
        )
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
        val bounds = ScreenMetricsHelper.currentBounds(this)
        val density = bounds.density
        val sphereSize = (52 * density).toInt()

        val params = WindowManager.LayoutParams(
            sphereSize,
            sphereSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bounds.widthPx - sphereSize - (16 * density).toInt())
            y = (bounds.heightPx * 0.35f).toInt()
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
                        val currentBounds = ScreenMetricsHelper.currentBounds(this)
                        val screenWidth = currentBounds.widthPx
                        val margin = (12 * currentBounds.density).toInt()
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
            Log.e("Translator", "FloatingBubbleService: failed to add overlay view", e)
            stopSelf()
        }
    }

    private fun toggleTranslationState() {
        if (!ServiceBridge.isTranslatingActive) {
            // Verify accessibility service is connected before activating
            if (!ServiceBridge.isAccessibilityActive) {
                Toast.makeText(this, "⚠️ Accessibility Service is OFF. Please toggle it ON in Settings!", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("Translator", "FloatingBubbleService: failed to open accessibility settings", e)
                }
                return
            }
            ServiceBridge.isTranslatingActive = true
            syncSphereAppearance(true)
            Toast.makeText(this, "Translation Active", Toast.LENGTH_SHORT).show()
            if (ServiceBridge.isNotificationActive) {
                TranslationNotificationManager.showNotification(this, isTranslating = true)
            }
            TranslationTileService.requestSync(this)
            ServiceBridge.onTranslationStateChanged?.invoke(true)
            ServiceBridge.notifyUiStateChanged()
        } else {
            ServiceBridge.isTranslatingActive = false
            syncSphereAppearance(false)
            Toast.makeText(this, "Translation Paused (Idle)", Toast.LENGTH_SHORT).show()
            if (ServiceBridge.isNotificationActive) {
                TranslationNotificationManager.showNotification(this, isTranslating = false)
            }
            TranslationTileService.requestSync(this)
            ServiceBridge.onTranslationStateChanged?.invoke(false)
            ServiceBridge.notifyUiStateChanged()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceBridge.onBubbleAppearanceSync = null
        ServiceBridge.isBubbleRunning = false
        // Por qué: Al cambiar de burbuja a notificación desde la pantalla principal, `onDestroy` llega de forma
        // asíncrona tras activar la notificación; solo detenemos la traducción si la notificación no tomó el relevo.
        if (!ServiceBridge.isNotificationActive) {
            ServiceBridge.isTranslatingActive = false
            ServiceBridge.onTranslationStateChanged?.invoke(false)
        }
        TranslationTileService.requestSync(this)
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
