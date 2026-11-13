package com.example.translator

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias del controlador de sesión reactivo [TranslatorSessionController].
 */
class TranslatorSessionControllerTest {

    @Test
    fun toggleAndSurfaceResets_updateRuntimeStateAtomically() = runTest {
        val controller = TranslatorSessionController()
        assertFalse(controller.current.isAnyServiceActive)
        assertFalse(controller.current.isAccessibilityActive)

        controller.setAccessibilityActive(true)
        controller.setBubbleRunning(true)
        assertTrue(controller.current.isAnyServiceActive)

        val activated = controller.toggleTranslating()
        assertTrue(activated)
        assertTrue(controller.current.isTranslatingActive)

        val deactivated = controller.toggleTranslating()
        assertFalse(deactivated)
        assertFalse(controller.current.isTranslatingActive)
        assertTrue(controller.current.isAnyServiceActive)

        controller.stopAllSurfaces()
        assertFalse(controller.current.isAnyServiceActive)
        assertTrue(controller.current.isAccessibilityActive)

        controller.disconnectAccessibility()
        assertEquals(TranslatorRuntimeState(), controller.runtimeState.first())
    }
}

