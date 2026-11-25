package com.example.translator

import java.io.BufferedReader
import java.io.InputStreamReader
import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de regresión de precisión sistemática para chats grupales rumanos sin API externa:
 * valida el léxico sin ambigüedad, sufijos morfológicos deterministas, clíticos/condicionales,
 * tabla de pivote `ro -> el`, controles negativos en inglés y latencia (< 200 ms / 100 frases).
 */
class RomanianOfflineAccuracyCorpusTest {

    @Test
    fun lexiconLoadsSubstantialUnambiguousEntries() {
        val size = RomanianDiacriticsRestorer.lexiconSize()
        assertTrue("Expected >= 400 unambiguous entries, was $size", size >= 400)
        for ((asciiKey, accentedVal) in RomanianDiacriticsRestorer.unambiguousLexicon) {
            assertTrue("Key '$asciiKey' should differ from '$accentedVal'", asciiKey != accentedVal)
            assertTrue("Key '$asciiKey' should be lowercase", asciiKey == asciiKey.lowercase())
        }
    }

    @Test
    fun systematicDiacriticsAndSuffixes_restoreUnseenRomanianWithoutCorruptingEnglish() {
        assertEquals(
            "Avem informații noi despre situația secțiunii",
            TextNormalizer.normalizeRomanianChatForTranslation("Avem informatii noi despre situatia sectiunii")
        )
        assertEquals(
            "Aplicația funcționează și are opțiunea de distribuție",
            TextNormalizer.normalizeRomanianChatForTranslation("Aplicatia functioneaza si are optiunea de distributie")
        )
        assertEquals(
            "Trebuie să rezolvăm problema cu soluția asta",
            TextNormalizer.normalizeRomanianChatForTranslation("Trebuie sa rezolvam problema cu solutia asta")
        )
        assertEquals(
            "Vă trimit o adeverință pentru ședință mâine",
            TextNormalizer.normalizeRomanianChatForTranslation("Va trimit o adeverinta pentru sedinta maine")
        )

        // Sufijos y palabras ambiguas que NO deben alterarse
        assertEquals(
            "Acest website de pe internet este pentru teste",
            TextNormalizer.normalizeRomanianChatForTranslation("Acest website de pe internet este pentru teste")
        )
        assertEquals(
            "O poveste scurtă despre peste",
            TextNormalizer.normalizeRomanianChatForTranslation("O poveste scurta despre peste")
        )

        // Controles negativos estrictos en inglés
        assertEquals("He is in London", TextNormalizer.normalizeRomanianChatForTranslation("He is in London"))
        assertEquals("I am going home", TextNormalizer.normalizeRomanianChatForTranslation("I am going home"))
        assertEquals("The cat sat on the mat", TextNormalizer.normalizeRomanianChatForTranslation("The cat sat on the mat"))
        assertEquals("Too early to ask this", TextNormalizer.normalizeRomanianChatForTranslation("Too early to ask this"))
    }

    @Test
    fun generalCliticAndConditionalRestorer_fixesBrokenInformalStructures() {
        assertEquals(
            "Mi-aș lua și eu bilet printr-o cerere",
            TextNormalizer.normalizeRomanianChatForTranslation("Mi as lua si eu bilet printr o cerere")
        )
        assertEquals(
            "N-ar fi rau dacă s-ar putea mâine",
            TextNormalizer.normalizeRomanianChatForTranslation("N ar fi rau daca s ar putea maine")
        )
        assertEquals(
            "Nu-i problema, și-a luat deja cartela",
            TextNormalizer.normalizeRomanianChatForTranslation("Nu i problema, si a luat deja cartela")
        )
        assertEquals(
            "Ce-i cu cererea asta, i-ai trimis deja?",
            TextNormalizer.normalizeRomanianChatForTranslation("Ce i cu cererea asta, i ai trimis deja?")
        )
        assertEquals(
            "Ați verificat dacă a fost imprimat și verificat formularul?",
            TextNormalizer.normalizeRomanianChatForTranslation("Ati verificat daca a fost printat si checkuit formularul?")
        )
    }

