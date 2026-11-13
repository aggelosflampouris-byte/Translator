package com.example.translator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Receptor de difusión para las acciones del menú de notificaciones del sistema.
 *
 * Por qué: Procesa de forma desacoplada los toques en los botones de acción de la notificación
 * (Traducir, Pausar/Reanudar y Detener), comunicándose con el servicio de accesibilidad a través de ServiceBridge.
 */
class TranslationNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            TranslationNotificationManager.ACTION_SCAN -> {
                if (!ServiceBridge.isAccessibilityActive) {
                    Toast.makeText(context, "Accessibility Service is OFF. Toggle it ON in Settings.", Toast.LENGTH_LONG).show()
                    return
                }
                val wasActive = ServiceBridge.isTranslatingActive
                ServiceBridge.isTranslatingActive = true
                if (!wasActive) {
                    ServiceBridge.onTranslationStateChanged?.invoke(true)
                }
                ServiceBridge.onBubbleAppearanceSync?.invoke(true)
                ServiceBridge.onScanRequested?.invoke()
                TranslationNotificationManager.showNotification(context, isTranslating = true)
                TranslationTileService.requestSync(context)
            }

            TranslationNotificationManager.ACTION_TOGGLE -> {
                val newState = !ServiceBridge.isTranslatingActive
                if (newState && !ServiceBridge.isAccessibilityActive) {
                    Toast.makeText(context, "Accessibility Service is OFF. Toggle it ON in Settings.", Toast.LENGTH_LONG).show()
                    return
                }
                ServiceBridge.isTranslatingActive = newState
                ServiceBridge.onTranslationStateChanged?.invoke(newState)
                ServiceBridge.onBubbleAppearanceSync?.invoke(newState)
                TranslationNotificationManager.showNotification(context, isTranslating = newState)
                TranslationTileService.requestSync(context)
                val msg = if (newState) "Translation Active" else "Translation Paused"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }

            TranslationNotificationManager.ACTION_STOP -> {
                ServiceBridge.isTranslatingActive = false
                ServiceBridge.isBubbleRunning = false
                ServiceBridge.onBubbleVisibilityChanged?.invoke(false)
                ServiceBridge.onTranslationStateChanged?.invoke(false)
                TranslationNotificationManager.cancelNotification(context)
                TranslationTileService.requestSync(context)
                ServiceBridge.notifyUiStateChanged()
                Toast.makeText(context, "OverChat Stopped", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

