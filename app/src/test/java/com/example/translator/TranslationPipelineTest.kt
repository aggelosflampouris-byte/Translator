package com.example.translator

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationPipelineTest {

    private class FakeEngine(private val response: (String) -> String?) : TranslationEngine {
        var calls = 0
        override suspend fun translate(
            text: String,
            sourceLang: String,
            targetLang: String,
            hints: TranslationHints
        ): String? {
            calls++
            return response(text)
        }
    }

    // Por qué: La memoria aprendida por el usuario tiene prioridad sobre la caché y sobre el motor NMT
    @Test
    fun memoryLookup_beatsCacheAndEngine() = runTest {
        val engine = FakeEngine { "NMT: $it" }
        val pipeline = TranslationPipeline(
            engine = engine,
            memoryLookup = { _, _, _ -> "Μνήμη χρήστη" },
            cacheLookup = { _, _, _ -> "Από κρυφή μνήμη" }
        )

        val result = pipeline.translate("Acesta este un mesaj", "ro", "el")

        assertEquals("Μνήμη χρήστη", result)
        assertEquals(0, engine.calls)
    }

    // Por qué: La caché LRU evita invocar el motor NMT cuando ya se tradujo el mismo mensaje
    @Test
    fun cacheLookup_beatsEngine() = runTest {
        val engine = FakeEngine { "NMT: $it" }
        val pipeline = TranslationPipeline(
            engine = engine,
            cacheLookup = { _, _, _ -> "Από κρυφή μνήμη" }
        )

        val result = pipeline.translate("Acesta este un mesaj", "ro", "el")

        assertEquals("Από κρυφή μνήμη", result)
        assertEquals(0, engine.calls)
    }

    // Por qué: El glosario aprendido y la protección de emojis se aplican de extremo a extremo sobre la salida NMT
    @Test
    fun fullPipeline_protectsEmojisAppliesGlossaryAndStoresInCache() = runTest {
        val stored = mutableMapOf<String, String>()
        val engine = FakeEngine { input ->
            // Preserves PUA placeholder characters and translates words
            input.replace("Compania", "Εταιρεία")
        }
        val pipeline = TranslationPipeline(
            engine = engine,
            cacheStore = { src, tgt, key, value -> stored["$src|$tgt|$key"] = value },
            learnedCorrectionsApplier = { _, _, text ->
                CorrectionApplier.apply(text, listOf(TermCorrection("εταιρεία", "παρέα")))
            }
        )

        val result = pipeline.translate("Compania 🎉", "ro", "el")

        assertEquals("Παρέα 🎉", result)
        assertEquals("Παρέα 🎉", stored["ro|el|Compania 🎉"])
        assertEquals(1, engine.calls)
    }

    @Test
    fun invalidTargetScript_returnsNullAndDoesNotCache() = runTest {
        val stored = mutableMapOf<String, String>()
        val engine = FakeEngine { "Untranslated Latin output" }
        val pipeline = TranslationPipeline(
            engine = engine,
            cacheStore = { src, tgt, key, value -> stored["$src|$tgt|$key"] = value }
        )

        val result = pipeline.translate("Un mesaj în română", "ro", "el")

        assertNull(result)
        assertEquals(0, stored.size)
    }
}

