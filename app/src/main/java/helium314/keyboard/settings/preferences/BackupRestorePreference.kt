// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.backup.BackupArchiveInfo
import helium314.keyboard.settings.backup.ModularExportDialog
import helium314.keyboard.settings.backup.ModularRestoreDialog
import helium314.keyboard.settings.backup.ModuleCheckboxRow
import helium314.keyboard.settings.backup.BackupModule
import helium314.keyboard.settings.backup.ModularBackupEngine
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun BackupRestorePreference(setting: Setting) {
    val ctx = LocalContext.current
    var showActionChoiceDialog by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }

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
    var error: String? by rememberSaveable { mutableStateOf(null) }

    // Launcher for creating backup zip
    val backupLauncher = filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                ModularBackupEngine.createBackup(ctx, uri, selectedExportModules)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, ctx.getString(R.string.backup_restored).replace("restored", "created"), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                LogCatcher.e("BackupRestorePreference", "Backup failed: ${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    error = "b" + (t.message ?: "Unknown error")
                }
            }
        }
    }

    // Launcher for picking backup zip to restore
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
                LogCatcher.e("BackupRestorePreference", "Failed inspecting archive: ${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    error = "r" + (t.message ?: "Invalid backup archive")
                }
            }
        }
    }

    Preference(name = setting.title, onClick = { showActionChoiceDialog = true })

    // Step 1: Action Choice Dialog (Create Backup or Restore Backup)
    if (showActionChoiceDialog) {
        ConfirmationDialog(
            onDismissRequest = { showActionChoiceDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                showActionChoiceDialog = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                restorePickerLauncher.launch(intent)
            },
            onConfirmed = {
                showActionChoiceDialog = false
                showExportDialog = true
            }
        )
    }

    // Step 2: Modular Export Dialog
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

    // Step 3: Selective Restore Dialog
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
                        LogCatcher.e("BackupRestorePreference", "Restore failed: ${t.message}", t)
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

@Composable
fun backupLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                ModularBackupEngine.createBackup(ctx, uri, BackupModule.entries.toSet())
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, ctx.getString(R.string.backup_restored).replace("restored", "created"), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                LogCatcher.e("BackupRestorePreference", "Backup failed: ${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    onError("b" + (t.message ?: "Backup failed"))
                }
            }
        }
    }
}

@Composable
fun restoreLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                val info = ModularBackupEngine.inspectArchive(ctx, uri)
                ModularBackupEngine.restoreArchive(ctx, uri, info.availableModules)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
                    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                }
            } catch (t: Throwable) {
                LogCatcher.e("BackupRestorePreference", "Restore failed: ${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    onError("r" + (t.message ?: "Failed to restore backup"))
                }
            }
        }
    }
}

