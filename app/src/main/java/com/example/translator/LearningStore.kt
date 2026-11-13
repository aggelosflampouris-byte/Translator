package com.example.translator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.Executors

/**
 * A draft the user inserted into the chat input via the reverse-translation pill.
 */
data class DraftSession(
    val sourceText: String,
    val machineTranslation: String,
    val sourceLang: String,
    val targetLang: String,
    val createdAtMillis: Long
)

/**
 * On-device, private memory of what the user has taught the translator.
 *
 * - Translation memory: sentences the user actually sent, reused verbatim next time (both directions).
 * - Learned corrections: words the user replaced in a draft, injected into preferred hints
 *   and applied deterministically once learned (since [MIN_HITS_FOR_AUTO_CORRECTION] = 1).
 *
 * Por qué: SQLite persiste el aprendizaje entre reinicios del servicio; los mapas en memoria
 * permiten consultas O(1) en el hilo de traducción sin tocar el disco.
 */
class LearningStore private constructor(context: Context) {

    companion object {
        private const val TAG = "LearningStore"

        // Por qué: Aplica de inmediato el vocabulario corregido por el usuario desde la primera edición enviada.
        const val MIN_HITS_FOR_AUTO_CORRECTION = 1

        private const val MAX_MEMORY_ENTRIES = 3000

        // Por qué: Un borrador abandonado durante minutos ya no representa la intención del envío.
        const val DRAFT_SESSION_TTL_MS = 5 * 60 * 1000L

        private val WHITESPACE_REGEX = Regex("""\s+""")

        @Volatile private var instance: LearningStore? = null

        fun get(context: Context): LearningStore {
            return instance ?: synchronized(this) {
                instance ?: LearningStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private data class CorrectionRecord(
        val sourceLang: String,
        val targetLang: String,
        val rejected: String,
        val preferred: String,
        val hits: Int,
        val updatedAt: Long
    )

    private val helper = LearningDatabaseHelper(context)
    private val diskExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private val memory = HashMap<String, String>()
    private val corrections = HashMap<String, CorrectionRecord>()

    init {
        diskExecutor.execute { loadSnapshot() }
    }

    // ── Lectura (hilo de traducción) ─────────────────────────────────────────

    fun lookupMemory(sourceLang: String, targetLang: String, text: String): String? {
        synchronized(lock) {
            return memory[memoryKey(sourceLang, targetLang, text)]
        }
    }

    /** Terms for the neural prompt, most confirmed and most recent first. */
    fun promptTerms(sourceLang: String, targetLang: String): List<TermCorrection> {
        synchronized(lock) {
            return corrections.values
                .filter { it.sourceLang == sourceLang && it.targetLang == targetLang }
                .sortedWith(compareByDescending<CorrectionRecord> { it.hits }.thenByDescending { it.updatedAt })
                .take(AppDefaults.MAX_PREFERRED_TERMS)
                .map { TermCorrection(it.rejected, it.preferred) }
        }
    }

    /** Applies confirmed corrections (hits ≥ 2) to any engine output, including ML Kit. */
    fun applyConfirmedCorrections(sourceLang: String, targetLang: String, text: String): String {
        val confirmed = synchronized(lock) {
            corrections.values
                .filter {
                    it.sourceLang == sourceLang &&
                        it.targetLang == targetLang &&
                        it.hits >= MIN_HITS_FOR_AUTO_CORRECTION
                }
                .map { TermCorrection(it.rejected, it.preferred) }
        }
        return CorrectionApplier.apply(text, confirmed)
    }

    fun stats(): Pair<Int, Int> {
        synchronized(lock) {
            return Pair(corrections.size, memory.size)
        }
    }

    // ── Aprendizaje ──────────────────────────────────────────────────────────

    /**
     * Learns from a draft the user inserted and then sent. Returns true if anything was learned.
     */
    fun learnFromSentDraft(session: DraftSession, sentText: String): Boolean {
        val feedback = CorrectionExtractor.analyze(session.machineTranslation, sentText)
        Log.d(TAG, "Draft feedback: ${feedback::class.simpleName}")

        return when (feedback) {
            is DraftFeedback.Unrelated -> false
            is DraftFeedback.Accepted -> {
                rememberBothDirections(session, sentText)
                // Por qué: Si el usuario acepta una frase con una palabra que antes corrigió, esa corrección pierde peso.
                weakenCorrectionsContradictedBy(session.sourceLang, session.targetLang, sentText)
                true
            }
            is DraftFeedback.Edited -> {
                rememberBothDirections(session, sentText)
                feedback.corrections.forEach { recordCorrection(session.sourceLang, session.targetLang, it) }
                true
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            memory.clear()
            corrections.clear()
        }
        diskExecutor.execute {
            try {
                val db = helper.writableDatabase
                db.delete(LearningDatabaseHelper.TABLE_MEMORY, null, null)
                db.delete(LearningDatabaseHelper.TABLE_CORRECTIONS, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear learning data", e)
            }
        }
    }

    // ── Detalles internos ────────────────────────────────────────────────────

    private fun rememberBothDirections(session: DraftSession, sentText: String) {
        // Por qué: La memoria se indexa por frase completa; los mensajes multilínea se traducen línea a línea.
        if (session.sourceText.contains('\n') || sentText.contains('\n')) return
        rememberTranslation(session.sourceLang, session.targetLang, session.sourceText, sentText)
        // Por qué: Si el contacto responde con la misma frase, la mejor traducción es la redacción original del usuario.
        rememberTranslation(session.targetLang, session.sourceLang, sentText, session.sourceText)
    }

    private fun rememberTranslation(sourceLang: String, targetLang: String, sourceText: String, translation: String) {
        val key = memoryKey(sourceLang, targetLang, sourceText)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            memory[key] = translation.trim()
        }
        diskExecutor.execute {
            try {
                val values = ContentValues().apply {
                    put("memory_key", key)
                    put("translation", translation.trim())
                    put("updated_at", now)
                }
                val db = helper.writableDatabase
                db.insertWithOnConflict(LearningDatabaseHelper.TABLE_MEMORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                trimMemoryIfNeeded(db)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist translation memory", e)
            }
        }
    }

    private fun recordCorrection(sourceLang: String, targetLang: String, correction: TermCorrection) {
        val key = correctionKey(sourceLang, targetLang, correction.rejected)
        val now = System.currentTimeMillis()
        val updated = synchronized(lock) {
            val existing = corrections[key]
            // Por qué: Si el usuario cambia de opinión sobre la palabra preferida, se reinicia la confianza.
            val hits = if (existing != null && existing.preferred == correction.preferred) existing.hits + 1 else 1
            CorrectionRecord(sourceLang, targetLang, correction.rejected, correction.preferred, hits, now)
                .also { corrections[key] = it }
        }
        persistCorrection(key, updated)
    }

    private fun weakenCorrectionsContradictedBy(sourceLang: String, targetLang: String, acceptedText: String) {
        val acceptedTokens = CorrectionExtractor.tokenize(acceptedText).joinToString(" ", prefix = " ", postfix = " ")
        val changed = mutableListOf<Pair<String, CorrectionRecord?>>()

        synchronized(lock) {
            val contradicted = corrections.entries.filter { (_, record) ->
                record.sourceLang == sourceLang &&
                    record.targetLang == targetLang &&
                    acceptedTokens.contains(" ${record.rejected} ")
            }
            for ((key, record) in contradicted) {
                val remaining = record.hits - 1
                if (remaining <= 0) {
                    corrections.remove(key)
                    changed.add(Pair(key, null))
                } else {
                    val weakened = record.copy(hits = remaining, updatedAt = System.currentTimeMillis())
                    corrections[key] = weakened
                    changed.add(Pair(key, weakened))
                }
            }
        }

        changed.forEach { (key, record) ->
            if (record == null) deleteCorrection(key) else persistCorrection(key, record)
        }
    }

    private fun persistCorrection(key: String, record: CorrectionRecord) {
        diskExecutor.execute {
            try {
                val values = ContentValues().apply {
                    put("correction_key", key)
                    put("source_lang", record.sourceLang)
                    put("target_lang", record.targetLang)
                    put("rejected", record.rejected)
                    put("preferred", record.preferred)
                    put("hits", record.hits)
                    put("updated_at", record.updatedAt)
                }
                helper.writableDatabase.insertWithOnConflict(
                    LearningDatabaseHelper.TABLE_CORRECTIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist correction", e)
            }
        }
    }

    private fun deleteCorrection(key: String) {
        diskExecutor.execute {
            try {
                helper.writableDatabase.delete(
                    LearningDatabaseHelper.TABLE_CORRECTIONS, "correction_key = ?", arrayOf(key)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete correction", e)
            }
        }
    }

    private fun trimMemoryIfNeeded(db: SQLiteDatabase) {
        val count = android.database.DatabaseUtils.queryNumEntries(db, LearningDatabaseHelper.TABLE_MEMORY)
        if (count <= MAX_MEMORY_ENTRIES) return

        // Por qué: Se eliminan las frases más antiguas (LRU) tanto del disco como del mapa en memoria.
        val oldestKeys = mutableListOf<String>()
        db.query(
            LearningDatabaseHelper.TABLE_MEMORY, arrayOf("memory_key"),
            null, null, null, null, "updated_at ASC", (count - MAX_MEMORY_ENTRIES).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) oldestKeys.add(cursor.getString(0))
        }
        oldestKeys.forEach { key ->
            db.delete(LearningDatabaseHelper.TABLE_MEMORY, "memory_key = ?", arrayOf(key))
        }
        synchronized(lock) {
            oldestKeys.forEach { memory.remove(it) }
        }
    }

    private fun loadSnapshot() {
        try {
            val db = helper.readableDatabase
            val loadedMemory = HashMap<String, String>()
            db.query(LearningDatabaseHelper.TABLE_MEMORY, arrayOf("memory_key", "translation"), null, null, null, null, null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        loadedMemory[cursor.getString(0)] = cursor.getString(1)
                    }
                }

            val loadedCorrections = HashMap<String, CorrectionRecord>()
            db.query(
                LearningDatabaseHelper.TABLE_CORRECTIONS,
                arrayOf("correction_key", "source_lang", "target_lang", "rejected", "preferred", "hits", "updated_at"),
                null, null, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    loadedCorrections[cursor.getString(0)] = CorrectionRecord(
                        sourceLang = cursor.getString(1),
                        targetLang = cursor.getString(2),
                        rejected = cursor.getString(3),
                        preferred = cursor.getString(4),
                        hits = cursor.getInt(5),
                        updatedAt = cursor.getLong(6)
                    )
                }
            }

            synchronized(lock) {
                // Por qué: putAll conserva lo aprendido en memoria antes de que terminara la carga del disco.
                memory.putAll(loadedMemory.filterKeys { it !in memory })
                corrections.putAll(loadedCorrections.filterKeys { it !in corrections })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load learning snapshot", e)
        }
    }

    private fun memoryKey(sourceLang: String, targetLang: String, text: String): String {
        return "${sourceLang}|${targetLang}|${normalizeForKey(text)}"
    }

    private fun correctionKey(sourceLang: String, targetLang: String, rejected: String): String {
        return "${sourceLang}|${targetLang}|${rejected}"
    }

    private fun normalizeForKey(text: String): String {
        return text.trim().lowercase().replace(WHITESPACE_REGEX, " ")
    }
}

private class LearningDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "adaptive_learning.db", null, 1) {

    companion object {
        const val TABLE_MEMORY = "translation_memory"
        const val TABLE_CORRECTIONS = "learned_corrections"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_MEMORY (" +
                "memory_key TEXT PRIMARY KEY, " +
                "translation TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE $TABLE_CORRECTIONS (" +
                "correction_key TEXT PRIMARY KEY, " +
                "source_lang TEXT NOT NULL, " +
                "target_lang TEXT NOT NULL, " +
                "rejected TEXT NOT NULL, " +
                "preferred TEXT NOT NULL, " +
                "hits INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Por qué: Versión inicial del esquema; futuras migraciones se añadirán aquí sin borrar datos.
    }
}
