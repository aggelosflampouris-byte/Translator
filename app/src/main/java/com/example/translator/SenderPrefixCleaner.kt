package com.example.translator

import java.text.Normalizer

/**
 * Extractor y limpiador puro de nombres de remitente inyectados por la accesibilidad
 * de aplicaciones como Messenger ("<Remitente>, <Mensaje>"), evitando mostrar el nombre
 * del remitente en las burbujas traducidas sin afectar vocativos escritos por el usuario.
 */
object SenderPrefixCleaner {

    data class BubbleTextSample(
        val text: String,
        val left: Int,
        val right: Int
    )

    data class DiscoveredSenders(
        val partnerNames: Set<String> = emptySet(),
        val selfNames: Set<String> = emptySet()
    )

    // Por qué: Separa un encabezado breve de remitente del cuerpo real cuando van unidos por ':', ',' o salto de línea.
    // Evitamos clases de caracteres con llaves '{}' por incompatibilidad estricta del motor ICU de Android.
    private val LEADING_PREFIX_DELIM_REGEX = Regex(
        """^([^\n,:]{2,42}?)(?:[ \t]*[:,][ \t]+|[ \t]*\n+[ \t]*)(.+)$""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val HEADER_STATUS_REGEX = Regex(
        """^(?:ενεργ[όή]ς(?:/[ήό])?\s+τώρα|ενεργ[όή]ς\s+πριν.*|active\s+now|active\s+\d+.*ago|activ(?:ă)?\s+acum|last\s+seen.*|τελευταία\s+εμφάνιση.*|πληκτρολογεί.*|typing.*)$""",
        RegexOption.IGNORE_CASE
    )

    // Por qué: Caracteres propios de oración o signos que nunca forman parte de un nombre de usuario.
    private const val INVALID_SENDER_PUNCTUATION = "?!;()[]{}«»\""

    // Por qué: Vocativos griegos frecuentes que el usuario puede escribir manualmente al inicio de un mensaje
    // y no deben confundirse con el prefijo de remitente TalkBack en una burbuja verificada.
    private val GREEK_VOCATIVE_EXCEPTIONS = setOf(
        "αγγελε", "γιωργο", "γιαννη", "νικο", "κωστα", "δημητρη",
        "φιλε", "αδερφε", "καλε", "μαλακα", "κυριε", "κυρια", "παιδια", "ρε"
    )

    // Por qué: Palabras de cortesía, interjecciones y acrónimos frecuentes que no deben interpretarse nunca como nombre propio
    private val NON_SENDER_TOKENS = setOf(
        "ok", "oke", "okay", "da", "nu", "ba", "hai", "pa", "na", "uite",
        "bine", "sigur", "desigur", "bravo", "mersi", "scuze", "salut", "buna", "neata",
        "hey", "hi", "hello", "yo", "yes", "no", "please", "sorry", "thanks", "thank",
        "omg", "wtf", "btw", "fyi", "lol", "brb", "asap", "sos", "ps", "nb",
        "γεια", "ναι", "οχι", "οκ", "καλα", "τελεια", "μπραβο", "ελα", "παμε",
        "σοβαρα", "αληθεια", "εισαι", "εγω", "εσυ", "εμεις", "αυτος", "αυτη",
        "today", "yesterday", "σημερα", "χθες", "αυριο", "azi", "maine", "ieri"
    )

    fun supportsAccessibilitySenderPrefix(profileId: String): Boolean {
        return profileId == "messenger"
    }

    /**
     * Normaliza un nombre eliminando acentos y diacríticos en griego, rumano y alfabeto latino.
     */
    fun normalizeName(raw: String): String {
        val decomposed = Normalizer.normalize(raw.lowercase().trim(), Normalizer.Form.NFD)
        val stripped = buildString(decomposed.length) {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                    append(ch)
                }
            }
        }
        return stripped
            .replace(Regex("""[.,:;\u2026]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun isPlausibleSenderName(candidate: String): Boolean {
        val trimmed = candidate.trim()
        if (trimmed.length !in 2..42) return false
        if (trimmed.any { it.isDigit() || it in INVALID_SENDER_PUNCTUATION }) return false

        val letterCount = trimmed.count { it.isLetter() }
        if (letterCount < 2) return false

        val tokens = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 4) return false

        val normalized = normalizeName(trimmed)
        if (normalized.isBlank() || NON_SENDER_TOKENS.contains(normalized)) return false
        if (TranslationFilter.isUiOrStatusToken(trimmed.lowercase()) ||
            TranslationFilter.isUiOrStatusToken(normalized)) {
            return false
        }
        return true
    }

