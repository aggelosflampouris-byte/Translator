package com.example.translator

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.min

data class MessageCandidate(
    val text: String,
    val bounds: Rect
)

/**
 * Representación pura e independiente de Android de un nodo textual recopilado durante el escaneo.
 *
 * Por qué: Permite validar la evaluación inferior-superior y el filtrado de solapamientos
 * directamente con pruebas JVM rápidas.
 */
data class CollectedNodeSnapshot(
    val rawRect: WindowRect,
    val expandedRect: WindowRect,
    val directText: String?,
    val descText: String?,
    val shortClass: String,
    val isScrollableContainer: Boolean,
    val childCount: Int,
    val inChatViewport: Boolean,
    val childIndices: List<Int>
)

data class EvaluatedCandidate(
    val text: String,
    val bounds: WindowRect
)

/**
 * Métricas no sensibles (sin texto privado) del recorrido del árbol de accesibilidad.
 */
class ScanStats {
    var visitedNodes: Int = 0
    var editableNodes: Int = 0
    var nodesWithText: Int = 0
    var nodesWithDesc: Int = 0
    var skippedWidget: Int = 0
    var skippedFilter: Int = 0
    var skippedTinyDesc: Int = 0
    var skippedBubbleBounds: Int = 0
    var passedCandidates: Int = 0
    private val filterReasonCounts = linkedMapOf<String, Int>()
    private val priorityChatSamples = ArrayList<String>(4)
    private val headChatSamples = ArrayList<String>(2)
    private val tailChatSamples = ArrayDeque<String>(4)
    private val chromeSamples = ArrayList<String>(6)

    fun recordFilterRejection(rawTag: String) {
        val bucket = rawTag.substringBefore('(')
        filterReasonCounts[bucket] = (filterReasonCounts[bucket] ?: 0) + 1
    }

    fun formatFilterBreakdown(): String {
        if (filterReasonCounts.isEmpty()) return ""
        return filterReasonCounts.entries.joinToString(",", prefix = "(", postfix = ")") { (k, v) -> "$k:$v" }
    }

    fun addSample(entry: String, inChatViewport: Boolean = true, highPriority: Boolean = false) {
        if (inChatViewport) {
            if (highPriority && priorityChatSamples.size < 4 && !priorityChatSamples.contains(entry)) {
                priorityChatSamples.add(entry)
            }
            if (headChatSamples.size < 2) {
                headChatSamples.add(entry)
            } else {
                // Por qué: Mantener los últimos nodos del viewport inferior permite diagnosticar
                // mensajes recientes junto al compositor cuando hay más de 6 burbujas arriba.
                if (tailChatSamples.size >= 4) {
                    tailChatSamples.removeFirst()
                }
                tailChatSamples.addLast(entry)
            }
        } else if (chromeSamples.size < 6) {
            chromeSamples.add(entry)
        }
    }

    fun formatSamples(): String {
        val combined = linkedSetOf<String>()
        for (item in priorityChatSamples) {
            if (combined.size >= 6) break
            combined.add(item)
        }
        for (item in headChatSamples) {
            if (combined.size >= 6) break
            combined.add(item)
        }
        for (item in tailChatSamples) {
            if (combined.size >= 6) break
            combined.add(item)
        }
        return if (combined.isNotEmpty()) {
            combined.joinToString(";")
        } else {
            chromeSamples.joinToString(";")
        }
    }
}

/**
 * Evaluador puro de candidatos y deduplicación espacial para nodos recolectados en una sola pasada.
 */
object NodeCandidateEvaluator {

