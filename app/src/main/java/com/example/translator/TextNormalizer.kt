package com.example.translator

import java.util.regex.Pattern

/**
 * Funciones puras para normalización de texto, restauración de diacríticos rumanos,
 * protección de emojis y detección automática del tipo de alfabeto.
 */
object TextNormalizer {

    enum class ScriptType {
        GREEK,
        LATIN,
        NEUTRAL
    }

    /**
     * Determina si el texto pertenece predominantemente al alfabeto griego o latino.
     * Por qué: Permite alternar la dirección de traducción de forma automática mientras
     * el usuario escribe en el teclado, sin requerir configuración manual constante.
     */
    fun detectScriptType(text: String): ScriptType {
        val greekCount = ScriptDetector.countGreek(text)
        val latinCount = ScriptDetector.countLatin(text)
        return when {
            greekCount > 0 && greekCount >= latinCount -> ScriptType.GREEK
            latinCount > greekCount -> ScriptType.LATIN
            else -> ScriptType.NEUTRAL
        }
    }

    // ── Romanian Diacritics Restoration ────────────────────────────────────────

    private val ROMANIAN_DIACRITICS_MAP = mapOf(
        "si" to "și",
        "in" to "în",
        "intr-un" to "într-un",
        "intr-o" to "într-o",
        "esti" to "ești",
        "fara" to "fără",
        "stiu" to "știu",
        "cand" to "când",
        "maine" to "mâine",
        "acasa" to "acasă",
        "multumesc" to "mulțumesc",
        "buna" to "bună",
        "draga" to "dragă",
        "multa" to "multă",
        "multi" to "mulți",
        "totusi" to "totuși",
        "insa" to "însă",
        "asteapta" to "așteaptă",
        "sanatate" to "sănătate",
        "placere" to "plăcere",
        "sfarsit" to "sfârșit",
        "decat" to "decât",
        "frumoasa" to "frumoasă",
        "viata" to "viață",
        "seara" to "seară",
        "dimineata" to "dimineață",
        "masina" to "mașină",
        "strada" to "stradă",
        "baiat" to "băiat",
        "baieti" to "băieți",
        "toti" to "toți",
        "cat" to "cât",
        "cati" to "câți",
        "cate" to "câte",
        "camin" to "cămin",
        "sef" to "șef",
        "sefa" to "șefa",
        "acorda" to "acordă",
        "performanta" to "performanță",
        "completam" to "completăm",
        "completati" to "completați",
        "afiseze" to "afișeze",
        "duceti" to "duceți",
        "dam" to "dăm",
        "lasa" to "lasă",
        "baza" to "bază",
        "semestru" to "semestrul",
        "bineinteles" to "bineînțeles",
        "inteles" to "înțeles",
        "impreuna" to "împreună",
        "incet" to "încet",
        "incotro" to "încotro",
        "inainte" to "înainte",
        "inapoi" to "înapoi",
        "langa" to "lângă",
        "pana" to "până",
        "dupa" to "după",
        "alaturi" to "alături",
        "intotdeauna" to "întotdeauna",
        "niciodata" to "niciodată",
        "iti" to "îți",
        "imi" to "îmi",
        "isi" to "își",
        "asa" to "așa",
        "poti" to "poți",
        "stii" to "știi",
        "stie" to "știe",
        "stim" to "știm",
        "stiti" to "știți",
        "facut" to "făcut",
        "facuta" to "făcută",
        "ramas" to "rămas",
        "raman" to "rămân",
        "ramane" to "rămâne",
        "tarziu" to "târziu",
        "poimaine" to "poimâine",
        "incolo" to "încolo",
        "incoace" to "încoace",
        "intreb" to "întreb",
        "intrebare" to "întrebare",
        "raspuns" to "răspuns",
        "raspunde" to "răspunde",
        "gandesc" to "gândesc",
        "gandit" to "gândit",
        "saptamana" to "săptămâna",
        "saptamani" to "săptămâni",
        "plati" to "plăți",
        "plata" to "plată",
        "legitimatia" to "legitimația",
        "legitimatie" to "legitimație",
        "stampilate" to "ștampilate",
        "stampilat" to "ștampilat",
        "stampilata" to "ștampilată",
        "stampila" to "ștampilă",
        "niste" to "niște",
        "parca" to "parcă",
        "macar" to "măcar",
        "inca" to "încă",
        "daca" to "dacă"
    )

