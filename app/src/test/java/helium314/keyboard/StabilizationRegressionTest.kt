// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Insets
import android.net.Uri
import android.view.View
import android.view.WindowInsets
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.desktop.DesktopShortcutsView
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.ShiftMode
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyType
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.TextKeyData
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.EnergyVad
import helium314.keyboard.latin.voice.VoicePulseView
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.settings.backup.BackupModule
import helium314.keyboard.settings.backup.ModularBackupEngine
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    shadows = [ShadowInputMethodManager2::class]
)
class StabilizationRegressionTest {

    @Test
    fun voicePulseView_scalesBarsAcrossFullHorizontalWidth() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val pulseView = VoicePulseView(ime)

        pulseView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY)
        )
        pulseView.layout(0, 0, 1080, 140)

        val wideCount = pulseView.getBarCountForTesting()
        assertTrue(wideCount > 21, "Expected dynamic bar count > 21 at 1080px, got $wideCount")
        assertTrue(wideCount <= 96, "Expected dynamic bar count <= 96, got $wideCount")

        pulseView.measure(
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY)
        )
        pulseView.layout(0, 0, 80, 140)
        assertTrue(pulseView.getBarCountForTesting() >= 21)
    }

    @Test
    fun energyVad_normalizesDigitalGainBeforeSpeechDetection() {
        val vad = EnergyVad()
        val gainedHum = ShortArray(1024) { 280 } // 70 effective amplitude when divided by 4x gain
        repeat(20) {
            vad.processFrame(gainedHum, gainedHum.size, gainMultiplier = 4)
        }
        assertFalse(vad.isSpeechDetected, "4x gained ambient hum must not falsely latch VAD speech state")

        val speechFrame = ShortArray(1024) { 4000 } // 1000 effective amplitude at 4x gain
        repeat(5) {
            vad.processFrame(speechFrame, speechFrame.size, gainMultiplier = 4)
        }
        assertTrue(vad.isSpeechDetected, "Actual speech frame above threshold should trigger VAD")
    }

    @Test
    fun textKeyData_dropsPlaceholderInPopupSets() {
        val params = KeyboardParams().apply {
            mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)
        }
        val placeholder = TextKeyData(type = KeyType.PLACEHOLDER)
        assertNull(placeholder.compute(params, isPopup = true))
    }

    @Test
    fun desktopShortcutsView_appliesBottomNavBarInsetToPaddingAndHeight() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val themedContext = KeyboardTheme.getKeyboardTheme(ime).let {
            android.view.ContextThemeWrapper(ime, it.mStyleId)
        }
        val view = android.view.LayoutInflater.from(themedContext)
            .inflate(R.layout.desktop_shortcuts_view, null) as DesktopShortcutsView

        val baseHeight = ResourceUtils.getSecondaryKeyboardHeight(ime.resources, Settings.getValues())
        val navInsetPx = 96
        val windowInsets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navInsetPx))
            .build()

        view.onApplyWindowInsets(windowInsets)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
        )

        assertTrue(view.paddingBottom >= navInsetPx, "Expected paddingBottom >= $navInsetPx, was ${view.paddingBottom}")
        assertTrue(view.measuredHeight >= baseHeight + navInsetPx, "Expected measuredHeight >= ${baseHeight + navInsetPx}, was ${view.measuredHeight}")
    }

    @Test
    fun modularBackup_privacyVaultBackupAndRestorePreservesPatternsAndUnlockConfig() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val prefs = ime.prefs()
        prefs.edit(commit = true) { clear() }
        VaultSessionManager.resetLockout(ime)
        VaultSessionManager.lockAll()

        val masterPattern = listOf(0, 1, 2, 5, 8)
        val securityPattern = listOf(2, 4, 6, 7, 8)
        assertTrue(VaultSessionManager.savePattern(ime, masterPattern))
        VaultSessionManager.setSeparatePatternsEnabled(ime, true)
        assertTrue(VaultSessionManager.saveSecurityPattern(ime, securityPattern))
        prefs.edit(commit = true) {
            putBoolean(VaultSessionManager.PREF_REQUIRE_GATEKEEPER, true)
            putBoolean(VaultSessionManager.PREF_GATEKEEPER_PRIVACY_VAULT, false)
            putBoolean(VaultSessionManager.PREF_GATEKEEPER_SECURITY_VAULT, true)
            putBoolean(VaultSessionManager.PREF_BIOMETRIC_ENABLED, true)
            putBoolean(VaultSessionManager.PREF_PHONE_PIN_ENABLED, true)
            putString(VaultSessionManager.PREF_PRIMARY_UNLOCK_METHOD, VaultSessionManager.UnlockMethod.BIOMETRIC.id)
            putString(VaultSessionManager.PREF_BACKUP_UNLOCK_METHOD, VaultSessionManager.UnlockMethod.DEVICE_PIN.id)
            putLong(VaultSessionManager.PREF_SECURITY_VAULT_TIMEOUT, 300_000L)
            putLong(VaultSessionManager.PREF_PRIVACY_VAULT_TIMEOUT, 600_000L)
            putBoolean("pref_vault_stealth_gatekeeper", false)
        }

        val archiveFile = File(ime.cacheDir, "vault_unlock_roundtrip_test.zip")
        try {
            val uri = Uri.fromFile(archiveFile)
            ModularBackupEngine.createBackup(
                context = ime,
                uri = uri,
                selectedModules = setOf(BackupModule.PRIVACY_VAULT),
                vaultPassphrase = "strong-test-passphrase"
            )

            // Wipe all preferences to simulate clean install / changed package name
            prefs.edit(commit = true) { clear() }
            VaultSessionManager.lockAll()
            assertFalse(VaultSessionManager.isPatternSet(ime))

            ModularBackupEngine.restoreArchive(
                context = ime,
                uri = uri,
                selectedModules = setOf(BackupModule.PRIVACY_VAULT),
                vaultPassphrase = "strong-test-passphrase"
            )

            val keyguardManager = ime.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            Shadows.shadowOf(keyguardManager).setIsDeviceSecure(true)
            Shadows.shadowOf(ime.packageManager).setSystemFeature(PackageManager.FEATURE_FINGERPRINT, true)

            assertTrue(VaultSessionManager.isPatternSet(ime))
            assertTrue(VaultSessionManager.isSeparatePatternsEnabled(ime))
            assertTrue(VaultSessionManager.verifyPattern(ime, masterPattern))
            assertTrue(VaultSessionManager.verifySecurityPattern(ime, securityPattern))
            assertEquals(
                VaultSessionManager.UnlockMethod.BIOMETRIC.id,
                prefs.getString(VaultSessionManager.PREF_PRIMARY_UNLOCK_METHOD, null)
            )
            assertEquals(VaultSessionManager.UnlockMethod.BIOMETRIC, VaultSessionManager.getPrimaryUnlockMethod(ime))
            assertEquals(
                VaultSessionManager.UnlockMethod.DEVICE_PIN.id,
                prefs.getString(VaultSessionManager.PREF_BACKUP_UNLOCK_METHOD, null)
            )
            assertEquals(VaultSessionManager.UnlockMethod.DEVICE_PIN, VaultSessionManager.getBackupUnlockMethod(ime))
            assertTrue(prefs.getBoolean(VaultSessionManager.PREF_REQUIRE_GATEKEEPER, false))
            assertFalse(prefs.getBoolean(VaultSessionManager.PREF_GATEKEEPER_PRIVACY_VAULT, true))
            assertTrue(prefs.getBoolean(VaultSessionManager.PREF_GATEKEEPER_SECURITY_VAULT, false))
            assertTrue(prefs.getBoolean(VaultSessionManager.PREF_BIOMETRIC_ENABLED, false))
            assertTrue(prefs.getBoolean(VaultSessionManager.PREF_PHONE_PIN_ENABLED, false))
            assertEquals(300_000L, prefs.getLong(VaultSessionManager.PREF_SECURITY_VAULT_TIMEOUT, 0L))
            assertEquals(600_000L, prefs.getLong(VaultSessionManager.PREF_PRIVACY_VAULT_TIMEOUT, 0L))
            assertFalse(prefs.getBoolean("pref_vault_stealth_gatekeeper", true))
        } finally {
            archiveFile.delete()
        }
    }

    @Test
    fun keyboardSwitcher_setAlphabetKeyboardIgnoredWhileVoiceInputActive_andCloseVoiceInputResets() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val inputView = ime.onCreateInputView()
        ime.setInputView(inputView)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.loadKeyboard(android.view.inputmethod.EditorInfo(), Settings.getValues(), 0, null, null)

        switcher.setVoiceInputKeyboard()
        val voiceView = switcher.voiceInputView
        assertTrue(voiceView != null && voiceView.isVoiceActive())
        assertEquals(View.VISIBLE, voiceView.visibility)

        // Simulates selection/shift sync while Space/Delete/Enter is pressed inside voice modal
        switcher.setAlphabetKeyboard(ShiftMode.AUTOMATIC)
        assertTrue(voiceView.isVoiceActive(), "Voice modal must stay active on shift/alphabet sync")
        assertEquals(View.VISIBLE, voiceView.visibility)

        // Explicit dismissal via X / ABC / Back closes voice input and returns cleanly to alphabet
        switcher.closeVoiceInput()
        assertFalse(voiceView.isVoiceActive())
        assertEquals(View.GONE, voiceView.visibility)
    }

    @Test
    fun energyVad_detectsPauseWithinEightFrames() {
        val vad = EnergyVad()
        val speechFrame = ShortArray(1024) { 4000 }
        repeat(4) { vad.processFrame(speechFrame, speechFrame.size, gainMultiplier = 1) }
        assertTrue(vad.isSpeechDetected)

        val quietFrame = ShortArray(1024) { 10 }
        repeat(7) { vad.processFrame(quietFrame, quietFrame.size, gainMultiplier = 1) }
        assertTrue(vad.isSpeechDetected, "7 quiet frames should still keep speech active")

        vad.processFrame(quietFrame, quietFrame.size, gainMultiplier = 1)
        assertFalse(vad.isSpeechDetected, "8th quiet frame (~512ms) must finalize utterance cleanly")
    }

    @Test
    fun voiceInputService_onErrorTransitionsToPausedAndClearsBuffer() {
        val service = Robolectric.setupService(helium314.keyboard.latin.voice.VoiceInputService::class.java)
        val bufferField = helium314.keyboard.latin.voice.VoiceInputService::class.java.getDeclaredField("audioBuffer").apply {
            isAccessible = true
        }
        val countField = helium314.keyboard.latin.voice.VoiceInputService::class.java.getDeclaredField("audioSampleCount").apply {
            isAccessible = true
        }
        val stateField = helium314.keyboard.latin.voice.VoiceInputService::class.java.getDeclaredField("currentState").apply {
            isAccessible = true
        }

        bufferField.set(service, FloatArray(16000))
        countField.setInt(service, 9000)
        stateField.set(service, helium314.keyboard.latin.voice.VoiceIpcProtocol.ServiceState.RECORDING)

        service.onError("Mic busy")
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()

        assertEquals(
            helium314.keyboard.latin.voice.VoiceIpcProtocol.ServiceState.PAUSED,
            stateField.get(service)
        )
        assertNull(bufferField.get(service))
        assertEquals(0, countField.getInt(service))
    }

    @Test
    fun voiceInputView_errorMovesToPausedAndFinalTextCommitsImmediately() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val inputView = ime.onCreateInputView()
        ime.setInputView(inputView)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.loadKeyboard(android.view.inputmethod.EditorInfo(), Settings.getValues(), 0, null, null)

        // Create a dummy >1MB model file so VoiceModelManager.hasActiveModel(ime) is true
        val modelFile = helium314.keyboard.latin.voice.VoiceModelManager.getActiveModelFile(ime)
        modelFile.parentFile?.mkdirs()
        java.io.RandomAccessFile(modelFile, "rw").use { it.setLength(1024L * 1024L + 64L) }

        try {
            switcher.setVoiceInputKeyboard()
            val voiceView = switcher.voiceInputView!!
            val committed = mutableListOf<String>()
            val baseListener = ime.mKeyboardActionListener
            val listenerField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("keyboardActionListener").apply {
                isAccessible = true
            }
            listenerField.set(voiceView, object : helium314.keyboard.keyboard.KeyboardActionListener by baseListener {
                override fun onTextInput(rawText: String?) {
                    if (rawText != null) committed.add(rawText)
                }
            })

            val connListenerField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("connectionListener").apply {
                isAccessible = true
            }
            val connListener = connListenerField.get(voiceView) as helium314.keyboard.latin.voice.VoiceInputConnection.VoiceConnectionListener

            // 1. Final transcription commits immediately (0ms) and cannot be clobbered by a trailing preview
            connListener.onFinalTranscription("hello world")
            connListener.onTranscriptionPreview("next phrase")
            org.robolectric.shadows.ShadowLooper.runUiThreadTasks()
            assertEquals(listOf("hello world"), committed)

            // 2. Engine error transitions Voice modal cleanly to PAUSED (matching bouncing-bar pause)
            connListener.onError("Low memory")
            connListener.onStateChanged(helium314.keyboard.latin.voice.VoiceIpcProtocol.ServiceState.ERROR)
            org.robolectric.shadows.ShadowLooper.runUiThreadTasks()

            val pausedField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("isVoicePaused").apply {
                isAccessible = true
            }
            val pulseField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("pulseView").apply {
                isAccessible = true
            }
            val streamField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("streamingText").apply {
                isAccessible = true
            }
            val pulse = pulseField.get(voiceView) as VoicePulseView
            val streamText = streamField.get(voiceView) as android.widget.TextView

            assertTrue(pausedField.getBoolean(voiceView), "Voice modal must enter isVoicePaused=true on engine error")
            assertEquals(VoicePulseView.PulseState.PAUSED, pulse.pulseState)
            assertTrue(streamText.text.toString().contains("Paused: Low memory"))
            switcher.closeVoiceInput()
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun voiceInputView_preservesPausedBannerOnLateFinalAndPausesWithoutModelOnError() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val inputView = ime.onCreateInputView()
        ime.setInputView(inputView)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.loadKeyboard(android.view.inputmethod.EditorInfo(), Settings.getValues(), 0, null, null)

        val modelFile = helium314.keyboard.latin.voice.VoiceModelManager.getActiveModelFile(ime)
        val hadModel = modelFile.exists()
        if (hadModel) modelFile.delete()

        switcher.setVoiceInputKeyboard()
        // Settle Robolectric's async bindService callback before injecting synthetic engine events
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()
        val voiceView = switcher.voiceInputView!!
        val committed = mutableListOf<String>()
        val baseListener = ime.mKeyboardActionListener
        val listenerField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("keyboardActionListener").apply {
            isAccessible = true
        }
        listenerField.set(voiceView, object : helium314.keyboard.keyboard.KeyboardActionListener by baseListener {
            override fun onTextInput(rawText: String?) {
                if (rawText != null) committed.add(rawText)
            }
        })

        val connListenerField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("connectionListener").apply {
            isAccessible = true
        }
        val connListener = connListenerField.get(voiceView) as helium314.keyboard.latin.voice.VoiceInputConnection.VoiceConnectionListener

        // Even without an imported model, a hardware mic error enters paused state cleanly with reason
        connListener.onError("Mic unavailable")
        connListener.onStateChanged(helium314.keyboard.latin.voice.VoiceIpcProtocol.ServiceState.PAUSED)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()

        val streamField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("streamingText").apply {
            isAccessible = true
        }
        val pausedField = helium314.keyboard.latin.voice.VoiceInputView::class.java.getDeclaredField("isVoicePaused").apply {
            isAccessible = true
        }
        val streamText = streamField.get(voiceView) as android.widget.TextView
        assertTrue(pausedField.getBoolean(voiceView))
        assertTrue(
            streamText.text.toString().contains("Paused: Mic unavailable"),
            "Expected Paused: Mic unavailable, got: ${streamText.text}"
        )

        // A trailing finalized utterance commits cleanly without wiping the Paused status banner
        connListener.onFinalTranscription("last spoken words")
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()
        assertEquals(listOf("last spoken words"), committed)
        assertTrue(
            streamText.text.toString().contains("Paused: Mic unavailable"),
            "Expected Paused: Mic unavailable after late final, got: ${streamText.text}"
        )

        switcher.closeVoiceInput()
    }

    @Test
    fun voiceInputService_doesNotResurrectPausedFromIdleWhenUnbound() {
        val service = Robolectric.setupService(helium314.keyboard.latin.voice.VoiceInputService::class.java)
        val stateField = helium314.keyboard.latin.voice.VoiceInputService::class.java.getDeclaredField("currentState").apply {
            isAccessible = true
        }
        val pauseMethod = helium314.keyboard.latin.voice.VoiceInputService::class.java.getDeclaredMethod(
            "pauseEngineOnFailure",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ).apply {
            isAccessible = true
        }

        assertEquals(helium314.keyboard.latin.voice.VoiceIpcProtocol.ServiceState.IDLE, stateField.get(service))
        pauseMethod.invoke(service, "Late error", true, false)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(helium314.keyboard.latin.voice.VoiceIpcProtocol.ServiceState.IDLE, stateField.get(service))
    }
}
