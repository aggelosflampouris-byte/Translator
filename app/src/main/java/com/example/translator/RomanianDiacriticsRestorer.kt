package com.example.translator

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Restaurador sistemático de diacríticos rumanos basado en un léxico léxicamente no ambiguo
 * y reglas morfológicas deterministas de sufijo.
 *
 * Por qué: ML Kit está entrenado con rumano acentuado; restaurar diacríticos en palabras no vistas
 * reduce drásticamente las traducciones erróneas sin depender de una lista manual interminable
 * ni alterar palabras inglesas válidas.
 */
object RomanianDiacriticsRestorer {

    private const val RESOURCE_PATH = "/ro_diacritics_unambiguous.tsv"

    private val WORD_BOUNDARY_REGEX = Regex("""(?i)\b([a-zăâîșț]+(-[a-zăâîșț]+)?)\b""")

    private val CROSS_LINGUAL_SHORT_WORDS = mapOf(
        "si" to "și",
        "in" to "în",
        "cat" to "cât",
        "dam" to "dăm",
        "il" to "îl",
        "ii" to "îi"
    )

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

    // Por qué: Ordenados de mayor a menor longitud de sufijo para evitar coincidencias parciales;
    // se omiten sufijos ambiguos como `-este` (este/peste/poveste) o prefijos como `in-` (internet).
    private val DETERMINISTIC_SUFFIX_RULES: List<Pair<String, String>> = listOf(
        "atiile" to "ațiile",
        "tiunile" to "țiunile",
        "tiunii" to "țiunii",
        "tiunea" to "țiunea",
        "unctie" to "uncție",
        "unctia" to "uncția",
        "unctii" to "uncții",
        "tiune" to "țiune",
        "tiuni" to "țiuni",
        "ectie" to "ecție",
        "ectia" to "ecția",
        "ectii" to "ecții",
        "atie" to "ație",
        "atia" to "ația",
        "atii" to "ații",
        "utie" to "uție",
        "utia" to "uția",
        "utii" to "uții",
        "itie" to "iție",
        "itia" to "iția",
        "itii" to "iții",
        "eaza" to "ează"
    )

    private val SUFFIX_SKIP_WORDS = setOf(
        "internet", "este", "peste", "poveste", "veste", "teste", "create",
        "update", "website", "elite", "suite", "favorite", "satellite"
    )

    internal val unambiguousLexicon: Map<String, String> by lazy { loadLexicon() }

    private fun loadLexicon(): Map<String, String> {
        val stream = RomanianDiacriticsRestorer::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: return emptyMap()
        val map = LinkedHashMap<String, String>(1200)
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val tabIdx = line.indexOf('\t')
                if (tabIdx <= 0 || tabIdx >= line.length - 1) return@forEachLine
                val key = line.substring(0, tabIdx).trim().lowercase()
                val value = line.substring(tabIdx + 1).trim().lowercase()
                if (key.isNotEmpty() && value.isNotEmpty() && key != value) {
                    map[key] = value
                }
            }
        }
        return map
    }

    internal fun lexiconSize(): Int = unambiguousLexicon.size

    fun normalizeRomanianCedillas(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace('ş', 'ș')
            .replace('Ş', 'Ș')
            .replace('ţ', 'ț')
            .replace('Ţ', 'Ț')
    }

    /**
     * Restaura diacríticos rumanos sobre palabras del léxico no ambiguo y frases multipalabra.
     *
     * Por qué: `includeCrossLingualShortWords` permite diferir palabras cortas que colisionan con
     * inglés (`in`, `cat`, `dam`, `si`, `il`, `ii`) hasta confirmar contexto rumano real.
     */
    fun restore(
        text: String,
        includeCrossLingualShortWords: Boolean = true,
        applySuffixRules: Boolean = false
    ): String {
        if (text.isBlank()) return text
        var result = normalizeRomanianCedillas(text)

        result = WORD_BOUNDARY_REGEX.replace(result) { match ->
            val word = match.value
            val lower = word.lowercase()
            val isAllUpper = word.length > 1 && word == word.uppercase()

            val crossLingual = if (includeCrossLingualShortWords) {
                when {
                    (lower == "il" || lower == "ii") && word == word.uppercase() -> null
                    else -> CROSS_LINGUAL_SHORT_WORDS[lower]
                }
            } else {
                null
            }

            val lexical = crossLingual ?: unambiguousLexicon[lower]
            val resolved = lexical ?: if (applySuffixRules && !isAllUpper) {
                restoreByDeterministicSuffix(lower)
            } else {
                null
            }

            if (resolved != null) matchCasing(word, resolved) else word
        }

        for ((pattern, withDiacritics) in MULTI_WORD_DIACRITIC_RULES) {
            result = pattern.replace(result) { match ->
                matchCasing(match.value, withDiacritics)
            }
        }
        return result
    }

    /**
     * Aplica palabras cortas compartidas con inglés (`în`, `și`, `cât`, `dăm`, `îl`, `îi`)
     * y sufijos morfológicos una vez confirmado el contexto rumano del mensaje.
     */
    fun restoreCrossLingualAndSuffixes(text: String): String {
        if (text.isBlank()) return text
        return WORD_BOUNDARY_REGEX.replace(text) { match ->
            val word = match.value
            val lower = word.lowercase()
            val isAllUpper = word.length > 1 && word == word.uppercase()

            val cross = when {
                (lower == "il" || lower == "ii") && word == word.uppercase() -> null
                else -> CROSS_LINGUAL_SHORT_WORDS[lower]
            }
            val resolved = cross ?: if (!isAllUpper && !ScriptDetector.hasRomanianDiacritics(lower)) {
                restoreByDeterministicSuffix(lower)
            } else {
                null
            }
            if (resolved != null) matchCasing(word, resolved) else word
        }
    }

    private fun restoreByDeterministicSuffix(lowerWord: String): String? {
        if (lowerWord.length < 6 || lowerWord.contains('-') || lowerWord in SUFFIX_SKIP_WORDS) {
            return null
        }
        for ((plainSuffix, accentedSuffix) in DETERMINISTIC_SUFFIX_RULES) {
            if (lowerWord.endsWith(plainSuffix) && lowerWord.length > plainSuffix.length + 1) {
                return lowerWord.substring(0, lowerWord.length - plainSuffix.length) + accentedSuffix
            }
        }
        return null
    }

    internal fun matchCasing(original: String, replacement: String): String {
        val letters = original.filter { it.isLetter() }
        return when {
            letters.isNotEmpty() && letters.all { it.isUpperCase() } -> replacement.uppercase()
            original.firstOrNull()?.isUpperCase() == true -> {
                replacement.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            else -> replacement.lowercase()
        }
    }
}

