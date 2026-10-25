// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * VaultSessionManager handles secure pattern storage (salted SHA-256 hash)
 * and in-memory session timers for Privacy Vault (5m) and Security Vault (3m).
 *
 * All telemetry sent to LogCatcher is strictly sanitized: zero PII, zero credentials,
 * zero pattern coordinates.
 */
object VaultSessionManager {
    private const val TAG = "VaultSessionManager"

    private const val PREF_PATTERN_SALT = "pref_vault_pattern_salt"
    private const val PREF_PATTERN_HASH = "pref_vault_pattern_hash"

    const val PREF_SEPARATE_PATTERNS = "pref_vault_separate_patterns"
    const val PREF_REQUIRE_GATEKEEPER = "pref_vault_require_gatekeeper"
    const val PREF_GATEKEEPER_PRIVACY_VAULT = "pref_vault_gatekeeper_privacy_vault"
    const val PREF_GATEKEEPER_SECURITY_VAULT = "pref_vault_gatekeeper_security_vault"
    const val PREF_BIOMETRIC_ENABLED = "pref_vault_biometric_enabled"
    const val PREF_PHONE_PIN_ENABLED = "pref_vault_phone_pin_enabled"
    const val PREF_PRIMARY_UNLOCK_METHOD = "pref_vault_primary_unlock_method"
    const val PREF_BACKUP_UNLOCK_METHOD = "pref_vault_backup_unlock_method"
    private const val PREF_SECURITY_PATTERN_SALT = "pref_vault_security_pattern_salt"
    private const val PREF_SECURITY_PATTERN_HASH = "pref_vault_security_pattern_hash"

    enum class UnlockMethod(val id: String) {
        PATTERN("pattern"),
        BIOMETRIC("biometric"),
        DEVICE_PIN("device_pin");

        companion object {
            fun fromId(id: String?): UnlockMethod = when (id) {
                BIOMETRIC.id -> BIOMETRIC
                DEVICE_PIN.id -> DEVICE_PIN
                else -> PATTERN
            }
        }
    }

    const val PREF_SECURITY_VAULT_TIMEOUT = "pref_security_vault_timeout"
    const val PREF_PRIVACY_VAULT_TIMEOUT = "pref_privacy_vault_timeout"

    // Default session durations
    const val DEFAULT_PRIVACY_SESSION_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
    const val DEFAULT_SECURITY_SESSION_DURATION_MS = 3 * 60 * 1000L // 3 minutes
    const val SETTINGS_SESSION_DURATION_MS = 3 * 60 * 1000L         // 3 minutes (isolated to Security Settings UI)

    // Backward compatibility aliases
    const val PRIVACY_SESSION_DURATION_MS = DEFAULT_PRIVACY_SESSION_DURATION_MS
    const val SECURITY_SESSION_DURATION_MS = DEFAULT_SECURITY_SESSION_DURATION_MS

    @Volatile
    private var privacySessionExpiryMs: Long = 0L

    @Volatile
    private var securitySessionExpiryMs: Long = 0L

    @Volatile
    private var settingsSessionExpiryMs: Long = 0L

    init {
        LogCatcher.markComponentActive("VaultSessionManager", "Security", "Active")
    }

    /** Checks if a master pattern is currently configured */
    fun isPatternSet(context: Context): Boolean {
        val sp = context.prefs()
        return sp.contains(PREF_PATTERN_HASH) && sp.contains(PREF_PATTERN_SALT)
    }

    fun isSeparatePatternsEnabled(context: Context): Boolean {
        return context.prefs().getBoolean(PREF_SEPARATE_PATTERNS, false)
    }

    fun setSeparatePatternsEnabled(context: Context, enabled: Boolean) {
        context.prefs().edit {
            putBoolean(PREF_SEPARATE_PATTERNS, enabled)
            if (!enabled) {
                remove(PREF_SECURITY_PATTERN_SALT)
                remove(PREF_SECURITY_PATTERN_HASH)
            }
        }
    }

