// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.security.vault.crypto.VaultCryptoManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.data.VaultEntryEntity
import helium314.keyboard.security.vault.data.VaultGroupEntity
import helium314.keyboard.security.vault.engine.KdbxRepository
import helium314.keyboard.security.vault.engine.KdbxSyncManager
import helium314.keyboard.security.vault.generator.PasswordGenerator
import helium314.keyboard.security.vault.totp.TotpGenerator
import helium314.keyboard.settings.SearchSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * KeePassDX-style Security Vault Settings Screen.
 *
 * Implements M3 OutlinedCard containers with 16dp radii:
 * Card 1: Active Database Overview & Status Badges (KDBX 4.1, Argon2id, AES-256-GCM, Key File Bound).
 * Card 2: 2-Way Sync Engine & Visual Diff Sheet.
 * Card 3: Security & Session Rules (timeout, stealth gatekeeper, auto-fill pills).
 * Card 4: Import / Export / Backup Actions.
 *
 * Fully supports composite master credentials (Password + Key File .key/.keyx)
 * and preserves 100% of custom entry fields without metadata loss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityVaultScreen(
    onClickBrowseEntries: () -> Unit = {},
    onClickBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { SecurityVaultDao.getInstance(context) }

    var groups by remember { mutableStateOf<List<VaultGroupEntity>>(emptyList()) }
    var entries by remember { mutableStateOf<List<VaultEntryEntity>>(emptyList()) }

    var kdbxUriString by remember { mutableStateOf(context.prefs().getString(KdbxSyncManager.PREF_KDBX_URI, null)) }
    var kdbxFileName by remember { mutableStateOf(context.prefs().getString(KdbxSyncManager.PREF_KDBX_FILENAME, null)) }
    var keyFileUriString by remember { mutableStateOf(context.prefs().getString(KdbxSyncManager.PREF_KEYFILE_URI, null)) }
    var keyFileName by remember { mutableStateOf(context.prefs().getString(KdbxSyncManager.PREF_KEYFILE_FILENAME, null)) }
    var lastSyncTime by remember { mutableStateOf(context.prefs().getLong(KdbxSyncManager.PREF_LAST_SYNC_TIME, 0L)) }

    // Dialog & Sheet States
    var showPasswordDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingKeyFileUri by remember { mutableStateOf<Uri?>(null) }
    var masterPasswordInput by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    var showSyncDiffSheet by remember { mutableStateOf(false) }

    // Session Rules Preferences
    var autofillPillsEnabled by remember {
        mutableStateOf(context.prefs().getBoolean("pref_vault_autofill_pills_enabled", true))
    }
    var stealthGatekeeperEnabled by remember {
        mutableStateOf(context.prefs().getBoolean("pref_vault_require_gatekeeper", false))
    }

    // Live TOTP Tick update
    var currentTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            currentTickMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    fun reloadData() {
        groups = dao.getAllGroups()
        entries = dao.getAllEntries()
    }

    LaunchedEffect(Unit) {
        reloadData()
    }

    // SAF Picker for KDBX file
    val kdbxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (t: Throwable) {
                LogCatcher.log('W', "SecurityVaultScreen", "Persistable permission warning: ${t.message}")
            }
            pendingImportUri = uri
            pendingKeyFileUri = if (!keyFileUriString.isNullOrBlank()) Uri.parse(keyFileUriString) else null
            showPasswordDialog = true
        }
    }

    // SAF Picker for Key File (.key / .keyx)
    val keyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (t: Throwable) {
                LogCatcher.log('W', "SecurityVaultScreen", "KeyFile persistable permission warning: ${t.message}")
            }
            pendingKeyFileUri = uri
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Security Vault",
        settings = emptyList()
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // ==========================================
                    // CARD 1: Active Database Overview & Badges
                    // ==========================================
                    item {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = kdbxFileName ?: "No Database Attached",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (entries.isNotEmpty()) {
                                                "${entries.size} entries across ${groups.size} folders"
                                            } else {
                                                "Local KeePassDX sandbox is empty"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (kdbxUriString != null) {
                                        Button(
                                            onClick = {
                                                val uri = Uri.parse(kdbxUriString)
                                                pendingImportUri = uri
                                                showSyncDiffSheet = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            Text("Sync")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Technical Badges Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    StatusBadge(text = "KDBX 4.1", color = MaterialTheme.colorScheme.primary)
                                    StatusBadge(text = "Argon2id", color = MaterialTheme.colorScheme.secondary)
                                    StatusBadge(text = "AES-256", color = MaterialTheme.colorScheme.tertiary)
                                    if (!keyFileUriString.isNullOrBlank()) {
                                        StatusBadge(text = "Key File Bound", color = Color(0xFF2E7D32))
                                    }
                                }

                                if (lastSyncTime > 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                    Text(
                                        text = "Last synchronized: ${sdf.format(Date(lastSyncTime))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // ==========================================
                    // CARD 2: 2-Way Sync Engine & Visual Diff
                    // ==========================================
                    item {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "2-Way Sync Engine",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Inspect and reconcile differences between your device sandbox and the external .kdbx file before committing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            if (kdbxUriString != null) {
                                                pendingImportUri = Uri.parse(kdbxUriString)
                                                showSyncDiffSheet = true
                                            } else {
                                                Toast.makeText(context, "Please import a .kdbx file first", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Visual Diff Sheet")
                                    }
                                    Button(
                                        onClick = {
                                            if (kdbxUriString != null) {
                                                pendingImportUri = Uri.parse(kdbxUriString)
                                                showSyncDiffSheet = true
                                            } else {
                                                kdbxPickerLauncher.launch(arrayOf("*/*"))
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Synchronize")
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // CARD 3: Security & Session Rules
                    // ==========================================
                    item {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Security & Session Rules",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Configure memory timeout, biometric prompt gatekeepers, and keyboard inline autofill suggestion pills.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Rule 1: Keyboard Autofill Pills
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Keyboard Suggestion Strip Pills",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Show matched login & TOTP pills directly above the keyboard keys for 1-tap typing.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = autofillPillsEnabled,
                                        onCheckedChange = { checked ->
                                            autofillPillsEnabled = checked
                                            context.prefs().edit {
                                                putBoolean("pref_vault_autofill_pills_enabled", checked)
                                            }
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Rule 2: Stealth Gatekeeper Protection
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Stealth Gatekeeper Auth",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Require biometric or stealth pattern verification before opening vault settings or suggestion pills.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = stealthGatekeeperEnabled,
                                        onCheckedChange = { checked ->
                                            stealthGatekeeperEnabled = checked
                                            context.prefs().edit {
                                                putBoolean("pref_vault_require_gatekeeper", checked)
                                            }
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Session timeout badge info
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_lock),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "In-memory session timeout: ${VaultSessionManager.getSecurityTimeoutMs(context) / 60000} minutes idle auto-lock",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // ==========================================
                    // CARD 4: Import / Export / Backup Actions
                    // ==========================================
                    item {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Database Management & Key File",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Import KeePass databases, bind cryptographic key files (.key / .keyx), or clear local cache.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { kdbxPickerLauncher.launch(arrayOf("*/*")) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Open .kdbx")
                                    }

                                    OutlinedButton(
                                        onClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (keyFileUriString != null) "Change .key" else "Bind .key")
                                    }
                                }

                                if (keyFileName != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Key File: $keyFileName",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF2E7D32)
                                        )
                                        TextButton(
                                            onClick = {
                                                context.prefs().edit {
                                                    remove(KdbxSyncManager.PREF_KEYFILE_URI)
                                                    remove(KdbxSyncManager.PREF_KEYFILE_FILENAME)
                                                }
                                                keyFileUriString = null
                                                keyFileName = null
                                                pendingKeyFileUri = null
                                                Toast.makeText(context, "Key file unlinked", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text("Unlink", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Card 5: Dedicated Vault Entries Access
                    item {
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onClickBrowseEntries() },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🔑", fontSize = 18.sp)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "KDBX Vault Entries",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${entries.size} entries in ${groups.size} groups • Tap to browse & edit",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = "→",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    // --- Master Password Dialog with Optional Key File Binding ---
    if (showPasswordDialog && pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isProcessing) {
                    showPasswordDialog = false
                    pendingImportUri = null
                    masterPasswordInput = ""
                }
            },
            title = { Text("Unlock Database") },
            text = {
                Column {
                    Text("Enter master password for this KeePass file to import and seal it in your device hardware.")
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = masterPasswordInput,
                        onValueChange = { masterPasswordInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Master Password") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Text(if (isPasswordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Key File Selection Button inside Import Dialog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Key File (.key / .keyx)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = pendingKeyFileUri?.lastPathSegment ?: keyFileName ?: "None attached (optional)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pendingKeyFileUri != null || keyFileName != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(if (pendingKeyFileUri != null) "Change" else "Select")
                        }
                    }

                    if (isProcessing) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Decrypting & indexing KDBX...")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingImportUri ?: return@Button
                        val pwdChars = masterPasswordInput.toCharArray()
                        val activeKeyUri = pendingKeyFileUri
                        isProcessing = true
                        scope.launch(Dispatchers.IO) {
                            val result = KdbxRepository.importFromUri(context, uri, pwdChars, activeKeyUri)
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                if (result.success) {
                                    val name = uri.lastPathSegment ?: "vault.kdbx"
                                    context.prefs().edit {
                                        putString(KdbxSyncManager.PREF_KDBX_URI, uri.toString())
                                        putString(KdbxSyncManager.PREF_KDBX_FILENAME, name)
                                        if (activeKeyUri != null) {
                                            putString(KdbxSyncManager.PREF_KEYFILE_URI, activeKeyUri.toString())
                                            putString(KdbxSyncManager.PREF_KEYFILE_FILENAME, activeKeyUri.lastPathSegment ?: "keyfile.key")
                                        }
                                        putLong(KdbxSyncManager.PREF_LAST_SYNC_TIME, System.currentTimeMillis())
                                    }
                                    kdbxUriString = uri.toString()
                                    kdbxFileName = name
                                    if (activeKeyUri != null) {
                                        keyFileUriString = activeKeyUri.toString()
                                        keyFileName = activeKeyUri.lastPathSegment ?: "keyfile.key"
                                    }
                                    lastSyncTime = System.currentTimeMillis()
                                    reloadData()
                                    showPasswordDialog = false
                                    pendingImportUri = null
                                    masterPasswordInput = ""
                                    Toast.makeText(context, "Loaded ${result.entryCount} entries", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, result.errorMessage ?: "Decryption failed", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = masterPasswordInput.isNotBlank() && !isProcessing
                ) {
                    Text("Unlock & Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        pendingImportUri = null
                        masterPasswordInput = ""
                    },
                    enabled = !isProcessing
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Visual Diff Bottom Sheet for 2-Way Sync ---
    if (showSyncDiffSheet && pendingImportUri != null) {
        VisualDiffBottomSheet(
            uri = pendingImportUri!!,
            keyFileUri = if (!keyFileUriString.isNullOrBlank()) Uri.parse(keyFileUriString) else pendingKeyFileUri,
            onDismiss = { showSyncDiffSheet = false },
            onSyncCommitted = {
                lastSyncTime = System.currentTimeMillis()
                reloadData()
                showSyncDiffSheet = false
            }
        )
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisualDiffBottomSheet(
    uri: Uri,
    keyFileUri: Uri? = null,
    onDismiss: () -> Unit,
    onSyncCommitted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var masterPassword by remember { mutableStateOf("") }
    var isDiffComputed by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var diffResult by remember { mutableStateOf<KdbxSyncManager.SyncDiffResult?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "2-Way Sync & Visual Diff",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (keyFileUri != null) {
                Text(
                    text = "Bound Key File: ${keyFileUri.lastPathSegment ?: "keyfile.key"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold
                )
            }

            if (!isDiffComputed) {
                Text("Enter master password to compare local sandbox with external file.")
                OutlinedTextField(
                    value = masterPassword,
                    onValueChange = { masterPassword = it },
                    label = { Text("Master Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val pwd = masterPassword.toCharArray()
                        scope.launch(Dispatchers.IO) {
                            val res = KdbxSyncManager.computeDiff(context, uri, pwd, keyFileUri)
                            withContext(Dispatchers.Main) {
                                diffResult = res
                                isDiffComputed = true
                            }
                        }
                    },
                    enabled = masterPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Inspect Differences")
                }
            } else {
                val res = diffResult
                if (res != null) {
                    if (res.errorMessage != null) {
                        Text("Error: ${res.errorMessage}", color = MaterialTheme.colorScheme.error)
                    } else if (!res.hasChanges) {
                        Text("Everything is in sync! Zero differences found.", fontWeight = FontWeight.Bold)
                    } else {
                        Text(
                            text = "Differences: +${res.addedCount} Added, ~${res.modifiedCount} Modified, -${res.deletedCount} Deleted",
                            fontWeight = FontWeight.Bold
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        ) {
                            items(res.diffs) { diff ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val (badge, color) = when (diff.type) {
                                        KdbxSyncManager.DiffType.ADDED -> "+ Added" to Color(0xFF2E7D32)
                                        KdbxSyncManager.DiffType.MODIFIED -> "~ Modified" to Color(0xFFEF6C00)
                                        KdbxSyncManager.DiffType.DELETED -> "- Deleted" to Color(0xFFC62828)
                                        KdbxSyncManager.DiffType.CONFLICT -> "⚠️ Conflict" to Color(0xFFAD1457)
                                    }
                                    Text(
                                        text = badge,
                                        color = color,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(90.dp)
                                    )
                                    Text(
                                        text = diff.title,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                val pwd = masterPassword.toCharArray()
                                isSyncing = true
                                scope.launch(Dispatchers.IO) {
                                    val success = KdbxSyncManager.executeSync(context, uri, pwd, keyFileUri)
                                    withContext(Dispatchers.Main) {
                                        isSyncing = false
                                        if (success) {
                                            Toast.makeText(context, "Sync committed successfully", Toast.LENGTH_SHORT).show()
                                            onSyncCommitted()
                                        } else {
                                            Toast.makeText(context, "Sync failed to write to file", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            enabled = !isSyncing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isSyncing) "Syncing..." else "Confirm & Sync to File")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
