// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String = "",
    val appVersionCode: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val modules: List<String> = emptyList(),
    val notes: String = "VianBoard Modular Archive",
    val stats: Map<String, Int> = emptyMap()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val MANIFEST_FILE_NAME = "manifest.json"
    }
}

@Serializable
data class VaultBackupData(
    val hasPattern: Boolean,
    val saltHex: String? = null,
    val hashHex: String? = null,
    val isSeparatePatterns: Boolean = false,
    val securitySaltHex: String? = null,
    val securityHashHex: String? = null
)

@Serializable
data class PortableVaultGroup(
    val groupUuid: String,
    val parentGroupUuid: String? = null,
    val name: String,
    val iconId: Int = 0
)

@Serializable
data class PortableVaultEntry(
    val entryUuid: String,
    val groupUuid: String,
    val title: String,
    val username: String? = null,
    val passwordPlain: String? = null,
    val urlOrPackage: String? = null,
    val totpSecretPlain: String? = null,
    val notesPlain: String? = null,
    val customFieldsPlainJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class PortableVaultAttachment(
    val attachmentUuid: String,
    val entryUuid: String,
    val filename: String,
    val mimeType: String? = null,
    val dataBase64: String? = null
)

@Serializable
data class PortableVaultContainer(
    val groups: List<PortableVaultGroup> = emptyList(),
    val entries: List<PortableVaultEntry> = emptyList(),
    val attachments: List<PortableVaultAttachment> = emptyList()
)


@Serializable
data class ClipboardBackupEntry(
    val timestamp: Long,
    val isPinned: Boolean,
    val text: String? = null,
    val file: String? = null,
    val mimeTypes: List<String>? = null
)

@Serializable
data class PromptBackupEntry(
    val timestamp: Long,
    val isPinned: Boolean,
    val title: String,
    val text: String
)

@Serializable
data class VoiceReplacementBackupEntry(
    val originalWord: String,
    val replacementWord: String,
    val isWholeWord: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class PrivacyVaultBackupEntry(
    val shortcut: String,
    val phrase: String,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class BackupArchiveInfo(
    val schemaVersion: Int,
    val appVersion: String,
    val timestamp: Long,
    val availableModules: Set<BackupModule>,
    val stats: Map<String, Int> = emptyMap(),
    val isLegacy: Boolean = false
)
