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
    var isBiometricsAvailable by remember { mutableStateOf(VaultSessionManager.isBiometricsSupported(context)) }
    var isBiometricEnabled by remember { mutableStateOf(VaultSessionManager.isBiometricEnabled(context)) }
    var isDevicePinSupported by remember { mutableStateOf(VaultSessionManager.isDevicePinSupported(context)) }
    var isPhonePinEnabled by remember { mutableStateOf(VaultSessionManager.isPhonePinEnabled(context)) }
    var primaryUnlockMethod by remember { mutableStateOf(VaultSessionManager.getPrimaryUnlockMethod(context)) }
    var backupUnlockMethod by remember { mutableStateOf(VaultSessionManager.getBackupUnlockMethod(context)) }
    var showPrimaryMethodDialog by remember { mutableStateOf(false) }
    var showBackupMethodDialog by remember { mutableStateOf(false) }
    var isStealthGatekeeper by remember {
        mutableStateOf(context.prefs().getBoolean("pref_vault_stealth_gatekeeper", true))
    }
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
                // SECTION 1: PRIVACY VAULT
                PreferenceCategory(title = "Privacy Vault")
                Preference(
                    name = "Open Privacy Vault",
                    description = "Private phrases, clipboard history & quick personal shortcuts",
                    onClick = onClickPrivacyVault,
                    icon = R.drawable.ic_dictionary
                ) { NextScreenIcon() }

                Preference(
                    name = "Privacy Vault Unlock",
                    description = if (!isSeparatePatterns) {
                        if (isPatternConfigured) "Linked to Unified Master Pattern (Active)"
                        else "Linked to Unified Master Pattern (Tap to configure)"
                    } else {
                        if (isPatternConfigured) "Dedicated Privacy pattern configured (Tap to change)"
                        else "No pattern configured (Tap to configure)"
                    },
                    onClick = onClickPatternLock,
                    icon = R.drawable.ic_settings_security
                ) { NextScreenIcon() }

                // SECTION 2: SECURITY VAULT
                PreferenceCategory(title = "Security Vault")
                Preference(
                    name = "Open Security Vault",
                    description = "KeePass KDBX file onboarding, 1:1 folders, credentials & 2-way sync",
                    onClick = onClickSecurityVault,
                    icon = R.drawable.ic_setup_key
                ) { NextScreenIcon() }

                Preference(
                    name = "Security Vault Unlock",
                    description = if (!isSeparatePatterns) {
                        if (isPatternConfigured) "Linked to Unified Master Pattern (Active)"
                        else "Linked to Unified Master Pattern (Tap to configure)"
                    } else {
                        if (isSecurityPatternSet) "Dedicated Security pattern configured (Tap to change)"
                        else "No dedicated security pattern configured (Tap to configure)"
                    },
                    onClick = {
                        if (isSeparatePatterns) onClickSecurityPatternLock()
                        else onClickPatternLock()
                    },
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

                // SECTION 3: UNLOCK MECHANISMS & GATEKEEPER
                PreferenceCategory(title = "Unlock Mechanisms & Gatekeeper")
                Preference(
                    name = "Require Unlock for Security Settings",
                    description = if (isGatekeeperEnabled) {
                        "Security challenge required before opening Security settings"
                    } else {
                        "Direct access to Security settings without challenge"
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
                    name = "Independent Vault Credentials",
                    description = if (isSeparatePatterns) {
                        "Separate credentials for Privacy Vault, Security Vault, and Settings"
                    } else {
                        "Single unified master credential for all 3 vaults and settings"
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

                if (!isSeparatePatterns) {
                    Preference(
                        name = "Master Unlock Pattern",
                        description = if (isPatternConfigured) {
                            "Unified master pattern configured (Active for all 3 targets)"
                        } else {
                            "No master pattern configured (Tap to configure master unlock)"
                        },
                        onClick = onClickPatternLock,
                        icon = R.drawable.ic_settings_security
                    ) { NextScreenIcon() }
                } else {
                    Preference(
                        name = "Settings Gatekeeper Pattern",
                        description = if (isSecurityPatternSet) {
                            "Dedicated gatekeeper pattern configured (Active for Settings challenge)"
                        } else {
                            "No dedicated pattern configured (Tap to configure)"
                        },
                        onClick = onClickSecurityPatternLock,
                        icon = R.drawable.ic_settings_security
                    ) { NextScreenIcon() }
                }

                PreferenceCategory(title = "Authentication Methods (Tri-Method)")
                Preference(
                    name = "Primary Unlock Method",
                    description = when (primaryUnlockMethod) {
                        VaultSessionManager.UnlockMethod.BIOMETRIC -> "Biometric Unlock (Fingerprint / Face)"
                        VaultSessionManager.UnlockMethod.DEVICE_PIN -> "Phone PIN / Password (Device Credential)"
                        else -> "Pattern Unlock (Default)"
                    },
                    onClick = {
                        if (isPatternConfigured || isSecurityPatternSet) {
                            showPrimaryMethodDialog = true
                        } else {
                            Toast.makeText(context, "Configure pattern first as master baseline", Toast.LENGTH_SHORT).show()
                        }
                    },
                    icon = R.drawable.ic_settings_security
                ) { NextScreenIcon() }

                Preference(
                    name = "Backup Unlock Method",
                    description = when (backupUnlockMethod) {
                        VaultSessionManager.UnlockMethod.DEVICE_PIN -> "Phone PIN / Password (Fallback if biometric fails)"
                        else -> "Pattern Unlock (Default fallback)"
                    },
                    onClick = {
                        if (isPatternConfigured || isSecurityPatternSet) {
                            showBackupMethodDialog = true
                        } else {
                            Toast.makeText(context, "Configure pattern first as master baseline", Toast.LENGTH_SHORT).show()
                        }
                    },
                    icon = R.drawable.ic_vpn_key
                ) { NextScreenIcon() }

                Preference(
                    name = "Test Primary Authentication",
                    description = "Verify biometric or device credential challenge right now",
                    onClick = {
                        if (primaryUnlockMethod == VaultSessionManager.UnlockMethod.BIOMETRIC ||
                            primaryUnlockMethod == VaultSessionManager.UnlockMethod.DEVICE_PIN) {
                            val mode = if (primaryUnlockMethod == VaultSessionManager.UnlockMethod.DEVICE_PIN)
                                BiometricAuthTrampolineActivity.MODE_DEVICE_PIN
                            else BiometricAuthTrampolineActivity.MODE_BIOMETRIC
                            BiometricAuthTrampolineActivity.launch(
                                context,
                                mode,
                                BiometricAuthTrampolineActivity.VAULT_SETTINGS
                            ) { success, backupRequested ->
                                if (success) {
                                    Toast.makeText(context, "Authentication verified successfully!", Toast.LENGTH_SHORT).show()
                                } else if (backupRequested) {
                                    Toast.makeText(context, "Backup fallback requested", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Authentication failed or canceled", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Toast.makeText(context, "Pattern unlock is active as primary method", Toast.LENGTH_SHORT).show()
                        }
                    },
                    icon = R.drawable.ic_settings_security
                )

                PreferenceCategory(title = "Stealth & Session Control")
                Preference(
                    name = "Stealth Gatekeeper",
                    description = if (isStealthGatekeeper) {
                        "Disguised pattern unlock over standard QWERTY keys with zero visual tell"
                    } else {
                        "Standard tactile pattern unlock screen"
                    },
                    onClick = {
                        val next = !isStealthGatekeeper
                        context.prefs().edit { putBoolean("pref_vault_stealth_gatekeeper", next) }
                        isStealthGatekeeper = next
                    },
                    icon = R.drawable.ic_settings_security
                ) {
                    Switch(
                        checked = isStealthGatekeeper,
                        onCheckedChange = { checked ->
                            context.prefs().edit { putBoolean("pref_vault_stealth_gatekeeper", checked) }
                            isStealthGatekeeper = checked
                        }
                    )
                }

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

        if (showPrimaryMethodDialog) {
            AlertDialog(
                onDismissRequest = { showPrimaryMethodDialog = false },
                title = { Text("Select Primary Unlock Method") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    primaryUnlockMethod = VaultSessionManager.UnlockMethod.PATTERN
                                    VaultSessionManager.setPrimaryUnlockMethod(context, VaultSessionManager.UnlockMethod.PATTERN)
                                    showPrimaryMethodDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = primaryUnlockMethod == VaultSessionManager.UnlockMethod.PATTERN,
                                onClick = null
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Pattern Unlock (Default)", fontWeight = FontWeight.Bold)
                                Text("Tactile 3x3 pattern matrix or stealth QWERTY overlay", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isBiometricsAvailable) {
                                    if (isBiometricsAvailable) {
                                        primaryUnlockMethod = VaultSessionManager.UnlockMethod.BIOMETRIC
                                        VaultSessionManager.setPrimaryUnlockMethod(context, VaultSessionManager.UnlockMethod.BIOMETRIC)
                                        showPrimaryMethodDialog = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = primaryUnlockMethod == VaultSessionManager.UnlockMethod.BIOMETRIC,
                                onClick = null,
                                enabled = isBiometricsAvailable
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Biometric (Fingerprint / Face)", fontWeight = FontWeight.Bold)
                                Text(
                                    if (isBiometricsAvailable) "Quick biometric sensor verification"
                                    else "Biometric hardware not available or enrolled",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isDevicePinSupported) {
                                    if (isDevicePinSupported) {
                                        primaryUnlockMethod = VaultSessionManager.UnlockMethod.DEVICE_PIN
                                        VaultSessionManager.setPrimaryUnlockMethod(context, VaultSessionManager.UnlockMethod.DEVICE_PIN)
                                        showPrimaryMethodDialog = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = primaryUnlockMethod == VaultSessionManager.UnlockMethod.DEVICE_PIN,
                                onClick = null,
                                enabled = isDevicePinSupported
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Phone PIN / Password", fontWeight = FontWeight.Bold)
                                Text(
                                    if (isDevicePinSupported) "Android system lockscreen PIN or password"
                                    else "No device lockscreen PIN or password set on device",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPrimaryMethodDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showBackupMethodDialog) {
            AlertDialog(
                onDismissRequest = { showBackupMethodDialog = false },
                title = { Text("Select Backup Unlock Method") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "The backup method automatically activates if primary biometric authentication fails or is bypassed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    backupUnlockMethod = VaultSessionManager.UnlockMethod.PATTERN
                                    VaultSessionManager.setBackupUnlockMethod(context, VaultSessionManager.UnlockMethod.PATTERN)
                                    showBackupMethodDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = backupUnlockMethod == VaultSessionManager.UnlockMethod.PATTERN,
                                onClick = null
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Pattern Unlock (Default)", fontWeight = FontWeight.Bold)
                                Text("Always available offline fallback", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isDevicePinSupported) {
                                    if (isDevicePinSupported) {
                                        backupUnlockMethod = VaultSessionManager.UnlockMethod.DEVICE_PIN
                                        VaultSessionManager.setBackupUnlockMethod(context, VaultSessionManager.UnlockMethod.DEVICE_PIN)
                                        showBackupMethodDialog = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = backupUnlockMethod == VaultSessionManager.UnlockMethod.DEVICE_PIN,
                                onClick = null,
                                enabled = isDevicePinSupported
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Phone PIN / Password", fontWeight = FontWeight.Bold)
                                Text(
                                    if (isDevicePinSupported) "System lockscreen credential fallback"
                                    else "No device PIN configured",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBackupMethodDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
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
