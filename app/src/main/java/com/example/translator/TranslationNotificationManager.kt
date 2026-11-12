package com.example.translator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Gestor del menú de control en la barra de notificaciones del sistema.
 *
 * Por qué: Reemplaza la burbuja flotante intrusiva en pantalla por un control nativo
 * en la bandeja de notificaciones de Android, permitiendo traducir la pantalla de WhatsApp,
 * pausar o detener el servicio con cero obstrucción visual sobre el chat.
 */
object TranslationNotificationManager {

    const val CHANNEL_ID = "translation_controls_channel"
    const val NOTIFICATION_ID = 2026

    const val ACTION_SCAN = "com.example.translator.ACTION_SCAN"
    const val ACTION_TOGGLE = "com.example.translator.ACTION_TOGGLE"
    const val ACTION_STOP = "com.example.translator.ACTION_STOP"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "OverChat Translation Controls"
            val descriptionText = "Controles rápidos para traducir mensajes en WhatsApp"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showNotification(context: Context, isTranslating: Boolean = ServiceBridge.isTranslatingActive) {
        createNotificationChannel(context)

        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val src = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
        val tgt = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE

        val srcCode = if (src == "AUTO") {
            "Auto"
        } else {
            java.util.Locale.forLanguageTag(src).displayLanguage.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
            }
        }
        val tgtCode = java.util.Locale.forLanguageTag(tgt).displayLanguage.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }

        // Intent al tocar la notificación (abre MainActivity)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Acción 1: Traducir pantalla ahora
        val scanIntent = Intent(context, TranslationNotificationReceiver::class.java).apply {
            action = ACTION_SCAN
        }
        val scanPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Acción 2: Pausar / Reanudar
        val toggleIntent = Intent(context, TranslationNotificationReceiver::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Acción 3: Detener
        val stopIntent = Intent(context, TranslationNotificationReceiver::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isTranslating) "OverChat Active" else "OverChat Paused"
        val subtitle = "$srcCode to $tgtCode"

        val toggleActionTitle = if (isTranslating) "Pause" else "Resume"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate_sphere)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_translate_sphere, "Translate Screen", scanPendingIntent)
            .addAction(R.drawable.ic_translate_sphere, toggleActionTitle, togglePendingIntent)
            .addAction(R.drawable.ic_translate_sphere, "Stop", stopPendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$subtitle\nUse 'Translate Screen' to translate visible WhatsApp messages.")
            )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())
        ServiceBridge.isNotificationActive = true
        ServiceBridge.notifyUiStateChanged()
    }

    fun cancelNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        ServiceBridge.isNotificationActive = false
        ServiceBridge.notifyUiStateChanged()
    }
}

