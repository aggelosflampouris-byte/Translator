package com.example.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderPrefixCleanerTest {

    @Test
    fun extractPartnerNamesFromHeaderTexts_ignoresStatusAndTruncatedSingleTokens() {
        val headerTexts = listOf(
            "Πίσω",
            "Λευτέρης Φλαμ...\nΕνεργός/ή τώρα",
            "Λευτέρης Φλαμπούρης",
            "Φωνητική κλήση",
            "Βιντεοκλήση",
            "Πληροφορίες",
            "Κωνσταν..."
        )

        val names = SenderPrefixCleaner.extractPartnerNamesFromHeaderTexts(headerTexts)
        assertTrue(names.contains("λευτερης φλαμπουρης"))
        assertTrue(names.contains("λευτερης"))
        assertFalse(names.contains("λευτερης φλαμ"))
        assertFalse(names.contains("κωνσταν"))
        assertFalse(names.contains("ενεργος/η τωρα"))
        assertFalse(names.contains("φωνητικη κληση"))
    }

    @Test
    fun discoverSideSenders_andStripSenderPrefix_cleansMessengerSelfAndPartnerDirectionalOnly() {
        val screenWidth = 1080
        val samples = listOf(
            SenderPrefixCleaner.BubbleTextSample("ΑΓΓΕΛΟΣ, Κάνω μια δοκιμή", left = 424, right = 1057),
            SenderPrefixCleaner.BubbleTextSample("ΑΓΓΕΛΟΣ, Bună ziua mâine am un curs", left = 330, right = 1057),
            SenderPrefixCleaner.BubbleTextSample("ΑΓΓΕΛΟΣ, Τίποτα", left = 780, right = 1057),
            SenderPrefixCleaner.BubbleTextSample("Λευτέρης Φλαμπούρης, Τι πρεπει να κανω εγω", left = 130, right = 630),
            SenderPrefixCleaner.BubbleTextSample("Λευτέρης, Elena, hai la cafea?", left = 130, right = 600)
        )
        val headerPartnerNames = SenderPrefixCleaner.extractPartnerNamesFromHeaderTexts(
            listOf("Λευτέρης Φλαμπούρης\nΕνεργός/ή τώρα")
        )
        val discovered = SenderPrefixCleaner.discoverSideSenders(samples, headerPartnerNames, screenWidth)

        assertTrue(discovered.selfNames.contains("αγγελος"))
        assertTrue(discovered.partnerNames.contains("λευτερης φλαμπουρης"))
        assertTrue(discovered.partnerNames.contains("λευτερης"))

        // Outgoing bubble strips self sender "ΑΓΓΕΛΟΣ, " even for same-script Greek->Romanian or short Latin
        assertEquals(
            "Κάνω μια δοκιμή",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "ΑΓΓΕΛΟΣ, Κάνω μια δοκιμή",
                isOutgoing = true,
                hadEnvelopeMetadata = true,
                discoveredSenders = discovered
            )
        )
        assertEquals(
            "Bună ziua mâine am un curs",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "ΑΓΓΕΛΟΣ, Bună ziua mâine am un curs",
                isOutgoing = true,
                hadEnvelopeMetadata = true,
                discoveredSenders = discovered
            )
        )

        // Incoming bubble strips partner name, even if header partner was a single first name
        assertEquals(
            "Ce faci?",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "Λευτέρης Φλαμπούρης, Ce faci?",
                isOutgoing = false,
                hadEnvelopeMetadata = true,
                discoveredSenders = discovered
            )
        )
        assertEquals(
            "Bine, tu?",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "Λευτέρης, Bine, tu?",
                isOutgoing = false,
                hadEnvelopeMetadata = true,
                discoveredSenders = discovered
            )
        )

        // Directional protection: outgoing message addressing partner preserves partner's name;
        // incoming message addressing user preserves user's name.
        val partnerElenaSenders = SenderPrefixCleaner.DiscoveredSenders(
            partnerNames = setOf("elena", "λευτερης"),
            selfNames = setOf("αγγελος", "aggelos")
        )
        assertEquals(
            "Elena, hai la cafea!",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "ΑΓΓΕΛΟΣ, Elena, hai la cafea!",
                isOutgoing = true,
                hadEnvelopeMetadata = true,
                discoveredSenders = partnerElenaSenders
            )
        )
        assertEquals(
            "Aggelos, mergem?",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "Λευτέρης, Aggelos, mergem?",
                isOutgoing = false,
                hadEnvelopeMetadata = true,
                discoveredSenders = partnerElenaSenders
            )
        )

        // Sticker/image accessibility node with only sender name remaining discards empty body
        assertEquals(
            "",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "ΑΓΓΕΛΟΣ",
                isOutgoing = true,
                hadEnvelopeMetadata = true,
                discoveredSenders = discovered
            )
        )
    }

    @Test
    fun stripSenderPrefix_handlesSingleBubbleEnvelopeAllCapsWithoutTouchingUserVocatives() {
        val emptySenders = SenderPrefixCleaner.DiscoveredSenders()

        assertEquals(
            "Bună ziua, mergem mâine",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "AGGELOS, Bună ziua, mergem mâine",
                isOutgoing = true,
                hadEnvelopeMetadata = true,
                discoveredSenders = emptySenders
            )
        )
        assertEquals(
            "Elena, hai la cafea!",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "Elena, hai la cafea!",
                isOutgoing = true,
                hadEnvelopeMetadata = false,
                discoveredSenders = emptySenders
            )
        )
        // Por qué: En burbujas verificadas de TalkBack (hadEnvelopeMetadata=true), un nombre propio griego
        // en nominativo ante cuerpo latino también se retira aunque solo haya un mensaje visible.
        assertEquals(
            "Da",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "Άγγελος, Da",
                isOutgoing = true,
                hadEnvelopeMetadata = true,
                discoveredSenders = emptySenders
            )
        )
        assertEquals(
            "Άγγελε, ce mai faci?",
            SenderPrefixCleaner.stripSenderPrefix(
                textAfterSuffixStrip = "Άγγελε, ce mai faci?",
                isOutgoing = true,
                hadEnvelopeMetadata = true,
                discoveredSenders = emptySenders
            )
        )
    }

    @Test
    fun isPlausibleSenderName_andNormalizeName_handlePunctuationAndAccentsWithoutCurlyRegex() {
        assertEquals("αγγελος", SenderPrefixCleaner.normalizeName("Άγγελος"))
        assertEquals("stefan", SenderPrefixCleaner.normalizeName("Ștefan"))
        assertFalse(SenderPrefixCleaner.isPlausibleSenderName("User{test}"))
        assertFalse(SenderPrefixCleaner.isPlausibleSenderName("User[1]"))
        assertFalse(SenderPrefixCleaner.isPlausibleSenderName("Ce faci?"))
        assertFalse(SenderPrefixCleaner.isPlausibleSenderName("«Salut»"))
        assertTrue(SenderPrefixCleaner.isPlausibleSenderName("ΑΓΓΕΛΟΣ"))
        assertTrue(SenderPrefixCleaner.isPlausibleSenderName("Λευτέρης Φλαμπούρης"))
    }
}

