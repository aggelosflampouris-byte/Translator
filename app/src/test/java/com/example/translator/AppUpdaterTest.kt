package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun normalizeVersion_stripsPrefixesAndWhitespace() {
        assertEquals("1.0.63", AppUpdater.normalizeVersion("v1.0.63"))
        assertEquals("1.0.63", AppUpdater.normalizeVersion("V1.0.63"))
        assertEquals("1.0.63", AppUpdater.normalizeVersion(" 1.0.63 "))
        assertEquals("2.0.0", AppUpdater.normalizeVersion("v2.0.0"))
    }

    // Por qué: Verifica que la comparación de versiones detecte correctamente cuándo una versión es posterior
    @Test
    fun isNewerVersion_detectsNewerReleasesCorrectly() {
        assertTrue(AppUpdater.isNewerVersion("v1.0.64", "1.0.63"))
        assertTrue(AppUpdater.isNewerVersion("v1.1.0", "1.0.63"))
        assertTrue(AppUpdater.isNewerVersion("v2.0.0", "1.0.63"))
        assertTrue(AppUpdater.isNewerVersion("1.0.64", "v1.0.63"))

        // Same version
        assertFalse(AppUpdater.isNewerVersion("v1.0.63", "1.0.63"))
        assertFalse(AppUpdater.isNewerVersion("1.0.63", "v1.0.63"))

        // Older version
        assertFalse(AppUpdater.isNewerVersion("v1.0.62", "1.0.63"))
        assertFalse(AppUpdater.isNewerVersion("v0.9.9", "1.0.63"))
    }

    // Por qué: Comprueba el formateo semántico cuando el número de subversión supera los 9 dígitos
    @Test
    fun isNewerVersion_handlesMultiDigitComponents() {
        assertTrue(AppUpdater.isNewerVersion("v1.0.100", "1.0.99"))
        assertFalse(AppUpdater.isNewerVersion("v1.0.9", "1.0.10"))
    }
}
