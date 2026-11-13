package com.example.translator

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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
            return
        }

        val newState = !ServiceBridge.isTranslatingActive
        ServiceBridge.isTranslatingActive = newState
        ServiceBridge.onTranslationStateChanged?.invoke(newState)

        if (newState) {
            TranslationNotificationManager.showNotification(this, isTranslating = true)
            Toast.makeText(this, "OverChat Active", Toast.LENGTH_SHORT).show()
        } else {
            TranslationNotificationManager.showNotification(this, isTranslating = false)
            Toast.makeText(this, "OverChat Paused", Toast.LENGTH_SHORT).show()
        }

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
