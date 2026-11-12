package com.example.translator

/**
 * Extra knowledge passed to an engine for a single translation request.
 *
 * @property contextMessages Recent chat messages shown above the text, oldest first.
 * @property preferredTerms Wording the user has taught the app for this language pair.
 */
data class TranslationHints(
    val contextMessages: List<String> = emptyList(),
    val preferredTerms: List<TermCorrection> = emptyList()
) {
    val isEmpty: Boolean
        get() = contextMessages.isEmpty() && preferredTerms.isEmpty()

    companion object {
        val NONE = TranslationHints()
    }
}

/**
 * Builds the neural model prompt, injecting chat context and learned glossary terms.
 *
 * Por qué: El modelo no se reentrena en el teléfono (batería, memoria y olvido catastrófico);
 * en su lugar aprende "en contexto": los mensajes previos desambiguan el sentido y el glosario
 * del usuario fija su vocabulario preferido en cada inferencia usando las plantillas oficiales
 * de entrenamiento de Index-Translate-2B.
 */
object TranslationPromptBuilder {

    const val MAX_CONTEXT_MESSAGES = 3
    const val MAX_CONTEXT_CHARS = 160
    const val MAX_PREFERRED_TERMS = 8

    fun build(text: String, targetLanguageName: String, hints: TranslationHints): String {
        val instruction = buildUserInstruction(text, targetLanguageName, hints)
        // Por qué: Index-Translate-2B (arquitectura Qwen3.5) requiere el bloque vacío <think>\n\n</think>\n\n
        // tras <|im_start|>assistant\n para desactivar el modo de razonamiento y emitir directamente la traducción.
        return "<|im_start|>user\n$instruction<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    fun buildUserInstruction(text: String, targetLanguageName: String, hints: TranslationHints): String {
        val cleanText = text.trim()
        val context = sanitizeContext(hints.contextMessages)
        val terms = hints.preferredTerms.take(MAX_PREFERRED_TERMS)

        if (context.isEmpty() && terms.isEmpty()) {
            // Por qué: Plantilla simple oficial de entrenamiento de Index-Translate-2B
            return "Translate the following text into $targetLanguageName. Output the translation directly, without any explanation:\n\n$cleanText"
        }

        // Por qué: Plantilla restringida oficial (instTrans) de Index-Translate-2B para glosario y contexto
        val constraints = mutableListOf<String>()
        if (terms.isNotEmpty()) {
            val mapping = terms.joinToString(", ") { "${it.rejected} -> ${it.preferred}" }
            constraints.add("[Hard Constraint] Terminology mapping: $mapping")
        }
        if (context.isNotEmpty()) {
            val joinedContext = context.joinToString(" | ")
            constraints.add("[Note] Domain context disambiguation: informal chat conversation. Preceding messages (context only, do not translate): $joinedContext")
        }

        return buildString {
            append("Please translate the following text into $targetLanguageName and strictly follow all constraints.\n\n")
            append("[Source]\n")
            append(cleanText)
            append("\n\n[Constraints]\n")
            constraints.forEachIndexed { index, constraint ->
                append("${index + 1}. $constraint\n")
            }
            append("\nOutput only the translation without any explanation.")
        }
    }

    /**
     * Strips Qwen3.5 `<think>...</think>` blocks and special chat tokens from raw model output.
     *
     * Por qué: Si por cualquier motivo el modelo emite etiquetas de razonamiento o marcadores de fin de turno,
     * se eliminan antes de validar la traducción; si un bloque `<think>` quedó sin cerrar por límite de tokens,
     * se devuelve cadena vacía para activar el respaldo limpio.
     */
    fun stripThink(raw: String): String {
        var cleaned = raw
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .trim()

        val closeTag = "</think>"
        val closeIndex = cleaned.lastIndexOf(closeTag, ignoreCase = true)
        if (closeIndex >= 0) {
            cleaned = cleaned.substring(closeIndex + closeTag.length).trim()
        }

        if (cleaned.startsWith("<think", ignoreCase = true)) {
            return ""
        }

        return cleaned
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("«").removeSuffix("»")
            .trim()
    }

    /**
     * Detects outputs where the model translated or echoed the context instead of only the text.
     */
    fun hasContextLeak(output: String, input: String, hints: TranslationHints): Boolean {
        val cleanOutput = stripThink(output)
        val cleanInput = input.trim()
        if (cleanOutput.isEmpty()) return true

        val maxReasonableLength = maxOf(cleanInput.length * 3, cleanInput.length + 80)
        if (cleanOutput.length > maxReasonableLength) return true
        if (cleanOutput.contains('\n') && !cleanInput.contains('\n')) return true
        if (cleanOutput.contains("[Source]", ignoreCase = true) ||
            cleanOutput.contains("[Constraints]", ignoreCase = true)
        ) {
            return true
        }

        return sanitizeContext(hints.contextMessages).any { message ->
            message.length >= 8 && cleanOutput.contains(message, ignoreCase = true)
        }
    }

    private fun sanitizeContext(messages: List<String>): List<String> {
        return messages
            .map { it.replace('\n', ' ').trim().take(MAX_CONTEXT_CHARS) }
            .filter { it.isNotBlank() }
            .takeLast(MAX_CONTEXT_MESSAGES)
    }
}