    fun isGatekeeperEnabled(context: Context): Boolean {
        return context.prefs().getBoolean(PREF_REQUIRE_GATEKEEPER, true)
    }

    fun setGatekeeperEnabled(context: Context, enabled: Boolean) {
        context.prefs().edit {
            putBoolean(PREF_REQUIRE_GATEKEEPER, enabled)
        }
    }

    fun isPrivacyVaultGatekeeperEnabled(context: Context): Boolean {
        return context.prefs().getBoolean(PREF_GATEKEEPER_PRIVACY_VAULT, true)
    }

    fun setPrivacyVaultGatekeeperEnabled(context: Context, enabled: Boolean) {
        context.prefs().edit {
            putBoolean(PREF_GATEKEEPER_PRIVACY_VAULT, enabled)
        }
    }

    fun isSecurityVaultGatekeeperEnabled(context: Context): Boolean {
        return context.prefs().getBoolean(PREF_GATEKEEPER_SECURITY_VAULT, true)
    }

    fun setSecurityVaultGatekeeperEnabled(context: Context, enabled: Boolean) {
        context.prefs().edit {
            putBoolean(PREF_GATEKEEPER_SECURITY_VAULT, enabled)
        }
    }

    fun isBiometricsSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)
    }

    fun isBiometricEnabled(context: Context): Boolean {
        return context.prefs().getBoolean(PREF_BIOMETRIC_ENABLED, false)
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        context.prefs().edit {
            putBoolean(PREF_BIOMETRIC_ENABLED, enabled)
        }
    }

    fun isDevicePinSupported(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isDeviceSecure ?: false
    }

    fun isPhonePinEnabled(context: Context): Boolean {
        return context.prefs().getBoolean(PREF_PHONE_PIN_ENABLED, false)
    }

    fun setPhonePinEnabled(context: Context, enabled: Boolean) {
        context.prefs().edit {
            putBoolean(PREF_PHONE_PIN_ENABLED, enabled)
        }
    }

    fun getPrimaryUnlockMethod(context: Context): UnlockMethod {
        val raw = context.prefs().getString(PREF_PRIMARY_UNLOCK_METHOD, UnlockMethod.PATTERN.id)
        val method = UnlockMethod.fromId(raw)
        // Auto-validate hardware/enrollment capability; fallback to PATTERN if unavailable
        return when (method) {
            UnlockMethod.BIOMETRIC -> if (isBiometricsSupported(context) && isBiometricEnabled(context)) method else UnlockMethod.PATTERN
            UnlockMethod.DEVICE_PIN -> if (isDevicePinSupported(context) && isPhonePinEnabled(context)) method else UnlockMethod.PATTERN
            UnlockMethod.PATTERN -> UnlockMethod.PATTERN
        }
    }

    fun setPrimaryUnlockMethod(context: Context, method: UnlockMethod) {
        context.prefs().edit {
            putString(PREF_PRIMARY_UNLOCK_METHOD, method.id)
            if (method == UnlockMethod.BIOMETRIC) {
                putBoolean(PREF_BIOMETRIC_ENABLED, true)
            } else if (method == UnlockMethod.DEVICE_PIN) {
                putBoolean(PREF_PHONE_PIN_ENABLED, true)
            }
        }
    }

    fun getBackupUnlockMethod(context: Context): UnlockMethod {
        val raw = context.prefs().getString(PREF_BACKUP_UNLOCK_METHOD, UnlockMethod.PATTERN.id)
        val method = UnlockMethod.fromId(raw)
        return when (method) {
            UnlockMethod.DEVICE_PIN -> if (isDevicePinSupported(context) && isPhonePinEnabled(context)) method else UnlockMethod.PATTERN
            else -> UnlockMethod.PATTERN
        }
    }

    fun setBackupUnlockMethod(context: Context, method: UnlockMethod) {
        context.prefs().edit {
            putString(PREF_BACKUP_UNLOCK_METHOD, method.id)
            if (method == UnlockMethod.DEVICE_PIN) {
                putBoolean(PREF_PHONE_PIN_ENABLED, true)
            }
        }
    }

    fun isSecurityPatternSet(context: Context): Boolean {
        if (!isSeparatePatternsEnabled(context)) {
            return isPatternSet(context)
        }
        val sp = context.prefs()
        return sp.contains(PREF_SECURITY_PATTERN_HASH) && sp.contains(PREF_SECURITY_PATTERN_SALT)
    }

    fun saveSecurityPattern(context: Context, pattern: List<Int>): Boolean {
        if (pattern.size < 4) {
            LogCatcher.log('W', TAG, "saveSecurityPattern: Pattern too short")
            return false
        }
        try {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = computePbkdf2Hash(pattern, salt)

            context.prefs().edit {
                putString(PREF_SECURITY_PATTERN_SALT, salt.toHexString())
                putString(PREF_SECURITY_PATTERN_HASH, HASH_PREFIX_PBKDF2 + hash.toHexString())
            }
            LogCatcher.log('I', TAG, "saveSecurityPattern: Dedicated security vault pattern configured (PBKDF2-HMAC-SHA256)")
            return true
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "saveSecurityPattern error: ${t.message}", t)
            return false
        }
    }

    fun verifySecurityPattern(context: Context, pattern: List<Int>): Boolean {
        if (!isSeparatePatternsEnabled(context) || !isSecurityPatternSet(context)) {
            return verifyPattern(context, pattern)
        }
        val sp = context.prefs()
        val saltHex = sp.getString(PREF_SECURITY_PATTERN_SALT, null)
        val storedHashHex = sp.getString(PREF_SECURITY_PATTERN_HASH, null)

        if (saltHex == null || storedHashHex == null) {
            return verifyPattern(context, pattern)
        }

        try {
            val salt = saltHex.hexToByteArray()
            val matches = if (storedHashHex.startsWith(HASH_PREFIX_PBKDF2)) {
                val rawStored = storedHashHex.removePrefix(HASH_PREFIX_PBKDF2)
                val inputHash = computePbkdf2Hash(pattern, salt).toHexString()
                MessageDigest.isEqual(inputHash.toByteArray(), rawStored.toByteArray())
            } else {
                // Legacy SHA-256 verification with automatic PBKDF2 upgrade
                val inputHash = computeLegacySha256(pattern, salt).toHexString()
                val legacyMatch = MessageDigest.isEqual(inputHash.toByteArray(), storedHashHex.toByteArray())
                if (legacyMatch) {
                    saveSecurityPattern(context, pattern) // In-place upgrade
                    LogCatcher.log('I', TAG, "verifySecurityPattern: Upgraded legacy pattern hash to PBKDF2-HMAC-SHA256")
                }
                legacyMatch
            }

            if (matches) {
                LogCatcher.log('I', TAG, "verifySecurityPattern: Security verification succeeded")
            } else {
                LogCatcher.log('W', TAG, "verifySecurityPattern: Security verification failed")
            }
            return matches
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "verifySecurityPattern error: ${t.message}", t)
            return false
        }
    }

    /** Saves a new pattern using PBKDF2-HMAC-SHA256 (100,000 iterations) */
    fun savePattern(context: Context, pattern: List<Int>): Boolean {
        if (pattern.size < 4) {
            LogCatcher.log('W', TAG, "savePattern: Pattern too short (min 4 nodes required)")
            return false
        }
        try {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = computePbkdf2Hash(pattern, salt)

            context.prefs().edit {
                putString(PREF_PATTERN_SALT, salt.toHexString())
                putString(PREF_PATTERN_HASH, HASH_PREFIX_PBKDF2 + hash.toHexString())
            }
            LogCatcher.log('I', TAG, "savePattern: Master unlock pattern successfully configured (PBKDF2-HMAC-SHA256)")
            return true
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "savePattern error: ${t.message}", t)
            return false
        }
    }

    /** Verifies an input pattern against the stored hash with automatic PBKDF2 upgrade */
    fun verifyPattern(context: Context, pattern: List<Int>): Boolean {
        val sp = context.prefs()
        val saltHex = sp.getString(PREF_PATTERN_SALT, null)
        val storedHashHex = sp.getString(PREF_PATTERN_HASH, null)

        if (saltHex == null || storedHashHex == null) {
            LogCatcher.log('W', TAG, "verifyPattern: Attempted verification without configured pattern")
            return false
        }

        try {
            val salt = saltHex.hexToByteArray()
            val matches = if (storedHashHex.startsWith(HASH_PREFIX_PBKDF2)) {
                val rawStored = storedHashHex.removePrefix(HASH_PREFIX_PBKDF2)
                val inputHash = computePbkdf2Hash(pattern, salt).toHexString()
                MessageDigest.isEqual(inputHash.toByteArray(), rawStored.toByteArray())
            } else {
                // Legacy SHA-256 verification with automatic PBKDF2 upgrade
                val inputHash = computeLegacySha256(pattern, salt).toHexString()
                val legacyMatch = MessageDigest.isEqual(inputHash.toByteArray(), storedHashHex.toByteArray())
                if (legacyMatch) {
                    savePattern(context, pattern) // In-place upgrade
                    LogCatcher.log('I', TAG, "verifyPattern: Upgraded legacy pattern hash to PBKDF2-HMAC-SHA256")
                }
                legacyMatch
            }

            if (matches) {
                LogCatcher.log('I', TAG, "verifyPattern: Verification succeeded")
            } else {
                LogCatcher.log('W', TAG, "verifyPattern: Verification failed (mismatch)")
            }
            return matches
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "verifyPattern error: ${t.message}", t)
            return false
        }
    }


    /** Clears configured pattern and purges active sessions */
    fun clearPattern(context: Context) {
        context.prefs().edit {
            remove(PREF_PATTERN_SALT)
            remove(PREF_PATTERN_HASH)
        }
        lockAll()
        LogCatcher.log('I', TAG, "clearPattern: Master pattern removed, all sessions purged")
    }

    // --- Session Timeout Preferences ---

    fun getSecurityTimeoutMs(context: Context?): Long {
        if (context == null) return DEFAULT_SECURITY_SESSION_DURATION_MS
        return context.prefs().getLong(PREF_SECURITY_VAULT_TIMEOUT, DEFAULT_SECURITY_SESSION_DURATION_MS)
    }

    fun getPrivacyTimeoutMs(context: Context?): Long {
        if (context == null) return DEFAULT_PRIVACY_SESSION_DURATION_MS
        return context.prefs().getLong(PREF_PRIVACY_VAULT_TIMEOUT, DEFAULT_PRIVACY_SESSION_DURATION_MS)
    }

    fun setSecurityTimeoutMs(context: Context, timeoutMs: Long) {
        context.prefs().edit { putLong(PREF_SECURITY_VAULT_TIMEOUT, timeoutMs) }
        LogCatcher.log('I', TAG, "setSecurityTimeoutMs: Security timeout updated to ${timeoutMs / 60000}m")
    }

    fun setPrivacyTimeoutMs(context: Context, timeoutMs: Long) {
        context.prefs().edit { putLong(PREF_PRIVACY_VAULT_TIMEOUT, timeoutMs) }
        LogCatcher.log('I', TAG, "setPrivacyTimeoutMs: Privacy timeout updated to ${timeoutMs / 60000}m")
    }

    // --- Session Management ---

    fun isPrivacySessionValid(): Boolean = System.currentTimeMillis() < privacySessionExpiryMs

    fun isSecuritySessionValid(): Boolean = System.currentTimeMillis() < securitySessionExpiryMs

    fun isSettingsSessionValid(): Boolean = System.currentTimeMillis() < settingsSessionExpiryMs

    fun getSecuritySessionRemainingMs(): Long = maxOf(0L, securitySessionExpiryMs - System.currentTimeMillis())

    fun getPrivacySessionRemainingMs(): Long = maxOf(0L, privacySessionExpiryMs - System.currentTimeMillis())

    fun getSettingsSessionRemainingMs(): Long = maxOf(0L, settingsSessionExpiryMs - System.currentTimeMillis())

    @JvmOverloads
    fun startPrivacySession(context: Context? = null) {
        val duration = getPrivacyTimeoutMs(context)
        privacySessionExpiryMs = System.currentTimeMillis() + duration
        LogCatcher.log('I', TAG, "startPrivacySession: ${duration / 60000}m session started (expires at $privacySessionExpiryMs)")
    }

    @JvmOverloads
    fun startSecuritySession(context: Context? = null) {
        val duration = getSecurityTimeoutMs(context)
        securitySessionExpiryMs = System.currentTimeMillis() + duration
        LogCatcher.log('I', TAG, "startSecuritySession: ${duration / 60000}m session started (expires at $securitySessionExpiryMs)")
    }

    fun startSettingsSession() {
        settingsSessionExpiryMs = System.currentTimeMillis() + SETTINGS_SESSION_DURATION_MS
        LogCatcher.log('I', TAG, "startSettingsSession: 3-minute settings session started")
    }

    fun lockPrivacy() {
        if (privacySessionExpiryMs == 0L) return
        privacySessionExpiryMs = 0L
        LogCatcher.log('I', TAG, "lockPrivacy: Privacy vault session locked")
    }

    fun lockSecurity() {
        if (securitySessionExpiryMs == 0L) return
        securitySessionExpiryMs = 0L
        LogCatcher.log('I', TAG, "lockSecurity: Security vault session locked")
    }

    fun lockSettings() {
        if (settingsSessionExpiryMs == 0L) return
        settingsSessionExpiryMs = 0L
        LogCatcher.log('I', TAG, "lockSettings: Security settings session locked")
    }

    fun lockVaults() {
        val wasActive = privacySessionExpiryMs != 0L || securitySessionExpiryMs != 0L
        lockPrivacy()
        lockSecurity()
        if (wasActive) {
            LogCatcher.log('I', TAG, "lockVaults: Privacy and Security vault sessions locked")
        }
    }

    fun lockAll() {
        val wasActive = privacySessionExpiryMs != 0L || securitySessionExpiryMs != 0L || settingsSessionExpiryMs != 0L
        lockPrivacy()
        lockSecurity()
        lockSettings()
        if (wasActive) {
            LogCatcher.log('I', TAG, "lockAll: All vault and settings sessions locked")
        }
    }

    // --- Crypto Helpers ---

    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_LENGTH = 256
    const val HASH_PREFIX_PBKDF2 = "pbkdf2:100000:"

    fun computePbkdf2Hash(pattern: List<Int>, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val patternChars = pattern.joinToString("").toCharArray()
        val spec = PBEKeySpec(patternChars, salt, iterations, PBKDF2_KEY_LENGTH)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }

    fun computeLegacySha256(pattern: List<Int>, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val patternBytes = ByteArray(pattern.size) { i -> pattern[i].toByte() }
        return md.digest(patternBytes)
    }

    fun deriveKeyFromSaltAndHash(salt: ByteArray, hashBytes: ByteArray): ByteArray {
        val spec = PBEKeySpec(hashBytes.toHexString().toCharArray(), salt, 10_000, 256)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }

    fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToByteArray(): ByteArray {
        val result = ByteArray(length / 2)
        for (i in indices step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }
}

