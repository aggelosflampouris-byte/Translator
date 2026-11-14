package com.example.translator

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Registra métricas estructurales y de rendimiento sin almacenar texto ni datos personales del usuario.
 *
 * Por qué: Separa los acumuladores de depuración del flujo principal de eventos y traducciones,
 * evitando sobrecargar el hilo principal con recorridos profundos en cada evento de accesibilidad.
 */
class DiagnosticRecorder {

    @Volatile var connectCount: Int = 0
    @Volatile var stateChangeCount: Int = 0
    @Volatile var scanLoopTicks: Int = 0
    @Volatile var eventCount: Int = 0
    @Volatile var lastEventPkg: String? = null
    @Volatile var lastEventType: Int = 0
    @Volatile var lastEventTimeMs: Long = 0L

    @Volatile var lastRootFetchMode: String = "-"
    @Volatile var lastWinFetchMode: String = "-"
    @Volatile var lastRootOrigin: String = "none"
    @Volatile var lastRootExtractDiag: String = "init"

    @Volatile var acceptedScrollCount: Int = 0
    @Volatile var ignoredScrollCount: Int = 0
    @Volatile var lastScrollDiag: String = "none"
    @Volatile var lastScrollPositionSig: ScrollPositionSignature? = null

    @Volatile var lastExternalWindowsSummary: String = "none"
    @Volatile var lastResolveReason: String = "init"
    @Volatile var lastTraceUpdateMs: Long = 0L

    private data class ScanStatsSnapshot(
        val fgPkg: String,
        val profileId: String,
        val rootOrigin: String,
        val sourceLang: String,
        val targetLang: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val density: Float,
        val topInset: Int,
        val effectiveBottom: Int,
        val stats: ScanStats,
        val dedupedCount: Int,
        val outcome: String
    ) {
        fun format(): String {
            return "fg=$fgPkg($profileId) root=$rootOrigin src=$sourceLang->$targetLang " +
                "scr=${screenWidth}x$screenHeight d=${"%.2f".format(density)} y=$topInset..$effectiveBottom " +
                "vis=${stats.visitedNodes} ed=${stats.editableNodes} t=${stats.nodesWithText} dsc=${stats.nodesWithDesc} " +
                "skW=${stats.skippedWidget} skF=${stats.skippedFilter}${stats.formatFilterBreakdown()} " +
                "skTiny=${stats.skippedTinyDesc} skBnd=${stats.skippedBubbleBounds} " +
                "cands=${stats.passedCandidates}/$dedupedCount smp=${stats.formatSamples()} | $outcome"
        }
    }

    @Volatile var lastWinProbeDiag: String = "none"
    @Volatile var lastEditProbeDiag: String = "none"
    @Volatile var lastEditableFindVia: String = "-"
    @Volatile var lastEditProbeMs: Long = 0L

    @Volatile private var lastTraceTag: String = "init"
    @Volatile private var lastTraceSvcPkgsCount: Int = 0
    @Volatile private var lastTraceLoopAlive: Boolean = false
    @Volatile private var lastTraceScrolling: Boolean = false
    @Volatile private var lastTraceFg: String? = null
    @Volatile private var lastTraceTgtEvt: String? = null
    @Volatile private var lastTraceMeaningfulSrc: Int = 0
    @Volatile private var lastTraceNullSrc: Int = 0

    @Volatile private var latestDraftDiag: String = "none"
    @Volatile private var latestAbortDiag: String? = null
    @Volatile private var latestScanIsAbort: Boolean = false
    @Volatile private var latestScanSnapshot: ScanStatsSnapshot? = null
    private val latestOutcomeParts = ArrayList<String>(4)

    private val srcProbeHistory = ArrayDeque<String>(4)
    private val seenExternalPackages = LinkedHashMap<String, Int>(8)
    private val perAppScanDiagMap = LinkedHashMap<String, String>(4)
    private val perAppFirstAbortMap = LinkedHashMap<String, String>(4)
    private val perAppLastSuccessMap = LinkedHashMap<String, String>(4)

    fun recordPerAppDiag(pkg: String, summary: String) {
        if (pkg.isBlank()) return
        val shortPkg = pkg.substringAfterLast('.')
        val isSuccessProgress = summary.startsWith("imm=") ||
            summary.startsWith("ov=") ||
            summary.startsWith("tr(")
        synchronized(perAppScanDiagMap) {
            // Por qué: Conserva el primer bloqueo y la última traducción exitosa de cada app sin formatear cadenas en el hilo caliente.
            if (isSuccessProgress) {
                perAppLastSuccessMap[shortPkg] = summary
            } else if (!perAppFirstAbortMap.containsKey(shortPkg)) {
                perAppFirstAbortMap[shortPkg] = summary
            }
            perAppScanDiagMap.remove(shortPkg)
            if (perAppScanDiagMap.size >= 3) {
                val oldestKey = perAppScanDiagMap.keys.firstOrNull()
                if (oldestKey != null) {
                    perAppScanDiagMap.remove(oldestKey)
                    perAppFirstAbortMap.remove(oldestKey)
                    perAppLastSuccessMap.remove(oldestKey)
                }
            }
            perAppScanDiagMap[shortPkg] = summary
        }
    }

