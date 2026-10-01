// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import helium314.keyboard.latin.utils.LogCatcher
import java.io.File

/**
 * SQLite Database for the Security Vault sandbox.
 * Stored explicitly in context.noBackupFilesDir to prevent sensitive credentials
 * from ever being uploaded to cloud backups or external backup agents.
 */
class SecurityVaultDatabase private constructor(
    context: Context,
    dbPath: String
) : SQLiteOpenHelper(context, dbPath, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_GROUPS)
        db.execSQL(CREATE_TABLE_ENTRIES)
        db.execSQL(CREATE_TABLE_ATTACHMENTS)
        db.execSQL(CREATE_INDEX_ENTRIES_GROUP)
        db.execSQL(CREATE_INDEX_ENTRIES_URL_PKG)
        db.execSQL(CREATE_INDEX_ENTRIES_UPDATED)
        db.execSQL(CREATE_INDEX_ATTACHMENTS_ENTRY)
        LogCatcher.log('I', TAG, "Security Vault sandbox database created successfully")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Initial version is 1; future schema migrations handled here
        LogCatcher.log('I', TAG, "Upgrading Security Vault database from version $oldVersion to $newVersion")
    }

    companion object {
        private const val TAG = "SecurityVaultDatabase"
        const val DATABASE_NAME = "security_vault.db"
        const val DATABASE_VERSION = 1

        const val TABLE_GROUPS = "vault_groups"
        const val COLUMN_GROUP_UUID = "group_uuid"
        const val COLUMN_PARENT_GROUP_UUID = "parent_group_uuid"
        const val COLUMN_GROUP_NAME = "name"
        const val COLUMN_GROUP_ICON_ID = "icon_id"

        const val TABLE_ENTRIES = "vault_entries"
        const val COLUMN_ENTRY_UUID = "entry_uuid"
        const val COLUMN_ENTRY_GROUP_UUID = "group_uuid"
        const val COLUMN_ENTRY_TITLE = "title"
        const val COLUMN_ENTRY_USERNAME = "username"
        const val COLUMN_ENTRY_PASSWORD = "password_encrypted"
        const val COLUMN_ENTRY_URL_OR_PKG = "url_or_package"
        const val COLUMN_ENTRY_TOTP = "totp_secret_encrypted"
        const val COLUMN_ENTRY_NOTES = "notes_encrypted"
        const val COLUMN_ENTRY_UPDATED_AT = "updated_at"

        const val TABLE_ATTACHMENTS = "vault_attachments"
        const val COLUMN_ATTACHMENT_UUID = "attachment_uuid"
        const val COLUMN_ATTACHMENT_ENTRY_UUID = "entry_uuid"
        const val COLUMN_ATTACHMENT_FILENAME = "filename"
        const val COLUMN_ATTACHMENT_MIME_TYPE = "mime_type"
        const val COLUMN_ATTACHMENT_DATA_BLOB = "data_blob_encrypted"

        private const val CREATE_TABLE_GROUPS = """
            CREATE TABLE IF NOT EXISTS $TABLE_GROUPS (
                $COLUMN_GROUP_UUID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_PARENT_GROUP_UUID TEXT,
                $COLUMN_GROUP_NAME TEXT NOT NULL,
                $COLUMN_GROUP_ICON_ID INTEGER DEFAULT 0
            )
        """

        private const val CREATE_TABLE_ENTRIES = """
            CREATE TABLE IF NOT EXISTS $TABLE_ENTRIES (
                $COLUMN_ENTRY_UUID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_ENTRY_GROUP_UUID TEXT NOT NULL,
                $COLUMN_ENTRY_TITLE TEXT NOT NULL,
                $COLUMN_ENTRY_USERNAME TEXT,
                $COLUMN_ENTRY_PASSWORD BLOB,
                $COLUMN_ENTRY_URL_OR_PKG TEXT,
                $COLUMN_ENTRY_TOTP BLOB,
                $COLUMN_ENTRY_NOTES BLOB,
                $COLUMN_ENTRY_UPDATED_AT INTEGER NOT NULL,
                FOREIGN KEY ($COLUMN_ENTRY_GROUP_UUID) REFERENCES $TABLE_GROUPS($COLUMN_GROUP_UUID) ON DELETE CASCADE
            )
        """

        private const val CREATE_TABLE_ATTACHMENTS = """
            CREATE TABLE IF NOT EXISTS $TABLE_ATTACHMENTS (
                $COLUMN_ATTACHMENT_UUID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_ATTACHMENT_ENTRY_UUID TEXT NOT NULL,
                $COLUMN_ATTACHMENT_FILENAME TEXT NOT NULL,
                $COLUMN_ATTACHMENT_MIME_TYPE TEXT,
                $COLUMN_ATTACHMENT_DATA_BLOB BLOB,
                FOREIGN KEY ($COLUMN_ATTACHMENT_ENTRY_UUID) REFERENCES $TABLE_ENTRIES($COLUMN_ENTRY_UUID) ON DELETE CASCADE
            )
        """

        private const val CREATE_INDEX_ENTRIES_GROUP = """
            CREATE INDEX IF NOT EXISTS idx_vault_entries_group 
            ON $TABLE_ENTRIES ($COLUMN_ENTRY_GROUP_UUID)
        """

        private const val CREATE_INDEX_ENTRIES_URL_PKG = """
            CREATE INDEX IF NOT EXISTS idx_vault_entries_url_or_pkg 
            ON $TABLE_ENTRIES ($COLUMN_ENTRY_URL_OR_PKG)
        """

        private const val CREATE_INDEX_ENTRIES_UPDATED = """
            CREATE INDEX IF NOT EXISTS idx_vault_entries_updated 
            ON $TABLE_ENTRIES ($COLUMN_ENTRY_UPDATED_AT DESC)
        """

        private const val CREATE_INDEX_ATTACHMENTS_ENTRY = """
            CREATE INDEX IF NOT EXISTS idx_vault_attachments_entry 
            ON $TABLE_ATTACHMENTS ($COLUMN_ATTACHMENT_ENTRY_UUID)
        """

        @Volatile
        private var instance: SecurityVaultDatabase? = null

        fun getInstance(context: Context): SecurityVaultDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val noBackupDir = context.noBackupFilesDir
                    if (!noBackupDir.exists()) {
                        noBackupDir.mkdirs()
                    }
                    val dbFile = File(noBackupDir, DATABASE_NAME)
                    SecurityVaultDatabase(context.applicationContext, dbFile.absolutePath).also {
                        instance = it
                    }
                }
            }
        }
    }
}
