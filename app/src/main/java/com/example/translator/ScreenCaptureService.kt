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

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CLEAR_OVERLAYS = "com.example.translator.CLEAR_OVERLAYS"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAR_OVERLAYS) {
            if (this::overlayManager.isInitialized) {
                overlayManager.removeAllOverlays()
            }
            return START_NOT_STICKY
        }

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

        startForeground(1, notification)
        
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != 0 && data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }
        
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // Use a slightly lower resolution for better performance in OCR
        val density = metrics.densityDpi
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (isProcessing) {
                // Drop frame if still processing
                val image = reader.acquireLatestImage()
                image?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            isProcessing = true
            processImage(image, width, height)
        }, handler)
    }

    private fun processImage(image: Image, width: Int, height: Int) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        // Create bitmap
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        val inputImage = InputImage.fromBitmap(croppedBitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
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
                            if (languageCode != "und" && languageCode != "en") {
                                val options = TranslatorOptions.Builder()
                                    .setSourceLanguage(languageCode)
                                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                                    .build()
                                val translator = Translation.getClient(options)

                                translator.downloadModelIfNeeded()
                                    .addOnSuccessListener {
                                        translator.translate(text)
                                            .addOnSuccessListener { translatedText ->
                                                translations.add(Pair(rect, translatedText))
                                                pendingTranslations--
                                                checkTranslationComplete(pendingTranslations, translations)
                                            }
                                            .addOnFailureListener {
                                                pendingTranslations--
                                                checkTranslationComplete(pendingTranslations, translations)
                                            }
                                    }
                                    .addOnFailureListener {
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
    }

    private fun checkTranslationComplete(pending: Int, translations: List<Pair<Rect?, String>>) {
        if (pending <= 0) {
            handler.post {
                overlayManager.updateOverlays(translations)
                // Add a delay before taking the next frame to save battery
                handler.postDelayed({ isProcessing = false }, 1000) 
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayManager.removeAllOverlays()
    }
}
