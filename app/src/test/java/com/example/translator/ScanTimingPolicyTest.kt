package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanTimingPolicyTest {

    @Test
    fun chooseContentDebounceMs_isShortWhenQuiet_andLongerDuringBursts() {
        assertEquals(
            ScanTimingPolicy.QUIET_CONTENT_DEBOUNCE_MS,
            ScanTimingPolicy.chooseContentDebounceMs(nowMs = 1_000L, lastContentEventMs = 0L)
        )
        assertEquals(
            ScanTimingPolicy.QUIET_CONTENT_DEBOUNCE_MS,
            ScanTimingPolicy.chooseContentDebounceMs(
                nowMs = 2_000L,
                lastContentEventMs = 2_000L - ScanTimingPolicy.CONTENT_IDLE_THRESHOLD_MS
            )
        )
        assertEquals(
            ScanTimingPolicy.BURST_CONTENT_DEBOUNCE_MS,
            ScanTimingPolicy.chooseContentDebounceMs(nowMs = 2_000L, lastContentEventMs = 1_850L)
        )
        // Un desfase negativo inesperado se trata como ráfaga conservadora (`0 < threshold`).
        assertEquals(
            ScanTimingPolicy.BURST_CONTENT_DEBOUNCE_MS,
            ScanTimingPolicy.chooseContentDebounceMs(nowMs = 1_000L, lastContentEventMs = 1_200L)
        )
    }

    @Test
    fun chooseDraftDelayMs_prioritizesFastHits_andBoundsStarvationOnMisses() {
        assertEquals(
            ScanTimingPolicy.DRAFT_FAST_PATH_DELAY_MS,
            ScanTimingPolicy.chooseDraftDelayMs(elapsedTypingMs = 0L, hasFastHit = true)
        )
        assertEquals(
            ScanTimingPolicy.DRAFT_FAST_PATH_DELAY_MS,
            ScanTimingPolicy.chooseDraftDelayMs(elapsedTypingMs = 500L, hasFastHit = true)
        )

        assertEquals(
            ScanTimingPolicy.DRAFT_ADAPTIVE_DEBOUNCE_MS,
            ScanTimingPolicy.chooseDraftDelayMs(elapsedTypingMs = 40L, hasFastHit = false)
        )
        assertEquals(
            60L,
            ScanTimingPolicy.chooseDraftDelayMs(
                elapsedTypingMs = ScanTimingPolicy.DRAFT_MAX_STARVATION_MS - 60L,
                hasFastHit = false
            )
        )
        assertEquals(
            ScanTimingPolicy.DRAFT_MIN_COALESCE_MS,
            ScanTimingPolicy.chooseDraftDelayMs(elapsedTypingMs = 450L, hasFastHit = false)
        )
    }
}

