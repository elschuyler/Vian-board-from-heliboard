// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
import java.security.MessageDigest
import java.security.SecureRandom

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
    private const val PREF_SECURITY_PATTERN_SALT = "pref_vault_security_pattern_salt"
    private const val PREF_SECURITY_PATTERN_HASH = "pref_vault_security_pattern_hash"

    // Session durations
    const val PRIVACY_SESSION_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
    const val SECURITY_SESSION_DURATION_MS = 3 * 60 * 1000L // 3 minutes

    @Volatile
    private var privacySessionExpiryMs: Long = 0L

    @Volatile
    private var securitySessionExpiryMs: Long = 0L

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
            val hash = computeHash(pattern, salt)

            context.prefs().edit {
                putString(PREF_SECURITY_PATTERN_SALT, salt.toHexString())
                putString(PREF_SECURITY_PATTERN_HASH, hash.toHexString())
            }
            LogCatcher.log('I', TAG, "saveSecurityPattern: Dedicated security vault pattern configured")
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
            val inputHash = computeHash(pattern, salt).toHexString()
            val matches = MessageDigest.isEqual(inputHash.toByteArray(), storedHashHex.toByteArray())
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

    /** Saves a new pattern using salted SHA-256 */
    fun savePattern(context: Context, pattern: List<Int>): Boolean {
        if (pattern.size < 4) {
            LogCatcher.log('W', TAG, "savePattern: Pattern too short (min 4 nodes required)")
            return false
        }
        try {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = computeHash(pattern, salt)

            context.prefs().edit {
                putString(PREF_PATTERN_SALT, salt.toHexString())
                putString(PREF_PATTERN_HASH, hash.toHexString())
            }
            LogCatcher.log('I', TAG, "savePattern: Master unlock pattern successfully configured")
            return true
        } catch (t: Throwable) {
            LogCatcher.log('E', TAG, "savePattern error: ${t.message}", t)
            return false
        }
    }

    /** Verifies an input pattern against the stored hash */
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
            val inputHash = computeHash(pattern, salt).toHexString()
            val matches = MessageDigest.isEqual(inputHash.toByteArray(), storedHashHex.toByteArray())

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

    // --- Session Management ---

    fun isPrivacySessionValid(): Boolean = System.currentTimeMillis() < privacySessionExpiryMs

    fun isSecuritySessionValid(): Boolean = System.currentTimeMillis() < securitySessionExpiryMs

    fun startPrivacySession() {
        privacySessionExpiryMs = System.currentTimeMillis() + PRIVACY_SESSION_DURATION_MS
        LogCatcher.log('I', TAG, "startPrivacySession: 5-minute session started")
    }

    fun startSecuritySession() {
        securitySessionExpiryMs = System.currentTimeMillis() + SECURITY_SESSION_DURATION_MS
        LogCatcher.log('I', TAG, "startSecuritySession: 3-minute session started")
    }

    fun lockPrivacy() {
        privacySessionExpiryMs = 0L
        LogCatcher.log('I', TAG, "lockPrivacy: Privacy vault session locked")
    }

    fun lockSecurity() {
        securitySessionExpiryMs = 0L
        LogCatcher.log('I', TAG, "lockSecurity: Security vault session locked")
    }

    fun lockAll() {
        lockPrivacy()
        lockSecurity()
        LogCatcher.log('I', TAG, "lockAll: All vault sessions locked")
    }

    // --- Crypto Helpers ---

    private fun computeHash(pattern: List<Int>, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val patternBytes = ByteArray(pattern.size) { i -> pattern[i].toByte() }
        return md.digest(patternBytes)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        val result = ByteArray(length / 2)
        for (i in indices step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }
}
