package com.example.translator

import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Pure functions for text sanitization, UI element filtering,
 * and language validation before OCR text blocks are submitted for translation.
 */
object TranslationFilter {

    private val URL_PATH_REGEX = Regex("""^/?[a-zA-Z0-9_\-/%?&=.]+$""")
    private val TIMESTAMP_REGEX = Regex("""^\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?$""", RegexOption.IGNORE_CASE)
    private val CHAT_RELATIVE_TIMESTAMP_REGEX = Regex(
        """^([a-zA-Zα-ωΑ-ΩάέήίόύώϊϋΐΰăâîșțĂÂÎȘȚ.]+\s+)?(today|yesterday|σήμερα|σημερα|χθες|astăzi|astazi|ieri|mon|tue|wed|thu|fri|sat|sun|δευ|τρι|τετ|πεμ|παρ|σαβ|κυρ|lun|mar|mie|joi|vin|sâm|sam|dum)?\s*(at|στις|στὶς|la)\s+\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?$""",
        RegexOption.IGNORE_CASE
    )
    private val LEADING_SENDER_PREFIX_REGEX = Regex(
        """^(?:(?:you sent|you replied|εσείς στείλατε|εσεις στειλατε|στείλατε|στειλατε|απαντήσατε|απαντησατε|ai trimis|ai răspuns|ai raspuns)\s*:\s*|(?:εσείς στείλατε|εσεις στειλατε)\s+)""",
        RegexOption.IGNORE_CASE
    )
    private val CHAT_STATUS_REGEX = Regex(
        """[,.\s\n]+(delivered|read|sent|seen|pending|unread|edited|\(edited\)|επεξεργάστηκε|\(επεξεργάστηκε\)|editat|παραδόθηκε|διαβάστηκε|στάλθηκε|εστάλη|εσταλη|το είδε|το ειδε|σε εκκρεμότητα|trimis|citit|citiți|cititi|livrat|în așteptare|[✓✔\u2713\u2714\u2705]+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val STANDALONE_STATUS_REGEX = Regex(
        """^([,.:\s\n]*(\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?)?[,.:\s\n]*(delivered|read|sent|seen|pending|unread|edited|\(edited\)|επεξεργάστηκε|\(επεξεργάστηκε\)|editat|παραδόθηκε|παραδοθηκε|διαβάστηκε|διαβαστηκε|στάλθηκε|σταλθηκε|εστάλη|εσταλη|το είδε|το ειδε|σε εκκρεμότητα|σε εκκρεμοτητα|εκκρεμεί|εκκρεμει|trimis|citit|citiți|cititi|livrat|în așteptare|in asteptare|[✓✔\u2713\u2714\u2705]+)[,.:\s\n]*)+$""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_TIMESTAMP_REGEX = Regex("""[\s\n]+\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?(\s*[✓✔\u2713\u2714\u2705]+)?\s*$""", RegexOption.IGNORE_CASE)
    private val PERCENTAGE_REGEX = Regex("""^\d{1,3}%$""")

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
        "you replied to"
    )

    private val MESSAGE_STATUS_TOKENS = setOf(
        "διαβάστηκε", "διαβαστηκε", "παραδόθηκε", "παραδοθηκε", "στάλθηκε", "σταλθηκε",
        "εστάλη", "εσταλη", "το είδε", "το ειδε",
        "σε εκκρεμότητα", "σε εκκρεμοτητα", "εκκρεμεί", "εκκρεμει", "μη αναγνωσμένο", "μη αναγνωσμενο",
        "επεξεργάστηκε", "επεξεργαστηκε",
        "read", "delivered", "sent", "seen", "pending", "unread", "edited", "(edited)",
        "citit", "citiți", "cititi", "livrat", "trimis", "editat", "în așteptare", "in asteptare",
        "leído", "leido", "entregado", "enviado", "pendiente"
    )

