// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.security.BiometricAuthTrampolineActivity
import helium314.keyboard.security.PatternGridView
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory

@Composable
fun SecurityScreen(
    onClickUnlockMethods: () -> Unit,
    onClickPrivacyVault: () -> Unit,
    onClickSecurityVault: () -> Unit,
    onClickKdbxEntries: () -> Unit = {},
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val isPatternConfigured = remember { VaultSessionManager.isPatternSet(context) }
    val isGatekeeperEnabled = remember { VaultSessionManager.isGatekeeperEnabled(context) }
    val isSeparatePatterns = remember { VaultSessionManager.isSeparatePatternsEnabled(context) }
    val isSecurityPatternSet = remember { VaultSessionManager.isSecurityPatternSet(context) }
    val primaryUnlockMethod = remember { VaultSessionManager.getPrimaryUnlockMethod(context) }
    var isContextSuggestions by remember {
        mutableStateOf(context.prefs().getBoolean("pref_vault_context_suggestions", true))
    }
    var isUnlocked by remember {
        mutableStateOf(
            !isPatternConfigured ||
            !isGatekeeperEnabled ||
            VaultSessionManager.isSettingsSessionValid()
        )
    }

    if (!isUnlocked) {
        SecurityGatekeeperUnlockScreen(
            onClickBack = onClickBack,
            onUnlocked = {
                VaultSessionManager.startSettingsSession()
                isUnlocked = true
                Toast.makeText(context, "Security Settings Unlocked", Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

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
                // SECTION 1: PRIVACY VAULT PAGE
                PreferenceCategory(title = "Privacy Vault")
                Preference(
                    name = "Privacy Vault",
                    description = "Private phrases, encrypted notes & clipboard history",
                    onClick = onClickPrivacyVault,
                    icon = R.drawable.ic_dictionary
                ) { NextScreenIcon() }

                // SECTION 2: SECURITY VAULT PAGE
                PreferenceCategory(title = "Security Vault")
                Preference(
                    name = "Security Vault Settings",
                    description = "KDBX database connection, Argon2id encryption, key file binding & 2-way sync",
                    onClick = onClickSecurityVault,
                    icon = R.drawable.ic_setup_key
                ) { NextScreenIcon() }

                Preference(
                    name = "KDBX Vault Entries",
                    description = "Browse, search, edit, create and organize credentials and folders",
                    onClick = onClickKdbxEntries,
                    icon = R.drawable.ic_vpn_key
                ) { NextScreenIcon() }

                Preference(
                    name = "Context Suggestion Pills",
                    description = if (isContextSuggestions) {
                        "Display matching account auto-fill pills on suggestion strip"
                    } else {
                        "Auto-fill pills disabled"
                    },
                    onClick = {
                        val next = !isContextSuggestions
                        context.prefs().edit { putBoolean("pref_vault_context_suggestions", next) }
                        isContextSuggestions = next
                    },
                    icon = R.drawable.ic_vpn_key
                ) {
                    Switch(
                        checked = isContextSuggestions,
                        onCheckedChange = { checked ->
                            context.prefs().edit { putBoolean("pref_vault_context_suggestions", checked) }
                            isContextSuggestions = checked
                        }
                    )
                }

                // SECTION 3: UNLOCK METHODS PAGE
                PreferenceCategory(title = "Unlock & Gatekeeper")
                Preference(
                    name = "Unlock Methods",
                    description = "Configure main security gate, pattern lock, biometrics, phone PIN, and stealth mode",
                    onClick = onClickUnlockMethods,
                    icon = R.drawable.ic_settings_security
                ) { NextScreenIcon() }

                Preference(
                    name = "Lock All Vault Sessions",
                    description = "Immediately purge active Privacy, Security, and Settings sessions",
                    onClick = {
                        VaultSessionManager.lockAll()
                        Toast.makeText(context, "All vault sessions locked", Toast.LENGTH_SHORT).show()
                    },
                    icon = R.drawable.ic_settings_about_log
                )
            }
        }
    }
}

@Composable
private fun SecurityGatekeeperUnlockScreen(
    onClickBack: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val primaryMethod = remember { VaultSessionManager.getPrimaryUnlockMethod(context) }
    var statusText by remember {
        mutableStateOf(
            if (primaryMethod == VaultSessionManager.UnlockMethod.BIOMETRIC)
                "Biometric challenge ready. Use sensor or draw pattern below."
            else if (primaryMethod == VaultSessionManager.UnlockMethod.DEVICE_PIN)
                "Device PIN ready. Tap unlock or draw pattern below."
            else
                "Draw pattern to access Security Settings"
        )
    }

    LaunchedEffect(Unit) {
        if (primaryMethod == VaultSessionManager.UnlockMethod.BIOMETRIC ||
            primaryMethod == VaultSessionManager.UnlockMethod.DEVICE_PIN) {
            val mode = if (primaryMethod == VaultSessionManager.UnlockMethod.DEVICE_PIN)
                BiometricAuthTrampolineActivity.MODE_DEVICE_PIN
            else BiometricAuthTrampolineActivity.MODE_BIOMETRIC
            BiometricAuthTrampolineActivity.launch(
                context,
                mode,
                BiometricAuthTrampolineActivity.VAULT_SETTINGS
            ) { success, _ ->
                if (success) {
                    onUnlocked()
                }
            }
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Security",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings_security),
                            contentDescription = "Lock",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Security Gatekeeper",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PatternGridView(ctx).apply {
                                onPatternCompleted = { pattern ->
                                    val valid = VaultSessionManager.verifySecurityPattern(ctx, pattern)
                                    if (valid) {
                                        statusText = "Verification successful!"
                                        postDelayed({
                                            clearPattern()
                                            onUnlocked()
                                        }, 400)
                                    } else {
                                        statusText = "Incorrect pattern. Try again."
                                        setErrorState()
                                        postDelayed({
                                            clearPattern()
                                            statusText = "Draw pattern to access Security Settings"
                                        }, 700)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(256.dp)
                    )
                }
            }
        }
    }
}
