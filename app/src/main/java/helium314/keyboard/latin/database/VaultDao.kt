// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.security.vault.crypto.VaultCryptoManager


data class VaultEntry(
    val id: Long,
    val shortcut: String,
    val phrase: String,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe DAO and in-memory cache for Privacy Vault entries.
 * Stores sensitive credentials, shortcuts, and phrases strictly isolated inside heliboard.db.
 * Never writes to android.provider.UserDictionary.Words or external content providers.
 */
class VaultDao private constructor(private val db: Database) {

    interface Listener {
        fun onVaultEntriesChanged()
    }

    var listener: Listener? = null
    @Volatile
    var isSuspended: Boolean = false

    private val cache = mutableListOf<VaultEntry>().apply {
        ensureTableExists(db.writableDatabase)
        loadFromDb(this)
    }

    private fun loadFromDb(target: MutableList<VaultEntry>) {
        try {
            db.readableDatabase.query(
                TABLE,
                arrayOf(COLUMN_ID, COLUMN_SHORTCUT, COLUMN_PHRASE, COLUMN_NOTES, COLUMN_TIMESTAMP),
                null,
                null,
                null,
                null,
                "$COLUMN_TIMESTAMP DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val rawPhrase = cursor.getString(2) ?: ""
                    val rawNotes = cursor.getString(3) ?: ""
                    val decryptedPhrase = if (rawPhrase.startsWith(ENCRYPTED_PREFIX)) {
                        VaultCryptoManager.decryptBase64ToString(rawPhrase.removePrefix(ENCRYPTED_PREFIX))
                    } else rawPhrase
                    val decryptedNotes = if (rawNotes.startsWith(ENCRYPTED_PREFIX)) {
                        VaultCryptoManager.decryptBase64ToString(rawNotes.removePrefix(ENCRYPTED_PREFIX))
                    } else rawNotes

                    if (decryptedPhrase == null || isCorruptedOrGibberish(decryptedPhrase)) {
                        LogCatcher.w("VaultDao", "Skipping un-decryptable or corrupted vault entry id=${cursor.getLong(0)}")
                        continue
                    }

                    target.add(
                        VaultEntry(
                            id = cursor.getLong(0),
                            shortcut = cursor.getString(1) ?: "",
                            phrase = decryptedPhrase,
                            notes = decryptedNotes ?: "",
                            timestamp = cursor.getLong(4)
                        )
                    )
                }
            }

        } catch (t: Throwable) {
            LogCatcher.e("VaultDao", "Error loading vault entries from database", t)
        }
    }

    val count: Int get() = synchronized(this) { cache.size }

    fun getAll(): List<VaultEntry> = synchronized(this) {
        cache.toList()
    }

    fun getEntry(id: Long): VaultEntry? = synchronized(this) {
        cache.firstOrNull { it.id == id }
    }

    fun findByShortcut(shortcut: String): VaultEntry? = synchronized(this) {
        if (shortcut.isBlank()) return null
        val trimmed = shortcut.trim()
        cache.firstOrNull { it.shortcut.equals(trimmed, ignoreCase = true) }
    }

    fun findMatchingShortcuts(prefix: String): List<VaultEntry> = synchronized(this) {
        if (prefix.isBlank()) return emptyList()
        val trimmed = prefix.trim()
        cache.filter { it.shortcut.startsWith(trimmed, ignoreCase = true) }
    }

    /**
     * Finds vault suggestions for the typed query:
     * 1. Exact shortcut match (highest priority, query length >= 1)
     * 2. Shortcut prefix match (query length >= 1)
     * 3. Phrase prefix or word-in-phrase prefix match (requires query length >= 3 to avoid false positives)
     */
    private fun isDelimiter(c: Char): Boolean =
        c == ' ' || c == '.' || c == '_' || c == '-' || c == '@' || c == ',' || c == ';'

    private fun containsDelimitedTokenStartingWith(text: String, prefix: String): Boolean {
        if (text.isEmpty() || prefix.isEmpty()) return false
        val len = text.length
        val pLen = prefix.length
        if (pLen > len) return false
        var i = 0
        while (i < len) {
            while (i < len && isDelimiter(text[i])) {
                i++
            }
            if (i >= len) break
            if (text.regionMatches(i, prefix, 0, pLen, ignoreCase = true)) {
                return true
            }
            while (i < len && !isDelimiter(text[i])) {
                i++
            }
        }
        return false
    }

    /**
     * Finds vault suggestions for the typed query:
     * 1. Exact shortcut match (highest priority, query length >= 3)
     * 2. Shortcut prefix match (query length >= 3)
     * 3. Phrase prefix or delimited token match (requires query length >= 3)
     */
    fun findSuggestions(query: String): List<VaultEntry> = synchronized(this) {
        if (isSuspended || query.isBlank() || cache.isEmpty()) return emptyList()
        // Strict 4-character gating: do not process queries shorter than 4 characters (spaces included)
        if (query.length < 4) return emptyList()
        val trimmed = query.trim()
        if (trimmed.length < 4) return emptyList()
        val results = mutableListOf<VaultEntry>()
        val seenIds = mutableSetOf<Long>()

        // 1. Exact shortcut (if shortcut set, query length >= 4)
        for (entry in cache) {
            if (entry.phrase.isBlank() || isCorruptedOrGibberish(entry.phrase)) continue
            if (entry.shortcut.isNotBlank() && entry.shortcut.equals(trimmed, ignoreCase = true)) {
                results.add(entry)
                seenIds.add(entry.id)
            }
        }

        // 2. Shortcut prefix (if shortcut set, query length >= 4)
        for (entry in cache) {
            if (entry.phrase.isBlank() || isCorruptedOrGibberish(entry.phrase)) continue
            if (entry.shortcut.isNotBlank() && !seenIds.contains(entry.id) && entry.shortcut.startsWith(trimmed, ignoreCase = true)) {
                results.add(entry)
                seenIds.add(entry.id)
            }
        }

        // 3. Phrase & notes token match (for query length >= 4, allocation-free)
        for (entry in cache) {
            if (entry.phrase.isBlank() || isCorruptedOrGibberish(entry.phrase)) continue
            if (!seenIds.contains(entry.id)) {
                val p = entry.phrase
                if (p.startsWith(trimmed, ignoreCase = true) || containsDelimitedTokenStartingWith(p, trimmed)) {
                    results.add(entry)
                    seenIds.add(entry.id)
                } else if (entry.notes.isNotBlank() && containsDelimitedTokenStartingWith(entry.notes, trimmed)) {
                    results.add(entry)
                    seenIds.add(entry.id)
                }
            }
        }

        results
    }

    /**
     * Finds a matching VaultEntry based on the text immediately before the cursor.
     * Evaluates strictly after at least 4 characters (spaces included):
     * 1. Shortcut match: Checks trailing token (min 4 letters, spaces included if part of shortcut).
     * 2. Phrase match: Checks trailing text ending at cursor from 4 up to 40 characters (spaces included).
     *
     * Returns a Pair(VaultEntry, replaceLength) indicating which entry matched and how many
     * characters before cursor should be deleted and replaced upon user authorization.
     */
    fun findMatchForTextBeforeCursor(textBeforeCursor: CharSequence?): Pair<VaultEntry, Int>? = synchronized(this) {
        if (isSuspended || textBeforeCursor.isNullOrEmpty() || cache.isEmpty()) return null
        val text = textBeforeCursor.toString()
        val textLen = text.length
        if (textLen < 4) return null

        // 1. Check for shortcut on trailing word token (after space, newline, or delimiter, min 4 chars)
        var tokenStart = textLen
        while (tokenStart > 0 && !Character.isWhitespace(text[tokenStart - 1]) && !isDelimiter(text[tokenStart - 1])) {
            tokenStart--
        }
        val token = text.substring(tokenStart)
        if (token.length >= 4) {
            // Check exact shortcut match first
            for (entry in cache) {
                if (entry.shortcut.isNotBlank() && entry.shortcut.equals(token, ignoreCase = true)) {
                    if (entry.phrase.isNotBlank() && !isCorruptedOrGibberish(entry.phrase)) {
                        return Pair(entry, token.length)
                    }
                }
            }
            // Check shortcut prefix match if token length >= 4
            for (entry in cache) {
                if (entry.shortcut.isNotBlank() && entry.shortcut.startsWith(token, ignoreCase = true)) {
                    if (entry.phrase.isNotBlank() && !isCorruptedOrGibberish(entry.phrase)) {
                        return Pair(entry, token.length)
                    }
                }
            }
            // Check exact phrase match on single word token
            for (entry in cache) {
                if (entry.phrase.isNotBlank() && !isCorruptedOrGibberish(entry.phrase)) {
                    if (entry.phrase.equals(token, ignoreCase = true)) {
                        return Pair(entry, token.length)
                    }
                }
            }
        }

        // Also check if any shortcut contains spaces (min 4 chars total) and matches text before cursor
        for (entry in cache) {
            val sc = entry.shortcut
            if (sc.isNotBlank() && sc.length >= 4 && sc.contains(' ')) {
                val scLen = sc.length
                if (textLen >= scLen && text.regionMatches(textLen - scLen, sc, 0, scLen, ignoreCase = true)) {
                    val isBoundary = (textLen == scLen) ||
                            Character.isWhitespace(text[textLen - scLen - 1]) ||
                            isDelimiter(text[textLen - scLen - 1])
                    if (isBoundary && entry.phrase.isNotBlank() && !isCorruptedOrGibberish(entry.phrase)) {
                        return Pair(entry, scLen)
                    }
                }
            }
        }

        // 2. Check phrase prefix match (at least 4 characters, spaces included)
        // Check from longest candidate down to 4 characters
        val maxCandidateLen = minOf(textLen, 40)
        for (len in maxCandidateLen downTo 4) {
            val candidate = text.substring(textLen - len)
            val isBoundary = (textLen == len) ||
                    Character.isWhitespace(text[textLen - len - 1]) ||
                    isDelimiter(text[textLen - len - 1])
            if (isBoundary) {
                for (entry in cache) {
                    if (entry.phrase.isNotBlank() && !isCorruptedOrGibberish(entry.phrase)) {
                        if (entry.phrase.startsWith(candidate, ignoreCase = true)) {
                            return Pair(entry, candidate.length)
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Cross-checks a candidate word or phrase against Privacy Vault entries.
     * Evaluates strictly for candidates of at least 4 characters.
     */
    fun findMatchingEntryForWord(word: String?): VaultEntry? = synchronized(this) {
        if (isSuspended || word.isNullOrBlank() || cache.isEmpty()) return null
        val trimmed = word.trim()
        if (trimmed.length < 4) return null
        for (entry in cache) {
            if (entry.phrase.isBlank() || isCorruptedOrGibberish(entry.phrase)) continue
            if (entry.shortcut.isNotBlank() && entry.shortcut.equals(trimmed, ignoreCase = true)) {
                return entry
            }
            if (entry.phrase.equals(trimmed, ignoreCase = true)) {
                return entry
            }
        }
        return null
    }

    fun addOrUpdate(id: Long? = null, shortcut: String = "", phrase: String, notes: String = ""): Long = synchronized(this) {
        val trimmedShortcut = shortcut.trim()
        val trimmedPhrase = phrase.trim()
        val trimmedNotes = notes.trim()
        if (trimmedPhrase.isEmpty()) return -1L

        val existingIndex = if (id != null && id > 0) {
            cache.indexOfFirst { it.id == id }
        } else if (trimmedShortcut.isNotEmpty()) {
            cache.indexOfFirst { it.shortcut.isNotBlank() && it.shortcut.equals(trimmedShortcut, ignoreCase = true) }
        } else {
            -1
        }
        val now = System.currentTimeMillis()

        val encryptedPhrase = try {
            ENCRYPTED_PREFIX + VaultCryptoManager.encryptStringToBase64(trimmedPhrase)
        } catch (t: Throwable) {
            LogCatcher.e("VaultDao", "Failed to encrypt phrase, saving plaintext fallback: ${t.message}", t)
            trimmedPhrase
        }
        val encryptedNotes = if (trimmedNotes.isNotEmpty()) {
            try {
                ENCRYPTED_PREFIX + VaultCryptoManager.encryptStringToBase64(trimmedNotes)
            } catch (_: Throwable) {
                trimmedNotes
            }
        } else ""

        val cv = ContentValues().apply {
            put(COLUMN_SHORTCUT, trimmedShortcut)
            put(COLUMN_PHRASE, encryptedPhrase)
            put(COLUMN_NOTES, encryptedNotes)
            put(COLUMN_TIMESTAMP, now)
        }

        val resultId: Long
        if (existingIndex >= 0) {
            val existing = cache[existingIndex]
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(existing.id.toString()))
            resultId = existing.id
            cache[existingIndex] = VaultEntry(resultId, trimmedShortcut, trimmedPhrase, trimmedNotes, now)
            LogCatcher.i("VaultDao", "Updated vault entry id=$resultId (shortcut='$trimmedShortcut')")
        } else {
            resultId = db.writableDatabase.insert(TABLE, null, cv)
            if (resultId != -1L) {
                cache.add(0, VaultEntry(resultId, trimmedShortcut, trimmedPhrase, trimmedNotes, now))
                LogCatcher.i("VaultDao", "Added vault entry id=$resultId (shortcut='$trimmedShortcut')")
            }
        }
        listener?.onVaultEntriesChanged()
        return resultId
    }

    fun addOrUpdate(shortcut: String, phrase: String, notes: String = ""): Long {
        return addOrUpdate(null, shortcut, phrase, notes)
    }

    fun delete(id: Long): Boolean = synchronized(this) {
        val deletedRows = db.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(id.toString()))
        val success = deletedRows > 0
        if (success) {
            cache.removeAll { it.id == id }
            LogCatcher.i("VaultDao", "Deleted vault entry id=$id")
            listener?.onVaultEntriesChanged()
        }
        return success
    }

    fun clear() = synchronized(this) {
        db.writableDatabase.delete(TABLE, null, null)
        cache.clear()
        LogCatcher.i("VaultDao", "Cleared all vault entries")
        listener?.onVaultEntriesChanged()
    }

    fun reload() = synchronized(this) {
        cache.clear()
        loadFromDb(cache)
        LogCatcher.i("VaultDao", "Reloaded ${cache.size} vault entries from database")
        listener?.onVaultEntriesChanged()
    }

    companion object {
        const val TABLE = "vault_entries"
        const val COLUMN_ID = "_id"
        const val COLUMN_SHORTCUT = "SHORTCUT"
        const val COLUMN_PHRASE = "PHRASE"
        const val COLUMN_NOTES = "NOTES"
        const val COLUMN_TIMESTAMP = "TIMESTAMP"
        const val ENCRYPTED_PREFIX = "🔒enc:"


        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SHORTCUT TEXT NOT NULL,
                $COLUMN_PHRASE TEXT NOT NULL,
                $COLUMN_NOTES TEXT,
                $COLUMN_TIMESTAMP INTEGER NOT NULL
            );
        """

        const val CREATE_SHORTCUT_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_vault_shortcut ON $TABLE ($COLUMN_SHORTCUT);
        """

        fun ensureTableExists(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE)
            db.execSQL(CREATE_SHORTCUT_INDEX)
        }

        fun isCorruptedOrGibberish(text: String): Boolean {
            if (text.isEmpty()) return false
            var controlOrCorruptCount = 0
            for (c in text) {
                if (c.isISOControl() && c != '\n' && c != '\r' && c != '\t') return true
                if (c == '\uFFFD') return true
                if (c.category == CharCategory.UNASSIGNED || c.category == CharCategory.PRIVATE_USE) {
                    controlOrCorruptCount++
                }
            }
            return controlOrCorruptCount > 2
        }

        /**
         * Obscures/masks a sensitive phrase for suggestion strip display to prevent shoulder-surfing.
         * Example: "user@domain.com" -> "🔒 us****om", "secret" -> "🔒 s**t"
         */
        fun maskPhrase(phrase: String): String {
            val trimmed = phrase.trim()
            if (trimmed.isEmpty() || isCorruptedOrGibberish(trimmed)) return "🔒 ****"
            return when {
                trimmed.length > 6 -> "🔒 ${trimmed.take(2)}****${trimmed.takeLast(2)}"
                trimmed.length in 3..6 -> "🔒 ${trimmed.take(1)}**${trimmed.takeLast(1)}"
                else -> "🔒 ***"
            }
        }

        @Volatile
        private var instance: VaultDao? = null

        fun getInstance(context: Context): VaultDao {
            return instance ?: synchronized(this) {
                instance ?: VaultDao(Database.getInstance(context.applicationContext)).also {
                    instance = it
                }
            }
        }
    }
}
