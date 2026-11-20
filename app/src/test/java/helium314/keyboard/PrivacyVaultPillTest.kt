// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.CapsMode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.security.VaultSessionManager
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class]
)
class PrivacyVaultPillTest {

    @BeforeTest
    fun setUp() {
        ShadowInputMethodService.reset()
    }

    @Test
    fun matchesSuppressedVaultPhrase_preventsImmediateSelfRetrigger_includingLongPhrases() {
        assertFalse(LatinIME.matchesSuppressedVaultPhrase(null, "hello", null))
        assertFalse(LatinIME.matchesSuppressedVaultPhrase("", "hello", null))

        // Short exact or case-insensitive phrase right before cursor
        assertTrue(LatinIME.matchesSuppressedVaultPhrase("MySecret", "prefix MySecret", null))
        assertTrue(LatinIME.matchesSuppressedVaultPhrase("MySecret", "prefix mysecret", null))
        assertFalse(LatinIME.matchesSuppressedVaultPhrase("MySecret", "prefix mysecret ", null))
        assertFalse(LatinIME.matchesSuppressedVaultPhrase("MySecret", "prefix mysecret next", null))

        // Long phrase (>40 chars): getTextBeforeCursor(40, 0) only sees the last 40 chars
        val longPhrase = "0123456789_ABCDEFGHIJ_0123456789_KLMNOPQRST_END"
        val trailing40 = longPhrase.substring(longPhrase.length - 40)
        assertEquals(40, trailing40.length)
        assertTrue(LatinIME.matchesSuppressedVaultPhrase(longPhrase, trailing40, null))
        assertFalse(LatinIME.matchesSuppressedVaultPhrase(longPhrase, "${trailing40}x", null))

        // Typed word fallback match
        assertTrue(LatinIME.matchesSuppressedVaultPhrase("secret", null, "secret"))
        assertFalse(LatinIME.matchesSuppressedVaultPhrase("secret", null, "sec"))
    }

    @Test
    fun keyboardSwitcher_stealthPatternUnlock_restoresSuggestionStripAndAllowsRepeatedUnlocks() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        ime.prefs().edit(commit = true) { clear() }
        VaultSessionManager.resetLockout(ime)
        VaultSessionManager.lockAll()
        assertTrue(VaultSessionManager.savePattern(ime, listOf(0, 1, 2, 5, 8)))

        val inputView = ime.onCreateInputView()
        ime.setInputView(inputView)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.loadKeyboard(EditorInfo(), Settings.getValues(), 0, null, null)

        val suggestionStripView = inputView.findViewById<View>(R.id.suggestion_strip_view)
        val suggestionsStrip = inputView.findViewById<View>(R.id.suggestions_strip)

        // Show stealth pattern unlock for Privacy Vault pill #1
        var successCount = 0
        var cancelCount = 0
        switcher.showStealthPatternUnlock({ successCount++ }, { cancelCount++ })

        assertTrue(switcher.isShowingStealthPatternUnlock)
        assertEquals(View.VISIBLE, switcher.patternUnlockStrip.visibility)
        assertEquals(View.GONE, suggestionStripView.visibility)

        // Simulate successful unlock completion -> stopStealthPatternUnlock() before callback
        switcher.stopStealthPatternUnlock()

        assertFalse(switcher.isShowingPatternUnlock)
        assertEquals(View.GONE, switcher.patternUnlockStrip.visibility)
        assertEquals(View.VISIBLE, switcher.stripContainer.visibility)
        assertEquals(View.VISIBLE, suggestionStripView.visibility)
        assertEquals(View.VISIBLE, suggestionsStrip.visibility)
        assertEquals(View.VISIBLE, switcher.mainKeyboardView.visibility)

        // Subsequent stealth unlock (after session lock/expiry) must work identically
        switcher.showStealthPatternUnlock({ successCount++ }, { cancelCount++ })
        assertTrue(switcher.isShowingStealthPatternUnlock)
        assertEquals(View.VISIBLE, switcher.patternUnlockStrip.visibility)
        assertEquals(View.GONE, suggestionStripView.visibility)

