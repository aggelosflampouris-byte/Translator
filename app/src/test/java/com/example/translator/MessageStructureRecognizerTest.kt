package com.example.translator

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de [MessageStructureRecognizer] e integración con [NodeCandidateEvaluator]
 * y [TranslationFilter] para garantizar la separación de remitente, mensaje citado y respuesta.
 */
class MessageStructureRecognizerTest {

    private val whatsappProfile = TargetAppCatalog.profileForPackage("com.whatsapp")
    private val density = 3.0f
    private val screenWidth = 1080
    private val minY = 200
    private val maxY = 2000

    @Test
    fun recognizeTextAnatomy_splitsTalkBackReplyGreekRomanianAndEnglish() {
        val greekReply = MessageStructureRecognizer.recognizeTextAnatomy(
            "~ Stefania V απάντησε στον χρήστη ~ Edward «Ea are doar 10»: mi ai spart contul?, 15:51"
        )
        assertEquals("~ Stefania V", greekReply.senderName)
        assertEquals("~ Edward", greekReply.repliedSenderName)
        assertEquals("Ea are doar 10", greekReply.repliedMessage)
        assertEquals("mi ai spart contul?", greekReply.replyingText)

        val roReply = MessageStructureRecognizer.recognizeTextAnatomy(
            "~ Mircea a răspuns la ~ Edysuperedy «Ca acum am permisiune»: nu nu, 20:46"
        )
        assertEquals("~ Mircea", roReply.senderName)
        assertEquals("~ Edysuperedy", roReply.repliedSenderName)
        assertEquals("Ca acum am permisiune", roReply.repliedMessage)
        assertEquals("nu nu", roReply.replyingText)

        val enReply = MessageStructureRecognizer.recognizeTextAnatomy(
            "~ Eric replied to ~ misu \"mai are sens?\": nu cred"
        )
        assertEquals("~ Eric", enReply.senderName)
        assertEquals("~ misu", enReply.repliedSenderName)
        assertEquals("mai are sens?", enReply.repliedMessage)
        assertEquals("nu cred", enReply.replyingText)

        // Frase normal con verbo "replied" sin estructura de cita no debe mutilarse
        val normalSentence = MessageStructureRecognizer.recognizeTextAnatomy(
            "I replied to your message yesterday",
            TranslateLanguage.ENGLISH
        )
        assertNull(normalSentence.repliedMessage)
        assertEquals("I replied to your message yesterday", normalSentence.replyingText)
    }

    @Test
    fun recognizeTextAnatomy_parsesMultilineCompositeAndCombinedPhoneHeaders() {
        val multiline = MessageStructureRecognizer.recognizeTextAnatomy(
            "~ Edward\n~ misu\nmai are sens sa vorbim cu profu?\nSa ii ziceti ce? Ca putea mai bine?\n15:51"
        )
        assertEquals("~ Edward", multiline.senderName)
        assertEquals("~ misu", multiline.repliedSenderName)
        assertEquals("mai are sens sa vorbim cu profu?", multiline.repliedMessage)
        assertEquals("Sa ii ziceti ce? Ca putea mai bine?", multiline.replyingText)

        val commaPhone = MessageStructureRecognizer.recognizeTextAnatomy("~ Edward, +40 773 947 253")
        assertEquals("~ Edward", commaPhone.senderName)
        assertNull(commaPhone.replyingText)

        val newlinePhone = MessageStructureRecognizer.recognizeTextAnatomy("~ Edward\n+40 773 947 253")
        assertEquals("~ Edward", newlinePhone.senderName)
        assertNull(newlinePhone.replyingText)

        val objReplacement = MessageStructureRecognizer.recognizeTextAnatomy("\uFFFC~ stefan")
        assertEquals("~ stefan", objReplacement.senderName)
        assertNull(objReplacement.replyingText)

        assertFalse(TranslationFilter.shouldTranslate("~ Edward, +40 773 947 253", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN, deferUnknownLatinToMlKit = true))
        assertFalse(TranslationFilter.shouldTranslate("~ Edward\n+40 773 947 253", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN, deferUnknownLatinToMlKit = true))
        assertFalse(TranslationFilter.shouldTranslate("\uFFFC~ stefan", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN, deferUnknownLatinToMlKit = true))
    }

