package com.example.translator

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Utilidad única de portapapeles con protección de privacidad en Android 13+.
 *
 * Por qué: Centraliza la etiqueta `EXTRA_IS_SENSITIVE` para que tanto la pastilla flotante
 * individual como la inserción de borradores eviten exponer mensajes en vistas previas del teclado.
 */
object ClipboardHelper {

    fun copySensitiveText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
    }
}

