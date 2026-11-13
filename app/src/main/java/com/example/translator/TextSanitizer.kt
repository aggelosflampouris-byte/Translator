package com.example.translator

/**
 * Conocimiento adicional enviado al motor para una solicitud de traducción puntual.
 *
 * @property preferredTerms Expresiones o traducciones enseñadas por el usuario para este par de idiomas.
 */
data class TranslationHints(
    val preferredTerms: List<TermCorrection> = emptyList()
) {
    val isEmpty: Boolean
        get() = preferredTerms.isEmpty()

    companion object {
        val NONE = TranslationHints()
    }
}

/**
 * Saneamiento determinista de caracteres especiales, marcas de control invisibles
 * y envoltorios artificiales antes y después de la traducción.
 */
object TextSanitizer {

    // Por qué: Caracteres de control invisibles, marcas direccionales de chat y carácter de reemplazo Unicode
    // que pueden corromper la tokenización o introducir secuencias invisibles en el texto traducido.
    private val INVISIBLE_AND_CONTROL_REGEX = Regex(
        """[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u200B\u200C\u200E\u200F\u202A-\u202E\u2060-\u206F\uFEFF\uFFFD]"""
    )

    // Por qué: Delimitadores de plantilla o etiquetas técnicas que nunca deben aparecer en un mensaje de usuario.
    private val CONTROL_TAG_REGEX = Regex(
        """(<\|[^|>]*\|>|<\s*/?\s*think\s*>|\[\s*(Source|Constraints|Hard Constraint|Note|Guardrail)\s*\])""",
        RegexOption.IGNORE_CASE
    )

    // Por qué: Detecta repeticiones descontroladas de caracteres especiales (6 o más símbolos idénticos seguidos).
    private val RUNAWAY_SPECIAL_CHAR_REGEX = Regex("""([^\p{L}\p{N}\s])\1{5,}""")

    /**
     * Limpia caracteres especiales de control, marcas invisibles y repeticiones excesivas de símbolos.
     */
    fun sanitizeSpecialCharacters(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace(INVISIBLE_AND_CONTROL_REGEX, "")
            .replace(CONTROL_TAG_REGEX, "")
            .replace(RUNAWAY_SPECIAL_CHAR_REGEX) { match ->
                val ch = match.value.first()
                ch.toString().repeat(3)
            }
            .trim()
    }

    /**
     * Elimina comillas o envoltorios de markdown añadidos por el motor si el mensaje original no los tenía.
     */
    fun stripWrappingQuotes(raw: String, sourceText: String = ""): String {
        var cleaned = raw
            .replace(CONTROL_TAG_REGEX, "")
            .replace(INVISIBLE_AND_CONTROL_REGEX, "")
            .replace('\u00A0', ' ')
            .trim()

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
        if (CONTROL_TAG_REGEX.containsMatchIn(output)) return true
        if (output.contains("```") && !input.contains("```")) return true
        if (RUNAWAY_SPECIAL_CHAR_REGEX.containsMatchIn(output) && !RUNAWAY_SPECIAL_CHAR_REGEX.containsMatchIn(input)) {
            return true
        }
        return false
    }
}

