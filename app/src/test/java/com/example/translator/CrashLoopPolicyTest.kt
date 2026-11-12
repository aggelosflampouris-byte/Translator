package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopPolicyTest {

    private val now = 1_000_000_000L

    @Test
    fun firstCrashEver_relaunches() {
        val decision = CrashLoopPolicy.evaluate(previousCount = 0, lastCrashAtMillis = 0L, nowMillis = now)
        assertEquals(1, decision.newCrashCount)
        assertTrue(decision.shouldRelaunch)
    }

    // Por qué: Un segundo fallo dentro de la ventana indica un bucle de arranque y no debe relanzarse
    @Test
    fun secondCrashWithinWindow_doesNotRelaunch() {
        val decision = CrashLoopPolicy.evaluate(previousCount = 1, lastCrashAtMillis = now - 5_000L, nowMillis = now)
        assertEquals(2, decision.newCrashCount)
        assertFalse(decision.shouldRelaunch)
    }

    @Test
    fun crashAfterWindowExpires_resetsCountAndRelaunches() {
        val lastCrash = now - CrashLoopPolicy.CRASH_WINDOW_MS - 1L
        val decision = CrashLoopPolicy.evaluate(previousCount = 5, lastCrashAtMillis = lastCrash, nowMillis = now)
        assertEquals(1, decision.newCrashCount)
        assertTrue(decision.shouldRelaunch)
    }

    // Por qué: Un reloj del sistema retrasado no debe contarse como fallo consecutivo
    @Test
    fun clockMovedBackwards_treatedAsIndependentCrash() {
        val decision = CrashLoopPolicy.evaluate(previousCount = 3, lastCrashAtMillis = now + 10_000L, nowMillis = now)
        assertEquals(1, decision.newCrashCount)
        assertTrue(decision.shouldRelaunch)
    }
}

