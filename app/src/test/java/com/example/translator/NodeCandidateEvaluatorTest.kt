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
    fun evaluateBottomUp_preservesBothQuotedAndShortReplyWhenParentExpansionCollidesWithSibling() {
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
            parentNodeId = 1
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

        assertEquals(2, bounded.size)
        val quoteCandidate = bounded.first { it.text.startsWith("Pai aia zic") }
        val replyCandidate = bounded.first { it.text == "Te pup stefan ms" }
        assertTrue(quoteCandidate.isQuoted)
        assertFalse(replyCandidate.isQuoted)
        assertEquals(WindowRect(160, 810, 880, 910), quoteCandidate.bounds)
        assertEquals(WindowRect(160, 930, 560, 995), replyCandidate.bounds)
        assertTrue(quoteCandidate.maxBottomPx <= replyCandidate.bounds.top)
        assertEquals(maxY, replyCandidate.maxBottomPx)
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

