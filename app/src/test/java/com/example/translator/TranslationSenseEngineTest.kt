package com.example.translator

import android.graphics.Rect
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSenseEngineTest {

    @Test
    fun isEligibleSourceText_acceptsRomanianMessages() {
        val candidates = emptyList<IdentifiedLanguage>()
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "La mulți ani și un An Nou fericit, Angelos.",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Angel, ai fost futut de negri.",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Ingerule, ce faci;",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
    }

    @Test
    fun isEligibleSourceText_rejectsEnglishTextWhenSourceIsRomanian() {
        val candidates = listOf(
            IdentifiedLanguage("en", 0.98f)
        )
        val wazeTitle = "Driving directions, live traffic & road conditions updates - Waze"
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                wazeTitle,
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )

        val wazeBody = "Realtime driving directions based on live traffic updates from Waze - Get the best route to your destination from fellow drivers"
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                wazeBody,
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                candidates
            )
        )
    }

    @Test
    fun isEligibleSourceText_rejectsGreekTextWhenTargetIsGreek() {
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Έχω σταθμεύσει εδώ: Γ. Μαρίνου, Ελληνικό.",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    @Test
    fun isEligibleSourceText_rejectsLatinAndGenericPrepositionsWhenSourceIsRomanian() {
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Clamavi de Profundis",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )

        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Clamavi de Profundis",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                listOf(IdentifiedLanguage("la", 0.88f))
            )
        )

        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "de",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "la",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )

        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Stremio",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                "Stremio",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                listOf(IdentifiedLanguage("it", 0.75f))
            )
        )

        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "De ce nu răspunzi?",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "ce faci de mâncare",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesHolidayGreetingsAccurately() {
        val greetingWithAddress = TranslationSenseEngine.resolveIdiomPreTranslation(
            "La mulți ani și un An Nou fericit, Angelos.",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(greetingWithAddress)
        assertTrue(greetingWithAddress!!.contains("Χρόνια πολλά"))
        assertTrue(greetingWithAddress.contains("ευτυχισμένο το νέο έτος"))
        assertTrue(greetingWithAddress.contains("Άγγελε") || greetingWithAddress.contains("Άγγελος"))

        val simpleGreeting = TranslationSenseEngine.resolveIdiomPreTranslation(
            "La mulți ani și un An Nou fericit",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertEquals("Χρόνια πολλά και ευτυχισμένο το νέο έτος!", simpleGreeting)
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesSlangAndColloquialPhrases() {
        val slangTranslation = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Angel, ai fost futut de negri.",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(slangTranslation)
        assertTrue(
            slangTranslation!!.contains("μαύροι") || slangTranslation.contains("από μαύρους")
        )
        assertTrue(
            slangTranslation.contains("πήδηξαν") || slangTranslation.contains("γάμησαν")
        )
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesConversationalQuestionsAndStatements() {
        val questionResult = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Îngerule, ce faci?",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(questionResult)
        assertEquals("Άγγελε, τι κάνεις;", questionResult)

        val statementResult = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Îngerule, totul e bine.",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(statementResult)
        assertEquals("Άγγελε, όλα είναι καλά.", statementResult)
    }

    @Test
    fun resolveIdiomPreTranslation_returnsNullForRegularSentences() {
        val result = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Autobuzul ajunge la ora cinci dupa-amiaza la statie.",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNull(result)
    }

    @Test
    fun applyPostTranslationSenseLogic_correctsBirthdayMistranslationForNewYear() {
        val corrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "La mulți ani și un An Nou fericit, Angelos.",
            translatedText = "Ευτυχισμένα γενέθλια και ένα ευτυχισμένο νέο έτος, ο Άγγελος.",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertFalse(corrected.contains("γενέθλια"))
        assertTrue(corrected.contains("Χρόνια πολλά"))
        assertFalse(corrected.contains("ο Άγγελος"))
    }

    @Test
    fun applyPostTranslationSenseLogic_correctsNonsensicalColorTranslations() {
        val corrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Angel, ai fost futut de negri.",
            translatedText = "Άγγελος, είσαι πατήσαμε με μαύρο χρώμα.",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertFalse(corrected.contains("μαύρο χρώμα"))
        assertFalse(corrected.contains("είσαι πατήσαμε"))
        assertTrue(corrected.contains("μαύροι") || corrected.contains("από μαύρους"))
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesGreekToRomanianConversationalExpressions() {
        val result1 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Πως εισαι φιλε μου Γιαννη",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result1)
        assertEquals("Cum ești, prietene Giannis?", result1)

        val result2 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Τι κανεις φιλε μου",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result2)
        assertEquals("Ce faci, prietene?", result2)

        val result3 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Καλημερα",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result3)
        assertEquals("Bună dimineața!", result3)

        val result4 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Χρονια πολλα και καλη χρονια, Γιαννη",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result4)
        assertEquals("La mulți ani și un An Nou fericit, Giannis!", result4)
    }

    @Test
    fun applyPostTranslationSenseLogic_correctsVocativeForms() {
        // Greek -> Romanian: "prietenul meu" -> "prietene" in direct address
        val roCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Πως εισαι φιλε μου Γιαννη",
            translatedText = "Cum esti prietenul meu Giannis",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Cum ești, prietene Giannis?", roCorrected)

        // Romanian -> Greek: "ο φίλος μου Γιάννης" -> "φίλε μου Γιάννη"
        val elCorrected = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Cum esti prietenul meu Giannis?",
            translatedText = "Πώς είσαι ο φίλος μου Γιάννης;",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertEquals("Πώς είσαι φίλε μου Γιάννη;", elCorrected)
    }

    @Test
    fun isMessageBubble_acceptsIncomingAndOutgoingBubblesAndRejectsDatePills() {
        val screenWidth = 1080
        // Incoming message (left margin)
        assertTrue(TranslationSenseEngine.isMessageBubble(36, 750, screenWidth))
        // Outgoing message (wide, e.g. "Cum ești prietenul meu Giannis")
        assertTrue(TranslationSenseEngine.isMessageBubble(340, 1036, screenWidth))
        // Outgoing message (medium, e.g. "Salut, Giannis!")
        assertTrue(TranslationSenseEngine.isMessageBubble(500, 1036, screenWidth))
        // Outgoing message (short, e.g. "Ce faci?")
        assertTrue(TranslationSenseEngine.isMessageBubble(650, 1036, screenWidth))
        // Mensaje saliente corto con margen derecho mayor al 72% del ancho
        assertTrue(TranslationSenseEngine.isMessageBubble(700, 800, screenWidth))
        // Centered date pill (e.g. "Σήμερα")
        assertFalse(TranslationSenseEngine.isMessageBubble(450, 630, screenWidth))
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesRomanianSentMessagesToGreek() {
        val resultMorning = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Bună dimineața!",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(resultMorning)
        assertEquals("Καλημέρα!", resultMorning)

        val result1 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Cum ești prietenul meu Giannis",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result1)
        assertEquals("Πώς είσαι, φίλε μου Γιάννη;", result1)

        val result2 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Salut, Giannis!",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result2)
        assertEquals("Γεια σου, Γιάννη!", result2)

        val result3 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Ce faci?",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result3)
        assertEquals("Τι κάνεις;", result3)
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesGreekSlangAndCurses() {
        val result1 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Άντε γαμήσου Γιάννη",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result1)
        assertEquals("Du-te dracului, Giannis!", result1)

        val result2 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Άντε ρε μαλάκα",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result2)
        assertEquals("Hai mă, prostule!", result2)

        val result3 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Γαμώτο",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result3)
        assertEquals("La dracu!", result3)

        val result4 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Σε πήδηξαν μαύροι",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result4)
        assertEquals("Te-au futut negrii!", result4)
    }

    @Test
    fun isEligibleSourceText_acceptsMesajAndBunaSeara() {
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Mesaj",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                "Bună seara!",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesMesajAndBunaSeara() {
        val result1 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Mesaj",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result1)
        assertEquals("Μήνυμα", result1)

        val result2 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Bună seara!",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result2)
        assertEquals("Καλησπέρα!", result2)

        val result3 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Scuze",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result3)
        assertEquals("Συγγνώμη!", result3)

        val result4 = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Mersi",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNotNull(result4)
        assertEquals("Ευχαριστώ!", result4)
    }

    @Test
    fun applyPostTranslationSenseLogic_fixesGreekAnteAndCurses() {
        val corrected1 = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Άντε γαμήσου Γιάννη",
            translatedText = "Ante giannis",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Du-te dracului, Giannis!", corrected1)

        val corrected2 = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Άντε φύγε τώρα",
            translatedText = "Ante pleacă acum",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Hai pleacă acum", corrected2)
    }

    @Test
    fun resolveGreekCursesAndInsults_resolvesGianniGamiesai() {
        val result = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Γιάννη γαμιέσαι",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(result)
        assertEquals("Du-te dracului, Giannis!", result)

        val isEligible = TranslationSenseEngine.isEligibleSourceText(
            "Γιάννη γαμιέσαι",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN,
            emptyList()
        )
        assertTrue(isEligible)

        val postResult = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Γιάννη γαμιέσαι",
            translatedText = "Giannis se fute",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertEquals("Du-te dracului, Giannis!", postResult)
    }

    @Test
    fun resolveIdiomPreTranslation_returnsNullForMultilineMessages() {
        val multilineGreek = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Καλησπέρα Γιάννη\nΜε το παρόν γράμμα...",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNull(multilineGreek)

        val multilineRomanian = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Bună seara, Giannis!\nCu această scrisoare...",
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )
        assertNull(multilineRomanian)
    }

    @Test
    fun resolveIdiomPreTranslation_resolvesSingleLineGreekGreeting() {
        val singleLineGreek = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Καλησπέρα Γιάννη",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNotNull(singleLineGreek)
        assertEquals("Bună seara, Giannis!", singleLineGreek)
    }

    @Test
    fun resolveIdiomPreTranslation_rejectsGreedyGreekGreetingInLongSentence() {
        val longGreek = TranslationSenseEngine.resolveIdiomPreTranslation(
            "Καλησπέρα Γιάννη θα ήθελα να ρωτήσω τι ώρα ανοίγει το κατάστημα αύριο",
            TranslateLanguage.GREEK,
            TranslateLanguage.ROMANIAN
        )
        assertNull(longGreek)
    }

    // Por qué: Verifica que ofertas laborales o mensajes en inglés sean estrictamente rechazados
    // cuando el idioma de origen configurado es rumano, evitando traducir inglés a inglés.
    @Test
    fun isEligibleSourceText_strictlyRejectsEnglishJobOfferWhenSourceIsRomanian() {
        val jobOfferEnglish = """
            Game Presenters with Greek Language
            No experience Needed
            Good knowledge (written and speaking) of Greek Language
            900€ Monthly Salary (net) (4500 RON)
            90€ performance bonus (450 RON)
            120€ meal tickets (600 RON)
            300€ relocation bonus (One time payment)
            Nightshift bonus (25%), overtime (75%), bank holidays(100%) weekends(5%).
            Public transportation subscription
            Unidentified period contract
            Christmas, Easter bonus
            Private Clinic Medical Subscription
            3 weeks paid training
            Team activities
        """.trimIndent()

        assertFalse(
            TranslationSenseEngine.isEligibleSourceText(
                jobOfferEnglish,
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    // Por qué: Verifica que los mensajes genuinos en rumano sean aceptados para su traducción al griego.
    @Test
    fun isEligibleSourceText_acceptsRomanianSubmissionText() {
        val romanianText = """
            se depun la secretariat urmatoarele:
            Cerere
            Borderou completat și semnat de titular
            Chitanțele STB
            Copie xerox după cardul STB
            Termen de depunere la secretariat:
            ultima saptamana a fiecarui semestru
        """.trimIndent()

        assertTrue(
            TranslationSenseEngine.isEligibleSourceText(
                romanianText,
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK,
                emptyList()
            )
        )
    }

    // Por qué: Verifica las resoluciones instantáneas del asistente inverso del teclado
    // tanto para expresiones comunes en griego hacia rumano como en rumano hacia griego.
    @Test
    fun resolveIdiomPreTranslation_keyboardReverseAssistantExpressions() {
        // Greek -> Romanian
        val kalimera = TranslationSenseEngine.resolveIdiomPreTranslation("Καλημέρα", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN)
        assertEquals("Bună dimineața!", kalimera)

        val kalisperaGianni = TranslationSenseEngine.resolveIdiomPreTranslation("Καλησπέρα Γιάννη", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN)
        assertEquals("Bună seara, Giannis!", kalisperaGianni)

        val tiKaneis = TranslationSenseEngine.resolveIdiomPreTranslation("Τι κάνεις φίλε μου;", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN)
        assertEquals("Ce faci, prietene?", tiKaneis)

        val olaKala = TranslationSenseEngine.resolveIdiomPreTranslation("Όλα καλά;", TranslateLanguage.GREEK, TranslateLanguage.ROMANIAN)
        assertEquals("Totul e bine?", olaKala)

        // Romanian -> Greek
        val duteDracu = TranslationSenseEngine.resolveIdiomPreTranslation("Du-te dracului", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        assertEquals("Άντε στο διάολο!", duteDracu)

        val multumesc = TranslationSenseEngine.resolveIdiomPreTranslation("Multumesc", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        assertEquals("Ευχαριστώ!", multumesc)
    }

    // Por qué: Valida que la lógica post-traducción corrige los errores sistemáticos
    // de ML Kit en la dirección Griego → Rumano para vocabulario informal y tecnológico.
    @Test
    fun applyPostTranslationSenseLogic_fixesGreekToRomanianMLKitErrors() {
        // ML Kit: "εφαρμογή" -> "cererea" en lugar de "aplicația"
        val fixedApp = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Προσπαθώντας να καταλάβω πως θα κάνω την εφαρμογή να δουλέψει για τον ηλίθιο της παρέας",
            translatedText = "Încercând să dau seama că voi face cererea de a lucra pentru stupidul companiei",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        // "cererea" -> "aplicația"
        assertTrue(fixedApp.contains("aplicația"))
        // "de a lucra" -> "să funcționeze"
        assertTrue(fixedApp.contains("funcționeze"))
        // "companiei" -> "grupului" (no "gașcii" que se traduce como συμμορία)
        assertTrue(fixedApp.contains("grupului"))
        // "stupidul" -> "idiotul" (preserva el sustantivo masculino sin convertirlo en adjetivo)
        assertTrue(fixedApp.contains("idiotul"))
        // "că voi" -> "cum voi"
        assertTrue(fixedApp.contains("cum voi"))
        // "dau seama că" -> "dau seama cum"
        assertTrue(fixedApp.contains("dau seama cum"))

        // Verificar que "παρέα" se corrige como "grup" / "grupul"
        val fixedParea = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Η παρέα είναι εδώ",
            translatedText = "Compania este aici",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertTrue(fixedParea.contains("grupul") || fixedParea.contains("Grupul") || fixedParea.contains("grup"))

        // Verificar corrección de "ηλίθιος"
        val fixedIdiot = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Είσαι ηλίθιος",
            translatedText = "Ești stupid",
            sourceLang = TranslateLanguage.GREEK,
            targetLang = TranslateLanguage.ROMANIAN
        )
        assertTrue(fixedIdiot.contains("idiot") || fixedIdiot.contains("prost"))
    }

    // Por qué: Valida que los textos en rumano traducidos a griego no viertan "gașcă/grup"
    // como "συμμορία" (banda armada/delictiva) ni los verbos de software como "εργαστεί" (empleo humano).
    @Test
    fun applyPostTranslationSenseLogic_fixesRomanianToGreekChatErrors() {
        val fixedOverlay = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Încercând să dau seama cum voi face aplicația să funcționeze pentru prostul gașcii",
            translatedText = "Προσπαθώντας να καταλάβω πώς θα κάνω την εφαρμογή να εργαστεί για την ηλίθια συμμορία",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        // "την εφαρμογή να εργαστεί" -> "την εφαρμογή να δουλέψει"
        assertTrue(fixedOverlay.contains("την εφαρμογή να δουλέψει"))
        // "για την ηλίθια συμμορία" -> "για τον ηλίθιο της παρέας"
        assertTrue(fixedOverlay.contains("για τον ηλίθιο της παρέας"))

        val fixedGang = TranslationSenseEngine.applyPostTranslationSenseLogic(
            originalText = "Sunt cu gașca",
            translatedText = "Είμαι με τη συμμορία",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertEquals("Είμαι με τη παρέα", fixedGang)
    }
}

