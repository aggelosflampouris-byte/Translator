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
        "ingerule", "îngerule", "frumusețe", "hai", "pa", "pup", "te pup", "om", "oameni",
        "totul", "tot", "mesaj", "mesaje", "mesajul", "scuze", "noroc", "sanatate", "sănătate",
        "vorbim", "sigur", "desigur", "bineinteles", "bineînțeles", "perfect", "bravo",
        "trebuie", "vino", "stai", "asteapta", "așteaptă", "poate", "cred"
    )

    private val GENERIC_ROMANCE_PREPOSITIONS = setOf("de", "in", "e", "a", "la")

    /**
     * Determines whether a WhatsApp message bubble is an incoming message from the contact
     * rather than an outgoing message sent by the user.
     * In WhatsApp, incoming bubbles hug the left screen edge (left < 35% of screen width),
     * while outgoing bubbles are right-aligned and date pills are centered.
     */
    fun isIncomingMessage(rectLeft: Int, rectRight: Int, screenWidth: Int): Boolean {
        if (screenWidth <= 0) return true
        val isLeftAligned = rectLeft < (screenWidth * 0.22f)
        val isNotRightHugging = rectRight < (screenWidth * 0.88f)
        val width = rectRight - rectLeft
        val hasReasonableWidth = width < (screenWidth * 0.95f) && width > 20
        return isLeftAligned && isNotRightHugging && hasReasonableWidth
    }

    fun isIncomingMessage(rect: Rect, screenWidth: Int): Boolean {
        return isIncomingMessage(rect.left, rect.right, screenWidth)
    }

    /**
     * Determines whether a recognized rectangle corresponds to a chat message bubble
     * (either incoming on the left or sent outgoing on the right), while filtering out
     * centered date pills (e.g. "Σήμερα", "Πέμπτη") and full-width background chrome.
     */
    fun isMessageBubble(rectLeft: Int, rectRight: Int, screenWidth: Int): Boolean {
        if (screenWidth <= 0) return true
        val width = rectRight - rectLeft
        val hasReasonableWidth = width < (screenWidth * 0.95f) && width > 20
        if (!hasReasonableWidth) return false

        // Incoming message: hugs left margin (< 25% of screen width)
        val isIncoming = rectLeft < (screenWidth * 0.25f)
        // Outgoing message: hugs right margin (> 78% of screen width)
        val isOutgoing = rectRight > (screenWidth * 0.78f)

        return isIncoming || isOutgoing
    }

    fun isMessageBubble(rect: Rect, screenWidth: Int): Boolean {
        return isMessageBubble(rect.left, rect.right, screenWidth)
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
            // 1. Definite Romanian diacritics
            if (clean.any { it in "ăâîșțĂÂÎȘȚ" }) return true

            // 2. Specific Romanian vocabulary (excluding generic Romance prepositions like "de", "in", "e", "a", "la")
            val specificRomanianTokens = words.count { COMMON_ROMANIAN_WORDS.contains(it) && !GENERIC_ROMANCE_PREPOSITIONS.contains(it) }
            if (specificRomanianTokens > 0) {
                val strongNonRomanian = candidates.firstOrNull { (it.languageTag == "en" || it.languageTag == "la") && it.confidence >= 0.70f }
                if (strongNonRomanian == null && englishTokenCount < 2) {
                    return true
                }
            }

            // 3. Reject generic Romance prepositions alone (e.g. "de" in "Clamavi de Profundis")
            val hasOnlyGenericPrepositions = romanianTokenCount > 0 && specificRomanianTokens == 0
            if (hasOnlyGenericPrepositions) {
                return false
            }

            // 4. Reject known non-Romanian languages identified by ML Kit (Latin, English, Spanish, Italian, etc.)
            val nonRomanianCandidate = candidates.firstOrNull {
                it.languageTag in setOf("la", "en", "es", "it", "fr", "pt", "de") && it.confidence >= 0.35f
            }
            if (nonRomanianCandidate != null) {
                return false
            }

            // 5. ML Kit explicit Romanian identification
            val roCandidate = candidates.firstOrNull { it.languageTag == "ro" || it.languageTag == "ron" }
            if (roCandidate != null && roCandidate.confidence >= 0.25f) {
                return true
            }

            // 6. Multi-word sentences in Latin script without conflicting languages
            val latinLetters = clean.count { it in 'a'..'z' || it in 'A'..'Z' }
            if (latinLetters >= 4 && words.size >= 2) {
                val conflictingLang = candidates.firstOrNull {
                    it.languageTag != TranslateLanguage.ROMANIAN && it.languageTag != "und" && it.confidence >= 0.50f
                }
                if (conflictingLang == null && englishTokenCount < 2 && candidates.none { it.languageTag == "la" && it.confidence >= 0.25f }) {
                    return true
                }
            }

            return false
        }

        if (sourceLang == TranslateLanguage.GREEK) {
            val greekLetters = clean.count { (it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF') && it.isLetter() }
            if (greekLetters > 0) return true
        }

        // Generic fallback for other configured source languages
        val matchingCandidate = candidates.firstOrNull { it.languageTag == sourceLang }
        return matchingCandidate != null && matchingCandidate.confidence >= 0.20f
    }

    /**
     * Resolves known idioms, holiday greetings, and slang expressions before submitting to ML Kit.
     * Prevents literal or nonsensical machine translations for cultural phrases.
     */
    /**
     * Resolves known idioms, holiday greetings, and conversational expressions before submitting to ML Kit.
     * Prevents literal or nonsensical machine translations for cultural phrases.
     */
    fun resolveIdiomPreTranslation(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        val trimmed = text.trim()
        // If text contains multiple lines, it must never be collapsed into a single idiom/greeting
        if (trimmed.contains("\n")) {
            return null
        }
        val words = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
        // Full pre-translation idioms and greetings never exceed 12 words (e.g. holiday greetings)
        if (words.size > 12) {
            return null
        }
        if (sourceLang == TranslateLanguage.ROMANIAN && targetLang == TranslateLanguage.GREEK) {
            return resolveRomanianToGreekIdioms(trimmed)
        }
        if (sourceLang == TranslateLanguage.GREEK && targetLang == TranslateLanguage.ROMANIAN) {
            return resolveGreekToRomanianIdioms(trimmed)
        }
        return null
    }

    private fun isRomanianGreetingMatch(normalized: String, keyword: String): Boolean {
        if (!normalized.contains(keyword)) return false
        val words = normalized.replace(Regex("""[;?!,.]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        if (words.size > 5) return false
        val allowedWords = setOf(
            "meu", "prieten", "prietene", "prietenul", "frate", "tuturor", "la", "toti", "va",
            "giannis", "ioan", "andrei", "alex", "marian", "elena", "maria", "si"
        )
        val keywordWords = keyword.split(" ")
        return words.all { keywordWords.contains(it) || allowedWords.contains(it) }
    }

    private fun isGreekGreetingMatch(normalized: String, keyword: String): Boolean {
        if (!normalized.contains(keyword)) return false
        val words = normalized.replace(Regex("""[;?!,.]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        if (words.size > 5) return false
        val allowedWords = setOf(
            "μου", "φιλε", "αδερφε", "παιδια", "σας", "ολους", "σε", "και", "καλη", "καλο",
            "γιαννη", "γιαννης", "αγγελε", "αγγελος", "γιωργο", "γιωργος",
            "νικο", "νικος", "κωστα", "κωστας", "δημητρη", "δημητρης", "μιχαλη", "μιχαλης",
            "μαρια", "ελενη", "ρε"
        )
        val keywordWords = keyword.split(" ")
        return words.all { keywordWords.contains(it) || allowedWords.contains(it) }
    }

    private fun resolveRomanianToGreekIdioms(text: String): String? {
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

        // 3. Conversational Questions & Statements
        if (normalized.contains("cum esti") || normalized.contains("ce faci") || normalized.contains("ce mai faci")) {
            val leadingAddress = extractLeadingAddressName(clean)
            val hasFriend = normalized.contains("prieten") || normalized.contains("prietene") || normalized.contains("prietenul meu")
            val hasBrother = normalized.contains("frate")
            val name = extractRomanianName(clean)

            val question = if (normalized.contains("cum esti")) "Πώς είσαι" else if (normalized.contains("ce mai faci")) "Τι γίνεται" else "Τι κάνεις"

            if (leadingAddress != null) {
                return "$leadingAddress, ${question.lowercase()};"
            }

            val salutation = when {
                hasFriend && name != null -> "φίλε μου $name"
                hasFriend -> "φίλε μου"
                hasBrother && name != null -> "αδερφέ μου $name"
                hasBrother -> "αδερφέ μου"
                name != null -> name
                else -> null
            }

            return if (salutation != null) "$question, $salutation;" else "$question;"
        }

        // 4. Greetings
        if (isRomanianGreetingMatch(normalized, "buna dimineata")) {
            val name = extractRomanianName(clean)
            return if (name != null) "Καλημέρα, $name!" else "Καλημέρα!"
        }
        if (isRomanianGreetingMatch(normalized, "buna seara")) {
            val name = extractRomanianName(clean)
            return if (name != null) "Καλησπέρα, $name!" else "Καλησπέρα!"
        }
        if (isRomanianGreetingMatch(normalized, "noapte buna")) {
            val name = extractRomanianName(clean)
            return if (name != null) "Καληνύχτα, $name!" else "Καληνύχτα!"
        }
        if (isRomanianGreetingMatch(normalized, "buna ziua")) {
            val name = extractRomanianName(clean)
            return if (name != null) "Γεια σας, $name!" else "Γεια σας!"
        }
        if (isRomanianGreetingMatch(normalized, "salut") || isRomanianGreetingMatch(normalized, "buna")) {
            val name = extractRomanianName(clean)
            return if (name != null) "Γεια σου, $name!" else "Γεια σου!"
        }

        if (normalized.contains("totul e bine") || normalized.contains("totul este bine")) {
            val address = extractLeadingAddressName(clean) ?: extractAddressName(clean)
            return if (address != null) "$address, όλα είναι καλά." else "Όλα είναι καλά."
        }

        if (normalized == "mesaj" || normalized == "mesaj." || normalized == "mesaj!") {
            return "Μήνυμα"
        }

        if (normalized.contains("scuze") || normalized.contains("imi cer scuze")) {
            return "Συγγνώμη!"
        }

        if (normalized.contains("multumesc") || normalized.contains("mersi")) {
            return "Ευχαριστώ!"
        }

        if (normalized.contains("cu placere")) {
            return "Παρακαλώ!"
        }

        return null
    }

    private fun resolveGreekToRomanianIdioms(text: String): String? {
        val clean = TranslationFilter.cleanMessageText(text).trim()
        val normalized = normalizeGreek(clean)

        val name = extractGreekName(clean)

        // 0. Vulgar, Curses, Insults & Slang Expressions
        if (normalized.contains("γαμησου") || normalized.contains("γαμηθεις") ||
            normalized.contains("γαμιεσαι") || normalized.contains("γαμιουνται") ||
            normalized.contains("αντε γαμησου") || normalized.contains("αντε και γαμησου") ||
            normalized.contains("να πας να γαμηθεις") || normalized.contains("αντε στο διαολο") ||
            normalized.contains("στο διαολο") || normalized.contains("αντε χεσου")) {
            return if (name != null) "Du-te dracului, $name!" else "Du-te dracului!"
        }

        if (normalized.contains("μαλακα") || normalized.contains("μαλακας") || normalized.contains("μαλακες")) {
            if (normalized.contains("αντε ρε") || normalized.contains("αντε")) {
                return if (name != null) "Hai mă, prostule, $name!" else "Hai mă, prostule!"
            }
            if (normalized.contains("εισαι")) {
                return if (name != null) "Ești prost, $name!" else "Ești prost!"
            }
            return if (name != null) "Prostule, $name!" else "Prostule!"
        }

        if (normalized.contains("γαμωτο") || normalized.contains("γαμω το")) {
            return "La dracu!"
        }

        if (normalized.contains("την πατησες") || normalized.contains("την πατησαμε")) {
            return if (name != null) "Te-ai futut, $name!" else "Te-ai futut!"
        }

        if (normalized.contains("σε πηδηξαν") || normalized.contains("σε πηδηξαν μαυροι")) {
            return if (name != null) "Te-au futut negrii, $name!" else "Te-au futut negrii!"
        }

        if (normalized.contains("τι στο διαολο") || normalized.contains("τι στο πουτσο")) {
            return "Ce dracu?"
        }

        if (normalized.contains("δεν γαμιεται")) {
            return "Dă-o dracului!"
        }

        if (normalized.contains("χεστηκα") || normalized.contains("στα αρχιδια μου")) {
            return "Mă doare-n cot!"
        }

        if (normalized.contains("παρατα με") || normalized.contains("ασε με ησυχο")) {
            return if (name != null) "Lasă-mă în pace, $name!" else "Lasă-mă în pace!"
        }

        // 1. "Πως εισαι φιλε μου Γιαννη", "Τι κανεις φιλε μου", etc.
        if (normalized.contains("πως εισαι") || normalized.contains("τι κανεις") ||
            normalized.contains("τι γινεται") || normalized.contains("πως παει")) {
            val hasFriend = normalized.contains("φιλε μου") || normalized.contains("φιλε")
            val hasBrother = normalized.contains("αδερφε μου") || normalized.contains("αδερφε")

            val salutation = when {
                hasFriend && name != null -> "prietene $name"
                hasFriend -> "prietene"
                hasBrother && name != null -> "frate $name"
                hasBrother -> "frate"
                name != null -> name
                else -> null
            }

            val question = when {
                normalized.contains("πως εισαι") -> "Cum ești"
                normalized.contains("πως παει") -> "Cum merge"
                normalized.contains("τι γινεται") -> "Ce mai faci"
                else -> "Ce faci"
            }
            return if (salutation != null) "$question, $salutation?" else "$question?"
        }

        // 2. Greetings
        if (isGreekGreetingMatch(normalized, "γεια σου")) {
            return if (name != null) "Salut, $name!" else "Salut!"
        }
        if (isGreekGreetingMatch(normalized, "γεια σας")) {
            return if (name != null) "Bună ziua, $name!" else "Bună ziua!"
        }
        if (isGreekGreetingMatch(normalized, "καλημερα")) {
            return if (name != null) "Bună dimineața, $name!" else "Bună dimineața!"
        }
        if (isGreekGreetingMatch(normalized, "καλησπερα")) {
            return if (name != null) "Bună seara, $name!" else "Bună seara!"
        }
        if (isGreekGreetingMatch(normalized, "καληνυχτα")) {
            return if (name != null) "Noapte bună, $name!" else "Noapte bună!"
        }

        // 3. Holiday greetings
        if (normalized.contains("χρονια πολλα") && (normalized.contains("νεο ετος") || normalized.contains("καλη χρονια") || normalized.contains("ευτυχισμενο"))) {
            return if (name != null) "La mulți ani și un An Nou fericit, $name!" else "La mulți ani și un An Nou fericit!"
        }
        if (normalized == "χρονια πολλα" || normalized == "χρονια πολλα!" || normalized == "χρονια πολλα;") {
            return if (name != null) "La mulți ani, $name!" else "La mulți ani!"
        }

        // 4. Status questions / statements
        if (normalized == "ολα καλα" || normalized == "ολα καλα." || normalized == "ολα καλα!") {
            return "Totul e bine."
        }
        if (normalized == "ολα καλα;" || normalized == "ολα καλα?") {
            return "Totul e bine?"
        }
        if (normalized.contains("που εισαι")) {
            return if (name != null) "Unde ești, $name?" else "Unde ești?"
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
        if (sourceLang == TranslateLanguage.ROMANIAN && targetLang == TranslateLanguage.GREEK) {
            var result = translatedText.trim()
            val origNorm = originalText.lowercase()
                .replace("ă", "a")
                .replace("â", "a")
                .replace("î", "i")
                .replace("ș", "s")
                .replace("ț", "t")

            // Correction 1: ML Kit mistranslating "La mulți ani" as "Ευτυχισμένα γενέθλια" (Happy Birthday)
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

            // Correction 5: Vocative address "ο φίλος μου" -> "φίλε μου"
            result = result.replace(Regex("""\bο\s+φίλος\s+μου\b""", RegexOption.IGNORE_CASE), "φίλε μου")
            result = result.replace(Regex("""\bφίλος\s+μου\b""", RegexOption.IGNORE_CASE), "φίλε μου")

            // Correction 6: Vocative Greek name endings in address context
            result = result.replace(Regex("""\bΓιάννης\b(?=[,!;?:]|\s*$)"""), "Γιάννη")
            result = result.replace(Regex("""\bΆγγελος\b(?=[,!;?:]|\s*$)"""), "Άγγελε")
            result = result.replace(Regex("""\bΓιώργος\b(?=[,!;?:]|\s*$)"""), "Γιώργο")
            result = result.replace(Regex("""\bΝίκος\b(?=[,!;?:]|\s*$)"""), "Νίκο")
            result = result.replace(Regex("""\bΚώστας\b(?=[,!;?:]|\s*$)"""), "Κώστα")
            result = result.replace(Regex("""\bΔημήτρης\b(?=[,!;?:]|\s*$)"""), "Δημήτρη")

            // Correction 7: Greek question mark normalization (';')
            if (origNorm.endsWith("?") && result.endsWith("?")) {
                result = result.substring(0, result.length - 1) + ";"
            }

            return result
        }

        if (sourceLang == TranslateLanguage.GREEK && targetLang == TranslateLanguage.ROMANIAN) {
            var result = translatedText.trim()
            val origNorm = normalizeGreek(originalText.trim())
            val name = extractGreekName(originalText)

            // Fix ML Kit dropping Greek curses / producing "Ante [name]"
            if (origNorm.contains("γαμησου") || origNorm.contains("γαμηθεις") ||
                origNorm.contains("γαμιεσαι") || origNorm.contains("γαμιουνται") ||
                origNorm.contains("αντε γαμησου")) {
                return if (name != null) "Du-te dracului, $name!" else "Du-te dracului!"
            }
            if (origNorm.contains("μαλακα") || origNorm.contains("μαλακας")) {
                if (name != null) return "Hai mă, prostule, $name!"
                return "Prostule!"
            }

            // Fix ML Kit mistranslating Greek "Άντε" as Italian "Ante"
            if (result.startsWith("Ante ", ignoreCase = true)) {
                result = result.replaceFirst(Regex("""^Ante\s+""", RegexOption.IGNORE_CASE), "Hai ")
            }
            result = result.replace(Regex("""\bAnte\b""", RegexOption.IGNORE_CASE), "Hai")

            // Fix "prietenul meu" to vocative "prietene" in direct address contexts
            result = result.replace(Regex("""\b(esti|ești)\s+prietenul\s+meu\b""", RegexOption.IGNORE_CASE), "ești, prietene")
            result = result.replace(Regex("""\bprietenul\s+meu\s+([A-Z][a-zA-Z]+)""", RegexOption.IGNORE_CASE), "prietene $1")
            result = result.replace(Regex("""\bprietenul\s+meu\b""", RegexOption.IGNORE_CASE), "prietene")

            // Question mark normalization for Romanian ('?' instead of Greek ';')
            if (originalText.trim().endsWith(";") || originalText.trim().endsWith("?") ||
                result.startsWith("Cum ", ignoreCase = true) ||
                result.startsWith("Ce ", ignoreCase = true) ||
                result.startsWith("Unde ", ignoreCase = true)) {
                if (!result.endsWith("?")) {
                    result = result.replace(Regex("""[;.]+$"""), "") + "?"
                }
            }
            return result
        }

        return translatedText
    }

    fun normalizeGreek(text: String): String {
        return text.lowercase()
            .replace("ά", "α").replace("έ", "ε").replace("ή", "η")
            .replace("ί", "ι").replace("ό", "ο").replace("ύ", "υ").replace("ώ", "ω")
            .replace("ΐ", "ι").replace("ΰ", "υ")
    }

    private val GREEK_STOPWORDS = setOf(
        "πως", "εισαι", "τι", "κανεις", "φιλε", "μου", "αδερφε", "ολα", "καλα", "που",
        "καλημερα", "καλησπερα", "καληνυχτα", "και", "να", "το", "σε", "με", "για",
        "αντε", "ρε", "μαλακα", "μαλακας", "γαμησου", "γαμηθεις", "γαμιεσαι", "γαμιουνται", "διαολο", "χεσου",
        "πολλα", "χρονια", "ευτυχισμενο", "νεο", "ετος", "τωρα", "εδω", "εκει",
        "αυριο", "σημερα", "χθες", "ελα", "μπραβο", "ευχαριστω", "παρακαλω", "ναι", "οχι",
        "συγνωμη", "συγγνωμη", "γεια", "σου", "σας", "εγω", "εσυ", "αυτος", "αυτη", "αυτο"
    )

    private fun extractGreekName(text: String): String? {
        val words = text.replace(Regex("""[;?!,.]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        // 1. Match known Greek names anywhere in the text
        for (word in words) {
            val norm = normalizeGreek(word)
            val known = when (norm) {
                "γιαννη", "γιαννης", "γιαννηs" -> "Giannis"
                "αγγελε", "αγγελος", "αγγελοs" -> "Angelos"
                "γιωργο", "γιωργος", "γιωργοs" -> "George"
                "νικο", "νικος", "νικοs" -> "Nikos"
                "κωστα", "κωστας", "κωσταs" -> "Costas"
                "δημητρη", "δημητρης", "δημητρηs" -> "Dimitris"
                "μιχαλη", "μιχαλης", "μιχαληs" -> "Mihai"
                "μαρια" -> "Maria"
                "ελενη" -> "Elena"
                else -> null
            }
            if (known != null) return known
        }

        // 2. Fallback: last word in sentence if capitalized and not a stopword
        val lastWord = words.lastOrNull()
        if (lastWord != null && lastWord.length >= 3 && lastWord.first().isUpperCase()) {
            val norm = normalizeGreek(lastWord)
            if (!GREEK_STOPWORDS.contains(norm)) {
                return lastWord
            }
        }

        // 3. Fallback: first word ONLY if explicitly separated by a comma (e.g., "Γιάννη, πώς είσαι;")
        val firstWord = words.firstOrNull()
        if (firstWord != null && text.trim().startsWith("$firstWord,", ignoreCase = true) && firstWord.length >= 3) {
            val norm = normalizeGreek(firstWord)
            if (!GREEK_STOPWORDS.contains(norm)) {
                return firstWord
            }
        }

        return null
    }

    private fun extractAddressName(text: String): String? {
        val parts = text.split(Regex("[,:!\\n]+")).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val last = parts.last()
            val cleanName = last.replace(Regex("""[.!?:;]+$"""), "").trim()
            val norm = cleanName.lowercase().replace("ă", "a").replace("â", "a").replace("î", "i").replace("ș", "s").replace("ț", "t")
            if (norm in setOf("ingerule", "ingerul", "angel", "angelos")) {
                return formatGreekVocativeName(cleanName)
            }
            if (cleanName.length in 2..20 && !cleanName.contains(" ") && !COMMON_ROMANIAN_WORDS.contains(norm)) {
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
            val norm = cleanName.lowercase().replace("ă", "a").replace("â", "a").replace("î", "i").replace("ș", "s").replace("ț", "t")
            if (norm in setOf("ingerule", "ingerul", "angel", "angelos")) {
                return formatGreekVocativeName(cleanName)
            }
            if (cleanName.length in 2..20 && !cleanName.contains(" ") && !COMMON_ROMANIAN_WORDS.contains(norm)) {
                return formatGreekVocativeName(cleanName)
            }
        }
        return null
    }

    private fun extractRomanianName(text: String): String? {
        val leading = extractLeadingAddressName(text)
        if (leading != null) return leading

        val trailing = extractAddressName(text)
        if (trailing != null) return trailing

        val words = text.replace(Regex("""[;?!,.]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        for (word in words) {
            val norm = word.lowercase()
                .replace("ă", "a").replace("â", "a").replace("î", "i")
                .replace("ș", "s").replace("ț", "t")
            val known = when (norm) {
                "giannis", "ioannis", "γιάννης", "γιαννη" -> "Γιάννη"
                "angel", "angelos", "ingerule", "ingerul", "άγγελος" -> "Άγγελε"
                "george", "georgios", "γιώργος" -> "Γιώργο"
                "nikos", "nikolaos", "νίκος" -> "Νίκο"
                "costas", "kostas", "κώστας" -> "Κώστα"
                "dimitris", "dimitrios", "δημήτρης" -> "Δημήτρη"
                "mihai", "mihalis", "μιχάλης" -> "Μιχάλη"
                "maria", "μαρία" -> "Μαρία"
                "elena", "έλενα" -> "Έλενα"
                else -> null
            }
            if (known != null) return known
        }

        val lastWord = words.lastOrNull()
        if (lastWord != null && lastWord.length >= 3 && lastWord.first().isUpperCase()) {
            val normLast = lastWord.lowercase()
            if (!COMMON_ROMANIAN_WORDS.contains(normLast)) {
                return formatGreekVocativeName(lastWord)
            }
        }
        return null
    }

    private fun formatGreekVocativeName(name: String): String {
        return when (name.lowercase().replace("î", "i").replace("ă", "a").replace("ș", "s").replace("ț", "t")) {
            "angel", "angelos", "άγγελος" -> "Άγγελε"
            "ingerule", "ingerul" -> "Άγγελε"
            "giannis", "ioannis", "γιάννης" -> "Γιάννη"
            "george", "georgios", "γιώργος" -> "Γιώργο"
            "nikos", "nikolaos", "νίκος" -> "Νίκο"
            "costas", "kostas", "κώστας" -> "Κώστα"
            "dimitris", "dimitrios", "δημήτρης" -> "Δημήτρη"
            "mihai", "mihalis", "μιχάλης" -> "Μιχάλη"
            "maria", "μαρία" -> "Μαρία"
            "elena", "έλενα" -> "Έλενα"
            else -> name
        }
    }
}
