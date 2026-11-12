package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTargetResolverTest {

    private val selfPkg = "com.example.translator"
    private val enabledPkgs = setOf("com.whatsapp", "com.facebook.orca", "org.telegram.messenger")
    private val fullScreen = WindowRect(0, 0, 1080, 2340)

    @Test
    fun resolve_returnsMessenger_whenRootInActiveWindowMatches() {
        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.facebook.orca",
            activeWindowBounds = fullScreen,
            appWindows = emptyList(),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = null,
            recentEventAgeMs = Long.MAX_VALUE,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.facebook.orca", result.targetPackage)
    }

    @Test
    fun resolve_ignoresThinOemEdgeHandleAboveMessenger() {
        val edgeHandle = AppWindowSnapshot(
            packageName = "com.samsung.android.app.cocktailbarservice",
            isActive = false,
            isFocused = false,
            layer = 99,
            bounds = WindowRect(1050, 400, 1080, 950)
        )
        val messengerWin = AppWindowSnapshot(
            packageName = "com.facebook.orca",
            isActive = false,
            isFocused = true,
            layer = 10,
            bounds = fullScreen
        )
        val result = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.samsung.android.honeyboard",
            activeWindowBounds = WindowRect(0, 1500, 1080, 2340),
            appWindows = listOf(messengerWin, edgeHandle),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = null,
            recentEventAgeMs = Long.MAX_VALUE,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.facebook.orca", result.targetPackage)
    }

    @Test
    fun resolve_ignoresThinLauncherTaskbarOverMessenger_butBlocksFullscreenLauncher() {
        val taskbarStrip = WindowRect(0, 2220, 1080, 2340)
        val messengerWin = AppWindowSnapshot(
            packageName = "com.facebook.orca",
            isActive = false,
            isFocused = false,
            layer = 10,
            bounds = fullScreen
        )
        val stripResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = taskbarStrip,
            appWindows = listOf(messengerWin),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = null,
            recentEventAgeMs = Long.MAX_VALUE,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.facebook.orca", stripResult.targetPackage)

        val homeResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = "com.sec.android.app.launcher",
            activeWindowBounds = fullScreen,
            appWindows = listOf(
                AppWindowSnapshot("com.sec.android.app.launcher", isActive = true, isFocused = true, layer = 20, bounds = fullScreen),
                messengerWin
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.facebook.orca",
            recentEventAgeMs = 500L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertNull(homeResult.targetPackage)
    }

    @Test
    fun resolve_blocksSelfMainActivity_butAllowsSmallSelfBubbleOverMessenger() {
        val mainActivityResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = selfPkg,
            activeWindowBounds = fullScreen,
            appWindows = listOf(
                AppWindowSnapshot(selfPkg, isActive = true, isFocused = true, layer = 30, bounds = fullScreen),
                AppWindowSnapshot("com.facebook.orca", isActive = false, isFocused = false, layer = 10, bounds = fullScreen)
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.facebook.orca",
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
                AppWindowSnapshot("com.facebook.orca", isActive = false, isFocused = true, layer = 10, bounds = fullScreen)
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = null,
            recentEventAgeMs = Long.MAX_VALUE,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.facebook.orca", bubbleResult.targetPackage)
    }

    @Test
    fun resolve_usesRecentEventFallbackOnlyWithinGraceWindowWhenWindowsEmpty() {
        val freshResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = null,
            activeWindowBounds = null,
            appWindows = emptyList(),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.facebook.orca",
            recentEventAgeMs = 1_500L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertEquals("com.facebook.orca", freshResult.targetPackage)

        val staleResult = ForegroundTargetResolver.resolve(
            activeWindowPkg = null,
            activeWindowBounds = null,
            appWindows = emptyList(),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.facebook.orca",
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
                AppWindowSnapshot("com.facebook.orca", isActive = false, isFocused = false, layer = 10, bounds = fullScreen)
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            recentEventPkg = "com.facebook.orca",
            recentEventAgeMs = 400L,
            enabledTargetPackages = enabledPkgs,
            selfPackage = selfPkg
        )
        assertNull(result.targetPackage)
    }
}

