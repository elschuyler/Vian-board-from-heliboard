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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
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
    onClickBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { SecurityVaultDao.getInstance(context) }

    var groups by remember { mutableStateOf<List<VaultGroupEntity>>(emptyList()) }
    var entries by remember { mutableStateOf<List<VaultEntryEntity>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedGroupUuids by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    var showEntryEditor by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<VaultEntryEntity?>(null) }

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
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            floatingActionButton = {
                if (groups.isNotEmpty() || entries.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            editingEntry = null
                            showEntryEditor = true
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_plus), contentDescription = "Add Entry")
                    }
                }
            }
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
                                        text = "In-memory session timeout: 3 minutes idle auto-lock",
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

                    // Search & Filter Bar
                    if (entries.isNotEmpty()) {
                        item {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                placeholder = { Text("Search vault entries...") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    // Folders & Entries Tree List
                    val filteredEntries = if (searchQuery.isBlank()) {
                        entries
                    } else {
                        entries.filter {
                            it.title.contains(searchQuery, ignoreCase = true) ||
                                    (it.username?.contains(searchQuery, ignoreCase = true) == true) ||
                                    (it.urlOrPackage?.contains(searchQuery, ignoreCase = true) == true) ||
                                    (it.customFieldsJson?.contains(searchQuery, ignoreCase = true) == true)
                        }
                    }

                    if (searchQuery.isNotBlank()) {
                        items(filteredEntries, key = { it.entryUuid }) { entry ->
                            VaultEntryCard(
                                entry = entry,
                                currentTickMs = currentTickMs,
                                onClick = {
                                    editingEntry = entry
                                    showEntryEditor = true
                                }
                            )
                        }
                    } else if (groups.isNotEmpty() || entries.isNotEmpty()) {
                        val rootGroups = groups.filter { it.parentGroupUuid == null }
                        val ungroupedEntries = entries.filter { e -> groups.none { it.groupUuid == e.groupUuid } }

                        items(rootGroups, key = { it.groupUuid }) { group ->
                            VaultGroupAccordionItem(
                                group = group,
                                allGroups = groups,
                                allEntries = entries,
                                expandedGroupUuids = expandedGroupUuids,
                                currentTickMs = currentTickMs,
                                onToggleExpand = { uuid ->
                                    expandedGroupUuids = if (expandedGroupUuids.contains(uuid)) {
                                        expandedGroupUuids - uuid
                                    } else {
                                        expandedGroupUuids + uuid
                                    }
                                },
                                onEntryClick = { entry ->
                                    editingEntry = entry
                                    showEntryEditor = true
                                }
                            )
                        }

                        if (ungroupedEntries.isNotEmpty()) {
                            item {
                                Text(
                                    text = "General",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(ungroupedEntries, key = { it.entryUuid }) { entry ->
                                VaultEntryCard(
                                    entry = entry,
                                    currentTickMs = currentTickMs,
                                    onClick = {
                                        editingEntry = entry
                                        showEntryEditor = true
                                    }
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(72.dp))
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

    // --- Entry Editor Dialog with Custom Fields Support ---
    if (showEntryEditor) {
        EntryEditorDialog(
            entry = editingEntry,
            groups = groups,
            onDismiss = { showEntryEditor = false },
            onSave = { updatedEntry ->
                dao.insertOrUpdateEntry(updatedEntry)
                reloadData()
                showEntryEditor = false
            },
            onDelete = { entryToDelete ->
                dao.deleteEntry(entryToDelete.entryUuid)
                reloadData()
                showEntryEditor = false
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

@Composable
private fun VaultGroupAccordionItem(
    group: VaultGroupEntity,
    allGroups: List<VaultGroupEntity>,
    allEntries: List<VaultEntryEntity>,
    expandedGroupUuids: Set<String>,
    currentTickMs: Long,
    onToggleExpand: (String) -> Unit,
    onEntryClick: (VaultEntryEntity) -> Unit
) {
    val isExpanded = expandedGroupUuids.contains(group.groupUuid)
    val groupEntries = allEntries.filter { it.groupUuid == group.groupUuid }
    val childGroups = allGroups.filter { it.parentGroupUuid == group.groupUuid }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggleExpand(group.groupUuid) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isExpanded) "▼" else "▶",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(20.dp)
            )
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${groupEntries.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp)
            ) {
                for (child in childGroups) {
                    VaultGroupAccordionItem(
                        group = child,
                        allGroups = allGroups,
                        allEntries = allEntries,
                        expandedGroupUuids = expandedGroupUuids,
                        currentTickMs = currentTickMs,
                        onToggleExpand = onToggleExpand,
                        onEntryClick = onEntryClick
                    )
                }
                for (entry in groupEntries) {
                    VaultEntryCard(
                        entry = entry,
                        currentTickMs = currentTickMs,
                        onClick = { onEntryClick(entry) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun VaultEntryCard(
    entry: VaultEntryEntity,
    currentTickMs: Long,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔑", fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = entry.username ?: entry.urlOrPackage ?: "No username",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.totpSecretEncrypted != null) {
                    val totpSecret = remember(entry.totpSecretEncrypted) {
                        VaultCryptoManager.decryptToString(entry.totpSecretEncrypted)
                    }
                    if (!totpSecret.isNullOrEmpty()) {
                        val totpRes = TotpGenerator.generateFromSecret(totpSecret, currentTickMs)
                        if (totpRes != null) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = totpRes.code,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${totpRes.remainingSeconds}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Display custom field pill badges if present
            if (!entry.customFieldsJson.isNullOrBlank()) {
                val customMap = remember(entry.customFieldsJson) {
                    try {
                        val json = Json.parseToJsonElement(entry.customFieldsJson) as? JsonObject
                        json?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
                    } catch (_: Throwable) {
                        emptyMap()
                    }
                }

                if (customMap.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for ((k, v) in customMap.entries.take(3)) {
                            Box(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$k: $v",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (customMap.size > 3) {
                            Text(
                                text = "+${customMap.size - 3} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryEditorDialog(
    entry: VaultEntryEntity?,
    groups: List<VaultGroupEntity>,
    onDismiss: () -> Unit,
    onSave: (VaultEntryEntity) -> Unit,
    onDelete: (VaultEntryEntity) -> Unit
) {
    val isEditing = entry != null
    var title by remember { mutableStateOf(entry?.title ?: "") }
    var username by remember { mutableStateOf(entry?.username ?: "") }
    var password by remember {
        mutableStateOf(VaultCryptoManager.decryptToString(entry?.passwordEncrypted) ?: "")
    }
    var isPassVisible by remember { mutableStateOf(false) }
    var urlOrPackage by remember { mutableStateOf(entry?.urlOrPackage ?: "") }
    var totpSecret by remember {
        mutableStateOf(VaultCryptoManager.decryptToString(entry?.totpSecretEncrypted) ?: "")
    }
    var notes by remember {
        mutableStateOf(VaultCryptoManager.decryptToString(entry?.notesEncrypted) ?: "")
    }
    var selectedGroupUuid by remember {
        mutableStateOf(entry?.groupUuid ?: groups.firstOrNull()?.groupUuid ?: "default_group")
    }

    // Custom Fields Map (parsed from customFieldsJson)
    var customFieldsList by remember {
        val initialMap = try {
            if (!entry?.customFieldsJson.isNullOrBlank()) {
                val json = Json.parseToJsonElement(entry?.customFieldsJson!!) as? JsonObject
                json?.map { (k, v) -> Pair(k, (v as? JsonPrimitive)?.content ?: "") }?.toMutableList()
            } else null
        } catch (_: Throwable) {
            null
        }
        mutableStateOf(initialMap ?: mutableListOf<Pair<String, String>>())
    }

    var newCustomKey by remember { mutableStateOf("") }
    var newCustomVal by remember { mutableStateOf("") }
    var isAddingCustomField by remember { mutableStateOf(false) }

    // Inline Generator Configuration States
    var isGeneratorExpanded by remember { mutableStateOf(false) }
    var genLength by remember { mutableFloatStateOf(16f) }
    var genUppercase by remember { mutableStateOf(true) }
    var genLowercase by remember { mutableStateOf(true) }
    var genDigits by remember { mutableStateOf(true) }
    var genSymbols by remember { mutableStateOf(true) }
    var genExcludeLookalikes by remember { mutableStateOf(false) }
    var genPassphraseMode by remember { mutableStateOf(false) }
    var genWordCount by remember { mutableIntStateOf(4) }

    fun generateNewPassword() {
        val config = PasswordGenerator.GeneratorConfig(
            length = genLength.toInt(),
            includeUppercase = genUppercase,
            includeLowercase = genLowercase,
            includeDigits = genDigits,
            includeSymbols = genSymbols,
            excludeLookalike = genExcludeLookalikes,
            isPassphraseMode = genPassphraseMode,
            passphraseWordCount = genWordCount
        )
        password = PasswordGenerator.generate(config)
        isPassVisible = true
    }

    val passwordStrength = remember(password) {
        PasswordGenerator.estimateStrength(password)
    }

    // Live TOTP validation preview
    val totpPreview = remember(totpSecret) {
        if (totpSecret.isNotBlank()) TotpGenerator.generateFromSecret(totpSecret) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Entry" else "New Entry") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Password Input Field with Visibility & Direct Generate Icon
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (isPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { isPassVisible = !isPassVisible }) {
                                Text(if (isPassVisible) "👁" else "🔒", fontSize = 14.sp)
                            }
                            IconButton(onClick = { generateNewPassword() }) {
                                Text("🎲", fontSize = 14.sp)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Password Strength Indicator
                if (password.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Strength: ${passwordStrength.rating.label}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${passwordStrength.entropyBits.toInt()} bits",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val barColor = when (passwordStrength.rating) {
                            PasswordGenerator.StrengthAssessment.Rating.WEAK -> Color(0xFFE53935)
                            PasswordGenerator.StrengthAssessment.Rating.FAIR -> Color(0xFFFB8C00)
                            PasswordGenerator.StrengthAssessment.Rating.STRONG -> Color(0xFF43A047)
                            PasswordGenerator.StrengthAssessment.Rating.UNBREAKABLE -> Color(0xFF1E88E5)
                        }
                        LinearProgressIndicator(
                            progress = { passwordStrength.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = barColor
                        )
                    }
                }

                // Password Generator Accordion Expand/Collapse
                TextButton(
                    onClick = { isGeneratorExpanded = !isGeneratorExpanded },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(if (isGeneratorExpanded) "Hide Generator Options ▲" else "Configure Generator ▼")
                }

                AnimatedVisibility(visible = isGeneratorExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Length: ${genLength.toInt()}", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = genLength,
                                onValueChange = { genLength = it },
                                valueRange = 8f..32f,
                                steps = 23,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            FilterChipCompact("A-Z", genUppercase) { genUppercase = !genUppercase }
                            FilterChipCompact("a-z", genLowercase) { genLowercase = !genLowercase }
                            FilterChipCompact("0-9", genDigits) { genDigits = !genDigits }
                            FilterChipCompact("#$%", genSymbols) { genSymbols = !genSymbols }
                        }

                        Button(
                            onClick = { generateNewPassword() },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Generate Password")
                        }
                    }
                }

                OutlinedTextField(
                    value = urlOrPackage,
                    onValueChange = { urlOrPackage = it },
                    label = { Text("URL / Package (Autofill Match)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = totpSecret,
                    onValueChange = { totpSecret = it },
                    label = { Text("TOTP Secret (Base32 or URI)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (totpPreview != null) {
                    Text(
                        text = "Live Token: ${totpPreview.code} (${totpPreview.remainingSeconds}s remaining)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                // ==========================================
                // Custom String Fields Section (Zero Data Loss)
                // ==========================================
                Text(
                    text = "Custom Fields (${customFieldsList.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )

                for ((index, pair) in customFieldsList.withIndex()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${pair.first}:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(
                            text = pair.second,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val updated = customFieldsList.toMutableList()
                                updated.removeAt(index)
                                customFieldsList = updated
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (isAddingCustomField) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                            value = newCustomKey,
                            onValueChange = { newCustomKey = it },
                            label = { Text("Field Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newCustomVal,
                            onValueChange = { newCustomVal = it },
                            label = { Text("Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { isAddingCustomField = false }) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (newCustomKey.isNotBlank()) {
                                    val updated = customFieldsList.toMutableList()
                                    updated.add(Pair(newCustomKey.trim(), newCustomVal.trim()))
                                    customFieldsList = updated
                                    newCustomKey = ""
                                    newCustomVal = ""
                                    isAddingCustomField = false
                                }
                            },
                            enabled = newCustomKey.isNotBlank()
                        ) {
                            Text("Add")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { isAddingCustomField = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Add Custom Field")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val passEncrypted = if (password.isNotBlank()) VaultCryptoManager.encryptString(password) else null
                    val totpEncrypted = if (totpSecret.isNotBlank()) VaultCryptoManager.encryptString(totpSecret) else null
                    val notesEncrypted = if (notes.isNotBlank()) VaultCryptoManager.encryptString(notes) else null

                    val customJson = if (customFieldsList.isNotEmpty()) {
                        val jsonObject = buildJsonObject {
                            for ((k, v) in customFieldsList) {
                                put(k, JsonPrimitive(v))
                            }
                        }
                        jsonObject.toString()
                    } else null

                    val updated = VaultEntryEntity(
                        entryUuid = entry?.entryUuid ?: UUID.randomUUID().toString(),
                        groupUuid = selectedGroupUuid,
                        title = title.ifBlank { "Untitled" },
                        username = username.ifBlank { null },
                        passwordEncrypted = passEncrypted,
                        urlOrPackage = urlOrPackage.ifBlank { null },
                        totpSecretEncrypted = totpEncrypted,
                        notesEncrypted = notesEncrypted,
                        customFieldsJson = customJson,
                        updatedAt = System.currentTimeMillis()
                    )
                    onSave(updated)
                },
                enabled = title.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (isEditing) {
                    TextButton(
                        onClick = { onDelete(entry!!) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun FilterChipCompact(label: String, selected: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