        switcher.stopStealthPatternUnlock()
        assertFalse(switcher.isShowingPatternUnlock)
        assertEquals(View.GONE, switcher.patternUnlockStrip.visibility)
        assertEquals(View.VISIBLE, suggestionStripView.visibility)
        assertEquals(View.VISIBLE, suggestionsStrip.visibility)
        assertEquals(0, cancelCount)
    }

    @Test
    fun keyboardSwitcher_stealthPatternUnlock_cancelViaCloseSecondaryKeyboard_invokesOnCancelAndRestoresStrip() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        ime.prefs().edit(commit = true) { clear() }
        VaultSessionManager.resetLockout(ime)
        VaultSessionManager.lockAll()
        assertTrue(VaultSessionManager.savePattern(ime, listOf(0, 1, 2, 5, 8)))

        val inputView = ime.onCreateInputView()
        ime.setInputView(inputView)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.loadKeyboard(EditorInfo(), Settings.getValues(), 0, null, null)

        val suggestionStripView = inputView.findViewById<View>(R.id.suggestion_strip_view)
        val suggestionsStrip = inputView.findViewById<View>(R.id.suggestions_strip)

        var successCount = 0
        var cancelCount = 0
        switcher.showStealthPatternUnlock({ successCount++ }, { cancelCount++ })
        assertTrue(switcher.isShowingStealthPatternUnlock)

        switcher.closeSecondaryKeyboard()

        assertEquals(0, successCount)
        assertEquals(1, cancelCount)
        assertFalse(switcher.isShowingPatternUnlock)
        assertEquals(View.GONE, switcher.patternUnlockStrip.visibility)
        assertEquals(View.VISIBLE, suggestionStripView.visibility)
        assertEquals(View.VISIBLE, suggestionsStrip.visibility)
    }

    @Test
    fun keyboardSwitcher_liteMode_detachesPatternUnlockViewAfterStop() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        ime.prefs().edit(commit = true) {
            clear()
            putBoolean(Settings.PREF_LITE_MODE, true)
        }
        Settings.getInstance().loadSettings(ime, Settings.getValues().mLocale, Settings.getValues().mInputAttributes)
        assertTrue(Settings.getValues().mLiteMode)

        val inputView = ime.onCreateInputView()
        ime.setInputView(inputView)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.loadKeyboard(EditorInfo(), Settings.getValues(), 0, null, null)

        switcher.showStealthPatternUnlock({}, {})
        assertTrue(switcher.isShowingStealthPatternUnlock)

        switcher.stopStealthPatternUnlock()

        val field = KeyboardSwitcher::class.java.getDeclaredField("mPatternUnlockView").apply {
            isAccessible = true
        }
        assertNull(field.get(switcher), "mPatternUnlockView should be detached and nulled in Lite Mode")
        assertEquals(View.GONE, switcher.patternUnlockStrip.visibility)
        assertEquals(View.VISIBLE, inputView.findViewById<View>(R.id.suggestion_strip_view).visibility)
    }

    @Test
    @Config(
        sdk = [36],
        shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class]
    )
    fun inputLogic_commitPrivacyVaultEntry_replacesTriggerAndResetsComposingWord() {
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        val editorInfo = EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        ime.onStartInput(editorInfo, false)
        val inputView = ime.onCreateInputView()
        ime.setInputView(inputView)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.loadKeyboard(editorInfo, Settings.getValues(), 0, null, null)

        val inputLogicField = LatinIME::class.java.getDeclaredField("mInputLogic").apply {
            isAccessible = true
        }
        val inputLogic = inputLogicField.get(ime) as InputLogic
        val composerField = InputLogic::class.java.getDeclaredField("mWordComposer").apply {
            isAccessible = true
        }
        val wordComposer = composerField.get(inputLogic) as WordComposer

        // Simulate typing a 3-letter vault trigger "sec"
        for (ch in "sec") {
            ime.onEvent(Event.createEventForCodePointFromUnknownSource(ch.code))
        }
        assertEquals("sec", ShadowInputMethodService.text)
        assertTrue(wordComposer.isComposingWord)

        // Commit Privacy Vault phrase replacing the 3-char trigger
        val phrase = "my secret vault"
        inputLogic.commitPrivacyVaultEntry(Settings.getValues(), phrase, 3, CapsMode.OFF)

        assertEquals(phrase, ShadowInputMethodService.text)
        assertEquals("", ShadowInputMethodService.composingText)
        assertFalse(wordComposer.isComposingWord, "WordComposer must be reset after committing vault entry")
        assertEquals("", wordComposer.typedWord)

        // Subsequent typing must append cleanly without deleting or mangling the committed phrase
        for (ch in " abc") {
            ime.onEvent(Event.createEventForCodePointFromUnknownSource(ch.code))
        }
        assertEquals("$phrase abc", ShadowInputMethodService.text)
    }

    @Test
    fun keyboardSwitcher_showStealthPatternUnlock_separatesSecurityAndPrivacySessions() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        ime.prefs().edit(commit = true) { clear() }
        VaultSessionManager.resetLockout(ime)
        VaultSessionManager.lockAll()

        val privacyPattern = listOf(0, 1, 2, 5, 8)
        val securityPattern = listOf(2, 4, 6, 7, 8)
        assertTrue(VaultSessionManager.savePattern(ime, privacyPattern))
        VaultSessionManager.setSeparatePatternsEnabled(ime, true)
        assertTrue(VaultSessionManager.saveSecurityPattern(ime, securityPattern))

        val inputView = ime.onCreateInputView()
        ime.setInputView(inputView)
        val switcher = KeyboardSwitcher.getInstance()
        switcher.loadKeyboard(EditorInfo(), Settings.getValues(), 0, null, null)

        var unlockedSecurity = false
        switcher.showStealthPatternUnlock({ unlockedSecurity = true }, null, true)
        val patternViewField = KeyboardSwitcher::class.java.getDeclaredField("mPatternUnlockView").apply {
            isAccessible = true
        }
        val patternView = patternViewField.get(switcher)!!
        val handleMethod = patternView.javaClass.getDeclaredMethod("handlePatternEntered", List::class.java).apply {
            isAccessible = true
        }

        // Entering Privacy pattern while Security Vault unlock is active must fail
        handleMethod.invoke(patternView, privacyPattern)
        assertFalse(VaultSessionManager.isSecuritySessionValid())
        assertFalse(unlockedSecurity)

        // Entering Security pattern succeeds and unlocks Security session only
        VaultSessionManager.resetLockout(ime)
        handleMethod.invoke(patternView, securityPattern)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue(unlockedSecurity)
        assertTrue(VaultSessionManager.isSecuritySessionValid())
        assertFalse(VaultSessionManager.isPrivacySessionValid())
    }
}