    fun evaluateBottomUp(
        rawNodes: List<CollectedNodeSnapshot>,
        minY: Int,
        maxY: Int,
        screenWidth: Int,
        density: Float,
        configuredSource: String,
        configuredTarget: String,
        profile: ChatAppProfile,
        stats: ScanStats? = null
    ): List<EvaluatedCandidate> {
        if (rawNodes.isEmpty()) return emptyList()
        val outList = mutableListOf<EvaluatedCandidate>()
        val subtreeMaxAccepted = IntArray(rawNodes.size)

        for (i in rawNodes.indices) {
            val item = rawNodes[i]
            var maxChildTextLen = 0
            for (childIdx in item.childIndices) {
                if (childIdx in rawNodes.indices && subtreeMaxAccepted[childIdx] > maxChildTextLen) {
                    maxChildTextLen = subtreeMaxAccepted[childIdx]
                }
            }
            if (maxChildTextLen > 0) {
                val outerLen = maxOf(item.directText?.length ?: 0, item.descText?.length ?: 0)
                if (item.isScrollableContainer || item.childCount > 6 || outerLen <= maxChildTextLen + 12) {
                    subtreeMaxAccepted[i] = maxChildTextLen
                    continue
                }
            }
            val acceptedLen = evaluateCandidateNode(
                item = item,
                minY = minY,
                maxY = maxY,
                screenWidth = screenWidth,
                density = density,
                configuredSource = configuredSource,
                configuredTarget = configuredTarget,
                profile = profile,
                outList = outList,
                stats = stats
            )
            subtreeMaxAccepted[i] = maxOf(maxChildTextLen, acceptedLen)
        }
        return outList
    }

    private fun evaluateCandidateNode(
        item: CollectedNodeSnapshot,
        minY: Int,
        maxY: Int,
        screenWidth: Int,
        density: Float,
        configuredSource: String,
        configuredTarget: String,
        profile: ChatAppProfile,
        outList: MutableList<EvaluatedCandidate>,
        stats: ScanStats?
    ): Int {
        val rawRect = item.rawRect
        val expandedRect = item.expandedRect
        val directText = item.directText
        val descText = item.descText

        // Por qué: Un nodo contenedor puede traer texto corto en text y el cuerpo traducible en
        // contentDescription; si el primero se descarta, evaluamos la alternativa.
        var chosenRaw = directText ?: descText ?: return 0
        var cleanText = TranslationFilter.cleanMessageText(chosenRaw)
        var rejectReason = TranslationFilter.filterRejectionReason(
            text = cleanText,
            configuredTarget = configuredTarget,
            configuredSource = configuredSource,
            deferUnknownLatinToMlKit = true
        )
        var usedDirectText = directText != null

        if (rejectReason != null && directText != null && descText != null && descText != directText) {
            val altClean = TranslationFilter.cleanMessageText(descText)
            val altReject = TranslationFilter.filterRejectionReason(
                text = altClean,
                configuredTarget = configuredTarget,
                configuredSource = configuredSource,
                deferUnknownLatinToMlKit = true
            )
            if (altReject == null) {
                chosenRaw = descText
                cleanText = altClean
                rejectReason = null
                usedDirectText = false
            }
        }

        if (cleanText.isBlank() || rejectReason != null) {
            val tag = rejectReason ?: "emp"
            val isInterestingReject = tag == "sys" || tag == "elig" || (tag.startsWith("grk(") && !tag.endsWith("/0)"))
            stats?.let {
                it.skippedFilter++
                it.recordFilterRejection(tag)
                it.addSample(
                    "${item.shortClass}(t=${directText?.length ?: 0},d=${descText?.length ?: 0},[${rawRect.left},${rawRect.top}-${rawRect.right},${rawRect.bottom}],flt:$tag)",
                    inChatViewport = item.inChatViewport,
                    highPriority = isInterestingReject
                )
            }
            return 0
        }

        if (!usedDirectText && rawRect.width < (22 * density).toInt() && rawRect.height < (18 * density).toInt()) {
            stats?.let {
                it.skippedTinyDesc++
                it.addSample(
                    "${item.shortClass}(d=${descText?.length ?: 0},[${rawRect.left},${rawRect.top}-${rawRect.right},${rawRect.bottom}],tiny)",
                    inChatViewport = item.inChatViewport
                )
            }
            return 0
        }

        val isBubble = TranslationSenseEngine.isMessageBubble(expandedRect.left, expandedRect.right, screenWidth, profile.alignmentMode)
        val visibleMargin = (20 * density).toInt()

        if (isBubble &&
            expandedRect.width > (22 * density).toInt() &&
            expandedRect.height > (16 * density).toInt() &&
            expandedRect.bottom > minY + visibleMargin &&
            expandedRect.top < maxY - visibleMargin
        ) {
            val clampedRect = WindowRect(
                left = expandedRect.left,
                top = expandedRect.top.coerceAtLeast(minY),
                right = expandedRect.right,
                bottom = expandedRect.bottom.coerceAtMost(maxY)
            )
            outList.add(EvaluatedCandidate(cleanText, clampedRect))
            stats?.let {
                it.passedCandidates++
                it.addSample(
                    "${item.shortClass}(t=${directText?.length ?: 0},d=${descText?.length ?: 0},[${clampedRect.left},${clampedRect.top}-${clampedRect.right},${clampedRect.bottom}],ok)",
                    inChatViewport = item.inChatViewport,
                    highPriority = true
                )
            }
            return chosenRaw.length
        }

        stats?.let {
            it.skippedBubbleBounds++
            it.addSample(
                "${item.shortClass}(b=$isBubble,[${expandedRect.left},${expandedRect.top}-${expandedRect.right},${expandedRect.bottom}],bnd)",
                inChatViewport = item.inChatViewport
            )
        }
        return 0
    }

