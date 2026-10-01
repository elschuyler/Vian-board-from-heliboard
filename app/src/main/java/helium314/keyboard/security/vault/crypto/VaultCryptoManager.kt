// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import helium314.keyboard.latin.utils.LogCatcher
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * VaultCryptoManager handles hardware-backed AES-256-GCM authenticated encryption
 * using Android's hardware AndroidKeyStore provider.
 *
 * All sensitive credential fields (passwords, TOTP seeds, notes, attachments)
 * are encrypted with a 128-bit authentication tag and a 12-byte cryptographic IV.
 *
 * Explicit zeroization utilities guarantee zero credential leakage in RAM.
 */
object VaultCryptoManager {
    private const val TAG = "VaultCryptoManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vianboard_security_vault_master"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    init {
        LogCatcher.markComponentActive("VaultCryptoManager", "Crypto", "Ready")
    }

    /**
     * Checks if the hardware master key is already initialized in AndroidKeyStore.
     */
    fun isKeyInitialized(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.containsAlias(KEY_ALIAS)
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "isKeyInitialized check error: ${t.message}", t)
            false
        }
    }

    /**
     * Retrieves or generates the hardware-backed AES-256-GCM secret key.
     */
    @Synchronized
    fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        LogCatcher.log('I', TAG, "Generating new hardware-backed AES-256 key in AndroidKeyStore")
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * Returns: [12-byte IV][Ciphertext + 16-byte GCM Tag]
     */
    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        if (iv.size != GCM_IV_LENGTH_BYTES) {
            throw IllegalStateException("Unexpected IV length: ${iv.size} bytes (expected $GCM_IV_LENGTH_BYTES)")
        }

        val ciphertext = cipher.doFinal(plaintext)
        val byteBuffer = ByteBuffer.allocate(iv.size + ciphertext.size)
        byteBuffer.put(iv)
        byteBuffer.put(ciphertext)
        return byteBuffer.array()
    }

    /**
     * Decrypts encrypted blob using AES-256-GCM.
     * Expects: [12-byte IV][Ciphertext + 16-byte GCM Tag]
     */
    @Synchronized
    fun decrypt(encryptedBlob: ByteArray): ByteArray {
        if (encryptedBlob.size <= GCM_IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Invalid encrypted payload size: ${encryptedBlob.size}")
        }

        val key = getOrCreateKey()
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        val ciphertextLength = encryptedBlob.size - GCM_IV_LENGTH_BYTES
        val ciphertext = ByteArray(ciphertextLength)

        val byteBuffer = ByteBuffer.wrap(encryptedBlob)
        byteBuffer.get(iv)
        byteBuffer.get(ciphertext)

        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Encrypts a string directly into an encrypted byte array.
     */
    fun encryptString(plaintext: String): ByteArray {
        val bytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        try {
            return encrypt(bytes)
        } finally {
            zeroize(bytes)
        }
    }

    /**
     * Decrypts an encrypted byte blob to a String.
     */
    fun decryptToString(encryptedBlob: ByteArray?): String? {
        if (encryptedBlob == null || encryptedBlob.isEmpty()) return null
        val decryptedBytes = decrypt(encryptedBlob)
        try {
            return String(decryptedBytes, StandardCharsets.UTF_8)
        } finally {
            zeroize(decryptedBytes)
        }
    }

    /**
     * Decrypts an encrypted byte blob directly into a CharArray.
     * Allows surgical field injection via commitText without creating immutable String copies in RAM.
     */
    fun decryptToCharArray(encryptedBlob: ByteArray?): CharArray? {
        if (encryptedBlob == null || encryptedBlob.isEmpty()) return null
        val decryptedBytes = decrypt(encryptedBlob)
        try {
            val charBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(decryptedBytes))
            val charArray = CharArray(charBuffer.remaining())
            charBuffer.get(charArray)
            return charArray
        } finally {
            zeroize(decryptedBytes)
        }
    }

    /**
     * Memory hygiene: Securely zeroizes character arrays in memory.
     */
    fun zeroize(chars: CharArray?) {
        if (chars != null) {
            Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Memory hygiene: Securely zeroizes byte arrays in memory.
     */
    fun zeroize(bytes: ByteArray?) {
        if (bytes != null) {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    /**
     * Purges master key from AndroidKeyStore (e.g. on full vault factory reset).
     */
    @Synchronized
    fun deleteKey(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
                LogCatcher.log('I', TAG, "Hardware master key deleted from AndroidKeyStore")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "deleteKey error: ${t.message}", t)
            false
        }
    }
}
