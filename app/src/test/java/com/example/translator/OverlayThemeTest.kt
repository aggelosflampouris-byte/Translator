package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `auto theme switches to light palette when device is in day mode`() {
        val lightIncoming = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = false,
            isWhatsAppApp = true,
            isSystemDarkMode = false
        )
        val lightOutgoing = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = true,
            isWhatsAppApp = false,
            isSystemDarkMode = false
        )
        val forcedClassicWhatsApp = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_WHATSAPP,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = true,
            isWhatsAppApp = true,
            isSystemDarkMode = false
        )

        assertEquals(R.drawable.bg_translation_bubble_light_incoming, lightIncoming.backgroundRes)
        assertEquals(lightSurfaceText, lightIncoming.textColor)
        assertEquals(R.drawable.bg_translation_bubble_light_outgoing, lightOutgoing.backgroundRes)
        assertEquals(lightSurfaceText, lightOutgoing.textColor)
        assertEquals(R.drawable.bg_translation_bubble_outgoing, forcedClassicWhatsApp.backgroundRes)
        assertEquals(darkSurfaceText, forcedClassicWhatsApp.textColor)
    }

    @Test
    fun `isOutgoingBubble ignores wide incoming group messages past 65 percent width`() {
        val screenWidth = 1080
        // Mensajes entrantes anchos en grupo WhatsApp (~12%-88%): deben permanecer entrantes (izquierda)
        assertFalse(
            OverlayManager.isOutgoingBubble(
                targetLeft = 135,
                targetRight = 945,
                screenWidth = screenWidth,
                alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT
            )
        )
        // Cita entrante algo indentada (~17%-85%): sigue siendo entrante
        assertFalse(
            OverlayManager.isOutgoingBubble(
                targetLeft = 185,
                targetRight = 918,
                screenWidth = screenWidth,
                alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT
            )
        )
        // Mensaje corto saliente a la derecha (~63%-94%) y mensaje largo saliente (~20%-95%)
        assertTrue(
            OverlayManager.isOutgoingBubble(
                targetLeft = 680,
                targetRight = 1020,
                screenWidth = screenWidth,
                alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT
            )
        )
        assertTrue(
            OverlayManager.isOutgoingBubble(
                targetLeft = 220,
                targetRight = 1020,
                screenWidth = screenWidth,
                alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT
            )
        )
        // Chats tipo flujo izquierdo nunca marcan salientes
        assertFalse(
            OverlayManager.isOutgoingBubble(
                targetLeft = 680,
                targetRight = 1020,
                screenWidth = screenWidth,
                alignmentMode = ChatAlignmentMode.LEFT_STREAM_CHAT
            )
        )
    }

    @Test
    fun `quoted overlay style uses compact quote drawables for dark and light modes`() {
        val darkQuoted = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = false,
            isWhatsAppApp = true,
            isSystemDarkMode = true,
            isQuoted = true
        )
        val lightQuoted = OverlayManager.resolveBubbleStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            isRightSide = true,
            isWhatsAppApp = true,
            isSystemDarkMode = false,
            isQuoted = true
        )

        assertEquals(R.drawable.bg_translation_bubble_quoted_dark, darkQuoted.backgroundRes)
        assertEquals(0xFFD1D7DB.toInt(), darkQuoted.textColor)
        assertEquals(R.drawable.bg_translation_bubble_quoted_light, lightQuoted.backgroundRes)
        assertEquals(0xFF334155.toInt(), lightQuoted.textColor)
    }

    @Test
    fun `draft pill style adapts across themes undo state and stale pending state`() {
        val autoLight = OverlayManager.resolveDraftPillStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            isSystemDarkMode = false
        )
        assertEquals(R.drawable.bg_draft_bubble_light, autoLight.containerBgRes)
        assertEquals(R.drawable.bg_draft_badge_light, autoLight.badgeBgRes)
        assertEquals(R.drawable.bg_draft_action_btn_light, autoLight.actionBgRes)
        assertEquals(R.drawable.bg_draft_close_btn_light, autoLight.closeBgRes)
        assertEquals(lightSurfaceText, autoLight.bodyTextColor)

        val whatsappEvenLightSystem = OverlayManager.resolveDraftPillStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_WHATSAPP,
            isSystemDarkMode = false
        )
        assertEquals(R.drawable.bg_draft_bubble_dark, whatsappEvenLightSystem.containerBgRes)
        assertEquals(R.drawable.bg_draft_badge_dark, whatsappEvenLightSystem.badgeBgRes)
        assertEquals(R.drawable.bg_draft_action_btn, whatsappEvenLightSystem.actionBgRes)

        val undoDark = OverlayManager.resolveDraftPillStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            isSystemDarkMode = true,
            isUndoMode = true
        )
        assertEquals(R.drawable.bg_draft_undo_btn_dark, undoDark.actionBgRes)
        assertEquals(0xFF34D399.toInt(), undoDark.actionTextColor)

        val undoLight = OverlayManager.resolveDraftPillStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_LIGHT,
            isSystemDarkMode = true,
            isUndoMode = true
        )
        assertEquals(R.drawable.bg_draft_undo_btn_light, undoLight.actionBgRes)
        assertEquals(0xFF047857.toInt(), undoLight.badgeTextColor)

        val staleDark = OverlayManager.resolveDraftPillStyle(
            overlayTheme = AppDefaults.OVERLAY_THEME_AUTO,
            isSystemDarkMode = true,
            isStalePending = true
        )
        assertEquals(0xFFCBD5E1.toInt(), staleDark.bodyTextColor)
    }

    @Test
    fun `draft frame computes compact width caps at 420dp and clamps inside composer viewport`() {
        val density = 2f
        val compact = OverlayManager.resolveDraftFrame(
            measuredWidth = 180,
            measuredHeight = 60,
            anchorTop = 900,
            anchorLeft = 40,
            screenWidth = 1080,
            screenHeight = 1920,
            density = density,
            isUndoMode = false
        )
        // Ancho mínimo de 200dp * 2 = 400px, altura mínima 44dp * 2 = 88px.
        assertEquals(400, compact.bubbleWidth)
        assertEquals(88, compact.bubbleHeight)
        assertEquals(40, compact.posX)
        assertEquals(900 - 88 - 16, compact.posY)

        val clampedWide = OverlayManager.resolveDraftFrame(
            measuredWidth = 1600,
            measuredHeight = 200,
            anchorTop = 40,
            anchorLeft = 1000,
            screenWidth = 1080,
            screenHeight = 1920,
            density = density,
            isUndoMode = true
        )
        // Tope de 420dp * 2 = 840px y sujeción a márgenes horizontales (12dp=24px) y superior (48dp=96px).
        assertEquals(840, clampedWide.bubbleWidth)
        assertEquals(200, clampedWide.bubbleHeight)
        assertEquals(1080 - 840 - 24, clampedWide.posX)
        assertEquals(96, clampedWide.posY)
    }
}

