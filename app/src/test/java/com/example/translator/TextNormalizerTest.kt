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
        val input = "Salut prietene! 😊 Cum esti? 🔥👍 Într-un înger"
        val protected = TextNormalizer.protectEmojis(input)

        // Verifies hyphen in Într-un is untouched
        assert(protected.text.contains("Într-un"))
        assertEquals(listOf("😊", "🔥👍"), protected.tokens)

        // Simulated translation preserving markers
        val pua0 = (0xE000).toChar()
        val pua1 = (0xE001).toChar()
        val simulatedTranslation = "Γεια σου φιλε! $pua0 Πως εισαι; $pua1 Σε εναν αγγελο"
        val restored = TextNormalizer.restoreEmojis(simulatedTranslation, protected.tokens)

        assertEquals("Γεια σου φιλε! 😊 Πως εισαι; 🔥👍 Σε εναν αγγελο", restored)
    }
}
