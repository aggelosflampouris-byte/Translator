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

    @Volatile var lastWinProbeDiag: String = "none"
    @Volatile var lastEditProbeDiag: String = "none"
    @Volatile var lastEditableFindVia: String = "-"
    @Volatile var lastEditProbeMs: Long = 0L

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
            // Por qué: Conserva el primer bloqueo y la última traducción exitosa de cada app para no perder
            // trazabilidad en AppHistory cuando un desplazamiento posterior termina en `empty`.
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
            ServiceBridge.lastPerAppScanDebug = perAppScanDiagMap.entries.joinToString(" || ") { (k, latest) ->
                val parts = mutableListOf<String>()
                perAppFirstAbortMap[k]?.let { if (it != latest) parts.add(it) }
                perAppLastSuccessMap[k]?.let { if (it != latest && !parts.contains(it)) parts.add(it) }
                parts.add(latest)
                "$k:${parts.joinToString(">>")}"
            }
        }
    }

    fun setDraftDiag(summary: String) {
        ServiceBridge.lastDraftDebug = summary
    }

    fun setTreeScanDiag(diag: String) {
        ServiceBridge.lastScanDebug = diag
        ServiceBridge.lastTreeScanDebug = diag
    }

    fun appendTreeScanDiag(suffix: String) {
        ServiceBridge.lastScanDebug = "${ServiceBridge.lastScanDebug} | $suffix"
        ServiceBridge.lastTreeScanDebug = "${ServiceBridge.lastTreeScanDebug} | $suffix"
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
        updateProbeDebug()
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
        updateProbeDebug()
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
        updateProbeDebug()
    }

    fun recordWindowRootProbe(diag: String, root: AccessibilityNodeInfo) {
        lastWinProbeDiag = try {
            val rect = Rect().also { root.getBoundsInScreen(it) }
            "$diag(${rect.width()}x${rect.height()},ch=${root.childCount},pkg=${root.packageName?.toString()?.substringAfterLast('.') ?: "-"})"
        } catch (_: Exception) {
            "$diag(exc)"
        }
        updateProbeDebug()
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
        val msgEnabled = enabledTargetPackages.contains("com.facebook.orca")
        ServiceBridge.lastRuntimeTrace =
            "$tag | c=$connectCount s=$stateChangeCount l=$scanLoopTicks(alive=$loopAlive) e=$eventCount " +
            "svcPkgs=${enabledTargetPackages.size}(msg=$msgEnabled) scr=$isScreenScrolling($acceptedScrollCount/$ignoredScrollCount:$lastScrollDiag) " +
            "fg=${sessionState.lastForegroundTargetPackage ?: "null"} root=$lastRootOrigin($lastRootExtractDiag,src=${sessionState.meaningfulTargetSrcCount}/${sessionState.targetSrcNullCount}) " +
            "res=$lastResolveReason tgtEvt=${sessionState.lastTargetEventPkg ?: "none"} lastEvt=${lastEventPkg ?: "none"}(${lastEventType}) " +
            "seen=[${formatSeenPackages()}] ext=[$lastExternalWindowsSummary]"
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

    fun clearAll() {
        lastEventPkg = null
        lastEventType = 0
        lastEventTimeMs = 0L
        lastRootFetchMode = "-"
        lastWinFetchMode = "-"
        lastRootOrigin = "none"
        lastRootExtractDiag = "init"
        lastScrollPositionSig = null
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

