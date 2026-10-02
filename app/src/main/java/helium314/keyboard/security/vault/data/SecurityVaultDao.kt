// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.core.database.sqlite.transaction
import helium314.keyboard.latin.utils.LogCatcher

/**
 * Thread-safe Data Access Object for the Security Vault sandbox database.
 * Provides optimized, indexed queries for in-keyboard auto-matching,
 * folder tree rendering, and full CRUD operations in Settings.
 */
class SecurityVaultDao private constructor(private val dbHelper: SecurityVaultDatabase) {

    interface Listener {
        fun onSecurityVaultDataChanged()
    }

    private val listeners = mutableListOf<Listener>()

    fun addListener(listener: Listener) = synchronized(listeners) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) = synchronized(listeners) {
        listeners.remove(listener)
    }

    private fun notifyDataChanged() {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) {
            try {
                l.onSecurityVaultDataChanged()
            } catch (t: Throwable) {
                LogCatcher.log('E', TAG, "Error invoking vault listener: ${t.message}", t)
            }
        }
    }

    // --- Groups (Folders) CRUD ---

    fun insertOrUpdateGroup(group: VaultGroupEntity): Boolean = synchronized(this) {
        try {
            val cv = ContentValues().apply {
                put(SecurityVaultDatabase.COLUMN_GROUP_UUID, group.groupUuid)
                put(SecurityVaultDatabase.COLUMN_PARENT_GROUP_UUID, group.parentGroupUuid)
                put(SecurityVaultDatabase.COLUMN_GROUP_NAME, group.name)
                put(SecurityVaultDatabase.COLUMN_GROUP_ICON_ID, group.iconId)
            }
            val rowId = dbHelper.writableDatabase.insertWithOnConflict(
                SecurityVaultDatabase.TABLE_GROUPS,
                null,
                cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
            val success = rowId != -1L
            if (success) notifyDataChanged()
            return success
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "insertOrUpdateGroup error: ${t.message}", t)
            return false
        }
    }

    fun deleteGroup(groupUuid: String): Boolean = synchronized(this) {
        try {
            val rows = dbHelper.writableDatabase.delete(
                SecurityVaultDatabase.TABLE_GROUPS,
                "${SecurityVaultDatabase.COLUMN_GROUP_UUID} = ?",
                arrayOf(groupUuid)
            )
            val success = rows > 0
            if (success) notifyDataChanged()
            return success
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "deleteGroup error: ${t.message}", t)
            return false
        }
    }

    fun getGroup(groupUuid: String): VaultGroupEntity? = synchronized(this) {
        try {
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_GROUPS,
                null,
                "${SecurityVaultDatabase.COLUMN_GROUP_UUID} = ?",
                arrayOf(groupUuid),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return parseGroup(cursor)
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getGroup error: ${t.message}", t)
        }
        return null
    }

    fun getAllGroups(): List<VaultGroupEntity> = synchronized(this) {
        val result = mutableListOf<VaultGroupEntity>()
        try {
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_GROUPS,
                null,
                null,
                null,
                null,
                null,
                "${SecurityVaultDatabase.COLUMN_GROUP_NAME} ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(parseGroup(cursor))
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getAllGroups error: ${t.message}", t)
        }
        return result
    }

    fun getChildGroups(parentGroupUuid: String?): List<VaultGroupEntity> = synchronized(this) {
        val result = mutableListOf<VaultGroupEntity>()
        try {
            val selection = if (parentGroupUuid == null) {
                "${SecurityVaultDatabase.COLUMN_PARENT_GROUP_UUID} IS NULL"
            } else {
                "${SecurityVaultDatabase.COLUMN_PARENT_GROUP_UUID} = ?"
            }
            val selectionArgs = if (parentGroupUuid == null) null else arrayOf(parentGroupUuid)

            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_GROUPS,
                null,
                selection,
                selectionArgs,
                null,
                null,
                "${SecurityVaultDatabase.COLUMN_GROUP_NAME} ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(parseGroup(cursor))
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getChildGroups error: ${t.message}", t)
        }
        return result
    }

    fun getGroupCount(): Int = synchronized(this) {
        try {
            dbHelper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ${SecurityVaultDatabase.TABLE_GROUPS}",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getInt(0)
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getGroupCount error: ${t.message}", t)
        }
        return 0
    }

    // --- Entries CRUD ---

    fun insertOrUpdateEntry(entry: VaultEntryEntity): Boolean = synchronized(this) {
        try {
            val cv = ContentValues().apply {
                put(SecurityVaultDatabase.COLUMN_ENTRY_UUID, entry.entryUuid)
                put(SecurityVaultDatabase.COLUMN_ENTRY_GROUP_UUID, entry.groupUuid)
                put(SecurityVaultDatabase.COLUMN_ENTRY_TITLE, entry.title)
                put(SecurityVaultDatabase.COLUMN_ENTRY_USERNAME, entry.username)
                put(SecurityVaultDatabase.COLUMN_ENTRY_PASSWORD, entry.passwordEncrypted)
                put(SecurityVaultDatabase.COLUMN_ENTRY_URL_OR_PKG, entry.urlOrPackage)
                put(SecurityVaultDatabase.COLUMN_ENTRY_TOTP, entry.totpSecretEncrypted)
                put(SecurityVaultDatabase.COLUMN_ENTRY_NOTES, entry.notesEncrypted)
                put(SecurityVaultDatabase.COLUMN_ENTRY_UPDATED_AT, entry.updatedAt)
            }
            val rowId = dbHelper.writableDatabase.insertWithOnConflict(
                SecurityVaultDatabase.TABLE_ENTRIES,
                null,
                cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
            val success = rowId != -1L
            if (success) notifyDataChanged()
            return success
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "insertOrUpdateEntry error: ${t.message}", t)
            return false
        }
    }

    fun deleteEntry(entryUuid: String): Boolean = synchronized(this) {
        try {
            val rows = dbHelper.writableDatabase.delete(
                SecurityVaultDatabase.TABLE_ENTRIES,
                "${SecurityVaultDatabase.COLUMN_ENTRY_UUID} = ?",
                arrayOf(entryUuid)
            )
            val success = rows > 0
            if (success) notifyDataChanged()
            return success
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "deleteEntry error: ${t.message}", t)
            return false
        }
    }

    fun getEntry(entryUuid: String): VaultEntryEntity? = synchronized(this) {
        try {
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_ENTRIES,
                null,
                "${SecurityVaultDatabase.COLUMN_ENTRY_UUID} = ?",
                arrayOf(entryUuid),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return parseEntry(cursor)
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getEntry error: ${t.message}", t)
        }
        return null
    }

    fun getAllEntries(): List<VaultEntryEntity> = synchronized(this) {
        val result = mutableListOf<VaultEntryEntity>()
        try {
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_ENTRIES,
                null,
                null,
                null,
                null,
                null,
                "${SecurityVaultDatabase.COLUMN_ENTRY_TITLE} COLLATE NOCASE ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(parseEntry(cursor))
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getAllEntries error: ${t.message}", t)
        }
        return result
    }

    fun getEntriesByGroup(groupUuid: String): List<VaultEntryEntity> = synchronized(this) {
        val result = mutableListOf<VaultEntryEntity>()
        try {
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_ENTRIES,
                null,
                "${SecurityVaultDatabase.COLUMN_ENTRY_GROUP_UUID} = ?",
                arrayOf(groupUuid),
                null,
                null,
                "${SecurityVaultDatabase.COLUMN_ENTRY_TITLE} COLLATE NOCASE ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(parseEntry(cursor))
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getEntriesByGroup error: ${t.message}", t)
        }
        return result
    }

    fun getRecentEntries(limit: Int = 15): List<VaultEntryEntity> = synchronized(this) {
        val result = mutableListOf<VaultEntryEntity>()
        try {
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_ENTRIES,
                null,
                null,
                null,
                null,
                null,
                "${SecurityVaultDatabase.COLUMN_ENTRY_UPDATED_AT} DESC",
                limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(parseEntry(cursor))
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getRecentEntries error: ${t.message}", t)
        }
        return result
    }

    /**
     * Fast indexed search matching active package name or web domain hint.
     * Used by SuggestionStripView to render instant credential pills.
     */
    fun findByPackageOrUrl(query: String): List<VaultEntryEntity> = synchronized(this) {
        if (query.isBlank()) return emptyList()
        val trimmed = query.trim()
        val result = mutableListOf<VaultEntryEntity>()
        try {
            val wildQuery = "%$trimmed%"
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_ENTRIES,
                null,
                "${SecurityVaultDatabase.COLUMN_ENTRY_URL_OR_PKG} LIKE ? OR ${SecurityVaultDatabase.COLUMN_ENTRY_TITLE} LIKE ?",
                arrayOf(wildQuery, wildQuery),
                null,
                null,
                "${SecurityVaultDatabase.COLUMN_ENTRY_TITLE} COLLATE NOCASE ASC",
                "10"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(parseEntry(cursor))
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "findByPackageOrUrl error: ${t.message}", t)
        }
        return result
    }

    fun getEntryCount(): Int = synchronized(this) {
        try {
            dbHelper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ${SecurityVaultDatabase.TABLE_ENTRIES}",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getInt(0)
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getEntryCount error: ${t.message}", t)
        }
        return 0
    }

    // --- Attachments CRUD ---

    fun insertOrUpdateAttachment(attachment: VaultAttachmentEntity): Boolean = synchronized(this) {
        try {
            val cv = ContentValues().apply {
                put(SecurityVaultDatabase.COLUMN_ATTACHMENT_UUID, attachment.attachmentUuid)
                put(SecurityVaultDatabase.COLUMN_ATTACHMENT_ENTRY_UUID, attachment.entryUuid)
                put(SecurityVaultDatabase.COLUMN_ATTACHMENT_FILENAME, attachment.filename)
                put(SecurityVaultDatabase.COLUMN_ATTACHMENT_MIME_TYPE, attachment.mimeType)
                put(SecurityVaultDatabase.COLUMN_ATTACHMENT_DATA_BLOB, attachment.dataBlobEncrypted)
            }
            val rowId = dbHelper.writableDatabase.insertWithOnConflict(
                SecurityVaultDatabase.TABLE_ATTACHMENTS,
                null,
                cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
            return rowId != -1L
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "insertOrUpdateAttachment error: ${t.message}", t)
            return false
        }
    }

    fun deleteAttachment(attachmentUuid: String): Boolean = synchronized(this) {
        try {
            val rows = dbHelper.writableDatabase.delete(
                SecurityVaultDatabase.TABLE_ATTACHMENTS,
                "${SecurityVaultDatabase.COLUMN_ATTACHMENT_UUID} = ?",
                arrayOf(attachmentUuid)
            )
            return rows > 0
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "deleteAttachment error: ${t.message}", t)
            return false
        }
    }

    fun getAttachmentsForEntry(entryUuid: String): List<VaultAttachmentEntity> = synchronized(this) {
        val result = mutableListOf<VaultAttachmentEntity>()
        try {
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_ATTACHMENTS,
                null,
                "${SecurityVaultDatabase.COLUMN_ATTACHMENT_ENTRY_UUID} = ?",
                arrayOf(entryUuid),
                null,
                null,
                "${SecurityVaultDatabase.COLUMN_ATTACHMENT_FILENAME} ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(parseAttachment(cursor))
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getAttachmentsForEntry error: ${t.message}", t)
        }
        return result
    }

    fun getAttachment(attachmentUuid: String): VaultAttachmentEntity? = synchronized(this) {
        try {
            dbHelper.readableDatabase.query(
                SecurityVaultDatabase.TABLE_ATTACHMENTS,
                null,
                "${SecurityVaultDatabase.COLUMN_ATTACHMENT_UUID} = ?",
                arrayOf(attachmentUuid),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return parseAttachment(cursor)
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "getAttachment error: ${t.message}", t)
        }
        return null
    }

    // --- Bulk Operations (Used by KDBX Import & Sync Engine) ---

    fun replaceAllWith(
        groups: List<VaultGroupEntity>,
        entries: List<VaultEntryEntity>,
        attachments: List<VaultAttachmentEntity>
    ): Boolean = synchronized(this) {
        try {
            dbHelper.writableDatabase.transaction {
                execSQL("DELETE FROM ${SecurityVaultDatabase.TABLE_ATTACHMENTS}")
                execSQL("DELETE FROM ${SecurityVaultDatabase.TABLE_ENTRIES}")
                execSQL("DELETE FROM ${SecurityVaultDatabase.TABLE_GROUPS}")

                for (g in groups) {
                    val cv = ContentValues().apply {
                        put(SecurityVaultDatabase.COLUMN_GROUP_UUID, g.groupUuid)
                        put(SecurityVaultDatabase.COLUMN_PARENT_GROUP_UUID, g.parentGroupUuid)
                        put(SecurityVaultDatabase.COLUMN_GROUP_NAME, g.name)
                        put(SecurityVaultDatabase.COLUMN_GROUP_ICON_ID, g.iconId)
                    }
                    insert(SecurityVaultDatabase.TABLE_GROUPS, null, cv)
                }

                for (e in entries) {
                    val cv = ContentValues().apply {
                        put(SecurityVaultDatabase.COLUMN_ENTRY_UUID, e.entryUuid)
                        put(SecurityVaultDatabase.COLUMN_ENTRY_GROUP_UUID, e.groupUuid)
                        put(SecurityVaultDatabase.COLUMN_ENTRY_TITLE, e.title)
                        put(SecurityVaultDatabase.COLUMN_ENTRY_USERNAME, e.username)
                        put(SecurityVaultDatabase.COLUMN_ENTRY_PASSWORD, e.passwordEncrypted)
                        put(SecurityVaultDatabase.COLUMN_ENTRY_URL_OR_PKG, e.urlOrPackage)
                        put(SecurityVaultDatabase.COLUMN_ENTRY_TOTP, e.totpSecretEncrypted)
                        put(SecurityVaultDatabase.COLUMN_ENTRY_NOTES, e.notesEncrypted)
                        put(SecurityVaultDatabase.COLUMN_ENTRY_UPDATED_AT, e.updatedAt)
                    }
                    insert(SecurityVaultDatabase.TABLE_ENTRIES, null, cv)
                }

                for (a in attachments) {
                    val cv = ContentValues().apply {
                        put(SecurityVaultDatabase.COLUMN_ATTACHMENT_UUID, a.attachmentUuid)
                        put(SecurityVaultDatabase.COLUMN_ATTACHMENT_ENTRY_UUID, a.entryUuid)
                        put(SecurityVaultDatabase.COLUMN_ATTACHMENT_FILENAME, a.filename)
                        put(SecurityVaultDatabase.COLUMN_ATTACHMENT_MIME_TYPE, a.mimeType)
                        put(SecurityVaultDatabase.COLUMN_ATTACHMENT_DATA_BLOB, a.dataBlobEncrypted)
                    }
                    insert(SecurityVaultDatabase.TABLE_ATTACHMENTS, null, cv)
                }
            }
            LogCatcher.log('I', TAG, "Bulk replaced vault data: ${groups.size} groups, ${entries.size} entries, ${attachments.size} attachments")
            notifyDataChanged()
            return true
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "replaceAllWith transaction error: ${t.message}", t)
            return false
        }
    }

    fun clearAll(): Boolean = synchronized(this) {
        try {
            dbHelper.writableDatabase.transaction {
                execSQL("DELETE FROM ${SecurityVaultDatabase.TABLE_ATTACHMENTS}")
                execSQL("DELETE FROM ${SecurityVaultDatabase.TABLE_ENTRIES}")
                execSQL("DELETE FROM ${SecurityVaultDatabase.TABLE_GROUPS}")
            }
            LogCatcher.log('I', TAG, "Security Vault database completely cleared")
            notifyDataChanged()
            return true
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "clearAll error: ${t.message}", t)
            return false
        }
    }

    // --- Cursor Parsers ---

    private fun parseGroup(cursor: Cursor): VaultGroupEntity {
        return VaultGroupEntity(
            groupUuid = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_GROUP_UUID)),
            parentGroupUuid = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_PARENT_GROUP_UUID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_GROUP_NAME)),
            iconId = cursor.getInt(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_GROUP_ICON_ID))
        )
    }

    private fun parseEntry(cursor: Cursor): VaultEntryEntity {
        val passCol = cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_PASSWORD)
        val totpCol = cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_TOTP)
        val notesCol = cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_NOTES)

        return VaultEntryEntity(
            entryUuid = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_UUID)),
            groupUuid = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_GROUP_UUID)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_TITLE)),
            username = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_USERNAME)),
            passwordEncrypted = if (cursor.isNull(passCol)) null else cursor.getBlob(passCol),
            urlOrPackage = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_URL_OR_PKG)),
            totpSecretEncrypted = if (cursor.isNull(totpCol)) null else cursor.getBlob(totpCol),
            notesEncrypted = if (cursor.isNull(notesCol)) null else cursor.getBlob(notesCol),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ENTRY_UPDATED_AT))
        )
    }

    private fun parseAttachment(cursor: Cursor): VaultAttachmentEntity {
        val blobCol = cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ATTACHMENT_DATA_BLOB)
        return VaultAttachmentEntity(
            attachmentUuid = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ATTACHMENT_UUID)),
            entryUuid = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ATTACHMENT_ENTRY_UUID)),
            filename = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ATTACHMENT_FILENAME)),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(SecurityVaultDatabase.COLUMN_ATTACHMENT_MIME_TYPE)),
            dataBlobEncrypted = if (cursor.isNull(blobCol)) null else cursor.getBlob(blobCol)
        )
    }

    companion object {
        private const val TAG = "SecurityVaultDao"

        @Volatile
        private var instance: SecurityVaultDao? = null

        fun getInstance(context: Context): SecurityVaultDao {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val db = SecurityVaultDatabase.getInstance(context)
                    SecurityVaultDao(db).also {
                        instance = it
                    }
                }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                SecurityVaultDatabase.resetInstance()
                instance = null
            }
        }
    }
}
