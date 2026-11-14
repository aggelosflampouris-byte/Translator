package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Pure functions for text sanitization, UI element filtering,
 * and language validation before detected text blocks are submitted for translation.
 */
object TranslationFilter {

    private val URL_PATH_REGEX = Regex("""^/?[a-zA-Z0-9_\-/%?&=.]+$""")
    private val TIMESTAMP_REGEX = Regex("""^\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?$""", RegexOption.IGNORE_CASE)
    private val CHAT_RELATIVE_TIMESTAMP_REGEX = Regex(
        """^(?:(?:\d{1,2}\s+[a-zA-Zα-ωΑ-ΩάέήίόύώϊϋΐΰăâîșțĂÂÎȘȚ.]+\s+)?(?:today|yesterday|σήμερα|σημερα|χθες|astăzi|astazi|ieri|mon|tue|wed|thu|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday|δευ|τρι|τετ|πεμ|παρ|σαβ|κυρ|δευτέρα|τρίτη|τετάρτη|πέμπτη|παρασκευή|σάββατο|κυριακή|lun|mar|mie|joi|vin|sâm|sam|dum|luni|marți|marti|miercuri|vineri|sâmbătă|sambata|duminică|duminica)[,\s]+)?(at|στις|στὶς|la)\s+\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?$""",
        RegexOption.IGNORE_CASE
    )
    private val LEADING_SENDER_PREFIX_REGEX = Regex(
        """^(?:(?:you sent|you replied|reply(?:\s+to)?|εσείς στείλατε|εσεις στειλατε|εσείς απαντήσατε|εσεις απαντησατε|στείλατε|στειλατε|απαντήσατε|απαντησατε|απάντηση|απαντηση|ai trimis|ai răspuns|ai raspuns|răspuns|raspuns)(?:\s+(?:(?:στον?\s+χρήστη|στον?\s+χρηστη|στον?\s+εαυτό\s+(?:σας|του|της|τους)|σε\s+(?:τον?\s+)?(?:χρήστη|χρηστη|μήνυμ[άα]|μηνυμα)|σε|προς(?:\s+τον?)?|to|la)\s+[^\n:,?!;()]{1,100}|(?:στο\s+μήνυμ[άα]|στο\s+μηνυμα)(?:\s+(?:σας|του|της|τους))?(?:\s+[^\n:,?!;()]{1,100})?|σε\s+εσάς|σ'\s*εσάς))?(?:\s*«[^»\n]{1,120}»)?(?:[ \t]*[:,][ \t]+|[ \t]*\n+[ \t]*)|(?:εσείς στείλατε|εσεις στειλατε)\s+|(?:(?:ο|η|ο/η)\s+χρήστης\s+|(?:ο|η|ο/η)\s+χρηστης\s+|(?:ο|η|ο/η)\s+)?[^\n:,?!;()]{1,36}\s+(?:έστειλε|εστειλε|απάντησε|απαντησε|sent|replied|a trimis|a răspuns|a raspuns)(?:\s+(?:(?:στον?\s+χρήστη|στον?\s+χρηστη|στον?\s+εαυτό\s+(?:του|της|τους)|σε\s+(?:τον?\s+)?(?:χρήστη|χρηστη|μήνυμ[άα]|μηνυμα)|σε|προς(?:\s+τον?)?|to|la)\s+[^\n:,?!;()]{1,100}|(?:στο\s+μήνυμ[άα]|στο\s+μηνυμα)(?:\s+(?:σας|του|της|τους))?(?:\s+[^\n:,?!;()]{1,100})?|σε\s+εσάς|σ'\s*εσάς))?(?:\s*«[^»\n]{1,120}»)?(?:[ \t]*[:,][ \t]+|[ \t]*\n+[ \t]*)|(?:εσείς|εσεις|you)(?:[ \t]*[:,][ \t]+|[ \t]*\n+[ \t]*)|tu[ \t]*:[ \t]+)""",
        RegexOption.IGNORE_CASE
    )
    // Por qué: En etiquetas TalkBack en griego ("Λευτέρης Φλαμπούρης: Salut"), retira el prefijo
    // con dos puntos antes de un cuerpo en alfabeto latino.
    private val GREEK_SENDER_COLON_BEFORE_LATIN_REGEX = Regex(
        """^[α-ωΑ-Ωάέήίόύώϊϋΐΰς][α-ωΑ-Ωάέήίόύώϊϋΐΰς \t./'-]{0,42}:[ \t]+(?=[\s\S]*[a-zA-ZăâîșțĂÂÎȘȚ]{2})"""
    )
    // Por qué: En etiquetas con formato "<NOMBRE>, <mensaje>", retira nombres en mayúsculas o nombres
    // completos en griego antes de un cuerpo latino sin mutilar vocativos simples.
    private val GREEK_DISPLAY_NAME_PREFIX_BEFORE_LATIN_REGEX = Regex(
        """^(?!(?:ΝΑΙ|ΟΧΙ|ΣΟΣ)(?:[ \t]*[,:]|[ \t]*\n))(?:[Α-ΩΆΈΉΊΌΎΏΪΫ]{3,20}(?:[ \t]+[Α-ΩΆΈΉΊΌΎΏΪΫ]{2,20}){0,3}|[Α-ΩΆΈΉΊΌΎΏΪΫ][α-ωάέήίόύώϊϋΐΰς'-]{1,20}(?:[ \t]+[Α-ΩΆΈΉΊΌΎΏΪΫ][α-ωάέήίόύώϊϋΐΰς'-]{1,20}){1,3})(?:[ \t]*[,:][ \t]+|[ \t]*\n+[ \t]*)(?=[\s\S]*[a-zA-ZăâîșțĂÂÎȘȚ]{2})"""
    )
    private val TRAILING_ACTION_HINT_REGEX = Regex(
        """(?:[,.]\s*|\n+\s*)(?:πατήστε\s+(?:δύο|δυο|2)\s+φορές|πατηστε\s+(?:δυο|2)\s+φορες|κάντε\s+διπλό\s+πάτημα|καντε\s+διπλο\s+πατημα|πατήστε\s+παρατεταμένα|πατηστε\s+παρατεταμενα|άγγιγμα\s+και\s+παρατεταμένο|αγγιγμα\s+και\s+παρατεταμενο|double[- ]?tap|tap\s+and\s+hold)[^\n]*$""",
        RegexOption.IGNORE_CASE
    )
    // Por qué: TalkBack puede anexar conteos de reacción o lectores entre la hora y la sugerencia final;
    // retirarlos antes permite limpiar después el estado de entrega y la marca de tiempo restante.
    private val TRAILING_REACTION_OR_SEEN_REGEX = Regex(
        """(?:,\s*|\n+\s*)(?:(?:\d+\s+)?(?:αντίδραση|αντιδράσεις|αντιδραση|αντιδρασεις|reaction|reactions|reacție|reacții|reactie|reactii)(?:\s*[:：]?\s*[^\n,]*)?|(?:(?:ο|η|ο/η)\s+χρήστης\s+|(?:ο|η|ο/η)\s+χρηστης\s+|εσείς\s+)?[^\n,]{1,40}\s+(?:αντέδρασε|αντεδρασε|αντιδράσατε|αντιδρασατε|reacted)(?:\s+(?:με|with|cu)\s+[^\n,]*)?|(?:διαβάστηκε|διαβαστηκε|εθεάθη|εθεαθη|το\s+είδε|το\s+ειδε|seen|read)\s+(?:από(?:\s+τον?\s+χρήστη)?|απο(?:\s+τον?\s+χρηστη)?|by|de)\s+[^\n,]+)$""",
        RegexOption.IGNORE_CASE
    )
    private val CHAT_STATUS_REGEX = Regex(
        """[,.\s\n]+(delivered|read|sent|seen|pending|unread|edited|\(edited\)|επεξεργάστηκε|\(επεξεργάστηκε\)|τροποποιήθηκε|\(τροποποιήθηκε\)|τροποποιηθηκε|\(τροποποιηθηκε\)|editat|παραδόθηκε|παραδοθηκε|διαβάστηκε|διαβαστηκε|στάλθηκε|σταλθηκε|εστάλη|εσταλη|απεστάλη|απεσταλη|εθεάθη|εθεαθη|εμφανίστηκε|εμφανιστηκε|το είδε|το ειδε|σε εκκρεμότητα|trimis|citit|citiți|cititi|livrat|în așteptare|[✓✔\u2713\u2714\u2705]+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val STANDALONE_STATUS_REGEX = Regex(
        """^([,.:\s\n]*(\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?)?[,.:\s\n]*(delivered|read|sent|seen|pending|unread|edited|\(edited\)|επεξεργάστηκε|\(επεξεργάστηκε\)|τροποποιήθηκε|\(τροποποιήθηκε\)|τροποποιηθηκε|\(τροποποιηθηκε\)|editat|παραδόθηκε|παραδοθηκε|διαβάστηκε|διαβαστηκε|στάλθηκε|σταλθηκε|εστάλη|εσταλη|απεστάλη|απεσταλη|εθεάθη|εθεαθη|εμφανίστηκε|εμφανιστηκε|το είδε|το ειδε|σε εκκρεμότητα|σε εκκρεμοτητα|εκκρεμεί|εκκρεμει|trimis|citit|citiți|cititi|livrat|în așteptare|in asteptare|[✓✔\u2713\u2714\u2705]+)[,.:\s\n]*)+$""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_TIMESTAMP_REGEX = Regex(
        """(?:(?:,\s*|\n+\s*)(?:today|yesterday|σήμερα|σημερα|χθες|mon|tue|wed|thu|fri|sat|sun|δευ|τρι|τετ|πεμ|παρ|σαβ|κυρ|lun|mar|mie|joi|vin|sâm|sam|dum)?\s*(?:at|στις|στὶς|la)?\s*|[\s\n]+)\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?(\s*[✓✔\u2713\u2714\u2705]+)?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val PERCENTAGE_REGEX = Regex("""^\d{1,3}%$""")
    private val TRAILING_PUNCT_SPACE_REGEX = Regex("""[,:\s]+$""")

    private val COMMON_UI_TOKENS = setOf(
        "μήνυμα", "type a message", "type a message...", "message", "aa", "signal message",
        "search", "αναζήτηση", "χθες", "σήμερα", "yesterday", "today", "online", "συνδέθηκε",
        "active now", "ενεργός τώρα", "ενεργή τώρα", "ενεργός/ή τώρα", "ενεργος/η τωρα",
        "typing...", "συνομιλίες", "ενημερώσεις", "κλήσεις",
        "chats", "updates", "calls", "channels", "stories", "reels", "bot", "app",
        "πίσω", "back", "κλήση", "call", "φωνητική κλήση", "voice call",
        "βιντεοκλήση", "video call", "πληροφορίες", "info", "conversation information",
        "κάμερα", "camera", "συλλογή", "gallery", "μικρόφωνο", "voice clip",
        "μου αρέσει", "like", "thumbs up", "αυτοκόλλητα", "stickers", "gif", "emoji",
        "περισσότερα", "more", "μενού", "menu"
    )

    private val SYSTEM_NOTICE_PHRASES = listOf(
        "end-to-end encrypted",
        "κρυπτογραφημένα από άκρο σε άκρο",
        "κρυπτογραφημενα απο ακρο σε ακρο",
        "criptate de la un capăt la altul",
        "criptate de la un capat la altul",
        "waiting for this message",
        "αναμονή για αυτό το μήνυμα",
        "αναμονη για αυτο το μηνυμα",
        "în așteptarea acestui mesaj",
        "in asteptarea acestui mesaj",
        "missed voice call",
        "missed video call",
        "αναπάντητη φωνητική κλήση",
        "αναπαντητη φωνητικη κληση",
        "αναπάντητη βιντεοκλήση",
        "αναπαντητη βιντεοκληση",
        "apel vocal nepreluat",
        "apel video nepreluat",
        "disappearing messages",
        "αυτοκαταστρεφόμενα μηνύματα",
        "mesaje temporare",
        "liked a message",
        "reacted to your message",
        "απαντήσατε στο χρήστη",
        "απαντησατε στο χρηστη",
        "απαντήσατε στον χρήστη",
        "απαντησατε στον χρηστη",
        "απάντησε στο χρήστη",
        "απάντησε στον χρήστη",
        "απαντησε στο χρηστη",
        "απαντησε στον χρηστη",
        "απάντησε στο μήνυμά σας",
        "αντέδρασε στο μήνυμα",
        "you replied to",
        "replied to their own message",
        "a răspuns la mesajul tău",
        "a raspuns la mesajul tau"
    )

    private val MESSAGE_STATUS_TOKENS = setOf(
        "διαβάστηκε", "διαβαστηκε", "παραδόθηκε", "παραδοθηκε", "στάλθηκε", "σταλθηκε",
        "εστάλη", "εσταλη", "απεστάλη", "απεσταλη", "εθεάθη", "εθεαθη",
        "εμφανίστηκε", "εμφανιστηκε", "το είδε", "το ειδε",
        "σε εκκρεμότητα", "σε εκκρεμοτητα", "εκκρεμεί", "εκκρεμει", "μη αναγνωσμένο", "μη αναγνωσμενο",
        "επεξεργάστηκε", "επεξεργαστηκε", "τροποποιήθηκε", "(τροποποιήθηκε)", "τροποποιηθηκε", "(τροποποιηθηκε)",
        "read", "delivered", "sent", "seen", "pending", "unread", "edited", "(edited)",
        "citit", "citiți", "cititi", "livrat", "trimis", "editat", "în așteptare", "in asteptare",
        "leído", "leido", "entregado", "enviado", "pendiente"
    )

    private val STANDALONE_AT_MENTION_REGEX = Regex(
        """^@[~\p{L}\p{N}_.-]+(?:\s+[~\p{L}\p{N}_.-]+){0,4}$"""
    )
    private val TRAILING_PHONE_SUFFIX_REGEX = Regex(
        """(?:\s*[,;|]?\s*\+\d[\d\s\-().]{6,}\d)$"""
    )
    private val STANDALONE_PHONE_REGEX = Regex(
        """^\+?\d[\d\s\-().]{6,}\d$"""
    )
    private val TIME_HHMM_CONTAINED_REGEX = Regex("""\b\d{1,2}:\d{2}\b""")
    private val NUMERIC_APPROX_UNIT_REGEX = Regex(
        """^\d+(?:[.,]\d+)?\s+(?:min|minute|min\.|ore|ora|oră|ani|an|zi|zile|lei|leu|eur|euro|ron|usd|km|m|cm|mm|kg|g|gb|mb|%|s)$""",
        RegexOption.IGNORE_CASE
    )
    private val SENDER_STARTER_WORDS = setOf(
        "salut", "buna", "bună", "mersi", "multumesc", "mulțumesc", "ce", "nu", "da"
    )
    private val WHITESPACE_SPLIT_REGEX = Regex("""\s+""")

    /**
     * Normaliza marcas bidireccionales, espacios Unicode y variantes visuales de virgulilla (`∼˜⁓～`)
     * para detectar alias de remitente sin perder coincidencias en TalkBack/WhatsApp.
     */
    private fun normalizeHandleInspectText(raw: String): String {
        return TextSanitizer.sanitizeSpecialCharacters(raw)
            .replace("\u200D", "")
            .replace('\u02DC', '~')
            .replace('\u02F7', '~')
            .replace('\u0303', '~')
            .replace('\u2053', '~')
            .replace('\u223C', '~')
            .replace('\uFF5E', '~')
            .trim()
    }

    /**
     * Indica si el texto representa una marca horaria de mensaje (`00:26`, `Τροποποιήθηκε 00:27`)
     * para distinguir burbujas independientes frente a citas incrustadas sin marca propia.
     */
    fun isTimestampLikeText(text: String): Boolean {
        val normalized = TextSanitizer.sanitizeSpecialCharacters(text).trim()
        if (normalized.isEmpty() || !TIME_HHMM_CONTAINED_REGEX.containsMatchIn(normalized)) return false
        if (normalized.matches(TIMESTAMP_REGEX) ||
            normalized.matches(CHAT_RELATIVE_TIMESTAMP_REGEX) ||
            normalized.matches(STANDALONE_STATUS_REGEX)
        ) {
            return true
        }
        val stripped = cleanMessageText(normalized).trim()
        return stripped.isEmpty() || MESSAGE_STATUS_TOKENS.contains(stripped.lowercase())
    }

    /**
     * Indica si el texto corresponde a un número telefónico aislado en cabeceras grupales (`+40 773 947 253`).
     */
    fun isPhoneNumberText(text: String): Boolean {
        val normalized = normalizeHandleInspectText(text)
        if (normalized.isEmpty() || normalized.any { it.isLetter() }) return false
        val digitCount = normalized.count { it.isDigit() }
        return digitCount in 7..16 && normalized.matches(STANDALONE_PHONE_REGEX)
    }

    /**
     * Detecta menciones puras (`@~Geala Stefan`) o alias de participante en chats grupales de WhatsApp (`~ stefan`,
     * `~ Crypto1706`) para no traducir nombres de remitentes ni encender consultas IPC padre sobre ellos.
     */
    fun isSenderHandleOrMentionOnly(text: String): Boolean {
        val normalized = normalizeHandleInspectText(text)
        if (normalized.isEmpty() || normalized.contains('\n')) return false
        if (normalized.indexOfAny(charArrayOf('?', '!', ';', ':', ',')) >= 0) return false
        if (normalized.matches(STANDALONE_AT_MENTION_REGEX) && normalized.any { it.isLetter() }) {
            return true
        }
        if (!normalized.startsWith('~')) return false

        val phoneStripped = normalized.replace(TRAILING_PHONE_SUFFIX_REGEX, "").trim()
        val hadPhoneSuffix = phoneStripped.length < normalized.length
        val withoutTilde = phoneStripped.trimStart('~').trim()
        if (withoutTilde.isEmpty() || withoutTilde.length > 48 || !withoutTilde.any { it.isLetter() }) {
            return false
        }
        if (ScriptDetector.containsUrlOrDomain(withoutTilde)) return false
        if (!hadPhoneSuffix && withoutTilde.matches(NUMERIC_APPROX_UNIT_REGEX)) return false

        val tokens = withoutTilde.split(WHITESPACE_SPLIT_REGEX).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 6) return false
        if (!hadPhoneSuffix) {
            val firstWord = tokens.first().lowercase().trimEnd('.', '…')
            if (firstWord in SENDER_STARTER_WORDS) {
                return false
            }
        }
        return true
    }

    /**
     * Comprobación rápida sin asignación de `Regex` para evitar consultas `node.parent` por IPC
     * en nodos que no contienen letras o que ya están escritos en el idioma de destino.
     */
    fun hasPlausibleLetterCandidate(
        directText: String?,
        descText: String?,
        configuredTarget: String
    ): Boolean {
        return isFieldPlausibleForParentExpansion(directText, configuredTarget) ||
            isFieldPlausibleForParentExpansion(descText, configuredTarget)
    }

    private fun isFieldPlausibleForParentExpansion(raw: String?, configuredTarget: String): Boolean {
        if (raw.isNullOrBlank()) return false
        val normalized = normalizeHandleInspectText(raw)
        if (normalized.isEmpty() || isPhoneNumberText(normalized) || isSenderHandleOrMentionOnly(normalized)) {
            return false
        }
        var hasLetter = false
        for (i in 0 until normalized.length) {
            if (normalized[i].isLetter()) {
                hasLetter = true
                break
            }
        }
        if (!hasLetter) return false
        if (configuredTarget == TranslateLanguage.GREEK) {
            val greekCount = ScriptDetector.countGreek(normalized)
            val latinCount = ScriptDetector.countLatin(normalized)
            // Por qué: En TalkBack puede venir "ΛΕΥΤΕΡΗΣ, Bună"; solo omitimos si no hay cuerpo latino significativo.
            if (greekCount > 0 && latinCount < 2) return false
        }
        return true
    }

    /**
     * Determines whether a recognized text snippet is a candidate for translation
     * or should be discarded (e.g. URLs, timestamps, UI chrome, battery, target-language script).
     */
    fun shouldTranslate(
        text: String,
        configuredTarget: String,
        configuredSource: String = TranslateLanguage.ROMANIAN,
        deferUnknownLatinToMlKit: Boolean = false
    ): Boolean {
        return filterRejectionReason(
            text = text,
            configuredTarget = configuredTarget,
            configuredSource = configuredSource,
            deferUnknownLatinToMlKit = deferUnknownLatinToMlKit
        ) == null
    }

    /**
     * Devuelve un código corto sin PII indicando por qué se descartó el texto, o `null` si es candidato válido.
     */
    fun filterRejectionReason(
        text: String,
        configuredTarget: String,
        configuredSource: String = TranslateLanguage.ROMANIAN,
        deferUnknownLatinToMlKit: Boolean = false,
        alreadyCleaned: Boolean = false
    ): String? {
        val cleaned = if (alreadyCleaned) text else cleanMessageText(text)
        val trimmed = cleaned.trim()
        if (trimmed.length < 2) return "emp"

        // Must contain at least one letter
        if (!trimmed.any { it.isLetter() }) return "let"

        // Filter out URLs, schemes, domain extensions, and URL path fragments
        if (ScriptDetector.containsUrlOrDomain(trimmed) ||
            trimmed.contains("://") ||
            (trimmed.startsWith("/") && !trimmed.contains(" ")) ||
            (trimmed.matches(URL_PATH_REGEX) && (trimmed.contains("/") || (trimmed.length > 8 && !trimmed.contains(" "))))) {
            return "url"
        }

        // Filter out pure timestamps (e.g. "5:16 μ.μ.", "12:55", "Today at 2:30 PM")
        if (trimmed.matches(TIMESTAMP_REGEX) || trimmed.matches(CHAT_RELATIVE_TIMESTAMP_REGEX)) {
            return "ts"
        }

        val lower = trimmed.lowercase()
        if (COMMON_UI_TOKENS.contains(lower) || MESSAGE_STATUS_TOKENS.contains(lower)) {
            return "ui"
        }

        // Filter out chat system and call notices
        if (SYSTEM_NOTICE_PHRASES.any { lower.contains(it) }) {
            return "sys"
        }

        // Filter out standalone group-chat sender handles ("~ stefan") and @mentions ("@~Geala Stefan")
        if (isSenderHandleOrMentionOnly(trimmed)) {
            return "snd"
        }

        // Filter out standalone status or combined timestamp + delivery markers
        if (lower.matches(STANDALONE_STATUS_REGEX)) {
            return "st"
        }

        // Filter out checkmark symbols (e.g. "✓", "✓✓")
        if (trimmed.all { it in "✓✔\u2713\u2714\u2705,.:\t\n " }) {
            return "chk"
        }

        // Filter out percentage / battery indicators (e.g. "71%")
        if (trimmed.matches(PERCENTAGE_REGEX)) {
            return "pct"
        }

        // If target is Greek, skip any block that is purely or predominantly Greek text
        if (configuredTarget == TranslateLanguage.GREEK) {
            val greekCharCount = ScriptDetector.countGreek(trimmed)
            val latinCharCount = ScriptDetector.countLatin(trimmed)
            if (greekCharCount > 0 && (latinCharCount == 0 || greekCharCount > latinCharCount)) {
                return "grk($greekCharCount/$latinCharCount)"
            }
        }

        // Por qué: Valida estrictamente que el texto sea elegible como idioma de origen,
        // descartando otros idiomas (ej. inglés) antes de enviarlo al traductor o a la caché.
        if (!TranslationSenseEngine.isEligibleSourceText(
                text = trimmed,
                sourceLang = configuredSource,
                targetLang = configuredTarget,
                identifiedLang = null,
                allowDeferredLatinForMlKit = deferUnknownLatinToMlKit,
                alreadyCleaned = true
            )
        ) {
            return "elig"
        }

        return null
    }

    private fun stripTrailingEnvelopeMetadata(text: String): String {
        val sanitized = TextSanitizer.sanitizeSpecialCharacters(text).trim()
        // Por qué: Si el nodo completo es únicamente un separador de fecha/hora (ej. "ΤΕΤ ΣΤΙΣ 6:02 Μ.Μ."),
        // se descarta por completo antes de recortar la hora final para no dejar fragmentos sueltos ("ΤΕΤ ΣΤΙΣ").
        if (sanitized.matches(TIMESTAMP_REGEX) || sanitized.matches(CHAT_RELATIVE_TIMESTAMP_REGEX)) {
            return ""
        }
        var cleaned = sanitized
        for (pass in 0 until 4) {
            val before = cleaned
            cleaned = cleaned.replace(TRAILING_ACTION_HINT_REGEX, "").trim()
            cleaned = cleaned.replace(TRAILING_REACTION_OR_SEEN_REGEX, "").trim()
            cleaned = cleaned.replace(CHAT_STATUS_REGEX, "").trim()
            cleaned = cleaned.replace(TRAILING_TIMESTAMP_REGEX, "").trim()
            if (cleaned == before) break
        }
        return cleaned.replace(TRAILING_PUNCT_SPACE_REGEX, "").trim()
    }

    private fun stripLeadingSenderPrefixes(cleanedText: String): String {
        var cleaned = cleanedText.trim()
        if (cleaned.isEmpty()) return ""
        // Por qué: Elimina prefijos de accesibilidad ("ΑΓΓΕΛΟΣ, ", "Εσείς στείλατε: ",
        // "Απαντήσατε στο χρήστη ...: "), aplicando hasta 2 pasadas por si hay nombre + etiqueta de respuesta.
        for (pass in 0 until 2) {
            val before = cleaned
            cleaned = cleaned.replace(LEADING_SENDER_PREFIX_REGEX, "").trim()
            cleaned = cleaned.replace(GREEK_SENDER_COLON_BEFORE_LATIN_REGEX, "").trim()
            cleaned = cleaned.replace(GREEK_DISPLAY_NAME_PREFIX_BEFORE_LATIN_REGEX, "").trim()
            if (cleaned == before) break
        }
        return cleaned.replace(TRAILING_PUNCT_SPACE_REGEX, "").trim()
    }

    /**
     * Cleans Accessibility-detected text by stripping trailing timestamps,
     * edited tags, and delivery status markers across supported chat apps.
     */
    fun cleanMessageText(text: String): String {
        val stripped = stripTrailingEnvelopeMetadata(text)
        if (stripped.isEmpty()) return ""
        return stripLeadingSenderPrefixes(stripped)
    }
}
