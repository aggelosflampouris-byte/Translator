package com.example.translator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollEventClassifierTest {

    @Test
    fun classify_ignoresSmallChildViews() {
        val decision = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 12,
            sourceHeightPx = 140,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 10,
            maxScrollY = 100,
            fromIndex = -1,
            toIndex = -1,
            itemCount = 0,
            prevSignature = null
        )
        assertFalse(decision.isRelevantVerticalScroll)
    }

    @Test
    fun classify_ignoresHorizontalOnlyScrollInChat() {
        val decision = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 48,
            scrollDeltaY = 0,
            sourceHeightPx = 1200,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 0,
            maxScrollY = 0,
            fromIndex = 0,
            toIndex = 5,
            itemCount = 12,
            prevSignature = null
        )
        assertFalse(decision.isRelevantVerticalScroll)
    }

    @Test
    fun classify_acceptsRealVerticalDeltaScroll() {
        val decision = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = -95,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 400,
            maxScrollY = 2000,
            fromIndex = 4,
            toIndex = 12,
            itemCount = 20,
            prevSignature = null
        )
        assertTrue(decision.isRelevantVerticalScroll)
    }

    @Test
    fun classify_ignoresRepeatedZeroDeltaLayoutRelayouts() {
        val first = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 0,
            maxScrollY = 1500,
            fromIndex = 5,
            toIndex = 14,
            itemCount = 25,
            prevSignature = null
        )
        assertTrue(first.isRelevantVerticalScroll)

        val repeat = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 0,
            maxScrollY = 1500,
            fromIndex = 5,
            toIndex = 14,
            itemCount = 25,
            prevSignature = first.nextSignature
        )
        assertFalse(repeat.isRelevantVerticalScroll)

        val empty = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = -1,
            maxScrollY = -1,
            fromIndex = -1,
            toIndex = -1,
            itemCount = -1,
            prevSignature = null
        )
        assertFalse(empty.isRelevantVerticalScroll)
    }

    @Test
    fun classify_suppressesLayoutJitterAndDoesNotInterruptOnSmallZeroDelta() {
        val first = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 0,
            maxScrollY = 1500,
            fromIndex = 0,
            toIndex = 4,
            itemCount = 12,
            prevSignature = null
        )
        assertFalse(first.shouldInterruptOverlays)

        val jitter = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 0,
            maxScrollY = 1500,
            fromIndex = 1,
            toIndex = 5,
            itemCount = 13,
            prevSignature = first.nextSignature
        )
        assertFalse(jitter.isRelevantVerticalScroll)
        assertFalse(jitter.shouldInterruptOverlays)

        val realScroll = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 120,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 120,
            maxScrollY = 1500,
            fromIndex = 2,
            toIndex = 7,
            itemCount = 13,
            prevSignature = first.nextSignature
        )
        assertTrue(realScroll.isRelevantVerticalScroll)
        assertTrue(realScroll.shouldInterruptOverlays)
    }

    @Test
    fun classify_doesNotInterruptOverlaysWhenFromIndexJumpsWithZeroDeltaAndUnchangedScrollY() {
        val prev = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 500,
            maxScrollY = 2000,
            fromIndex = 0,
            toIndex = 4,
            itemCount = 20,
            prevSignature = null
        )
        assertFalse(prev.shouldInterruptOverlays)

        val indexOnlyJump = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 500,
            maxScrollY = 2000,
            fromIndex = 10,
            toIndex = 15,
            itemCount = 21,
            prevSignature = prev.nextSignature
        )
        assertTrue(indexOnlyJump.isRelevantVerticalScroll)
        assertFalse(indexOnlyJump.shouldInterruptOverlays)

        val realOffsetJump = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = 0,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 620,
            maxScrollY = 2000,
            fromIndex = 11,
            toIndex = 16,
            itemCount = 21,
            prevSignature = indexOnlyJump.nextSignature
        )
        assertTrue(realOffsetJump.isRelevantVerticalScroll)
        assertTrue(realOffsetJump.shouldInterruptOverlays)
    }

    @Test
    fun classify_doesNotInterruptOverlaysOnTinySubPixelDeltaJitter() {
        val jitter = ScrollEventClassifier.classify(
            hasDeltaApi = true,
            scrollDeltaX = 0,
            scrollDeltaY = -1,
            sourceHeightPx = 1800,
            screenHeightPx = 2340,
            windowId = 1,
            scrollY = 0,
            maxScrollY = 1500,
            fromIndex = 2,
            toIndex = 8,
            itemCount = 20,
            prevSignature = null
        )
        assertTrue(jitter.isRelevantVerticalScroll)
        assertFalse(jitter.shouldInterruptOverlays)
    }
}

