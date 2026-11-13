package com.example.translator

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.min

data class MessageCandidate(
    val text: String,
    val bounds: Rect,
    val isQuoted: Boolean = false,
    val maxBottomPx: Int = Int.MAX_VALUE
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
    val childIndices: List<Int>,
    val rowId: Int = 0,
    val parentNodeId: Int = 0
)

data class EvaluatedCandidate(
    val text: String,
    val bounds: WindowRect,
    val rawBounds: WindowRect = bounds,
    val rowId: Int = 0,
    val parentNodeId: Int = 0,
    val isQuoted: Boolean = false,
    val maxBottomPx: Int = Int.MAX_VALUE
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
        val subtreeAcceptedCounts = IntArray(rawNodes.size)

        for (i in rawNodes.indices) {
            val item = rawNodes[i]
            var maxChildTextLen = 0
            var acceptedChildCount = 0
            for (childIdx in item.childIndices) {
                if (childIdx in rawNodes.indices) {
                    if (subtreeMaxAccepted[childIdx] > maxChildTextLen) {
                        maxChildTextLen = subtreeMaxAccepted[childIdx]
                    }
                    acceptedChildCount += subtreeAcceptedCounts[childIdx]
                }
            }
            if (maxChildTextLen > 0) {
                val outerLen = maxOf(item.directText?.length ?: 0, item.descText?.length ?: 0)
                // Por qué: Si subnodos textuales ya fueron aceptados dentro de la burbuja, suprime contenedores
                // envolventes (sobre todo descripciones TalkBack que concatenan remitente, cita y respuesta).
                if (item.isScrollableContainer ||
                    item.childCount > 6 ||
                    acceptedChildCount >= 2 ||
                    item.directText.isNullOrBlank() ||
                    outerLen <= maxChildTextLen + 12
                ) {
                    subtreeMaxAccepted[i] = maxChildTextLen
                    subtreeAcceptedCounts[i] = acceptedChildCount
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
            subtreeAcceptedCounts[i] = if (acceptedLen > 0) acceptedChildCount + 1 else acceptedChildCount
        }
        return resolveSiblingExpansionsAndReplyStructure(
            candidates = outList,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = configuredSource,
            profile = profile
        )
    }

    /**
     * Evita que una expansión al contenedor padre invada hermanos dentro de la misma fila grupal,
     * descarta cabeceras de remitente encima del mensaje y etiqueta citas vs respuesta principal.
     */
    private fun resolveSiblingExpansionsAndReplyStructure(
        candidates: List<EvaluatedCandidate>,
        minY: Int,
        maxY: Int,
        screenWidth: Int,
        density: Float,
        configuredSource: String,
        profile: ChatAppProfile
    ): List<EvaluatedCandidate> {
        if (candidates.isEmpty()) return emptyList()

        // 1. Degrada bounds expandidos si invaden el rawBounds de otro candidato (ej. cita vs respuesta corta)
        val nonInvading = ArrayList<EvaluatedCandidate>(candidates.size)
        for (idx in candidates.indices) {
            val cand = candidates[idx]
            if (cand.bounds == cand.rawBounds) {
                nonInvading.add(cand)
                continue
            }
            var collidesWithSibling = false
            for (otherIdx in candidates.indices) {
                if (otherIdx == idx) continue
                val otherRaw = candidates[otherIdx].rawBounds
                val sameOrUnknownRow = cand.rowId == 0 || candidates[otherIdx].rowId == 0 || cand.rowId == candidates[otherIdx].rowId
                if (!sameOrUnknownRow) continue
                if (overlapRatioOfSmaller(cand.bounds, otherRaw) > 0.10f) {
                    collidesWithSibling = true
                    break
                }
            }
            if (!collidesWithSibling) {
                nonInvading.add(cand)
            } else {
                val tight = clampRectToViewport(cand.rawBounds, minY, maxY)
                if (TranslationSenseEngine.isMessageBubble(tight.left, tight.right, screenWidth, profile.alignmentMode) &&
                    tight.width > (22 * density).toInt() &&
                    tight.height > (14 * density).toInt()
                ) {
                    nonInvading.add(cand.copy(bounds = tight))
                } else {
                    nonInvading.add(cand)
                }
            }
        }

        // 2. Dentro de cada fila de chat (rowId > 0), descarta cabeceras de remitente encima del cuerpo
        // y marca citas superiores con `isQuoted = true`.
        val kept = ArrayList<EvaluatedCandidate>(nonInvading.size)
        val byRow = nonInvading.groupBy { it.rowId }
        for ((rowId, group) in byRow) {
            if (rowId <= 0 || group.size <= 1) {
                kept.addAll(group)
                continue
            }
            val sortedRow = group.sortedWith(compareBy<EvaluatedCandidate> { it.bounds.top }.thenBy { it.bounds.left })
            val bodyOrKeep = ArrayList<EvaluatedCandidate>(sortedRow.size)
            for (i in sortedRow.indices) {
                val c = sortedRow[i]
                val isAboveAnotherCandidate = i < sortedRow.lastIndex &&
                    sortedRow[i + 1].bounds.top >= c.bounds.top + (10 * density).toInt()
                if (isAboveAnotherCandidate && TranslationSenseEngine.isLikelyRowSenderHeader(c.text, configuredSource)) {
                    continue
                }
                bodyOrKeep.add(c)
            }
            val finalRow = if (bodyOrKeep.isNotEmpty()) bodyOrKeep else sortedRow
            if (finalRow.size <= 1) {
                kept.addAll(finalRow)
            } else {
                val mainBody = finalRow.last()
                val quoteIndentPx = (2 * density).toInt()
                for (i in finalRow.indices) {
                    val item = finalRow[i]
                    val isUpperReplyQuote = i < finalRow.lastIndex &&
                        item.bounds.bottom <= mainBody.bounds.top + (14 * density).toInt() &&
                        (item.bounds.left >= mainBody.bounds.left + quoteIndentPx ||
                            (item.parentNodeId != 0 && mainBody.parentNodeId != 0 && item.parentNodeId != mainBody.parentNodeId))
                    kept.add(item.copy(isQuoted = isUpperReplyQuote))
                }
            }
        }
        return kept
    }

    private fun clampRectToViewport(rect: WindowRect, minY: Int, maxY: Int): WindowRect {
        val safeMaxY = maxOf(minY, maxY)
        val top = rect.top.coerceIn(minY, safeMaxY)
        val bottom = rect.bottom.coerceIn(top, safeMaxY)
        return WindowRect(
            left = rect.left,
            top = top,
            right = rect.right,
            bottom = bottom
        )
    }

    private fun overlapRatioOfSmaller(a: WindowRect, b: WindowRect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val intersectArea = (interRight - interLeft).toLong() * (interBottom - interTop)
        val minArea = min(a.width.toLong() * a.height, b.width.toLong() * b.height)
        return if (minArea > 0L) intersectArea.toFloat() / minArea.toFloat() else 0f
    }

    fun assignMaxBottomBounds(candidates: List<EvaluatedCandidate>, maxY: Int, density: Float): List<EvaluatedCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedWith(compareBy<EvaluatedCandidate> { it.bounds.top }.thenBy { it.bounds.left })
        val gapPx = (3 * density).toInt()
        val horizSlopPx = (8 * density).toInt()
        val verticalStepPx = (14 * density).toInt()
        return sorted.mapIndexed { idx, curr ->
            var limit = maxY
            for (nextIdx in (idx + 1) until sorted.size) {
                val next = sorted[nextIdx]
                if (next.bounds.top < curr.bounds.top + verticalStepPx) continue
                val horizOverlap = min(curr.bounds.right, next.bounds.right) > maxOf(curr.bounds.left, next.bounds.left) - horizSlopPx
                if (horizOverlap) {
                    limit = min(limit, (next.bounds.top - gapPx).coerceAtLeast(curr.bounds.top + (20 * density).toInt()))
                    break
                }
            }
            curr.copy(maxBottomPx = limit)
        }
    }

    internal data class SelectedCandidateText(
        val cleanText: String,
        val rawLength: Int,
        val usedDirectText: Boolean,
        val rejectReason: String?
    )

    internal fun selectCandidateText(
        directText: String?,
        descText: String?,
        configuredSource: String,
        configuredTarget: String,
        deferUnknownLatinToMlKit: Boolean
    ): SelectedCandidateText? {
        var chosenRaw = directText ?: descText ?: return null
        var cleanText = TranslationFilter.cleanMessageText(chosenRaw).trim()
        var rejectReason = TranslationFilter.filterRejectionReason(
            text = cleanText,
            configuredTarget = configuredTarget,
            configuredSource = configuredSource,
            deferUnknownLatinToMlKit = deferUnknownLatinToMlKit,
            alreadyCleaned = true
        )
        var usedDirectText = directText != null

        if (rejectReason != null && directText != null && descText != null && descText != directText) {
            val altClean = TranslationFilter.cleanMessageText(descText).trim()
            val altReject = TranslationFilter.filterRejectionReason(
                text = altClean,
                configuredTarget = configuredTarget,
                configuredSource = configuredSource,
                deferUnknownLatinToMlKit = deferUnknownLatinToMlKit,
                alreadyCleaned = true
            )
            if (altReject == null) {
                chosenRaw = descText
                cleanText = altClean
                rejectReason = null
                usedDirectText = false
            }
        }

        return SelectedCandidateText(
            cleanText = cleanText,
            rawLength = chosenRaw.length,
            usedDirectText = usedDirectText,
            rejectReason = rejectReason
        )
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
        val selected = selectCandidateText(
            directText = directText,
            descText = descText,
            configuredSource = configuredSource,
            configuredTarget = configuredTarget,
            deferUnknownLatinToMlKit = true
        ) ?: return 0
        val cleanText = selected.cleanText
        val rejectReason = selected.rejectReason
        val usedDirectText = selected.usedDirectText

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
            val clampedRect = clampRectToViewport(expandedRect, minY, maxY)
            val clampedRaw = clampRectToViewport(rawRect, minY, maxY)
            outList.add(
                EvaluatedCandidate(
                    text = cleanText,
                    bounds = clampedRect,
                    rawBounds = clampedRaw,
                    rowId = item.rowId,
                    parentNodeId = item.parentNodeId
                )
            )
            stats?.let {
                it.passedCandidates++
                it.addSample(
                    "${item.shortClass}(t=${directText?.length ?: 0},d=${descText?.length ?: 0},[${clampedRect.left},${clampedRect.top}-${clampedRect.right},${clampedRect.bottom}],ok)",
                    inChatViewport = item.inChatViewport,
                    highPriority = true
                )
            }
            return selected.rawLength
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
                    if (minArea > 0 && intersectArea > minArea * 0.28f) {
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

        val rowGen = IntArray(1)
        val visitGen = intArrayOf(1)
        collectRawNodes(
            node = rootNode,
            minY = minY,
            maxY = maxY,
            screenWidth = displayBounds.widthPx,
            screenHeight = displayBounds.heightPx,
            density = displayBounds.density,
            configuredTarget = configuredTarget,
            profile = profile,
            stats = stats,
            outRawNodes = rawNodes,
            currentRowId = 0,
            currentParentNodeId = 0,
            rowGen = rowGen,
            visitGen = visitGen
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

        val filtered = NodeCandidateEvaluator.filterOverlapping(evaluated, displayBounds.density)
        return NodeCandidateEvaluator
            .assignMaxBottomBounds(filtered, maxY, displayBounds.density)
            .map {
                MessageCandidate(
                    text = it.text,
                    bounds = Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom),
                    isQuoted = it.isQuoted,
                    maxBottomPx = it.maxBottomPx
                )
            }
    }

    /**
     * Extracts and validates a clicked message node through the same sanitization, filtering,
     * and bubble-geometry rules used by tree scans.
     */
    fun extractClickedCandidate(
        sourceNode: AccessibilityNodeInfo,
        displayBounds: DisplayBounds,
        configuredSource: String,
        configuredTarget: String,
        profile: ChatAppProfile
    ): MessageCandidate? {
        val nodePkg = try { sourceNode.packageName?.toString() ?: "" } catch (_: Exception) { "" }
        if (nodePkg.isNotBlank() && !isEnabledPackage(nodePkg)) return null

        val nodeWithText = AccessibilityNodeSafeOps.findFirstTextChild(sourceNode) ?: sourceNode
        val directText = try { nodeWithText.text?.toString()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
        val descText = try { nodeWithText.contentDescription?.toString()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

        val selected = NodeCandidateEvaluator.selectCandidateText(
            directText = directText,
            descText = descText,
            configuredSource = configuredSource,
            configuredTarget = configuredTarget,
            deferUnknownLatinToMlKit = true
        ) ?: return null
        if (selected.cleanText.isBlank() || selected.rejectReason != null) return null

        val rect = Rect()
        try {
            nodeWithText.getBoundsInScreen(rect)
        } catch (_: Exception) {
            return null
        }
        AccessibilityNodeSafeOps.normalizeFullWidthMessageBounds(
            rect,
            displayBounds.widthPx,
            displayBounds.heightPx,
            displayBounds.density
        )
        if (!TranslationSenseEngine.isMessageBubble(rect, displayBounds.widthPx, profile.alignmentMode)) return null

        return MessageCandidate(selected.cleanText, rect)
    }

    private fun collectRawNodes(
        node: AccessibilityNodeInfo,
        minY: Int,
        maxY: Int,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        configuredTarget: String,
        profile: ChatAppProfile,
        stats: ScanStats,
        outRawNodes: MutableList<CollectedNodeSnapshot>,
        currentRowId: Int = 0,
        currentParentNodeId: Int = 0,
        rowGen: IntArray = IntArray(1),
        visitGen: IntArray = intArrayOf(1),
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

        val myNodeId = visitGen[0]++
        val className = try { node.className?.toString() ?: "" } catch (_: Exception) { "" }
        val isScrollableContainer = AccessibilityNodeSafeOps.isScrollableOrToolbarClass(node, className)
        // Por qué: Identifica la lista vertical de mensajes para asignar un `rowId` estable
        // a cada fila hija y aislar citas respecto a respuestas cortas dentro de la misma burbuja.
        val isChatListContainer = isScrollableContainer && currentRowId == 0 && run {
            if (className.contains("RecyclerView", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true)
            ) {
                true
            } else {
                val listRect = Rect()
                try {
                    node.getBoundsInScreen(listRect)
                    listRect.height() >= (screenHeight * 0.35f).toInt()
                } catch (_: Exception) {
                    false
                }
            }
        }

        var mergedChildIndices: MutableList<Int>? = null
        val chCount = try { node.childCount } catch (_: Exception) { 0 }
        for (i in 0 until chCount) {
            val child = AccessibilityNodeSafeOps.safeChild(node, i) ?: continue
            val childRowId = if (isChatListContainer) ++rowGen[0] else currentRowId
            val childIds = collectRawNodes(
                node = child,
                minY = minY,
                maxY = maxY,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                density = density,
                configuredTarget = configuredTarget,
                profile = profile,
                stats = stats,
                outRawNodes = outRawNodes,
                currentRowId = childRowId,
                currentParentNodeId = myNodeId,
                rowGen = rowGen,
                visitGen = visitGen,
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

        if (AccessibilityNodeSafeOps.isIgnoredWidgetClass(className)) {
            stats.skippedWidget++
            return collectedChildren
        }
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
        // Por qué: Solo consulta node.parent vía Binder cuando hay texto plausible tras descartar relojes/UI griega.
        val expandedRect = if (TranslationFilter.hasPlausibleLetterCandidate(directText, descText, configuredTarget)) {
            resolveExpandedBounds(node, rect, screenWidth, screenHeight, density, profile)
        } else {
            rect
        }
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
                childIndices = collectedChildren,
                rowId = currentRowId,
                parentNodeId = currentParentNodeId
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
        // Por qué: Solo expande al contenedor padre inmediato bajo márgenes verticales estrictos
        // para que respuestas cortas en grupos no absorban la cita ni la cabecera del remitente.
        try {
            val container = node.parent
            if (container != null) {
                val pRect = Rect()
                container.getBoundsInScreen(pRect)
                val maxTopExtend = (14 * density).toInt()
                val maxBottomExtend = (26 * density).toInt()
                val maxParentHeight = (rawRect.height() * 1.45f + 16 * density).toInt()
                val maxParentWidth = (screenWidth * if (profile.expandContainerToParent) 0.90f else 0.85f).toInt()
                val safeVerticalSpan = pRect.top >= rawRect.top - maxTopExtend &&
                    pRect.bottom <= rawRect.bottom + maxBottomExtend &&
                    pRect.height() <= maxParentHeight
                val safeHorizontalSpan = profile.expandContainerToParent ||
                    pRect.width() <= (result.width() * 1.35f)
                if (safeVerticalSpan &&
                    safeHorizontalSpan &&
                    pRect.width() <= maxParentWidth &&
                    pRect.height() <= screenHeight &&
                    pRect.width() >= result.width() &&
                    pRect.height() >= result.height() &&
                    Rect.intersects(pRect, result)
                ) {
                    result.set(pRect)
                }
            }
        } catch (_: Exception) {
            // Ignora nodo padre reciclado durante transición
        }
        AccessibilityNodeSafeOps.normalizeFullWidthMessageBounds(result, screenWidth, screenHeight, density)
        return result
    }
}

