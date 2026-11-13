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
        "fata" to "fată",
        "toti" to "toți",
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
        """(?iu)\b(și|si|să|în|îți|îmi|își|așa|nu|da|eu|tu|noi|voi|ce|cine|când|unde|cum|dacă|care|doar|sau|pentru|trebuie|cred|poți|vrei|faci|făcut|cineva|mulțumesc|mersi|pup|rămas|prins|chinui|abonamente|legitimația|ștampilate)\b"""
    )
    private val PHRASE_O_SA_REGEX = Regex("""(?iu)\bo\s+sa\b""")
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
    private val PHRASE_CA_CLAUSE_REGEX = Regex("""(?iu)\bca\s+(nu|doar|numai|deja|e|este|sunt|am|ai|a|au|o|va|mai|poate|trebuie)\b""")
    private val PHRASE_ANU_NUM_REGEX = Regex("""(?iu)\banu\s+(\d+)\b""")
    private val DIGIT_BEFORE_UNIT_REGEX = Regex("""\d\s*$""")

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
            val replacement = ROMANIAN_DIACRITICS_MAP[word.lowercase()]
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
     * restaura diacríticos léxicos y de frase, corrige erratas frecuentes (`sa die` -> `să fie`,
     * `sa intrii` -> `să intri`) y expande abreviaturas en minúsculas (`vr`, `gt`, `cnv`, `fc`, `ms`, `pt`)
     * preservando intactas las siglas en mayúsculas (`VR`, `GT`, `FC`, `LA`).
     */
    fun normalizeRomanianChatForTranslation(text: String): String {
        if (text.isBlank()) return text
        var result = restoreRomanianDiacritics(text)

        result = PHRASE_O_SA_REGEX.replace(result) { "${matchCasing(it.value.take(1), "o")} să" }
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
        result = PHRASE_ANU_NUM_REGEX.replace(result) { match ->
            val anu = match.value.substringBefore(' ')
            "${matchCasing(anu, "anul")} ${match.groupValues[1]}"
        }

        val hasRoContext = ScriptDetector.hasRomanianDiacritics(result) || RO_CONTEXT_MARKER_REGEX.containsMatchIn(result)
        if (hasRoContext) {
            result = WORD_BOUNDARY_REGEX.replace(result) { match ->
                val word = match.value
                // Por qué: Solo expande abreviaturas de chat cuando van en minúsculas para no alterar siglas como VR, GT, FC o MS.
                if (word.any { it.isUpperCase() }) return@replace word
                val preceding = result.substring(0, match.range.first)
                val expanded = when (word) {
                    "ms", "mș" -> if (DIGIT_BEFORE_UNIT_REGEX.containsMatchIn(preceding)) null else "mulțumesc"
                    "pt" -> if (DIGIT_BEFORE_UNIT_REGEX.containsMatchIn(preceding)) null else "pentru"
                    else -> LOWERCASE_CHAT_ABBR_WORDS[word]
                }
                expanded ?: word
            }
        }

        return result
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
        """(@~?[A-Za-z0-9_.]+|#[\p{L}\p{N}_]+|[\x{1F300}-\x{1FAFF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}\x{1F1E6}-\x{1F1FF}\x{1F600}-\x{1F64F}\x{1F680}-\x{1F6FF}\uFE0F\u200D]+)"""
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
