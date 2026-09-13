// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.keyboard.desktop.DesktopShortcut
import helium314.keyboard.keyboard.desktop.DesktopShortcutsCatalog
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun DesktopShortcutsCustomizer(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var items by remember { mutableStateOf(DesktopShortcutsCatalog.getAllWithConfig(prefs)) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val listState = rememberLazyListState()
    val dragDropState = rememberReorderableLazyListState(listState) { from, to ->
        val updated = items.toMutableList()
        val moved = updated.removeAt(from.index)
        updated.add(to.index, moved)
        items = updated
    }

    val chosenCount = items.count { it.second }

    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButtonText = stringResource(android.R.string.ok),
        onConfirmed = {
            DesktopShortcutsCatalog.saveConfig(prefs, items)
            onDismissRequest()
        },
        neutralButtonText = stringResource(R.string.button_default),
        onNeutral = {
            val chosenSet = DesktopShortcutsCatalog.DEFAULT_CHOSEN_IDS.toSet()
            items = DesktopShortcutsCatalog.ALL_SHORTCUTS.map { it to chosenSet.contains(it.id) }
        },
        title = {
            Column {
                Text(
                    text = "Desktop Shortcuts",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Configure the 7 modal buttons (Active: $chosenCount/7)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        content = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search shortcuts (e.g. Select, Ctrl+C)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
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
                        it.first.title.lowercase().contains(q) ||
                                it.first.subtitle.lowercase().contains(q) ||
                                it.first.category.lowercase().contains(q)
                    }
                }

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 450.dp)
                ) {
                    items(filteredItems, key = { it.first.id }) { itemPair ->
                        val shortcut = itemPair.first
                        val isEnabled = itemPair.second

                        ReorderableItem(
                            state = dragDropState,
                            key = shortcut.id,
                            enabled = searchQuery.isBlank()
                        ) { dragging ->
                            val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp)
                            Surface(
                                shadowElevation = elevation,
                                shape = RoundedCornerShape(8.dp),
                                tonalElevation = if (isEnabled) 2.dp else 0.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (searchQuery.isBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .longPressDraggableHandle()
                                                .padding(end = 8.dp)
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_drag_indicator),
                                                contentDescription = "Reorder",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = shortcut.title,
                                            fontWeight = if (isEnabled) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                        RoundedCornerShape(4.dp)
                                                    )
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = shortcut.subtitle,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "• ${shortcut.category}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { checked ->
                                            val index = items.indexOfFirst { it.first.id == shortcut.id }
                                            if (index != -1) {
                                                val updated = items.toMutableList()
                                                updated[index] = shortcut to checked
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
}
