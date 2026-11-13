package com.example.translator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Renderiza y gestiona la esfera flotante de control directamente como [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 *
 * Por qué: Al ejecutarse dentro del servicio de accesibilidad ya concedido, la esfera no requiere
 * el permiso adicional `SYSTEM_ALERT_WINDOW` ni un servicio secundario separado, recuerda la última
 * posición elegida por el usuario y ofrece accesos rápidos mediante pulsación prolongada.
 */
class FloatingSphereOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onToggleActive: () -> Unit,
    private val onSwapLanguages: () -> Unit,
    private val onToggleTranslationMode: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val onHideBubble: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inflater = LayoutInflater.from(context)

    private var sphereView: View? = null
    private var sphereParams: WindowManager.LayoutParams? = null
    private var quickMenuView: View? = null
    private var isTranslatingActiveState: Boolean = ServiceBridge.isTranslatingActive

    fun isShowing(): Boolean = sphereView != null

    fun show(isTranslatingActive: Boolean = ServiceBridge.isTranslatingActive) {
        isTranslatingActiveState = isTranslatingActive
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(isTranslatingActive) }
            return
        }

        if (sphereView != null) {
            syncAppearance(isTranslatingActive)
            return
        }

        val bounds = ScreenMetricsHelper.currentBounds(context)
        val density = bounds.density
        val sphereSize = (52 * density).toInt()
        val margin = (12 * density).toInt()

        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val edgeRight = prefs.getBoolean(AppDefaults.KEY_SPHERE_EDGE_RIGHT, true)
        val yRatio = prefs.getFloat(AppDefaults.KEY_SPHERE_Y_RATIO, 0.35f).coerceIn(0.08f, 0.85f)

        val initialX = if (edgeRight) {
            (bounds.widthPx - sphereSize - margin).coerceAtLeast(margin)
        } else {
            margin
        }
        val initialY = (bounds.heightPx * yRatio).toInt()
            .coerceIn((48 * density).toInt(), (bounds.heightPx - sphereSize - margin).coerceAtLeast((48 * density).toInt()))

        val params = WindowManager.LayoutParams(
            sphereSize,
            sphereSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val view = inflater.inflate(R.layout.floating_sphere_menu, null)
        attachTouchInteractions(view, params, sphereSize)

        try {
            windowManager.addView(view, params)
            sphereView = view
            sphereParams = params
            applySphereBackground(view, isTranslatingActive)
        } catch (e: Exception) {
            Log.e("Translator", "FloatingSphereOverlayController: failed to add overlay sphere", e)
        }
    }

    fun syncAppearance(isActive: Boolean) {
        isTranslatingActiveState = isActive
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { syncAppearance(isActive) }
            return
        }
        sphereView?.let { applySphereBackground(it, isActive) }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }
        dismissQuickMenu()
        sphereView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w("Translator", "FloatingSphereOverlayController: failed to remove sphere", e)
            }
        }
        sphereView = null
        sphereParams = null
    }

    private fun applySphereBackground(view: View, isActive: Boolean) {
        val root = view.findViewById<FrameLayout>(R.id.sphere_root) ?: return
        root.setBackgroundResource(
            if (isActive) R.drawable.bg_floating_sphere_active else R.drawable.bg_floating_sphere_idle
        )
    }

    private fun attachTouchInteractions(
        view: View,
        params: WindowManager.LayoutParams,
        sphereSize: Int
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toggleQuickMenu()
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 420L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        dismissQuickMenu()
                    }
                    if (isDragging) {
                        val bounds = ScreenMetricsHelper.currentBounds(context)
                        val maxX = (bounds.widthPx - sphereSize).coerceAtLeast(0)
                        val maxY = (bounds.heightPx - sphereSize).coerceAtLeast(0)
                        params.x = (initialX + dx).coerceIn(0, maxX)
                        params.y = (initialY + dy).coerceIn((24 * bounds.density).toInt(), maxY)
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                            // Ignora si la ventana fue retirada durante la transición
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDragging && !longPressTriggered) {
                        if (quickMenuView != null) {
                            dismissQuickMenu()
                        } else {
                            onToggleActive()
                        }
                    } else if (isDragging) {
                        snapAndPersistPosition(view, params, sphereSize)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapAndPersistPosition(
        view: View,
        params: WindowManager.LayoutParams,
        sphereSize: Int
    ) {
        val currentBounds = ScreenMetricsHelper.currentBounds(context)
        val screenWidth = currentBounds.widthPx
        val screenHeight = currentBounds.heightPx.coerceAtLeast(1)
        val margin = (12 * currentBounds.density).toInt()
        val snapRight = params.x + sphereSize / 2 >= screenWidth / 2
        params.x = if (snapRight) {
            (screenWidth - sphereSize - margin).coerceAtLeast(margin)
        } else {
            margin
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignora error transitorio de layout
        }
        val yRatio = (params.y.toFloat() / screenHeight.toFloat()).coerceIn(0.08f, 0.85f)
        context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AppDefaults.KEY_SPHERE_EDGE_RIGHT, snapRight)
            .putFloat(AppDefaults.KEY_SPHERE_Y_RATIO, yRatio)
            .apply()
    }

    private fun toggleQuickMenu() {
        if (quickMenuView != null) {
            dismissQuickMenu()
            return
        }
        val anchor = sphereParams ?: return
        val bounds = ScreenMetricsHelper.currentBounds(context)
        val density = bounds.density

        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val src = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
        val tgt = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
        val mode = prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL

        val menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (8 * density).toInt()
            val vPad = (6 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0F172A"))
                cornerRadius = 16f * density
                setStroke((1 * density).toInt(), Color.parseColor("#3394A3B8"))
            }
            elevation = 8f * density
        }

        fun addChip(label: String, accent: Boolean = false, action: () -> Unit) {
            val chip = TextView(context).apply {
                text = label
                setTextColor(if (accent) Color.parseColor("#10B981") else Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                setOnClickListener {
                    dismissQuickMenu()
                    action()
                }
            }
            menuContainer.addView(chip)
        }

        addChip("⇄ ${src.uppercase()}/${tgt.uppercase()}", accent = true) { onSwapLanguages() }
        addChip(if (mode == AppDefaults.MODE_TAP) "Auto" else "Tap") { onToggleTranslationMode() }
        addChip("App") { onOpenApp() }
        addChip("Hide") { onHideBubble() }

        menuContainer.measure(
            View.MeasureSpec.makeMeasureSpec(bounds.widthPx - (32 * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val menuW = menuContainer.measuredWidth
        val menuH = menuContainer.measuredHeight
        val gap = (8 * density).toInt()
        val margin = (12 * density).toInt()
        val isOnRight = anchor.x + anchor.width / 2 >= bounds.widthPx / 2
        val posX = if (isOnRight) {
            (anchor.x - menuW - gap).coerceAtLeast(margin)
        } else {
            (anchor.x + anchor.width + gap).coerceAtMost((bounds.widthPx - menuW - margin).coerceAtLeast(margin))
        }
        val posY = (anchor.y + (anchor.height - menuH) / 2)
            .coerceIn((48 * density).toInt(), (bounds.heightPx - menuH - margin).coerceAtLeast((48 * density).toInt()))

        val menuParams = WindowManager.LayoutParams(
            menuW,
            menuH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        try {
            windowManager.addView(menuContainer, menuParams)
            quickMenuView = menuContainer
        } catch (e: Exception) {
            Log.w("Translator", "FloatingSphereOverlayController: failed to show quick menu", e)
        }
    }

    private fun dismissQuickMenu() {
        quickMenuView?.let { menu ->
            try {
                windowManager.removeView(menu)
            } catch (_: Exception) {
                // Ignora si ya fue retirado
            }
            quickMenuView = null
        }
    }
}

