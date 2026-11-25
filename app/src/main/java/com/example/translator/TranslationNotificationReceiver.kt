package com.example.translator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Receptor de difusión para las acciones del menú de notificaciones del sistema.
 *
 * Por qué: Procesa de forma desacoplada los toques en los botones de acción de la notificación
 * (Pausar/Reanudar y Detener), comunicándose con el servicio de accesibilidad a través de ServiceBridge.
 */
class TranslationNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val locCtx = AppLocaleManager.localizedContext(context)

        when (action) {
            TranslationNotificationManager.ACTION_TOGGLE -> {
                val newState = !ServiceBridge.isTranslatingActive
                if (newState && !ServiceBridge.isAccessibilityActive) {
                    Toast.makeText(locCtx, locCtx.getString(R.string.toast_a11y_service_off), Toast.LENGTH_LONG).show()
                    return
                }
                ServiceBridge.isTranslatingActive = newState
                ServiceBridge.onTranslationStateChanged?.invoke(newState)
                TranslationNotificationManager.showNotification(context, isTranslating = newState)
                val msg = locCtx.getString(
                    if (newState) R.string.toast_translation_active else R.string.toast_translation_paused
                )
                Toast.makeText(locCtx, msg, Toast.LENGTH_SHORT).show()
            }

            TranslationNotificationManager.ACTION_STOP -> {
                ServiceBridge.isTranslatingActive = false
                ServiceBridge.onTranslationStateChanged?.invoke(false)
                TranslationNotificationManager.cancelNotification(context)
                ServiceBridge.notifyUiStateChanged()
                Toast.makeText(locCtx, locCtx.getString(R.string.toast_overchat_stopped), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