    fun filterOverlapping(candidates: List<EvaluatedCandidate>, density: Float): List<EvaluatedCandidate> {
        if (candidates.size <= 1) return candidates
        val accepted = ArrayList<EvaluatedCandidate>(candidates.size)
        val sorted = candidates.sortedByDescending { it.text.length }

        for (candidate in sorted) {
            val cRect = candidate.bounds
            var overlaps = false
            for (acc in accepted) {
                val aRect = acc.bounds
                val interLeft = maxOf(aRect.left, cRect.left)
                val interTop = maxOf(aRect.top, cRect.top)
                val interRight = min(aRect.right, cRect.right)
                val interBottom = min(aRect.bottom, cRect.bottom)
                if (interRight > interLeft && interBottom > interTop) {
                    val intersectArea = (interRight - interLeft).toLong() * (interBottom - interTop)
                    val cArea = cRect.width.toLong() * cRect.height
                    val aArea = aRect.width.toLong() * aRect.height
                    val minArea = min(cArea, aArea)
                    if (minArea > 0 && intersectArea > minArea * 0.20f) {
                        overlaps = true
                        break
                    }
                }
                val aCenterY = (aRect.top + aRect.bottom) / 2
                val cCenterY = (cRect.top + cRect.bottom) / 2
                val aContainsC = aRect.left <= cRect.left && aRect.top <= cRect.top && aRect.right >= cRect.right && aRect.bottom >= cRect.bottom && !aRect.isEmpty
                val cContainsA = cRect.left <= aRect.left && cRect.top <= aRect.top && cRect.right >= aRect.right && cRect.bottom >= aRect.bottom && !cRect.isEmpty
                if (abs(aCenterY - cCenterY) < 20 * density && (aContainsC || cContainsA)) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) {
                accepted.add(candidate)
            }
        }
        return accepted
    }
}

/**
 * Recolector unificado de una sola pasada sobre el árbol de accesibilidad de la app activa.
 */