    fun setDraftDiag(summary: String) {
        latestDraftDiag = summary
    }

    fun recordScanStatsSnapshot(
        fgPkg: String,
        profileId: String,
        rootOrigin: String,
        sourceLang: String,
        targetLang: String,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        topInset: Int,
        effectiveBottom: Int,
        stats: ScanStats,
        dedupedCount: Int,
        outcome: String
    ) {
        synchronized(latestOutcomeParts) {
            latestOutcomeParts.clear()
        }
        latestScanIsAbort = false
        latestScanSnapshot = ScanStatsSnapshot(
            fgPkg = fgPkg,
            profileId = profileId,
            rootOrigin = rootOrigin,
            sourceLang = sourceLang,
            targetLang = targetLang,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
            topInset = topInset,
            effectiveBottom = effectiveBottom,
            stats = stats,
            dedupedCount = dedupedCount,
            outcome = outcome
        )
    }

    fun setAbortDiag(diag: String, isTreeContext: Boolean = false) {
        latestAbortDiag = diag
        latestScanIsAbort = true
        if (isTreeContext && latestScanSnapshot == null) {
            ServiceBridge.lastTreeScanDebug = diag
        }
    }

    fun setTreeScanDiag(diag: String) {
        latestAbortDiag = diag
        latestScanIsAbort = true
        ServiceBridge.lastScanDebug = diag
        ServiceBridge.lastTreeScanDebug = diag
    }

    fun appendTreeScanDiag(suffix: String) {
        synchronized(latestOutcomeParts) {
            if (latestOutcomeParts.size < 4) {
                latestOutcomeParts.add(suffix)
            }
        }
    }

    fun recordSeenExternalPackage(pkg: String) {
        synchronized(seenExternalPackages) {
            if (!seenExternalPackages.containsKey(pkg) && seenExternalPackages.size >= 8) {
                val oldestKey = seenExternalPackages.keys.firstOrNull()
                if (oldestKey != null) seenExternalPackages.remove(oldestKey)
            }
            seenExternalPackages[pkg] = (seenExternalPackages[pkg] ?: 0) + 1
        }
    }

    fun resetPerPackageProbes() {
        synchronized(srcProbeHistory) {
            srcProbeHistory.clear()
        }
        lastWinProbeDiag = "none"
        lastEditProbeDiag = "none"
        lastEditableFindVia = "-"
    }

    fun recordSrcSample(eventType: Int, node: AccessibilityNodeInfo) {
        val needMore = synchronized(srcProbeHistory) { srcProbeHistory.size < 3 }
        if (!needMore) return
        val entry = try {
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val rect = Rect().also { node.getBoundsInScreen(it) }
            "e($eventType):$cls(${rect.width()}x${rect.height()},ed=${node.isEditable},vis=${node.isVisibleToUser},ch=${node.childCount})"
        } catch (e: Exception) {
            "e($eventType):exc(${e.javaClass.simpleName})"
        }
        synchronized(srcProbeHistory) {
            if (srcProbeHistory.contains(entry) || srcProbeHistory.size >= 3) return
            srcProbeHistory.addLast(entry)
        }
    }

    fun recordEditableProbe(via: String, node: AccessibilityNodeInfo) {
        val nowMs = SystemClock.uptimeMillis()
        if (lastEditProbeDiag != "none" && nowMs - lastEditProbeMs < 500L) return
        lastEditProbeMs = nowMs
        lastEditProbeDiag = try {
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val pkg = node.packageName?.toString()?.substringAfterLast('.') ?: "-"
            "$via:$cls(${rect.width()}x${rect.height()},ed=${node.isEditable},pkg=$pkg)"
        } catch (e: Exception) {
            "$via:exc(${e.javaClass.simpleName})"
        }
    }

    fun recordWindowRootProbe(diag: String, root: AccessibilityNodeInfo) {
        lastWinProbeDiag = try {
            val rect = Rect().also { root.getBoundsInScreen(it) }
            "$diag(${rect.width()}x${rect.height()},ch=${root.childCount},pkg=${root.packageName?.toString()?.substringAfterLast('.') ?: "-"})"
        } catch (_: Exception) {
            "$diag(exc)"
        }
    }

    fun updateProbeDebug() {
        val srcSummary = synchronized(srcProbeHistory) {
            srcProbeHistory.joinToString(";").ifEmpty { "none" }
        }
        ServiceBridge.lastProbeDebug =
            "win=$lastWinProbeDiag | edit=$lastEditProbeDiag | src=[$srcSummary]"
    }

