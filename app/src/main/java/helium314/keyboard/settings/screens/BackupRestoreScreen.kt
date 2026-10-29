// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.backup.BackupArchiveInfo
import helium314.keyboard.settings.backup.BackupModule
import helium314.keyboard.settings.backup.ModularBackupEngine
import helium314.keyboard.settings.backup.ModularExportDialog
import helium314.keyboard.settings.backup.ModularRestoreDialog
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.preferences.Preference
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.engine.KdbxRepository
import helium314.keyboard.settings.preferences.PreferenceCategory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun BackupRestoreScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }
    var showHeliBoardConfirm by rememberSaveable { mutableStateOf(false) }

    var selectedExportModules by remember {
        mutableStateOf(BackupModule.entries.toSet())
    }
    var selectedRestoreModules by remember {
        mutableStateOf(emptySet<BackupModule>())
    }
    var pendingArchiveInfo by remember {
        mutableStateOf<BackupArchiveInfo?>(null)
    }
    var pendingRestoreUri by remember {
        mutableStateOf<Uri?>(null)
    }

    var showKdbxExportPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showKdbxImportPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var pendingKdbxExportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingKdbxImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingKdbxKeyFileUri by remember { mutableStateOf<Uri?>(null) }
    var kdbxMasterPasswordInput by remember { mutableStateOf("") }
    var kdbxConfirmPasswordInput by remember { mutableStateOf("") }
    var isKdbxPasswordVisible by remember { mutableStateOf(false) }
    var isKdbxProcessing by remember { mutableStateOf(false) }
    var kdbxDialogError by remember { mutableStateOf<String?>(null) }

    val backupLauncher = filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                ModularBackupEngine.createBackup(ctx, uri, selectedExportModules)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, ctx.getString(R.string.backup_restored).replace("restored", "created"), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                LogCatcher.e("BackupRestoreScreen", "Backup failed: ${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    error = "b" + (t.message ?: "Backup failed")
                }
            }
        }
    }

    val restorePickerLauncher = filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                val info = ModularBackupEngine.inspectArchive(ctx, uri)
                Handler(Looper.getMainLooper()).post {
                    pendingArchiveInfo = info
                    pendingRestoreUri = uri
                    selectedRestoreModules = info.availableModules
                    showRestoreDialog = true
                }
            } catch (t: Throwable) {
                LogCatcher.e("BackupRestoreScreen", "Failed inspecting archive: ${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    error = "r" + (t.message ?: "Invalid backup archive")
                }
            }
        }
    }

    val kdbxExportLauncher = filePicker { uri ->
        pendingKdbxExportUri = uri
        kdbxMasterPasswordInput = ""
        kdbxConfirmPasswordInput = ""
        kdbxDialogError = null
        showKdbxExportPasswordDialog = true
    }

    val kdbxImportLauncher = filePicker { uri ->
        pendingKdbxImportUri = uri
        pendingKdbxKeyFileUri = null
        kdbxMasterPasswordInput = ""
        kdbxDialogError = null
        showKdbxImportPasswordDialog = true
    }

    val kdbxKeyFileLauncher = filePicker { uri ->
        pendingKdbxKeyFileUri = uri
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.backup_restore_title),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                PreferenceCategory(title = "Keyboard Data Archive (.zip)")
                Preference(
                    name = stringResource(R.string.button_backup),
                    description = "Modular export of settings, custom layouts, dictionaries, and clipboard",
                    icon = R.drawable.ic_settings_advanced,
                    onClick = { showExportDialog = true }
                )
                Preference(
                    name = stringResource(R.string.button_restore),
                    description = "Granular restore from backup archive (.zip excludes vault credentials, use .kdbx)",
                    icon = R.drawable.ic_settings_advanced,
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip")
                        restorePickerLauncher.launch(intent)
                    }
                )
                Preference(
                    name = "Import HeliBoard Backup",
                    description = "Seamlessly import configurations and user data from legacy HeliBoard backups",
                    icon = R.drawable.ic_settings_about,
                    onClick = { showHeliBoardConfirm = true }
                )

                PreferenceCategory(title = "Security Vault (KeePass .kdbx)")
                Preference(
                    name = "Export Security Vault (.kdbx)",
                    description = "Export credentials into an industry-standard portable KeePass database (.kdbx)",
                    icon = R.drawable.ic_vpn_key,
                    onClick = {
                        val dao = SecurityVaultDao.getInstance(ctx)
                        if (dao.getAllGroups().isEmpty()) {
                            Toast.makeText(ctx, "Security Vault has no groups or entries to export", Toast.LENGTH_LONG).show()
                        } else {
                            val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .putExtra(Intent.EXTRA_TITLE, "VianBoard_vault_$currentDate.kdbx")
                                .setType("application/octet-stream")
                            kdbxExportLauncher.launch(intent)
                        }
                    }
                )
                Preference(
                    name = "Import Security Vault (.kdbx)",
                    description = "Import an external KeePass database (.kdbx) with master password and seal in hardware",
                    icon = R.drawable.ic_settings_security,
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                        kdbxImportLauncher.launch(intent)
                    }
                )
            }
        }
    }

    if (showHeliBoardConfirm) {
        ConfirmationDialog(
            onDismissRequest = { showHeliBoardConfirm = false },
            title = { Text("Import HeliBoard Backup") },
            content = { Text("Select a HeliBoard backup (.zip). You will be able to selectively choose which components to restore.") },
            confirmButtonText = "Select File",
            onConfirmed = {
                showHeliBoardConfirm = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                restorePickerLauncher.launch(intent)
            }
        )
    }

    if (showExportDialog) {
        ModularExportDialog(
            selectedModules = selectedExportModules,
            onModulesChanged = { selectedExportModules = it },
            onDismiss = { showExportDialog = false },
            onConfirmed = {
                showExportDialog = false
                val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        "VianBoard_backup_$currentDate.zip"
                    )
                    .setType("application/zip")
                backupLauncher.launch(intent)
            }
        )
    }

    val currentArchiveInfo = pendingArchiveInfo
    val currentRestoreUri = pendingRestoreUri
    if (showRestoreDialog && currentArchiveInfo != null && currentRestoreUri != null) {
        ModularRestoreDialog(
            archiveInfo = currentArchiveInfo,
            selectedModules = selectedRestoreModules,
            onModulesChanged = { selectedRestoreModules = it },
            onDismiss = {
                showRestoreDialog = false
                pendingArchiveInfo = null
                pendingRestoreUri = null
            },
            onConfirmed = {
                val uri = currentRestoreUri
                val modulesToRestore = selectedRestoreModules
                showRestoreDialog = false
                pendingArchiveInfo = null
                pendingRestoreUri = null

                ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
                    try {
                        ModularBackupEngine.restoreArchive(ctx, uri, modulesToRestore)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
                            (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                        }
                    } catch (t: Throwable) {
                        LogCatcher.e("BackupRestoreScreen", "Restore failed: ${t.message}", t)
                        Handler(Looper.getMainLooper()).post {
                            error = "r" + (t.message ?: "Failed to restore backup")
                        }
                    }
                }
            }
        )
    }

    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }

    if (showKdbxExportPasswordDialog && pendingKdbxExportUri != null) {
        val exportUri = pendingKdbxExportUri!!
        AlertDialog(
            onDismissRequest = {
                if (!isKdbxProcessing) {
                    showKdbxExportPasswordDialog = false
                    pendingKdbxExportUri = null
                    kdbxMasterPasswordInput = ""
                    kdbxConfirmPasswordInput = ""
                    kdbxDialogError = null
                }
            },
            title = { Text("Export Security Vault (.kdbx)") },
            text = {
                Column {
                    Text(
                        "Set a master password to encrypt your portable KeePass database. You will use this password to unlock this file in KeePassXC or VianBoard.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = kdbxMasterPasswordInput,
                        onValueChange = {
                            kdbxMasterPasswordInput = it
                            kdbxDialogError = null
                        },
                        label = { Text("Master Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isKdbxPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        trailingIcon = {
                            IconButton(onClick = { isKdbxPasswordVisible = !isKdbxPasswordVisible }) {
                                Text(if (isKdbxPasswordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = kdbxConfirmPasswordInput,
                        onValueChange = {
                            kdbxConfirmPasswordInput = it
                            kdbxDialogError = null
                        },
                        label = { Text("Confirm Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isKdbxPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                    )
                    if (kdbxDialogError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(kdbxDialogError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (kdbxMasterPasswordInput != kdbxConfirmPasswordInput) {
                            kdbxDialogError = "Passwords do not match"
                            return@Button
                        }
                        if (kdbxMasterPasswordInput.isEmpty()) {
                            kdbxDialogError = "Password cannot be empty"
                            return@Button
                        }
                        isKdbxProcessing = true
                        val pwdChars = kdbxMasterPasswordInput.toCharArray()
                        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
                            try {
                                val success = KdbxRepository.exportToUri(ctx, exportUri, pwdChars)
                                Handler(Looper.getMainLooper()).post {
                                    isKdbxProcessing = false
                                    if (success) {
                                        showKdbxExportPasswordDialog = false
                                        pendingKdbxExportUri = null
                                        kdbxMasterPasswordInput = ""
                                        kdbxConfirmPasswordInput = ""
                                        Toast.makeText(ctx, "Security Vault exported to KeePass (.kdbx) successfully", Toast.LENGTH_LONG).show()
                                    } else {
                                        kdbxDialogError = "Failed to export KeePass database"
                                    }
                                }
                            } catch (t: Throwable) {
                                LogCatcher.e("BackupRestoreScreen", "KDBX export failed: ${t.message}", t)
                                Handler(Looper.getMainLooper()).post {
                                    isKdbxProcessing = false
                                    kdbxDialogError = t.message ?: "Export failed"
                                }
                            }
                        }
                    },
                    enabled = kdbxMasterPasswordInput.isNotBlank() && !isKdbxProcessing
                ) {
                    Text(if (isKdbxProcessing) "Exporting..." else "Export .kdbx")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!isKdbxProcessing) {
                            showKdbxExportPasswordDialog = false
                            pendingKdbxExportUri = null
                            kdbxMasterPasswordInput = ""
                            kdbxConfirmPasswordInput = ""
                            kdbxDialogError = null
                        }
                    },
                    enabled = !isKdbxProcessing
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showKdbxImportPasswordDialog && pendingKdbxImportUri != null) {
        val importUri = pendingKdbxImportUri!!
        AlertDialog(
            onDismissRequest = {
                if (!isKdbxProcessing) {
                    showKdbxImportPasswordDialog = false
                    pendingKdbxImportUri = null
                    pendingKdbxKeyFileUri = null
                    kdbxMasterPasswordInput = ""
                    kdbxDialogError = null
                }
            },
            title = { Text("Import Security Vault (.kdbx)") },
            text = {
                Column {
                    Text(
                        "Enter the master password to decrypt this KeePass file and seal credentials in your device hardware.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = kdbxMasterPasswordInput,
                        onValueChange = {
                            kdbxMasterPasswordInput = it
                            kdbxDialogError = null
                        },
                        label = { Text("Master Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isKdbxPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        trailingIcon = {
                            IconButton(onClick = { isKdbxPasswordVisible = !isKdbxPasswordVisible }) {
                                Text(if (isKdbxPasswordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Key File (Optional)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = pendingKdbxKeyFileUri?.lastPathSegment ?: "None selected",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        if (pendingKdbxKeyFileUri != null) {
                            TextButton(onClick = { pendingKdbxKeyFileUri = null }) {
                                Text("Clear")
                            }
                        } else {
                            TextButton(onClick = {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType("*/*")
                                kdbxKeyFileLauncher.launch(intent)
                            }) {
                                Text("Browse")
                            }
                        }
                    }
                    if (kdbxDialogError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(kdbxDialogError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isKdbxProcessing = true
                        val pwdChars = kdbxMasterPasswordInput.toCharArray()
                        val keyFileUri = pendingKdbxKeyFileUri
                        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
                            try {
                                val result = KdbxRepository.importFromUri(ctx, importUri, pwdChars, keyFileUri)
                                Handler(Looper.getMainLooper()).post {
                                    isKdbxProcessing = false
                                    if (result.success) {
                                        showKdbxImportPasswordDialog = false
                                        pendingKdbxImportUri = null
                                        pendingKdbxKeyFileUri = null
                                        kdbxMasterPasswordInput = ""
                                        Toast.makeText(
                                            ctx,
                                            "Security Vault imported successfully: ${result.groupCount} groups, ${result.entryCount} entries",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        kdbxDialogError = result.errorMessage ?: "Failed to import KeePass file (invalid password or format)"
                                    }
                                }
                            } catch (t: Throwable) {
                                LogCatcher.e("BackupRestoreScreen", "KDBX import failed: ${t.message}", t)
                                Handler(Looper.getMainLooper()).post {
                                    isKdbxProcessing = false
                                    kdbxDialogError = t.message ?: "Import failed"
                                }
                            }
                        }
                    },
                    enabled = (kdbxMasterPasswordInput.isNotBlank() || pendingKdbxKeyFileUri != null) && !isKdbxProcessing
                ) {
                    Text(if (isKdbxProcessing) "Importing..." else "Import .kdbx")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!isKdbxProcessing) {
                            showKdbxImportPasswordDialog = false
                            pendingKdbxImportUri = null
                            pendingKdbxKeyFileUri = null
                            kdbxMasterPasswordInput = ""
                            kdbxDialogError = null
                        }
                    },
                    enabled = !isKdbxProcessing
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
