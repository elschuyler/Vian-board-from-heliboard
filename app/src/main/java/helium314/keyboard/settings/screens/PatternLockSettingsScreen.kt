// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.security.PatternGridView
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.PreferenceCategory

@Composable
fun PatternLockSettingsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    var isPatternSet by remember { mutableStateOf(VaultSessionManager.isPatternSet(context)) }
    var isSettingNewPattern by remember { mutableStateOf(!isPatternSet) }

    // Setup state: 0 = Draw initial pattern, 1 = Confirm pattern
    var setupStep by remember { mutableStateOf(0) }
    var provisionalPattern by remember { mutableStateOf<List<Int>?>(null) }
    var instructionText by remember {
        mutableStateOf(
            if (!isPatternSet) "Draw pattern to configure (connect at least 4 dots)"
            else "Master unlock pattern is active"
        )
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Pattern Lock",
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
                // Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPatternSet) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (isPatternSet) "Status: Pattern Configured" else "Status: Not Configured",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isPatternSet) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isPatternSet) {
                                "Vaults are secured with hardware-backed SHA-256 salted pattern authentication."
                            } else {
                                "Configure a 3x3 pattern to unlock Privacy Vault and Security Vault."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isPatternSet) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = instructionText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Interactive 3x3 Pattern Grid
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
                                    if (isSettingNewPattern) {
                                        if (setupStep == 0) {
                                            if (pattern.size < 4) {
                                                instructionText = "Connect at least 4 dots. Try again."
                                                setErrorState()
                                                postDelayed({ clearPattern() }, 600)
                                            } else {
                                                provisionalPattern = pattern
                                                setupStep = 1
                                                instructionText = "Draw pattern again to confirm"
                                                LogCatcher.log('I', "PatternLockSettings", "Provisional pattern entered, awaiting confirmation")
                                                clearPattern()
                                            }
                                        } else if (setupStep == 1) {
                                            if (pattern == provisionalPattern) {
                                                VaultSessionManager.savePattern(ctx, pattern)
                                                isPatternSet = true
                                                isSettingNewPattern = false
                                                setupStep = 0
                                                provisionalPattern = null
                                                instructionText = "Master pattern successfully saved!"
                                                Toast.makeText(ctx, "Pattern saved successfully", Toast.LENGTH_SHORT).show()
                                                clearPattern()
                                            } else {
                                                instructionText = "Patterns did not match. Start again."
                                                LogCatcher.log('W', "PatternLockSettings", "Pattern confirmation mismatch")
                                                setErrorState()
                                                postDelayed({
                                                    clearPattern()
                                                    setupStep = 0
                                                    provisionalPattern = null
                                                    instructionText = "Draw pattern to configure (connect at least 4 dots)"
                                                }, 700)
                                            }
                                        }
                                    } else {
                                        // Pattern is set, test verification
                                        val valid = VaultSessionManager.verifyPattern(ctx, pattern)
                                        if (valid) {
                                            instructionText = "Pattern verified! Session unlocked."
                                            VaultSessionManager.startSecuritySession()
                                            Toast.makeText(ctx, "Security Vault (Placeholder: Unlocked)", Toast.LENGTH_SHORT).show()
                                            postDelayed({
                                                clearPattern()
                                                instructionText = "Master unlock pattern is active"
                                            }, 800)
                                        } else {
                                            instructionText = "Incorrect pattern. Try again."
                                            setErrorState()
                                            postDelayed({
                                                clearPattern()
                                                instructionText = "Master unlock pattern is active"
                                            }, 600)
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(256.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    if (isPatternSet && !isSettingNewPattern) {
                        Button(
                            onClick = {
                                LogCatcher.log('I', "PatternLockSettings", "User initiated pattern change flow")
                                isSettingNewPattern = true
                                setupStep = 0
                                provisionalPattern = null
                                instructionText = "Draw new pattern (connect at least 4 dots)"
                            }
                        ) {
                            Text("Change Pattern")
                        }
                        OutlinedButton(
                            onClick = {
                                LogCatcher.log('I', "PatternLockSettings", "User initiated pattern removal")
                                VaultSessionManager.clearPattern(context)
                                isPatternSet = false
                                isSettingNewPattern = true
                                setupStep = 0
                                provisionalPattern = null
                                instructionText = "Draw pattern to configure (connect at least 4 dots)"
                                Toast.makeText(context, "Pattern removed", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Remove Pattern")
                        }
                    } else if (isSettingNewPattern && isPatternSet) {
                        OutlinedButton(
                            onClick = {
                                LogCatcher.log('I', "PatternLockSettings", "User cancelled pattern change")
                                isSettingNewPattern = false
                                setupStep = 0
                                provisionalPattern = null
                                instructionText = "Master unlock pattern is active"
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                PreferenceCategory(title = "Hardware & Session Timers")

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Session Lifespans (Memory-Only)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• Privacy Vault: 5-minute active window\n• Security Vault: 3-minute active window",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Timestamps reside exclusively in RAM. App suspension or process death immediately forces re-authentication.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
