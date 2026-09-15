// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.popup.CommaPopupItem
import helium314.keyboard.keyboard.popup.CommaPopupsCatalog
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun CommaPopupsCustomizer(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var items by remember { mutableStateOf(CommaPopupsCatalog.getAllWithConfig(prefs)) }

    val listState = rememberLazyListState()
    val dragDropState = rememberReorderableLazyListState(listState) { from, to ->
        val updated = items.toMutableList()
        val moved = updated.removeAt(from.index)
        updated.add(to.index, moved)
        items = updated
    }

    val activeCount = items.count { it.second }

    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButtonText = stringResource(android.R.string.ok),
        onConfirmed = {
            CommaPopupsCatalog.saveConfig(prefs, items)
            KeyboardLayoutSet.onSystemLocaleChanged()
            onDismissRequest()
        },
        neutralButtonText = stringResource(R.string.button_default),
        onNeutral = {
            items = CommaPopupsCatalog.ALL_ITEMS.map { it to true }
        },
        title = {
            Column {
                Text(
                    text = "Comma Key Popups",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Reorder and toggle popup actions (Active: $activeCount/${items.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        content = {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                items(items, key = { it.first.id }) { itemPair ->
                    val popupItem = itemPair.first
                    val isEnabled = itemPair.second

                    ReorderableItem(
                        state = dragDropState,
                        key = popupItem.id
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

                                Icon(
                                    painter = painterResource(popupItem.iconRes),
                                    contentDescription = popupItem.title,
                                    tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = popupItem.title,
                                        fontWeight = if (isEnabled) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (popupItem.isAlwaysEnabled) {
                                        Text(
                                            text = "Required",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Switch(
                                    checked = isEnabled,
                                    enabled = !popupItem.isAlwaysEnabled,
                                    onCheckedChange = { checked ->
                                        if (!popupItem.isAlwaysEnabled) {
                                            items = items.map {
                                                if (it.first.id == popupItem.id) it.first to checked else it
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
