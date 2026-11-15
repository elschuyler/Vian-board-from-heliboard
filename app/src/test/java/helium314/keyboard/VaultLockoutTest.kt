// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.database.VaultDao
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.security.VaultSessionManager
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VaultLockoutTest {
    private lateinit var context: Context

    private val validPattern = listOf(0, 1, 2, 5, 8)
    private val wrongPattern = listOf(0, 3, 6, 7, 8)

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.prefs().edit(commit = true) { clear() }
        VaultSessionManager.resetLockout(context)
        VaultSessionManager.lockAll()
        assertTrue(VaultSessionManager.savePattern(context, validPattern))
    }

    @Test
    fun fiveFailedAttempts_triggersThirtySecondLockout_andBlocksEvenValidPattern() {
        repeat(4) { attempt ->
            assertFalse(VaultSessionManager.verifyPattern(context, wrongPattern))
            assertFalse(VaultSessionManager.isLockedOut(context), "Attempt ${attempt + 1} should not lock out")
            assertEquals(attempt + 1, VaultSessionManager.getFailedAttemptCount(context))
        }

        // 5th failed attempt triggers 30s cooldown
        assertFalse(VaultSessionManager.verifyPattern(context, wrongPattern))
        assertTrue(VaultSessionManager.isLockedOut(context))
        val remainingMs = VaultSessionManager.getLockoutRemainingMs(context)
        assertTrue(remainingMs in 1L..VaultSessionManager.LOCKOUT_DURATION_MS)
        assertEquals(VaultSessionManager.MAX_FAILED_ATTEMPTS, VaultSessionManager.getFailedAttemptCount(context))

        // Valid pattern must remain blocked while locked out
        assertFalse(VaultSessionManager.verifyPattern(context, validPattern))
        assertTrue(VaultSessionManager.isLockedOut(context))
    }

    @Test
    fun validUnlockBeforeLockout_resetsFailedAttemptCounter() {
        repeat(3) {
            assertFalse(VaultSessionManager.verifyPattern(context, wrongPattern))
        }
        assertEquals(3, VaultSessionManager.getFailedAttemptCount(context))

        assertTrue(VaultSessionManager.verifyPattern(context, validPattern))
        assertEquals(0, VaultSessionManager.getFailedAttemptCount(context))
        assertFalse(VaultSessionManager.isLockedOut(context))
    }

    @Test
    fun separateSecurityPattern_sharesBruteForceCounterAndLockout() {
        val secPattern = listOf(2, 4, 6, 7, 8)
        VaultSessionManager.setSeparatePatternsEnabled(context, true)
        assertTrue(VaultSessionManager.saveSecurityPattern(context, secPattern))

        repeat(5) {
            assertFalse(VaultSessionManager.verifySecurityPattern(context, wrongPattern))
        }
        assertTrue(VaultSessionManager.isLockedOut(context))
        assertFalse(VaultSessionManager.verifySecurityPattern(context, secPattern))

        // Successful biometric/settings session start clears lockout
        VaultSessionManager.startSettingsSession(context)
        assertFalse(VaultSessionManager.isLockedOut(context))
        assertEquals(0, VaultSessionManager.getFailedAttemptCount(context))
        assertTrue(VaultSessionManager.verifySecurityPattern(context, secPattern))
    }

    @Test
    fun expiredLockoutTimestamp_resetsCleanly() {
        repeat(5) {
            assertFalse(VaultSessionManager.verifyPattern(context, wrongPattern))
        }
        assertTrue(VaultSessionManager.isLockedOut(context))

        // Simulate cooldown completion & process restart via resetLockout + past timestamp check
        VaultSessionManager.resetLockout(null) // clear in-memory cache only
        context.prefs().edit(commit = true) {
            putLong("pref_vault_lockout_until_ms", System.currentTimeMillis() - 1000L)
            putInt("pref_vault_failed_attempts", 5)
        }
        assertFalse(VaultSessionManager.isLockedOut(context))
        assertEquals(0L, VaultSessionManager.getLockoutRemainingMs(context))
        assertEquals(0, VaultSessionManager.getFailedAttemptCount(context))
        assertTrue(VaultSessionManager.verifyPattern(context, validPattern))
    }

    @Test
    fun vaultDaoAddOrUpdate_failsClosedWithoutPlaintextWhenKeyStoreUnavailable() {
        val dao = VaultDao.getInstance(context)
        dao.clear()
        // On Robolectric JVM there is no AndroidKeyStore provider; addOrUpdate must fail closed (-1L)
        // and must NOT fall back to writing plaintext rows into SQLite.
        val result = dao.addOrUpdate(shortcut = "pin", phrase = "9942", notes = "secret")
        assertEquals(-1L, result)
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun defaultSessionTimeouts_areThreeMinutesSecurityAndFourMinutesPrivacy() {
        assertEquals(3 * 60 * 1000L, VaultSessionManager.DEFAULT_SECURITY_SESSION_DURATION_MS)
        assertEquals(4 * 60 * 1000L, VaultSessionManager.DEFAULT_PRIVACY_SESSION_DURATION_MS)
        assertEquals(3 * 60 * 1000L, VaultSessionManager.getSecurityTimeoutMs(context))
        assertEquals(4 * 60 * 1000L, VaultSessionManager.getPrivacyTimeoutMs(context))

        VaultSessionManager.startSecuritySession(context)
        VaultSessionManager.startPrivacySession(context)
        assertTrue(VaultSessionManager.isSecuritySessionValid())
        assertTrue(VaultSessionManager.isPrivacySessionValid())
        assertTrue(VaultSessionManager.getSecuritySessionRemainingMs() in (2 * 60 * 1000L)..(3 * 60 * 1000L))
        assertTrue(VaultSessionManager.getPrivacySessionRemainingMs() in (3 * 60 * 1000L)..(4 * 60 * 1000L))
    }

    @Test
    fun configuredTimeoutsAndLockVaults_respectPrefsAndIsolateSettingsSession() {
        VaultSessionManager.setSecurityTimeoutMs(context, 10 * 60 * 1000L)
        VaultSessionManager.setPrivacyTimeoutMs(context, 2 * 60 * 1000L)
        VaultSessionManager.startSecuritySession(context)
        VaultSessionManager.startPrivacySession(context)
        VaultSessionManager.startSettingsSession(context)

        assertTrue(VaultSessionManager.getSecuritySessionRemainingMs() > 5 * 60 * 1000L)
        assertTrue(VaultSessionManager.getPrivacySessionRemainingMs() <= 2 * 60 * 1000L)

        // Closing the keyboard calls lockVaults(), wiping both keyboard vaults with zero effect on settings session
        VaultSessionManager.lockVaults()
        assertFalse(VaultSessionManager.isSecuritySessionValid())
        assertFalse(VaultSessionManager.isPrivacySessionValid())
        assertEquals(0L, VaultSessionManager.getSecuritySessionRemainingMs())
        assertEquals(0L, VaultSessionManager.getPrivacySessionRemainingMs())
        assertTrue(VaultSessionManager.isSettingsSessionValid())
    }
}
