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
        var greekCount = 0
        var latinCount = 0
        for (ch in text) {
            if ((ch in '\u0370'..'\u03FF') || (ch in '\u1F00'..'\u1FFF')) {
                greekCount++
            } else if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in "ăâîșțĂÂÎȘȚ")) {
                latinCount++
            }
        }
        return when {
            greekCount > latinCount -> ScriptType.GREEK
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
        "dragă" to "dragă",
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
        "noapte buna" to "noapte bună",
        "buna dimineata" to "bună dimineața",
        "buna seara" to "bună seara",
        "buna ziua" to "bună ziua"
    )

    private val WORD_BOUNDARY_REGEX = Regex("""(?i)\b([a-zăâîșț]+(-[a-zăâîșț]+)?)\b""")

    /**
     * Restaura los signos diacríticos rumanos para palabras comunes de chat.
     * Por qué: En chat informal es común omitir diacríticos ('esti' en vez de 'ești'),
     * lo cual degrada la precisión del modelo ML Kit en el dispositivo.
     */
    fun restoreRomanianDiacritics(text: String): String {
        var result = text

        // Multi-word phrases first
        for ((plain, withDiacritics) in ROMANIAN_DIACRITICS_MAP) {
            if (plain.contains(" ")) {
                val pattern = Regex("""(?i)\b${Regex.escape(plain)}\b""")
                result = pattern.replace(result) { match ->
                    matchCasing(match.value, withDiacritics)
                }
            }
        }

        // Single words
        result = WORD_BOUNDARY_REGEX.replace(result) { match ->
            val word = match.value
            val lower = word.lowercase()
            val replacement = ROMANIAN_DIACRITICS_MAP[lower]
            if (replacement != null && !replacement.contains(" ")) {
                matchCasing(word, replacement)
            } else {
                word
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

    // ── Emoji & Token Protection ───────────────────────────────────────────────

    // Matches emoji surrogate pairs, symbols, and variation selectors
    private val EMOJI_PATTERN: Pattern = Pattern.compile(
        """[\uD83C-\uDBFF\uDC00-\uDFFF]+|[\u2600-\u27BF\uFE00-\uFE0F\u200D]+"""
    )

    private val TOKEN_RESTORE_PATTERN = Regex("""__\s*(?:EMOJI|emoji)_(\d+)\s*__""")

    data class ProtectedResult(
        val text: String,
        val tokens: List<String>
    )

    /**
     * Enmascara emojis con marcadores textuales seguros (`__EMOJI_0__`) antes de la llamada a ML Kit.
     * Por qué: Evita que el modelo ML Kit altere, elimine o traduzca erróneamente los emojis.
     */
    fun protectEmojis(text: String): ProtectedResult {
        val tokens = mutableListOf<String>()
        val matcher = EMOJI_PATTERN.matcher(text)
        val sb = StringBuffer()

        while (matcher.find()) {
            val emoji = matcher.group()
            val tokenIndex = tokens.size
            tokens.add(emoji)
            matcher.appendReplacement(sb, "__EMOJI_${tokenIndex}__")
        }
        matcher.appendTail(sb)

        return ProtectedResult(sb.toString(), tokens)
    }

    /**
     * Re-inserts the protected emojis back into their original locations
     * in the translated output.
     */
    fun restoreEmojis(translatedText: String, tokens: List<String>): String {
        if (tokens.isEmpty()) return translatedText

        return TOKEN_RESTORE_PATTERN.replace(translatedText) { match ->
            val indexStr = match.groupValues.getOrNull(1)
            val index = indexStr?.toIntOrNull()
            if (index != null && index in tokens.indices) {
                tokens[index]
            } else {
                match.value
            }
        }
    }
}
