package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas unitarias de los textos de notificación y acción rápida de idiomas.
 */
class TranslationNotificationManagerTest {

    @Test
    fun buildNotificationCopy_adaptsToActiveAndPausedStates() {
        val active = TranslationNotificationManager.buildNotificationCopy(
            isTranslating = true,
            srcName = "Romanian",
            tgtName = "Greek"
        )
        assertEquals("OverChat Active", active.title)
        assertEquals("Romanian to Greek", active.subtitle)
        assertEquals("Switch Lang", active.primaryActionLabel)
        assertEquals("Pause", active.toggleActionLabel)
        assertEquals("Automatically translates visible chat screens.", active.expandedHint)

        val paused = TranslationNotificationManager.buildNotificationCopy(
            isTranslating = false,
            srcName = "Greek",
            tgtName = "Romanian"
        )
        assertEquals("OverChat Paused", paused.title)
        assertEquals("Resume", paused.toggleActionLabel)
        assertEquals("Switch Lang", paused.primaryActionLabel)
        assertEquals("Translation is paused. Tap Resume to continue.", paused.expandedHint)
    }
}
