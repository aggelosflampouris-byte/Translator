package com.example.translator

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticRecorderTest {

    private lateinit var recorder: DiagnosticRecorder

    @Before
    fun setUp() {
        recorder = DiagnosticRecorder()
        resetBridgeDiagnostics()
    }

    @After
    fun tearDown() {
        recorder.clearAll()
        resetBridgeDiagnostics()
    }

    private fun resetBridgeDiagnostics() {
        ServiceBridge.lastScanDebug = "none"
        ServiceBridge.lastTreeScanDebug = "none"
        ServiceBridge.lastPerAppScanDebug = ""
        ServiceBridge.lastDraftDebug = "none"
        ServiceBridge.lastProbeDebug = "none"
        ServiceBridge.lastRuntimeTrace = "init"
    }

    @Test
    fun diagnosticsAreDeferredUntilFlushToServiceBridge() {
        recorder.setDraftDiag("tr(fast,35ms)")
        recorder.recordPerAppDiag("com.whatsapp", "skip(scroll)")
        recorder.recordPerAppDiag("com.whatsapp", "tr(3)")
        recorder.recordScanStatsSnapshot(
            fgPkg = "com.whatsapp",
            profileId = "whatsapp",
            rootOrigin = "active",
            sourceLang = "ro",
            targetLang = "el",
            screenWidth = 1080,
            screenHeight = 2400,
            density = 3.0f,
            topInset = 80,
            effectiveBottom = 2200,
            stats = ScanStats(),
            dedupedCount = 2,
            outcome = "tr(2)"
        )
        recorder.appendTreeScanDiag("extra=ok")

        // Sin volcado explícito, ServiceBridge permanece intacto (cero concatenación en eventos calientes).
        assertEquals("none", ServiceBridge.lastDraftDebug)
        assertEquals("", ServiceBridge.lastPerAppScanDebug)
        assertEquals("none", ServiceBridge.lastScanDebug)
        assertEquals("init", ServiceBridge.lastRuntimeTrace)

        recorder.flushToServiceBridge()

        assertEquals("tr(fast,35ms)", ServiceBridge.lastDraftDebug)
        assertEquals("whatsapp:skip(scroll)>>tr(3)", ServiceBridge.lastPerAppScanDebug)
        assertTrue(ServiceBridge.lastScanDebug.contains("fg=com.whatsapp(whatsapp)"))
        assertTrue(ServiceBridge.lastScanDebug.contains("tr(2) | extra=ok"))
        assertEquals(ServiceBridge.lastScanDebug, ServiceBridge.lastTreeScanDebug)
        assertTrue(ServiceBridge.lastRuntimeTrace.contains("root=none"))
    }

    @Test
    fun recordPerAppDiag_retainsMostRecentThreePackages() {
        recorder.recordPerAppDiag("com.app.one", "tr(1)")
        recorder.recordPerAppDiag("com.app.two", "tr(2)")
        recorder.recordPerAppDiag("com.app.three", "tr(3)")
        recorder.recordPerAppDiag("com.app.four", "tr(4)")

        recorder.flushToServiceBridge()

        assertFalse(ServiceBridge.lastPerAppScanDebug.contains("one:"))
        assertTrue(ServiceBridge.lastPerAppScanDebug.contains("two:tr(2)"))
        assertTrue(ServiceBridge.lastPerAppScanDebug.contains("three:tr(3)"))
        assertTrue(ServiceBridge.lastPerAppScanDebug.contains("four:tr(4)"))
    }
}

