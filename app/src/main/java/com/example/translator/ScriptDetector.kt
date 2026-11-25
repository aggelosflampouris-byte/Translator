package com.example.translator

/**
 * Utilidades compartidas de detección de alfabeto (griego, latino, diacríticos rumanos) y URLs.
 *
 * Por qué: Centraliza en un único lugar los conteos de caracteres y patrones de enlaces que antes
 * estaban duplicados en seis clases distintas.
 */
object ScriptDetector {

    private const val ROMANIAN_DIACRITICS = "ăâîșțĂÂÎȘȚ"

    private val URL_OR_DOMAIN_REGEX = Regex(
        """(https?://|www\.|\.com\b|\.org\b|\.net\b|\.ly/|bit\.ly|waze\.com|facebook\.com|instagram\.com|tiktok\.com|twitter\.com|youtube\.com|/share/)""",
        RegexOption.IGNORE_CASE
    )
    private val URL_TOKEN_REGEX = Regex("""(?i)(?:[a-z][a-z0-9+.-]*://\S+|www\.\S+)""")
    private val DOMAIN_TOKEN_REGEX = Regex(
        """(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)+\.(?:com|org|net|ro|gr|io|ly|gg|me|tv|co|uk|de|fr)\S*|\b(?:bit\.ly|vm\.tiktok\.com|youtu\.be|t\.me|wa\.me)/\S*"""
    )

    fun isGreekLetter(ch: Char): Boolean {
        return (ch in '\u0370'..'\u03FF' || ch in '\u1F00'..'\u1FFF') && ch.isLetter()
    }

    fun isLatinLetter(ch: Char): Boolean {
        return ch in 'a'..'z' || ch in 'A'..'Z' || ch in ROMANIAN_DIACRITICS || ch in "şţŞŢ"
    }

    fun countGreek(text: CharSequence): Int {
        var count = 0
        for (i in 0 until text.length) {
            if (isGreekLetter(text[i])) count++
        }
        return count
    }

    fun countLatin(text: CharSequence): Int {
        var count = 0
        for (i in 0 until text.length) {
            if (isLatinLetter(text[i])) count++
        }
        return count
    }

    fun hasGreek(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            if (isGreekLetter(text[i])) return true
        }
        return false
    }

    fun hasLatin(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            if (isLatinLetter(text[i])) return true
        }
        return false
    }

    fun hasRomanianDiacritics(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            if (text[i] in ROMANIAN_DIACRITICS) return true
        }
        return false
    }

    fun containsUrlOrDomain(text: CharSequence): Boolean {
        return URL_OR_DOMAIN_REGEX.containsMatchIn(text)
    }

    fun stripUrls(text: CharSequence): String {
        val withoutScheme = URL_TOKEN_REGEX.replace(text, " ")
        return DOMAIN_TOKEN_REGEX.replace(withoutScheme, " ").trim()
    }

    /**
     * Indica si el texto es únicamente un enlace o dominio sin suficiente mensaje legible alrededor.
     */
    fun isOnlyUrlOrDomain(text: CharSequence): Boolean {
        if (!containsUrlOrDomain(text) && !URL_TOKEN_REGEX.containsMatchIn(text)) return false
        val remainder = stripUrls(text)
        var letterCount = 0
        for (i in 0 until remainder.length) {
            if (remainder[i].isLetter()) {
                letterCount++
                if (letterCount >= 2) return false
            }
        }
        return true
    }
}