    /**
     * Determines whether a recognized text snippet is a candidate for translation
     * or should be discarded (e.g. URLs, timestamps, UI chrome, battery, target-language script).
     */
    fun shouldTranslate(
        text: String,
        configuredTarget: String,
        configuredSource: String = TranslateLanguage.ROMANIAN
    ): Boolean {
        val cleaned = cleanMessageText(text)
        val trimmed = cleaned.trim()
        if (trimmed.length < 2) return false

        // Must contain at least one letter
        if (!trimmed.any { it.isLetter() }) return false

        // Filter out URLs, schemes, domain extensions, and URL path fragments
        if (ScriptDetector.containsUrlOrDomain(trimmed) ||
            trimmed.contains("://") ||
            (trimmed.startsWith("/") && !trimmed.contains(" ")) ||
            (trimmed.matches(URL_PATH_REGEX) && (trimmed.contains("/") || (trimmed.length > 8 && !trimmed.contains(" "))))) {
            return false
        }

        // Filter out pure timestamps (e.g. "5:16 μ.μ.", "12:55", "Today at 2:30 PM")
        if (trimmed.matches(TIMESTAMP_REGEX) || trimmed.matches(CHAT_RELATIVE_TIMESTAMP_REGEX)) {
            return false
        }

        val lower = trimmed.lowercase()
        if (COMMON_UI_TOKENS.contains(lower) || MESSAGE_STATUS_TOKENS.contains(lower)) {
            return false
        }

        // Filter out chat system and call notices
        if (SYSTEM_NOTICE_PHRASES.any { lower.contains(it) }) {
            return false
        }

        // Filter out standalone status or combined timestamp + delivery markers
        if (lower.matches(STANDALONE_STATUS_REGEX)) {
            return false
        }

        // Filter out checkmark symbols (e.g. "✓", "✓✓")
        if (trimmed.all { it in "✓✔\u2713\u2714\u2705,.:\t\n " }) {
            return false
        }

        // Filter out percentage / battery indicators (e.g. "71%")
        if (trimmed.matches(PERCENTAGE_REGEX)) {
            return false
        }

        // If target is Greek, skip any block that is purely or predominantly Greek text
        if (configuredTarget == TranslateLanguage.GREEK) {
            val greekCharCount = ScriptDetector.countGreek(trimmed)
            val latinCharCount = ScriptDetector.countLatin(trimmed)
            if (greekCharCount > 0 && (latinCharCount == 0 || greekCharCount > latinCharCount)) {
                return false
            }
        }

        // Por qué: Valida estrictamente que el texto sea eligible como idioma de origen (ej. rumano),
        // descartando ofertas de trabajo o mensajes en inglés para no traducirlos erróneamente a inglés.
        if (configuredSource != "AUTO") {
            if (!TranslationSenseEngine.isEligibleSourceText(trimmed, configuredSource, configuredTarget)) {
                return false
            }
        }

        return true
    }

    /**
     * Cleans OCR- or Accessibility-detected text by stripping trailing timestamps,
     * edited tags, and delivery status markers across supported messaging apps.
     */
    fun cleanMessageText(text: String): String {
        var cleaned = TextSanitizer.sanitizeSpecialCharacters(text).trim()
        // Por qué: Si el nodo completo es únicamente un separador de fecha/hora (ej. "ΤΕΤ ΣΤΙΣ 6:02 Μ.Μ."),
        // se descarta por completo antes de recortar la hora final para no dejar fragmentos sueltos ("ΤΕΤ ΣΤΙΣ").
        if (cleaned.matches(TIMESTAMP_REGEX) || cleaned.matches(CHAT_RELATIVE_TIMESTAMP_REGEX)) {
            return ""
        }
        // Por qué: Elimina prefijos de accesibilidad de Messenger/Instagram ("Εσείς στείλατε: ", "You sent: ")
        // y marcadores de estado/edición u horas adjuntas al final de la burbuja
        cleaned = cleaned.replace(LEADING_SENDER_PREFIX_REGEX, "").trim()
        cleaned = cleaned.replace(CHAT_STATUS_REGEX, "").trim()
        cleaned = cleaned.replace(TRAILING_TIMESTAMP_REGEX, "").trim()
        cleaned = cleaned.replace(CHAT_STATUS_REGEX, "").trim()
        cleaned = cleaned.replace(Regex("""[,:\s]+$"""), "").trim()
        return cleaned
    }

    /**
     * Checks if text is already in the target language (e.g. Greek script when target is Greek,
     * or ML Kit identified the target language with high confidence).
     */
    fun isTargetLanguage(text: String, targetLang: String, candidates: List<IdentifiedLanguage>): Boolean {
        if (targetLang == TranslateLanguage.GREEK) {
            val greekLetters = ScriptDetector.countGreek(text)
            val latinLetters = ScriptDetector.countLatin(text)
            if (greekLetters > 0 && greekLetters >= latinLetters) return true
        }
        val targetCandidate = candidates.firstOrNull { it.languageTag == targetLang }
        if (targetCandidate != null && targetCandidate.confidence >= 0.40f) {
            return true
        }
        return false
    }

    /**
     * Validates that text matches the explicitly configured source language.
     * Prevents random UI strings, other languages, or low-confidence garbage from being translated.
     */
    fun isMatchingSourceLanguage(text: String, sourceLang: String, candidates: List<IdentifiedLanguage>): Boolean {
        // Romanian specific check: diacritics, common vocabulary, or Latin script without strong conflict
        if (sourceLang == TranslateLanguage.ROMANIAN) {
            if (ScriptDetector.hasRomanianDiacritics(text)) return true

            val words = text.lowercase().split(Regex("""[^a-zăâîșț]+""")).filter { it.isNotBlank() }
            if (words.any { TranslationSenseEngine.COMMON_ROMANIAN_WORDS.contains(it) }) return true

            if (ScriptDetector.countLatin(text) >= 2) {
                val conflictingHighConfidence = candidates.firstOrNull {
                    it.languageTag != TranslateLanguage.ROMANIAN && it.languageTag != "und" && it.confidence >= 0.65f
                }
                if (conflictingHighConfidence == null) {
                    return true
                }
            }
        }

        // Check candidate languages from ML Kit
        val match = candidates.firstOrNull { it.languageTag == sourceLang }
        if (match != null && match.confidence >= 0.15f) {
            val higherConf = candidates.firstOrNull { it.languageTag != sourceLang && it.confidence > (match.confidence * 2.0f) }
            return higherConf == null
        }

        return false
    }
}
