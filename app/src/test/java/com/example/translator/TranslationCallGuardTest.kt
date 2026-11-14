package com.example.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationCallGuardTest {

    @Test
    fun circuitBreaker_opensOnThresholdWithinWindow_andRecoversAfterCooldown() {
        var now = 1_000L
        val guard = TranslationCallGuard(
            failureThreshold = 3,
            failureWindowMs = 15_000L,
            cooldownMs = 10_000L,
            uptimeMs = { now }
        )

        assertFalse(guard.isCircuitOpen())
        guard.recordTimeout(now)
        now += 1_000L
        guard.recordTimeout(now)
        assertFalse(guard.isCircuitOpen())

        now += 1_000L
        guard.recordTimeout(now)
        assertTrue(guard.isCircuitOpen())

        now += 9_999L
        assertTrue(guard.isCircuitOpen())

        now += 1L
        assertFalse(guard.isCircuitOpen())
    }

    @Test
    fun circuitBreaker_resetsWindowOnStaleTimeout_orSuccess() {
        var now = 1_000L
        val guard = TranslationCallGuard(
            failureThreshold = 3,
            failureWindowMs = 5_000L,
            cooldownMs = 10_000L,
            uptimeMs = { now }
        )

        guard.recordTimeout(now)
        now += 1_000L
        guard.recordTimeout(now)
        // Una llamada exitosa limpia el historial de fallos previos.
        guard.recordSuccess()

        now += 500L
        guard.recordTimeout(now)
        assertFalse(guard.isCircuitOpen())

        now += 6_000L
        guard.recordTimeout(now)
        now += 500L
        guard.recordTimeout(now)
        // Solo van 2 fallos dentro de la nueva ventana tras expirar el primero.
        assertFalse(guard.isCircuitOpen())
    }

    @Test
    fun runGuarded_returnsNullOnTimeout_tripsCircuit_andSkipsBlockedCalls() = runTest {
        val guard = TranslationCallGuard(
            failureThreshold = 2,
            failureWindowMs = 10_000L,
            cooldownMs = 5_000L,
            uptimeMs = { 100L + testScheduler.currentTime }
        )

        val first = guard.runGuarded(200L) {
            delay(500L)
            "never"
        }
        assertNull(first)
        assertFalse(guard.isCircuitOpen())

        val second = guard.runGuarded(200L) {
            delay(500L)
            "never"
        }
        assertNull(second)
        assertTrue(guard.isCircuitOpen())

        var invokedWhileOpen = false
        val skipped = guard.runGuarded(200L) {
            invokedWhileOpen = true
            "ok"
        }
        assertNull(skipped)
        assertFalse(invokedWhileOpen)

        testScheduler.advanceTimeBy(5_000L)
        assertFalse(guard.isCircuitOpen())
        val recovered = guard.runGuarded(200L) { "recovered" }
        assertEquals("recovered", recovered)
    }

    @Test
    fun runGuarded_propagatesOuterCancellation() = runTest {
        val guard = TranslationCallGuard(uptimeMs = { testScheduler.currentTime })
        var caughtCancellation = false

        val deferred = async {
            try {
                guard.runGuarded(5_000L) {
                    delay(2_000L)
                    "done"
                }
            } catch (cancel: CancellationException) {
                caughtCancellation = true
                throw cancel
            }
        }

        testScheduler.advanceTimeBy(100L)
        deferred.cancel()
        try {
            deferred.await()
        } catch (_: CancellationException) {
            // Esperado al cancelar desde la corrutina padre.
        }

        assertTrue(caughtCancellation)
        assertFalse(guard.isCircuitOpen())
    }
}

