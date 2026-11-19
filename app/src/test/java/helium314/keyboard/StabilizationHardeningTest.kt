// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.SystemBroadcastReceiver
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.VoiceInputService
import helium314.keyboard.latin.voice.VoiceIpcProtocol
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.security.vault.crypto.VaultCryptoManager
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.isSecurityDestinationRoute
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StabilizationHardeningTest {
    private lateinit var context: Context

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.prefs().edit(commit = true) { clear() }
        VaultSessionManager.resetLockout(context)
        VaultSessionManager.lockAll()
    }

    @Test
    fun blockScreenshotsPreference_defaultsTrue_andTogglesCleanly() {
        assertTrue(VaultSessionManager.isBlockScreenshotsEnabled(context))
        VaultSessionManager.setBlockScreenshotsEnabled(context, false)
        assertFalse(VaultSessionManager.isBlockScreenshotsEnabled(context))
        VaultSessionManager.setBlockScreenshotsEnabled(context, true)
        assertTrue(VaultSessionManager.isBlockScreenshotsEnabled(context))
    }

    @Test
    fun isSecurityDestinationRoute_matchesOnlySecurityAndVaultRoutes() {
        val secured = listOf(
            SettingsDestination.Security,
            SettingsDestination.UnlockMethods,
            SettingsDestination.PatternLock,
            SettingsDestination.SecurityPatternLock,
            SettingsDestination.PrivacyVault,
            SettingsDestination.SecurityVault,
            SettingsDestination.KdbxEntries,
            SettingsDestination.KdbxEntryEditor,
            "${SettingsDestination.KdbxEntryEditor}?uuid=sample-123"
        )
        secured.forEach { route ->
            assertTrue(isSecurityDestinationRoute(route), "Expected $route to require FLAG_SECURE when enabled")
        }

        val unsecured = listOf(
            null,
            "",
            SettingsDestination.Settings,
            SettingsDestination.About,
            SettingsDestination.Advanced,
            SettingsDestination.Debug,
            SettingsDestination.Appearance,
            SettingsDestination.VoiceInput,
            SettingsDestination.BackupRestore
        )
        unsecured.forEach { route ->
            assertFalse(isSecurityDestinationRoute(route), "Expected $route to remain screenshotable")
        }
    }

    @Test
    fun vaultSessions_expireOnMonotonicElapsedRealtime() {
        VaultSessionManager.startSecuritySession(context)
        VaultSessionManager.startPrivacySession(context)
        VaultSessionManager.startSettingsSession(context)

        assertTrue(VaultSessionManager.isSecuritySessionValid())
        assertTrue(VaultSessionManager.isPrivacySessionValid())
        assertTrue(VaultSessionManager.isSettingsSessionValid())

        // Advance monotonic clock past 3 minutes (Security & Settings timeout), still under 4 minutes (Privacy)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(181))
        assertFalse(VaultSessionManager.isSecuritySessionValid())
        assertFalse(VaultSessionManager.isSettingsSessionValid())
        assertTrue(VaultSessionManager.isPrivacySessionValid())
        assertTrue(VaultSessionManager.getPrivacySessionRemainingMs() in 1L..60_000L)

        // Advance monotonic clock past 4 minutes total
        ShadowSystemClock.advanceBy(Duration.ofSeconds(65))
        assertFalse(VaultSessionManager.isPrivacySessionValid())
        assertEquals(0L, VaultSessionManager.getPrivacySessionRemainingMs())
    }

    @Test
    fun unpackCustomFieldsJson_preservesLegacyJsonAndFailsClosedOnInvalidCiphertext() {
        assertNull(VaultCryptoManager.unpackCustomFieldsJson(null))
        assertNull(VaultCryptoManager.unpackCustomFieldsJson("   "))

        val legacyJson = """{"recovery":"code-123"}"""
        assertEquals(legacyJson, VaultCryptoManager.unpackCustomFieldsJson("  $legacyJson  "))

        // Corrupted Base64 / non-JSON payloads must return null (fail closed)
        assertNull(VaultCryptoManager.unpackCustomFieldsJson("not-base64-ciphertext!!!"))
        assertNull(VaultCryptoManager.unpackCustomFieldsJson("dGVzdC1ub3QtYWVzLWdjbQ=="))
    }

    @Test
    fun voiceInputService_ignoresTrailingChunksWhenNotRecordingWithoutAllocatingBuffer() {
        val service = Robolectric.setupService(VoiceInputService::class.java)
        val bufferField = VoiceInputService::class.java.getDeclaredField("audioBuffer").apply {
            isAccessible = true
        }
        val stateField = VoiceInputService::class.java.getDeclaredField("currentState").apply {
            isAccessible = true
        }

        // Default IDLE state must not allocate 1.92 MB FloatArray on stray chunk callbacks
        assertNull(bufferField.get(service))
        service.onAudioChunkAvailable(ShortArray(320) { 500 }, 320)
        assertNull(bufferField.get(service))

        // PAUSED state must likewise ignore chunk callbacks without allocating a fresh buffer
        stateField.set(service, VoiceIpcProtocol.ServiceState.PAUSED)
        service.onAudioChunkAvailable(ShortArray(320) { 500 }, 320)
        assertNull(bufferField.get(service))
    }

    @Test
    fun systemBroadcastReceiver_handlesNullIntentAndNullContextSafely() {
        val receiver = SystemBroadcastReceiver()
        receiver.onReceive(null, null)
        receiver.onReceive(context, null)
        receiver.onReceive(null, Intent(Intent.ACTION_LOCALE_CHANGED))
    }

    @Test
    fun logCatcher_componentRegistryUpdatesImmutably() {
        LogCatcher.markComponentActive("HardeningProbe", "Test", "Active")
        val firstSnapshot = LogCatcher.getActiveComponents().first { it.name == "HardeningProbe" }
        assertEquals("Active", firstSnapshot.status)

        LogCatcher.markComponentActive("HardeningProbe", "Test", "Running")
        val secondSnapshot = LogCatcher.getActiveComponents().first { it.name == "HardeningProbe" }
        assertEquals("Active", firstSnapshot.status)
        assertEquals("Running", secondSnapshot.status)

        LogCatcher.markComponentInactive("HardeningProbe")
        assertFalse(LogCatcher.getActiveComponents().any { it.name == "HardeningProbe" })
    }
}
