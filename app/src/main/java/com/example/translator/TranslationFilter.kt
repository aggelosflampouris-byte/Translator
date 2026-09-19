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
    private val WHATSAPP_STATUS_REGEX = Regex(
        """[,.\s\n]+(delivered|read|sent|pending|unread|παραδόθηκε|διαβάστηκε|στάλθηκε|σε εκκρεμότητα|trimis|citit|citiți|cititi|livrat|în așteptare|[✓✔\u2713\u2714\u2705]+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val STANDALONE_STATUS_REGEX = Regex(
        """^([,.:\s\n]*(\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?)?[,.:\s\n]*(delivered|read|sent|pending|unread|παραδόθηκε|παραδοθηκε|διαβάστηκε|διαβαστηκε|στάλθηκε|σταλθηκε|σε εκκρεμότητα|σε εκκρεμοτητα|εκκρεμεί|εκκρεμει|trimis|citit|citiți|cititi|livrat|în așteptare|in asteptare|[✓✔\u2713\u2714\u2705]+)[,.:\s\n]*)+$""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_TIMESTAMP_REGEX = Regex("""[\s\n]+\d{1,2}:\d{2}(\s*(μ\.?μ\.?|π\.?μ\.?|am|pm))?(\s*[✓✔\u2713\u2714\u2705]+)?\s*$""", RegexOption.IGNORE_CASE)
    private val PERCENTAGE_REGEX = Regex("""^\d{1,3}%$""")

    private val COMMON_UI_TOKENS = setOf(
        "μήνυμα", "type a message", "message", "search", "αναζήτηση",
        "χθες", "σήμερα", "yesterday", "today", "online", "συνδέθηκε",
        "συνομιλίες", "ενημερώσεις", "κλήσεις", "chats", "updates", "calls"
    )

    private val MESSAGE_STATUS_TOKENS = setOf(
        "διαβάστηκε", "διαβαστηκε", "παραδόθηκε", "παραδοθηκε", "στάλθηκε", "σταλθηκε",
        "σε εκκρεμότητα", "σε εκκρεμοτητα", "εκκρεμεί", "εκκρεμει", "μη αναγνωσμένο", "μη αναγνωσμενο",
        "read", "delivered", "sent", "pending", "unread",
        "citit", "citiți", "cititi", "livrat", "trimis", "în așteptare", "in asteptare",
        "leído", "leido", "entregado", "enviado", "pendiente"
    )

    /**
     * Determines whether a recognized text snippet is a candidate for translation
     * or should be discarded (e.g. URLs, timestamps, UI chrome, battery, target-language script).
     */
    fun shouldTranslate(text: String, configuredTarget: String): Boolean {
        val cleaned = cleanMessageText(text)
        val trimmed = cleaned.trim()
        if (trimmed.length < 2) return false

        // Must contain at least one letter
        if (!trimmed.any { it.isLetter() }) return false

        // Filter out URLs, schemes, domain extensions, and URL path fragments
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("www.", ignoreCase = true) ||
            trimmed.contains("://") ||
            trimmed.contains(".com", ignoreCase = true) ||
            trimmed.contains(".org", ignoreCase = true) ||
            trimmed.contains(".net", ignoreCase = true) ||
            trimmed.contains(".ly/", ignoreCase = true) ||
            trimmed.contains("bit.ly", ignoreCase = true) ||
            trimmed.contains("waze.com", ignoreCase = true) ||
            trimmed.contains("facebook.com", ignoreCase = true) ||
            (trimmed.startsWith("/") && !trimmed.contains(" ")) ||
            (trimmed.matches(URL_PATH_REGEX) && (trimmed.contains("/") || (trimmed.length > 8 && !trimmed.contains(" "))))) {
            return false
        }

        // Filter out pure timestamps (e.g. "5:16 μ.μ.", "12:55", "3:23 pm", "7:27")
        if (trimmed.matches(TIMESTAMP_REGEX)) {
            return false
        }

        // Filter out common UI labels, status, and navigation markers
        val lower = trimmed.lowercase()
        if (COMMON_UI_TOKENS.contains(lower) || MESSAGE_STATUS_TOKENS.contains(lower)) {
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

        // If target is Greek, skip any block that is primarily Greek text
        if (configuredTarget == TranslateLanguage.GREEK) {
            val greekCharCount = trimmed.count { (it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF') && it.isLetter() }
            val latinCharCount = trimmed.count { it in 'a'..'z' || it in 'A'..'Z' || it in "ăâîșțĂÂÎȘȚ" }
            if (greekCharCount > 0 && greekCharCount >= latinCharCount) {
                return false
            }
        }

        return true
    }

    /**
     * Cleans OCR- or Accessibility-detected text by stripping trailing timestamps
     * and WhatsApp delivery status markers commonly grouped into chat bubble metadata.
     */
    fun cleanMessageText(text: String): String {
        var cleaned = text.trim()
        // Strip trailing WhatsApp status markers (e.g. ", Delivered", ", Παραδόθηκε")
        cleaned = cleaned.replace(WHATSAPP_STATUS_REGEX, "").trim()
        // Strip trailing timestamps (e.g. ", 7:27 μ.μ.")
        cleaned = cleaned.replace(TRAILING_TIMESTAMP_REGEX, "").trim()
        // Repeat status strip in case status was after timestamp or vice-versa
        cleaned = cleaned.replace(WHATSAPP_STATUS_REGEX, "").trim()
        // Strip trailing commas, periods or colons left over from timestamp removal
        cleaned = cleaned.replace(Regex("""[,:\s]+$"""), "").trim()
        return cleaned
    }

    /**
     * Checks if text is already in the target language (e.g. Greek script when target is Greek,
     * or ML Kit identified the target language with high confidence).
     */
    fun isTargetLanguage(text: String, targetLang: String, candidates: List<IdentifiedLanguage>): Boolean {
        if (targetLang == TranslateLanguage.GREEK) {
            val greekLetters = text.count { (it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF') && it.isLetter() }
            val latinLetters = text.count { it in 'a'..'z' || it in 'A'..'Z' || it in "ăâîșțĂÂÎȘȚ" }
            if (greekLetters > 0 && greekLetters >= latinLetters) return true
        }
        val targetCandidate = candidates.firstOrNull { it.languageTag == targetLang }
        if (targetCandidate != null && targetCandidate.confidence >= 0.40f) {
            return true
        }
        return false
    }

    private val COMMON_ROMANIAN_WORDS = setOf(
        "la", "multi", "ani", "un", "an", "nou", "fericit", "si",
        "ce", "faci", "buna", "salut", "unde", "esti", "cum", "bine",
        "da", "nu", "multumesc", "mersi", "te", "iubesc", "prieten",
        "noapte", "ziua", "seara", "drag", "draga", "acum", "maine", "azi"
    )

    /**
     * Validates that text matches the explicitly configured source language.
     * Prevents random UI strings, other languages, or low-confidence garbage from being translated.
     */
    fun isMatchingSourceLanguage(text: String, sourceLang: String, candidates: List<IdentifiedLanguage>): Boolean {
        // Romanian specific check: diacritics, common vocabulary, or Latin script without strong conflict
        if (sourceLang == TranslateLanguage.ROMANIAN) {
            val hasRomanianDiacritics = text.any { it in "ăâîșțĂÂÎȘȚ" }
            if (hasRomanianDiacritics) return true

            val words = text.lowercase().split(Regex("""[^a-zăâîșț]+""")).filter { it.isNotBlank() }
            if (words.any { COMMON_ROMANIAN_WORDS.contains(it) }) return true

            val latinCharCount = text.count { it in 'a'..'z' || it in 'A'..'Z' || it in "ăâîșțĂÂÎȘȚ" }
            if (latinCharCount >= 2) {
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
