package com.example.translator

/**
 * Tabla declarativa de desambiguación pre- y post-traducción específicamente para evitar
 * distorsiones del pivote interno inglés (`ro -> en -> el`) en ML Kit.
 */
object RomanianGreekPivotTable {

    private data class PivotRewriteRule(
        val regex: Regex,
        val transform: (MatchResult) -> String
    )

    private val PIVOT_BONUL_CONTEXT_REGEX = Regex("""(?iu)\b(stb|cartel|bilet|secretariat|cerere|abonament)""")
    private val PIVOT_BONUL_REGEX = Regex("""(?iu)\bbonul\b""")

    private val RO_PANA_WEEKDAY_REGEX = Regex("""(?i)\bpana\s+(luni|marti|miercuri|joi|vineri|sambata|duminica)\b""")
    private val EL_APO_WEEKDAY_REGEX = Regex("""(?iu)\bαπό\s+(την?\s+)?(Δευτέρα|Τρίτη|Τετάρτη|Πέμπτη|Παρασκευή|Σάββατο|Κυριακή)\b""")
    private val EL_PRYTIDA_REGEX = Regex("""(?iu)\bΠρυτίδα\b""")
    private val EL_ECHO_SEF_REGEX = Regex("""(?iu)\bέχω\s+σεφ\b""")
    private val EL_SPOROS_REGEX = Regex("""(?iu)\bσπόρος\b""")

    private val GREEK_NOMINATIVE_TO_VOCATIVE_NAMES = mapOf(
        "Γιάννης" to "Γιάννη",
        "Άγγελος" to "Άγγελε",
        "Γιώργος" to "Γιώργο",
        "Νίκος" to "Νίκο",
        "Κώστας" to "Κώστα",
        "Δημήτρης" to "Δημήτρη"
    )
    private val BIRTHDAY_GREEK_REGEX = Regex("""(Ευτυχισμένα|Χαρούμενα)\s+γενέθλια""", RegexOption.IGNORE_CASE)
    private val NEW_YEAR_GREEK_REGEX = Regex("""και ένα ευτυχισμένο νέο έτος""", RegexOption.IGNORE_CASE)
    private val GREEK_NOM_ARTICLE_COMMA_REGEX = Regex(""",\s*ο\s+([ΆΈΉΊΌΎΏΑ-Ωα-ωάέήίόύώ]+)""")
    private val GREEK_NOM_ARTICLE_START_REGEX = Regex("""^ο\s+([ΆΈΉΊΌΎΏΑ-Ωα-ωάέήίόύώ]+),""")
    private val GREEK_ARTICLE_MY_FRIEND_REGEX = Regex("""(?iu)(?<!\p{L})ο\s+φίλος\s+μου(?!\p{L})""")
    private val GREEK_MY_FRIEND_REGEX = Regex("""(?iu)(?<!\p{L})φίλος\s+μου(?!\p{L})""")
    private val GREEK_VOCATIVE_NAME_RULES = GREEK_NOMINATIVE_TO_VOCATIVE_NAMES.map { (nominative, vocative) ->
        Regex("""(?<!\p{L})$nominative(?=[,!;?:]|\s*$)""") to vocative
    }

