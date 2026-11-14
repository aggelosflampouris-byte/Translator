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
        "driving", "directions", "live", "traffic", "road", "updates", "share",
        "get", "based", "today", "yesterday", "message", "type", "call", "audio",
        "is", "are", "was", "were", "been", "has", "had", "can", "could", "should",
        "our", "your", "who", "which", "when", "where", "why", "how", "so", "up",
        "out", "if", "about", "into", "just", "very", "much", "more", "now", "see",
        "look", "come", "its", "over", "also", "back", "after", "use", "two", "work",
        "first", "even", "new", "want", "because", "any", "these", "give", "day", "days",
        "week", "weeks", "month", "months", "year", "years", "time", "no", "yes", "please",
        "thanks", "thank", "hello", "good", "morning", "evening", "night", "sorry",
        "know", "think", "going", "need", "make", "take", "friends", "friend",
        "people", "happy", "love", "tomorrow", "tonight", "later", "soon", "here"
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
        "dupa", "după", "acasa", "acasă", "bani", "loc", "treaba", "treabă", "drum",
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
        "scumpule", "neata", "neatza", "salutari", "salutări",
        "astepti", "aștepți", "treci", "auzim", "raman", "rămân", "intram", "intrăm",
        "iesim", "ieșim", "dormi", "dorm", "lucrez", "lucrezi", "muncesc", "muncesti",
        "eu", "noi", "voi", "ei", "ele", "aia", "asta", "ăsta", "ala", "ăla", "ăia", "alea",
        "acel", "acea", "acela", "aceea", "cineva", "cnv", "toți", "toti", "toate",
        "îl", "îți", "îmi", "își", "încolo", "incolo", "încoace", "incoace", "parcă", "până",
        "niște", "niste", "cât", "câți", "cati", "câte", "cate", "mai", "deci", "păi", "pai",
        "dăm", "dau", "dai", "vor", "vrei", "vreți", "vreti", "făcut", "fc", "chinui",
        "duc", "duci", "duce", "duceți", "duceti", "merg", "merge", "prins", "rămas", "ramas",
        "intru", "intri", "intrii", "ștampilate", "stampilate", "știe", "stie", "știi", "stii",
        "poze", "abonament", "abonamente", "abonamentele", "legitimația", "legitimatia",
        "cartela", "cartele", "metrou", "bilete", "ploaie", "afară", "afara", "lungă", "lunga",
        "șuruburi", "suruburi", "dovada", "dovadă", "ms"
    )

    private val GENERIC_ROMANCE_PREPOSITIONS = setOf("de", "in", "e", "a", "la")
    // Por qué: Vocablos breves coincidentes con inglés o lenguas romances que no deben anular el detector ML Kit por sí solos.
    private val AMBIGUOUS_ROMANCE_OR_ENGLISH_TOKENS = GENERIC_ROMANCE_PREPOSITIONS + setOf(
        "am", "an", "ai", "care", "drum", "pot", "cum", "perfect", "exact", "pup"
    )

    // Por qué: Tabla declarativa de nombres propios masculinos frecuentes para convertir nominativo a vocativo tras la NMT.
    private val GREEK_NOMINATIVE_TO_VOCATIVE_NAMES = mapOf(
        "Γιάννης" to "Γιάννη",
        "Άγγελος" to "Άγγελε",
        "Γιώργος" to "Γιώργο",
        "Νίκος" to "Νίκο",
        "Κώστας" to "Κώστα",
        "Δημήτρης" to "Δημήτρη"
    )

    private val WORD_SPLIT_REGEX = Regex("""[^a-zăâîșțşţ]+""")
    private val TITLE_CASE_NAME_TOKEN_REGEX = Regex("""^[A-ZĂÂÎȘȚ][a-zăâîșț.-]*\.{0,3}$""")
    private val IDIOM_PUNCT_REGEX = Regex("""[;?!,.¡¿]+""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val BIRTHDAY_GREEK_REGEX = Regex("""(Ευτυχισμένα|Χαρούμενα)\s+γενέθλια""", RegexOption.IGNORE_CASE)
    private val NEW_YEAR_GREEK_REGEX = Regex("""και ένα ευτυχισμένο νέο έτος""", RegexOption.IGNORE_CASE)
    private val GREEK_NOM_ARTICLE_COMMA_REGEX = Regex(""",\s*ο\s+([ΆΈΉΊΌΎΏΑ-Ωα-ωάέήίόύώ]+)""")
    private val GREEK_NOM_ARTICLE_START_REGEX = Regex("""^ο\s+([ΆΈΉΊΌΎΏΑ-Ωα-ωάέήίόύώ]+),""")
    private val GREEK_ARTICLE_MY_FRIEND_REGEX = Regex("""(?iu)(?<!\p{L})ο\s+φίλος\s+μου(?!\p{L})""")
    private val GREEK_MY_FRIEND_REGEX = Regex("""(?iu)(?<!\p{L})φίλος\s+μου(?!\p{L})""")
    private val GREEK_VOCATIVE_NAME_RULES = GREEK_NOMINATIVE_TO_VOCATIVE_NAMES.map { (nominative, vocative) ->
        Regex("""(?<!\p{L})$nominative(?=[,!;?:]|\s*$)""") to vocative
    }
    private val RO_ANTE_START_REGEX = Regex("""^Ante\s+""", RegexOption.IGNORE_CASE)
    private val RO_ANTE_WORD_REGEX = Regex("""\bAnte\b""", RegexOption.IGNORE_CASE)
    private val RO_ESTI_PRIETENUL_MEU_REGEX = Regex("""\b(esti|ești)\s+prietenul\s+meu\b""", RegexOption.IGNORE_CASE)
    private val RO_PRIETENUL_MEU_NAME_REGEX = Regex("""\bprietenul\s+meu\s+([A-Z][a-zA-Z]+)""", RegexOption.IGNORE_CASE)
    private val RO_PRIETENUL_MEU_REGEX = Regex("""\bprietenul\s+meu\b""", RegexOption.IGNORE_CASE)
    private val TRAILING_DOT_OR_SEMI_REGEX = Regex("""[;.]+$""")

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
                // Por qué: Flujos estilo canal alinean todos los mensajes en columna izquierda tras el avatar
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
     * Evalúa si un conjunto de mensajes visibles en un grupo confirma que la conversación activa
     * pertenece al idioma origen configurado (p. ej. múltiples burbujas en rumano inequívoco).
     */
    fun hasStrongConversationSourceContext(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): Boolean {
        if (sourceLang != TranslateLanguage.ROMANIAN || texts.isEmpty()) return false
        var strongSourceCount = 0
        var eligibleLatinCount = 0
        var strongEnglishCount = 0

        for (raw in texts) {
            val clean = TextNormalizer.normalizeRomanianCedillas(raw).trim()
            if (clean.length < 2) continue
            val greekLetters = ScriptDetector.countGreek(clean)
            val latinLetters = ScriptDetector.countLatin(clean)
            if (targetLang == TranslateLanguage.GREEK && greekLetters > latinLetters) continue
            if (latinLetters < 2) continue
            eligibleLatinCount++

            val words = clean.lowercase().split(WORD_SPLIT_REGEX).filter { it.isNotBlank() }
            val specificRo = words.count {
                COMMON_ROMANIAN_WORDS.contains(it) && !AMBIGUOUS_ROMANCE_OR_ENGLISH_TOKENS.contains(it)
            }
            val exclusiveEn = words.count {
                COMMON_ENGLISH_TOKENS.contains(it) &&
                    !COMMON_ROMANIAN_WORDS.contains(it) &&
                    !AMBIGUOUS_ROMANCE_OR_ENGLISH_TOKENS.contains(it)
            }
            if (ScriptDetector.hasRomanianDiacritics(clean) || (specificRo >= 2 && specificRo >= exclusiveEn)) {
                strongSourceCount++
            } else if (exclusiveEn >= 2 && specificRo == 0) {
                strongEnglishCount++
            }
        }

        if (strongSourceCount >= 2) return true
        return strongSourceCount == 1 && strongEnglishCount == 0 && eligibleLatinCount <= 3
    }

    /**
     * Identifica cabeceras cortas de remitente o insignias de rol ("Higher Year Representative",
     * "Stan Raluca") que aparecen encima del mensaje real dentro de la misma fila grupal.
     */
    fun isLikelyRowSenderHeader(text: String, sourceLang: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 48 || trimmed.contains('\n')) return false
        if (TranslationFilter.isSenderHandleOrMentionOnly(trimmed)) return true
        if (trimmed.indexOfAny(charArrayOf('?', '!', ';', ':', ',')) >= 0) return false
        val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 4) return false
        if (!tokens.all { it.matches(TITLE_CASE_NAME_TOKEN_REGEX) }) return false

        val normalizedWords = TextNormalizer.normalizeRomanianCedillas(trimmed)
            .lowercase()
            .split(WORD_SPLIT_REGEX)
            .filter { it.isNotBlank() }
        if (sourceLang == TranslateLanguage.ROMANIAN) {
            val specificRo = normalizedWords.count {
                COMMON_ROMANIAN_WORDS.contains(it) && !AMBIGUOUS_ROMANCE_OR_ENGLISH_TOKENS.contains(it)
            }
            if (specificRo > 0) return false
        }
        return true
    }

    private data class ScriptFacts(
        val greekLetters: Int,
        val latinLetters: Int,
        val hasRoDiacritics: Boolean,
        val wordCount: Int,
        val englishTokenCount: Int,
        val exclusiveEnglishTokenCount: Int,
        val romanianTokenCount: Int,
        val specificRomanianTokens: Int
    )

    private fun analyzeScriptFacts(text: String, alreadyCleaned: Boolean): ScriptFacts? {
        val clean = (if (alreadyCleaned) text else TranslationFilter.cleanMessageText(text)).trim()
        if (clean.length < 2 || ScriptDetector.containsUrlOrDomain(clean)) return null

        val greekLetters = ScriptDetector.countGreek(clean)
        val latinLetters = ScriptDetector.countLatin(clean)
        val cedillaNormalized = TextNormalizer.normalizeRomanianCedillas(clean)
        val words = cedillaNormalized.lowercase().split(WORD_SPLIT_REGEX).filter { it.isNotBlank() }

        var englishTokenCount = 0
        var exclusiveEnglishTokenCount = 0
        var romanianTokenCount = 0
        var specificRomanianTokens = 0
        for (word in words) {
            val isCommonEn = COMMON_ENGLISH_TOKENS.contains(word)
            val isCommonRo = COMMON_ROMANIAN_WORDS.contains(word)
            val isAmbiguous = AMBIGUOUS_ROMANCE_OR_ENGLISH_TOKENS.contains(word)
            if (isCommonEn) {
                englishTokenCount++
                if (!isCommonRo && !isAmbiguous) exclusiveEnglishTokenCount++
            }
            if (isCommonRo) {
                romanianTokenCount++
                if (!isAmbiguous) specificRomanianTokens++
            }
        }

        return ScriptFacts(
            greekLetters = greekLetters,
            latinLetters = latinLetters,
            hasRoDiacritics = ScriptDetector.hasRomanianDiacritics(cedillaNormalized),
            wordCount = words.size,
            englishTokenCount = englishTokenCount,
            exclusiveEnglishTokenCount = exclusiveEnglishTokenCount,
            romanianTokenCount = romanianTokenCount,
            specificRomanianTokens = specificRomanianTokens
        )
    }

    /**
     * Determina si es indispensable consultar ML Kit Language ID o si el alfabeto/vocabulario ya resuelve el caso.
     *
     * Por qué: Evita llamadas IPC innecesarias a ML Kit para griego o frases rumanas/inglesas inequívocas.
     */
    fun needsLanguageIdentification(
        text: String,
        sourceLang: String,
        targetLang: String,
        alreadyCleaned: Boolean = false
    ): Boolean {
        val facts = analyzeScriptFacts(text, alreadyCleaned) ?: return false
        if (targetLang == TranslateLanguage.GREEK &&
            facts.greekLetters > 0 &&
            (facts.latinLetters == 0 || facts.greekLetters > facts.latinLetters)
        ) {
            return false
        }
        return when (sourceLang) {
            TranslateLanguage.ROMANIAN -> {
                if (facts.hasRoDiacritics) false
                else if (facts.exclusiveEnglishTokenCount >= 2 && facts.romanianTokenCount == 0) false
                else !(facts.specificRomanianTokens >= 2 && facts.specificRomanianTokens >= facts.exclusiveEnglishTokenCount)
            }
            TranslateLanguage.ENGLISH -> {
                facts.latinLetters >= 2 &&
                    facts.englishTokenCount == 0 &&
                    !facts.hasRoDiacritics &&
                    facts.romanianTokenCount == 0
            }
            else -> false
        }
    }

    /**
     * Enforces strict source-language eligibility.
     */
    fun isEligibleSourceText(
        text: String,
        sourceLang: String,
        targetLang: String,
        identifiedLang: String? = null,
        allowDeferredLatinForMlKit: Boolean = false,
        alreadyCleaned: Boolean = false,
        conversationMatchesSource: Boolean = false
    ): Boolean {
        val facts = analyzeScriptFacts(text, alreadyCleaned) ?: return false

        if (targetLang == TranslateLanguage.GREEK) {
            if (facts.greekLetters > 0 && (facts.latinLetters == 0 || facts.greekLetters > facts.latinLetters)) {
                return false
            }
        }

        if (sourceLang == TranslateLanguage.ROMANIAN) {
            if (facts.hasRoDiacritics) return true

            if (facts.exclusiveEnglishTokenCount >= 2 && facts.romanianTokenCount == 0) {
                return false
            }

            // Por qué: Frases mixtas o coloquiales en rumano (ej. "I see, thanks, si dam poze..." o "ce e aia")
            // con múltiples tokens rumanos inequívocos deben traducirse aunque ML Kit detecte "en" al inicio.
            if (facts.specificRomanianTokens >= 2 && facts.specificRomanianTokens >= facts.exclusiveEnglishTokenCount) {
                return true
            }

            // Por qué: En mensajes rumanos breves sin diacríticos (ej. "buna ziua", "ce faci"), ML Kit
            // suele reportar danés o italiano; el vocabulario rumano inequívoco sin tokens ingleses debe prevalecer.
            if (facts.specificRomanianTokens > 0 &&
                facts.exclusiveEnglishTokenCount == 0 &&
                (identifiedLang != TranslateLanguage.ENGLISH && identifiedLang != "eng" || facts.specificRomanianTokens >= 2)
            ) {
                return true
            }

            if (conversationMatchesSource &&
                identifiedLang != targetLang &&
                (facts.specificRomanianTokens > 0 || (facts.wordCount in 1..4 && facts.exclusiveEnglishTokenCount == 0 && facts.latinLetters >= 2))
            ) {
                return true
            }

            if (identifiedLang != null && identifiedLang != "und" && identifiedLang != TranslateLanguage.ROMANIAN && identifiedLang != "ron") {
                return false
            }

            if (facts.specificRomanianTokens > 0 && facts.exclusiveEnglishTokenCount < 2) {
                return true
            }

            if (facts.exclusiveEnglishTokenCount > 0 && facts.romanianTokenCount == 0) {
                return false
            }

            if (identifiedLang == TranslateLanguage.ROMANIAN || identifiedLang == "ron") {
                return true
            }

            if (allowDeferredLatinForMlKit && identifiedLang == null && facts.latinLetters >= 2) {
                return true
            }

            return false
        }

        if (sourceLang == TranslateLanguage.ENGLISH) {
            if (facts.latinLetters < 2) return false
            if (facts.englishTokenCount > 0) return true
            if (identifiedLang == TranslateLanguage.ENGLISH || identifiedLang == "eng") return true
            if (facts.hasRoDiacritics || facts.romanianTokenCount > 0) return false
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
            return facts.greekLetters > 0
        }

        if (targetLang == TranslateLanguage.GREEK) {
            return facts.latinLetters >= 2
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
        val words = clean.replace(IDIOM_PUNCT_REGEX, " ")
            .split(WHITESPACE_REGEX)
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
                result = result.replace(BIRTHDAY_GREEK_REGEX, "Χρόνια πολλά")
                result = result.replace(NEW_YEAR_GREEK_REGEX, "και ευτυχισμένο το νέο έτος")
            }

            // Por qué: Elimina el artículo nominativo "ο" delante de nombres propios en vocativo tras coma
            result = result.replace(GREEK_NOM_ARTICLE_COMMA_REGEX, ", $1")
            result = result.replace(GREEK_NOM_ARTICLE_START_REGEX, "$1,")

            // Por qué: En trato directo "ο φίλος μου" debe ir en vocativo "φίλε μου"
            result = result.replace(GREEK_ARTICLE_MY_FRIEND_REGEX, "φίλε μου")
            result = result.replace(GREEK_MY_FRIEND_REGEX, "φίλε μου")

            // Por qué: Convierte la terminación nominativa a vocativa solo si el nombre ya está en la traducción.
            GREEK_VOCATIVE_NAME_RULES.forEach { (pattern, vocative) ->
                result = result.replace(pattern, vocative)
            }

            // Por qué: En griego las preguntas se cierran con ';'
            if (origNorm.endsWith("?") && result.endsWith("?")) {
                result = result.substring(0, result.length - 1) + ";"
            }

            return result
        }

        if (sourceLang == TranslateLanguage.GREEK && targetLang == TranslateLanguage.ROMANIAN) {
            var result = translatedText.trim()

            // Por qué: ML Kit vierte la interjección griega "Άντε" como el préstamos italiano "Ante"; se corrige el token sin borrar la frase
            result = result.replaceFirst(RO_ANTE_START_REGEX, "Hai ")
            result = result.replace(RO_ANTE_WORD_REGEX, "Hai")

            // Por qué: En trato directo "prietenul meu" se convierte al vocativo "prietene"
            result = result.replace(RO_ESTI_PRIETENUL_MEU_REGEX, "ești, prietene")
            result = result.replace(RO_PRIETENUL_MEU_NAME_REGEX, "prietene $1")
            result = result.replace(RO_PRIETENUL_MEU_REGEX, "prietene")

            // Por qué: Normaliza el signo de interrogación griego ';' a '?' en rumano
            if (originalText.trim().endsWith(";") || originalText.trim().endsWith("?") ||
                result.startsWith("Cum ", ignoreCase = true) ||
                result.startsWith("Ce ", ignoreCase = true) ||
                result.startsWith("Unde ", ignoreCase = true)) {
                if (!result.endsWith("?")) {
                    result = result.replace(TRAILING_DOT_OR_SEMI_REGEX, "") + "?"
                }
            }
            return result
        }

        return translatedText
    }

    private fun normalizeRomanian(text: String): String {
        return text.lowercase()
            .replace('ş', 'ș')
            .replace('ţ', 'ț')
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
