// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference

@Composable
fun MainSettingsScreen(
    onClickAppearance: () -> Unit,
    onClickWordEngine: () -> Unit,
    onClickSecurity: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                Preference(
                    name = stringResource(R.string.settings_screen_appearance),
                    description = "Layouts, currencies, toolbar & desktop shortcuts",
                    onClick = onClickAppearance,
                    icon = R.drawable.ic_settings_preferences
                ) { NextScreenIcon() }
                Preference(
                    name = "Word Engine",
                    description = "Text correction & dictionaries",
                    onClick = onClickWordEngine,
                    icon = R.drawable.ic_settings_correction
                ) { NextScreenIcon() }
                Preference(
                    name = "Security",
                    description = "Pattern lock, privacy vault & security vault",
                    onClick = onClickSecurity,
                    icon = R.drawable.ic_settings_security
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_advanced),
                    description = "Backup & restore, about",
                    onClick = onClickAdvanced,
                    icon = R.drawable.ic_settings_advanced
                ) { NextScreenIcon() }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {})
        }
    }
}
