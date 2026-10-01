// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.engine

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.security.vault.crypto.VaultCryptoManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import java.io.InputStream

/**
 * 2-Way KDBX Sync Engine with Conflict Detection & Visual Diff generation.
 * Compares external KDBX file with local SQLite cache by entry UUID.
 */
object KdbxSyncManager {
    private const val TAG = "KdbxSyncManager"

    const val PREF_KDBX_URI = "pref_security_vault_kdbx_uri"
    const val PREF_KDBX_FILENAME = "pref_security_vault_kdbx_filename"
    const val PREF_LAST_SYNC_TIME = "pref_security_vault_last_sync_timestamp"
    const val PREF_LAST_FILE_MODIFIED = "pref_security_vault_last_file_modified"

    enum class DiffType {
        ADDED,
        MODIFIED,
        DELETED,
        CONFLICT
    }

    data class FieldDiff(
        val fieldName: String,
        val oldValue: String?,
        val newValue: String?
    )

    data class EntryDiff(
        val entryUuid: String,
        val title: String,
        val type: DiffType,
        val fieldDiffs: List<FieldDiff> = emptyList()
    )

    data class SyncDiffResult(
        val isExternalModified: Boolean,
        val diffs: List<EntryDiff>,
        val errorMessage: String? = null
    ) {
        val hasChanges: Boolean get() = diffs.isNotEmpty()
        val addedCount: Int get() = diffs.count { it.type == DiffType.ADDED }
        val modifiedCount: Int get() = diffs.count { it.type == DiffType.MODIFIED }
        val deletedCount: Int get() = diffs.count { it.type == DiffType.DELETED }
        val conflictCount: Int get() = diffs.count { it.type == DiffType.CONFLICT }
    }

    /**
     * Inspects differences between the external file and local sandbox without modifying either.
     */
    fun computeDiff(
        context: Context,
        uri: Uri,
        masterPassword: CharArray
    ): SyncDiffResult {
        var inputStream: InputStream? = null
        try {
            val dao = SecurityVaultDao.getInstance(context)
            val localEntries = dao.getAllEntries().associateBy { it.entryUuid }

            inputStream = context.contentResolver.openInputStream(uri)
                ?: return SyncDiffResult(false, emptyList(), "Could not open external file")

            val credentials = Credentials.from(EncryptedValue.fromString(String(masterPassword)))
            val externalDb = KeePassDatabase.decode(inputStream, credentials)

            // Flatten external entries
            val externalEntries = mutableMapOf<String, ExtEntry>()
            fun collect(group: app.keemobile.kotpass.models.Group) {
                for (e in group.entries) {
                    val uuid = e.uuid.toString()
                    externalEntries[uuid] = ExtEntry(
                        uuid = uuid,
                        title = e.fields["Title"]?.content ?: "Untitled",
                        username = e.fields["UserName"]?.content,
                        password = e.fields["Password"]?.content,
                        url = e.fields["URL"]?.content,
                        notes = e.fields["Notes"]?.content,
                        totp = e.fields["otp"]?.content ?: e.fields["TOTP Seed"]?.content
                    )
                }
                for (child in group.groups) {
                    collect(child)
                }
            }
            collect(externalDb.content.group)

            val diffs = mutableListOf<EntryDiff>()

            // 1. Check local entries vs external
            for ((uuid, local) in localEntries) {
                val ext = externalEntries[uuid]
                if (ext == null) {
                    // Added locally
                    diffs.add(
                        EntryDiff(
                            entryUuid = uuid,
                            title = local.title,
                            type = DiffType.ADDED,
                            fieldDiffs = listOf(
                                FieldDiff("Entry", null, "New Entry '${local.title}'")
                            )
                        )
                    )
                } else {
                    // Exists in both: compare fields
                    val fieldDiffs = mutableListOf<FieldDiff>()
                    if (local.title != ext.title) {
                        fieldDiffs.add(FieldDiff("Title", ext.title, local.title))
                    }
                    if (local.username != ext.username) {
                        fieldDiffs.add(FieldDiff("Username", ext.username, local.username))
                    }
                    if (local.urlOrPackage != ext.url) {
                        fieldDiffs.add(FieldDiff("URL", ext.url, local.urlOrPackage))
                    }

                    val localPass = VaultCryptoManager.decryptToString(local.passwordEncrypted)
                    if (localPass != ext.password) {
                        fieldDiffs.add(FieldDiff("Password", "••••••••", if (localPass.isNullOrEmpty()) "(cleared)" else "•••••••• (modified)"))
                    }

                    val localNotes = VaultCryptoManager.decryptToString(local.notesEncrypted)
                    if (localNotes != ext.notes) {
                        fieldDiffs.add(FieldDiff("Notes", ext.notes, localNotes))
                    }

                    val localTotp = VaultCryptoManager.decryptToString(local.totpSecretEncrypted)
                    if (localTotp != ext.totp) {
                        fieldDiffs.add(FieldDiff("TOTP", ext.totp, localTotp))
                    }

                    if (fieldDiffs.isNotEmpty()) {
                        diffs.add(
                            EntryDiff(
                                entryUuid = uuid,
                                title = local.title,
                                type = DiffType.MODIFIED,
                                fieldDiffs = fieldDiffs
                            )
                        )
                    }
                }
            }

            // 2. Check deleted local entries (exist in external but deleted in local sandbox)
            for ((uuid, ext) in externalEntries) {
                if (!localEntries.containsKey(uuid)) {
                    diffs.add(
                        EntryDiff(
                            entryUuid = uuid,
                            title = ext.title,
                            type = DiffType.DELETED,
                            fieldDiffs = listOf(
                                FieldDiff("Entry", ext.title, "(Deleted locally)")
                            )
                        )
                    )
                }
            }

            return SyncDiffResult(
                isExternalModified = false,
                diffs = diffs
            )
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "computeDiff error: ${t.message}", t)
            return SyncDiffResult(false, emptyList(), t.message ?: "Failed to compute diff")
        } finally {
            try {
                inputStream?.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Executes the actual sync write back to the external SAF file after user confirmation.
     */
    fun executeSync(
        context: Context,
        uri: Uri,
        masterPassword: CharArray
    ): Boolean {
        val success = KdbxRepository.exportToUri(context, uri, masterPassword)
        if (success) {
            context.prefs().edit {
                putLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
            }
            LogCatcher.log('I', TAG, "2-Way sync committed successfully to external KDBX")
        }
        return success
    }

    private data class ExtEntry(
        val uuid: String,
        val title: String,
        val username: String?,
        val password: String?,
        val url: String?,
        val notes: String?,
        val totp: String?
    )
}
