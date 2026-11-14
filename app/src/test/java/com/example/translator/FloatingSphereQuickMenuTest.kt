package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas unitarias del formato de etiquetas del menú rápido de la esfera flotante.
 */
class FloatingSphereQuickMenuTest {

    @Test
    fun formatQuickMenuLabels_usesClearDirectionAndTurnOffLabels() {
        val autoLabels = FloatingSphereOverlayController.formatQuickMenuLabels(
            src = "ro",
            tgt = "el",
            mode = AppDefaults.MODE_ALL
        )
        assertEquals("⇄ RO → EL", autoLabels.pairChip)
        assertEquals("Mode: Auto", autoLabels.modeChip)
        assertEquals("Settings", autoLabels.settingsChip)
        assertEquals("Turn Off", autoLabels.turnOffChip)

        val tapLabels = FloatingSphereOverlayController.formatQuickMenuLabels(
            src = "el",
            tgt = "en",
            mode = AppDefaults.MODE_TAP
        )
        assertEquals("⇄ EL → EN", tapLabels.pairChip)
        assertEquals("Mode: Tap", tapLabels.modeChip)
    }
}

