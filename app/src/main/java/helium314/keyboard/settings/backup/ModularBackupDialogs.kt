// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.backup

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.R
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ModularExportDialog(
    selectedModules: Set<BackupModule>,
    onModulesChanged: (Set<BackupModule>) -> Unit,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .clip(RoundedCornerShape(24.dp)),
        title = {
            Text(
                text = stringResource(R.string.backup_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Models exclusion note banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.backup_models_excluded_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.backup_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Quick Select All / Deselect All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onModulesChanged(BackupModule.entries.toSet()) }) {
                        Text(stringResource(R.string.backup_select_all))
                    }
                    TextButton(onClick = { onModulesChanged(emptySet()) }) {
                        Text(stringResource(R.string.backup_deselect_all))
                    }
                }

                // Checkboxes for all 5 modules
                BackupModule.entries.forEach { module ->
                    val isChecked = module in selectedModules
                    ModuleCheckboxRow(
                        module = module,
                        isChecked = isChecked,
                        onCheckedChange = { checked ->
                            if (checked) onModulesChanged(selectedModules + module)
                            else onModulesChanged(selectedModules - module)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmed,
                enabled = selectedModules.isNotEmpty()
            ) {
                Text(
                    stringResource(R.string.button_export_selected),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
fun ModularRestoreDialog(
    archiveInfo: BackupArchiveInfo,
    selectedModules: Set<BackupModule>,
    onModulesChanged: (Set<BackupModule>) -> Unit,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(archiveInfo.timestamp)
    val versionNote = if (archiveInfo.isLegacy) "Legacy" else "v${archiveInfo.appVersion}"

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .clip(RoundedCornerShape(24.dp)),
        title = {
            Text(
                text = stringResource(R.string.restore_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Archive Header Info
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "$dateStr • $versionNote (${archiveInfo.availableModules.size} modules)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Text(
                    text = stringResource(R.string.restore_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Quick Select All / Deselect All for available modules
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onModulesChanged(archiveInfo.availableModules) }) {
                        Text(stringResource(R.string.backup_select_all))
                    }
                    TextButton(onClick = { onModulesChanged(emptySet()) }) {
                        Text(stringResource(R.string.backup_deselect_all))
                    }
                }

                // Checkboxes for ONLY modules present in the archive
                archiveInfo.availableModules.forEach { module ->
                    val isChecked = module in selectedModules
                    ModuleCheckboxRow(
                        module = module,
                        isChecked = isChecked,
                        onCheckedChange = { checked ->
                            if (checked) onModulesChanged(selectedModules + module)
                            else onModulesChanged(selectedModules - module)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmed,
                enabled = selectedModules.isNotEmpty()
            ) {
                Text(
                    stringResource(R.string.button_restore_selected),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
fun ModuleCheckboxRow(
    module: BackupModule,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!isChecked) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = null,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(module.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(module.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Special warning banner for Security Vault
            if (module == BackupModule.SECURITY_VAULT && isChecked) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.backup_vault_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