    @Test
    fun recognizeRowAnatomy_handlesUnindentedReplyBubbleWithoutViewIdsAndClampsExpandedReplyTop() {
        val sharedBubbleRect = WindowRect(130, 500, 900, 820)
        // Sin viewIdName y con rawRect.left idéntico (150) entre texto citado y respuesta,
        // pero agrupando la tarjeta de cita bajo su subContainerNodeId = 50.
        val rawNodes = listOf(
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 515, 450, 555),
                expandedRect = sharedBubbleRect,
                directText = "~ Stefania V",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 1,
                parentNodeId = 20,
                nodeId = 101,
                subContainerNodeId = 20
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 565, 420, 600),
                expandedRect = WindowRect(150, 565, 420, 600),
                directText = "~ Edward",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 1,
                parentNodeId = 51,
                nodeId = 102,
                subContainerNodeId = 50
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 605, 720, 665),
                expandedRect = sharedBubbleRect,
                directText = "Ea are doar 10",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 1,
                parentNodeId = 50,
                nodeId = 103,
                subContainerNodeId = 50
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 690, 760, 795),
                expandedRect = sharedBubbleRect,
                directText = "mi ai spart contul?",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 1,
                parentNodeId = 10,
                nodeId = 104,
                subContainerNodeId = 10
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(780, 780, 880, 808),
                expandedRect = WindowRect(780, 780, 880, 808),
                directText = "15:51",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 1,
                parentNodeId = 10,
                nodeId = 105,
                subContainerNodeId = 10
            )
        )

        val anatomies = MessageStructureRecognizer.recognizeRowAnatomy(rawNodes, TranslateLanguage.ROMANIAN, density)
        assertEquals(1, anatomies.size)
        val anatomy = anatomies.single()
        assertEquals("~ Stefania V", anatomy.senderName)
        assertEquals("~ Edward", anatomy.repliedSenderName)
        assertEquals("Ea are doar 10", anatomy.repliedMessage)
        assertEquals("mi ai spart contul?", anatomy.replyingText)
        assertEquals(665, anatomy.minReplyTopPx)

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = rawNodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )
        assertEquals(1, evaluated.size)
        val reply = evaluated.single()
        assertEquals("mi ai spart contul?", reply.text)
        assertTrue(reply.bounds.top >= 665)
    }

    @Test
    fun recognizeRowAnatomy_usesWhatsAppViewIdsForSenderQuoteAndReplyBody() {
        val rawNodes = listOf(
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 500, 420, 540),
                expandedRect = WindowRect(150, 500, 420, 540),
                directText = "Stefan",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 2,
                parentNodeId = 20,
                nodeId = 201,
                isInsideHeaderContainer = true,
                viewIdName = "com.whatsapp:id/name_in_group_tv"
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(155, 550, 430, 585),
                expandedRect = WindowRect(155, 550, 430, 585),
                directText = "misu",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 2,
                parentNodeId = 21,
                nodeId = 202,
                isInsideQuoteContainer = true,
                isInsideHeaderContainer = true,
                viewIdName = "com.whatsapp:id/quoted_title"
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(155, 590, 760, 650),
                expandedRect = WindowRect(155, 590, 760, 650),
                directText = "Ce faci diseara?",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 2,
                parentNodeId = 21,
                nodeId = 203,
                isInsideQuoteContainer = true,
                viewIdName = "com.whatsapp:id/quoted_text"
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 670, 740, 740),
                expandedRect = WindowRect(150, 670, 740, 740),
                directText = "Ne vedem la camin",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 2,
                parentNodeId = 20,
                nodeId = 204,
                viewIdName = "com.whatsapp:id/message_text",
                isMainMessageBodyViewId = true
            )
        )

        val anatomy = MessageStructureRecognizer.recognizeRowAnatomy(rawNodes, TranslateLanguage.ROMANIAN, density).single()
        assertEquals("Stefan", anatomy.senderName)
        assertEquals("misu", anatomy.repliedSenderName)
        assertEquals("Ce faci diseara?", anatomy.repliedMessage)
        assertEquals("Ne vedem la camin", anatomy.replyingText)

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = rawNodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )
        assertEquals(listOf("Ne vedem la camin"), evaluated.map { it.text })
    }

    @Test
    fun recognizeRowAnatomy_dropsLowercaseSavedNamesAboveTextStickerOrEmojis() {
        val rawNodes = listOf(
            // Fila 10: contacto guardado en minúsculas sin teléfono encima de texto rumano
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 320, 320, 360),
                expandedRect = WindowRect(150, 320, 320, 360),
                directText = "stefan",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 10,
                parentNodeId = 101,
                nodeId = 1001,
                subContainerNodeId = 101
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 370, 660, 440),
                expandedRect = WindowRect(150, 370, 660, 440),
                directText = "Eu vin azi",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 10,
                parentNodeId = 102,
                nodeId = 1002,
                subContainerNodeId = 102
            ),
            // Fila 11: contacto guardado en minúsculas encima de sticker/imagen sin texto (isMediaPlaceholder)
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 500, 310, 540),
                expandedRect = WindowRect(150, 500, 310, 540),
                directText = "misu",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 11,
                parentNodeId = 111,
                nodeId = 1101,
                subContainerNodeId = 111
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 550, 520, 820),
                expandedRect = WindowRect(150, 550, 520, 820),
                directText = null,
                descText = null,
                shortClass = "ImageView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 11,
                parentNodeId = 112,
                nodeId = 1102,
                subContainerNodeId = 112,
                isMediaPlaceholder = true
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(130, 490, 560, 840),
                expandedRect = WindowRect(130, 490, 560, 840),
                directText = null,
                descText = "misu, Sticker, 15:50",
                shortClass = "LinearLayout",
                isScrollableContainer = false,
                childCount = 2,
                inChatViewport = true,
                childIndices = listOf(2, 3),
                rowId = 11,
                parentNodeId = 110,
                nodeId = 1103
            ),
            // Fila 12: contacto guardado en minúsculas encima de emojis únicamente
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 900, 320, 940),
                expandedRect = WindowRect(150, 900, 320, 940),
                directText = "sonic",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 12,
                parentNodeId = 120,
                nodeId = 1201
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 950, 320, 1010),
                expandedRect = WindowRect(150, 950, 320, 1010),
                directText = "😭😭",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 12,
                parentNodeId = 120,
                nodeId = 1202
            )
        )

        val anatomies = MessageStructureRecognizer.recognizeRowAnatomy(rawNodes, TranslateLanguage.ROMANIAN, density)
        assertEquals("stefan", anatomies[0].senderName)
        assertEquals("Eu vin azi", anatomies[0].replyingText)
        assertEquals("misu", anatomies[1].senderName)
        assertNull(anatomies[1].replyingText)
        assertEquals("sonic", anatomies[2].senderName)

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = rawNodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )
        assertEquals(listOf("Eu vin azi"), evaluated.map { it.text })
    }

    @Test
    fun recognizeRowAnatomy_keepsConsecutiveStandaloneBubblesInSameRow() {
        val rawNodes = listOf(
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 420, 460, 460),
                expandedRect = WindowRect(150, 420, 460, 460),
                directText = "~ Edysuperedy",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 201
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 466, 560, 500),
                expandedRect = WindowRect(150, 466, 560, 500),
                directText = "Higher Year Representative",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 201
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 515, 760, 580),
                expandedRect = WindowRect(150, 515, 760, 580),
                directText = "Verificati si maine de dimi",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 202
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(680, 565, 790, 595),
                expandedRect = WindowRect(680, 565, 790, 595),
                directText = "00:26",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 202
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(150, 625, 810, 690),
                expandedRect = WindowRect(150, 625, 810, 690),
                directText = "Poate mai dureaza sa se updateze",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 203
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(560, 690, 840, 720),
                expandedRect = WindowRect(560, 690, 840, 720),
                directText = "Τροποποιήθηκε 00:27",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 20,
                parentNodeId = 203
            )
        )

        val anatomies = MessageStructureRecognizer.recognizeRowAnatomy(rawNodes, TranslateLanguage.ROMANIAN, density)
        assertEquals(2, anatomies.size)
        assertEquals("~ Edysuperedy", anatomies[0].senderName)
        assertEquals("Higher Year Representative", anatomies[0].senderRoleSubtitle)
        assertEquals("Verificati si maine de dimi", anatomies[0].replyingText)
        assertEquals("Poate mai dureaza sa se updateze", anatomies[1].replyingText)

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = rawNodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = whatsappProfile
        )
        assertEquals(
            listOf("Verificati si maine de dimi", "Poate mai dureaza sa se updateze"),
            evaluated.map { it.text }
        )
    }

    @Test
    fun recognizeRowAnatomy_viberIgnoresSideHeartAndInlineTranslatePill() {
        val viberProfile = TargetAppCatalog.CURATED_PROFILES.first { it.id == "viber" }
        val rawNodes = listOf(
            // Burbuja saliente con corazón de reacción a la izquierda
            CollectedNodeSnapshot(
                rawRect = WindowRect(340, 260, 960, 360),
                expandedRect = WindowRect(310, 240, 980, 410),
                directText = "Vreau să fac un test de tip traducere",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 31,
                parentNodeId = 311,
                nodeId = 3101
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(820, 362, 950, 396),
                expandedRect = WindowRect(820, 362, 950, 396),
                directText = "1:01 PM",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 31,
                parentNodeId = 311,
                nodeId = 3102
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(240, 290, 330, 390),
                expandedRect = WindowRect(240, 290, 330, 390),
                directText = null,
                descText = null,
                shortClass = "ImageView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 31,
                parentNodeId = 310,
                nodeId = 3103,
                isMediaPlaceholder = true
            ),
            // Burbuja entrante con botón inline "Μετάφραση" debajo del texto y corazón a la derecha
            CollectedNodeSnapshot(
                rawRect = WindowRect(70, 480, 680, 620),
                expandedRect = WindowRect(50, 460, 700, 680),
                directText = "Sunt sigur că aplicația ta a funcționat mai bine decât Outlook în ultimele ore.",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 32,
                parentNodeId = 321,
                nodeId = 3201
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(80, 628, 320, 668),
                expandedRect = WindowRect(80, 628, 320, 668),
                directText = "Μετάφραση",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 32,
                parentNodeId = 321,
                nodeId = 3202
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(560, 628, 670, 668),
                expandedRect = WindowRect(560, 628, 670, 668),
                directText = "3:55 PM",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 32,
                parentNodeId = 321,
                nodeId = 3203
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(695, 510, 775, 590),
                expandedRect = WindowRect(695, 510, 775, 590),
                directText = null,
                descText = null,
                shortClass = "ImageView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 32,
                parentNodeId = 320,
                nodeId = 3204,
                isMediaPlaceholder = true
            ),
            // Burbuja saliente corta con corazón lateral
            CollectedNodeSnapshot(
                rawRect = WindowRect(340, 720, 960, 790),
                expandedRect = WindowRect(310, 700, 980, 825),
                directText = "Te voi dracu '. Adăugați Mala.",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 33,
                parentNodeId = 331,
                nodeId = 3301
            ),
            CollectedNodeSnapshot(
                rawRect = WindowRect(240, 725, 330, 815),
                expandedRect = WindowRect(240, 725, 330, 815),
                directText = null,
                descText = null,
                shortClass = "ImageView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 33,
                parentNodeId = 330,
                nodeId = 3302,
                isMediaPlaceholder = true
            ),
            // Pill flotante inferior de Viber fuera de la lista (rowId = 0)
            CollectedNodeSnapshot(
                rawRect = WindowRect(40, 1850, 520, 1910),
                expandedRect = WindowRect(40, 1850, 520, 1910),
                directText = "Προτεινόμενες απαντήσεις",
                descText = null,
                shortClass = "TextView",
                isScrollableContainer = false,
                childCount = 0,
                inChatViewport = true,
                childIndices = emptyList(),
                rowId = 0,
                parentNodeId = 900,
                nodeId = 9001
            )
        )

        val anatomies = MessageStructureRecognizer.recognizeRowAnatomy(
            rawNodes = rawNodes,
            configuredSource = TranslateLanguage.ROMANIAN,
            density = density,
            profile = viberProfile
        )
        assertEquals(3, anatomies.size)
        assertEquals("Vreau să fac un test de tip traducere", anatomies[0].replyingText)
        assertEquals(
            "Sunt sigur că aplicația ta a funcționat mai bine decât Outlook în ultimele ore.",
            anatomies[1].replyingText
        )
        assertEquals("Te voi dracu '. Adăugați Mala.", anatomies[2].replyingText)

        val evaluated = NodeCandidateEvaluator.evaluateBottomUp(
            rawNodes = rawNodes,
            minY = minY,
            maxY = maxY,
            screenWidth = screenWidth,
            density = density,
            configuredSource = TranslateLanguage.ROMANIAN,
            configuredTarget = TranslateLanguage.GREEK,
            profile = viberProfile
        )
        assertEquals(
            listOf(
                "Vreau să fac un test de tip traducere",
                "Sunt sigur că aplicația ta a funcționat mai bine decât Outlook în ultimele ore.",
                "Te voi dracu '. Adăugați Mala."
            ),
            evaluated.map { it.text }
        )
    }
}

