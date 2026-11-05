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
                    Toast.makeText(context, "⚠️ Accessibility Service is OFF. Toggle it ON in Settings!", Toast.LENGTH_LONG).show()
                    return
                }
                ServiceBridge.isTranslatingActive = true
                ServiceBridge.onTranslationStateChanged?.invoke(true)
                ServiceBridge.onScanRequested?.invoke()
                TranslationNotificationManager.showNotification(context, isTranslating = true)
                Toast.makeText(context, "Scanning & translating WhatsApp...", Toast.LENGTH_SHORT).show()
            }

            TranslationNotificationManager.ACTION_TOGGLE -> {
                val newState = !ServiceBridge.isTranslatingActive
                ServiceBridge.isTranslatingActive = newState
                ServiceBridge.onTranslationStateChanged?.invoke(newState)
                TranslationNotificationManager.showNotification(context, isTranslating = newState)
                val msg = if (newState) "Translation Active" else "Translation Paused"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }

            TranslationNotificationManager.ACTION_STOP -> {
                ServiceBridge.isTranslatingActive = false
                ServiceBridge.onTranslationStateChanged?.invoke(false)
                TranslationNotificationManager.cancelNotification(context)
                if (ServiceBridge.isBubbleRunning) {
                    context.stopService(Intent(context, FloatingBubbleService::class.java))
                }
                Toast.makeText(context, "Translator Stopped", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
