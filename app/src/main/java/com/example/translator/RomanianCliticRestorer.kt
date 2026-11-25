package com.example.translator

/**
 * Restaurador general de clíticos, negaciones, condicionales y abreviaturas de chat en rumano.
 *
 * Por qué: En chats informales se omiten los guiones ("n am", "s a", "mi as", "nu i", "si a luat"),
 * lo cual rompe el analizador gramatical de ML Kit antes del pivote inglés.
 */
object RomanianCliticRestorer {

    private val WORD_BOUNDARY_REGEX = Regex("""(?i)\b([a-zăâîșț]+(-[a-zăâîșț]+)?)\b""")
    private val RO_WORD_REGEX = Regex("""(?i)\b[a-zăâîșț]{2,}\b""")

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
        """(?iu)\b(și|si|să|în|îți|îmi|își|așa|nu|da|eu|tu|el|ea|noi|voi|ce|cine|când|unde|cum|dacă|care|doar|sau|pentru|trebuie|cred|poți|vrei|faci|făcut|cineva|mulțumesc|mersi|pup|rămas|prins|chinui|abonamente|legitimația|ștampilate|lăudat|mințit|rectorat|secretariat|cămin|cartela|cererea|bonul|bursa|dosar|permisiune|contractul|bună|salut|problemă|restanță|restanțe|buletin|buletinul|din|probabil|studenti|studenți|sesiune|partial|parțial)\b"""
    )

    // Pasada no condicional (segura sin contexto extra)
    private val NEG_N_VERB_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(n)\s+(avem|am|ai|are|au|ați|ati)\b""")
    private val NEG_N_O_SA_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(n)\s+o\s+(să|sa)\b""")
    private val CLITIC_SA_SAU_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(s)\s+(a|au)\s+(?=\p{L}{2,})""")
    private val CLITIC_PRON_AUX_UNCONDITIONAL_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(ne|mi|ti|ți)\s+(am|ai|a|au|ați|ati)\s+(?=\p{L}{2,})""")
    private val CLITIC_INTR_DINTR_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(intr|într|dintr|printr)\s+(un|o)\b""")
    private val COND_UNCONDITIONAL_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(mi|ti|ți|s|n)\s+(as|aș|ar)\s+(?=\p{L}{2,})""")

    private val PHRASE_O_SA_REGEX = Regex("""(?iu)\bo\s+sa\b""")
    private val PHRASE_OR_SA_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(or)\s+(să|sa)\b""")
    private val PHRASE_NU_SA_MA_REGEX = Regex("""(?iu)\b(nu|sa|să)\s+ma\b""")
    private val PHRASE_AUX_SA_REGEX = Regex("""(?iu)\b(trebuie|vreau|vrea|vrei|vrut|poti|poți|poate|am|ai|are|avem|aveti|aveți|hai|haide|stai|sta|mergi|duci)\s+sa\b""")
    private val PHRASE_SA_DIE_REGEX = Regex("""\b([Ss][aă])\s+die\b""")
    private val PHRASE_SA_INTRII_REGEX = Regex("""(?iu)\b(sa|să)\s+intrii\b""")
    private val PHRASE_SA_SCRI_REGEX = Regex("""(?iu)\b(sa|să)\s+scri\b""")
    private val PHRASE_SA_CLAUSE_REGEX = Regex(
        """(?iu)\bsa\s+(nu|te|ne|va|vă|le|li|ii|îi|imi|îmi|iti|îți|o|se|fie|fiu|fim|fiti|fiți|merg|mergem|merge|vii|vina|vină|dam|dăm|dai|dea|intri|intru|fac|facem|faca|facă|stii|știi|stie|știe|spun|spuna|spună|fur|fure|iau|iei|luam|luăm|duc|duci|duca|ducă|vad|văd|vada|vadă|vorbim|vorbesc|trimit|trimita|trimită|verific|verifice|depun|depunem|depuna|depună|completez|completam|completăm|semnez|semnam|semnăm|semneze)\b"""
    )
    private val PHRASE_TI_DA_REGEX = Regex("""(?iu)\b(ti|ți)\s+da\b""")
    private val PHRASE_TI_VERB_REGEX = Regex("""(?iu)\b(ti|ți)\s+(dau|dai|spune|spun|trimit|trimite|arăt|arat|zice|zic)\b""")
    private val PHRASE_VERB_CA_REGEX = Regex(
        """(?iu)\b(cred|zic|zice|spune|spun|stiu|știu|pare|parcă|gândesc|banuiesc|bănuiesc|vad|văd|auzit|aflat|scrie|pentru|fiindcă|deși|măcar|hai|haide|probabil)\s+ca\b"""
    )
    private val PHRASE_CA_CLAUSE_REGEX = Regex(
        """(?iu)\bca\s+(nu|doar|numai|deja|e|este|sunt|am|ai|a|au|o|va|mai|poate|trebuie|acum|mâine|maine|azi|aici|acolo|eu|tu|el|ea|noi|voi|ei|ele|oricum|dacă|daca|după|dupa|pentru|fără|fara|tot|toți|toti)\b"""
    )
    private val PHRASE_VI_MOVEMENT_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(vi)\s+(pe\s+la|la|cu|azi|mâine|maine|acum|aici|și|si)\b""")
    private val PHRASE_ANU_NUM_REGEX = Regex("""(?iu)\banu\s+(\d+)\b""")
    private val PHRASE_ANU_DEMO_REGEX = Regex("""(?iu)\b(anu)\s+(asta|ăsta|trecut|viitor)\b""")

    // Pasada condicionada a contexto rumano real
    private val CLITIC_SINGLE_PRON_AUX_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(m|te|l|v|le)\s+(am|ai|a|au|ați|ati)\s+(?=\p{L}{2,})""")
    private val CLITIC_I_AUX_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(i)\s+(am|ai|a|au|ați|ati)\s+(\p{L}{2,})""")
    private val ENGLISH_AFTER_I_AM_GUARD = setOf(
        "going", "here", "sorry", "good", "fine", "not", "just", "so", "very", "the", "in", "at", "on", "from", "ready", "sure"
    )
    private val CLITIC_SI_AUX_PARTICIPLE_REGEX = Regex(
        """(?iu)(?<![\p{L}'’-])\b(si|și)\s+(a|au)\s+(luat|făcut|dat|pus|spus|găsit|pierdut|cumpărat|trimis|primit|zis|scris|cerut|băgat|schimbat|ales|adus|dus|stricat|reparat|uitat|păstrat|comandat)\b"""
    )
    private val COND_CONTEXT_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(l|i|ne|v|le|te|m)\s+(as|aș|ar|ai|ați|ati)\s+(?=\p{L}{2,})""")
    private val ENCLITIC_NU_PRON_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(nu)\s+(i|l|mi|ti|ți|si|și)\s+(?=\p{L}{2,})""")
    private val ENCLITIC_SA_PRON_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(sa|să)\s+(l|i|mi|ti|ți|si|și)\s+(?=\p{L}{2,})""")
    private val ENCLITIC_QUESTION_I_REGEX = Regex("""(?iu)(?<![\p{L}'’-])\b(ce|unde|cine|asta|cum)\s+(i)\s+(?=\p{L}{2,})""")

    private val RO_CAMPUS_ACRONYM_REGEX = Regex("""(?i)(?<!\p{L})(acs|stb|fils|upb|bac)(?!\p{L})""")
    private val RO_BUILDING_CODE_REGEX = Regex("""(?i)(?<!\p{L})p(\d{1,2})(?!\p{L})""")
    private val RO_UPDATE_ROMGLISH_REGEX = Regex("""(?iu)\bupdat(eze|ează|eaza|at|ăm|am|a)\b""")
    private val RO_PRINT_ROMGLISH_REGEX = Regex("""(?iu)\bprint(eze|ează|eaza|at|ată|am|ăm|a)\b""")
    private val RO_CHECK_ROMGLISH_REGEX = Regex("""(?iu)\bcheck(ui|uiască|uiasca|uit|uim|uiți|uiti)\b""")
    private val RO_FORWARD_ROMGLISH_REGEX = Regex("""(?iu)\bforward(eze|ează|eaza|at|am|ăm)\b""")
    private val RO_VA_TO_YOU_REGEX = Regex("""(?iu)\b(va)\s+(învață|trimit|rog|anunț|mulțumesc|dau|las|aștept|spun|zic|explic|arăt)\b""")
    private val RO_CUM_E_LA_NOI_REGEX = Regex("""(?iu)\bcum\s+e\s+la\s+(noi|voi|ei|ele)\b""")

    fun hasRomanianContext(text: String): Boolean {
        if (ScriptDetector.hasRomanianDiacritics(text)) return true
        return RO_CONTEXT_MARKER_REGEX.containsMatchIn(text)
    }

    fun restoreUnconditionalCliticsAndPhrases(text: String): String {
        var result = text

        result = NEG_N_VERB_REGEX.replace(result) { match ->
            val neg = RomanianDiacriticsRestorer.matchCasing(match.groupValues[1], "nu")
            val verb = if (match.groupValues[2].lowercase() == "ati") "ați" else match.groupValues[2]
            "$neg $verb"
        }
        result = NEG_N_O_SA_REGEX.replace(result) {
            "${RomanianDiacriticsRestorer.matchCasing(it.groupValues[1], "nu")} o să"
        }
        result = CLITIC_SA_SAU_REGEX.replace(result) { "${it.groupValues[1]}-${it.groupValues[2]} " }
        result = CLITIC_PRON_AUX_UNCONDITIONAL_REGEX.replace(result) { match ->
            val rawPron = match.groupValues[1]
            val pron = if (rawPron.lowercase() == "ti") RomanianDiacriticsRestorer.matchCasing(rawPron, "ți") else rawPron
            val aux = if (match.groupValues[2].lowercase() == "ati") "ați" else match.groupValues[2]
            "$pron-$aux "
        }
        result = CLITIC_INTR_DINTR_REGEX.replace(result) { match ->
            val rawPrefix = match.groupValues[1]
            val prefix = if (rawPrefix.lowercase() == "intr") RomanianDiacriticsRestorer.matchCasing(rawPrefix, "într") else rawPrefix
            "$prefix-${match.groupValues[2]}"
        }
        result = COND_UNCONDITIONAL_REGEX.replace(result) { match ->
            val pron = normalizeConditionalPronoun(match.groupValues[1])
            val aux = normalizeConditionalAux(match.groupValues[2])
            "$pron-$aux "
        }

        result = PHRASE_O_SA_REGEX.replace(result) { "${RomanianDiacriticsRestorer.matchCasing(it.value.take(1), "o")} să" }
        result = PHRASE_OR_SA_REGEX.replace(result) { "${RomanianDiacriticsRestorer.matchCasing(it.groupValues[1].take(1), "o")} să" }
        result = PHRASE_NU_SA_MA_REGEX.replace(result) { match ->
            val first = match.groupValues[1]
            val fixedFirst = if (first.lowercase() == "sa") RomanianDiacriticsRestorer.matchCasing(first, "să") else first
            "$fixedFirst mă"
        }
        result = PHRASE_AUX_SA_REGEX.replace(result) { "${it.groupValues[1]} să" }
        result = PHRASE_SA_DIE_REGEX.replace(result) { match ->
            "${RomanianDiacriticsRestorer.matchCasing(match.groupValues[1], "să")} fie"
        }
        result = PHRASE_SA_INTRII_REGEX.replace(result) { match ->
            "${RomanianDiacriticsRestorer.matchCasing(match.groupValues[1], "să")} intri"
        }
        result = PHRASE_SA_SCRI_REGEX.replace(result) { match ->
            "${RomanianDiacriticsRestorer.matchCasing(match.groupValues[1], "să")} scrii"
        }
        result = PHRASE_SA_CLAUSE_REGEX.replace(result) { match ->
            val prefix = match.value.substringBefore(' ')
            "${RomanianDiacriticsRestorer.matchCasing(prefix, "să")} ${match.groupValues[1]}"
        }
        result = PHRASE_TI_DA_REGEX.replace(result) { "${RomanianDiacriticsRestorer.matchCasing(it.groupValues[1], "îți")} dă" }
        result = PHRASE_TI_VERB_REGEX.replace(result) { "${RomanianDiacriticsRestorer.matchCasing(it.groupValues[1], "îți")} ${it.groupValues[2]}" }
        result = PHRASE_VERB_CA_REGEX.replace(result) { match ->
            val verb = match.groupValues[1]
            val ca = match.value.substringAfterLast(' ')
            "$verb ${RomanianDiacriticsRestorer.matchCasing(ca, "că")}"
        }
        result = PHRASE_CA_CLAUSE_REGEX.replace(result) { match ->
            val ca = match.value.substringBefore(' ')
            "${RomanianDiacriticsRestorer.matchCasing(ca, "că")} ${match.groupValues[1]}"
        }
        result = PHRASE_VI_MOVEMENT_REGEX.replace(result) { match ->
            "${RomanianDiacriticsRestorer.matchCasing(match.groupValues[1], "vii")} ${match.groupValues[2]}"
        }
        result = PHRASE_ANU_NUM_REGEX.replace(result) { match ->
            val anu = match.value.substringBefore(' ')
            "${RomanianDiacriticsRestorer.matchCasing(anu, "anul")} ${match.groupValues[1]}"
        }
        result = PHRASE_ANU_DEMO_REGEX.replace(result) { match ->
            val anu = match.groupValues[1]
            val second = if (match.groupValues[2].lowercase() == "asta") {
                RomanianDiacriticsRestorer.matchCasing(match.groupValues[2], "ăsta")
            } else {
                match.groupValues[2]
            }
            "${RomanianDiacriticsRestorer.matchCasing(anu, "anul")} $second"
        }

        return result
    }

    fun restoreContextGatedCliticsAndChat(text: String): String {
        var result = text

        result = CLITIC_SINGLE_PRON_AUX_REGEX.replace(result) { match ->
            val aux = if (match.groupValues[2].lowercase() == "ati") "ați" else match.groupValues[2]
            "${match.groupValues[1]}-$aux "
        }
        result = CLITIC_I_AUX_REGEX.replace(result) { match ->
            val nextToken = match.groupValues[3]
            val nextLower = nextToken.lowercase()
            if (nextLower in ENGLISH_AFTER_I_AM_GUARD || !RO_WORD_REGEX.matches(nextToken)) {
                match.value
            } else {
                val aux = if (match.groupValues[2].lowercase() == "ati") "ați" else match.groupValues[2]
                "${match.groupValues[1]}-$aux $nextToken"
            }
        }
        result = CLITIC_SI_AUX_PARTICIPLE_REGEX.replace(result) { match ->
            val conj = RomanianDiacriticsRestorer.matchCasing(match.groupValues[1], "și")
            "$conj-${match.groupValues[2]} ${match.groupValues[3]}"
        }
        result = COND_CONTEXT_REGEX.replace(result) { match ->
            val rawPron = match.groupValues[1]
            val rawAux = match.groupValues[2]
            // Por qué: Solo reescribe `"l ai"`, `"i am"` condicional si el auxiliar es aș/ar, o pronombre l-/m-/te- ante aș/ar.
            val lowerAux = rawAux.lowercase()
            if ((rawPron.lowercase() == "i" || rawPron.lowercase() == "l") && (lowerAux == "ai" || lowerAux == "ati" || lowerAux == "ați")) {
                val fixedAux = if (lowerAux == "ati") "ați" else rawAux
                "$rawPron-$fixedAux "
            } else if (lowerAux == "as" || lowerAux == "aș" || lowerAux == "ar") {
                "${normalizeConditionalPronoun(rawPron)}-${normalizeConditionalAux(rawAux)} "
            } else {
                match.value
            }
        }
        result = ENCLITIC_NU_PRON_REGEX.replace(result) { match ->
            "${match.groupValues[1]}-${normalizeEncliticPronoun(match.groupValues[2])} "
        }
        result = ENCLITIC_SA_PRON_REGEX.replace(result) { match ->
            val conj = if (match.groupValues[1].lowercase() == "sa") {
                RomanianDiacriticsRestorer.matchCasing(match.groupValues[1], "să")
            } else {
                match.groupValues[1]
            }
            "$conj-${normalizeEncliticPronoun(match.groupValues[2])} "
        }
        result = ENCLITIC_QUESTION_I_REGEX.replace(result) { match ->
            "${match.groupValues[1]}-${match.groupValues[2]} "
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
        result = RO_UPDATE_ROMGLISH_REGEX.replace(result) { match ->
            val stem = when (match.groupValues[1].lowercase()) {
                "eze" -> "actualizeze"
                "ează", "eaza" -> "actualizează"
                "at" -> "actualizat"
                "ăm", "am" -> "actualizăm"
                else -> "actualiza"
            }
            RomanianDiacriticsRestorer.matchCasing(match.value, stem)
        }
        result = RO_PRINT_ROMGLISH_REGEX.replace(result) { match ->
            val stem = when (match.groupValues[1].lowercase()) {
                "eze" -> "imprime"
                "ează", "eaza" -> "imprimă"
                "at" -> "imprimat"
                "ată" -> "imprimată"
                "am", "ăm" -> "imprimăm"
                else -> "imprima"
            }
            RomanianDiacriticsRestorer.matchCasing(match.value, stem)
        }
        result = RO_CHECK_ROMGLISH_REGEX.replace(result) { match ->
            val stem = when (match.groupValues[1].lowercase()) {
                "ui", "uiască", "uiasca" -> "verifice"
                "uit" -> "verificat"
                "uim" -> "verificăm"
                else -> "verificați"
            }
            RomanianDiacriticsRestorer.matchCasing(match.value, stem)
        }
        result = RO_FORWARD_ROMGLISH_REGEX.replace(result) { match ->
            val stem = when (match.groupValues[1].lowercase()) {
                "eze" -> "redirecționeze"
                "ează", "eaza" -> "redirecționează"
                "at" -> "redirecționat"
                else -> "redirecționăm"
            }
            RomanianDiacriticsRestorer.matchCasing(match.value, stem)
        }
        result = RO_VA_TO_YOU_REGEX.replace(result) {
            "${RomanianDiacriticsRestorer.matchCasing(it.groupValues[1], "vă")} ${it.groupValues[2]}"
        }
        result = RO_CUM_E_LA_NOI_REGEX.replace(result) { "cum este la ${it.groupValues[1]}" }

        return result
    }

    private fun normalizeConditionalPronoun(raw: String): String = when (raw.lowercase()) {
        "ti" -> RomanianDiacriticsRestorer.matchCasing(raw, "ți")
        else -> raw
    }

    private fun normalizeConditionalAux(raw: String): String = when (raw.lowercase()) {
        "as" -> RomanianDiacriticsRestorer.matchCasing(raw, "aș")
        "ati" -> RomanianDiacriticsRestorer.matchCasing(raw, "ați")
        else -> raw
    }

    private fun normalizeEncliticPronoun(raw: String): String = when (raw.lowercase()) {
        "ti" -> RomanianDiacriticsRestorer.matchCasing(raw, "ți")
        "si" -> RomanianDiacriticsRestorer.matchCasing(raw, "și")
        else -> raw
    }

    private fun hasPrecedingDigit(text: String, startIndex: Int): Boolean {
        var idx = startIndex - 1
        while (idx >= 0 && text[idx].isWhitespace()) {
            idx--
        }
        return idx >= 0 && text[idx].isDigit()
    }
}

