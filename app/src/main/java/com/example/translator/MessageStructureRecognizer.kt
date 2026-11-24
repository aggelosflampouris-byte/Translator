package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage
import kotlin.math.abs
import kotlin.math.min

/**
 * Rol estructural de un nodo o segmento textual dentro de una fila de chat.
 */
enum class MessageComponentRole {
    SENDER_NAME,
    REPLIED_SENDER_NAME,
    REPLIED_MESSAGE,
    REPLYING_TEXT,
    METADATA_IGNORED
}

/**
 * Anatomía descompuesta de una burbuja o texto de mensaje grupal para distinguir claramente
 * remitente, autor/contenido de la cita original y el texto real de la respuesta.
 */
data class RecognizedMessageAnatomy(
    val senderName: String? = null,
    val senderRoleSubtitle: String? = null,
    val repliedSenderName: String? = null,
    val repliedMessage: String? = null,
    val replyingText: String? = null,
    val replyingNodeIndex: Int = -1,
    val minReplyTopPx: Int = Int.MIN_VALUE
)

internal data class NodeStructureAnalysis(
    val roles: Array<MessageComponentRole>,
    val minReplyTopByNodeIndex: IntArray,
    val rowAnatomies: List<RecognizedMessageAnatomy>,
    val repliedMessageByNodeIndex: Array<String?>
)

/**
 * Reconocedor explícito de la estructura de mensajes de chat.
 *
 * Por qué: Separa de forma determinista el nombre del remitente, el mensaje citado (original)
 * y el texto de respuesta actual usando IDs de vista, jerarquía de nodos, geometría y patrones TalkBack.
 */
