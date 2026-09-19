package com.example.translator

import android.graphics.Rect
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Sense logic engine adhering to strict typing and pure functions.
 * Responsibilities:
 * 1. Validates source language eligibility and filters out unwanted text (English previews, URLs, outgoing messages).
 * 2. Pre-translates idiomatic expressions, holiday greetings, and slang with natural conversational equivalents.
 * 3. Applies post-translation sanity logic to repair low-accuracy statistical model outputs.
 */
object TranslationSenseEngine {

    private val URL_OR_DOMAIN_REGEX = Regex(
        """(https?://|www\.|\.com|\.org|\.net|\.ly/|bit\.ly|waze\.com|facebook\.com|instagram\.com|tiktok\.com|twitter\.com|youtube\.com|/share/)""",
        RegexOption.IGNORE_CASE
    )

    private val COMMON_ENGLISH_TOKENS = setOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
        "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
        "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
        "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "driving", "directions", "live", "traffic", "road", "conditions", "updates",
        "realtime", "waze", "fellow", "drivers", "destination", "route", "share",
        "get", "based", "today", "yesterday", "message", "type", "call", "audio"
    )

    val COMMON_ROMANIAN_WORDS = setOf(
        "la", "multi", "mulți", "ani", "un", "an", "nou", "fericit", "fericita", "fericită", "si", "și",
        "ce", "faci", "buna", "bună", "salut", "unde", "esti", "ești", "cum", "bine",
        "da", "nu", "multumesc", "mulțumesc", "mersi", "te", "iubesc", "prieten", "prietene",
        "frate", "noapte", "ziua", "seara", "drag", "draga", "dragă", "acum", "maine", "mâine",
        "azi", "ai", "fost", "am", "avut", "sunt", "este", "e", "in", "în", "de", "cu",
        "pentru", "care", "cine", "dece", "de ce", "vreau", "stiu", "știu", "pot",
        "futut", "fute", "futu", "pula", "pizda", "dracu", "dracului", "negri", "negrii",
        "ingerule", "îngerule", "frumusețe", "hai", "pa", "pup", "te pup", "om", "oameni"
    )

    /**
     * Determines whether a WhatsApp message bubble is an incoming message from the contact
     * rather than an outgoing message sent by the user.
     * In WhatsApp, incoming bubbles hug the left screen edge (left < 35% of screen width),
     * while outgoing bubbles are right-aligned and date pills are centered.
     */
    fun isIncomingMessage(rectLeft: Int, rectRight: Int, screenWidth: Int): Boolean {
        if (screenWidth <= 0) return true
        val width = rectRight - rectLeft
        val isLeftAligned = rectLeft < (screenWidth * 0.35f)
        val hasReasonableWidth = width < (screenWidth * 0.95f) && width > 20
        return isLeftAligned && hasReasonableWidth
    }

    fun isIncomingMessage(rect: Rect, screenWidth: Int): Boolean {
        return isIncomingMessage(rect.left, rect.right, screenWidth)
    }

    /**
     * Enforces strict source-language eligibility.
     * Ensures only text genuinely written in [sourceLang] is translated,
     * and strictly rejects English preview cards, Greek text, and web links.
     */
    fun isEligibleSourceText(
        text: String,
        sourceLang: String,
        targetLang: String,
        candidates: List<IdentifiedLanguage>
    ): Boolean {
        val clean = TranslationFilter.cleanMessageText(text).trim()
        if (clean.length < 2) return false

        // Discard URLs, domains, and preview parameters
        if (clean.contains(URL_OR_DOMAIN_REGEX)) {
            return false
        }

        // If target is Greek, discard any text already composed of Greek script
        if (targetLang == TranslateLanguage.GREEK) {
            val greekLetters = clean.count { (it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF') && it.isLetter() }
            val latinLetters = clean.count { it in 'a'..'z' || it in 'A'..'Z' || it in "ăâîșțĂÂÎȘȚ" }
            if (greekLetters > 0 && greekLetters >= latinLetters) {
                return false
            }
        }

        // Tokenize words for vocabulary inspection
        val words = clean.lowercase().split(Regex("""[^a-zăâîșț]+""")).filter { it.isNotBlank() }

        // Reject if English tokens dominate
        val englishTokenCount = words.count { COMMON_ENGLISH_TOKENS.contains(it) }
        val romanianTokenCount = words.count { COMMON_ROMANIAN_WORDS.contains(it) }

        if (englishTokenCount >= 2 && romanianTokenCount == 0 && !clean.any { it in "ăâîșțĂÂÎȘȚ" }) {
            return false
        }

        // Check ML Kit candidate classifications if present
        val englishCandidate = candidates.firstOrNull { it.languageTag == "en" }
        if (englishCandidate != null && englishCandidate.confidence >= 0.30f && romanianTokenCount == 0 && !clean.any { it in "ăâîșțĂÂÎȘȚ" }) {
            return false
        }

        if (sourceLang == TranslateLanguage.ROMANIAN) {
            // Definite Romanian indicators
            val hasDiacritics = clean.any { it in "ăâîșțĂÂÎȘȚ" }
            if (hasDiacritics) return true
            if (romanianTokenCount > 0) return true

            // For Latin words without diacritics, verify absence of conflicting languages
            val latinCharCount = clean.count { it in 'a'..'z' || it in 'A'..'Z' }
            if (latinCharCount >= 2) {
                val conflictingNonSource = candidates.firstOrNull {
                    it.languageTag != TranslateLanguage.ROMANIAN && it.languageTag != "und" && it.confidence >= 0.60f
                }
                if (conflictingNonSource == null && englishTokenCount < 2) {
                    return true
                }
            }
            return false
        }

        // Generic fallback for other configured source languages
        val matchingCandidate = candidates.firstOrNull { it.languageTag == sourceLang }
        return matchingCandidate != null && matchingCandidate.confidence >= 0.20f
    }

    /**
     * Resolves known idioms, holiday greetings, and slang expressions before submitting to ML Kit.
     * Prevents literal or nonsensical machine translations for cultural phrases.
     */
    fun resolveIdiomPreTranslation(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        if (sourceLang != TranslateLanguage.ROMANIAN || targetLang != TranslateLanguage.GREEK) {
            return null
        }

        val clean = TranslationFilter.cleanMessageText(text).trim()
        val normalized = clean.lowercase()
            .replace("ă", "a")
            .replace("â", "a")
            .replace("î", "i")
            .replace("ș", "s")
            .replace("ț", "t")

        // 1. Holiday & Greeting Expressions
        if (normalized.contains("la multi ani") && normalized.contains("an nou fericit")) {
            val address = extractAddressName(clean)
            return if (address != null) {
                "Χρόνια πολλά και ευτυχισμένο το νέο έτος, $address!"
            } else {
                "Χρόνια πολλά και ευτυχισμένο το νέο έτος!"
            }
        }

        if (normalized == "la multi ani" || normalized == "la multi ani!") {
            return "Χρόνια πολλά!"
        }

        if (normalized.startsWith("la multi ani,") || normalized.startsWith("la multi ani ")) {
            val address = extractAddressName(clean)
            return if (address != null) "Χρόνια πολλά, $address!" else "Χρόνια πολλά!"
        }

        if (normalized.contains("craciun fericit")) {
            return "Καλά Χριστούγεννα!"
        }

        if (normalized.contains("paste fericit")) {
            return "Καλό Πάσχα!"
        }

        // 2. Slang & Colloquial Expressions (accurate sense translation)
        if (normalized.contains("futut de negri") || normalized.contains("te-au futut negrii")) {
            val address = extractLeadingAddressName(clean)
            return if (address != null) {
                "$address, σε πήδηξαν μαύροι."
            } else {
                "Σε πήδηξαν μαύροι."
            }
        }

        if (normalized.contains("te-ai futut")) {
            return "Την πάτησες!"
        }

        if (normalized.contains("du-te dracului") || normalized.contains("du-te dracu")) {
            return "Άντε στο διάολο!"
        }

        if (normalized.contains("ce dracu") || normalized.contains("ce naiba")) {
            return "Τι στο διάολο;"
        }

        // 3. Conversational Questions
        if (normalized == "ce faci" || normalized == "ce faci?" || normalized == "ce mai faci" || normalized == "ce mai faci?") {
            return "Τι κάνεις;"
        }

        if (normalized == "ce faci prietene" || normalized == "ce faci prietene?") {
            return "Τι κάνεις φίλε;"
        }

        if (normalized == "unde esti" || normalized == "unde esti?") {
            return "Πού είσαι;"
        }

        return null
    }

    /**
     * Post-processing sense logic to correct known statistical and grammatical errors
     * produced by ML Kit's generic offline models.
     */
    fun applyPostTranslationSenseLogic(
        originalText: String,
        translatedText: String,
        sourceLang: String,
        targetLang: String
    ): String {
        if (sourceLang != TranslateLanguage.ROMANIAN || targetLang != TranslateLanguage.GREEK) {
            return translatedText
        }

        var result = translatedText.trim()
        val origNorm = originalText.lowercase()
            .replace("ă", "a")
            .replace("â", "a")
            .replace("î", "i")
            .replace("ș", "s")
            .replace("ț", "t")

        // Correction 1: ML Kit mistranslating "La mulți ani" as "Ευτυχισμένα γενέθλια" (Happy Birthday)
        // when the context is New Year or general celebration
        if (origNorm.contains("an nou") || !origNorm.contains("zi de nastere")) {
            result = result.replace(Regex("""(Ευτυχισμένα|Χαρούμενα)\s+γενέθλια""", RegexOption.IGNORE_CASE), "Χρόνια πολλά")
            result = result.replace(Regex("""και ένα ευτυχισμένο νέο έτος""", RegexOption.IGNORE_CASE), "και ευτυχισμένο το νέο έτος")
        }

        // Correction 2: ML Kit literal translation of "negri" as "μαύρο χρώμα" (black color)
        if (origNorm.contains("negri")) {
            result = result.replace("με μαύρο χρώμα", "από μαύρους")
            result = result.replace("μαύρο χρώμα", "μαύρους")
        }

        // Correction 3: Grammatical collision "είσαι πατήσαμε" -> "σε πήδηξαν" / "σε πάτησαν"
        if (result.contains("είσαι πατήσαμε", ignoreCase = true)) {
            result = result.replace(Regex("""είσαι\s+πατήσαμε""", RegexOption.IGNORE_CASE), "σε πήδηξαν")
        }

        // Correction 4: Clean up awkward Greek nominative name articles in address contexts
        result = result.replace(Regex(""",\s*ο\s+([ΆΈΉΊΌΎΏΑ-Ωα-ωάέήίόύώ]+)"""), ", $1")
        result = result.replace(Regex("""^ο\s+([ΆΈΉΊΌΎΏΑ-Ωα-ωάέήίόύώ]+),"""), "$1,")
        result = result.replace(Regex("""\bΆγγελος\b(?=[,!]|\s*$)"""), "Άγγελε")

        // Correction 5: Greek question mark normalization (';')
        if (origNorm.endsWith("?") && result.endsWith("?")) {
            result = result.substring(0, result.length - 1) + ";"
        }

        return result
    }

    private fun extractAddressName(text: String): String? {
        val parts = text.split(Regex("[,:!\\n]+")).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val last = parts.last()
            val cleanName = last.replace(Regex("""[.!?:;]+$"""), "").trim()
            if (cleanName.length in 2..20 && !cleanName.contains(" ")) {
                return formatGreekVocativeName(cleanName)
            }
        }
        return null
    }

    private fun extractLeadingAddressName(text: String): String? {
        val parts = text.split(Regex("[,:!\\n]+")).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val first = parts.first()
            val cleanName = first.replace(Regex("""[.!?:;]+$"""), "").trim()
            if (cleanName.length in 2..20 && !cleanName.contains(" ")) {
                return formatGreekVocativeName(cleanName)
            }
        }
        return null
    }

    private fun formatGreekVocativeName(name: String): String {
        return when (name.lowercase()) {
            "angel", "angelos", "άγγελος" -> "Άγγελε"
            "giannis", "ioannis", "γιάννης" -> "Γιάννη"
            "george", "georgios", "γιώργος" -> "Γιώργο"
            "nikos", "nikolaos", "νίκος" -> "Νίκο"
            "costas", "kostas", "κώστας" -> "Κώστα"
            "dimitris", "dimitrios", "δημήτρης" -> "Δημήτρη"
            else -> name
        }
    }
}