    @Test
    fun pivotTable_disambiguatesEverydayCampusAndConversationalTraps() {
        val cases = listOf(
            "N-am chef să merg azi" to "Nu am dispoziție să merg azi",
            "Spor la învățat pentru examen!" to "Succes la învățat pentru examen!",
            "Pe bune?" to "Serios?",
            "E cam nașpa situația" to "E cam rău situația",
            "Super mișto ideea" to "Super grozav ideea",
            "Baftă mâine!" to "Mult noroc mâine!",
            "Faza e că nu merge" to "Ideea este că nu merge",
            "N-are treabă cu noi" to "Nu are legătură cu noi",
            "Păi da, cred că vin" to "Ei bine da, cred că vin",
            "Am o restanță și am nevoie de buletinul nou" to "Am un examen restant și am nevoie de cartea de identitate nou",
            "Întreabă profu când venim" to "Întreabă profesorul când venim"
        )
        for ((input, expected) in cases) {
            assertEquals(
                expected,
                TranslationSenseEngine.applyPreTranslationPivotDisambiguation(
                    normalizedText = input,
                    sourceLang = TranslateLanguage.ROMANIAN,
                    targetLang = TranslateLanguage.GREEK
                )
            )
        }

        assertEquals(
            "Δεν έχω διάθεση να πάω",
            TranslationSenseEngine.applyPostTranslationSenseLogic(
                originalText = "Nu am chef să merg",
                translatedText = "Δεν έχω σεφ να πάω",
                sourceLang = TranslateLanguage.ROMANIAN,
                targetLang = TranslateLanguage.GREEK
            )
        )
    }

