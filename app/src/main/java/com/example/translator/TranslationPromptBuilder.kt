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

    // Por qué: Caracteres de control invisibles, marcas direccionales de WhatsApp y carácter de reemplazo Unicode
    // que pueden corromper la tokenización del prompt o inyectar secuencias invisibles.
    private val INVISIBLE_AND_CONTROL_REGEX = Regex(
        """[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u200B\u200C\u200E\u200F\u202A-\u202E\u2060-\u206F\uFEFF\uFFFD]"""
    )

    // Por qué: Etiquetas de plantilla ChatML o delimitadores internos que nunca deben aparecer en texto de usuario
    private val PROMPT_CONTROL_TOKEN_REGEX = Regex(
        """(<\|[^|>]*\|>|<\s*/?\s*think\s*>|\[\s*(Source|Constraints|Hard Constraint|Note|Guardrail)\s*\])""",
        RegexOption.IGNORE_CASE
    )

    // Por qué: Detecta repeticiones descontroladas de caracteres especiales (6 o más símbolos idénticos seguidos)
    private val RUNAWAY_SPECIAL_CHAR_REGEX = Regex("""([^\p{L}\p{N}\s])\1{5,}""")

    /**
     * Limpia caracteres especiales de control, marcas invisibles e intentos de inyección de plantilla
     * antes de construir el prompt o enviar el texto al motor neuronal.
     */
    fun sanitizeSpecialCharacters(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace(INVISIBLE_AND_CONTROL_REGEX, "")
            .replace(PROMPT_CONTROL_TOKEN_REGEX, "")
            .replace(RUNAWAY_SPECIAL_CHAR_REGEX) { match ->
                val ch = match.value.first()
                ch.toString().repeat(3)
            }
            .trim()
    }

    fun build(text: String, targetLanguageName: String, hints: TranslationHints): String {
        val instruction = buildUserInstruction(text, targetLanguageName, hints)
        // Por qué: Index-Translate-2B (arquitectura Qwen3.5) requiere el bloque vacío <think>\n\n</think>\n\n
        // tras <|im_start|>assistant\n para desactivar el modo de razonamiento y emitir directamente la traducción.
        return "<|im_start|>user\n$instruction<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    fun buildUserInstruction(text: String, targetLanguageName: String, hints: TranslationHints): String {
        val cleanText = sanitizeSpecialCharacters(text)
        val context = sanitizeContext(hints.contextMessages)
        val terms = hints.preferredTerms.take(MAX_PREFERRED_TERMS)

        if (context.isEmpty() && terms.isEmpty()) {
            // Por qué: Plantilla oficial con guardrail explícito para preservar caracteres especiales, símbolos y números
            return "Translate the following text into $targetLanguageName. Preserve all numbers, emojis, @mentions, #hashtags, and special symbols intact, and output the translation directly, without quotes, markdown, or any explanation:\n\n$cleanText"
        }

        // Por qué: Plantilla restringida oficial (instTrans) con guardrails para glosario, contexto y caracteres especiales
        val constraints = mutableListOf<String>()
        if (terms.isNotEmpty()) {
            val mapping = terms.joinToString(", ") {
                "${sanitizeSpecialCharacters(it.rejected)} -> ${sanitizeSpecialCharacters(it.preferred)}"
            }
            constraints.add("[Hard Constraint] Terminology mapping: $mapping")
        }
        if (context.isNotEmpty()) {
            val joinedContext = context.joinToString(" | ")
            constraints.add("[Note] Domain context disambiguation: informal chat conversation. Preceding messages (context only, do not translate): $joinedContext")
        }
        constraints.add(
            "[Guardrail] Special characters: Preserve all numbers, emojis, @mentions, #hashtags, currency/math symbols (€, $, %, +, &, =, /), and line breaks exactly as they appear. Do not wrap the output in quotes, markdown, or tags, and do not invent or duplicate special characters."
        )

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
     * Strips Qwen3.5 `<think>...</think>` blocks, special chat tokens, and hallucinated wrapper characters
     * from raw model output while respecting wrappers that existed in [sourceText].
     */
    fun stripThink(raw: String, sourceText: String = ""): String {
        var cleaned = raw
            .replace(Regex("""<\|[^|>]*\|>"""), "")
            .replace(INVISIBLE_AND_CONTROL_REGEX, "")
            .replace('\u00A0', ' ')
            .trim()

        val closeTag = "</think>"
        val closeIndex = cleaned.lastIndexOf(closeTag, ignoreCase = true)
        if (closeIndex >= 0) {
            cleaned = cleaned.substring(closeIndex + closeTag.length).trim()
        }

        if (cleaned.startsWith("<think", ignoreCase = true)) {
            return ""
        }

        val trimmedSource = sourceText.trim()
        // Por qué: Solo elimina comillas o envoltorios de markdown si el mensaje original no los tenía
        if (!trimmedSource.startsWith("**") && cleaned.startsWith("**") && cleaned.endsWith("**") && cleaned.length > 4) {
            cleaned = cleaned.removePrefix("**").removeSuffix("**").trim()
        }
        if (!trimmedSource.startsWith("`") && cleaned.startsWith("`") && cleaned.endsWith("`") && cleaned.length > 2) {
            cleaned = cleaned.removePrefix("`").removeSuffix("`").trim()
        }
        if (!trimmedSource.startsWith("\"") && cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length > 2) {
            cleaned = cleaned.removePrefix("\"").removeSuffix("\"").trim()
        } else if (trimmedSource.isEmpty()) {
            cleaned = cleaned.removePrefix("\"").removeSuffix("\"").trim()
        }
        if (!trimmedSource.startsWith("«") && cleaned.startsWith("«") && cleaned.endsWith("»") && cleaned.length > 2) {
            cleaned = cleaned.removePrefix("«").removeSuffix("»").trim()
        } else if (trimmedSource.isEmpty()) {
            cleaned = cleaned.removePrefix("«").removeSuffix("»").trim()
        }
        if (!trimmedSource.startsWith("„") && cleaned.startsWith("„") && (cleaned.endsWith("”") || cleaned.endsWith("“")) && cleaned.length > 2) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }

        return cleaned.trim()
    }

    /**
     * Verifica si la salida contiene corrupción de caracteres especiales, etiquetas filtradas o repetición anómala.
     */
    fun hasSpecialCharacterCorruption(output: String, input: String): Boolean {
        if (output.contains('\uFFFD')) return true
        if (PROMPT_CONTROL_TOKEN_REGEX.containsMatchIn(output)) return true
        if (output.contains("```") && !input.contains("```")) return true
        if (RUNAWAY_SPECIAL_CHAR_REGEX.containsMatchIn(output) && !RUNAWAY_SPECIAL_CHAR_REGEX.containsMatchIn(input)) {
            return true
        }
        return false
    }

    /**
     * Detects outputs where the model translated or echoed the context instead of only the text.
     */
    fun hasContextLeak(output: String, input: String, hints: TranslationHints): Boolean {
        val cleanOutput = stripThink(output, input)
        val cleanInput = sanitizeSpecialCharacters(input)
        if (cleanOutput.isEmpty()) return true
        if (hasSpecialCharacterCorruption(output, input) && !output.contains("</think>", ignoreCase = true)) {
            return true
        }
        if (hasSpecialCharacterCorruption(cleanOutput, cleanInput)) return true

        val maxReasonableLength = maxOf(cleanInput.length * 3, cleanInput.length + 80)
        if (cleanOutput.length > maxReasonableLength) return true
        if (cleanOutput.contains('\n') && !cleanInput.contains('\n')) return true
        if (cleanOutput.contains("[Source]", ignoreCase = true) ||
            cleanOutput.contains("[Constraints]", ignoreCase = true) ||
            cleanOutput.contains("[Guardrail]", ignoreCase = true)
        ) {
            return true
        }

        return sanitizeContext(hints.contextMessages).any { message ->
            message.length >= 8 && cleanOutput.contains(message, ignoreCase = true)
        }
    }

    private fun sanitizeContext(messages: List<String>): List<String> {
        return messages
            .map { sanitizeSpecialCharacters(it.replace('\n', ' ')).take(MAX_CONTEXT_CHARS) }
            .filter { it.isNotBlank() }
            .takeLast(TranslationPromptBuilder.MAX_CONTEXT_MESSAGES)
    }
}
