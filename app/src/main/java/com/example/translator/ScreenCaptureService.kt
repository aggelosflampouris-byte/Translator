package com.example.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
                    val textBlocks = visionText.textBlocks
                    if (textBlocks.isEmpty()) {
                        overlayManager.removeAllOverlays()
                        isProcessing = false
                        return@addOnSuccessListener
                    }

                    val translations = mutableListOf<Pair<Rect?, String>>()
                    var pendingTranslations = textBlocks.size

                    for (block in textBlocks) {
                        val text = block.text
                        val rect = block.boundingBox

                        languageIdentifier.identifyLanguage(text)
                            .addOnSuccessListener { languageCode ->
                                if (isDestroyed || !isRunning) {
                                    pendingTranslations--
                                    checkTranslationComplete(pendingTranslations, translations)
                                    return@addOnSuccessListener
                                }
                                val bcp47Code = TranslateLanguage.fromLanguageTag(languageCode)
                                
                                val isTargetLanguage = bcp47Code == targetLanguage
                                val isSourceLanguageMatch = sourceLanguage == "AUTO" || bcp47Code == sourceLanguage
                                
                                if (bcp47Code != null && !isTargetLanguage && isSourceLanguageMatch) {
                                    try {
                                        val translator = getTranslator(bcp47Code, targetLanguage)

                                        translator.downloadModelIfNeeded()
                                            .addOnSuccessListener {
                                                if (isDestroyed || !isRunning) {
                                                    pendingTranslations--
                                                    checkTranslationComplete(pendingTranslations, translations)
                                                    return@addOnSuccessListener
                                                }
                                                try {
                                                    translator.translate(text)
                                                        .addOnSuccessListener { translatedText ->
                                                            if (isDestroyed || !isRunning) {
                                                                pendingTranslations--
                                                                checkTranslationComplete(pendingTranslations, translations)
                                                                return@addOnSuccessListener
                                                            }
                                                            translations.add(Pair(rect, translatedText))
                                                            pendingTranslations--
                                                            checkTranslationComplete(pendingTranslations, translations)
                                                        }
                                                        .addOnFailureListener {
                                                            pendingTranslations--
                                                            checkTranslationComplete(pendingTranslations, translations)
                                                        }
                                                } catch (e: Exception) {
                                                    Log.e("Translator", "Translation call failed", e)
                                                    pendingTranslations--
                                                    checkTranslationComplete(pendingTranslations, translations)
                                                }
                                            }
                                            .addOnFailureListener {
                                                pendingTranslations--
                                                checkTranslationComplete(pendingTranslations, translations)
                                            }
                                    } catch (e: Exception) {
                                        Log.e("Translator", "Translation initialization failed for $bcp47Code", e)
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
