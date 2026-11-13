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
    fun detectScriptType_identifiesGreekWithLatinName() {
        val type = TextNormalizer.detectScriptType("Γεια John")
        assertEquals(TextNormalizer.ScriptType.GREEK, type)
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

    @Test
    fun protectAndRestoreEmojis_protectsMentionsAndUnicodeHashtagsWithoutUnsupportedFlags() {
        val input = "Salut @giannis_99 #καλημέρα 😊"
        val protected = TextNormalizer.protectEmojis(input)
        assertEquals(listOf("@giannis_99", "#καλημέρα", "😊"), protected.tokens)
        val restored = TextNormalizer.restoreEmojis(protected.text, protected.tokens)
        assertEquals(input, restored)
    }

    @Test
    fun protectAndRestoreEmojis_protectsTildeGroupMention() {
        val input = "Multumesc @~Geala Stefan"
        val protected = TextNormalizer.protectEmojis(input)
        assertEquals(listOf("@~Geala"), protected.tokens)
        assertEquals(input, TextNormalizer.restoreEmojis(protected.text, protected.tokens))
    }

    @Test
    fun normalizeRomanianChatForTranslation_handlesSlangPhrasesCedillasAndAcronyms() {
        assertEquals(
            "îți dau eu cartela să intri când vrei tu și gata",
            TextNormalizer.normalizeRomanianChatForTranslation("iti dau eu cartela sa intrii cand vr tu si gt")
        )
        assertEquals(
            "e cineva anul 2 care a făcut LA cu prof?",
            TextNormalizer.normalizeRomanianChatForTranslation("e cnv anu 2 care a fc LA cu prof?")
        )
        assertEquals(
            "Și să fie ștampilate",
            TextNormalizer.normalizeRomanianChatForTranslation("Si sa die stampilate")
        )
        assertEquals(
            "Să fie bine, nu mă chinui, o să vezi",
            TextNormalizer.normalizeRomanianChatForTranslation("Sa die bine, nu ma chinui, o sa vezi")
        )
        assertEquals(
            "Cred că legitimația și niște șuruburi",
            TextNormalizer.normalizeRomanianChatForTranslation("Cred ca legitimatia si niste şuruburi")
        )
        assertEquals(
            "Te pup stefan mulțumesc",
            TextNormalizer.normalizeRomanianChatForTranslation("Te pup stefan ms")
        )
        assertEquals(
            "Durează 10 ms și 5 pt cu VR și GT",
            TextNormalizer.normalizeRomanianChatForTranslation("Durează 10 ms și 5 pt cu VR și GT")
        )
    }
}
