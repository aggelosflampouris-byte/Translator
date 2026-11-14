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
 * en la bandeja de notificaciones de Android, permitiendo traducir la pantalla de la app de chat activa,
 * pausar o detener el servicio con cero obstrucción visual sobre el chat.
 */
object TranslationNotificationManager {

    const val CHANNEL_ID = "translation_controls_channel"
    const val NOTIFICATION_ID = 2026

    const val ACTION_TOGGLE = "com.example.translator.ACTION_TOGGLE"
    const val ACTION_STOP = "com.example.translator.ACTION_STOP"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "OverChat Translation Controls"
            val descriptionText = "Controles rápidos para traducir mensajes en tus apps de chat"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    data class NotificationCopy(
        val title: String,
        val subtitle: String,
        val primaryActionLabel: String,
        val toggleActionLabel: String,
        val expandedHint: String
    )

    // Por qué: Centraliza los textos dependientes del modo para probarlos en JVM y evitar mensajes contradictorios
    fun buildNotificationCopy(
        mode: String,
        isTranslating: Boolean,
        srcName: String,
        tgtName: String,
        autoTitle: String = "OverChat · Auto",
        tapTitle: String = "OverChat · Tap Mode",
        pausedTitle: String = "OverChat Paused",
        subtitle: String = "$srcName to $tgtName",
        switchActionLabel: String = "Switch Lang",
        pauseLabel: String = "Pause",
        resumeLabel: String = "Resume",
        autoHint: String = "Automatically translates visible chat screens.",
        tapHint: String = "Tap any message in chat to translate.",
        pausedHint: String = "Translation is paused. Tap Resume to continue."
    ): NotificationCopy {
        val isTapMode = mode == AppDefaults.MODE_TAP
        val title = when {
            !isTranslating -> pausedTitle
            isTapMode -> tapTitle
            else -> autoTitle
        }
        val toggleActionLabel = if (isTranslating) pauseLabel else resumeLabel
        val expandedHint = when {
            !isTranslating -> pausedHint
            isTapMode -> tapHint
            else -> autoHint
        }
        return NotificationCopy(
            title = title,
            subtitle = subtitle,
            primaryActionLabel = switchActionLabel,
            toggleActionLabel = toggleActionLabel,
            expandedHint = expandedHint
        )
    }

    fun showNotification(context: Context, isTranslating: Boolean = ServiceBridge.isTranslatingActive) {
        createNotificationChannel(context)

        val prefs = context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val src = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
        val tgt = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
        val mode = prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL

        val srcCode = AppDefaults.formatLanguageDisplayName(src)
        val tgtCode = AppDefaults.formatLanguageDisplayName(tgt)

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

        // Acción 1: Abrir ventana flotante rápida para cambiar idiomas
        val switchIntent = Intent(context, LanguageQuickSwitchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val switchPendingIntent = PendingIntent.getActivity(
            context,
            1,
            switchIntent,
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

        val copy = buildNotificationCopy(
            mode = mode,
            isTranslating = isTranslating,
            srcName = srcCode,
            tgtName = tgtCode,
            autoTitle = context.getString(R.string.notif_title_auto_active),
            tapTitle = context.getString(R.string.notif_title_tap_active),
            pausedTitle = context.getString(R.string.notif_title_paused),
            subtitle = context.getString(R.string.notif_subtitle_pair, srcCode, tgtCode),
            switchActionLabel = context.getString(R.string.notif_action_switch_languages),
            pauseLabel = context.getString(R.string.notif_action_pause),
            resumeLabel = context.getString(R.string.notif_action_resume),
            autoHint = context.getString(R.string.notif_hint_auto),
            tapHint = context.getString(R.string.notif_hint_tap),
            pausedHint = context.getString(R.string.notif_hint_paused)
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate_sphere)
            .setContentTitle(copy.title)
            .setContentText(copy.subtitle)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_translate_sphere,
                copy.primaryActionLabel,
                switchPendingIntent
            )
            .addAction(R.drawable.ic_translate_sphere, copy.toggleActionLabel, togglePendingIntent)
            .addAction(
                R.drawable.ic_translate_sphere,
                context.getString(R.string.notif_action_stop),
                stopPendingIntent
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${copy.subtitle}\n${copy.expandedHint}")
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

