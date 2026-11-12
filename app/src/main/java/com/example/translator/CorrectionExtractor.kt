package com.example.translator

/**
 * A learned preference: the engine produced [rejected], the user replaced it with [preferred].
 * Both values are stored lowercase and without surrounding punctuation.
 */
data class TermCorrection(val rejected: String, val preferred: String)

/**
 * Result of comparing a machine draft with the text the user actually sent.
 */
sealed class DraftFeedback {
    /** The user sent the draft without changes. */
    object Accepted : DraftFeedback()

    /** The user lightly edited the draft. [corrections] holds the word-level substitutions. */
    data class Edited(val corrections: List<TermCorrection>) : DraftFeedback()

    /** The sent text is too different from the draft to be a correction of it. */
    object Unrelated : DraftFeedback()
}

/**
 * Pure word-level diff logic used by the adaptive learning loop.
 *
 * Por qué: Se aísla de Android para poder probarla con pruebas unitarias y mantener
 * el servicio de accesibilidad libre de lógica algorítmica.
 */
object CorrectionExtractor {

    // Por qué: Una corrección real de vocabulario afecta a pocas palabras; bloques mayores son reescrituras.
    private const val MAX_SPAN_TOKENS = 3

    // Por qué: Si menos del 40% de las palabras coinciden, el usuario escribió otro mensaje distinto.
    private const val MIN_OVERLAP_RATIO = 0.4

    // Por qué: Limita el coste O(n·m) de la tabla LCS en mensajes extremadamente largos.
    private const val MAX_TOKENS = 120

    private val WHITESPACE = Regex("\\s+")

    fun analyze(machineDraft: String, sentText: String): DraftFeedback {
        val machineTokens = tokenize(machineDraft)
        val sentTokens = tokenize(sentText)

        if (machineTokens.isEmpty() || sentTokens.isEmpty()) return DraftFeedback.Unrelated
        if (machineTokens.size > MAX_TOKENS || sentTokens.size > MAX_TOKENS) return DraftFeedback.Unrelated
        if (machineTokens == sentTokens) return DraftFeedback.Accepted

        val lcsTable = buildLcsTable(machineTokens, sentTokens)
        val commonCount = lcsTable[0][0]
        val overlapRatio = commonCount.toDouble() / maxOf(machineTokens.size, sentTokens.size)
        if (overlapRatio < MIN_OVERLAP_RATIO) return DraftFeedback.Unrelated

        val corrections = extractReplacementBlocks(machineTokens, sentTokens, lcsTable)
        return DraftFeedback.Edited(corrections)
    }

    /**
     * Splits text into lowercase word tokens, trimming punctuation at both ends of each word.
     */
    fun tokenize(text: String): List<String> {
        return text.trim()
            .split(WHITESPACE)
            .map { stripEdgePunctuation(it).lowercase() }
            .filter { token -> token.any { it.isLetterOrDigit() } }
    }

    private fun stripEdgePunctuation(word: String): String {
        return word.trim { !it.isLetterOrDigit() }
    }

    /**
     * Suffix-based LCS table: table[i][j] = length of the LCS of a[i..] and b[j..].
     */
    private fun buildLcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                table[i][j] = if (a[i] == b[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }
        return table
    }

    /**
     * Walks the LCS alignment and collects spans where machine words were replaced by user words.
     */
    private fun extractReplacementBlocks(
        machine: List<String>,
        sent: List<String>,
        table: Array<IntArray>
    ): List<TermCorrection> {
        val corrections = mutableListOf<TermCorrection>()
        val removed = mutableListOf<String>()
        val inserted = mutableListOf<String>()

        fun flushBlock() {
            buildCorrection(removed, inserted)?.let { corrections.add(it) }
            removed.clear()
            inserted.clear()
        }

        var i = 0
        var j = 0
        while (i < machine.size && j < sent.size) {
            when {
                machine[i] == sent[j] -> {
                    flushBlock()
                    i++
                    j++
                }
                table[i + 1][j] >= table[i][j + 1] -> removed.add(machine[i++])
                else -> inserted.add(sent[j++])
            }
        }
        while (i < machine.size) removed.add(machine[i++])
        while (j < sent.size) inserted.add(sent[j++])
        flushBlock()

        return corrections.distinctBy { it.rejected }
    }

    private fun buildCorrection(removed: List<String>, inserted: List<String>): TermCorrection? {
        // Por qué: Solo una sustitución (palabra quitada + palabra puesta) indica una preferencia léxica;
        // inserciones o borrados puros suelen ser cambios de contenido, no de traducción.
        if (removed.isEmpty() || inserted.isEmpty()) return null
        if (removed.size > MAX_SPAN_TOKENS || inserted.size > MAX_SPAN_TOKENS) return null

        val rejected = removed.joinToString(" ")
        val preferred = inserted.joinToString(" ")
        if (rejected == preferred) return null
        if (rejected.none { it.isLetter() } || preferred.none { it.isLetter() }) return null

        return TermCorrection(rejected = rejected, preferred = preferred)
    }
}

/**
 * Applies learned corrections to a finished translation, respecting word boundaries and capitalization.
 */
object CorrectionApplier {

    fun apply(text: String, corrections: List<TermCorrection>): String {
        if (text.isBlank() || corrections.isEmpty()) return text

        var result = text
        // Por qué: Se aplican primero las frases más largas para que no las rompa una corrección de una sola palabra.
        for (correction in corrections.sortedByDescending { it.rejected.length }) {
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(correction.rejected) + "(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE
            )
            result = pattern.replace(result) { match ->
                matchCapitalization(match.value, correction.preferred)
            }
        }
        return result
    }

    private fun matchCapitalization(original: String, replacement: String): String {
        val startsUppercase = original.firstOrNull()?.isUpperCase() == true
        return if (startsUppercase) {
            replacement.replaceFirstChar { it.titlecase() }
        } else {
            replacement
        }
    }
}

/**
 * Detects the moment a chat input is sent: the field jumps from a real message straight to empty.
 *
 * Por qué: Al borrar con la tecla de retroceso el texto pasa por longitudes intermedias (2 → 1 → 0),
 * mientras que al enviar WhatsApp vacía el campo de golpe. Esta diferencia evita falsos positivos.
 */
class SentMessageDetector(private val minSentLength: Int = 2) {

    private var lastText: String = ""

    /**
     * Feeds the current input text. Returns the sent message when a send is detected, otherwise null.
     */
    fun observe(currentText: String): String? {
        val current = currentText.trim()
        val previous = lastText
        lastText = current
        return if (current.isEmpty() && previous.length >= minSentLength) previous else null
    }

    fun reset() {
        lastText = ""
    }
}