    fun extractLeadingPrefixCandidate(text: String): Pair<String, String>? {
        val match = LEADING_PREFIX_DELIM_REGEX.find(text.trimStart()) ?: return null
        val prefix = match.groupValues[1].trim()
        val remainder = match.groupValues[2].trimStart()
        if (!isPlausibleSenderName(prefix)) return null
        if (!remainder.any { it.isLetterOrDigit() }) return null
        return Pair(prefix, remainder)
    }

    /**
     * Extrae los posibles nombres del interlocutor desde la cabecera fija superior del chat.
     */
    fun extractPartnerNamesFromHeaderTexts(headerTexts: List<String>): Set<String> {
        if (headerTexts.isEmpty()) return emptySet()
        val names = linkedSetOf<String>()

        for (rawHeader in headerTexts) {
            val segments = rawHeader.split('\n', ',')
            for (rawSegment in segments) {
                val segment = rawSegment.trim()
                if (segment.isBlank()) continue
                val lowerSeg = segment.lowercase()
                if (HEADER_STATUS_REGEX.matches(segment) ||
                    TranslationFilter.isUiOrStatusToken(lowerSeg) ||
                    TranslationFilter.isUiOrStatusToken(normalizeName(segment))) {
                    continue
                }

                // Por qué: Si Android elipsa el apellido en la barra ("Λευτέρης Φλαμ..."), conservamos igualmente el primer nombre íntegro
                val isEllipsized = segment.endsWith("...") || segment.endsWith("…")
                val cleanedSegment = segment.replace(Regex("""(?:\.\.\.|…)+$"""), "").trim()
                if (cleanedSegment.isBlank()) continue

                if (!isEllipsized && isPlausibleSenderName(cleanedSegment)) {
                    names.add(normalizeName(cleanedSegment))
                }

                val tokens = cleanedSegment.split(Regex("""\s+""")).filter { it.isNotBlank() }
                val firstToken = tokens.firstOrNull()
                if (firstToken != null && (!isEllipsized || tokens.size >= 2)) {
                    val normFirst = normalizeName(firstToken)
                    if (normFirst.length >= 3 &&
                        !firstToken.contains('.') &&
                        isPlausibleSenderName(firstToken) &&
                        !NON_SENDER_TOKENS.contains(normFirst)) {
                        names.add(normFirst)
                    }
                }
            }
        }
        return names
    }

    /**
     * Detecta nombres de remitente repetidos por alineación lateral (izquierda = interlocutor, derecha = usuario)
     * combinándolos con los nombres extraídos de la cabecera del chat.
     */
    fun discoverSideSenders(
        samples: List<BubbleTextSample>,
        headerPartnerNames: Set<String>,
        screenWidth: Int
    ): DiscoveredSenders {
        val leftBodiesByPrefix = linkedMapOf<String, MutableSet<String>>()
        val rightBodiesByPrefix = linkedMapOf<String, MutableSet<String>>()

        for (sample in samples) {
            val strippedAction = TranslationFilter.stripExplicitActionPrefix(sample.text)
            val candidate = extractLeadingPrefixCandidate(strippedAction) ?: continue
            val normPrefix = normalizeName(candidate.first)
            if (normPrefix.isBlank()) continue

            val isOutgoing = screenWidth > 0 && sample.right > screenWidth * 0.72f
            val isIncoming = screenWidth > 0 && !isOutgoing && sample.left < screenWidth * 0.28f
            val targetMap = when {
                isOutgoing -> rightBodiesByPrefix
                isIncoming -> leftBodiesByPrefix
                else -> continue
            }
            val normalizedBody = normalizeName(candidate.second).take(48)
            if (normalizedBody.isNotBlank()) {
                targetMap.getOrPut(normPrefix) { linkedSetOf() }.add(normalizedBody)
            }
        }

        // Por qué: Exigir al menos 2 cuerpos distintos por lado evita falsos positivos con vocativos únicos
        val discoveredPartners = linkedSetOf<String>().apply {
            addAll(headerPartnerNames)
            for ((prefix, bodies) in leftBodiesByPrefix) {
                if (bodies.size >= 2) add(prefix)
            }
        }
        val discoveredSelf = linkedSetOf<String>().apply {
            for ((prefix, bodies) in rightBodiesByPrefix) {
                if (bodies.size >= 2) add(prefix)
            }
        }
        return DiscoveredSenders(
            partnerNames = discoveredPartners,
            selfNames = discoveredSelf
        )
    }

