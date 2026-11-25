package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun normalizeRomanianChatForTranslation_handlesGroupChatCliticsAcronymsAndFataContext() {
        assertEquals(
            "Poza/scan față-verso cu cartela",
            TextNormalizer.normalizeRomanianChatForTranslation("Poza/scan fata verso cu cartela")
        )
        assertEquals(
            "de ce cer și spatele/fața",
            TextNormalizer.normalizeRomanianChatForTranslation("de ce cer si spatele/fata")
        )
        assertEquals(
            "De ce nu avem și noi stand de votat aici la rectorat",
            TextNormalizer.normalizeRomanianChatForTranslation("De ce n avem si noi stand de votat aici la rectorat")
        )
        assertEquals(
            "El așa s-a lăudat, dacă ne-a mințit ghinion pentru noi",
            TextNormalizer.normalizeRomanianChatForTranslation("El asa s a lăudat, dacă ne a mințit ghinion pt noi")
        )
        assertEquals(
            "Edi e șef de cămin la P10 anul ăsta?",
            TextNormalizer.normalizeRomanianChatForTranslation("Edi e sef de camin la p10 anu asta?")
        )
        assertEquals(
            "îl duceți la admina până vineri",
            TextNormalizer.normalizeRomanianChatForTranslation("il duceti la admina pana vineri")
        )
        assertEquals(
            "Că acum am permisiune",
            TextNormalizer.normalizeRomanianChatForTranslation("Ca acum am permisiune")
        )
        assertEquals(
            "O să completați un acord. Asta când o să se afișeze listele",
            TextNormalizer.normalizeRomanianChatForTranslation("O sa completati un acord. Asta cand or sa se afiseze listele")
        )
        assertEquals(
            "Bună",
            TextNormalizer.normalizeRomanianChatForTranslation("Bunaa")
        )
        assertEquals(
            "La ACS știu că se duc fizic",
            TextNormalizer.normalizeRomanianChatForTranslation("La acs stiu ca se duc fizic")
        )
        assertEquals(
            "Capitolul II din IL",
            TextNormalizer.restoreRomanianDiacritics("Capitolul II din IL")
        )
        assertEquals(
            "He's a student and it's a book",
            TextNormalizer.normalizeRomanianChatForTranslation("He's a student and it's a book")
        )

        val detached = TextNormalizer.detachLeadingMentions("@~Edward vi pe la noi sa îți dam contractul?")
        assertEquals("@~Edward ", detached.prefix)
        assertEquals(
            "vii pe la noi să îți dăm contractul?",
            TextNormalizer.normalizeRomanianChatForTranslation(detached.body)
        )

        val multiWordPush = TextNormalizer.detachLeadingMentions("@~Vlad Chintoiu poza fata verso")
        assertEquals("@~Vlad Chintoiu ", multiWordPush.prefix)
        assertEquals("poza fata verso", multiWordPush.body)

        val unicodePush = TextNormalizer.detachLeadingMentions("@~Ștefan ne vedem maine")
        assertEquals("@~Ștefan ", unicodePush.prefix)
        assertEquals("ne vedem maine", unicodePush.body)

        val initialPush = TextNormalizer.detachLeadingMentions("@~Stefania V ai vazut?")
        assertEquals("@~Stefania V ", initialPush.prefix)
        assertEquals("ai vazut?", initialPush.body)

        val starterGuard = TextNormalizer.detachLeadingMentions("@~Edward Ce faci azi?")
        assertEquals("@~Edward ", starterGuard.prefix)
        assertEquals("Ce faci azi?", starterGuard.body)

        assertEquals(
            "Și durează 500 ms sau 12 pt",
            TextNormalizer.normalizeRomanianChatForTranslation("Si durează 500 ms sau 12 pt")
        )
        assertEquals(
            "Vă învață stefania",
            TextNormalizer.normalizeRomanianChatForTranslation("Va invata stefania")
        )
        assertEquals(
            "Poate mai durează să se actualizeze",
            TextNormalizer.normalizeRomanianChatForTranslation("Poate mai dureaza sa se updateze")
        )
        assertEquals(
            "Perfect hai că vin și eu să fur câte ceva",
            TextNormalizer.normalizeRomanianChatForTranslation("Perfect hai ca vin si eu sa fur cate ceva")
        )
        assertEquals(
            "Nu știu cum este la noi",
            TextNormalizer.normalizeRomanianChatForTranslation("Nu stiu cum e la noi")
        )
        assertEquals(
            "Bine bă misule",
            TextNormalizer.normalizeRomanianChatForTranslation("Bineee baaa misule")
        )
        assertEquals(
            "probabil că ai vrut să scrii ianuarie în loc de februarie",
            TextNormalizer.normalizeRomanianChatForTranslation("probabil ca ai vrut sa scri ianuarie in loc de februarie")
        )

        val urlMessage = "‼️ Va invitam sa urmăriți colegii noștri 🤗 https://vm.tiktok.com/ZN8PxdsHD/"
        val protectedUrl = TextNormalizer.protectEmojis(urlMessage)
        assertTrue(protectedUrl.tokens.contains("https://vm.tiktok.com/ZN8PxdsHD/"))
        assertEquals(urlMessage, TextNormalizer.restoreEmojis(protectedUrl.text, protectedUrl.tokens))
    }
}
