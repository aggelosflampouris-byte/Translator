package com.example.translator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayDeliveryCoalescerTest {

    @Test
    fun multipleItemsWithinWindow_areCoalescedIntoSingleFlush() = runTest {
        val flushes = mutableListOf<Pair<Long, List<String>>>()
        val coalescer = OverlayDeliveryCoalescer<String>(
            scope = backgroundScope,
            windowMs = 40L,
            onFlush = { batchId, items -> flushes.add(batchId to items) }
        )

        coalescer.reset(newBatchId = 10L, expectedPendingGroups = 3)
        coalescer.enqueue(10L, listOf("uno"))
        advanceTimeBy(15L)
        coalescer.enqueue(10L, listOf("dos"))
        runCurrent()

        assertTrue("Expected flush to wait until 40ms window or completion", flushes.isEmpty())

        advanceTimeBy(25L)
        runCurrent()

        assertEquals(1, flushes.size)
        assertEquals(10L, flushes[0].first)
        assertEquals(listOf("uno", "dos"), flushes[0].second)

        // Llega el último grupo y completa el lote inmediatamente sin esperar otros 40ms.
        coalescer.enqueue(10L, listOf("tres"))
        assertEquals(2, flushes.size)
        assertEquals(listOf("tres"), flushes[1].second)
        assertTrue(coalescer.isBatchComplete(10L))
    }

    @Test
    fun singlePendingGroup_flushesImmediatelyWithZeroDelay() = runTest {
        val flushes = mutableListOf<Pair<Long, List<String>>>()
        val coalescer = OverlayDeliveryCoalescer<String>(
            scope = backgroundScope,
            windowMs = 40L,
            onFlush = { batchId, items -> flushes.add(batchId to items) }
        )

        coalescer.reset(newBatchId = 5L, expectedPendingGroups = 1)
        coalescer.enqueue(5L, listOf("hola"))

        assertEquals(1, flushes.size)
        assertEquals(listOf("hola"), flushes[0].second)
        assertTrue(coalescer.isBatchComplete(5L))
    }

    @Test
    fun staleBatchDeliveries_areDroppedAfterReset() = runTest {
        val flushes = mutableListOf<Pair<Long, List<String>>>()
        val coalescer = OverlayDeliveryCoalescer<String>(
            scope = backgroundScope,
            windowMs = 40L,
            onFlush = { batchId, items -> flushes.add(batchId to items) }
        )

        coalescer.reset(newBatchId = 1L, expectedPendingGroups = 2)
        coalescer.enqueue(1L, listOf("viejo"))
        coalescer.reset(newBatchId = 2L, expectedPendingGroups = 2)

        advanceTimeBy(50L)
        runCurrent()
        coalescer.enqueue(1L, listOf("obsoleto"))
        coalescer.markGroupDoneEmpty(1L)

        assertTrue("Stale batch 1 must never flush after reset to batch 2", flushes.isEmpty())

        coalescer.enqueue(2L, listOf("nuevo1"))
        coalescer.markGroupDoneEmpty(2L)

        assertEquals(1, flushes.size)
        assertEquals(2L, flushes[0].first)
        assertEquals(listOf("nuevo1"), flushes[0].second)
    }
}

