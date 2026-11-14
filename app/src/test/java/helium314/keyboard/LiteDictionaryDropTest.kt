// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import androidx.core.content.edit
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodManager2::class,
])
class LiteDictionaryDropTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    @AfterTest
    fun tearDown() {
        latinIME.prefs().edit(commit = true) {
            remove(Settings.PREF_ADDITIONAL_SUBTYPES)
            remove(Settings.PREF_ENABLED_SUBTYPES)
            remove(Settings.PREF_SELECTED_SUBTYPE)
        }
        SubtypeSettings.reloadEnabledSubtypes(latinIME)
    }

    @Test
    fun dropAndRestoreSecondaryDictionaries_keepsPrimaryWarmAndRespectsUsesSameSettings() {
        val prefs = latinIME.prefs()
        val enSubtype = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first()
        val mainLocale = enSubtype.locale()
        val withSecondary = enSubtype.toSettingsSubtype().with(ExtraValue.SECONDARY_LOCALES, "fr")
        val updatedSubtype = withSecondary.toAdditionalSubtype()
        val canonicalSettingsSubtype = updatedSubtype.toSettingsSubtype()

        prefs.edit(commit = true) {
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, canonicalSettingsSubtype.toPref())
            putString(Settings.PREF_ENABLED_SUBTYPES, canonicalSettingsSubtype.toPref())
            putString(Settings.PREF_SELECTED_SUBTYPE, canonicalSettingsSubtype.toPref())
        }
        SubtypeSettings.reloadEnabledSubtypes(latinIME)

        val selected = SubtypeSettings.getSelectedSubtype(prefs)
        assertEquals(mainLocale, selected.locale(), "Selected subtype locale mismatch")
        val secLocales = getSecondaryLocales(selected.extraValue)
        assertEquals(listOf(Locale.FRENCH), secLocales, "Secondary locales mismatch")

        val sv = Settings.getValues()
        val facilitator = DictionaryFacilitatorImpl()
        facilitator.resetDictionaries(
            context = latinIME,
            newLocale = mainLocale,
            useContactsDict = sv.mUseContactsDictionary,
            useAppsDict = sv.mUseAppsDictionary,
            usePersonalizedDicts = sv.mUsePersonalizedDicts,
            forceReloadMainDictionary = false,
            dictNamePrefix = "",
            listener = null
        )

        assertFalse(facilitator.isSecondaryDropped)
        assertTrue(facilitator.isForLocale(mainLocale))
        val onlyMain = facilitator.usesSameSettings(listOf(mainLocale), false, false, sv.mUsePersonalizedDicts)
        assertFalse(onlyMain, "Expected secondary French group to be loaded too (main=$mainLocale, sec=$secLocales)")
        assertTrue(
            facilitator.usesSameSettings(listOf(mainLocale, Locale.FRENCH), false, false, sv.mUsePersonalizedDicts),
            "Should report same settings before dropping secondary dictionary"
        )

        facilitator.dropSecondaryDictionaries()
        assertTrue(facilitator.isSecondaryDropped)
        assertTrue(facilitator.isForLocale(mainLocale), "Primary dictionary group must stay active")
        assertTrue(
            facilitator.usesSameSettings(listOf(mainLocale, Locale.FRENCH), false, false, sv.mUsePersonalizedDicts),
            "While secondary dicts are intentionally dropped in Lite Mode, usesSameSettings should return true so IME doesn't reload immediately on focus"
        )
        assertFalse(
            facilitator.usesSameSettings(listOf(Locale.GERMAN, Locale.FRENCH), false, false, sv.mUsePersonalizedDicts),
            "Changing primary locale must still trigger dictionary reload"
        )

        facilitator.restoreSecondaryDictionaries(latinIME, null)
        assertFalse(facilitator.isSecondaryDropped)
        assertTrue(facilitator.usesSameSettings(listOf(mainLocale, Locale.FRENCH), false, false, sv.mUsePersonalizedDicts))
        facilitator.closeDictionaries()
    }
}
