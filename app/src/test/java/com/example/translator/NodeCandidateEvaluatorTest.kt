package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias del evaluador puro de nodos [NodeCandidateEvaluator].
 *
 * Por qué: Verifica la supresión inferior-superior de contenedores envolventes, el respaldo de texto
 * en `contentDescription`, el recorte al viewport y la deduplicación espacial sin árbol Android real.
 */
class NodeCandidateEvaluatorTest {

    private val instagramProfile = TargetAppCatalog.profileForPackage("com.instagram.android")
    private val density = 3.0f
    private val screenWidth = 1080
    private val minY = 200
    private val maxY = 2000

    @Test
    fun evaluateBottomUp_suppressesWrapperContainerWhenChildAccepted() {
        val child = CollectedNodeSnapshot(
            rawRect = WindowRect(80, 500, 650, 590),
            expandedRect = WindowRect(80, 500, 650, 590),
            directText = "Ce faci mâine seară?",
            descText = null,
            shortClass = "TextView",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = true,
            childIndices = emptyList()
        )
        // Contenedor envolvente con el mismo texto o un sufijo breve (≤ +12 caracteres) debe suprimirse.
        val wrapperContainer = CollectedNodeSnapshot(
            rawRect = WindowRect(60, 480, 670, 610),
            expandedRect = WindowRect(60, 480, 670, 610),
            directText = null,
            descText = "Ce faci mâine seară?, 12:00",
            shortClass = "ViewGroup",
            isScrollableContainer = false,
            childCount = 1,
            inChatViewport = true,
            childIndices = listOf(0)
        )

        val results = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = listOf(child, wrapperContainer),
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = instagramProfile
        )

