// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import helium314.keyboard.security.PatternGridView
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory

@Composable
fun SecurityScreen(
    onClickPatternLock: () -> Unit,
    onClickSecurityPatternLock: () -> Unit = {},
    onClickPrivacyVault: () -> Unit,
    onClickSecurityVault: () -> Unit,
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    var isPatternConfigured by remember { mutableStateOf(VaultSessionManager.isPatternSet(context)) }
    var isGatekeeperEnabled by remember { mutableStateOf(VaultSessionManager.isGatekeeperEnabled(context)) }
    var isSeparatePatterns by remember { mutableStateOf(VaultSessionManager.isSeparatePatternsEnabled(context)) }
    var isSecurityPatternSet by remember { mutableStateOf(VaultSessionManager.isSecurityPatternSet(context)) }
    var isUnlocked by remember {
        mutableStateOf(
            !isPatternConfigured ||
            !isGatekeeperEnabled ||
            VaultSessionManager.isSecuritySessionValid()
        )
    }

    if (!isUnlocked) {
        SecurityGatekeeperUnlockScreen(
            onClickBack = onClickBack,
            onUnlocked = {
                VaultSessionManager.startSecuritySession()
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
                PreferenceCategory(title = "Authentication & Patterns")
                Preference(
                    name = "Master Pattern",
                    description = if (isPatternConfigured) {
                        "Master pattern configured (Active for Privacy Vault)"
                    } else {
                        "No pattern configured (Tap to configure)"
                    },
                    onClick = onClickPatternLock,
                    icon = R.drawable.ic_settings_security
                ) { NextScreenIcon() }

                Preference(
                    name = "Separate Vault Patterns",
                    description = if (isSeparatePatterns) {
                        "Independent patterns: Privacy Vault vs Security Vault/Settings"
                    } else {
                        "Single unified master pattern for all vaults and settings"
                    },
                    onClick = {
                        val next = !isSeparatePatterns
                        VaultSessionManager.setSeparatePatternsEnabled(context, next)
                        isSeparatePatterns = next
                        isSecurityPatternSet = VaultSessionManager.isSecurityPatternSet(context)
                    },
                    icon = R.drawable.ic_setup_key
                ) {
                    Switch(
                        checked = isSeparatePatterns,
                        onCheckedChange = { checked ->
                            VaultSessionManager.setSeparatePatternsEnabled(context, checked)
                            isSeparatePatterns = checked
                            isSecurityPatternSet = VaultSessionManager.isSecurityPatternSet(context)
                        }
                    )
                }

                if (isSeparatePatterns) {
                    Preference(
                        name = "Security Vault Pattern",
                        description = if (isSecurityPatternSet) {
                            "Dedicated security pattern configured (Active for Settings & KDBX)"
                        } else {
                            "No dedicated security pattern configured (Tap to configure)"
                        },
                        onClick = onClickSecurityPatternLock,
                        icon = R.drawable.ic_settings_security
                    ) { NextScreenIcon() }
                }

                PreferenceCategory(title = "Vault Modules")
                Preference(
                    name = "Privacy Vault",
                    description = "Quick private phrases, shortcuts & credentials (Isolated & Protected)",
                    onClick = onClickPrivacyVault,
                    icon = R.drawable.ic_dictionary
                ) { NextScreenIcon() }

                Preference(
                    name = "Security Vault",
                    description = "KeePass KDBX & TOTP credentials (Coming in Phase 27)",
                    onClick = onClickSecurityVault,
                    icon = R.drawable.ic_setup_key
                ) { NextScreenIcon() }

                PreferenceCategory(title = "Protection & Gatekeeper")
                Preference(
                    name = "Require unlock for Security Settings",
                    description = if (isGatekeeperEnabled) {
                        "Pattern challenge required to access Security settings"
                    } else {
                        "Direct access to Security settings without pattern prompt"
                    },
                    onClick = {
                        if (!isPatternConfigured) {
                            Toast.makeText(context, "Configure a pattern first", Toast.LENGTH_SHORT).show()
                        } else {
                            val next = !isGatekeeperEnabled
                            VaultSessionManager.setGatekeeperEnabled(context, next)
                            isGatekeeperEnabled = next
                        }
                    },
                    icon = R.drawable.ic_settings_about_log
                ) {
                    Switch(
                        checked = isGatekeeperEnabled,
                        onCheckedChange = { checked ->
                            if (!isPatternConfigured && checked) {
                                Toast.makeText(context, "Configure a pattern first", Toast.LENGTH_SHORT).show()
                            } else {
                                VaultSessionManager.setGatekeeperEnabled(context, checked)
                                isGatekeeperEnabled = checked
                            }
                        },
                        enabled = isPatternConfigured
                    )
                }

                Preference(
                    name = "Lock All Vault Sessions",
                    description = "Immediately purge active Privacy and Security vault sessions",
                    onClick = {
                        VaultSessionManager.lockAll()
                        Toast.makeText(context, "All vault sessions locked", Toast.LENGTH_SHORT).show()
                    },
                    icon = R.drawable.ic_settings_security
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
    var statusText by remember { mutableStateOf("Draw pattern to access Security Settings") }

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
