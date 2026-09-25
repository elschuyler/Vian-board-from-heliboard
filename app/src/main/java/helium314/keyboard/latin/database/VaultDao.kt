// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import helium314.keyboard.latin.utils.LogCatcher

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

    private val cache = mutableListOf<VaultEntry>().apply {
        ensureTableExists(db.writableDatabase)
        loadFromDb(this)
        LogCatcher.markComponentActive("VaultDao", "Database", "Ready (${size} entries)")
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
                    target.add(
                        VaultEntry(
                            id = cursor.getLong(0),
                            shortcut = cursor.getString(1) ?: "",
                            phrase = cursor.getString(2) ?: "",
                            notes = cursor.getString(3) ?: "",
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

    fun addOrUpdate(shortcut: String, phrase: String, notes: String = ""): Long = synchronized(this) {
        val trimmedShortcut = shortcut.trim()
        val trimmedPhrase = phrase.trim()
        val trimmedNotes = notes.trim()
        if (trimmedShortcut.isEmpty() || trimmedPhrase.isEmpty()) return -1L

        val existingIndex = cache.indexOfFirst { it.shortcut.equals(trimmedShortcut, ignoreCase = true) }
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(COLUMN_SHORTCUT, trimmedShortcut)
            put(COLUMN_PHRASE, trimmedPhrase)
            put(COLUMN_NOTES, trimmedNotes)
            put(COLUMN_TIMESTAMP, now)
        }

        val id: Long
        if (existingIndex >= 0) {
            val existing = cache[existingIndex]
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(existing.id.toString()))
            id = existing.id
            cache[existingIndex] = VaultEntry(id, trimmedShortcut, trimmedPhrase, trimmedNotes, now)
            LogCatcher.i("VaultDao", "Updated vault entry: '$trimmedShortcut'")
        } else {
            id = db.writableDatabase.insert(TABLE, null, cv)
            if (id != -1L) {
                cache.add(0, VaultEntry(id, trimmedShortcut, trimmedPhrase, trimmedNotes, now))
                LogCatcher.i("VaultDao", "Added vault entry: '$trimmedShortcut'")
            }
        }
        listener?.onVaultEntriesChanged()
        return id
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

        /**
         * Obscures/masks a sensitive phrase for suggestion strip display to prevent shoulder-surfing.
         * Example: "user@domain.com" -> "🔒 us****om", "secret" -> "🔒 s**t"
         */
        fun maskPhrase(phrase: String): String {
            val trimmed = phrase.trim()
            if (trimmed.isEmpty()) return "🔒 ****"
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
