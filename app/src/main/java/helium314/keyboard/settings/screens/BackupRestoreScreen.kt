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
                Preference(
                    name = stringResource(R.string.button_backup),
                    description = "Modular export of settings, custom layouts, dictionaries, and clipboard",
                    icon = R.drawable.ic_settings_advanced,
                    onClick = { showExportDialog = true }
                )
                Preference(
                    name = stringResource(R.string.button_restore),
                    description = "Granular restore from backup archive with component selection",
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
}