    private val PRE_TRANSLATION_RULES: List<PivotRewriteRule> = listOf(
        PivotRewriteRule(Regex("""(?iu)\bfață[\s-]+verso\b""")) { m ->
            matchPreserveFirstCap(m.value, "pe ambele fețe")
        },
        PivotRewriteRule(Regex("""(?iu)\bviza\s+de\s+flotant\b""")) { m ->
            matchPreserveFirstCap(m.value, "viza de reședință temporară")
        },
        PivotRewriteRule(Regex("""(?iu)\b(mergem|mergeți|se\s+duc|ducem|venim|depunem|predăm|duceți)\s+fizic\b""")) { m ->
            "${m.groupValues[1]} în persoană"
        },
        PivotRewriteRule(Regex("""(?iu)\b(hai|haide)\s+că\s+(vin|merg|fac|dau|iau|scriu|sun|trimit|ajung|încerc|văd|stau)\b""")) { m ->
            "${matchPreserveFirstCap(m.groupValues[1], "bine")}, ${m.groupValues[2]}"
        },
        PivotRewriteRule(Regex("""(?iu)\bn-am\s+chef\b|\bnu\s+am\s+chef\b""")) { m ->
            matchPreserveFirstCap(m.value, "nu am dispoziție")
        },
        PivotRewriteRule(Regex("""(?iu)\b(am|ai|are|avem|aveți|au)\s+chef\b""")) { m ->
            "${m.groupValues[1]} dispoziție"
        },
        PivotRewriteRule(Regex("""(?iu)\bpe\s+bune\b""")) { m ->
            matchPreserveFirstCap(m.value, "serios")
        },
        PivotRewriteRule(Regex("""(?iu)\bnașpa\b""")) { m ->
            matchPreserveFirstCap(m.value, "rău")
        },
        PivotRewriteRule(Regex("""(?iu)\bmișto\b""")) { m ->
            matchPreserveFirstCap(m.value, "grozav")
        },
        PivotRewriteRule(Regex("""(?iu)\bbaftă\b""")) { m ->
            matchPreserveFirstCap(m.value, "mult noroc")
        },
        PivotRewriteRule(Regex("""(?iu)\b(spor)\s+la\s+(treabă|învățat|muncă|scris|citit|proiect|examene|examen)\b""")) { m ->
            "${matchPreserveFirstCap(m.groupValues[1], "succes")} la ${m.groupValues[2]}"
        },
        PivotRewriteRule(Regex("""(?iu)\bfaza\s+(?:e|este)\s+că\b""")) { m ->
            matchPreserveFirstCap(m.value, "ideea este că")
        },
        PivotRewriteRule(Regex("""(?iu)\bn-are\s+treabă\b|\bnu\s+are\s+treabă\b""")) { m ->
            matchPreserveFirstCap(m.value, "nu are legătură")
        },
        PivotRewriteRule(Regex("""(?iu)\blasă\s+că\s*,?""")) { m ->
            "${matchPreserveFirstCap(m.value.substringBefore(' '), "nu-ți face griji")},"
        },
        PivotRewriteRule(Regex("""(?iu)^păi\b""")) { m ->
            matchPreserveFirstCap(m.value, "ei bine")
        },
        PivotRewriteRule(Regex("""(?iu)\brestanțele\b""")) { m ->
            matchPreserveFirstCap(m.value, "examenele restante")
        },
        PivotRewriteRule(Regex("""(?iu)\brestanțe\b""")) { m ->
            matchPreserveFirstCap(m.value, "examene restante")
        },
        PivotRewriteRule(Regex("""(?iu)\brestanța\b""")) { m ->
            matchPreserveFirstCap(m.value, "examenul restant")
        },
        PivotRewriteRule(Regex("""(?iu)\bo\s+restanță\b""")) { m ->
            matchPreserveFirstCap(m.value, "un examen restant")
        },
        PivotRewriteRule(Regex("""(?iu)\brestanță\b""")) { m ->
            matchPreserveFirstCap(m.value, "examen restant")
        },
        PivotRewriteRule(Regex("""(?iu)\bbuletinul\b""")) { m ->
            matchPreserveFirstCap(m.value, "cartea de identitate")
        },
        PivotRewriteRule(Regex("""(?iu)\bbuletin\b""")) { m ->
            matchPreserveFirstCap(m.value, "carte de identitate")
        },
        PivotRewriteRule(Regex("""(?iu)\bprofu\b""")) { m ->
            matchPreserveFirstCap(m.value, "profesorul")
        },
        PivotRewriteRule(Regex("""(?iu)\bprofa\b""")) { m ->
            matchPreserveFirstCap(m.value, "profesoara")
        },
        PivotRewriteRule(Regex("""(?iu)\bmediei\s+de\s+la\s+bac\b""")) { m ->
            matchPreserveFirstCap(m.value, "notei medii de la bacalaureat")
        },
        PivotRewriteRule(Regex("""(?iu)\bmedia\s+de\s+la\s+bac\b""")) { m ->
            matchPreserveFirstCap(m.value, "nota medie de la bacalaureat")
        },
        PivotRewriteRule(Regex("""(?iu)\badmina\b""")) { m ->
            matchPreserveFirstCap(m.value, "administratoarea")
        },
        PivotRewriteRule(Regex("""(?iu)\bdobitoci\b""")) { m ->
            matchPreserveFirstCap(m.value, "proști")
        }
    )

    fun applyPreTranslationDisambiguation(normalizedText: String): String {
        var result = normalizedText
        for (rule in PRE_TRANSLATION_RULES) {
            result = rule.regex.replace(result, rule.transform)
        }
        if (PIVOT_BONUL_CONTEXT_REGEX.containsMatchIn(result)) {
            result = PIVOT_BONUL_REGEX.replace(result) { matchPreserveFirstCap(it.value, "chitanța") }
        }
        // Por qué: ML Kit suele interpretar "O să <verbo>" con mayúscula inicial como artículo griego "Ο " + imperativo.
        if (result.startsWith("O să ")) {
            result = "o să " + result.removePrefix("O să ")
        }
        return result
    }

    fun applyPostTranslationFixes(
        originalText: String,
        translatedText: String,
        origNorm: String
    ): String {
        var result = translatedText.trim()

        // Por qué: "La mulți ani" fuera de cumpleaños significa "Χρόνια πολλά", no "Ευτυχισμένα γενέθλια"
        if (origNorm.contains("an nou") || !origNorm.contains("zi de nastere")) {
            result = result.replace(BIRTHDAY_GREEK_REGEX, "Χρόνια πολλά")
            result = result.replace(NEW_YEAR_GREEK_REGEX, "και ευτυχισμένο το νέο έτος")
        }

        // Por qué: Corrige inversiones de sentido y alucinaciones frecuentes del pivote ro -> en -> el.
        if (origNorm.contains("rectorat")) {
            result = result.replace(EL_PRYTIDA_REGEX, "Πρυτανεία")
        }
        if (RO_PANA_WEEKDAY_REGEX.containsMatchIn(origNorm) && !origNorm.contains("de la") && !origNorm.contains("din ")) {
            result = result.replace(EL_APO_WEEKDAY_REGEX) { match ->
                "μέχρι ${match.groupValues[1]}${match.groupValues[2]}"
            }
        }
        if (origNorm.contains("chef")) {
            result = result.replace(EL_ECHO_SEF_REGEX, "έχω διάθεση")
        }
        if (origNorm.contains("spor")) {
            result = result.replace(EL_SPOROS_REGEX, "καλή επιτυχία")
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

        if (originalText.trim().firstOrNull()?.isUpperCase() == true && result.firstOrNull()?.isLowerCase() == true) {
            result = result.replaceFirstChar { it.titlecase() }
        }

        return result
    }

    private fun matchPreserveFirstCap(original: String, replacement: String): String {
        return if (original.firstOrNull()?.isUpperCase() == true) {
            replacement.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } else {
            replacement
        }
    }
}