object MessageStructureRecognizer {

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
    private val MAIN_MESSAGE_BODY_ID_KEYWORDS = listOf(
        "message_text", "conversation_text", "message_body"
    )
    private val REPLY_WRAPPER_DESC_MARKERS = listOf(
        "απάντησ", "απαντησ", "replied to", "răspuns", "raspuns", "reply to"
    )
    private val TRAILING_PHONE_EXTRACT_REGEX = Regex(
        """(?:\s*[,;|:\n]?\s*\+\d[\d\s\-().]{6,}\d)+$"""
    )
    private val COLON_HANDLE_LINE_REGEX = Regex(
        """^(?:~[^\n:]{1,42}|[A-ZĂÂÎȘȚ][a-zăâîșț0-9._'-]{1,20}(?:\s+[A-ZĂÂÎȘȚ][a-zăâîșț0-9._'-]{1,20}){0,2}|[Α-ΩΆΈΉΊΌΎΏΪΫα-ωάέήίόύώϊϋΐΰς][Α-ΩΆΈΉΊΌΎΏΪΫα-ωάέήίόύώϊϋΐΰς\s.'-]{1,36}):$"""
    )
    private val COLON_INLINE_HANDLE_PREFIX_REGEX = Regex(
        """^(~[^\n:]{1,42}):\s+([\s\S]+)$"""
    )
    private val TALKBACK_REPLY_FULL_REGEX = Regex(
        """^(?:(.{1,48}?)\s+)?(?:απάντησε|απαντησε|απαντήσατε|απαντησατε|απάντηση|απαντηση|replied|reply|a\s+răspuns|a\s+raspuns|ai\s+răspuns|ai\s+raspuns)(?:\s+(?:στον?\s+χρήστη|στον?\s+χρηστη|στο\s+μήνυμ[άα](?:\s+(?:σας|του|της|τους))?|στο\s+μηνυμα(?:\s+(?:σας|του|της|τους))?|στον?\s+εαυτό\s+(?:σας|του|της|τους)|σε|to|la))?(?:\s+([^«"“:\n]{1,64}?))?(?:\s*[«"“]([^»"”\n]{1,180})[»"”])?(?:[ \t]*[:,][ \t]+|[ \t]*\n+[ \t]*)([\s\S]+)$""",
        RegexOption.IGNORE_CASE
    )
    private val QUOTED_SNIPPET_ONLY_EXTRACT_REGEX = Regex(
        """[«"“]([^»"”\n]{1,180})[»"”]"""
    )
    private val TIME_HHMM_REGEX = Regex("""\b\d{1,2}:\d{2}\b""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val NON_HANDLE_CLAUSE_TOKENS = setOf(
        "am", "ai", "a", "au", "are", "e", "este", "sunt", "esti", "ești",
        "eu", "tu", "el", "ea", "noi", "voi", "ei", "ele",
        "da", "nu", "ca", "că", "sa", "să", "si", "și", "la", "in", "în", "de", "cu", "pe",
        "ce", "cine", "cand", "când", "unde", "cum", "daca", "dacă", "care", "doar", "mai",
        "bine", "ok", "okay", "multumesc", "mulțumesc", "mersi", "salut", "buna", "bună",
        "poate", "cred", "hai", "gata", "vin", "plec", "ajung", "vreau", "stiu", "știu", "pot",
        "yes", "no", "sorry", "thanks", "thank", "please", "hello", "hi", "hey", "oh", "genau"
    )

    internal fun isQuoteContainerResourceId(viewIdName: String?): Boolean {
        val shortId = extractShortViewId(viewIdName)
        if (shortId.isEmpty()) return false
        return QUOTE_CONTAINER_ID_KEYWORDS.any { shortId.contains(it) }
    }

    internal fun isSenderHeaderResourceId(viewIdName: String?): Boolean {
        val shortId = extractShortViewId(viewIdName)
        if (shortId.isEmpty()) return false
        return SENDER_HEADER_ID_KEYWORDS.any { shortId.contains(it) }
    }

    internal fun isMainMessageBodyResourceId(viewIdName: String?): Boolean {
        val shortId = extractShortViewId(viewIdName)
        if (shortId.isEmpty() || isQuoteContainerResourceId(shortId) || isSenderHeaderResourceId(shortId)) {
            return false
        }
        return MAIN_MESSAGE_BODY_ID_KEYWORDS.any { shortId.contains(it) }
    }

    private fun extractShortViewId(viewIdName: String?): String =
        viewIdName?.substringAfterLast('/')?.lowercase().orEmpty()

    private fun stripPhoneSuffix(text: String): String =
        text.replace(TRAILING_PHONE_EXTRACT_REGEX, "").trimEnd(',', ':', ' ').trim()

    private fun isSavedContactIdentifier(text: String, configuredSource: String): Boolean {
        val normalized = stripPhoneSuffix(TranslationFilter.normalizeHandleInspectText(text))
        if (normalized.isEmpty() || normalized.length > 32 || normalized.contains('\n')) return false
        if (normalized.indexOfAny(charArrayOf('?', '!', ';', ':', ',')) >= 0) return false
        if (ScriptDetector.containsUrlOrDomain(normalized)) return false
        if (!normalized.any { it.isLetter() }) return false
        if (TranslationFilter.isSenderHandleOrMentionOnly(normalized)) return true
        if (TranslationSenseEngine.isLikelyRowSenderHeader(normalized, configuredSource)) return true

        val tokens = normalized.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 3) return false
        val lowerTokens = tokens.map { TextNormalizer.normalizeRomanianCedillas(it).lowercase().trimEnd('.', '…') }
        if (lowerTokens.any { it in NON_HANDLE_CLAUSE_TOKENS || it in TranslationSenseEngine.COMMON_ROMANIAN_WORDS }) {
            return false
        }
        return true
    }

    /**
     * Descompone un bloque de texto crudo (TalkBack o multilínea) en remitente, autor/mensaje citado y respuesta real.
     */
    fun recognizeTextAnatomy(
        rawText: String,
        configuredSource: String = TranslateLanguage.ROMANIAN
    ): RecognizedMessageAnatomy {
        val sanitized = TextSanitizer.sanitizeSpecialCharacters(rawText).trim()
        if (sanitized.isEmpty()) return RecognizedMessageAnatomy()

        val envelopeStripped = TranslationFilter.stripTrailingEnvelopeMetadata(sanitized)
        if (envelopeStripped.isEmpty()) return RecognizedMessageAnatomy()

        if (TranslationFilter.isTimestampLikeText(sanitized) || TranslationFilter.isPhoneNumberText(envelopeStripped)) {
            return RecognizedMessageAnatomy()
        }

        if (TranslationFilter.isSenderHandleOrMentionOnly(envelopeStripped)) {
            return RecognizedMessageAnatomy(senderName = stripPhoneSuffix(envelopeStripped), replyingText = null)
        }

        if (!envelopeStripped.contains('\n')) {
            recognizeTalkBackReplyLine(envelopeStripped)?.let { return it }
            val inlineHandleMatch = COLON_INLINE_HANDLE_PREFIX_REGEX.matchEntire(envelopeStripped)
            if (inlineHandleMatch != null) {
                val handle = stripPhoneSuffix(inlineHandleMatch.groupValues[1])
                val reply = inlineHandleMatch.groupValues[2].trim()
                return RecognizedMessageAnatomy(senderName = handle, replyingText = reply.ifEmpty { null })
            }
            val cleaned = TranslationFilter.cleanMessageText(envelopeStripped)
            return RecognizedMessageAnatomy(replyingText = cleaned.ifEmpty { null })
        }

        return recognizeMultilineAnatomy(envelopeStripped, configuredSource)
    }

    private fun recognizeTalkBackReplyLine(line: String): RecognizedMessageAnatomy? {
        val match = TALKBACK_REPLY_FULL_REGEX.matchEntire(line) ?: return null
        val beforeVerb = match.groupValues[1].trim().ifEmpty { null }
        val targetAuthor = match.groupValues[2].trim().trimEnd(',', ':').ifEmpty { null }
        val quotedSnippet = match.groupValues[3].trim().ifEmpty { null }
        val replyPart = match.groupValues[4].trim()
        if (replyPart.isEmpty()) return null

        // Por qué: Evita falsos positivos en frases normales como "I replied to your message yesterday"
        // exigiendo cita explícita entre comillas o separador estructural de TalkBack.
        val hasExplicitQuote = quotedSnippet != null
        val hasReplyAuthor = targetAuthor != null &&
            !targetAuthor.equals("your message", ignoreCase = true) &&
            !targetAuthor.equals("their own message", ignoreCase = true)
        if (!hasExplicitQuote && !hasReplyAuthor && !line.contains(':')) return null

        val extractedQuote = quotedSnippet
            ?: QUOTED_SNIPPET_ONLY_EXTRACT_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()

        val cleanedAuthor = targetAuthor?.let { stripPhoneSuffix(it) }
        val cleanedSender = beforeVerb?.let { stripPhoneSuffix(it) }
        val normalizedReply = TranslationFilter.cleanMessageText(replyPart).ifEmpty { replyPart }
        return RecognizedMessageAnatomy(
            senderName = cleanedSender,
            repliedSenderName = cleanedAuthor,
            repliedMessage = extractedQuote,
            replyingText = normalizedReply
        )
    }

    private fun recognizeMultilineAnatomy(
        envelopeStripped: String,
        configuredSource: String
    ): RecognizedMessageAnatomy {
        val lines = envelopeStripped.lines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !TranslationFilter.isPhoneNumberText(it) &&
                    !TranslationFilter.isTimestampLikeText(it)
            }
        if (lines.isEmpty()) return RecognizedMessageAnatomy()
        if (lines.size == 1) {
            return recognizeTextAnatomy(lines[0], configuredSource)
        }

        var idx = 0
        var senderName: String? = null
        var senderRoleSubtitle: String? = null

        val firstLine = lines[0]
        val firstHasPhone = firstLine.length > stripPhoneSuffix(firstLine).length
        val firstIsExplicitHeader = TranslationFilter.isSenderHandleOrMentionOnly(firstLine) ||
            firstLine.matches(COLON_HANDLE_LINE_REGEX) ||
            firstHasPhone
        if (firstIsExplicitHeader) {
            senderName = stripPhoneSuffix(firstLine.trimEnd(':'))
            idx = 1
        }

        if (senderName != null && idx + 1 < lines.size) {
            val secondLine = lines[idx]
            if (!TranslationFilter.isSenderHandleOrMentionOnly(secondLine) &&
                TranslationSenseEngine.isLikelyRoleSubtitleBelowHeader(secondLine, configuredSource)
            ) {
                senderRoleSubtitle = secondLine
                idx++
            }
        }

        // Busca una cabecera de autor citado en líneas intermedias (p. ej. "~ misu" antes de cita y respuesta)
        var quotedAuthorIdx = -1
        for (q in idx until lines.lastIndex) {
            val candidate = lines[q]
            val hasPhone = candidate.length > stripPhoneSuffix(candidate).length
            if ((TranslationFilter.isSenderHandleOrMentionOnly(candidate) || hasPhone) && q + 1 < lines.size) {
                quotedAuthorIdx = q
                break
            }
        }

        if (quotedAuthorIdx >= 0) {
            val repliedSenderName = stripPhoneSuffix(lines[quotedAuthorIdx].trimEnd(':'))
            val replyText = lines.last()
            val quoteEndIdx = (lines.lastIndex).coerceAtLeast(quotedAuthorIdx + 1)
            val repliedMessage = if (quotedAuthorIdx + 1 < quoteEndIdx) {
                lines.subList(quotedAuthorIdx + 1, quoteEndIdx).joinToString("\n").ifEmpty { null }
            } else {
                null
            }
            return RecognizedMessageAnatomy(
                senderName = senderName,
                senderRoleSubtitle = senderRoleSubtitle,
                repliedSenderName = repliedSenderName,
                repliedMessage = repliedMessage,
                replyingText = TranslationFilter.cleanMessageText(replyText).ifEmpty { replyText }
            )
        }

        if (idx >= lines.size) {
            return RecognizedMessageAnatomy(
                senderName = senderName,
                senderRoleSubtitle = senderRoleSubtitle,
                replyingText = null
            )
        }

        val remainingJoined = lines.subList(idx, lines.size).joinToString("\n")
        if (senderName == null) {
            val fallbackTalkBack = recognizeTalkBackReplyLine(remainingJoined.replace('\n', ' '))
            if (fallbackTalkBack != null) return fallbackTalkBack
        }
        val cleanedRemaining = TranslationFilter.cleanMessageText(remainingJoined)
        return RecognizedMessageAnatomy(
            senderName = senderName,
            senderRoleSubtitle = senderRoleSubtitle,
            replyingText = cleanedRemaining.ifEmpty { remainingJoined }
        )
    }

    /**
     * Analiza una fila o árbol completo de nodos recolectados y devuelve la lista de anatomías reconocidas por burbuja.
     */
    fun recognizeRowAnatomy(
        rawNodes: List<CollectedNodeSnapshot>,
        configuredSource: String = TranslateLanguage.ROMANIAN,
        density: Float = 3.0f
    ): List<RecognizedMessageAnatomy> {
        return analyzeRawNodes(rawNodes, configuredSource, density).rowAnatomies
    }

    internal fun analyzeRawNodes(
        rawNodes: List<CollectedNodeSnapshot>,
        configuredSource: String = TranslateLanguage.ROMANIAN,
        density: Float = 3.0f
    ): NodeStructureAnalysis {
        val n = rawNodes.size
        val roles = Array(n) { MessageComponentRole.REPLYING_TEXT }
        val minReplyTops = IntArray(n) { Int.MIN_VALUE }
        val repliedMessagesByNode = arrayOfNulls<String>(n)
        if (n == 0) {
            return NodeStructureAnalysis(roles, minReplyTops, emptyList(), repliedMessagesByNode)
        }

        val texts = Array(n) { "" }
        val isLeafCarrier = BooleanArray(n)
        val isTimestamp = BooleanArray(n)
        val isPhone = BooleanArray(n)

        for (i in 0 until n) {
            val item = rawNodes[i]
            val txt = TextSanitizer.sanitizeSpecialCharacters(item.directText ?: item.descText ?: "").trim()
            texts[i] = txt
            isLeafCarrier[i] = item.isMediaPlaceholder || !item.directText.isNullOrBlank() || item.childIndices.isEmpty()
            val shortId = extractShortViewId(item.viewIdName)
            isTimestamp[i] = isLeafCarrier[i] && !item.isMediaPlaceholder && (
                TranslationFilter.isTimestampLikeText(txt) ||
                    ((shortId == "date" || shortId.endsWith("_date") || shortId.contains("timestamp")) &&
                        txt.length <= 24 && TIME_HHMM_REGEX.containsMatchIn(txt))
                )
            isPhone[i] = isLeafCarrier[i] && !item.isMediaPlaceholder && (
                TranslationFilter.isPhoneNumberText(txt) ||
                    shortId.contains("phone_number") ||
                    shortId == "sender_phone"
                )
            if (!isLeafCarrier[i] || isTimestamp[i] || isPhone[i] || item.isMediaPlaceholder) {
                roles[i] = MessageComponentRole.METADATA_IGNORED
            }
        }

        val anatomies = ArrayList<RecognizedMessageAnatomy>()
        val byRow = (0 until n).groupBy { rawNodes[it].rowId }

        for ((rowId, indices) in byRow) {
            if (rowId <= 0) {
                for (i in indices) {
                    if (!isLeafCarrier[i] || isTimestamp[i] || isPhone[i] || rawNodes[i].isMediaPlaceholder) continue
                    val item = rawNodes[i]
                    val hasPhonePeer = findPhoneSiblingInRowOrParent(i, rawNodes, isLeafCarrier, isPhone, density)
                    when {
                        item.isInsideHeaderContainer && item.isInsideQuoteContainer -> roles[i] = MessageComponentRole.REPLIED_SENDER_NAME
                        item.isInsideHeaderContainer -> roles[i] = MessageComponentRole.SENDER_NAME
                        item.isInsideQuoteContainer -> roles[i] = if (hasPhonePeer || TranslationFilter.isSenderHandleOrMentionOnly(texts[i])) {
                            MessageComponentRole.REPLIED_SENDER_NAME
                        } else {
                            MessageComponentRole.REPLIED_MESSAGE
                        }
                        hasPhonePeer || TranslationFilter.isSenderHandleOrMentionOnly(texts[i]) -> {
                            roles[i] = MessageComponentRole.SENDER_NAME
                        }
                        else -> roles[i] = MessageComponentRole.REPLYING_TEXT
                    }
                }
                continue
            }

            val rowTimestamps = indices.filter { isTimestamp[it] }
            val rowPhones = indices.filter { isPhone[it] }
            val rowHasReplyDesc = indices.any { idx ->
                val desc = rawNodes[idx].descText?.lowercase() ?: return@any false
                REPLY_WRAPPER_DESC_MARKERS.any { desc.contains(it) }
            }

            val contentLeaves = indices
                .filter { isLeafCarrier[it] && !isTimestamp[it] && !isPhone[it] }
                .sortedWith(compareBy<Int> { rawNodes[it].rawRect.top }.thenBy { rawNodes[it].rawRect.left })

            if (contentLeaves.isEmpty()) continue

            val clusters = splitRowIntoBubbleClusters(contentLeaves, rowTimestamps, rawNodes, density)
            for (cluster in clusters) {
                val anatomy = classifyBubbleCluster(
                    cluster = cluster,
                    rawNodes = rawNodes,
                    texts = texts,
                    rowTimestamps = rowTimestamps,
                    rowPhones = rowPhones,
                    rowHasReplyDesc = rowHasReplyDesc,
                    configuredSource = configuredSource,
                    density = density,
                    outRoles = roles,
                    outMinReplyTops = minReplyTops
                )
                if (anatomy.replyingNodeIndex >= 0 && !anatomy.repliedMessage.isNullOrBlank()) {
                    repliedMessagesByNode[anatomy.replyingNodeIndex] = anatomy.repliedMessage
                }
                anatomies.add(anatomy)
            }
        }

        return NodeStructureAnalysis(roles, minReplyTops, anatomies, repliedMessagesByNode)
    }

    private fun findPhoneSiblingInRowOrParent(
        nodeIdx: Int,
        rawNodes: List<CollectedNodeSnapshot>,
        isLeafCarrier: BooleanArray,
        isPhone: BooleanArray,
        density: Float
    ): Boolean {
        val item = rawNodes[nodeIdx]
        val bandTolPx = (14 * density).toInt()
        for (j in rawNodes.indices) {
            if (j == nodeIdx || !isLeafCarrier[j] || !isPhone[j]) continue
            val peer = rawNodes[j]
            val sameGroup = (item.rowId > 0 && peer.rowId == item.rowId) ||
                (item.parentNodeId != 0 && peer.parentNodeId == item.parentNodeId)
            if (!sameGroup) continue
            if (nodesShareHorizontalBand(item.rawRect, peer.rawRect, bandTolPx)) return true
        }
        return false
    }

    private fun nodesShareHorizontalBand(a: WindowRect, b: WindowRect, bandTolPx: Int): Boolean {
        val minBottom = min(a.bottom, b.bottom)
        val maxTop = maxOf(a.top, b.top)
        return (minBottom - maxTop) > 0 || abs(a.top - b.top) <= bandTolPx
    }

    private fun splitRowIntoBubbleClusters(
        contentLeaves: List<Int>,
        rowTimestamps: List<Int>,
        rawNodes: List<CollectedNodeSnapshot>,
        density: Float
    ): List<List<Int>> {
        if (contentLeaves.size <= 1 || rowTimestamps.isEmpty()) {
            return listOf(contentLeaves)
        }
        val clusters = ArrayList<List<Int>>(2)
        var currentStart = 0
        for (i in 0 until contentLeaves.lastIndex) {
            val currIdx = contentLeaves[i]
            val nextIdx = contentLeaves[i + 1]
            if (rawNodes[nextIdx].isInsideQuoteContainer) continue
            val hasOwnTsBeforeNext = hasTimestampForLeafBefore(
                leafRect = rawNodes[currIdx].rawRect,
                nextLeafTop = rawNodes[nextIdx].rawRect.top,
                rowTimestamps = rowTimestamps,
                rawNodes = rawNodes,
                density = density
            )
            if (hasOwnTsBeforeNext) {
                clusters.add(contentLeaves.subList(currentStart, i + 1))
                currentStart = i + 1
            }
        }
        clusters.add(contentLeaves.subList(currentStart, contentLeaves.size))
        return clusters
    }

    private fun hasTimestampForLeafBefore(
        leafRect: WindowRect,
        nextLeafTop: Int?,
        rowTimestamps: List<Int>,
        rawNodes: List<CollectedNodeSnapshot>,
        density: Float
    ): Boolean {
        val topSlop = (6 * density).toInt()
        val bottomSlop = (22 * density).toInt()
        val nextSlop = (6 * density).toInt()
        for (tsIdx in rowTimestamps) {
            val tsRect = rawNodes[tsIdx].rawRect
            val alignsWithLeaf = tsRect.top >= leafRect.top - topSlop &&
                tsRect.bottom <= leafRect.bottom + bottomSlop
            if (!alignsWithLeaf) continue
            if (nextLeafTop == null || tsRect.bottom <= nextLeafTop + nextSlop) {
                return true
            }
        }
        return false
    }

    private fun classifyBubbleCluster(
        cluster: List<Int>,
        rawNodes: List<CollectedNodeSnapshot>,
        texts: Array<String>,
        rowTimestamps: List<Int>,
        rowPhones: List<Int>,
        rowHasReplyDesc: Boolean,
        configuredSource: String,
        density: Float,
        outRoles: Array<MessageComponentRole>,
        outMinReplyTops: IntArray
    ): RecognizedMessageAnatomy {
        val bandTolPx = (14 * density).toInt()
        val lastIdx = cluster.lastIndex

        var senderName: String? = null
        var senderRoleSubtitle: String? = null
        var repliedSenderName: String? = null
        val repliedMessages = ArrayList<String>(2)

        val assigned = arrayOfNulls<MessageComponentRole>(cluster.size)
        val hasPhonePeer = BooleanArray(cluster.size) { pos ->
            val node = rawNodes[cluster[pos]]
            !node.isMediaPlaceholder && rowPhones.any { pIdx ->
                nodesShareHorizontalBand(node.rawRect, rawNodes[pIdx].rawRect, bandTolPx)
            }
        }

        // 1. Señales explícitas por viewIdResourceName, contenedores y teléfonos horizontales
        for (pos in cluster.indices) {
            val nodeIdx = cluster[pos]
            val node = rawNodes[nodeIdx]
            if (node.isMediaPlaceholder) {
                assigned[pos] = MessageComponentRole.METADATA_IGNORED
                continue
            }
            val txt = texts[nodeIdx]
            when {
                node.isInsideHeaderContainer || isSenderHeaderResourceId(node.viewIdName) -> {
                    val isQuoteAuthor = node.isInsideQuoteContainer || (pos > 0 && assigned[0] == MessageComponentRole.SENDER_NAME)
                    assigned[pos] = if (isQuoteAuthor) {
                        MessageComponentRole.REPLIED_SENDER_NAME
                    } else {
                        MessageComponentRole.SENDER_NAME
                    }
                }
                node.isInsideQuoteContainer || isQuoteContainerResourceId(node.viewIdName) -> {
                    val hasLowerSiblingInSameQuote = (pos + 1 until cluster.size).any { nextPos ->
                        val nextNode = rawNodes[cluster[nextPos]]
                        !nextNode.isMediaPlaceholder &&
                            (nextNode.isInsideQuoteContainer || nextPos < lastIdx) &&
                            (sharesNonZeroSubContainer(node, nextNode) || nextNode.isInsideQuoteContainer)
                    }
                    val looksLikeAuthor = hasPhonePeer[pos] ||
                        TranslationFilter.isSenderHandleOrMentionOnly(txt) ||
                        (hasLowerSiblingInSameQuote && isSavedContactIdentifier(txt, configuredSource))
                    assigned[pos] = if (looksLikeAuthor) {
                        MessageComponentRole.REPLIED_SENDER_NAME
                    } else {
                        MessageComponentRole.REPLIED_MESSAGE
                    }
                }
                hasPhonePeer[pos] || TranslationFilter.isSenderHandleOrMentionOnly(txt) -> {
                    assigned[pos] = if (pos == 0 || (pos == lastIdx && assigned[0] == null)) {
                        MessageComponentRole.SENDER_NAME
                    } else {
                        MessageComponentRole.REPLIED_SENDER_NAME
                    }
                }
            }
        }

        // 2. Cabecera de remitente y subtítulo de rol en parte superior (incluso contactos guardados sin `~` ni teléfono)
        if (assigned[0] == null && cluster.size >= 2) {
            val firstIdx = cluster[0]
            val firstNode = rawNodes[firstIdx]
            val firstTxt = texts[firstIdx]
            val secondNode = rawNodes[cluster[1]]
            val hasDifferentContainerThanNext = (firstNode.parentNodeId != 0 && secondNode.parentNodeId != 0 &&
                firstNode.parentNodeId != secondNode.parentNodeId) ||
                (firstNode.subContainerNodeId != 0 && secondNode.subContainerNodeId != 0 &&
                    firstNode.subContainerNodeId != secondNode.subContainerNodeId)
            val secondIsMediaOrLetterless = secondNode.isMediaPlaceholder ||
                !texts[cluster[1]].any { it.isLetter() }
            val firstLooksLikeHeader = TranslationSenseEngine.isLikelyRowSenderHeader(firstTxt, configuredSource) ||
                (isSavedContactIdentifier(firstTxt, configuredSource) &&
                    (hasDifferentContainerThanNext || cluster.size >= 3 || secondIsMediaOrLetterless))
            if (firstLooksLikeHeader) {
                assigned[0] = MessageComponentRole.SENDER_NAME
            }
        }

        if (assigned[0] == MessageComponentRole.SENDER_NAME && cluster.size >= 2 && assigned[1] == null) {
            val topHeaderBottom = rawNodes[cluster[0]].rawRect.bottom
            val secondIdx = cluster[1]
            val gap = rawNodes[secondIdx].rawRect.top - topHeaderBottom
            if (gap <= (16 * density).toInt() &&
                TranslationSenseEngine.isLikelyRoleSubtitleBelowHeader(texts[secondIdx], configuredSource)
            ) {
                assigned[1] = MessageComponentRole.SENDER_NAME
                senderRoleSubtitle = texts[secondIdx]
            }
        }

        // 3. Separa el cuerpo real (último nodo o marcador multimedia) de citas superiores y autores citados intermedios
        val remainingPositions = cluster.indices.filter { pos ->
            assigned[pos] == null || rawNodes[cluster[pos]].isMediaPlaceholder
        }

        if (remainingPositions.isNotEmpty()) {
            val bottomPos = remainingPositions.last()
            val bottomNode = rawNodes[cluster[bottomPos]]
            if (!bottomNode.isMediaPlaceholder) {
                assigned[bottomPos] = MessageComponentRole.REPLYING_TEXT
            } else {
                assigned[bottomPos] = MessageComponentRole.METADATA_IGNORED
            }

            val quoteIndentPx = (2 * density).toInt()
            val bubbleHasOnlyBottomTimestamp = hasTimestampForLeafBefore(
                leafRect = bottomNode.rawRect,
                nextLeafTop = null,
                rowTimestamps = rowTimestamps,
                rawNodes = rawNodes,
                density = density
            )

            for (posIdx in 0 until remainingPositions.lastIndex) {
                val pos = remainingPositions[posIdx]
                val nodeIdx = cluster[pos]
                val node = rawNodes[nodeIdx]
                if (node.isMediaPlaceholder) continue
                val txt = texts[nodeIdx]

                // Autor citado intermedio en tarjeta de cita sin `~` ni IDs explícitos
                val nextRemainingPos = remainingPositions[posIdx + 1]
                val nextRemainingNode = rawNodes[cluster[nextRemainingPos]]
                if (posIdx + 1 < remainingPositions.lastIndex &&
                    !nextRemainingNode.isMediaPlaceholder &&
                    (isSavedContactIdentifier(txt, configuredSource) ||
                        TranslationSenseEngine.isLikelyRowSenderHeader(txt, configuredSource)) &&
                    sharesNonZeroSubContainer(node, nextRemainingNode)
                ) {
                    assigned[pos] = MessageComponentRole.REPLIED_SENDER_NAME
                    continue
                }

                val isIndented = node.rawRect.left >= bottomNode.rawRect.left + quoteIndentPx
                val differentContainer = (node.parentNodeId != 0 && bottomNode.parentNodeId != 0 &&
                    node.parentNodeId != bottomNode.parentNodeId) ||
                    (node.subContainerNodeId != 0 && bottomNode.subContainerNodeId != 0 &&
                        node.subContainerNodeId != bottomNode.subContainerNodeId)
                val hasOwnTsBeforeBottom = hasTimestampForLeafBefore(
                    leafRect = node.rawRect,
                    nextLeafTop = bottomNode.rawRect.top,
                    rowTimestamps = rowTimestamps,
                    rawNodes = rawNodes,
                    density = density
                )
                val isQuotedByStructure = isIndented ||
                    differentContainer ||
                    bottomNode.isMainMessageBodyViewId ||
                    rowHasReplyDesc ||
                    repliedSenderName != null ||
                    assigned.any { it == MessageComponentRole.REPLIED_SENDER_NAME } ||
                    (!hasOwnTsBeforeBottom && bubbleHasOnlyBottomTimestamp)

                assigned[pos] = if (isQuotedByStructure && !node.isMainMessageBodyViewId) {
                    MessageComponentRole.REPLIED_MESSAGE
                } else {
                    MessageComponentRole.REPLYING_TEXT
                }
            }
        }

        // 4. Consolida roles finales, metadata de anatomía y cota superior minReplyTopPx
        var replyingText: String? = null
        var replyingNodeIndex = -1
        var headerAndQuoteBottom = Int.MIN_VALUE

        for (pos in cluster.indices) {
            val nodeIdx = cluster[pos]
            val role = assigned[pos] ?: MessageComponentRole.METADATA_IGNORED
            outRoles[nodeIdx] = role
            val txt = texts[nodeIdx]
            when (role) {
                MessageComponentRole.SENDER_NAME -> {
                    val cleanHeader = stripPhoneSuffix(txt)
                    if (senderName == null) {
                        senderName = cleanHeader
                    } else if (senderRoleSubtitle == null && cleanHeader != senderName) {
                        senderRoleSubtitle = cleanHeader
                    }
                    headerAndQuoteBottom = maxOf(headerAndQuoteBottom, rawNodes[nodeIdx].rawRect.bottom)
                }
                MessageComponentRole.REPLIED_SENDER_NAME -> {
                    if (repliedSenderName == null) {
                        repliedSenderName = stripPhoneSuffix(txt)
                    }
                    headerAndQuoteBottom = maxOf(headerAndQuoteBottom, rawNodes[nodeIdx].rawRect.bottom)
                }
                MessageComponentRole.REPLIED_MESSAGE -> {
                    repliedMessages.add(txt)
                    headerAndQuoteBottom = maxOf(headerAndQuoteBottom, rawNodes[nodeIdx].rawRect.bottom)
                }
                MessageComponentRole.REPLYING_TEXT -> {
                    val parsedText = recognizeTextAnatomy(txt, configuredSource).replyingText
                    replyingText = parsedText ?: txt.ifEmpty { null }
                    replyingNodeIndex = nodeIdx
                    if (headerAndQuoteBottom != Int.MIN_VALUE &&
                        headerAndQuoteBottom <= rawNodes[nodeIdx].rawRect.top + (12 * density).toInt()
                    ) {
                        outMinReplyTops[nodeIdx] = headerAndQuoteBottom
                    }
                }
                MessageComponentRole.METADATA_IGNORED -> Unit
            }
        }

        val minReplyTop = if (replyingNodeIndex >= 0) outMinReplyTops[replyingNodeIndex] else Int.MIN_VALUE
        return RecognizedMessageAnatomy(
            senderName = senderName,
            senderRoleSubtitle = senderRoleSubtitle,
            repliedSenderName = repliedSenderName,
            repliedMessage = repliedMessages.joinToString("\n").ifEmpty { null },
            replyingText = replyingText,
            replyingNodeIndex = replyingNodeIndex,
            minReplyTopPx = minReplyTop
        )
    }

    private fun sharesNonZeroSubContainer(a: CollectedNodeSnapshot, b: CollectedNodeSnapshot): Boolean {
        return (a.subContainerNodeId != 0 && a.subContainerNodeId == b.subContainerNodeId) ||
            (a.parentNodeId != 0 && a.parentNodeId == b.parentNodeId)
    }
}