    fun updateRuntimeTrace(
        tag: String,
        enabledTargetPackages: Set<String>,
        loopAlive: Boolean,
        isScreenScrolling: Boolean,
        sessionState: ForegroundSessionState
    ) {
        lastTraceUpdateMs = SystemClock.uptimeMillis()
        lastTraceTag = tag
        lastTraceSvcPkgsCount = enabledTargetPackages.size
        lastTraceLoopAlive = loopAlive
        lastTraceScrolling = isScreenScrolling
        lastTraceFg = sessionState.lastForegroundTargetPackage
        lastTraceTgtEvt = sessionState.lastTargetEventPkg
        lastTraceMeaningfulSrc = sessionState.meaningfulTargetSrcCount
        lastTraceNullSrc = sessionState.targetSrcNullCount
    }

    fun maybeUpdateRuntimeTrace(
        throttled: Boolean,
        enabledTargetPackages: Set<String>,
        loopAlive: Boolean,
        isScreenScrolling: Boolean,
        sessionState: ForegroundSessionState
    ) {
        val now = SystemClock.uptimeMillis()
        if (!throttled || now - lastTraceUpdateMs >= 450L) {
            updateRuntimeTrace("evt", enabledTargetPackages, loopAlive, isScreenScrolling, sessionState)
        }
    }

    /**
     * Formatea de manera diferida todas las trazas y métricas en [ServiceBridge] solo cuando se solicitan.
     *
     * Por qué: Evita miles de concatenaciones y `String.format` por minuto en uso normal sin perder detalle en soporte.
     */
    fun flushToServiceBridge() {
        updateProbeDebug()
        ServiceBridge.lastDraftDebug = latestDraftDiag
        ServiceBridge.lastRuntimeTrace =
            "$lastTraceTag | c=$connectCount s=$stateChangeCount l=$scanLoopTicks(alive=$lastTraceLoopAlive) e=$eventCount " +
            "svcPkgs=$lastTraceSvcPkgsCount scr=$lastTraceScrolling($acceptedScrollCount/$ignoredScrollCount:$lastScrollDiag) " +
            "fg=${lastTraceFg ?: "null"} root=$lastRootOrigin($lastRootExtractDiag,src=$lastTraceMeaningfulSrc/$lastTraceNullSrc) " +
            "res=$lastResolveReason tgtEvt=${lastTraceTgtEvt ?: "none"} lastEvt=${lastEventPkg ?: "none"}($lastEventType) " +
            "seen=[${formatSeenPackages()}] ext=[$lastExternalWindowsSummary]"

        synchronized(perAppScanDiagMap) {
            ServiceBridge.lastPerAppScanDebug = perAppScanDiagMap.entries.joinToString(" || ") { (k, latest) ->
                val parts = mutableListOf<String>()
                perAppFirstAbortMap[k]?.let { if (it != latest) parts.add(it) }
                perAppLastSuccessMap[k]?.let { if (it != latest && !parts.contains(it)) parts.add(it) }
                parts.add(latest)
                "$k:${parts.joinToString(">>")}"
            }
        }

        val snapshot = latestScanSnapshot
        val formattedTree = if (snapshot != null) {
            val extras = synchronized(latestOutcomeParts) {
                if (latestOutcomeParts.isEmpty()) "" else " | ${latestOutcomeParts.joinToString(" | ")}"
            }
            snapshot.format() + extras
        } else {
            null
        }

        if (formattedTree != null) {
            ServiceBridge.lastTreeScanDebug = formattedTree
        }
        val abortText = latestAbortDiag
        if (latestScanIsAbort && abortText != null) {
            ServiceBridge.lastScanDebug = abortText
        } else if (formattedTree != null) {
            ServiceBridge.lastScanDebug = formattedTree
        }
    }

    fun clearAll() {
        lastEventPkg = null
        lastEventType = 0
        lastEventTimeMs = 0L
        lastRootFetchMode = "-"
        lastWinFetchMode = "-"
        lastRootOrigin = "none"
        lastRootExtractDiag = "init"
        lastScrollPositionSig = null
        latestDraftDiag = "none"
        latestAbortDiag = null
        latestScanIsAbort = false
        latestScanSnapshot = null
        synchronized(latestOutcomeParts) {
            latestOutcomeParts.clear()
        }
        synchronized(perAppScanDiagMap) {
            perAppScanDiagMap.clear()
            perAppFirstAbortMap.clear()
            perAppLastSuccessMap.clear()
        }
        synchronized(srcProbeHistory) {
            srcProbeHistory.clear()
        }
        synchronized(seenExternalPackages) {
            seenExternalPackages.clear()
        }
        ServiceBridge.lastPerAppScanDebug = ""
        ServiceBridge.lastDraftDebug = "none"
    }

    private fun formatSeenPackages(): String {
        synchronized(seenExternalPackages) {
            if (seenExternalPackages.isEmpty()) return "none"
            return seenExternalPackages.entries.joinToString(",") { (pkg, count) ->
                val shortPkg = pkg.removePrefix("com.facebook.").removePrefix("com.samsung.android.")
                "$shortPkg#$count"
            }
        }
    }
}