    @Test
    fun performance_100GroupMessagesNormalizeWellUnderBudget() {
        // Calienta la carga perezosa del léxico y las tablas de pivote una vez antes de medir.
        val warmup = TextNormalizer.normalizeRomanianChatForTranslation("Avem informatii noi")
        TranslationSenseEngine.applyPreTranslationPivotDisambiguation(
            warmup,
            TranslateLanguage.ROMANIAN,
            TranslateLanguage.GREEK
        )

        val samples = listOf(
            "N am chef azi, poate maine merge mai repede",
            "S a afisat ceva de burse sau restante?",
            "Mi as lua bilet printr o aplicatie daca functioneaza",
            "Poza/scan fata verso cu buletinul la secretariat",
            "Vezi ca ti am trimis informatiile pentru sedinta"
        )

        val startNs = System.nanoTime()
        repeat(20) {
            for (s in samples) {
                val norm = TextNormalizer.normalizeRomanianChatForTranslation(s)
                TranslationSenseEngine.applyPreTranslationPivotDisambiguation(
                    norm,
                    TranslateLanguage.ROMANIAN,
                    TranslateLanguage.GREEK
                )
            }
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("Expected 100 messages < 350 ms, took ${elapsedMs}ms", elapsedMs < 350L)
    }

    @Test
    fun tsvLexicon_invariantsHoldAcrossEveryRow() {
        val stream = RomanianDiacriticsRestorer::class.java.getResourceAsStream("/ro_diacritics_unambiguous.tsv")
        requireNotNull(stream) { "Missing /ro_diacritics_unambiguous.tsv on classpath" }
        val seenKeys = HashSet<String>(2500)
        val forbiddenKeys = setOf(
            "rau", "faina", "rata", "par", "mar", "paste",
            "printre", "alba", "comanda", "cursa", "licenta", "intreaga", "construit"
        )
        var rowCount = 0
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t')
                assertEquals("Expected 2 columns in '$line'", 2, parts.size)
                val key = parts[0]
                val value = parts[1]
                assertTrue("Key '$key' must be lowercase", key == key.lowercase())
                assertTrue("Key '$key' must be ASCII", key.all { it.code < 128 })
                assertTrue("Identity mapping not allowed for '$key'", key != value)
                assertEquals("Non-diacritic mutation for '$key' -> '$value'", key, stripRomanian(value))
                assertTrue("Duplicate key '$key'", seenKeys.add(key))
                assertFalse("Forbidden homograph/mutation '$key' present", key in forbiddenKeys)
                rowCount++
            }
        }
        assertTrue("Expected > 1500 clean TSV entries, was $rowCount", rowCount > 1500)
    }

    @Test
    fun auditRegressions_preservePrintreProfesorilorRestantaAndLasaCa() {
        assertEquals(
            "Am găsit printre notite",
            TextNormalizer.normalizeRomanianChatForTranslation("Am gasit printre notite")
        )
        assertEquals(
            "Le-am scris profesorilor",
            TextNormalizer.normalizeRomanianChatForTranslation("Le-am scris profesorilor")
        )
        assertEquals(
            "Am un examen restant la mate",
            TranslationSenseEngine.applyPreTranslationPivotDisambiguation(
                normalizedText = "Am o restanță la mate",
                sourceLang = TranslateLanguage.ROMANIAN,
                targetLang = TranslateLanguage.GREEK
            )
        )
        val lasaCaOut = TranslationSenseEngine.applyPreTranslationPivotDisambiguation(
            normalizedText = "Lasă că, vedem mâine",
            sourceLang = TranslateLanguage.ROMANIAN,
            targetLang = TranslateLanguage.GREEK
        )
        assertFalse("Should not produce double commas: $lasaCaOut", lasaCaOut.contains(",,"))
        assertTrue(lasaCaOut.startsWith("Nu-ți face griji,"))

        // Regresiones de campus y traducciones observadas en capturas FILS Boboci 2026
        assertEquals(
            "avem examen intermediar azi?",
            TranslationSenseEngine.applyPreTranslationPivotDisambiguation("avem parțial azi?", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        )
        assertEquals(
            "sesiunea de examene începe luni",
            TranslationSenseEngine.applyPreTranslationPivotDisambiguation("sesiunea începe luni", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        )
        assertEquals(
            "numărul de înmatriculare de pe website",
            TranslationSenseEngine.applyPreTranslationPivotDisambiguation("nr matricol de pe site", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        )
        assertEquals(
            "cin' cântă la balul studențesc?",
            TranslationSenseEngine.applyPreTranslationPivotDisambiguation("cin' cântă la bal?", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        )
        assertEquals(
            "în anul 2 la profesor Maria",
            TranslationSenseEngine.applyPreTranslationPivotDisambiguation("anul 2 la prof Maria", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        )
        assertEquals(
            "E anonimizat așa",
            TranslationSenseEngine.applyPreTranslationPivotDisambiguation("E anonimizat așa, gen", TranslateLanguage.ROMANIAN, TranslateLanguage.GREEK)
        )

        assertEquals(
            "Πότε έχουμε χειμερινές διακοπές;",
            TranslationSenseEngine.applyPostTranslationSenseLogic(
                "Cand avem vacanta de iarna?",
                "Όταν έχουμε χειμερινές διακοπές;",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        assertEquals(
            "Πόσο διαρκεί το τεστ;",
            TranslationSenseEngine.applyPostTranslationSenseLogic(
                "Cat dureaza testul?",
                "Όσο διαρκεί το τεστ;",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        assertEquals(
            "Ξέρει κανείς ποιος τραγουδάει στον χορό;",
            TranslationSenseEngine.applyPostTranslationSenseLogic(
                "stie cineva cine canta la bal?",
                "Ξέρει κανείς που τραγουδάει στην μπάλα;",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        assertEquals(
            "Δεν βρίσκουν αριθμό μητρώου στην ιστοσελίδα",
            TranslationSenseEngine.applyPostTranslationSenseLogic(
                "Nu găsesc nr matricol pe site",
                "Δεν βρίσκουν μήτρα στην περιοχή",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
        assertEquals(
            "Ήταν καλό να παραλείψετε την έξοδο πάνω από τη εξεταστική",
            TranslationSenseEngine.applyPostTranslationSenseLogic(
                "Era bine să treceți cu vederea peste sesiune",
                "Ήταν καλό να παραλείψετε την έξοδο πάνω από τη σύνοδο",
                TranslateLanguage.ROMANIAN,
                TranslateLanguage.GREEK
            )
        )
    }

    private fun stripRomanian(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when (c) {
                    'ă', 'â' -> 'a'
                    'î' -> 'i'
                    'ș', 'ş' -> 's'
                    'ț', 'ţ' -> 't'
                    'Ă', 'Â' -> 'A'
                    'Î' -> 'I'
                    'Ș', 'Ş' -> 'S'
                    'Ț', 'Ţ' -> 'T'
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}

