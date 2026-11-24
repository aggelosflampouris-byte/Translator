package com.example.translator

import java.util.regex.Pattern

/**
 * Orquestador de normalización de texto, restauración sistemática de diacríticos y clíticos rumanos,
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

    fun normalizeRomanianCedillas(text: String): String =
        RomanianDiacriticsRestorer.normalizeRomanianCedillas(text)

    /**
     * Restaura los signos diacríticos rumanos sobre el léxico léxicamente no ambiguo.
     */
    fun restoreRomanianDiacritics(text: String): String =
        RomanianDiacriticsRestorer.restore(
            text = text,
            includeCrossLingualShortWords = true,
            applySuffixRules = false
        )

    /**
     * Prepara texto informal de chat grupal rumano antes de enviarlo a ML Kit NMT:
     * colapsa alargamientos expresivos, restaura diacríticos por léxico/sufijos,
     * reconstruye clíticos y negaciones sin guion y protege siglas.
     */
    fun normalizeRomanianChatForTranslation(text: String): String {
        if (text.isBlank()) return text
        var result = normalizeRomanianCedillas(text)

        for ((pattern, canonical) in ELONGATED_WORD_RULES) {
            result = pattern.replace(result) { RomanianDiacriticsRestorer.matchCasing(it.value, canonical) }
        }

        // Por qué: Resuelve "față"/"fața" por contexto antes del diccionario léxico para no confundir "față-verso" con "fată".
        result = FATA_VERSO_REGEX.replace(result) { RomanianDiacriticsRestorer.matchCasing(it.value.take(4), "față") + "-verso" }
        result = SPATELE_FATA_REGEX.replace(result) { "${it.groupValues[1]}/${RomanianDiacriticsRestorer.matchCasing(it.groupValues[2], "fața")}" }
        result = FATA_SPATE_REGEX.replace(result) { "${RomanianDiacriticsRestorer.matchCasing(it.groupValues[1], "față")}/spate" }
        result = PREP_FATA_REGEX.replace(result) { "${it.groupValues[1]} față" }
        result = FATA_DE_REGEX.replace(result) { "${RomanianDiacriticsRestorer.matchCasing(it.groupValues[1], "față")} de" }

        // Por qué: Diferimos palabras cortas compartidas con inglés (`in`, `cat`, `dam`, `si`, `il`, `ii`) hasta confirmar contexto rumano.
        result = RomanianDiacriticsRestorer.restore(
            text = result,
            includeCrossLingualShortWords = false,
            applySuffixRules = false
        )
        result = RomanianCliticRestorer.restoreUnconditionalCliticsAndPhrases(result)

        if (RomanianCliticRestorer.hasRomanianContext(result)) {
            result = RomanianDiacriticsRestorer.restoreCrossLingualAndSuffixes(result)
            result = RomanianCliticRestorer.restoreContextGatedCliticsAndChat(result)
        }

        return result
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
