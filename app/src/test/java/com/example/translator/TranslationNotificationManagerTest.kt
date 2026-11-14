package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas unitarias de los textos adaptativos de notificación y acción rápida de idiomas.
 */
class TranslationNotificationManagerTest {

    @Test
    fun buildNotificationCopy_adaptsToAutoTapAndPausedStates() {
        val autoActive = TranslationNotificationManager.buildNotificationCopy(
            mode = AppDefaults.MODE_ALL,
            isTranslating = true,
            srcName = "Romanian",
            tgtName = "Greek"
        )
        assertEquals("OverChat · Auto", autoActive.title)
        assertEquals("Romanian to Greek", autoActive.subtitle)
        assertEquals("Switch Lang", autoActive.primaryActionLabel)
        assertEquals("Pause", autoActive.toggleActionLabel)
        assertEquals("Automatically translates visible chat screens.", autoActive.expandedHint)

        val tapActive = TranslationNotificationManager.buildNotificationCopy(
            mode = AppDefaults.MODE_TAP,
            isTranslating = true,
            srcName = "Romanian",
            tgtName = "Greek"
        )
        assertEquals("OverChat · Tap Mode", tapActive.title)
        assertEquals("Switch Lang", tapActive.primaryActionLabel)
        assertEquals("Pause", tapActive.toggleActionLabel)
        assertEquals("Tap any message in chat to translate.", tapActive.expandedHint)

        val pausedTap = TranslationNotificationManager.buildNotificationCopy(
            mode = AppDefaults.MODE_TAP,
            isTranslating = false,
            srcName = "Greek",
            tgtName = "Romanian"
        )
        assertEquals("OverChat Paused", pausedTap.title)
        assertEquals("Resume", pausedTap.toggleActionLabel)
        assertEquals("Switch Lang", pausedTap.primaryActionLabel)
        assertEquals("Translation is paused. Tap Resume to continue.", pausedTap.expandedHint)
    }
}
