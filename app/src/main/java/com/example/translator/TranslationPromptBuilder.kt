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
 * del usuario fija su vocabulario preferido en cada inferencia.
 */
object TranslationPromptBuilder {

    const val MAX_CONTEXT_MESSAGES = 3
    const val MAX_CONTEXT_CHARS = 160
    const val MAX_PREFERRED_TERMS = 8

    fun build(text: String, targetLanguageName: String, hints: TranslationHints): String {
        val prompt = StringBuilder()

        val context = sanitizeContext(hints.contextMessages)
        if (context.isNotEmpty()) {
            prompt.append("Previous messages in this chat (context only, do not translate them):\n")
            context.forEach { prompt.append("- ").append(it).append('\n') }
            prompt.append('\n')
        }

        val terms = hints.preferredTerms.take(MAX_PREFERRED_TERMS)
        if (terms.isNotEmpty()) {
            prompt.append("Preferred wording in $targetLanguageName (always use the right-hand form instead of the left-hand form):\n")
            terms.forEach { prompt.append("- \"${it.rejected}\" -> \"${it.preferred}\"\n") }
            prompt.append('\n')
        }

        // Por qué: Plantilla directa recomendada por IndexTeam; sin pistas el prompt es idéntico al original.
        prompt.append("Translate the following text into $targetLanguageName, output only the translated result without any explanation:\n")
        prompt.append(text.trim())
        return prompt.toString()
    }

    /**
     * Detects outputs where the model translated or echoed the context instead of only the text.
     */
    fun hasContextLeak(output: String, input: String, hints: TranslationHints): Boolean {
        val cleanOutput = output.trim()
        val cleanInput = input.trim()

        val maxReasonableLength = maxOf(cleanInput.length * 3, cleanInput.length + 80)
        if (cleanOutput.length > maxReasonableLength) return true
        if (cleanOutput.contains('\n') && !cleanInput.contains('\n')) return true

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

