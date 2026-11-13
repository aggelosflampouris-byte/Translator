package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTargetResolverTest {

    private val selfPkg = "com.example.translator"
    private val enabledPkgs = setOf("com.whatsapp", "com.instagram.android", "com.viber.voip")
    private val fullScreen = WindowRect(0, 0, 1080, 2340)

    @Test
    fun resolve_returnsInstagram_whenRootInActiveWindowMatches() {
        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.instagram.android",
            activeWindowBounds = fullScreen,
            appWindows = emptyList(),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = null,
            recentEventAgeMs = Long.MAX_VALUE,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.instagram.android", result.targetPackage)
    }

    @Test
    fun resolve_ignoresThinOemEdgeHandleAboveViber() {
        val edgeHandle = AppWindowSnapshot(
            packageName = "com.samsung.android.app.cocktailbarservice",
            isActive = false,
            isFocused = false,
            layer = 99,
            bounds = WindowRect(1050, 400, 1080, 950)
        )
        val viberWin = AppWindowSnapshot(
            packageName = "com.viber.voip",
            isActive = false,
            isFocused = true,
            layer = 10,
            bounds = fullScreen
        )
        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.samsung.android.honeyboard",
            activeWindowBounds = WindowRect(0, 1500, 1080, 2340),
            appWindows = listOf(viberWin, edgeHandle),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = null,
            recentEventAgeMs = Long.MAX_VALUE,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.viber.voip", result.targetPackage)
    }

    @Test
    fun resolve_ignoresThinLauncherTaskbarOverWhatsApp_butBlocksFullscreenLauncher() {
        val taskbarStrip = WindowRect(0, 2220, 1080, 2340)
        val whatsappWin = AppWindowSnapshot(
            packageName = "com.whatsapp",
            isActive = false,
            isFocused = false,
            layer = 10,
            bounds = fullScreen
        )
        val stripResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = taskbarStrip,
            appWindows = listOf(whatsappWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = null,
            recentEventAgeMs = Long.MAX_VALUE,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.whatsapp", stripResult.targetPackage)

        val homeResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = fullScreen,
            appWindows = listOf(
                AppWindowSnapshot("com.sec.android.app.launcher", isActive = true, isFocused = true, layer = 20, bounds = fullScreen),
                whatsappWin
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.whatsapp",
            recentEventAgeMs = 500L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertNull(homeResult.targetPackage)
    }

    @Test
    fun resolve_blocksSelfMainActivity_butAllowsSmallSelfBubbleOverInstagram() {
        val mainActivityResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = selfPkg,
            activeWindowBounds = fullScreen,
            appWindows = listOf(
                AppWindowSnapshot(selfPkg, isActive = true, isFocused = true, layer = 30, bounds = fullScreen),
                AppWindowSnapshot("com.instagram.android", isActive = false, isFocused = false, layer = 10, bounds = fullScreen)
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.instagram.android",
            recentEventAgeMs = 300L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertNull(mainActivityResult.targetPackage)

        val bubbleResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = selfPkg,
            activeWindowBounds = WindowRect(900, 800, 1060, 960),
            appWindows = listOf(
                AppWindowSnapshot(selfPkg, isActive = true, isFocused = false, layer = 30, bounds = WindowRect(900, 800, 1060, 960)),
                AppWindowSnapshot("com.instagram.android", isActive = false, isFocused = true, layer = 10, bounds = fullScreen)
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = null,
            recentEventAgeMs = Long.MAX_VALUE,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.instagram.android", bubbleResult.targetPackage)
    }

    @Test
    fun resolve_usesRecentEventFallbackOnlyWithinGraceWindowWhenWindowsEmpty() {
        val freshResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = null,
            activeWindowBounds = null,
            appWindows = emptyList(),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = 1_500L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.viber.voip", freshResult.targetPackage)

        val staleResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = null,
            activeWindowBounds = null,
            appWindows = emptyList(),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = 8_000L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertNull(staleResult.targetPackage)
    }

    @Test
    fun resolve_returnsNullForUnrelatedSubstantialApp() {
        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.android.chrome",
            activeWindowBounds = fullScreen,
            appWindows = listOf(
                AppWindowSnapshot("com.android.chrome", isActive = true, isFocused = true, layer = 20, bounds = fullScreen),
                AppWindowSnapshot("com.viber.voip", isActive = false, isFocused = false, layer = 10, bounds = fullScreen)
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = 400L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertNull(result.targetPackage)
    }

    @Test
    fun resolve_detectsTargetWhenOneUiLeavesLauncherFocusedBelowNullRootWindow() {
        val launcherWin = AppWindowSnapshot(
            packageName = "com.sec.android.app.launcher",
            isActive = true,
            isFocused = true,
            layer = 0,
            bounds = WindowRect(0, 0, 1080, 2316)
        )
        val unattributedWin = AppWindowSnapshot(
            packageName = null,
            isActive = false,
            isFocused = false,
            layer = 1,
            bounds = WindowRect(0, 0, 1080, 2316)
        )
        val inTarget = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = WindowRect(0, 0, 1080, 2316),
            appWindows = listOf(unattributedWin, launcherWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = 20L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.viber.voip", inTarget.targetPackage)

        // Cuando el usuario regresa al lanzador real, la ventana superior desaparece y debe bloquearse
        val backHome = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = WindowRect(0, 0, 1080, 2316),
            appWindows = listOf(launcherWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = 200L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertNull(backHome.targetPackage)
    }

    @Test
    fun resolve_preservesTargetDuringIdleReadingWhileUnattributedWindowRemainsVisible() {
        val unattributedTopWin = AppWindowSnapshot(
            packageName = null,
            isActive = true,
            isFocused = true,
            layer = 0,
            bounds = WindowRect(0, 0, 1080, 2316)
        )
        val idleReading = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.android.systemui",
            activeWindowBounds = WindowRect(0, 0, 1080, 135),
            appWindows = listOf(unattributedTopWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.instagram.android",
            recentEventAgeMs = 15_000L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.instagram.android", idleReading.targetPackage)
    }

    @Test
    fun resolve_allowsLauncherOverrideWhenActiveVisibleTargetSignalIsRecent() {
        val launcherWin = AppWindowSnapshot(
            packageName = "com.sec.android.app.launcher",
            isActive = true,
            isFocused = true,
            layer = 0,
            bounds = WindowRect(0, 0, 1080, 2340)
        )
        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = WindowRect(0, 0, 1080, 2340),
            appWindows = listOf(launcherWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = 300L,
            hasActiveVisibleTargetSignal = true,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.viber.voip", result.targetPackage)

        val idleReadingResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = WindowRect(0, 0, 1080, 2340),
            appWindows = listOf(launcherWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = 30_000L,
            hasActiveVisibleTargetSignal = true,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.viber.voip", idleReadingResult.targetPackage)

        val staleResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = WindowRect(0, 0, 1080, 2340),
            appWindows = listOf(launcherWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = ForegroundTargetResolver.UNATTRIBUTED_WINDOW_GRACE_MS + 500L,
            hasActiveVisibleTargetSignal = true,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertNull(staleResult.targetPackage)
    }

    @Test
    fun resolve_ignoresOneUiAssistantOverlayWhenRecentTargetIsActive() {
        val viberWin = AppWindowSnapshot(
            packageName = "com.viber.voip",
            isActive = false,
            isFocused = false,
            layer = 1,
            bounds = fullScreen
        )
        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.samsung.android.smartsuggestions",
            activeWindowBounds = WindowRect(0, 1800, 1080, 2100),
            appWindows = listOf(viberWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.viber.voip",
            recentEventAgeMs = 200L,
            enabledTargetPackages = enabledPkgs + "com.viber.voip",
            selfPackage = selfPkg
        )
        assertEquals("com.viber.voip", result.targetPackage)
    }
}

