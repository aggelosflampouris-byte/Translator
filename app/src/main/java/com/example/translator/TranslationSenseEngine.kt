package com.example.translator

import android.graphics.Rect
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Motor determinista de elegibilidad de idioma, modismos exactos de saludo y normalización gramatical ligera.
 *
 * Por qué: Resuelve únicamente saludos breves exactos (≤ 4 palabras) y ajustes gramaticales universales
 * (signos de interrogación griegos `;`, vocativos directos y "La mulți ani"), sin sobrescribir frases
 * completas ni alterar el significado de mensajes largos.
 */
object TranslationSenseEngine {

    private const val MAX_SHORT_IDIOM_WORDS = 4

    private val COMMON_ENGLISH_TOKENS = setOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
        "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
        "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
        "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "driving", "directions", "live", "traffic", "road", "conditions", "updates",
        "realtime", "waze", "fellow", "drivers", "destination", "route", "share",
        "get", "based", "today", "yesterday", "message", "type", "call", "audio",
        "is", "are", "was", "were", "been", "has", "had", "can", "could", "should",
        "our", "your", "who", "which", "when", "where", "why", "how", "so", "up",
        "out", "if", "about", "into", "just", "very", "much", "more", "now", "see",
        "look", "come", "its", "over", "also", "back", "after", "use", "two", "work",
        "first", "even", "new", "want", "because", "any", "these", "give", "day", "days",
        "week", "weeks", "month", "months", "year", "years", "time", "no", "yes", "please",
        "thanks", "thank", "job", "offer", "experience", "needed", "salary", "bonus", "payment",
        "language", "written", "speaking", "good", "knowledge", "tickets", "contract", "training",
        "team", "activities", "hours", "nightshift", "overtime", "holidays", "weekends", "subscription",
        "public", "transportation", "medical", "clinic", "private", "relocation", "period", "paid",
        "online", "game", "presenters", "monthly", "net", "meal", "bank", "christmas", "easter"
    )

    val COMMON_ROMANIAN_WORDS = setOf(
        "la", "multi", "mulți", "ani", "un", "an", "nou", "fericit", "fericita", "fericită", "si", "și",
        "ce", "faci", "buna", "bună", "salut", "unde", "esti", "ești", "cum", "bine",
        "da", "nu", "multumesc", "mulțumesc", "mersi", "te", "iubesc", "prieten", "prietene",
        "frate", "noapte", "ziua", "seara", "drag", "draga", "dragă", "acum", "maine", "mâine",
        "azi", "ai", "fost", "am", "avut", "sunt", "este", "e", "in", "în", "de", "cu",
        "pentru", "care", "cine", "dece", "de ce", "vreau", "stiu", "știu", "pot",
        "dracu", "dracului", "ingerule", "îngerule", "frumusețe", "hai", "pa", "pup", "te pup",
        "om", "oameni", "totul", "tot", "mesaj", "mesaje", "mesajul", "scuze", "noroc",
        "sanatate", "sănătate", "vorbim", "sigur", "desigur", "bineinteles", "bineînțeles",
        "perfect", "bravo", "trebuie", "vino", "stai", "asteapta", "așteaptă", "poate", "cred",
        "se", "depun", "secretariat", "urmatoarele", "următoarele", "cerere", "borderou",
        "completat", "semnat", "titular", "chitantele", "chitanțele", "copie", "xerox",
        "dupa", "după", "cardul", "termen", "depunere", "ultima", "saptamana", "săptămână",
        "fiecarui", "semestru", "acasa", "acasă", "bani", "loc", "treaba", "treabă", "drum",
        "masina", "mașină", "casa", "casă", "facem", "vedem", "cand", "când", "daca", "dacă",
        "dar", "sau", "adica", "adică", "deja", "inca", "încă", "foarte", "mult", "puțin", "putin",
        "astazi", "astăzi", "aseara", "aseară", "dimineata", "dimineață", "zi", "zile",
        "ajung", "ajuns", "imediat", "mergem", "zici", "spune", "spus", "plec", "plecam",
        "intalnim", "cafea", "mancam", "piata", "nimic", "gata", "astept", "intarziu",
        "scrie", "vorbesc", "intreb", "raspund", "uite", "aici", "acolo", "frumos",
        "frumoasa", "naspa", "asa", "chiar", "doar", "macar", "nici", "despre",
        "ceva", "nimeni", "toata", "greu", "usor", "rau",
        "vii", "suna", "sună", "scriu", "zis", "facut", "făcut", "poti", "poți",
        "vrei", "vrea", "haide", "exact", "probabil", "oricum", "iti", "îți",
        "imi", "îmi", "isi", "își", "pleci", "plecați", "plecati", "ajungi",
        "ajungem", "mergi", "mergeți", "mergeti", "mâncăm", "cafeaua", "serios",
        "misto", "mișto", "pupa", "pupă", "pupici", "iubire", "iubito", "frumoaso",
        "scumpule", "dimineață", "neata", "neatza", "salutari", "salutări",
        "astepti", "aștepți", "treci", "auzim", "raman", "rămân", "intram", "intrăm",
        "iesim", "ieșim", "dormi", "dorm", "lucrez", "lucrezi", "muncesc", "muncesti"
    )

    private val GENERIC_ROMANCE_PREPOSITIONS = setOf("de", "in", "e", "a", "la")

    // Por qué: Tabla declarativa de expresiones breves exactas (≤ 4 palabras) para evitar cadenas de if frágiles
    private data class IdiomRule(
        val phrases: Set<String>,
        val translation: String
    )

    private val ROMANIAN_TO_GREEK_EXACT_IDIOMS = listOf(
        IdiomRule(setOf("la multi ani si un an nou fericit", "la multi ani un an nou fericit"), "Χρόνια πολλά και ευτυχισμένο το νέο έτος!"),
        IdiomRule(setOf("la multi ani"), "Χρόνια πολλά!"),
        IdiomRule(setOf("craciun fericit"), "Καλά Χριστούγεννα!"),
        IdiomRule(setOf("paste fericit"), "Καλό Πάσχα!"),
        IdiomRule(setOf("buna dimineata"), "Καλημέρα!"),
        IdiomRule(setOf("buna seara"), "Καλησπέρα!"),
        IdiomRule(setOf("noapte buna"), "Καληνύχτα!"),
        IdiomRule(setOf("buna ziua"), "Γεια σας!"),
        IdiomRule(setOf("salut", "buna"), "Γεια σου!"),
        IdiomRule(setOf("ce faci", "cum esti"), "Τι κάνεις;"),
        IdiomRule(setOf("ce mai faci"), "Τι γίνεται;"),
        IdiomRule(setOf("totul e bine", "totul este bine"), "Όλα είναι καλά."),
        IdiomRule(setOf("mesaj"), "Μήνυμα"),
        IdiomRule(setOf("scuze", "imi cer scuze"), "Συγγνώμη!"),
        IdiomRule(setOf("multumesc", "mersi"), "Ευχαριστώ!"),
        IdiomRule(setOf("cu placere"), "Παρακαλώ!"),
        IdiomRule(setOf("du-te dracului", "du-te dracu"), "Άντε στο διάολο!"),
        IdiomRule(setOf("ce dracu", "ce naiba"), "Τι στο διάολο;")
    )

    private val GREEK_TO_ROMANIAN_EXACT_IDIOMS = listOf(
        IdiomRule(setOf("χρονια πολλα και καλη χρονια", "χρονια πολλα και ευτυχισμενο το νεο ετος"), "La mulți ani și un An Nou fericit!"),
        IdiomRule(setOf("χρονια πολλα"), "La mulți ani!"),
        IdiomRule(setOf("καλημερα"), "Bună dimineața!"),
        IdiomRule(setOf("καλησπερα"), "Bună seara!"),
        IdiomRule(setOf("καληνυχτα"), "Noapte bună!"),
        IdiomRule(setOf("γεια σου"), "Salut!"),
        IdiomRule(setOf("γεια σας"), "Bună ziua!"),
        IdiomRule(setOf("τι κανεις"), "Ce faci?"),
        IdiomRule(setOf("τι κανεις φιλε μου", "τι κανεις φιλε"), "Ce faci, prietene?"),
        IdiomRule(setOf("πως εισαι"), "Cum ești?"),
        IdiomRule(setOf("πως εισαι φιλε μου", "πως εισαι φιλε"), "Cum ești, prietene?"),
        IdiomRule(setOf("τι γινεται"), "Ce mai faci?"),
        IdiomRule(setOf("ολα καλα"), "Totul e bine."),
        IdiomRule(setOf("που εισαι"), "Unde ești?"),
        IdiomRule(setOf("γαμωτο", "γαμω το"), "La dracu!"),
        IdiomRule(setOf("αντε ρε μαλακα", "ρε μαλακα", "μαλακα"), "Hai mă, prostule!"),
        IdiomRule(setOf("αντε γαμησου", "αντε στο διαολο", "γαμησου"), "Du-te dracului!")
    )

    /**
     * Determines whether a recognized rectangle corresponds to a chat message bubble
     * based on the target app's [alignmentMode], while filtering out centered date pills
     * and full-width background chrome.
     */
    fun isMessageBubble(
        rectLeft: Int,
        rectRight: Int,
        screenWidth: Int,
        alignmentMode: ChatAlignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT
    ): Boolean {
        if (screenWidth <= 0) return true
        val width = rectRight - rectLeft
        val hasReasonableWidth = width < (screenWidth * 0.95f) && width > 20
        if (!hasReasonableWidth) return false

        return when (alignmentMode) {
            ChatAlignmentMode.LEFT_RIGHT_CHAT -> {
                // Por qué: Los mensajes entrantes abrazan el margen izquierdo (< 28% del ancho)
                // y los salientes el derecho (> 72% del ancho), descartando pastillas de fecha centradas.
                val isIncoming = rectLeft < (screenWidth * 0.28f)
                val isOutgoing = rectRight > (screenWidth * 0.72f)
                isIncoming || isOutgoing
            }
            ChatAlignmentMode.LEFT_STREAM_CHAT -> {
                // Por qué: Apps como Discord y Slack alinean todos los mensajes en columna izquierda tras el avatar
                rectLeft < (screenWidth * 0.35f)
            }
        }
    }

    fun isMessageBubble(
        rect: Rect,
        screenWidth: Int,
        alignmentMode: ChatAlignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT
    ): Boolean {
        return isMessageBubble(rect.left, rect.right, screenWidth, alignmentMode)
    }

    /**
     * Enforces strict source-language eligibility.
     */
    fun isEligibleSourceText(
        text: String,
        sourceLang: String,
        targetLang: String,
        identifiedLang: String? = null,
        allowDeferredLatinForMlKit: Boolean = false
    ): Boolean {
        val clean = TranslationFilter.cleanMessageText(text).trim()
        if (clean.length < 2) return false

        if (ScriptDetector.containsUrlOrDomain(clean)) {
            return false
        }

        val greekLetters = ScriptDetector.countGreek(clean)
        val latinLetters = ScriptDetector.countLatin(clean)

        if (targetLang == TranslateLanguage.GREEK) {
            if (greekLetters > 0 && (latinLetters == 0 || greekLetters > latinLetters)) {
                return false
            }
        }

        val words = clean.lowercase().split(Regex("""[^a-zăâîșț]+""")).filter { it.isNotBlank() }
        val englishTokenCount = words.count { COMMON_ENGLISH_TOKENS.contains(it) }
        val romanianTokenCount = words.count { COMMON_ROMANIAN_WORDS.contains(it) }
        val hasRoDiacritics = ScriptDetector.hasRomanianDiacritics(clean)

        if (sourceLang == TranslateLanguage.ROMANIAN) {
            if (identifiedLang != null && identifiedLang != "und" && identifiedLang != TranslateLanguage.ROMANIAN && identifiedLang != "ron") {
                if (!hasRoDiacritics) {
                    return false
                }
            }

            if (englishTokenCount >= 2 && romanianTokenCount == 0 && !hasRoDiacritics) {
                return false
            }

            if (hasRoDiacritics) return true

            val specificRomanianTokens = words.count { COMMON_ROMANIAN_WORDS.contains(it) && !GENERIC_ROMANCE_PREPOSITIONS.contains(it) }
            if (specificRomanianTokens > 0 && englishTokenCount < 2) {
                return true
            }

            if (englishTokenCount > 0 && romanianTokenCount == 0) {
                return false
            }

            if (identifiedLang == TranslateLanguage.ROMANIAN || identifiedLang == "ron") {
                return true
            }

            if (allowDeferredLatinForMlKit && identifiedLang == null && latinLetters >= 2) {
                return true
            }

            return false
        }

        if (sourceLang == TranslateLanguage.ENGLISH) {
            if (latinLetters < 2) return false
            if (englishTokenCount > 0) return true
            if (identifiedLang == TranslateLanguage.ENGLISH || identifiedLang == "eng") return true
            if (hasRoDiacritics || romanianTokenCount > 0) return false
            if (identifiedLang != null &&
                identifiedLang != "und" &&
                identifiedLang != TranslateLanguage.ENGLISH &&
                identifiedLang != targetLang
            ) {
                return false
            }
            return true
        }

        if (sourceLang == TranslateLanguage.GREEK) {
            return greekLetters > 0
        }

        if (targetLang == TranslateLanguage.GREEK) {
            return latinLetters >= 2
        }

        return true
    }

    fun isEligibleSourceText(
        text: String,
        sourceLang: String,
        targetLang: String,
        candidates: List<IdentifiedLanguage>
    ): Boolean {
        val topCandidate = candidates.maxByOrNull { it.confidence }?.languageTag
        return isEligibleSourceText(text, sourceLang, targetLang, topCandidate)
    }

    /**
     * Resolves exact short greetings and holiday phrases before submitting to NMT.
     *
     * Por qué: Solo actúa cuando el mensaje completo coincide exactamente con una expresión de la tabla
     * (tras normalizar puntuación y acentos), evitando colapsar frases largas que contengan "mersi" o "μαλάκα".
     */
    fun resolveIdiomPreTranslation(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        val trimmed = text.trim()
        if (trimmed.contains("\n")) return null

        val clean = TranslationFilter.cleanMessageText(trimmed).trim()
        val words = clean.replace(Regex("""[;?!,.¡¿]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        if (words.isEmpty() || words.size > MAX_SHORT_IDIOM_WORDS + 4) return null

        if (sourceLang == TranslateLanguage.ROMANIAN && targetLang == TranslateLanguage.GREEK) {
            val normalized = normalizeRomanian(words.joinToString(" "))
            val rule = ROMANIAN_TO_GREEK_EXACT_IDIOMS.firstOrNull { normalized in it.phrases } ?: return null
            if (words.size > MAX_SHORT_IDIOM_WORDS && !normalized.startsWith("la multi ani")) return null
            return rule.translation
        }

        if (sourceLang == TranslateLanguage.GREEK && targetLang == TranslateLanguage.ROMANIAN) {
            val normalized = normalizeGreek(words.joinToString(" "))
            val rule = GREEK_TO_ROMANIAN_EXACT_IDIOMS.firstOrNull { normalized in it.phrases } ?: return null
            if (words.size > MAX_SHORT_IDIOM_WORDS && !normalized.startsWith("χρονια πολλα")) return null
            if (rule.translation == "Totul e bine." && (clean.endsWith(";") || clean.endsWith("?"))) {
                return "Totul e bine?"
            }
            return rule.translation
        }

        return null
    }

    /**
     * Post-processing grammar adjustments that never discard the NMT sentence.
     */
    fun applyPostTranslationSenseLogic(
        originalText: String,
        translatedText: String,
        sourceLang: String,
        targetLang: String
    ): String {
        if (sourceLang == TranslateLanguage.ROMANIAN && targetLang == TranslateLanguage.GREEK) {
            var result = translatedText.trim()
            val origNorm = normalizeRomanian(originalText)

            // Por qué: "La mulți ani" fuera de cumpleaños significa "Χρόνια πολλά", no "Ευτυχισμένα γενέθλια"
            if (origNorm.contains("an nou") || !origNorm.contains("zi de nastere")) {
                result = result.replace(Regex("""(Ευτυχισμένα|Χαρούμενα)\s+γενέθλια""", RegexOption.IGNORE_CASE), "Χρόνια πολλά")
                result = result.replace(Regex("""και ένα ευτυχισμένο νέο έτος""", RegexOption.IGNORE_CASE), "και ευτυχισμένο το νέο έτος")
            }

            // Por qué: Elimina el artículo nominativo "ο" delante de nombres propios en vocativo tras coma
            result = result.replace(Regex(""",\s*ο\s+([ΆΈΉΊΌΎΏΑ-Ωα-ωάέήίόύώ]+)"""), ", $1")
            result = result.replace(Regex("""^ο\s+([ΆΈΉΊΌΎΏΑ-Ωα-ωάέήίόύώ]+),"""), "$1,")

            // Por qué: En trato directo "ο φίλος μου" debe ir en vocativo "φίλε μου"
            result = result.replace(Regex("""(?iu)(?<!\p{L})ο\s+φίλος\s+μου(?!\p{L})"""), "φίλε μου")
            result = result.replace(Regex("""(?iu)(?<!\p{L})φίλος\s+μου(?!\p{L})"""), "φίλε μου")

            // Por qué: Convierte la terminación nominativa a vocativa solo si el nombre ya está en la traducción
            result = result.replace(Regex("""(?<!\p{L})Γιάννης(?=[,!;?:]|\s*$)"""), "Γιάννη")
            result = result.replace(Regex("""(?<!\p{L})Άγγελος(?=[,!;?:]|\s*$)"""), "Άγγελε")
            result = result.replace(Regex("""(?<!\p{L})Γιώργος(?=[,!;?:]|\s*$)"""), "Γιώργο")
            result = result.replace(Regex("""(?<!\p{L})Νίκος(?=[,!;?:]|\s*$)"""), "Νίκο")
            result = result.replace(Regex("""(?<!\p{L})Κώστας(?=[,!;?:]|\s*$)"""), "Κώστα")
            result = result.replace(Regex("""(?<!\p{L})Δημήτρης(?=[,!;?:]|\s*$)"""), "Δημήτρη")

            // Por qué: En griego las preguntas se cierran con ';'
            if (origNorm.endsWith("?") && result.endsWith("?")) {
                result = result.substring(0, result.length - 1) + ";"
            }

            return result
        }

        if (sourceLang == TranslateLanguage.GREEK && targetLang == TranslateLanguage.ROMANIAN) {
            var result = translatedText.trim()

            // Por qué: ML Kit vierte la interjección griega "Άντε" como el préstamos italiano "Ante"; se corrige el token sin borrar la frase
            result = result.replaceFirst(Regex("""^Ante\s+""", RegexOption.IGNORE_CASE), "Hai ")
            result = result.replace(Regex("""\bAnte\b""", RegexOption.IGNORE_CASE), "Hai")

            // Por qué: En trato directo "prietenul meu" se convierte al vocativo "prietene"
            result = result.replace(Regex("""\b(esti|ești)\s+prietenul\s+meu\b""", RegexOption.IGNORE_CASE), "ești, prietene")
            result = result.replace(Regex("""\bprietenul\s+meu\s+([A-Z][a-zA-Z]+)""", RegexOption.IGNORE_CASE), "prietene $1")
            result = result.replace(Regex("""\bprietenul\s+meu\b""", RegexOption.IGNORE_CASE), "prietene")

            // Por qué: Normaliza el signo de interrogación griego ';' a '?' en rumano
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

    private fun normalizeRomanian(text: String): String {
        return text.lowercase()
            .replace("ă", "a")
            .replace("â", "a")
            .replace("î", "i")
            .replace("ș", "s")
            .replace("ț", "t")
            .trim()
    }

    fun normalizeGreek(text: String): String {
        return text.lowercase()
            .replace("ά", "α").replace("έ", "ε").replace("ή", "η")
            .replace("ί", "ι").replace("ό", "ο").replace("ύ", "υ").replace("ώ", "ω")
            .replace("ΐ", "ι").replace("ΰ", "υ")
            .trim()
    }
}
