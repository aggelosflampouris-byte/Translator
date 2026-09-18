package com.example.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private lateinit var overlayManager: OverlayManager
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val languageIdentifier = LanguageIdentification.getClient()

    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var isDestroyed = false

    override fun onCreate() {
        super.onCreate()
        isDestroyed = false
        overlayManager = OverlayManager(this)
        isRunning = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CLEAR_OVERLAYS = "com.example.translator.CLEAR_OVERLAYS"
        var isRunning = false
    }

    private var sourceLanguage: String = "AUTO"
    private var targetLanguage: String = TranslateLanguage.ENGLISH

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "screen_capture_channel"
        val channelName = "Screen Capture Service"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Translation Overlay Active")
            .setContentText("Monitoring screen for translation")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (intent?.action == ACTION_CLEAR_OVERLAYS) {
            if (this::overlayManager.isInitialized) {
                overlayManager.removeAllOverlays()
            }
            return START_NOT_STICKY
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        intent?.getStringExtra("sourceLanguage")?.let { sourceLanguage = it }
        intent?.getStringExtra("targetLanguage")?.let { targetLanguage = it }

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode != 0 && data != null && mediaProjection == null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = projectionManager.getMediaProjection(resultCode, data)
            if (mp != null) {
                mediaProjection = mp
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        stopSelf()
                    }
                }, handler)
                setupVirtualDisplay()
            }
        }
        
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int
        val density: Int

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
            density = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi
        }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (isDestroyed || !isRunning || isProcessing) {
                val image = reader.acquireLatestImage()
                image?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            isProcessing = true
            processImage(image, width, height)
        }, handler)
    }

    private val translators = mutableMapOf<String, Translator>()

    private fun getTranslator(sourceLang: String, targetLang: String): Translator {
        val key = "$sourceLang-$targetLang"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }
    }

    private fun processImage(image: Image, width: Int, height: Int) {
        if (isDestroyed || !isRunning) {
            image.close()
            isProcessing = false
            return
        }
        try {
            val planes = image.planes
            if (planes.isEmpty()) {
                isProcessing = false
                return
            }
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            
            // Use actual image dimensions in case they differ from VirtualDisplay dimensions
            val imgWidth = image.width
            val imgHeight = image.height
            
            if (pixelStride == 0 || imgWidth == 0 || imgHeight == 0) {
                isProcessing = false
                return
            }

            val rowPadding = rowStride - pixelStride * imgWidth
            val bitmapWidth = imgWidth + rowPadding / pixelStride

            if (bitmapWidth <= 0 || imgHeight <= 0) {
                isProcessing = false
                return
            }

            // Create bitmap
            val bitmap = Bitmap.createBitmap(bitmapWidth, imgHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // Ensure we don't crop outside the bitmap bounds
            val cropWidth = width.coerceAtMost(bitmap.width)
            val cropHeight = height.coerceAtMost(bitmap.height)
            
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, cropWidth, cropHeight)
            if (croppedBitmap !== bitmap) {
                bitmap.recycle() // Only recycle if createBitmap actually created a new copy
            }
            
            val overlayRects = overlayManager.getActiveOverlayRects()
            if (overlayRects.isNotEmpty()) {
                val canvas = Canvas(croppedBitmap)
                val paint = Paint().apply {
                    color = Color.BLACK
                    style = Paint.Style.FILL
                }
                for (overlayRect in overlayRects) {
                    val expanded = Rect(
                        (overlayRect.left - 4).coerceAtLeast(0),
                        (overlayRect.top - 4).coerceAtLeast(0),
                        (overlayRect.right + 4).coerceAtMost(croppedBitmap.width),
                        (overlayRect.bottom + 4).coerceAtMost(croppedBitmap.height)
                    )
                    canvas.drawRect(expanded, paint)
                }
            }

            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)

            textRecognizer.process(inputImage)
                .addOnCompleteListener {
                    croppedBitmap.recycle()
                }
                .addOnSuccessListener { visionText ->
                    if (isDestroyed || !isRunning) {
                        isProcessing = false
                        return@addOnSuccessListener
                    }

                    val prefs = getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
                    val configuredSource = prefs.getString("source_language", sourceLanguage) ?: sourceLanguage
                    val configuredTarget = prefs.getString("target_language", targetLanguage) ?: targetLanguage

                    val validBlocks = visionText.textBlocks.filter { block ->
                        val rect = block.boundingBox
                        val text = block.text
                        rect != null && shouldTranslate(text) && !overlayRects.any { Rect.intersects(rect, it) }
                    }

                    if (validBlocks.isEmpty()) {
                        overlayManager.removeAllOverlays()
                        isProcessing = false
                        return@addOnSuccessListener
                    }

                    val translations = mutableListOf<Pair<Rect?, String>>()
                    var pendingTranslations = validBlocks.size

                    for (block in validBlocks) {
                        val text = block.text
                        val rect = block.boundingBox

                        if (configuredSource != "AUTO") {
                            // User selected explicit source language (e.g. RO or EN)
                            languageIdentifier.identifyLanguage(text)
                                .addOnSuccessListener { detected ->
                                    if (isDestroyed || !isRunning) {
                                        pendingTranslations--
                                        checkTranslationComplete(pendingTranslations, translations)
                                        return@addOnSuccessListener
                                    }
                                    val detectedBcp = TranslateLanguage.fromLanguageTag(detected)
                                    if (detectedBcp == configuredTarget) {
                                        // Already in target language, skip
                                        pendingTranslations--
                                        checkTranslationComplete(pendingTranslations, translations)
                                        return@addOnSuccessListener
                                    }
                                    translateBlock(configuredSource, configuredTarget, text, rect, translations) {
                                        pendingTranslations--
                                        checkTranslationComplete(pendingTranslations, translations)
                                    }
                                }
                                .addOnFailureListener {
                                    translateBlock(configuredSource, configuredTarget, text, rect, translations) {
                                        pendingTranslations--
                                        checkTranslationComplete(pendingTranslations, translations)
                                    }
                                }
                        } else {
                            // Auto-detect language
                            languageIdentifier.identifyLanguage(text)
                                .addOnSuccessListener { languageCode ->
                                    if (isDestroyed || !isRunning) {
                                        pendingTranslations--
                                        checkTranslationComplete(pendingTranslations, translations)
                                        return@addOnSuccessListener
                                    }
                                    val bcp47Code = TranslateLanguage.fromLanguageTag(languageCode)
                                    if (bcp47Code != null && bcp47Code != configuredTarget && languageCode != "und") {
                                        translateBlock(bcp47Code, configuredTarget, text, rect, translations) {
                                            pendingTranslations--
                                            checkTranslationComplete(pendingTranslations, translations)
                                        }
                                    } else {
                                        pendingTranslations--
                                        checkTranslationComplete(pendingTranslations, translations)
                                    }
                                }
                                .addOnFailureListener {
                                    pendingTranslations--
                                    checkTranslationComplete(pendingTranslations, translations)
                                }
                        }
                    }
                }
                .addOnFailureListener {
                    Log.e("Translator", "OCR Failed", it)
                    isProcessing = false
                }
        } catch (e: Exception) {
            Log.e("Translator", "Error processing image: ${e.message}", e)
            isProcessing = false
        } finally {
            image.close()
        }
    }

    private fun translateBlock(
        sourceLang: String,
        targetLang: String,
        text: String,
        rect: Rect?,
        translations: MutableList<Pair<Rect?, String>>,
        onComplete: () -> Unit
    ) {
        try {
            val translator = getTranslator(sourceLang, targetLang)
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    if (isDestroyed || !isRunning) {
                        onComplete()
                        return@addOnSuccessListener
                    }
                    try {
                        translator.translate(text)
                            .addOnSuccessListener { translatedText ->
                                if (!isDestroyed && isRunning && translatedText.isNotBlank()) {
                                    translations.add(Pair(rect, translatedText))
                                }
                                onComplete()
                            }
                            .addOnFailureListener {
                                onComplete()
                            }
                    } catch (e: Exception) {
                        Log.e("Translator", "Translation call failed", e)
                        onComplete()
                    }
                }
                .addOnFailureListener {
                    onComplete()
                }
        } catch (e: Exception) {
            Log.e("Translator", "Translator initialization failed for $sourceLang->$targetLang", e)
            onComplete()
        }
    }

    private fun shouldTranslate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false

        // Filter out URLs
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("www.", ignoreCase = true) ||
            trimmed.contains("bit.ly/", ignoreCase = true) ||
            trimmed.contains("facebook.com/share", ignoreCase = true)) {
            return false
        }

        // Filter out pure timestamps e.g. "5:16 μ.μ.", "12:55", "3:23 pm", "7:17"
        if (trimmed.matches(Regex("""^\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?$""", RegexOption.IGNORE_CASE))) {
            return false
        }

        // Must contain at least one letter
        if (!trimmed.any { it.isLetter() }) {
            return false
        }

        return true
    }

    private fun checkTranslationComplete(pending: Int, translations: List<Pair<Rect?, String>>) {
        if (pending <= 0) {
            handler.post {
                if (isDestroyed || !isRunning) {
                    isProcessing = false
                    return@post
                }
                overlayManager.updateOverlays(translations)
                // Add a delay before taking the next frame to save battery
                handler.postDelayed({ isProcessing = false }, 1000) 
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed = true
        isRunning = false
        isProcessing = false
        handler.removeCallbacksAndMessages(null)
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        overlayManager.removeAllOverlays()
        
        // Clean up ML Kit resources to prevent memory leaks
        translators.values.forEach { 
            try {
                it.close()
            } catch (e: Exception) {
                Log.w("Translator", "Error closing translator", e)
            }
        }
        translators.clear()
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.w("Translator", "Error closing textRecognizer", e)
        }
        try {
            languageIdentifier.close()
        } catch (e: Exception) {
            Log.w("Translator", "Error closing languageIdentifier", e)
        }
    }
}
