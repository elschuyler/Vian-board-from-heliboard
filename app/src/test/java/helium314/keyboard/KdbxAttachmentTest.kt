// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.removeUnusedBinaries
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import helium314.keyboard.security.vault.engine.KdbxRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KdbxAttachmentTest {

    @Test
    fun kdbxBinaryAttachmentAndKeyFile_roundTripPreservesBytesAndName() {
        val masterPwd = "kdbx-master-pass".toCharArray()
        val keyFileBytes = "dummy-keyfile-secret-bytes-1234567890".toByteArray(Charsets.UTF_8)
        val credentials = KdbxRepository.createCredentials(masterPwd, keyFileBytes)

        val payloadBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x0A, 0x42)
        val binaryData = BinaryData.Uncompressed(memoryProtection = false, rawContent = payloadBytes.copyOf())
        val binRef = BinaryReference(hash = binaryData.hash, name = "recovery-codes.pdf")

        val entry = Entry(
            uuid = UUID.randomUUID(),
            fields = EntryFields(
                mapOf(
                    "Title" to EntryValue.Plain("Cloud Account"),
                    "UserName" to EntryValue.Plain("alice@example.com"),
                    "Password" to EntryValue.Encrypted(EncryptedValue.fromString("s3cr3t!"))
                )
            ),
            binaries = listOf(binRef)
        )
        val rootGroup = Group(
            uuid = UUID.randomUUID(),
            name = "Root",
            icon = PredefinedIcon.Folder,
            entries = listOf(entry),
            groups = emptyList()
        )

        val baseDb = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(generator = "VianBoard"),
            credentials = credentials
        )
        val populatedDb = baseDb
            .copy(content = baseDb.content.copy(group = rootGroup))
            .modifyBinaries { current -> current + (binaryData.hash to binaryData) }
            .removeUnusedBinaries()

        val outStream = ByteArrayOutputStream()
        populatedDb.encode(outStream)
        val kdbxBytes = outStream.toByteArray()

        val decodedDb = KeePassDatabase.decode(ByteArrayInputStream(kdbxBytes), credentials)
        val decodedBinaries = decodedDb.binaries
        val decodedEntry = decodedDb.content.group.entries.single()
        assertEquals(1, decodedEntry.binaries.size)
        val decodedRef = decodedEntry.binaries.single()
        assertEquals("recovery-codes.pdf", decodedRef.name)
        val decodedBinaryData = decodedBinaries[decodedRef.hash]
        assertNotNull(decodedBinaryData)
        assertContentEquals(payloadBytes, decodedBinaryData.getContent())
    }
}
