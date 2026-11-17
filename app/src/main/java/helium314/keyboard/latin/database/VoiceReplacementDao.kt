// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import helium314.keyboard.latin.utils.LogCatcher
import java.util.regex.Pattern

data class VoiceReplacementEntry(
    val id: Long,
    val originalWord: String,
    val replacementWord: String,
    val isWholeWord: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe DAO and in-memory cache for Word Improvement dictionary rules.
 * Maps misheard or phonetic words to user's desired terms (e.g. "knit" -> "need").
 */
class VoiceReplacementDao private constructor(private val db: Database) {

    interface Listener {
        fun onReplacementsChanged()
    }

    var listener: Listener? = null

    private val cache = mutableListOf<VoiceReplacementEntry>().apply {
        ensureTableExists(db.writableDatabase)
        loadFromDb(this)
    }

    private fun loadFromDb(target: MutableList<VoiceReplacementEntry>) {
        try {
            db.readableDatabase.query(
                TABLE,
                arrayOf(COLUMN_ID, COLUMN_ORIGINAL, COLUMN_REPLACEMENT, COLUMN_WHOLE_WORD, COLUMN_TIMESTAMP),
                null,
                null,
                null,
                null,
                "$COLUMN_TIMESTAMP DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    target.add(
                        VoiceReplacementEntry(
                            id = cursor.getLong(0),
                            originalWord = cursor.getString(1) ?: "",
                            replacementWord = cursor.getString(2) ?: "",
                            isWholeWord = cursor.getInt(3) != 0,
                            timestamp = cursor.getLong(4)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            LogCatcher.e("VoiceReplacementDao", "Error loading voice replacements from database", t)
        }
    }

    val count: Int get() = synchronized(this) { cache.size }

    fun getAll(): List<VoiceReplacementEntry> = synchronized(this) {
        cache.toList()
    }

    fun getEntry(id: Long): VoiceReplacementEntry? = synchronized(this) {
        cache.firstOrNull { it.id == id }
    }

    fun addOrUpdate(originalWord: String, replacementWord: String, isWholeWord: Boolean = true): Long = synchronized(this) {
        val trimmedOrig = originalWord.trim()
        val trimmedRepl = replacementWord.trim()
        if (trimmedOrig.isEmpty() || trimmedRepl.isEmpty()) return -1L

        val existingIndex = cache.indexOfFirst { it.originalWord.equals(trimmedOrig, ignoreCase = true) }
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(COLUMN_ORIGINAL, trimmedOrig)
            put(COLUMN_REPLACEMENT, trimmedRepl)
            put(COLUMN_WHOLE_WORD, if (isWholeWord) 1 else 0)
            put(COLUMN_TIMESTAMP, now)
        }

        val id: Long
        if (existingIndex >= 0) {
            val existing = cache[existingIndex]
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(existing.id.toString()))
            id = existing.id
            cache[existingIndex] = VoiceReplacementEntry(id, trimmedOrig, trimmedRepl, isWholeWord, now)
            LogCatcher.i("VoiceReplacementDao", "Updated replacement: '$trimmedOrig' -> '$trimmedRepl'")
        } else {
            id = db.writableDatabase.insert(TABLE, null, cv)
            if (id != -1L) {
                cache.add(0, VoiceReplacementEntry(id, trimmedOrig, trimmedRepl, isWholeWord, now))
                LogCatcher.i("VoiceReplacementDao", "Added replacement: '$trimmedOrig' -> '$trimmedRepl'")
            }
        }
        listener?.onReplacementsChanged()
        return id
    }

    fun delete(id: Long): Boolean = synchronized(this) {
        val deleted = db.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(id.toString())) > 0
        if (deleted) {
            cache.removeAll { it.id == id }
            LogCatcher.i("VoiceReplacementDao", "Deleted replacement rule id $id")
            listener?.onReplacementsChanged()
        }
        deleted
    }

    fun clear(): Unit = synchronized(this) {
        db.writableDatabase.delete(TABLE, null, null)
        cache.clear()
        LogCatcher.i("VoiceReplacementDao", "Cleared all voice replacements")
        listener?.onReplacementsChanged()
    }

    fun reload(): Unit = synchronized(this) {
        cache.clear()
        loadFromDb(cache)
        LogCatcher.i("VoiceReplacementDao", "Reloaded cache (${cache.size} rules)")
        listener?.onReplacementsChanged()
    }

    data class ReplacementResult(
        val text: String,
        val protectedMask: BooleanArray
    )

    /**
     * Applies configured phonetic replacements onto recognized speech text.
     * Rules run longest-first, respect whole-word boundaries, tolerate Whisper's
     * spelled-letter punctuation/hyphens (e.g. "S, K, P." -> "skp"), and track
     * replaced spans so downstream Voice Actions do not overwrite custom words.
     */
    fun applyReplacements(inputText: String): String = applyReplacementsWithMask(inputText).text

    fun applyReplacementsWithMask(inputText: String): ReplacementResult = synchronized(this) {
        if (cache.isEmpty() || inputText.isEmpty()) {
            return ReplacementResult(inputText, BooleanArray(inputText.length))
        }
        val sortedEntries = cache
            .filter { it.originalWord.isNotEmpty() }
            .sortedWith(
                compareByDescending<VoiceReplacementEntry> { it.originalWord.trim().length }
                    .thenByDescending { it.timestamp }
            )

        var currentText = inputText
        var currentMask = BooleanArray(inputText.length)

        for (entry in sortedEntries) {
            val regex = buildEntryRegex(entry) ?: continue
            val updated = replaceUnmasked(
                text = currentText,
                mask = currentMask,
                regex = regex,
                replacement = entry.replacementWord,
                markReplacementProtected = true
            )
            currentText = updated.text
            currentMask = updated.protectedMask
        }
        return ReplacementResult(currentText, currentMask)
    }

    private fun buildEntryRegex(entry: VoiceReplacementEntry): Regex? {
        val trimmed = entry.originalWord.trim()
        if (trimmed.isEmpty()) return null
        return if (entry.isWholeWord) {
            val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
            val body = if (tokens.size > 1) {
                tokens.joinToString(SPELLED_SEPARATOR_REGEX) { Pattern.quote(it) }
            } else {
                Pattern.quote(trimmed)
            }
            Regex("(?i)(?<![\\p{L}\\p{N}_])$body(?![\\p{L}\\p{N}_])")
        } else {
            Regex(Pattern.quote(trimmed), RegexOption.IGNORE_CASE)
        }
    }

    private fun replaceUnmasked(
        text: String,
        mask: BooleanArray,
        regex: Regex,
        replacement: String,
        markReplacementProtected: Boolean
    ): ReplacementResult {
        val matches = regex.findAll(text).filter { match ->
            val range = match.range
            !range.isEmpty() && range.none { idx -> idx < mask.size && mask[idx] }
        }.toList()

        if (matches.isEmpty()) return ReplacementResult(text, mask)

        val sb = StringBuilder(text.length + matches.size * replacement.length)
        val maskOut = ArrayList<Boolean>(text.length + matches.size * replacement.length)
        var cursor = 0

        for (match in matches) {
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (start < cursor) continue
            for (i in cursor until start) {
                sb.append(text[i])
                maskOut.add(i < mask.size && mask[i])
            }
            sb.append(replacement)
            repeat(replacement.length) {
                maskOut.add(markReplacementProtected)
            }
            cursor = endExclusive
        }
        for (i in cursor until text.length) {
            sb.append(text[i])
            maskOut.add(i < mask.size && mask[i])
        }

        return ReplacementResult(sb.toString(), maskOut.toBooleanArray())
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val SPELLED_SEPARATOR_REGEX = "(?:[,\\.\\-\\s]+)"

        const val TABLE = "VOICE_REPLACEMENTS"
        const val COLUMN_ID = "_id"
        const val COLUMN_ORIGINAL = "ORIGINAL_WORD"
        const val COLUMN_REPLACEMENT = "REPLACEMENT_WORD"
        const val COLUMN_WHOLE_WORD = "IS_WHOLE_WORD"
        const val COLUMN_TIMESTAMP = "TIMESTAMP"

        const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_ORIGINAL TEXT NOT NULL, " +
                "$COLUMN_REPLACEMENT TEXT NOT NULL, " +
                "$COLUMN_WHOLE_WORD INTEGER NOT NULL DEFAULT 1, " +
                "$COLUMN_TIMESTAMP INTEGER NOT NULL)"

        private var instance: VoiceReplacementDao? = null

        fun ensureTableExists(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE)
        }

        fun getInstance(context: Context): VoiceReplacementDao {
            if (instance == null) {
                instance = VoiceReplacementDao(Database.getInstance(context.applicationContext))
            }
            return instance!!
        }
    }
}
