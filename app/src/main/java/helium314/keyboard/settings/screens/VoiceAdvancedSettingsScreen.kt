// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun VoiceAdvancedSettingsScreen(
    onClickBack: () -> Unit,
) {
    val items = listOf(
        Settings.PREF_VOICE_SUPPRESS_ANNOTATIONS,
        Settings.PREF_VOICE_USE_BEAM_SEARCH,
        Settings.PREF_VOICE_CONTINUOUS_STREAMING,
        Settings.PREF_VOICE_VERBOSE_MODE,
    )

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.voice_input_advanced_title),
        settings = items
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                PreferenceCategory(title = stringResource(R.string.voice_input_advanced_decoding_cat))

                SwitchPreference(
                    name = stringResource(R.string.voice_input_suppress_annotations),
                    description = stringResource(R.string.voice_input_suppress_annotations_summary),
                    key = Settings.PREF_VOICE_SUPPRESS_ANNOTATIONS,
                    default = Defaults.PREF_VOICE_SUPPRESS_ANNOTATIONS
                )

                SwitchPreference(
                    name = stringResource(R.string.voice_input_use_beam_search),
                    description = stringResource(R.string.voice_input_use_beam_search_summary),
                    key = Settings.PREF_VOICE_USE_BEAM_SEARCH,
                    default = Defaults.PREF_VOICE_USE_BEAM_SEARCH
                )

                SwitchPreference(
                    name = stringResource(R.string.voice_input_continuous_streaming),
                    description = stringResource(R.string.voice_input_continuous_streaming_summary),
                    key = Settings.PREF_VOICE_CONTINUOUS_STREAMING,
                    default = Defaults.PREF_VOICE_CONTINUOUS_STREAMING
                )

                PreferenceCategory(title = stringResource(R.string.voice_input_advanced_diagnostics_cat))

                SwitchPreference(
                    name = stringResource(R.string.voice_input_verbose_mode),
                    description = stringResource(R.string.voice_input_verbose_mode_summary),
                    key = Settings.PREF_VOICE_VERBOSE_MODE,
                    default = Defaults.PREF_VOICE_VERBOSE_MODE
                )
            }
        }
    }
}

fun createVoiceAdvancedSettings(context: Context) = listOf(
    Setting(
        context,
        Settings.PREF_VOICE_SUPPRESS_ANNOTATIONS,
        R.string.voice_input_suppress_annotations,
        R.string.voice_input_suppress_annotations_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VOICE_SUPPRESS_ANNOTATIONS)
    },
    Setting(
        context,
        Settings.PREF_VOICE_USE_BEAM_SEARCH,
        R.string.voice_input_use_beam_search,
        R.string.voice_input_use_beam_search_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VOICE_USE_BEAM_SEARCH)
    },
    Setting(
        context,
        Settings.PREF_VOICE_CONTINUOUS_STREAMING,
        R.string.voice_input_continuous_streaming,
        R.string.voice_input_continuous_streaming_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VOICE_CONTINUOUS_STREAMING)
    },
    Setting(
        context,
        Settings.PREF_VOICE_VERBOSE_MODE,
        R.string.voice_input_verbose_mode,
        R.string.voice_input_verbose_mode_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VOICE_VERBOSE_MODE)
    }
)