class NodeTreeScanner(
    private val isEnabledPackage: (String) -> Boolean
) {

    fun scanVisibleMessages(
        rootNode: AccessibilityNodeInfo,
        minY: Int,
        maxY: Int,
        displayBounds: DisplayBounds,
        configuredSource: String,
        configuredTarget: String,
        profile: ChatAppProfile,
        stats: ScanStats
    ): List<MessageCandidate> {
        val rawNodes = mutableListOf<CollectedNodeSnapshot>()

        collectRawNodes(
            node = rootNode,
            minY = minY,
            maxY = maxY,
            screenWidth = displayBounds.widthPx,
            screenHeight = displayBounds.heightPx,
            density = displayBounds.density,
            profile = profile,
            stats = stats,
            outRawNodes = rawNodes
        )
        if (rawNodes.isEmpty()) return emptyList()

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = rawNodes,
            minY = minY,
            maxY = maxY,
            screenWidth = displayBounds.widthPx,
            density = displayBounds.density,
            configuredSource = configuredSource,
            configuredTarget = configuredTarget,
            profile = profile,
            stats = stats
        )

        return NodeCandidateEvaluator
            .filterOverlapping(evaluated, displayBounds.density)
            .map { MessageCandidate(it.text, Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)) }
    }

    private fun collectRawNodes(
        node: AccessibilityNodeInfo,
        minY: Int,
        maxY: Int,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        profile: ChatAppProfile,
        stats: ScanStats,
        outRawNodes: MutableList<CollectedNodeSnapshot>,
        depth: Int = 0
    ): List<Int> {
        if (depth > 64) return emptyList()
        stats.visitedNodes++
        val nodePkg = try { node.packageName?.toString() ?: "" } catch (_: Exception) { return emptyList() }
        if (nodePkg.isNotBlank() && !isEnabledPackage(nodePkg)) {
            return emptyList()
        }
        val isEditable = try { node.isEditable } catch (_: Exception) { false }
        if (isEditable) {
            stats.editableNodes++
            return emptyList()
        }

        var mergedChildIndices: MutableList<Int>? = null
        val chCount = try { node.childCount } catch (_: Exception) { 0 }
        for (i in 0 until chCount) {
            val child = AccessibilityNodeSafeOps.safeChild(node, i) ?: continue
            val childIds = collectRawNodes(
                node = child,
                minY = minY,
                maxY = maxY,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                density = density,
                profile = profile,
                stats = stats,
                outRawNodes = outRawNodes,
                depth = depth + 1
            )
            if (childIds.isNotEmpty()) {
                if (mergedChildIndices == null) {
                    mergedChildIndices = ArrayList(childIds.size)
                }
                mergedChildIndices.addAll(childIds)
            }
        }
        val collectedChildren = mergedChildIndices ?: emptyList()

        val className = try { node.className?.toString() ?: "" } catch (_: Exception) { "" }
        if (AccessibilityNodeSafeOps.isIgnoredWidgetClass(className)) {
            stats.skippedWidget++
            return collectedChildren
        }

        val isScrollableContainer = AccessibilityNodeSafeOps.isScrollableOrToolbarClass(node, className)
        val directText = try { node.text?.toString()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
        val descText = if (!isScrollableContainer) {
            try { node.contentDescription?.toString()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
        } else {
            null
        }
        if (directText != null) stats.nodesWithText++
        if (descText != null) stats.nodesWithDesc++

        if (directText.isNullOrBlank() && descText.isNullOrBlank()) {
            return collectedChildren
        }

        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Exception) {
            return collectedChildren
        }
        if (rect.right <= 0 || rect.left >= screenWidth) {
            stats.skippedBubbleBounds++
            return collectedChildren
        }

        val viewportPad = (16 * density).toInt()
        val inChatViewport = rect.bottom > minY + viewportPad && rect.top < maxY - viewportPad
        val expandedRect = resolveExpandedBounds(node, rect, screenWidth, screenHeight, density, profile)
        val newIndex = outRawNodes.size
        outRawNodes.add(
            CollectedNodeSnapshot(
                rawRect = WindowRect(rect.left, rect.top, rect.right, rect.bottom),
                expandedRect = WindowRect(expandedRect.left, expandedRect.top, expandedRect.right, expandedRect.bottom),
                directText = directText,
                descText = descText,
                shortClass = className.substringAfterLast('.'),
                isScrollableContainer = isScrollableContainer,
                childCount = chCount,
                inChatViewport = inChatViewport,
                childIndices = collectedChildren
            )
        )
        return listOf(newIndex)
    }

    private fun resolveExpandedBounds(
        node: AccessibilityNodeInfo,
        rawRect: Rect,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        profile: ChatAppProfile
    ): Rect {
        val result = Rect(rawRect)
        // Por qué: Solo expande al contenedor padre en apps con burbujas aisladas; en filas anchas
        // (Discord o Messenger) limita la expansión para no invadir cabeceras ni avatares.
        try {
            var container = node.parent
            val maxLevels = if (profile.expandContainerToParent) 1 else 0
            val maxParentWidthRatio = if (profile.expandContainerToParent) 0.95f else 0.85f
            for (level in 0..maxLevels) {
                if (container != null) {
                    val pRect = Rect()
                    container.getBoundsInScreen(pRect)
                    val safeExpansion = profile.expandContainerToParent ||
                        (pRect.width() <= (result.width() * 1.35f) && pRect.height() <= (result.height() * 1.50f))
                    if (safeExpansion &&
                        pRect.width() <= screenWidth * maxParentWidthRatio &&
                        pRect.height() <= screenHeight &&
                        pRect.width() >= result.width() &&
                        pRect.height() >= result.height() &&
                        Rect.intersects(pRect, result)
                    ) {
                        result.set(pRect)
                        break
                    }
                    container = container.parent
                }
            }
        } catch (_: Exception) {
            // Ignora nodo padre reciclado durante transición
        }
        AccessibilityNodeSafeOps.normalizeFullWidthMessageBounds(result, screenWidth, screenHeight, density)
        return result
    }
}

