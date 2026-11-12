package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayThemeTest {

    private val darkSurfaceText = 0xFFE9EDEF.toInt()
    private val lightSurfaceText = 0xFF0F172A.toInt()

    @Test
    fun `auto theme preserves classic whatsapp styling inside whatsapp`() {
        val incoming = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = false,
            isWhatsAppApp = true
        )
        val outgoing = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = true,
            isWhatsAppApp = true
        )

        assertEquals(R.drawable.bg_translation_bubble_incoming, incoming.backgroundRes)
        assertEquals(darkSurfaceText, incoming.textColor)
        assertEquals(R.drawable.bg_translation_bubble_outgoing, outgoing.backgroundRes)
        assertEquals(darkSurfaceText, outgoing.textColor)
    }

    @Test
    fun `auto theme uses neutral styling for non-whatsapp left-right chat apps like telegram`() {
        val incoming = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = false,
            isWhatsAppApp = false
        )
        val outgoing = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = true,
            isWhatsAppApp = false
        )

        assertEquals(R.drawable.bg_translation_bubble_neutral_incoming, incoming.backgroundRes)
        assertEquals(darkSurfaceText, incoming.textColor)
        assertEquals(R.drawable.bg_translation_bubble_neutral_outgoing, outgoing.backgroundRes)
        assertEquals(darkSurfaceText, outgoing.textColor)
    }

    @Test
    fun `auto theme treats left stream chats like discord as incoming even when right side is true`() {
        // Por qué: En chats en flujo izquierdo como Discord o Slack, mensajes largos hacia la derecha siguen siendo entrantes
        val leftMessage = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_STREAM_CHAT,
            isRightSide = false,
            isWhatsAppApp = false
        )
        val wideMessage = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_STREAM_CHAT,
            isRightSide = true,
            isWhatsAppApp = false
        )

        assertEquals(R.drawable.bg_translation_bubble_neutral_incoming, leftMessage.backgroundRes)
        assertEquals(R.drawable.bg_translation_bubble_neutral_incoming, wideMessage.backgroundRes)
        assertEquals(darkSurfaceText, wideMessage.textColor)
    }

    @Test
    fun `light theme applies light drawables and dark ink across alignment modes`() {
        val incoming = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_LIGHT,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = false,
            isWhatsAppApp = false
        )
        val outgoing = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_LIGHT,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = true,
            isWhatsAppApp = true
        )
        val streamWide = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_LIGHT,
            alignmentMode = ChatAlignmentMode.LEFT_STREAM_CHAT,
            isRightSide = true,
            isWhatsAppApp = false
        )

        assertEquals(R.drawable.bg_translation_bubble_light_incoming, incoming.backgroundRes)
        assertEquals(lightSurfaceText, incoming.textColor)
        assertEquals(R.drawable.bg_translation_bubble_light_outgoing, outgoing.backgroundRes)
        assertEquals(lightSurfaceText, outgoing.textColor)
        assertEquals(R.drawable.bg_translation_bubble_light_incoming, streamWide.backgroundRes)
        assertEquals(lightSurfaceText, streamWide.textColor)
    }

    @Test
    fun `classic whatsapp theme overrides non-whatsapp apps`() {
        val telegramOutgoing = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_WHATSAPP,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = true,
            isWhatsAppApp = false
        )
        val discordStream = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_WHATSAPP,
            alignmentMode = ChatAlignmentMode.LEFT_STREAM_CHAT,
            isRightSide = true,
            isWhatsAppApp = false
        )

        assertEquals(R.drawable.bg_translation_bubble_outgoing, telegramOutgoing.backgroundRes)
        assertEquals(darkSurfaceText, telegramOutgoing.textColor)
        assertEquals(R.drawable.bg_translation_bubble_incoming, discordStream.backgroundRes)
        assertEquals(darkSurfaceText, discordStream.textColor)
    }
}