    private val WORD_BOUNDARY_REGEX = Regex("""(?i)\b([a-zăâîșț]+(-[a-zăâîșț]+)?)\b""")
    private val LOWERCASE_CHAT_ABBR_WORDS = mapOf(
        "cnv" to "cineva",
        "cv" to "ceva",
        "cf" to "ce faci",
        "cmf" to "ce mai faci",
        "dc" to "de ce",
        "dk" to "dacă",
        "fc" to "făcut",
        "fct" to "făcut",
        "vr" to "vrei",
        "gt" to "gata",
        "bn" to "bine",
        "vb" to "vorbim",
        "nush" to "nu știu",
        "nuș" to "nu știu",
        "ptr" to "pentru",
        "pls" to "te rog",
        "npc" to "n-ai pentru ce"
    )
    private val RO_CONTEXT_MARKER_REGEX = Regex(
        """(?iu)\b(și|si|să|în|îți|îmi|își|așa|nu|da|eu|tu|el|ea|noi|voi|ce|cine|când|unde|cum|dacă|care|doar|sau|pentru|trebuie|cred|poți|vrei|faci|făcut|cineva|mulțumesc|mersi|pup|rămas|prins|chinui|abonamente|legitimația|ștampilate|lăudat|mințit|rectorat|secretariat|cămin|cartela|cererea|bonul|bursa|dosar|permisiune|contractul|bună|salut)\b"""
    )
    private val ELONGATED_WORD_RULES: List<Pair<Regex, String>> = listOf(
        Regex("""(?iu)\bbun[aă]{2,}\b""") to "bună",
        Regex("""(?iu)\bda{3,}\b""") to "da",
        Regex("""(?iu)\bnu{3,}\b""") to "nu",
        Regex("""(?iu)\bmultu{2,}\b""") to "mulțumesc",
        Regex("""(?iu)\bsalu{2,}t+\b""") to "salut",
        Regex("""(?iu)\bmersi{2,}\b""") to "mersi",
        Regex("""(?iu)\bha{2,}i\b""") to "hai"
    )
    private val FATA_VERSO_REGEX = Regex("""(?iu)\bfata[\s/-]+verso\b""")
    private val SPATELE_FATA_REGEX = Regex("""(?iu)\b(spatele)\s*/\s*(fata)\b""")
    private val FATA_SPATE_REGEX = Regex("""(?iu)\b(fata)\s*/\s*(spate)\b""")
    private val PREP_FATA_REGEX = Regex("""(?iu)\b(in|în|pe)\s+fata\b""")
    private val FATA_DE_REGEX = Regex("""(?iu)\b(fata)\s+de\b""")
    private val NEG_N_VERB_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(n)\s+(avem|am|ai|are|au|ați|ati)\b""")
    private val NEG_N_O_SA_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(n)\s+o\s+(să|sa)\b""")
    private val CLITIC_SA_SAU_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(s)\s+(a|au)\s+(?=\p{L}{2,})""")
    private val CLITIC_PRON_AUX_UNCONDITIONAL_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(ne|mi|ti|ți)\s+(am|ai|a|au|ați|ati)\s+(?=\p{L}{2,})""")
    private val CLITIC_INTR_DINTR_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(intr|într|dintr)\s+(un|o)\b""")
    private val CLITIC_SINGLE_PRON_AUX_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(m|te|l|v|le)\s+(am|ai|a|au|ați|ati)\s+(?=\p{L}{2,})""")
    private val CLITIC_SA_NU_PRON_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(sa|să|nu)\s+(l|i|mi|ti|ți)\s+(?=\p{L}{2,})""")
    private val PHRASE_O_SA_REGEX = Regex("""(?iu)\bo\s+sa\b""")
    private val PHRASE_OR_SA_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(or)\s+(să|sa)\b""")
    private val PHRASE_NU_SA_MA_REGEX = Regex("""(?iu)\b(nu|sa|să)\s+ma\b""")
    private val PHRASE_AUX_SA_REGEX = Regex("""(?iu)\b(trebuie|vreau|vrea|vrei|poti|poți|poate|am|ai|are|avem|aveti|aveți|hai|haide|stai|sta|mergi|duci)\s+sa\b""")
    private val PHRASE_SA_DIE_REGEX = Regex("""\b([Ss][aă])\s+die\b""")
    private val PHRASE_SA_INTRII_REGEX = Regex("""(?iu)\b(sa|să)\s+intrii\b""")
    private val PHRASE_SA_CLAUSE_REGEX = Regex(
        """(?iu)\bsa\s+(nu|te|ne|va|vă|le|li|ii|îi|imi|îmi|iti|îți|o|se|fie|fiu|fim|fiti|fiți|merg|mergem|merge|vii|vina|vină|dam|dăm|dai|dea|intri|intru|fac|facem|faca|facă|stii|știi|stie|știe|spun|spuna|spună)\b"""
    )
    private val PHRASE_TI_DA_REGEX = Regex("""(?iu)\b(ti|ți)\s+da\b""")
    private val PHRASE_TI_VERB_REGEX = Regex("""(?iu)\b(ti|ți)\s+(dau|dai|spune|spun|trimit|trimite|arăt|arat|zice|zic)\b""")
    private val PHRASE_VERB_CA_REGEX = Regex(
        """(?iu)\b(cred|zic|zice|spune|spun|stiu|știu|pare|parcă|gândesc|banuiesc|bănuiesc|vad|văd|auzit|aflat|scrie|pentru|fiindcă|deși|măcar)\s+ca\b"""
    )
    private val PHRASE_CA_CLAUSE_REGEX = Regex(
        """(?iu)\bca\s+(nu|doar|numai|deja|e|este|sunt|am|ai|a|au|o|va|mai|poate|trebuie|acum|mâine|maine|azi|aici|acolo|eu|tu|el|ea|noi|voi|ei|ele|oricum|dacă|daca|după|dupa|pentru|fără|fara|tot|toți|toti)\b"""
    )
    private val PHRASE_VI_MOVEMENT_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(vi)\s+(pe\s+la|la|cu|azi|mâine|maine|acum|aici|și|si)\b""")
    private val PHRASE_ANU_NUM_REGEX = Regex("""(?iu)\banu\s+(\d+)\b""")
    private val PHRASE_ANU_DEMO_REGEX = Regex("""(?iu)\b(anu)\s+(asta|ăsta|trecut|viitor)\b""")
    private val RO_CAMPUS_ACRONYM_REGEX = Regex("""(?i)(?<!\p{L})(acs|stb|fils|upb|bac)(?!\p{L})""")
    private val RO_BUILDING_CODE_REGEX = Regex("""(?i)(?<!\p{L})p(\d{1,2})(?!\p{L})""")
    private val SINGLE_LEADING_MENTION_REGEX = Regex("""^(@~?[\p{L}\p{N}_.-]+)([ \t]+)""")
    private val NEXT_TOKEN_REGEX = Regex("""^([^\s]+)[ \t]+""")
    private val SURNAME_OR_INITIAL_REGEX = Regex("""^(?:\p{Lu}\.?|\p{Lu}[\p{Ll}]{1,24})$""")
    private val MENTION_SENTENCE_STARTERS = setOf(
        "ce", "cum", "cand", "când", "unde", "care", "cine", "de", "nu", "da",
        "sa", "să", "si", "și", "la", "pe", "cu", "in", "în", "pentru",
        "poate", "daca", "dacă", "vezi", "hai", "buna", "bună", "salut",
        "multumesc", "mulțumesc", "mersi", "am", "ai", "are", "avem", "este",
        "sunt", "trebuie", "stii", "știi", "vii", "vino", "what", "when",
        "where", "why", "how", "can", "do", "did", "is", "no", "yes", "please", "thanks"
    )

    data class DetachedLeadingMentions(
        val prefix: String,
        val body: String
    )

    /**
     * Separa menciones iniciales (`@~Edward `, `@~Vlad Chintoiu `) antes de invocar la traducción
     * para evitar que el token opaco PUA contamine la primera palabra o se traduzcan apellidos.
     */
    fun detachLeadingMentions(text: String): DetachedLeadingMentions {
        var cursor = 0
        while (cursor < text.length) {
            val slice = text.substring(cursor)
            val mentionMatch = SINGLE_LEADING_MENTION_REGEX.find(slice) ?: break
            val token = mentionMatch.groupValues[1]
            var nextCursor = cursor + mentionMatch.range.last + 1

            // Por qué: Las menciones push de WhatsApp suelen incluir nombre y apellido (`@~Vlad Chintoiu `),
            // pero solo absorbemos una segunda palabra capitalizada si no es inicio de frase ni única palabra.
            if (token.startsWith("@~") && nextCursor < text.length) {
                val afterMention = text.substring(nextCursor)
                val secondMatch = NEXT_TOKEN_REGEX.find(afterMention)
                if (secondMatch != null) {
                    val secondToken = secondMatch.groupValues[1]
                    val remainingStart = nextCursor + secondMatch.range.last + 1
                    val hasBodyAfter = remainingStart < text.length && text.substring(remainingStart).any { !it.isWhitespace() }
                    if (hasBodyAfter &&
                        SURNAME_OR_INITIAL_REGEX.matches(secondToken) &&
                        !MENTION_SENTENCE_STARTERS.contains(secondToken.lowercase())
                    ) {
                        nextCursor = remainingStart
                    }
                }
            }
            cursor = nextCursor
        }
        return if (cursor == 0) {
            DetachedLeadingMentions("", text)
        } else {
            DetachedLeadingMentions(
                prefix = text.substring(0, cursor),
                body = text.substring(cursor)
            )
        }
    }

    private val MULTI_WORD_DIACRITIC_RULES: List<Pair<Regex, String>> = listOf(
        "noapte buna" to "noapte bună",
        "noapte bună" to "noapte bună",
        "buna dimineata" to "bună dimineața",
        "bună dimineață" to "bună dimineața",
        "buna seara" to "bună seara",
        "bună seară" to "bună seara",
        "buna ziua" to "bună ziua",
        "bună ziua" to "bună ziua"
    ).map { (plain, withDiacritics) ->
        Regex("""(?iu)(?<!\p{L})${Regex.escape(plain)}(?!\p{L})""") to withDiacritics
    }

    fun normalizeRomanianCedillas(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace('ş', 'ș')
            .replace('Ş', 'Ș')
            .replace('ţ', 'ț')
            .replace('Ţ', 'Ț')
    }

    /**
     * Restaura los signos diacríticos rumanos para palabras comunes de chat.
     * Por qué: En chat informal es común omitir diacríticos ('esti' en vez de 'ești'),
     * lo cual degrada la precisión del modelo ML Kit en el dispositivo.
     */
    fun restoreRomanianDiacritics(text: String): String {
        var result = normalizeRomanianCedillas(text)

        // Por qué: Procesa primero palabras individuales y luego frases multipalabra para que
        // saludos fijos ("bună seara", "bună dimineața") prevalezcan sobre formas indefinidas ("seară").
        result = WORD_BOUNDARY_REGEX.replace(result) { match ->
            val word = match.value
            val lower = word.lowercase()
            val replacement = when {
                (lower == "il" || lower == "ii") && word != word.uppercase() -> if (lower == "il") "îl" else "îi"
                (lower == "il" || lower == "ii") -> null
                else -> ROMANIAN_DIACRITICS_MAP[lower]
            }
            if (replacement != null) {
                matchCasing(word, replacement)
            } else {
                word
            }
        }

        for ((pattern, withDiacritics) in MULTI_WORD_DIACRITIC_RULES) {
            result = pattern.replace(result) { match ->
                matchCasing(match.value, withDiacritics)
            }
        }

        return result
    }

    /**
     * Prepara texto informal de chat grupal rumano antes de enviarlo a ML Kit NMT:
     * colapsa alargamientos expresivos, restaura clíticos/negaciones sin guion y diacríticos de frase,
     * y expande abreviaturas preservando siglas (`VR`, `GT`, `FC`, `LA`, `ACS`, `STB`, `P10`).
     */
    fun normalizeRomanianChatForTranslation(text: String): String {
        if (text.isBlank()) return text
        var result = normalizeRomanianCedillas(text)

        for ((pattern, canonical) in ELONGATED_WORD_RULES) {
            result = pattern.replace(result) { matchCasing(it.value, canonical) }
        }

        // Por qué: Resuelve "față"/"fața" por contexto antes del diccionario léxico para no confundir "față-verso" con "fată".
        result = FATA_VERSO_REGEX.replace(result) { matchCasing(it.value.take(4), "față") + "-verso" }
        result = SPATELE_FATA_REGEX.replace(result) { "${it.groupValues[1]}/${matchCasing(it.groupValues[2], "fața")}" }
        result = FATA_SPATE_REGEX.replace(result) { "${matchCasing(it.groupValues[1], "față")}/spate" }
        result = PREP_FATA_REGEX.replace(result) { "${it.groupValues[1]} față" }
        result = FATA_DE_REGEX.replace(result) { "${matchCasing(it.groupValues[1], "față")} de" }

        result = restoreRomanianDiacritics(result)

        result = NEG_N_VERB_REGEX.replace(result) { match ->
            val neg = matchCasing(match.groupValues[1], "nu")
            val verb = if (match.groupValues[2].lowercase() == "ati") "ați" else match.groupValues[2]
            "$neg $verb"
        }
        result = NEG_N_O_SA_REGEX.replace(result) { "${matchCasing(it.groupValues[1], "nu")} o să" }
        result = CLITIC_SA_SAU_REGEX.replace(result) { "${it.groupValues[1]}-${it.groupValues[2]} " }
        result = CLITIC_PRON_AUX_UNCONDITIONAL_REGEX.replace(result) { match ->
            val pron = if (match.groupValues[1].lowercase() == "ti") matchCasing(match.groupValues[1], "ți") else match.groupValues[1]
            val aux = if (match.groupValues[2].lowercase() == "ati") "ați" else match.groupValues[2]
            "$pron-$aux "
        }
        result = CLITIC_INTR_DINTR_REGEX.replace(result) { match ->
            val prefix = if (match.groupValues[1].lowercase() == "intr") matchCasing(match.groupValues[1], "într") else match.groupValues[1]
            "$prefix-${match.groupValues[2]}"
        }

        result = PHRASE_O_SA_REGEX.replace(result) { "${matchCasing(it.value.take(1), "o")} să" }
        result = PHRASE_OR_SA_REGEX.replace(result) { "${matchCasing(it.groupValues[1].take(1), "o")} să" }
        result = PHRASE_NU_SA_MA_REGEX.replace(result) { match ->
            val first = match.groupValues[1]
            val fixedFirst = if (first.lowercase() == "sa") matchCasing(first, "să") else first
            "$fixedFirst mă"
        }
        result = PHRASE_AUX_SA_REGEX.replace(result) { "${it.groupValues[1]} să" }
        result = PHRASE_SA_DIE_REGEX.replace(result) { match ->
            "${matchCasing(match.groupValues[1], "să")} fie"
        }
        result = PHRASE_SA_INTRII_REGEX.replace(result) { match ->
            "${matchCasing(match.groupValues[1], "să")} intri"
        }
        result = PHRASE_SA_CLAUSE_REGEX.replace(result) { match ->
            val prefix = match.value.substringBefore(' ')
            "${matchCasing(prefix, "să")} ${match.groupValues[1]}"
        }
        result = PHRASE_TI_DA_REGEX.replace(result) { "${matchCasing(it.groupValues[1], "îți")} dă" }
        result = PHRASE_TI_VERB_REGEX.replace(result) { "${matchCasing(it.groupValues[1], "îți")} ${it.groupValues[2]}" }
        result = PHRASE_VERB_CA_REGEX.replace(result) { match ->
            val verb = match.groupValues[1]
            val ca = match.value.substringAfterLast(' ')
            "$verb ${matchCasing(ca, "că")}"
        }
        result = PHRASE_CA_CLAUSE_REGEX.replace(result) { match ->
            val ca = match.value.substringBefore(' ')
            "${matchCasing(ca, "că")} ${match.groupValues[1]}"
        }
        result = PHRASE_VI_MOVEMENT_REGEX.replace(result) { match ->
            "${matchCasing(match.groupValues[1], "vii")} ${match.groupValues[2]}"
        }
        result = PHRASE_ANU_NUM_REGEX.replace(result) { match ->
            val anu = match.value.substringBefore(' ')
            "${matchCasing(anu, "anul")} ${match.groupValues[1]}"
        }
        result = PHRASE_ANU_DEMO_REGEX.replace(result) { match ->
            val anu = match.groupValues[1]
            val second = if (match.groupValues[2].lowercase() == "asta") matchCasing(match.groupValues[2], "ăsta") else match.groupValues[2]
            "${matchCasing(anu, "anul")} $second"
        }

        val hasRoContext = ScriptDetector.hasRomanianDiacritics(result) || RO_CONTEXT_MARKER_REGEX.containsMatchIn(result)
        if (hasRoContext) {
            result = CLITIC_SINGLE_PRON_AUX_REGEX.replace(result) { match ->
                val aux = if (match.groupValues[2].lowercase() == "ati") "ați" else match.groupValues[2]
                "${match.groupValues[1]}-$aux "
            }
            result = CLITIC_SA_NU_PRON_REGEX.replace(result) { match ->
                val conj = if (match.groupValues[1].lowercase() == "sa") matchCasing(match.groupValues[1], "să") else match.groupValues[1]
                val pron = if (match.groupValues[2].lowercase() == "ti") matchCasing(match.groupValues[2], "ți") else match.groupValues[2]
                "$conj-$pron "
            }
            result = WORD_BOUNDARY_REGEX.replace(result) { match ->
                val word = match.value
                // Por qué: Solo expande abreviaturas de chat cuando van en minúsculas para no alterar siglas como VR, GT, FC o MS.
                if (word.any { it.isUpperCase() }) return@replace word
                val expanded = when (word) {
                    "ms", "mș" -> if (hasPrecedingDigit(result, match.range.first)) null else "mulțumesc"
                    "pt" -> if (hasPrecedingDigit(result, match.range.first)) null else "pentru"
                    else -> LOWERCASE_CHAT_ABBR_WORDS[word]
                }
                expanded ?: word
            }
            result = RO_CAMPUS_ACRONYM_REGEX.replace(result) { it.groupValues[1].uppercase() }
            result = RO_BUILDING_CODE_REGEX.replace(result) { "P${it.groupValues[1]}" }
        }

        return result
    }

    private fun hasPrecedingDigit(text: String, startIndex: Int): Boolean {
        var idx = startIndex - 1
        while (idx >= 0 && text[idx].isWhitespace()) {
            idx--
        }
        return idx >= 0 && text[idx].isDigit()
    }

    private fun matchCasing(original: String, replacement: String): String {
        val letters = original.filter { it.isLetter() }
        return when {
            letters.isNotEmpty() && letters.all { it.isUpperCase() } -> replacement.uppercase()
            original.firstOrNull()?.isUpperCase() == true -> {
                replacement.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            else -> replacement.lowercase()
        }
    }

    // ── Emoji & Special Token Protection ──────────────────────────────────────

    // Por qué: Protege emojis compuestos, @menciones y #hashtags para que ningún motor neuronal
    // traduzca ni corrompa identificadores o secuencias de símbolos especiales.
    private val EMOJI_PATTERN: Pattern = Pattern.compile(
        """(@~?[\p{L}\p{N}_.]+|#[\p{L}\p{N}_]+|[\x{1F300}-\x{1FAFF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}\x{1F1E6}-\x{1F1FF}\x{1F600}-\x{1F64F}\x{1F680}-\x{1F6FF}\uFE0F\u200D]+)"""
    )

    data class ProtectedResult(
        val text: String,
        val tokens: List<String>
    )

    /**
     * Enmascara emojis con caracteres de la zona de uso privado de Unicode (PUA: \uE000+) antes de traducir.
     * Por qué: Los marcadores textuales como '__EMOJI_0__' son interpretados como texto en inglés por los modelos
     * de ML Kit y eliminados o traducidos (dejando residuos como '___0__'). Los caracteres PUA son opacos y
     * respetados intactos por cualquier motor de traducción sin alterar palabras compuestas con guión.
     */
    fun protectEmojis(text: String): ProtectedResult {
        val tokens = mutableListOf<String>()
        val matcher = EMOJI_PATTERN.matcher(text)
        val sb = StringBuffer()

        while (matcher.find()) {
            val emoji = matcher.group()
            val tokenIndex = tokens.size
            tokens.add(emoji)
            val puaChar = (0xE000 + tokenIndex).toChar()
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(puaChar.toString()))
        }
        matcher.appendTail(sb)

        return ProtectedResult(sb.toString(), tokens)
    }

    /**
     * Restaura los emojis sustituyendo los caracteres PUA por sus secuencias originales.
     */
    fun restoreEmojis(translatedText: String, tokens: List<String>): String {
        if (tokens.isEmpty()) return translatedText

        val sb = StringBuilder()
        for (ch in translatedText) {
            val idx = ch.code - 0xE000
            if (idx in tokens.indices) {
                sb.append(tokens[idx])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
