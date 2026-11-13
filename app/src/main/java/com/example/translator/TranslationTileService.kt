package com.example.translator

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi

/**
 * Servicio de Mosaico de Ajustes Rápidos (Quick Settings Tile).
 *
 * Por qué: Ofrece al usuario un acceso directo en la cortina superior de Android para activar
 * o desactivar la traducción de pantalla con un solo toque, sin necesidad de abrir la aplicación
 * principal ni mantener iconos flotantes visibles.
 */
@RequiresApi(Build.VERSION_CODES.N)
class TranslationTileService : TileService() {

    companion object {
        fun requestSync(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val appContext = context.applicationContext
                    requestListeningState(
                        appContext,
                        ComponentName(appContext, TranslationTileService::class.java)
                    )
                } catch (_: Exception) {
                    // Ignora fallos si el OEM restringe actualizaciones del Tile en segundo plano
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        if (!ServiceBridge.isAccessibilityActive) {
            Toast.makeText(this, "Accessibility Service is OFF. Toggle it ON in Settings.", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                // Por qué: En Android 14+ se requiere PendingIntent para startActivityAndCollapse
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            } catch (e: Exception) {
                Log.e("Translator", "TranslationTileService: failed to open accessibility settings", e)
            }
            return
        }

        val newState = !ServiceBridge.isTranslatingActive
        ServiceBridge.isTranslatingActive = newState
        ServiceBridge.onTranslationStateChanged?.invoke(newState)
        ServiceBridge.onBubbleAppearanceSync?.invoke(newState)

        val prefs = getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val controlInterface = prefs.getString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_NOTIFICATION)
        if (ServiceBridge.isNotificationActive || (newState && controlInterface == AppDefaults.INTERFACE_NOTIFICATION)) {
            TranslationNotificationManager.showNotification(this, isTranslating = newState)
        } else {
            ServiceBridge.notifyUiStateChanged()
        }
        Toast.makeText(this, if (newState) "OverChat Active" else "OverChat Paused", Toast.LENGTH_SHORT).show()

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isActive = ServiceBridge.isTranslatingActive

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "OverChat"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) "Active" else "Inactive"
        }
        tile.updateTile()
    }
}
