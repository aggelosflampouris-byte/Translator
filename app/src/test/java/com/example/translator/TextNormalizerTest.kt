package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun detectScriptType_identifiesGreekCorrectly() {
        val type = TextNormalizer.detectScriptType("Γεια σου, τι κανεις;")
        assertEquals(TextNormalizer.ScriptType.GREEK, type)
    }

    @Test
    fun detectScriptType_identifiesLatinCorrectly() {
        val type = TextNormalizer.detectScriptType("Ce faci, esti bine?")
        assertEquals(TextNormalizer.ScriptType.LATIN, type)
    }

    @Test
    fun detectScriptType_returnsNeutralForPunctuationOnly() {
        val type = TextNormalizer.detectScriptType("123 ??? 😊")
        assertEquals(TextNormalizer.ScriptType.NEUTRAL, type)
    }

    @Test
    fun restoreRomanianDiacritics_preservesLowercase() {
        val input = "ce faci, esti acasa si nu stiu?"
        val output = TextNormalizer.restoreRomanianDiacritics(input)
        assertEquals("ce faci, ești acasă și nu știu?", output)
    }

    @Test
    fun restoreRomanianDiacritics_preservesTitlecase() {
        val input = "Maine Vin Si Eu Acasa."
        val output = TextNormalizer.restoreRomanianDiacritics(input)
        assertEquals("Mâine Vin Și Eu Acasă.", output)
    }

    @Test
    fun restoreRomanianDiacritics_preservesUppercase() {
        val input = "NOAPTE BUNA, MULTUMESC!"
        val output = TextNormalizer.restoreRomanianDiacritics(input)
        assertEquals("NOAPTE BUNĂ, MULȚUMESC!", output)
    }

    @Test
    fun protectAndRestoreEmojis_roundtripsAccurately() {
        val input = "Salut prietene! 😊 Cum esti? 🔥👍"
        val protected = TextNormalizer.protectEmojis(input)

        assertEquals("Salut prietene! __EMOJI_0__ Cum esti? __EMOJI_1__", protected.text)
        assertEquals(listOf("😊", "🔥👍"), protected.tokens)

        // Simulate translation preserving markers or having small spaces
        val simulatedTranslation = "Γεια σου φιλε! __EMOJI_0__ Πως εισαι; __EMOJI_1__"
        val restored = TextNormalizer.restoreEmojis(simulatedTranslation, protected.tokens)

        assertEquals("Γεια σου φιλε! 😊 Πως εισαι; 🔥👍", restored)
    }
}
