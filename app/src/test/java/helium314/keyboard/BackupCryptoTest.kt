// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.settings.backup.BackupManifest
import helium314.keyboard.settings.backup.EncryptedVaultPayload
import helium314.keyboard.settings.backup.ModularBackupEngine
import helium314.keyboard.settings.backup.PrivacyVaultBackupEntry
import kotlinx.serialization.encodeToString
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCryptoTest {

    @Test
    fun encryptAndDecryptVaultBlob_roundTripsPayload() {
        val payload = EncryptedVaultPayload(
            entries = listOf(
                PrivacyVaultBackupEntry(shortcut = "sh ", phrase = "secret phrase", notes = "spaced trigger", timestamp = 100L),
                PrivacyVaultBackupEntry(shortcut = "pin", phrase = "9942", notes = "", timestamp = 200L)
            ),
            patternSalt = "aabbccdd",
            patternHash = "11223344"
        )
        val plaintext = ModularBackupEngine.json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val passphrase = "correct-horse-battery-staple"

        val (cipherBytes, saltBytes) = ModularBackupEngine.encryptVaultBlob(
            plaintext = plaintext,
            passphrase = passphrase,
            iterations = 10_000
        )

        assertEquals(16, saltBytes.size)
        assertTrue(cipherBytes.size > plaintext.size + 12)

        val decrypted = ModularBackupEngine.decryptVaultBlob(
            encryptedBytes = cipherBytes,
            passphrase = passphrase,
            salt = saltBytes,
            iterations = 10_000
        )
        val decoded = ModularBackupEngine.json.decodeFromString<EncryptedVaultPayload>(String(decrypted, Charsets.UTF_8))
        assertEquals(payload, decoded)
    }

    @Test
    fun encryptVaultBlob_usesUniqueRandomSaltAndIv() {
        val plaintext = "same-plaintext-secret".toByteArray(Charsets.UTF_8)
        val passphrase = "my-strong-passphrase"

        val (cipher1, salt1) = ModularBackupEngine.encryptVaultBlob(plaintext, passphrase, 10_000)
        val (cipher2, salt2) = ModularBackupEngine.encryptVaultBlob(plaintext, passphrase, 10_000)

        assertFalse(salt1.contentEquals(salt2))
        assertFalse(cipher1.contentEquals(cipher2))
    }

    @Test
    fun decryptVaultBlob_wrongPassphraseThrowsAeadBadTag() {
        val plaintext = "top-secret-vault-data".toByteArray(Charsets.UTF_8)
        val (cipherBytes, saltBytes) = ModularBackupEngine.encryptVaultBlob(
            plaintext = plaintext,
            passphrase = "right-password",
            iterations = 10_000
        )

        assertFailsWith<AEADBadTagException> {
            ModularBackupEngine.decryptVaultBlob(
                encryptedBytes = cipherBytes,
                passphrase = "wrong-password",
                salt = saltBytes,
                iterations = 10_000
            )
        }
    }

    @Test
    fun decryptVaultBlob_tamperedCiphertextFailsAuthentication() {
        val plaintext = "integrity-protected-blob".toByteArray(Charsets.UTF_8)
        val (cipherBytes, saltBytes) = ModularBackupEngine.encryptVaultBlob(
            plaintext = plaintext,
            passphrase = "right-password",
            iterations = 10_000
        )
        cipherBytes[cipherBytes.lastIndex] = (cipherBytes.last().toInt() xor 0xFF).toByte()

        assertFailsWith<GeneralSecurityException> {
            ModularBackupEngine.decryptVaultBlob(
                encryptedBytes = cipherBytes,
                passphrase = "right-password",
                salt = saltBytes,
                iterations = 10_000
            )
        }
    }

    @Test
    fun encryptAndDecryptVaultBlob_rejectBlankPassphrase() {
        val plaintext = "data".toByteArray(Charsets.UTF_8)
        assertFailsWith<IllegalArgumentException> {
            ModularBackupEngine.encryptVaultBlob(plaintext, "   ", BackupManifest.DEFAULT_KDF_ITERATIONS)
        }
        assertFailsWith<IllegalArgumentException> {
            ModularBackupEngine.decryptVaultBlob(ByteArray(32), "", ByteArray(16), 10_000)
        }
    }

    @Test
    fun encryptAndDecryptVaultPayload_preservesSecurityPatternAndUnlockSettings() {
        val payload = EncryptedVaultPayload(
            entries = listOf(PrivacyVaultBackupEntry(shortcut = "sh ", phrase = "secret phrase")),
            patternSalt = "aabbccdd",
            patternHash = "11223344",
            securityPatternSalt = "eeff0011",
            securityPatternHash = "55667788",
            separatePatterns = true,
            requireGatekeeper = true,
            gatekeeperPrivacyVault = false,
            gatekeeperSecurityVault = true,
            biometricEnabled = true,
            phonePinEnabled = true,
            primaryUnlockMethod = "biometric",
            backupUnlockMethod = "pattern",
            securityVaultTimeoutMs = 300_000L,
            privacyVaultTimeoutMs = 600_000L,
            stealthGatekeeper = false
        )
        val plaintext = ModularBackupEngine.json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val (cipherBytes, saltBytes) = ModularBackupEngine.encryptVaultBlob(plaintext, "pass-123", 10_000)
        val decrypted = ModularBackupEngine.decryptVaultBlob(cipherBytes, "pass-123", saltBytes, 10_000)
        val decoded = ModularBackupEngine.json.decodeFromString<EncryptedVaultPayload>(String(decrypted, Charsets.UTF_8))
        assertEquals(payload, decoded)
    }

    @Test
    fun unwrapVaultCiphertextOrNull_rejectsUndecryptableOrCorruptedPayloads() {
        assertNull(ModularBackupEngine.unwrapVaultCiphertextOrNull("🔒enc:ZmFrZWNpcGhlcnRleHQ="))
        assertNull(ModularBackupEngine.unwrapVaultCiphertextOrNull("corrupt\uFFFDvalue"))
        assertEquals("clean secret", ModularBackupEngine.unwrapVaultCiphertextOrNull("clean secret"))
    }
}
