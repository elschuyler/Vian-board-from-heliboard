// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.VaultDao
import helium314.keyboard.latin.database.VaultEntry
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.security.PatternGridView
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

@Composable
fun PrivacyVaultScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val vaultDao = remember { VaultDao.getInstance(context) }
    val isPatternSet = remember { VaultSessionManager.isPatternSet(context) }
    var isUnlocked by remember {
        mutableStateOf(!isPatternSet || VaultSessionManager.isPrivacySessionValid())
    }

    if (!isUnlocked) {
        // Pattern authentication challenge screen
        VaultUnlockScreen(
            onClickBack = onClickBack,
            onUnlocked = {
                VaultSessionManager.startPrivacySession()
                isUnlocked = true
                Toast.makeText(context, "Privacy Vault Unlocked", Toast.LENGTH_SHORT).show()
            }
        )
    } else {
        // Vault entries management screen
        VaultListScreen(
            onClickBack = onClickBack,
            vaultDao = vaultDao,
            isPatternSet = isPatternSet,
            onLockVault = {
                VaultSessionManager.lockPrivacy()
                isUnlocked = false
            }
        )
    }
}

@Composable
private fun VaultUnlockScreen(
    onClickBack: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Draw master pattern to unlock Privacy Vault") }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Privacy Vault",
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
                            text = "Vault Locked",
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
                        .size(300.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PatternGridView(ctx).apply {
                                onPatternCompleted = { pattern ->
                                    val valid = VaultSessionManager.verifyPattern(ctx, pattern)
                                    if (valid) {
                                        statusText = "Pattern verified!"
                                        postDelayed({
                                            clearPattern()
                                            onUnlocked()
                                        }, 300)
                                    } else {
                                        statusText = "Incorrect pattern. Try again."
                                        setErrorState()
                                        postDelayed({
                                            clearPattern()
                                        }, 600)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(280.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Pattern unlocks the Privacy Vault for 5 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VaultListScreen(
    onClickBack: () -> Unit,
    vaultDao: VaultDao,
    isPatternSet: Boolean,
    onLockVault: () -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(vaultDao.getAll()) }
    var selectedEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var revealedIds by remember { mutableStateOf(setOf<Long>()) }

    DisposableEffect(vaultDao) {
        val listener = object : VaultDao.Listener {
            override fun onVaultEntriesChanged() {
                entries = vaultDao.getAll()
            }
        }
        vaultDao.listener = listener
        onDispose {
            if (vaultDao.listener === listener) {
                vaultDao.listener = null
            }
        }
    }

    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text("Privacy Vault")
                Text(
                    text = "${entries.size} protected phrases",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        filteredItems = { term ->
            if (term.isBlank()) entries
            else entries.filter {
                it.shortcut.contains(term, ignoreCase = true) ||
                        it.phrase.contains(term, ignoreCase = true) ||
                        it.notes.contains(term, ignoreCase = true)
            }
        },
        itemContent = { entry ->
            val isRevealed = entry.id in revealedIds
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedEntry = entry }
                    .padding(vertical = 8.dp, horizontal = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Shortcut pill badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = entry.shortcut,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Masked or Revealed Phrase & Notes
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isRevealed) entry.phrase else VaultDao.maskPhrase(entry.phrase),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isRevealed) FontWeight.Medium else FontWeight.Normal,
                            fontFamily = if (isRevealed) FontFamily.Monospace else FontFamily.Default
                        )
                        if (entry.notes.isNotBlank()) {
                            Text(
                                text = entry.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Action buttons: Peek toggle & Edit
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            revealedIds = if (isRevealed) {
                                revealedIds - entry.id
                            } else {
                                revealedIds + entry.id
                            }
                        }
                    ) {
                        Text(if (isRevealed) "Hide" else "Peek")
                    }
                    IconButton(onClick = { selectedEntry = entry }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = "Edit Phrase",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )

    if (selectedEntry != null) {
        EditVaultEntryDialog(
            entry = selectedEntry!!,
            vaultDao = vaultDao,
            onDismiss = { selectedEntry = null }
        )
    }

    // Bottom FAB for adding new private phrases
    ExtendedFloatingActionButton(
        onClick = { selectedEntry = VaultEntry(0, "", "", "") },
        text = { Text("Add Phrase") },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = "Add Phrase"
            )
        },
        modifier = Modifier
            .wrapContentSize(Alignment.BottomEnd)
            .padding(all = 16.dp)
            .safeDrawingPadding()
    )
}

@Composable
private fun EditVaultEntryDialog(
    entry: VaultEntry,
    vaultDao: VaultDao,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var shortcut by remember { mutableStateOf(entry.shortcut) }
    var phrase by remember { mutableStateOf(entry.phrase) }
    var notes by remember { mutableStateOf(entry.notes) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    val isNew = entry.id == 0L
    val isValid = shortcut.isNotBlank() && phrase.isNotBlank()

    fun save() {
        if (isValid) {
            vaultDao.addOrUpdate(shortcut, phrase, notes)
            Toast.makeText(context, if (isNew) "Phrase added" else "Phrase updated", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    ThreeButtonAlertDialog(
        onDismissRequest = onDismiss,
        onConfirmed = { save() },
        checkOk = { isValid },
        confirmButtonText = stringResource(R.string.save),
        neutralButtonText = if (!isNew) stringResource(R.string.delete) else null,
        onNeutral = {
            if (!isNew) {
                vaultDao.delete(entry.id)
                Toast.makeText(context, "Phrase deleted", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        },
        title = {
            Text(if (isNew) "Add Private Phrase" else "Edit Private Phrase")
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = shortcut,
                    onValueChange = { shortcut = it },
                    label = { Text("Shortcut / Trigger (e.g. eml, pin)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
                )

                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("Private Phrase / Secret") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Text(if (isPasswordVisible) "Hide" else "Show")
                        }
                    }
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes / Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "🔒 Strictly isolated inside HeliBoard sandbox. Never shared with Android system or other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
