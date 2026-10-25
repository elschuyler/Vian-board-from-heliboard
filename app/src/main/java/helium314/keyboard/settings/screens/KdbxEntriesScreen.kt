// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.security.vault.crypto.VaultCryptoManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.data.VaultEntryEntity
import helium314.keyboard.security.vault.data.VaultGroupEntity
import helium314.keyboard.security.vault.totp.TotpGenerator
import helium314.keyboard.settings.SearchSettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Dedicated full page for browsing, searching, and managing KeePass KDBX entries and groups.
 * Provides a clean, focused view separated from database connection & sync settings.
 */
@Composable
fun KdbxEntriesScreen(
    onClickAddEntry: () -> Unit,
    onClickEditEntry: (String) -> Unit,
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { SecurityVaultDao.getInstance(context) }

    var groups by remember { mutableStateOf<List<VaultGroupEntity>>(emptyList()) }
    var entries by remember { mutableStateOf<List<VaultEntryEntity>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedGroupUuids by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Vault Entries",
        settings = emptyList()
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onClickAddEntry,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(painter = painterResource(R.drawable.ic_plus), contentDescription = "Add Entry")
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    placeholder = { Text("Search entries by title, username, url...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

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

                if (groups.isEmpty() && entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No Entries in Vault",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap + below to create a new entry",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (searchQuery.isNotBlank()) {
                            items(filteredEntries, key = { it.entryUuid }) { entry ->
                                VaultEntryItemCard(
                                    entry = entry,
                                    currentTickMs = currentTickMs,
                                    onClick = { onClickEditEntry(entry.entryUuid) }
                                )
                            }
                        } else {
                            val rootGroups = groups.filter { it.parentGroupUuid == null }
                            val ungroupedEntries = entries.filter { e -> groups.none { it.groupUuid == e.groupUuid } }

                            items(rootGroups, key = { it.groupUuid }) { group ->
                                VaultGroupAccordion(
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
                                        onClickEditEntry(entry.entryUuid)
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
                                    VaultEntryItemCard(
                                        entry = entry,
                                        currentTickMs = currentTickMs,
                                        onClick = { onClickEditEntry(entry.entryUuid) }
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
    }
}

@Composable
private fun VaultGroupAccordion(
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
                    VaultGroupAccordion(
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
                    VaultEntryItemCard(
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
private fun VaultEntryItemCard(
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
                        val unpacked = VaultCryptoManager.unpackCustomFieldsJson(entry.customFieldsJson)
                        if (!unpacked.isNullOrBlank()) {
                            val json = Json.parseToJsonElement(unpacked) as? JsonObject
                            json?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
                        } else emptyMap()
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
