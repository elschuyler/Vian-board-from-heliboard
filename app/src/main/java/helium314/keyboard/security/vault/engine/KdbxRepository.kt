// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.engine

import android.content.Context
import android.net.Uri
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.security.vault.crypto.VaultCryptoManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.data.VaultAttachmentEntity
import helium314.keyboard.security.vault.data.VaultEntryEntity
import helium314.keyboard.security.vault.data.VaultGroupEntity
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * KdbxRepository handles reading, parsing, and writing KeePass (.kdbx) databases
 * using Kotpass (KDBX 3.1 & 4.x parser/serializer).
 *
 * Runs exclusively in Settings (0% keyboard IME overhead).
 * Maps KDBX Groups & Entries 1:1 into Phase 1 SQLite entities while sealing
 * sensitive fields (passwords, TOTP seeds, notes) via hardware AES-256-GCM.
 */
object KdbxRepository {
    private const val TAG = "KdbxRepository"

    data class ImportResult(
        val success: Boolean,
        val groupCount: Int = 0,
        val entryCount: Int = 0,
        val errorMessage: String? = null
    )

    /**
     * Imports an external KDBX database from a SAF Uri using the provided master password.
     */
    fun importFromUri(
        context: Context,
        uri: Uri,
        masterPassword: CharArray
    ): ImportResult {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult(false, errorMessage = "Failed to open database file stream")

            val credentials = Credentials.from(EncryptedValue.fromString(String(masterPassword)))
            val db = KeePassDatabase.decode(inputStream, credentials)

            val groups = mutableListOf<VaultGroupEntity>()
            val entries = mutableListOf<VaultEntryEntity>()
            val attachments = mutableListOf<VaultAttachmentEntity>()

            // Traverse the tree recursively starting from root group
            val rootGroup = db.content.group
            traverseGroup(rootGroup, parentUuid = null, groups, entries, attachments)

            // Atomically replace sandbox database
            val dao = SecurityVaultDao.getInstance(context)
            val replaced = dao.replaceAllWith(groups, entries, attachments)

            if (replaced) {
                LogCatcher.log('I', TAG, "KDBX import successful: ${groups.size} groups, ${entries.size} entries")
                return ImportResult(
                    success = true,
                    groupCount = groups.size,
                    entryCount = entries.size
                )
            } else {
                return ImportResult(false, errorMessage = "Failed to commit database to local sandbox")
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "KDBX import error: ${t.message}", t)
            return ImportResult(false, errorMessage = t.message ?: "Invalid master password or corrupted database")
        } finally {
            try {
                inputStream?.close()
            } catch (_: Throwable) {}
        }
    }

    private fun traverseGroup(
        group: Group,
        parentUuid: String?,
        groupsList: MutableList<VaultGroupEntity>,
        entriesList: MutableList<VaultEntryEntity>,
        attachmentsList: MutableList<VaultAttachmentEntity>
    ) {
        val groupUuid = group.uuid.toString()
        groupsList.add(
            VaultGroupEntity(
                groupUuid = groupUuid,
                parentGroupUuid = parentUuid,
                name = group.name,
                iconId = group.icon.ordinal
            )
        )

        for (entry in group.entries) {
            val entryUuid = entry.uuid.toString()
            val title = entry.fields["Title"]?.content ?: "Untitled"
            val username = entry.fields["UserName"]?.content
            val password = entry.fields["Password"]?.content
            val url = entry.fields["URL"]?.content
            val notes = entry.fields["Notes"]?.content
            val totp = entry.fields["otp"]?.content
                ?: entry.fields["TOTP Seed"]?.content
                ?: entry.fields["TOTP Settings"]?.content

            val passwordEncrypted = if (!password.isNullOrEmpty()) {
                VaultCryptoManager.encryptString(password)
            } else null

            val totpEncrypted = if (!totp.isNullOrEmpty()) {
                VaultCryptoManager.encryptString(totp)
            } else null

            val notesEncrypted = if (!notes.isNullOrEmpty()) {
                VaultCryptoManager.encryptString(notes)
            } else null

            entriesList.add(
                VaultEntryEntity(
                    entryUuid = entryUuid,
                    groupUuid = groupUuid,
                    title = title,
                    username = username,
                    passwordEncrypted = passwordEncrypted,
                    urlOrPackage = url,
                    totpSecretEncrypted = totpEncrypted,
                    notesEncrypted = notesEncrypted,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // Recursively traverse child groups
        for (childGroup in group.groups) {
            traverseGroup(childGroup, groupUuid, groupsList, entriesList, attachmentsList)
        }
    }

    /**
     * Serializes the current local SQLite sandbox back to an external KDBX stream.
     */
    fun exportToUri(
        context: Context,
        uri: Uri,
        masterPassword: CharArray
    ): Boolean {
        var outputStream: OutputStream? = null
        try {
            val dao = SecurityVaultDao.getInstance(context)
            val groups = dao.getAllGroups()
            val allEntries = dao.getAllEntries()

            if (groups.isEmpty()) {
                LogCatcher.log('W', TAG, "Cannot export empty database")
                return false
            }

            // Build Kotpass database structure from local SQLite entities
            val rootGroupEntity = groups.firstOrNull { it.parentGroupUuid == null } ?: groups.first()
            val kotpassRootGroup = buildKotpassGroup(rootGroupEntity, groups, allEntries)

            val credentials = Credentials.from(EncryptedValue.fromString(String(masterPassword)))
            val baseDb = KeePassDatabase.Ver4x.create(
                rootName = rootGroupEntity.name,
                meta = Meta(),
                credentials = credentials
            )
            val db = baseDb.copy(content = baseDb.content.copy(group = kotpassRootGroup))

            outputStream = context.contentResolver.openOutputStream(uri, "wt")
                ?: return false

            db.encode(outputStream)
            LogCatcher.log('I', TAG, "Exported ${allEntries.size} entries back to KDBX successfully")
            return true
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "KDBX export error: ${t.message}", t)
            return false
        } finally {
            try {
                outputStream?.close()
            } catch (_: Throwable) {}
        }
    }

    private fun buildKotpassGroup(
        groupEntity: VaultGroupEntity,
        allGroups: List<VaultGroupEntity>,
        allEntries: List<VaultEntryEntity>
    ): Group {
        val groupEntries = allEntries.filter { it.groupUuid == groupEntity.groupUuid }
        val kotpassEntries = groupEntries.map { e ->
            val fieldsMap = mutableMapOf<String, EntryValue>()
            fieldsMap["Title"] = EntryValue.Plain(e.title)
            if (!e.username.isNullOrEmpty()) fieldsMap["UserName"] = EntryValue.Plain(e.username)
            if (!e.urlOrPackage.isNullOrEmpty()) fieldsMap["URL"] = EntryValue.Plain(e.urlOrPackage)

            val password = VaultCryptoManager.decryptToString(e.passwordEncrypted)
            if (!password.isNullOrEmpty()) {
                fieldsMap["Password"] = EntryValue.Encrypted(EncryptedValue.fromString(password))
            }

            val notes = VaultCryptoManager.decryptToString(e.notesEncrypted)
            if (!notes.isNullOrEmpty()) {
                fieldsMap["Notes"] = EntryValue.Plain(notes)
            }

            val totp = VaultCryptoManager.decryptToString(e.totpSecretEncrypted)
            if (!totp.isNullOrEmpty()) {
                fieldsMap["otp"] = EntryValue.Plain(totp)
            }

            val entryUuid = try {
                UUID.fromString(e.entryUuid)
            } catch (_: Throwable) {
                UUID.nameUUIDFromBytes(e.entryUuid.toByteArray())
            }

            Entry(
                uuid = entryUuid,
                fields = EntryFields(fieldsMap)
            )
        }

        val childGroupEntities = allGroups.filter { it.parentGroupUuid == groupEntity.groupUuid }
        val childGroups = childGroupEntities.map { child ->
            buildKotpassGroup(child, allGroups, allEntries)
        }

        val groupUuid = try {
            UUID.fromString(groupEntity.groupUuid)
        } catch (_: Throwable) {
            UUID.nameUUIDFromBytes(groupEntity.groupUuid.toByteArray())
        }

        val iconVal = PredefinedIcon.entries.getOrElse(groupEntity.iconId) { PredefinedIcon.Folder }
        return Group(
            uuid = groupUuid,
            name = groupEntity.name,
            icon = iconVal,
            entries = kotpassEntries,
            groups = childGroups
        )
    }
}
