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
        "buna ziua" to "bună ziua",
        "good seara" to "bună seara",
        "good dimineata" to "bună dimineața",
        "good dimineața" to "bună dimineața",
        "good ziua" to "bună ziua",
        "good noapte" to "noapte bună"
    )

    private val WORD_BOUNDARY_REGEX = Regex("""(?i)\b([a-zăâîșț]+(-[a-zăâîșț]+)?)\b""")

    /**
     * Restaura los signos diacríticos rumanos para palabras comunes de chat.
     * Por qué: En chat informal es común omitir diacríticos ('esti' en vez de 'ești'),
     * lo cual degrada la precisión del modelo ML Kit en el dispositivo.
     */
    fun restoreRomanianDiacritics(text: String): String {
        var result = text

        // Por qué: Procesa primero palabras individuales y luego frases multipalabra para que
        // saludos fijos ("bună seara", "bună dimineața") prevalezcan sobre formas indefinidas ("seară").
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

        val multiWordOverrides = mapOf(
            "noapte buna" to "noapte bună",
            "noapte bună" to "noapte bună",
            "buna dimineata" to "bună dimineața",
            "bună dimineață" to "bună dimineața",
            "buna seara" to "bună seara",
            "bună seară" to "bună seara",
            "buna ziua" to "bună ziua",
            "bună ziua" to "bună ziua",
            "good seara" to "bună seara",
            "good seară" to "bună seara",
            "good dimineata" to "bună dimineața",
            "good dimineața" to "bună dimineața",
            "good dimineață" to "bună dimineața",
            "good ziua" to "bună ziua",
            "good noapte" to "noapte bună"
        )
        for ((plain, withDiacritics) in multiWordOverrides) {
            val pattern = Regex("""(?iu)(?<!\p{L})${Regex.escape(plain)}(?!\p{L})""")
            result = pattern.replace(result) { match ->
                matchCasing(match.value, withDiacritics)
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

    // Por qué: Usamos rangos hex de puntos de código suplementarios para evitar colisiones con el guión ASCII '-'
    private val EMOJI_PATTERN: Pattern = Pattern.compile(
        """[\x{1F300}-\x{1F9FF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}\x{1F1E6}-\x{1F1FF}\x{1F600}-\x{1F64F}\x{1F680}-\x{1F6FF}]+"""
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