        assertEquals(1, results.size)
        assertEquals("Ce faci mâine seară?", results.single().text)
    }

    @Test
    fun evaluateBottomUp_fallsBackToDescriptionWhenDirectTextIsUiToken() {
        val node = CollectedNodeSnapshot(
            rawRect = WindowRect(80, 700, 640, 810),
            expandedRect = WindowRect(80, 700, 640, 810),
            directText = "Message",
            descText = "Ajung în zece minute acasă",
            shortClass = "ViewGroup",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = true,
            childIndices = emptyList()
        )

        val results = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = listOf(node),
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = instagramProfile
        )

        assertEquals(1, results.size)
        assertEquals("Ajung în zece minute acasă", results.single().text)
    }

    @Test
    fun evaluateBottomUp_clampsPartiallyClippedBubbleAndRejectsHeaderOutsideViewport() {
        // Por qué: visibleMargin = 20 * 3 = 60, así que un nodo con bottom <= 260 queda descartado como cabecera.
        val headerNode = CollectedNodeSnapshot(
            rawRect = WindowRect(80, 100, 600, 220),
            expandedRect = WindowRect(80, 100, 600, 220),
            directText = "Bună dimineața!",
            descText = null,
            shortClass = "TextView",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = false,
            childIndices = emptyList()
        )
        val clippedTopChatBubble = CollectedNodeSnapshot(
            rawRect = WindowRect(80, 170, 660, 320),
            expandedRect = WindowRect(80, 170, 660, 320),
            directText = "Ne vedem diseară la cafea",
            descText = null,
            shortClass = "TextView",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = true,
            childIndices = emptyList()
        )
        val stats = ScanStats()

        val results = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = listOf(headerNode, clippedTopChatBubble),
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = instagramProfile,
            stats = stats
        )

        assertEquals(1, results.size)
        assertEquals(minY, results.single().bounds.top)
        assertEquals(1, stats.skippedBubbleBounds)
        assertEquals(1, stats.passedCandidates)
    }

    @Test
    fun filterOverlapping_prefersLongerCandidateAndDropsContainedOverlap() {
        val longer = EvaluatedCandidate(
            text = "Mulțumesc mult pentru ajutorul de azi",
            bounds = WindowRect(80, 500, 700, 640)
        )
        val innerFragment = EvaluatedCandidate(
            text = "Mulțumesc mult",
            bounds = WindowRect(100, 520, 480, 580)
        )
        val distinct = EvaluatedCandidate(
            text = "Cum ești prietene?",
            bounds = WindowRect(80, 720, 560, 810)
        )

        val filtered = NodeCandidateEvaluator.filterOverlapping(
            candidates = listOf(innerFragment, longer, distinct),
            density = density
        )

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.text == longer.text })
        assertTrue(filtered.any { it.text == distinct.text })
    }

    @Test
    fun scanStats_retainsBottomChatSamplesAndFilterBreakdownBeyondFirstSixTopNodes() {
        val rawNodes = (0 until 8).map { idx ->
            val top = 250 + idx * 180
            val text = if (idx == 7) {
                "Εσείς στείλατε: Bună ziua, 1:25 μ.μ., Απεστάλη"
            } else {
                "Ελληνικό μήνυμα $idx, 1:25 μ.μ., Απεστάλη"
            }
            CollectedNodeSnapshot(
                rawRect = WindowRect(147, top, 1000, top + 100),
                expandedRect = WindowRect(147, top, 1000, top + 100),
                directText = text,
                descText = text,
                shortClass = "ViewGroup",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList()
            )
        }
        val stats = ScanStats()
        val results = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = rawNodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = instagramProfile,
            stats = stats
        )

        assertEquals(1, results.size)
        assertEquals("Bună ziua", results.single().text)
        assertEquals("(grk:7)", stats.formatFilterBreakdown())
        val samplesSummary = stats.formatSamples()
        assertTrue(samplesSummary.contains("ok"))
        assertTrue(samplesSummary.contains("[147,1330-1000,1430]"))
    }

    @Test
    fun evaluateBottomUp_translatesOnlyReplyAndDropsQuotedMessageAndHeader() {
        val whatsappProfile = TargetAppCatalog.profileForPackage("com.whatsapp")
        val sharedBubbleContainer = WindowRect(130, 740, 910, 1020)

        val senderHeader = CollectedNodeSnapshot(
            rawRect = WindowRect(150, 750, 440, 795),
            expandedRect = WindowRect(150, 750, 440, 795),
            directText = "~ Edysuperedy",
            descText = null,
            shortClass = "TextView",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = true,
            childIndices = emptyList(),
            rowId = 1,
            parentNodeId = 10
        )
        val quotedText = CollectedNodeSnapshot(
            rawRect = WindowRect(160, 810, 880, 910),
            expandedRect = sharedBubbleContainer,
            directText = "Pai aia zic, nu ma chinui azi, de maine incolo",
            descText = null,
            shortClass = "TextView",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = true,
            childIndices = emptyList(),
            rowId = 1,
            parentNodeId = 20
        )
        val shortReply = CollectedNodeSnapshot(
            rawRect = WindowRect(160, 930, 560, 995),
            expandedRect = sharedBubbleContainer,
            directText = "Te pup stefan ms",
            descText = null,
            shortClass = "TextView",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = true,
            childIndices = emptyList(),
            rowId = 1,
            parentNodeId = 10
        )
        val talkBackWrapper = CollectedNodeSnapshot(
            rawRect = sharedBubbleContainer,
            expandedRect = sharedBubbleContainer,
            directText = null,
            descText = "~ Edysuperedy, Pai aia zic, nu ma chinui azi, de maine incolo, Te pup stefan ms, 20:48",
            shortClass = "LinearLayout",
            isScrollableContainer = false,
            childCount = 3,
            inChatViewport = true,
            childIndices = listOf(0, 1, 2),
            rowId = 1,
            parentNodeId = 1,
            nodeId = 10
        )

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = listOf(senderHeader, quotedText, shortReply, talkBackWrapper),
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )
        val filtered = NodeCandidateEvaluator.filterOverlapping(evaluated, density)
        val bounded = NodeCandidateEvaluator.assignMaxBottomBounds(filtered, maxY, density)

        assertEquals(1, bounded.size)
        val replyCandidate = bounded.single()
        assertEquals("Te pup stefan ms", replyCandidate.text)
        assertFalse(replyCandidate.isQuoted)
        assertEquals(WindowRect(160, 930, 560, 995), replyCandidate.bounds)
        assertEquals(maxY, replyCandidate.maxBottomPx)
    }

    @Test
    fun evaluateBottomUp_dropsGroupHandlesRolesAndQuotesWhileKeepingReplyBodies() {
        val whatsappProfile = TargetAppCatalog.profileForPackage("com.whatsapp")
        val nodes = listOf(
            // Fila 1: contacto guardado sin virgulilla + teléfono + emojis (no debe traducirse el nombre)
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 300, 420, 345),
                expandedRect = WindowRect(150, 300, 780, 345),
                directText = "Stefania V",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 10,
                parentNodeId = 101
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(520, 302, 770, 342),
                expandedRect = WindowRect(520, 302, 770, 342),
                directText = "+40 748 782 007",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 10,
                parentNodeId = 101
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 360, 320, 420),
                expandedRect = WindowRect(150, 360, 320, 420),
                directText = "😭😭",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 10,
                parentNodeId = 100
            ),
            // Fila 2: alias en minúsculas + teléfono + subtítulo de rol comunitario "lache" + mensaje real
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 460, 340, 500),
                expandedRect = WindowRect(150, 460, 780, 500),
                directText = "misu",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 11,
                parentNodeId = 111
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(520, 462, 770, 500),
                expandedRect = WindowRect(520, 462, 770, 500),
                directText = "+40 770 875 500",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 11,
                parentNodeId = 111
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 506, 260, 540),
                expandedRect = WindowRect(150, 506, 260, 540),
                directText = "lache",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 11,
                parentNodeId = 110
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 550, 820, 620),
                expandedRect = WindowRect(150, 550, 820, 620),
                directText = "mai are sens sa vorbim cu profu?",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 11,
                parentNodeId = 110
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(710, 624, 810, 650),
                expandedRect = WindowRect(710, 624, 810, 650),
                directText = "15:51",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 11,
                parentNodeId = 110
            ),
            // Fila 3: burbuja de respuesta real ("nu nu") sobre cita incrustada ("Ca acum am permisiune")
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 700, 420, 740),
                expandedRect = WindowRect(150, 700, 760, 740),
                directText = "Mircea Ciuraru",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 12,
                parentNodeId = 120,
                isInsideHeaderContainer = true
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(166, 750, 680, 815),
                expandedRect = WindowRect(166, 750, 680, 815),
                directText = "Ca acum am permisiune",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 12,
                parentNodeId = 121
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 825, 340, 875),
                expandedRect = WindowRect(150, 825, 340, 875),
                directText = "nu nu",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 12,
                parentNodeId = 120
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(580, 878, 670, 905),
                expandedRect = WindowRect(580, 878, 670, 905),
                directText = "20:46",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 12,
                parentNodeId = 120
            ),
            // Fila 4: contenedor de cita explícito por viewId + respuesta no traducible (emojis) + TalkBack wrapper
            CollectedNodeSnapshot(
                rawRect = WindowRect(166, 960, 680, 1020),
                expandedRect = WindowRect(166, 960, 680, 1020),
                directText = "Ea are doar 10",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 13,
                parentNodeId = 131,
                isInsideQuoteContainer = true
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 1030, 300, 1090),
                expandedRect = WindowRect(150, 1030, 300, 1090),
                directText = "😭😭",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 13,
                parentNodeId = 130
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(130, 950, 720, 1100),
                expandedRect = WindowRect(130, 950, 720, 1100),
                directText = null,
                descText = "~ Stefania V, Ea are doar 10, 😭😭, 15:52",
                shortClass = "LinearLayout",
                isScrollableContainer = false,
                childCount = 2,
                inChatViewport = true,
                childIndices = listOf(12, 13),
                rowId = 13,
                parentNodeId = 130
            )
        )

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = nodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )

        assertEquals(
            listOf("mai are sens sa vorbim cu profu?", "nu nu"),
            evaluated.map { it.text }
        )
        assertTrue(evaluated.none { it.isQuoted })
    }

    @Test
    fun evaluateBottomUp_preservesConsecutiveStandaloneBubblesSharingSameRow() {
        val whatsappProfile = TargetAppCatalog.profileForPackage("com.whatsapp")
        val nodes = listOf(
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 420, 460, 460),
                expandedRect = WindowRect(150, 420, 820, 460),
                directText = "~ Edysuperedy",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 201
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 466, 560, 500),
                expandedRect = WindowRect(150, 466, 560, 500),
                directText = "Higher Year Representative",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 201
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 515, 760, 580),
                expandedRect = WindowRect(150, 515, 800, 595),
                directText = "Verificati si maine de dimi",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 202
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(680, 565, 790, 595),
                expandedRect = WindowRect(680, 565, 790, 595),
                directText = "00:26",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 202
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 625, 810, 690),
                expandedRect = WindowRect(150, 625, 850, 720),
                directText = "Poate mai dureaza sa se updateze",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 203
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(560, 690, 840, 720),
                expandedRect = WindowRect(560, 690, 840, 720),
                directText = "Τροποποιήθηκε 00:27",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 203
            )
        )

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = nodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )

        assertEquals(
            listOf("Verificati si maine de dimi", "Poate mai dureaza sa se updateze"),
            evaluated.map { it.text }
        )
        assertTrue(evaluated.none { it.isQuoted })
    }

    @Test
    fun evaluateBottomUp_dropsSavedNameAboveShortRejectedBodyEvenWhenTimestampIsNearby() {
        val whatsappProfile = TargetAppCatalog.profileForPackage("com.whatsapp")
        val nodes = listOf(
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 380, 340, 420),
                expandedRect = WindowRect(150, 380, 340, 420),
                directText = "Edward",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 30,
                parentNodeId = 300
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 426, 460, 462),
                expandedRect = WindowRect(150, 426, 460, 462),
                directText = "Too early to ask this",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 30,
                parentNodeId = 300
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(470, 456, 560, 482),
                expandedRect = WindowRect(470, 456, 560, 482),
                directText = "00:26",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 30,
                parentNodeId = 300
            )
        )

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = nodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )

        assertTrue(evaluated.isEmpty())
    }

    @Test
    fun resourceIdClassifiers_identifyWhatsAppQuoteAndSenderHeaderIds() {
        assertTrue(NodeCandidateEvaluator.isQuoteContainerResourceId("com.whatsapp:id/quoted_message_holder"))
        assertTrue(NodeCandidateEvaluator.isQuoteContainerResourceId("com.whatsapp:id/quoted_text"))
        assertFalse(NodeCandidateEvaluator.isQuoteContainerResourceId("com.whatsapp:id/message_text"))

        assertTrue(NodeCandidateEvaluator.isSenderHeaderResourceId("com.whatsapp:id/name_in_group_tv"))
        assertTrue(NodeCandidateEvaluator.isSenderHeaderResourceId("com.whatsapp:id/quoted_title"))
        assertFalse(NodeCandidateEvaluator.isSenderHeaderResourceId("com.whatsapp:id/message_text"))
    }

    @Test
    fun evaluateBottomUp_dropsRoleBadgeHeaderAboveGroupMessageBody() {
        val whatsappProfile = TargetAppCatalog.profileForPackage("com.whatsapp")
        val roleHeader = CollectedNodeSnapshot(
            rawRect = WindowRect(150, 520, 620, 560),
            expandedRect = WindowRect(150, 520, 620, 560),
            directText = "Higher Year Representative",
            descText = null,
            shortClass = "TextView",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = true,
            childIndices = emptyList(),
            rowId = 2,
            parentNodeId = 30
        )
        val body = CollectedNodeSnapshot(
            rawRect = WindowRect(150, 575, 860, 690),
            expandedRect = WindowRect(150, 575, 860, 690),
            directText = "Pai aia zic, nu ma chinui azi, de maine incolo",
            descText = null,
            shortClass = "TextView",
            isScrollableContainer = false,
            childCount = 0,
            inChatViewport = true,
            childIndices = emptyList(),
            rowId = 2,
            parentNodeId = 30
        )

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = listOf(roleHeader, body),
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )

        assertEquals(1, evaluated.size)
        assertEquals("Pai aia zic, nu ma chinui azi, de maine incolo", evaluated.single().text)
        assertFalse(evaluated.single().isQuoted)
    }
}

