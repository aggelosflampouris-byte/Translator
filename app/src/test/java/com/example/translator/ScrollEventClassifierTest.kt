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
}

