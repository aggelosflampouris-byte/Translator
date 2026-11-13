package com.example.translator

import android.content.Context
import android.os.Build
import android.view.WindowManager

data class DisplayBounds(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float
)

/**
 * Unifica la lectura de dimensiones reales de pantalla entre servicios y gestor de superposiciones.
 *
 * Por qué: Consultar `displayMetrics` en un sitio y `currentWindowMetrics` en otro provoca discrepancias
 * de alto o recorte en barras del sistema al posicionar y depurar burbujas en dispositivos Android 11+.
 */
object ScreenMetricsHelper {

    fun currentBounds(context: Context): DisplayBounds {
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels
        var height = dm.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val bounds = wm.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    width = bounds.width()
                    height = bounds.height()
                }
            } catch (_: Exception) {
                // Por qué: En contextos de prueba o sin ventana asociada conserva displayMetrics como respaldo.
            }
        }
        return DisplayBounds(
            widthPx = width,
            heightPx = height,
            density = dm.density
        )
    }
}

