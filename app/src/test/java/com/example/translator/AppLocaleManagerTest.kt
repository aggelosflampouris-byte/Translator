package com.example.translator

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de [AppLocaleManager], formato localizado de idiomas y paridad completa
 * de claves en los archivos de recursos XML (`values`, `values-el`, `values-ro`).
 */
class AppLocaleManagerTest {

    @Test
    fun normalizeLanguageTag_acceptsSupportedTagsAndFallsBackToSystem() {
        assertEquals(AppDefaults.APP_LANGUAGE_SYSTEM, AppLocaleManager.normalizeLanguageTag(null))
        assertEquals(AppDefaults.APP_LANGUAGE_SYSTEM, AppLocaleManager.normalizeLanguageTag(""))
        assertEquals(AppDefaults.APP_LANGUAGE_SYSTEM, AppLocaleManager.normalizeLanguageTag("system"))
        assertEquals("en", AppLocaleManager.normalizeLanguageTag(" EN "))
        assertEquals("el", AppLocaleManager.normalizeLanguageTag("el"))
        assertEquals("ro", AppLocaleManager.normalizeLanguageTag("Ro"))
        assertEquals(AppDefaults.APP_LANGUAGE_SYSTEM, AppLocaleManager.normalizeLanguageTag("fr"))
    }

    @Test
    fun resolveLocale_returnsTargetLocaleOrSystemFallback() {
        val fallback = Locale.GERMANY
        assertEquals(fallback, AppLocaleManager.resolveLocale("system", fallback))
        assertEquals("en", AppLocaleManager.resolveLocale("en", fallback).language)
        assertEquals("el", AppLocaleManager.resolveLocale("el", fallback).language)
        assertEquals("ro", AppLocaleManager.resolveLocale("ro", fallback).language)
    }

    @Test
    fun nativeLanguageLabel_returnsNativeEndonymsAndCustomSystemLabel() {
        assertEquals("Προεπιλογή Συστήματος", AppLocaleManager.nativeLanguageLabel("system", "Προεπιλογή Συστήματος"))
        assertEquals("English", AppLocaleManager.nativeLanguageLabel("en", "System Default"))
        assertEquals("Ελληνικά", AppLocaleManager.nativeLanguageLabel("el", "System Default"))
        assertEquals("Română", AppLocaleManager.nativeLanguageLabel("ro", "System Default"))
    }

    @Test
    fun formatLanguageDisplayName_respectsRequestedUiLocale() {
        val greekLocale = Locale.forLanguageTag("el")
        val romanianLocale = Locale.forLanguageTag("ro")

        assertEquals("Romanian", AppDefaults.formatLanguageDisplayName("ro", Locale.ENGLISH))
        assertEquals("Greek", AppDefaults.formatLanguageDisplayName("el", Locale.ENGLISH))
        assertTrue(AppDefaults.formatLanguageDisplayName("ro", greekLocale).contains("Ρουμαν"))
        assertTrue(AppDefaults.formatLanguageDisplayName("el", romanianLocale).contains("Greac"))
    }

    @Test
    fun stringResources_haveExactKeyParityAcrossEnglishGreekAndRomanian() {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val baseKeys = parseTranslatableStringKeys(File(resDir, "values/strings.xml"))
        val elKeys = parseTranslatableStringKeys(File(resDir, "values-el/strings.xml"))
        val roKeys = parseTranslatableStringKeys(File(resDir, "values-ro/strings.xml"))

        assertFalse(baseKeys.isEmpty())
        assertEquals("Greek missing keys=${baseKeys.keys - elKeys.keys} extra=${elKeys.keys - baseKeys.keys}", baseKeys.keys, elKeys.keys)
        assertEquals("Romanian missing keys=${baseKeys.keys - roKeys.keys} extra=${roKeys.keys - baseKeys.keys}", baseKeys.keys, roKeys.keys)

        val formatSpecRegex = Regex("""%\d+\$[sd]""")
        for ((key, baseVal) in baseKeys) {
            val expectedSpecs = formatSpecRegex.findAll(baseVal).map { it.value }.toSet()
            val elSpecs = formatSpecRegex.findAll(elKeys.getValue(key)).map { it.value }.toSet()
            val roSpecs = formatSpecRegex.findAll(roKeys.getValue(key)).map { it.value }.toSet()
            assertEquals("Greek format mismatch on $key", expectedSpecs, elSpecs)
            assertEquals("Romanian format mismatch on $key", expectedSpecs, roSpecs)
        }
    }

    private fun parseTranslatableStringKeys(file: File): Map<String, String> {
        assertTrue("Missing resource file: ${file.path}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as org.w3c.dom.Element
            if (el.getAttribute("translatable") == "false") continue
            map[el.getAttribute("name")] = el.textContent
        }
        return map
    }
}

