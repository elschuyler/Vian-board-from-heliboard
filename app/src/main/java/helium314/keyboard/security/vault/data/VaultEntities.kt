// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.data

/**
 * Entity representing a folder/group in the Security Vault.
 * Mirrors KeePass KDBX Groups 1:1 using the original 16-byte UUID.
 */
data class VaultGroupEntity(
    val groupUuid: String,
    val parentGroupUuid: String? = null,
    val name: String,
    val iconId: Int = 0
)

/**
 * Entity representing a credential entry in the Security Vault.
 * Mirrors KeePass KDBX Entries 1:1 using the original 16-byte UUID.
 * Sensitive fields (password, totpSecret, notes) are stored as AES-256-GCM encrypted byte blobs.
 */
data class VaultEntryEntity(
    val entryUuid: String,
    val groupUuid: String,
    val title: String,
    val username: String? = null,
    val passwordEncrypted: ByteArray? = null,
    val urlOrPackage: String? = null,
    val totpSecretEncrypted: ByteArray? = null,
    val notesEncrypted: ByteArray? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VaultEntryEntity

        if (entryUuid != other.entryUuid) return false
        if (groupUuid != other.groupUuid) return false
        if (title != other.title) return false
        if (username != other.username) return false
        if (passwordEncrypted != null) {
            if (other.passwordEncrypted == null) return false
            if (!passwordEncrypted.contentEquals(other.passwordEncrypted)) return false
        } else if (other.passwordEncrypted != null) return false
        if (urlOrPackage != other.urlOrPackage) return false
        if (totpSecretEncrypted != null) {
            if (other.totpSecretEncrypted == null) return false
            if (!totpSecretEncrypted.contentEquals(other.totpSecretEncrypted)) return false
        } else if (other.totpSecretEncrypted != null) return false
        if (notesEncrypted != null) {
            if (other.notesEncrypted == null) return false
            if (!notesEncrypted.contentEquals(other.notesEncrypted)) return false
        } else if (other.notesEncrypted != null) return false
        if (updatedAt != other.updatedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = entryUuid.hashCode()
        result = 31 * result + groupUuid.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (passwordEncrypted?.contentHashCode() ?: 0)
        result = 31 * result + (urlOrPackage?.hashCode() ?: 0)
        result = 31 * result + (totpSecretEncrypted?.contentHashCode() ?: 0)
        result = 31 * result + (notesEncrypted?.contentHashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * Entity representing an attached binary file or certificate.
 * Mirrors KeePass KDBX Binaries attached to an entry.
 */
data class VaultAttachmentEntity(
    val attachmentUuid: String,
    val entryUuid: String,
    val filename: String,
    val mimeType: String? = null,
    val dataBlobEncrypted: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VaultAttachmentEntity

        if (attachmentUuid != other.attachmentUuid) return false
        if (entryUuid != other.entryUuid) return false
        if (filename != other.filename) return false
        if (mimeType != other.mimeType) return false
        if (dataBlobEncrypted != null) {
            if (other.dataBlobEncrypted == null) return false
            if (!dataBlobEncrypted.contentEquals(other.dataBlobEncrypted)) return false
        } else if (other.dataBlobEncrypted != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = attachmentUuid.hashCode()
        result = 31 * result + entryUuid.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + (dataBlobEncrypted?.contentHashCode() ?: 0)
        return result
    }
}
