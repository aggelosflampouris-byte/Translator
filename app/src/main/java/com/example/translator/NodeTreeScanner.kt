package com.example.translator

import android.graphics.Rect
import android.os.Build
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
    val parentNodeId: Int = 0,
    val nodeId: Int = 0,
    val isInsideQuoteContainer: Boolean = false,
    val isInsideHeaderContainer: Boolean = false
)

data class EvaluatedCandidate(
    val text: String,
    val bounds: WindowRect,
    val rawBounds: WindowRect = bounds,
    val rowId: Int = 0,
    val parentNodeId: Int = 0,
    val nodeId: Int = 0,
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

    private val REPLY_WRAPPER_DESC_MARKERS = listOf(
        "απάντησ", "απαντησ", "replied to", "răspuns", "raspuns", "reply to"
    )
    private val QUOTE_CONTAINER_ID_KEYWORDS = listOf(
        "quoted", "quote_container", "quote_holder", "quote_box",
        "reply_preview", "reply_quote", "replied_message"
    )
    private val SENDER_HEADER_ID_KEYWORDS = listOf(
        "name_in_group", "push_name", "pushname", "sender_name",
        "message_author", "author_name", "contact_name", "sender_phone",
        "quoted_title", "quoted_name", "quoted_author",
        "group_role", "member_role", "sender_role", "push_name_subtitle"
    )

    internal fun isQuoteContainerResourceId(viewIdName: String?): Boolean {
        val shortId = viewIdName?.substringAfterLast('/')?.lowercase().orEmpty()
        if (shortId.isEmpty()) return false
        return QUOTE_CONTAINER_ID_KEYWORDS.any { shortId.contains(it) }
    }

    internal fun isSenderHeaderResourceId(viewIdName: String?): Boolean {
        val shortId = viewIdName?.substringAfterLast('/')?.lowercase().orEmpty()
        if (shortId.isEmpty()) return false
        return SENDER_HEADER_ID_KEYWORDS.any { shortId.contains(it) }
    }

    private class RowStructuralIndex(
        val rawNodes: List<CollectedNodeSnapshot>,
        private val effectiveNodeIds: IntArray,
        private val bestTexts: Array<String>,
        private val isLeafCarrier: BooleanArray,
        private val isTimestamp: BooleanArray,
        private val isPhone: BooleanArray,
        private val parentIdxOfNode: IntArray
    ) {
        fun effectiveId(idx: Int): Int = effectiveNodeIds[idx]

        fun indexOfNodeId(nodeId: Int): Int {
            if (nodeId == 0) return -1
            for (idx in effectiveNodeIds.indices) {
                if (effectiveNodeIds[idx] == nodeId) return idx
            }
            return -1
        }

        fun hasPhoneSiblingInBand(nodeIdx: Int, density: Float): Boolean {
            val item = rawNodes[nodeIdx]
            val bandTolPx = (14 * density).toInt()
            for (j in rawNodes.indices) {
                if (j == nodeIdx || !isLeafCarrier[j] || !isPhone[j]) continue
                val peer = rawNodes[j]
                val sameRowOrParent = (item.rowId > 0 && peer.rowId == item.rowId) ||
                    (item.parentNodeId != 0 && peer.parentNodeId == item.parentNodeId)
                if (!sameRowOrParent) continue
                val minBottom = min(item.rawRect.bottom, peer.rawRect.bottom)
                val maxTop = maxOf(item.rawRect.top, peer.rawRect.top)
                val verticalOverlap = minBottom - maxTop
                val closeTop = abs(item.rawRect.top - peer.rawRect.top) <= bandTolPx
                if (closeTop || verticalOverlap > 0) return true
            }
            return false
        }

        fun hasTimestampForNodeBefore(
            cand: EvaluatedCandidate,
            nextCand: EvaluatedCandidate?,
            density: Float
        ): Boolean {
            if (cand.rowId <= 0) return false
            val topSlop = (6 * density).toInt()
            val bottomSlop = (22 * density).toInt()
            val nextSlop = (6 * density).toInt()
            // Por qué: Aunque el mensaje inferior haya sido filtrado (ej. emojis o inglés), su cota superior
            // sigue delimitando que el reloj pertenece a ese mensaje inferior y no a la cabecera/cita previa.
            val nextOrLowerTop = nextCand?.rawBounds?.top ?: findFirstLowerBodyLeaf(cand, density)?.rawRect?.top
            for (i in rawNodes.indices) {
                if (!isLeafCarrier[i] || !isTimestamp[i]) continue
                val ts = rawNodes[i]
                if (ts.rowId != cand.rowId) continue
                val alignsWithCand = ts.rawRect.top >= cand.rawBounds.top - topSlop &&
                    ts.rawRect.bottom <= cand.rawBounds.bottom + bottomSlop
                if (!alignsWithCand) continue
                if (nextOrLowerTop == null || ts.rawRect.bottom <= nextOrLowerTop + nextSlop) {
                    return true
                }
            }
            return false
        }

        fun findFirstLowerBodyLeaf(cand: EvaluatedCandidate, density: Float): CollectedNodeSnapshot? {
            if (cand.rowId <= 0) return null
            val stepPx = (10 * density).toInt()
            val selfIdx = indexOfNodeId(cand.nodeId)
            var best: CollectedNodeSnapshot? = null
            for (i in rawNodes.indices) {
                if (i == selfIdx || !isLeafCarrier[i] || isTimestamp[i] || isPhone[i]) continue
                val other = rawNodes[i]
                if (other.rowId != cand.rowId) continue
                if (other.rawRect.top < cand.rawBounds.top + stepPx) continue
                if (other.rawRect.top < cand.rawBounds.bottom - (4 * density).toInt()) continue
                if (best == null || other.rawRect.top < best.rawRect.top) {
                    best = other
                }
            }
            return best
        }

        fun hasAnyLowerLeafInRow(cand: EvaluatedCandidate, density: Float): Boolean =
            findFirstLowerBodyLeaf(cand, density) != null

        fun hasHeaderLeafDirectlyAbove(cand: EvaluatedCandidate, configuredSource: String, density: Float): Boolean {
            if (cand.rowId <= 0) return false
            val maxGapPx = (16 * density).toInt()
            val minGapPx = -(6 * density).toInt()
            val selfIdx = indexOfNodeId(cand.nodeId)
            for (i in rawNodes.indices) {
                if (i == selfIdx || !isLeafCarrier[i] || isTimestamp[i] || isPhone[i]) continue
                val upper = rawNodes[i]
                if (upper.rowId != cand.rowId) continue
                val gap = cand.rawBounds.top - upper.rawRect.bottom
                if (gap !in minGapPx..maxGapPx) continue
                val upperText = bestTexts[i]
                val isHeader = upper.isInsideHeaderContainer ||
                    hasPhoneSiblingInBand(i, density) ||
                    TranslationFilter.isSenderHandleOrMentionOnly(upperText) ||
                    TranslationSenseEngine.isLikelyRowSenderHeader(upperText, configuredSource)
                if (isHeader) return true
            }
            return false
        }

        fun rowHasReplyWrapperDesc(rowId: Int): Boolean {
            if (rowId <= 0) return false
            for (i in rawNodes.indices) {
                val n = rawNodes[i]
                if (n.rowId != rowId) continue
                val desc = n.descText?.lowercase() ?: continue
                if (REPLY_WRAPPER_DESC_MARKERS.any { desc.contains(it) }) return true
            }
            return false
        }

        fun hasHeaderInsideParentContainer(parentNodeId: Int, beforeTop: Int): Boolean {
            if (parentNodeId == 0) return false
            for (i in rawNodes.indices) {
                if (!isLeafCarrier[i] || isTimestamp[i]) continue
                val n = rawNodes[i]
                if (n.parentNodeId != parentNodeId || n.rawRect.top > beforeTop) continue
                if (n.isInsideHeaderContainer ||
                    isPhone[i] ||
                    TranslationFilter.isSenderHandleOrMentionOnly(bestTexts[i])
                ) {
                    return true
                }
            }
            return false
        }

        fun shareEnclosingBubbleWrapper(nodeAId: Int, nodeBId: Int): Boolean {
            val idxA = indexOfNodeId(nodeAId)
            val idxB = indexOfNodeId(nodeBId)
            if (idxA >= 0 && idxB >= 0) {
                // Por qué: Un TalkBack/ViewGroup común que envuelve ambos elementos (p. ej. wrapper del mensaje)
                // confirma que pertenecen a una sola burbuja de respuesta y no a dos burbujas separadas.
                val ancA = collectAncestorIndices(idxA)
                val ancB = collectAncestorIndices(idxB)
                for (a in ancA) {
                    if (a in ancB) {
                        val node = rawNodes[a]
                        if (!node.isScrollableContainer && !node.descText.isNullOrBlank()) return true
                    }
                }
            }
            return false
        }

        fun rowHasTimestampOnlyNearBottom(rowId: Int, bottomMinTop: Int): Boolean {
            if (rowId <= 0) return false
            var sawAny = false
            for (i in rawNodes.indices) {
                if (!isLeafCarrier[i] || !isTimestamp[i]) continue
                val ts = rawNodes[i]
                if (ts.rowId != rowId) continue
                sawAny = true
                if (ts.rawRect.top < bottomMinTop) return false
            }
            return sawAny
        }

        private fun collectAncestorIndices(startIdx: Int): Set<Int> {
            val set = LinkedHashSet<Int>(4)
            var p = parentIdxOfNode[startIdx]
            var steps = 0
            while (p >= 0 && steps < 6) {
                if (!set.add(p)) break
                p = parentIdxOfNode[p]
                steps++
            }
            return set
        }

        companion object {
            fun build(rawNodes: List<CollectedNodeSnapshot>): RowStructuralIndex {
                val n = rawNodes.size
                val effectiveIds = IntArray(n)
                val bestTexts = Array(n) { "" }
                val isLeafCarrier = BooleanArray(n)
                val isTimestamp = BooleanArray(n)
                val isPhone = BooleanArray(n)
                val parentIdxOfNode = IntArray(n) { -1 }

                for (i in 0 until n) {
                    val item = rawNodes[i]
                    effectiveIds[i] = if (item.nodeId != 0) item.nodeId else (i + 1)
                    val txt = TextSanitizer.sanitizeSpecialCharacters(item.directText ?: item.descText ?: "").trim()
                    bestTexts[i] = txt
                    isLeafCarrier[i] = !item.directText.isNullOrBlank() || item.childIndices.isEmpty()
                    isTimestamp[i] = isLeafCarrier[i] && TranslationFilter.isTimestampLikeText(txt)
                    isPhone[i] = isLeafCarrier[i] && TranslationFilter.isPhoneNumberText(txt)
                    for (childIdx in item.childIndices) {
                        if (childIdx in 0 until n) {
                            parentIdxOfNode[childIdx] = i
                        }
                    }
                }
                return RowStructuralIndex(
                    rawNodes = rawNodes,
                    effectiveNodeIds = effectiveIds,
                    bestTexts = bestTexts,
                    isLeafCarrier = isLeafCarrier,
                    isTimestamp = isTimestamp,
                    isPhone = isPhone,
                    parentIdxOfNode = parentIdxOfNode
                )
            }
        }
    }

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
        val rowIndex = RowStructuralIndex.build(rawNodes)
        val outList = mutableListOf<EvaluatedCandidate>()
        val subtreeMaxAccepted = IntArray(rawNodes.size)
        val subtreeAcceptedCounts = IntArray(rawNodes.size)
        val subtreeHasContentLeaf = BooleanArray(rawNodes.size)

        for (i in rawNodes.indices) {
            val item = rawNodes[i]
            var maxChildTextLen = 0
            var acceptedChildCount = 0
            var hasChildContent = false
            for (childIdx in item.childIndices) {
                if (childIdx in rawNodes.indices) {
                    if (subtreeMaxAccepted[childIdx] > maxChildTextLen) {
                        maxChildTextLen = subtreeMaxAccepted[childIdx]
                    }
                    acceptedChildCount += subtreeAcceptedCounts[childIdx]
                    if (subtreeHasContentLeaf[childIdx]) {
                        hasChildContent = true
                    }
                }
            }
            val itemHasLeafBody = !item.directText.isNullOrBlank() ||
                (item.childIndices.isEmpty() && !item.descText.isNullOrBlank())
            subtreeHasContentLeaf[i] = hasChildContent ||
                itemHasLeafBody ||
                item.isInsideQuoteContainer ||
                item.isInsideHeaderContainer

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
            } else if (hasChildContent && item.directText.isNullOrBlank()) {
                // Por qué: Si un contenedor solo agrupa nodos ya inspeccionados (p. ej. cabecera + emojis o cita + emoji),
                // su contentDescription TalkBack concatenaría remitente/cita y nunca debe reemplazar a sus hijos.
                continue
            }

            if (item.isInsideQuoteContainer || item.isInsideHeaderContainer) {
                stats?.let {
                    it.skippedFilter++
                    it.recordFilterRejection(if (item.isInsideQuoteContainer) "quo" else "hdr")
                }
                continue
            }
            if (rowIndex.hasPhoneSiblingInBand(i, density)) {
                stats?.let {
                    it.skippedFilter++
                    it.recordFilterRejection("snd")
                }
                continue
            }

            val acceptedLen = evaluateCandidateNode(
                item = item,
                effectiveNodeId = rowIndex.effectiveId(i),
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
            rowIndex = rowIndex,
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
     * descarta cabeceras/roles de remitente y suprime citas incrustadas para traducir únicamente la respuesta.
     */
    private fun resolveSiblingExpansionsAndReplyStructure(
        candidates: List<EvaluatedCandidate>,
        rowIndex: RowStructuralIndex,
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

        // 2. Dentro de cada fila de chat (rowId > 0), descarta cabeceras/roles de remitente y suprime citas previas
        val kept = ArrayList<EvaluatedCandidate>(nonInvading.size)
        val byRow = nonInvading.groupBy { it.rowId }
        for ((rowId, group) in byRow) {
            if (rowId <= 0) {
                kept.addAll(group)
                continue
            }
            val sortedRow = group.sortedWith(compareBy<EvaluatedCandidate> { it.bounds.top }.thenBy { it.bounds.left })
            val withoutHeaders = stripRowHeadersAndRoleSubtitles(sortedRow, rowIndex, configuredSource, density)
            val replyOnly = stripEmbeddedReplyQuotes(withoutHeaders, rowIndex, density)
            kept.addAll(replyOnly)
        }
        return kept
    }

    private fun stripRowHeadersAndRoleSubtitles(
        sortedRow: List<EvaluatedCandidate>,
        rowIndex: RowStructuralIndex,
        configuredSource: String,
        density: Float
    ): List<EvaluatedCandidate> {
        val result = ArrayList<EvaluatedCandidate>(sortedRow.size)
        var lastDroppedHeaderBottom: Int? = null
        val maxRoleGapPx = (16 * density).toInt()

        for (i in sortedRow.indices) {
            val cand = sortedRow[i]
            val nextCand = sortedRow.getOrNull(i + 1)
            val hasOwnTimestamp = rowIndex.hasTimestampForNodeBefore(cand, nextCand, density)
            val hasLowerContent = i < sortedRow.lastIndex || rowIndex.hasAnyLowerLeafInRow(cand, density)

            if (!hasOwnTimestamp && hasLowerContent &&
                TranslationSenseEngine.isLikelyRowSenderHeader(cand.text, configuredSource)
            ) {
                lastDroppedHeaderBottom = cand.rawBounds.bottom
                continue
            }

            val headerAboveInRow = (lastDroppedHeaderBottom != null && cand.rawBounds.top - lastDroppedHeaderBottom <= maxRoleGapPx) ||
                rowIndex.hasHeaderLeafDirectlyAbove(cand, configuredSource, density)
            if (!hasOwnTimestamp && hasLowerContent && headerAboveInRow &&
                TranslationSenseEngine.isLikelyRoleSubtitleBelowHeader(cand.text, configuredSource)
            ) {
                lastDroppedHeaderBottom = cand.rawBounds.bottom
                continue
            }

            result.add(cand)
        }
        return result
    }

    private fun stripEmbeddedReplyQuotes(
        candidates: List<EvaluatedCandidate>,
        rowIndex: RowStructuralIndex,
        density: Float
    ): List<EvaluatedCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val result = ArrayList<EvaluatedCandidate>(candidates.size)
        val quoteIndentPx = (2 * density).toInt()
        val maxQuoteOverlapPx = (14 * density).toInt()
        val maxQuoteGapPx = (42 * density).toInt()

        for (i in candidates.indices) {
            val item = candidates[i]
            val nextCand = candidates.getOrNull(i + 1)
            if (rowIndex.hasTimestampForNodeBefore(item, nextCand, density)) {
                result.add(item)
                continue
            }
            val lowerRawLeaf = if (nextCand == null) rowIndex.findFirstLowerBodyLeaf(item, density) else null
            val lowerBounds = nextCand?.bounds ?: lowerRawLeaf?.rawRect
            val lowerRawBounds = nextCand?.rawBounds ?: lowerRawLeaf?.rawRect
            val lowerParentId = nextCand?.parentNodeId ?: lowerRawLeaf?.parentNodeId ?: 0
            val lowerNodeId = nextCand?.nodeId ?: lowerRawLeaf?.nodeId ?: 0

            if (lowerBounds == null || lowerRawBounds == null) {
                result.add(item)
                continue
            }
            val sitsAboveLower = item.bounds.bottom <= lowerBounds.top + maxQuoteOverlapPx &&
                (lowerBounds.top - item.bounds.bottom) <= maxQuoteGapPx
            if (!sitsAboveLower) {
                result.add(item)
                continue
            }

            val isIndented = item.rawBounds.left >= lowerRawBounds.left + quoteIndentPx ||
                item.bounds.left >= lowerBounds.left + quoteIndentPx
            val differentSubContainer = item.parentNodeId != 0 && lowerParentId != 0 && item.parentNodeId != lowerParentId
            val hasQuoteContainerCue = rowIndex.rowHasReplyWrapperDesc(item.rowId) ||
                rowIndex.hasHeaderInsideParentContainer(item.parentNodeId, item.rawBounds.bottom) ||
                rowIndex.shareEnclosingBubbleWrapper(item.nodeId, lowerNodeId) ||
                rowIndex.rowHasTimestampOnlyNearBottom(item.rowId, lowerRawBounds.top - (6 * density).toInt())

            val isEmbeddedQuote = isIndented || (differentSubContainer && hasQuoteContainerCue)
            if (!isEmbeddedQuote) {
                result.add(item)
            }
        }
        return result
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

        if (rejectReason != null && rejectReason != "snd" && directText != null && descText != null && descText != directText) {
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
        effectiveNodeId: Int,
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
                    parentNodeId = item.parentNodeId,
                    nodeId = effectiveNodeId
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
     * header/quote suppression, and bubble-geometry rules used by tree scans.
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

        val srcCls = try { sourceNode.className?.toString() ?: "" } catch (_: Exception) { "" }
        if (AccessibilityNodeSafeOps.isScrollableOrToolbarClass(sourceNode, srcCls)) return null

        if (isNodeOrAncestorQuoteOrHeader(sourceNode, displayBounds.density)) return null

        val chCount = try { sourceNode.childCount } catch (_: Exception) { 0 }
        if (chCount > 0) {
            val rawNodes = mutableListOf<CollectedNodeSnapshot>()
            collectRawNodes(
                node = sourceNode,
                minY = 0,
                maxY = displayBounds.heightPx,
                screenWidth = displayBounds.widthPx,
                screenHeight = displayBounds.heightPx,
                density = displayBounds.density,
                configuredTarget = configuredTarget,
                profile = profile,
                stats = ScanStats(),
                outRawNodes = rawNodes,
                currentRowId = 1,
                currentParentNodeId = 0,
                rowGen = intArrayOf(1),
                visitGen = intArrayOf(1)
            )
            if (rawNodes.isNotEmpty()) {
                val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
                    rawNodes = rawNodes,
                    minY = 0,
                    maxY = displayBounds.heightPx,
                    screenWidth = displayBounds.widthPx,
                    density = displayBounds.density,
                    configuredSource = configuredSource,
                    configuredTarget = configuredTarget,
                    profile = profile
                )
                val winning = NodeCandidateEvaluator
                    .filterOverlapping(evaluated, displayBounds.density)
                    .sortedWith(compareBy<EvaluatedCandidate> { it.bounds.top }.thenBy { it.bounds.left })
                    .lastOrNull()
                if (winning != null) {
                    val b = winning.bounds
                    return MessageCandidate(winning.text, Rect(b.left, b.top, b.right, b.bottom))
                }
                return null
            }
        }

        val nodeWithText = AccessibilityNodeSafeOps.findFirstTextChild(sourceNode) ?: sourceNode
        if (nodeWithText != sourceNode && isNodeOrAncestorQuoteOrHeader(nodeWithText, displayBounds.density)) {
            return null
        }
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

    private fun isNodeOrAncestorQuoteOrHeader(startNode: AccessibilityNodeInfo, density: Float): Boolean {
        val nodeRect = Rect()
        val hasNodeRect = try {
            startNode.getBoundsInScreen(nodeRect)
            true
        } catch (_: Exception) {
            false
        }
        var curr: AccessibilityNodeInfo? = startNode
        var steps = 0
        while (curr != null && steps < 6) {
            val viewId = try { curr.viewIdResourceName } catch (_: Exception) { null }
            val isHeadingNode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                try { curr.isHeading } catch (_: Exception) { false }
            if (isHeadingNode ||
                NodeCandidateEvaluator.isQuoteContainerResourceId(viewId) ||
                NodeCandidateEvaluator.isSenderHeaderResourceId(viewId)
            ) {
                return true
            }
            if (steps == 1 && hasNodeRect && hasHorizontalPhoneSibling(curr, nodeRect, density)) {
                return true
            }
            curr = try { curr.parent } catch (_: Exception) { null }
            steps++
        }
        return false
    }

    private fun hasHorizontalPhoneSibling(
        parentNode: AccessibilityNodeInfo,
        targetRect: Rect,
        density: Float
    ): Boolean {
        val chCount = try { parentNode.childCount } catch (_: Exception) { 0 }
        val limit = min(chCount, 6)
        val bandTolPx = (14 * density).toInt()
        val peerRect = Rect()
        for (i in 0 until limit) {
            val peer = AccessibilityNodeSafeOps.safeChild(parentNode, i) ?: continue
            val peerText = try { peer.text?.toString() } catch (_: Exception) { null } ?: continue
            if (!TranslationFilter.isPhoneNumberText(peerText)) continue
            try {
                peer.getBoundsInScreen(peerRect)
            } catch (_: Exception) {
                continue
            }
            if (abs(peerRect.top - targetRect.top) <= bandTolPx) {
                return true
            }
        }
        return false
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
        insideQuoteContainer: Boolean = false,
        insideHeaderContainer: Boolean = false,
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
        val viewIdName = try { node.viewIdResourceName } catch (_: Exception) { null }
        val isHeadingNode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            try { node.isHeading } catch (_: Exception) { false }
        val nodeInQuote = insideQuoteContainer || NodeCandidateEvaluator.isQuoteContainerResourceId(viewIdName)
        val nodeInHeader = insideHeaderContainer || isHeadingNode || NodeCandidateEvaluator.isSenderHeaderResourceId(viewIdName)

        val isScrollableContainer = AccessibilityNodeSafeOps.isScrollableOrToolbarClass(node, className)
        val isPagerOrToolbar = className.contains("ViewPager", ignoreCase = true) ||
            className.contains("HorizontalScrollView", ignoreCase = true) ||
            className.contains("Toolbar", ignoreCase = true) ||
            className.contains("TabLayout", ignoreCase = true)
        // Por qué: Excluye ViewPager/Toolbar externos para que cada burbuja dentro de RecyclerView/ListView
        // conserve su `rowId` propio y dos mensajes seguidos nunca se confundan con una cita.
        val isChatListContainer = !isPagerOrToolbar && isScrollableContainer && run {
            val isMainListWidget = className.contains("RecyclerView", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true)
            if (!isMainListWidget && currentRowId != 0) {
                false
            } else {
                val listRect = Rect()
                try {
                    node.getBoundsInScreen(listRect)
                    listRect.height() >= (screenHeight * 0.35f).toInt() &&
                        listRect.width() >= (screenWidth * 0.65f).toInt()
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
                insideQuoteContainer = nodeInQuote,
                insideHeaderContainer = nodeInHeader,
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
        // Por qué: Solo consulta node.parent vía Binder cuando hay texto plausible y no pertenece a cabecera o cita.
        val expandedRect = if (!nodeInQuote && !nodeInHeader &&
            TranslationFilter.hasPlausibleLetterCandidate(directText, descText, configuredTarget)
        ) {
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
                parentNodeId = currentParentNodeId,
                nodeId = myNodeId,
                isInsideQuoteContainer = nodeInQuote,
                isInsideHeaderContainer = nodeInHeader
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

