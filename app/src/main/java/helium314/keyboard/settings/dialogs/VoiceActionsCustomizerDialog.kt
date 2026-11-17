// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.VoiceActionCatalog
import helium314.keyboard.latin.voice.VoiceActionItem
import helium314.keyboard.latin.voice.VoiceActionKind
import helium314.keyboard.latin.voice.VoiceActionSpacing
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun VoiceActionsCustomizerDialog(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var items by remember { mutableStateOf(VoiceActionCatalog.getAll(prefs)) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<VoiceActionItem?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var deletingItem by remember { mutableStateOf<VoiceActionItem?>(null) }

    val listState = rememberLazyListState()
    val dragDropState = rememberReorderableLazyListState(listState) { from, to ->
        val updated = items.toMutableList()
        val moved = updated.removeAt(from.index)
        updated.add(to.index, moved)
        items = updated
    }

    val slottedCount = items.count { it.isSlotted }

    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButtonText = stringResource(android.R.string.ok),
        onConfirmed = {
            VoiceActionCatalog.saveAll(prefs, items)
        },
        neutralButtonText = stringResource(R.string.button_default),
        onNeutral = {
            items = VoiceActionCatalog.defaultItems()
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.voice_actions_dialog_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(
                            R.string.voice_actions_dialog_subtitle,
                            slottedCount,
                            VoiceActionCatalog.MAX_MODAL_SLOTS
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("voice_actions_add_button")
                ) {
                    Text("+ Add")
                }
            }
        },
        content = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.voice_actions_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("voice_actions_search_input"),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )

                val filteredItems = if (searchQuery.isBlank()) {
                    items
                } else {
                    val q = searchQuery.trim().lowercase()
                    items.filter {
                        it.title.lowercase().contains(q) ||
                            it.symbol.lowercase().contains(q) ||
                            it.triggerPhrases.lowercase().contains(q) ||
                            it.closeTriggerPhrases.lowercase().contains(q)
                    }
                }

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    items(filteredItems, key = { it.id }) { action ->
                        val slotIndex = if (action.isSlotted) {
                            items.filter { it.isSlotted }.indexOfFirst { it.id == action.id } + 1
                        } else 0

                        ReorderableItem(
                            state = dragDropState,
                            key = action.id,
                            enabled = searchQuery.isBlank()
                        ) { dragging ->
                            val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "dragElevation")
                            Surface(
                                shadowElevation = elevation,
                                shape = RoundedCornerShape(10.dp),
                                tonalElevation = if (action.isSlotted) 2.dp else 0.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingItem = action }
                                    .testTag("voice_action_row_${action.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 54.dp)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (searchQuery.isBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .longPressDraggableHandle()
                                                .padding(end = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_drag_indicator),
                                                contentDescription = "Reorder",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // Symbol Badge
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (action.isEnabled && action.isSlotted)
                                                    MaterialTheme.colorScheme.primaryContainer
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = action.symbol,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            color = if (action.isEnabled && action.isSlotted)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = action.title,
                                            fontWeight = if (action.isSlotted) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val detailText = when (action.kind) {
                                            VoiceActionKind.NUMBERS -> "Spoken numbers → digits"
                                            VoiceActionKind.BRACKETS, VoiceActionKind.CUSTOM_WRAPPER ->
                                                "${action.triggerPhrases} / ${action.closeTriggerPhrases}"
                                            else -> action.triggerPhrases.ifBlank { "Tap to configure" }
                                        }
                                        Text(
                                            text = detailText,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        val statusLabel = buildString {
                                            if (slotIndex > 0) append("Slot #$slotIndex") else append("Unslotted")
                                            append(" • ")
                                            append(if (action.isEnabled) "Active ON" else "Toggled OFF")
                                        }
                                        Text(
                                            text = statusLabel,
                                            fontSize = 10.sp,
                                            color = if (action.isSlotted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }

                                    if (!action.isBuiltIn) {
                                        IconButton(
                                            onClick = { deletingItem = action },
                                            modifier = Modifier.testTag("voice_action_delete_${action.id}")
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_bin),
                                                contentDescription = stringResource(R.string.delete),
                                                tint = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = action.isSlotted,
                                        modifier = Modifier.testTag("voice_action_slot_switch_${action.id}"),
                                        onCheckedChange = { checked ->
                                            if (checked && slottedCount >= VoiceActionCatalog.MAX_MODAL_SLOTS) {
                                                Toast.makeText(
                                                    ctx,
                                                    ctx.getString(R.string.voice_actions_max_slots_warning),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@Switch
                                            }
                                            val index = items.indexOfFirst { it.id == action.id }
                                            if (index != -1) {
                                                val updated = items.toMutableList()
                                                updated[index] = action.copy(isSlotted = checked)
                                                items = updated
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )

    if (showCreateDialog || editingItem != null) {
        val currentSlottedCount = items.count { it.isSlotted }
        VoiceActionEditDialog(
            initialItem = editingItem,
            canAssignNewSlot = currentSlottedCount < VoiceActionCatalog.MAX_MODAL_SLOTS,
            onDismiss = {
                showCreateDialog = false
                editingItem = null
            },
            onSave = { saved ->
                val updated = items.toMutableList()
                val existingIdx = updated.indexOfFirst { it.id == saved.id }
                if (existingIdx >= 0) {
                    updated[existingIdx] = saved
                } else {
                    if (saved.isSlotted) {
                        // Insert right after last slotted item if slotted, else at end
                        val insertPos = updated.indexOfLast { it.isSlotted }.let { if (it >= 0) it + 1 else 0 }
                        updated.add(insertPos, saved)
                    } else {
                        updated.add(saved)
                    }
                }
                items = VoiceActionCatalog.normalizeSlots(updated)
                showCreateDialog = false
                editingItem = null
            }
        )
    }

    if (deletingItem != null) {
        val target = deletingItem!!
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.voice_actions_delete_confirm, target.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        items = items.filterNot { it.id == target.id }
                        deletingItem = null
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun VoiceActionEditDialog(
    initialItem: VoiceActionItem?,
    canAssignNewSlot: Boolean,
    onDismiss: () -> Unit,
    onSave: (VoiceActionItem) -> Unit
) {
    val isNew = initialItem == null
    val isBuiltIn = initialItem?.isBuiltIn == true
    var kind by remember { mutableStateOf(initialItem?.kind ?: VoiceActionKind.CUSTOM_SYMBOL) }
    var title by remember { mutableStateOf(initialItem?.title ?: "") }
    var symbol by remember { mutableStateOf(initialItem?.symbol ?: "") }
    var triggers by remember { mutableStateOf(initialItem?.triggerPhrases ?: "") }
    var closeTriggers by remember { mutableStateOf(initialItem?.closeTriggerPhrases ?: "") }
    var outputText by remember { mutableStateOf(initialItem?.outputText ?: "") }
    var closeOutputText by remember { mutableStateOf(initialItem?.closeOutputText ?: "") }
    var spacing by remember { mutableStateOf(initialItem?.spacing ?: VoiceActionSpacing.WORD) }
    var isEnabled by remember { mutableStateOf(initialItem?.isEnabled ?: true) }
    var isSlotted by remember { mutableStateOf(initialItem?.isSlotted ?: canAssignNewSlot) }

    val isWrapper = kind == VoiceActionKind.BRACKETS || kind == VoiceActionKind.CUSTOM_WRAPPER
    val isNumbers = kind == VoiceActionKind.NUMBERS

    val isValid = title.isNotBlank() && symbol.isNotBlank() && when {
        isNumbers -> true
        isWrapper -> triggers.isNotBlank() && closeTriggers.isNotBlank() && outputText.isNotBlank() && closeOutputText.isNotBlank()
        kind == VoiceActionKind.NEXT_LINE || kind == VoiceActionKind.MULTIPLY || kind == VoiceActionKind.STAR ->
            triggers.isNotBlank()
        else -> triggers.isNotBlank() && (spacing == VoiceActionSpacing.NEWLINE || outputText.isNotEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.voice_actions_add_title else R.string.voice_actions_edit_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isNew) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kind == VoiceActionKind.CUSTOM_SYMBOL,
                            onClick = { kind = VoiceActionKind.CUSTOM_SYMBOL },
                            label = { Text("Single Symbol") }
                        )
                        FilterChip(
                            selected = kind == VoiceActionKind.CUSTOM_WRAPPER,
                            onClick = { kind = VoiceActionKind.CUSTOM_WRAPPER },
                            label = { Text("Open / Close Pair") }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.voice_actions_field_title)) },
                    placeholder = { Text("e.g. Forward Slash") },
                    singleLine = true,
                    enabled = !isBuiltIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_action_edit_title")
                )

                OutlinedTextField(
                    value = symbol,
                    onValueChange = { if (it.length <= 5) symbol = it },
                    label = { Text(stringResource(R.string.voice_actions_field_symbol)) },
                    placeholder = { Text("e.g. /") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_action_edit_symbol")
                )

                if (isNumbers) {
                    Text(
                        text = stringResource(R.string.voice_actions_numbers_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (isWrapper) {
                    OutlinedTextField(
                        value = triggers,
                        onValueChange = { triggers = it },
                        label = { Text(stringResource(R.string.voice_actions_field_open_triggers)) },
                        placeholder = { Text("e.g. open bracket, open parenthesis") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_action_edit_open_triggers")
                    )
                    OutlinedTextField(
                        value = closeTriggers,
                        onValueChange = { closeTriggers = it },
                        label = { Text(stringResource(R.string.voice_actions_field_close_triggers)) },
                        placeholder = { Text("e.g. close bracket, close parenthesis") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_action_edit_close_triggers")
                    )
                    if (!isBuiltIn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = outputText,
                                onValueChange = { outputText = it },
                                label = { Text(stringResource(R.string.voice_actions_field_open_output)) },
                                placeholder = { Text("[") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = closeOutputText,
                                onValueChange = { closeOutputText = it },
                                label = { Text(stringResource(R.string.voice_actions_field_close_output)) },
                                placeholder = { Text("]") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = triggers,
                        onValueChange = { triggers = it },
                        label = { Text(stringResource(R.string.voice_actions_field_triggers)) },
                        placeholder = { Text("e.g. slash, forward slash") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_action_edit_triggers")
                    )
                    if (!isBuiltIn) {
                        OutlinedTextField(
                            value = outputText,
                            onValueChange = { outputText = it },
                            label = { Text(stringResource(R.string.voice_actions_field_output)) },
                            placeholder = { Text("e.g. /") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("voice_action_edit_output")
                        )
                        Text(
                            text = "Spacing Mode",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            VoiceActionSpacing.entries.forEach { option ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { spacing = option }
                                        .padding(vertical = 2.dp)
                                ) {
                                    Checkbox(
                                        checked = spacing == option,
                                        onCheckedChange = { if (it) spacing = option }
                                    )
                                    Text(option.label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isEnabled = !isEnabled }
                ) {
                    Checkbox(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.voice_actions_field_enabled),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (isNew && canAssignNewSlot) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isSlotted = !isSlotted }
                    ) {
                        Checkbox(
                            checked = isSlotted,
                            onCheckedChange = { isSlotted = it }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.voice_actions_field_slotted),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                modifier = Modifier.testTag("voice_action_edit_save"),
                onClick = {
                    val saved = VoiceActionItem(
                        id = initialItem?.id ?: VoiceActionCatalog.createCustomId(),
                        title = title.trim(),
                        symbol = symbol.trim(),
                        kind = kind,
                        triggerPhrases = triggers.trim(),
                        closeTriggerPhrases = closeTriggers.trim(),
                        outputText = if (kind == VoiceActionKind.CUSTOM_SYMBOL && spacing == VoiceActionSpacing.NEWLINE) {
                            "\n"
                        } else if (isBuiltIn) {
                            initialItem?.outputText ?: outputText
                        } else {
                            outputText
                        },
                        closeOutputText = if (isBuiltIn) {
                            initialItem?.closeOutputText ?: closeOutputText
                        } else {
                            closeOutputText
                        },
                        spacing = if (isBuiltIn) (initialItem?.spacing ?: spacing) else spacing,
                        isEnabled = isEnabled,
                        isSlotted = isSlotted
                    )
                    onSave(saved)
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