    /**
     * Elimina el prefijo de remitente del lado correspondiente (entrante/saliente) y detecta casos
     * en los que el nodo contenía exclusivamente el nombre del remitente sin cuerpo de texto.
     */
    fun stripSenderPrefix(
        textAfterSuffixStrip: String,
        isOutgoing: Boolean,
        hadEnvelopeMetadata: Boolean,
        discoveredSenders: DiscoveredSenders,
        allowGenericEnvelopeAllCaps: Boolean = true
    ): String {
        var current = textAfterSuffixStrip.trim()
        if (current.isBlank()) return ""

        val sideKnownSenders = if (isOutgoing) discoveredSenders.selfNames else discoveredSenders.partnerNames

        for (pass in 0 until 2) {
            val candidate = extractLeadingPrefixCandidate(current) ?: break
            val rawPrefix = candidate.first
            val normPrefix = normalizeName(rawPrefix)

            val matchesKnownSender = sideKnownSenders.contains(normPrefix)
            val matchesEnvelopeAllCaps = !matchesKnownSender &&
                allowGenericEnvelopeAllCaps &&
                hadEnvelopeMetadata &&
                isAllCapsDisplayName(rawPrefix)
            val matchesEnvelopeGreekBeforeLatin = !matchesKnownSender &&
                !matchesEnvelopeAllCaps &&
                pass == 0 &&
                hadEnvelopeMetadata &&
                isGreekDisplayPrefixBeforeLatin(rawPrefix, candidate.second, normPrefix)

            if (matchesKnownSender || matchesEnvelopeAllCaps || matchesEnvelopeGreekBeforeLatin) {
                current = candidate.second.trim()
                continue
            }
            break
        }

        // Por qué: Si tras retirar metadatos de accesibilidad solo sobrevivió el nombre del remitente
        // (p. ej. un sticker o foto sin texto), se descarta para no traducir el nombre aislado.
        val normalizedRemainder = normalizeName(current)
        if (normalizedRemainder.isNotBlank() && sideKnownSenders.contains(normalizedRemainder)) {
            return ""
        }

        return current
    }

    private fun isGreekDisplayPrefixBeforeLatin(
        rawPrefix: String,
        remainder: String,
        normalizedPrefix: String
    ): Boolean {
        val trimmedPrefix = rawPrefix.trim()
        if (trimmedPrefix.any { it.isWhitespace() }) return false
        val firstLetter = trimmedPrefix.firstOrNull { it.isLetter() } ?: return false
        if (!firstLetter.isUpperCase()) return false
        if (ScriptDetector.countGreek(trimmedPrefix) < 3 || ScriptDetector.countLatin(trimmedPrefix) > 0) {
            return false
        }
        if (ScriptDetector.countLatin(remainder) < 2) return false
        return !NON_SENDER_TOKENS.contains(normalizedPrefix) && !GREEK_VOCATIVE_EXCEPTIONS.contains(normalizedPrefix)
    }

    private fun isAllCapsDisplayName(prefix: String): Boolean {
        val letters = prefix.filter { it.isLetter() }
        if (letters.length < 3) return false
        if (!letters.all { it.isUpperCase() }) return false
        val norm = normalizeName(prefix)
        return !NON_SENDER_TOKENS.contains(norm)
    }
}
