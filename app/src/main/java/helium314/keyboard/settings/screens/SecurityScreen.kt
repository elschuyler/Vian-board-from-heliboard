// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory

@Composable
fun SecurityScreen(
    onClickPatternLock: () -> Unit,
    onClickPrivacyVault: () -> Unit,
    onClickSecurityVault: () -> Unit,
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val isPatternConfigured = remember { mutableStateOf(VaultSessionManager.isPatternSet(context)) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Security",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                PreferenceCategory(title = "Authentication & Pattern")
                Preference(
                    name = "Pattern Lock",
                    description = if (isPatternConfigured.value) {
                        "Master pattern configured (Active)"
                    } else {
                        "No pattern configured (Tap to configure)"
                    },
                    onClick = onClickPatternLock,
                    icon = R.drawable.ic_settings_security
                ) { NextScreenIcon() }

                PreferenceCategory(title = "Vault Modules")
                Preference(
                    name = "Privacy Vault",
                    description = "Quick private phrases & personal dictionary twin (Coming in Phase 23)",
                    onClick = onClickPrivacyVault,
                    icon = R.drawable.ic_dictionary
                ) { NextScreenIcon() }

                Preference(
                    name = "Security Vault",
                    description = "KeePass KDBX & TOTP credentials (Coming in Phase 24)",
                    onClick = onClickSecurityVault,
                    icon = R.drawable.ic_setup_key
                ) { NextScreenIcon() }

                PreferenceCategory(title = "Protection & Gatekeeper")
                Preference(
                    name = "Require unlock for Security Settings",
                    description = "Locked settings gate (Disabled for now — arrangement placeholder for future release)",
                    onClick = {
                        Toast.makeText(
                            context,
                            "Settings gatekeeper arrangement placeholder (unlocked for now)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    icon = R.drawable.ic_settings_about_log
                ) {
                    Switch(
                        checked = false,
                        onCheckedChange = null,
                        enabled = false
                    )
                }
            }
        }
    }
}
