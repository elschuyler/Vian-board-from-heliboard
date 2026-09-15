// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LogKeeperActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LogKeeperScreen(onBack = { finish() })
                }
            }
        }
    }
}

enum class TimeFilter(val label: String, val durationMs: Long?) {
    ALL("All", null),
    ONE_HOUR("1h", 1 * 60 * 60 * 1000L),
    SIX_HOURS("6h", 6 * 60 * 60 * 1000L),
    TWELVE_HOURS("12h", 12 * 60 * 60 * 1000L),
    TWENTY_FOUR_HOURS("24h", 24 * 60 * 60 * 1000L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogKeeperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val scope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var selectedTimeFilter by remember { mutableStateOf(TimeFilter.ALL) }
    var isEnabled by remember {
        mutableStateOf(prefs.getBoolean("pref_log_keeper_enabled", true))
    }

    var logEntries by remember {
        mutableStateOf(LogCatcher.getLogs())
    }
    var crashReportText by remember {
        mutableStateOf(LogCatcher.readLastCrashReport())
    }
    var activeComponents by remember {
        mutableStateOf(LogCatcher.getActiveComponents())
    }
    var showActiveSubsystems by remember {
        mutableStateOf(false)
    }

    val now = System.currentTimeMillis()
    val timeFilteredLogs = remember(logEntries, selectedTimeFilter) {
        val dur = selectedTimeFilter.durationMs
        if (dur == null) logEntries
        else {
            val cutoff = now - dur
            logEntries.filter { it.timestamp >= cutoff }
        }
    }

    val timeFilteredErrors = remember(timeFilteredLogs) {
        timeFilteredLogs.filter { it.level in listOf('E', 'W', 'F') || it.tag.contains("CRASH", ignoreCase = true) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            context.contentResolver?.openOutputStream(uri)?.use { os ->
                os.writer().use { writer ->
                    writer.write("=== LOG KEEPER AUDIT EXPORT ===\n")
                    writer.write("Export Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Calendar.getInstance().time)}\n")
                    writer.write("Active Tab: ${if (selectedTabIndex == 0) "All Logs" else "Errors"}\n")
                    writer.write("Time Filter: ${selectedTimeFilter.label}\n")
                    writer.write("Privacy Filter: NO content, NO credentials, NO PII\n\n")

                    if (selectedTabIndex == 1 && !crashReportText.isNullOrBlank()) {
                        writer.write("--- PERSISTED CRASH REPORT ---\n")
                        writer.write(crashReportText)
                        writer.write("\n\n")
                    }

                    val entriesToExport = if (selectedTabIndex == 0) timeFilteredLogs else timeFilteredErrors
                    writer.write("--- LOG ENTRIES (${entriesToExport.size}) ---\n")
                    entriesToExport.forEach { entry ->
                        writer.write("${entry.toExportString()}\n")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            // Top Bar
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.log_keeper),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            isEnabled = checked
                            prefs.edit().putBoolean("pref_log_keeper_enabled", checked).apply()
                            LogCatcher.setEnabled(checked)
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            if (selectedTabIndex == 0) {
                                val fullLog = buildString {
                                    appendLine("=== VIANBOARD ALL SYSTEM LOGS (${timeFilteredLogs.size}) [Filter: ${selectedTimeFilter.label}] ===")
                                    if (activeComponents.isNotEmpty()) {
                                        appendLine("--- ACTIVE SUBSYSTEMS ---")
                                        activeComponents.forEach {
                                            appendLine("[${it.category}] ${it.name}: ${it.status}")
                                        }
                                        appendLine()
                                    }
                                    timeFilteredLogs.forEach { appendLine(it.toExportString()) }
                                }
                                clipboard.setPrimaryClip(ClipData.newPlainText("LogKeeper_AllLogs", fullLog))
                                Toast.makeText(context, "All logs & subsystem states copied", Toast.LENGTH_SHORT).show()
                            } else {
                                val cleanCrashTrace = crashReportText?.trim()
                                if (cleanCrashTrace.isNullOrBlank() && timeFilteredErrors.isEmpty()) {
                                    Toast.makeText(context, "No error logs to copy", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                val errorLog = buildString {
                                    if (!cleanCrashTrace.isNullOrBlank()) {
                                        appendLine("=== FATAL CRASH INTERCEPTED ===")
                                        appendLine(cleanCrashTrace)
                                        appendLine()
                                    }
                                    if (timeFilteredErrors.isNotEmpty()) {
                                        appendLine("=== ERROR & WARNING LOGS (${timeFilteredErrors.size}) [Filter: ${selectedTimeFilter.label}] ===")
                                        timeFilteredErrors.forEach { appendLine(it.toExportString()) }
                                    }
                                }
                                clipboard.setPrimaryClip(ClipData.newPlainText("LogKeeper_Errors", errorLog))
                                Toast.makeText(context, "Error logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.sym_keyboard_copy_rounded),
                            contentDescription = "Copy active tab logs"
                        )
                    }
                    IconButton(
                        onClick = {
                            val contentToExport = buildString {
                                appendLine("=== LOG KEEPER AUDIT EXPORT ===")
                                appendLine("Export Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Calendar.getInstance().time)}")
                                appendLine("Active Tab: ${if (selectedTabIndex == 0) "All Logs" else "Errors"}")
                                appendLine("Time Filter: ${selectedTimeFilter.label}")
                                appendLine("Privacy Filter: NO content, NO credentials, NO PII\n")

                                if (selectedTabIndex == 1 && !crashReportText.isNullOrBlank()) {
                                    appendLine("--- PERSISTED CRASH REPORT ---")
                                    appendLine(crashReportText)
                                    appendLine()
                                }

                                val entriesToExport = if (selectedTabIndex == 0) timeFilteredLogs else timeFilteredErrors
                                if (selectedTabIndex == 0 && activeComponents.isNotEmpty()) {
                                    appendLine("--- ACTIVE SUBSYSTEMS (${activeComponents.size}) ---")
                                    activeComponents.forEach {
                                        appendLine("- [${it.category}] ${it.name}: ${it.status} (lastSeen=${it.lastSeenAt})")
                                    }
                                    appendLine()
                                }
                                appendLine("--- LOG ENTRIES (${entriesToExport.size}) ---")
                                entriesToExport.forEach { appendLine(it.toExportString()) }
                            }
                            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Calendar.getInstance().time)
                            val tabName = if (selectedTabIndex == 0) "all_logs" else "errors"
                            val fileName = "VianBoard_${tabName}_${selectedTimeFilter.label}_$date.txt"
                            val success = LogCatcher.saveToDownloads(context, fileName, contentToExport)
                            if (success) {
                                Toast.makeText(context, "Saved to Downloads folder ($fileName)", Toast.LENGTH_LONG).show()
                            } else {
                                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .putExtra(Intent.EXTRA_TITLE, fileName)
                                    .setType("text/plain")
                                exportLauncher.launch(intent)
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file_download),
                            contentDescription = "Export logs"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 2 Tabs: [All Logs] and [Errors]
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = {
                        selectedTabIndex = 0
                        logEntries = LogCatcher.getLogs()
                        crashReportText = LogCatcher.readLastCrashReport()
                    },
                    text = {
                        Text(
                            text = "All Logs",
                            fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        logEntries = LogCatcher.getLogs()
                        crashReportText = LogCatcher.readLastCrashReport()
                    },
                    text = {
                        Text(
                            text = "Errors",
                            fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            // Time Pills Filter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimeFilter.values().forEach { filter ->
                    val isSelected = selectedTimeFilter == filter
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = if (isSelected) 4.dp else 0.dp,
                        modifier = Modifier
                            .clickable {
                                selectedTimeFilter = filter
                                logEntries = LogCatcher.getLogs()
                                crashReportText = LogCatcher.readLastCrashReport()
                            }
                    ) {
                        Text(
                            text = filter.label,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Tab Content
            if (selectedTabIndex == 0) {
                if (activeComponents.isNotEmpty()) {
                    ActiveSubsystemsCard(
                        components = activeComponents,
                        isExpanded = showActiveSubsystems,
                        onToggle = { showActiveSubsystems = !showActiveSubsystems }
                    )
                }
                LogListContent(
                    entries = timeFilteredLogs,
                    crashReport = null,
                    emptyMessage = if (selectedTimeFilter == TimeFilter.ALL)
                        "No logs recorded.\nSystem is running cleanly."
                    else "No logs in the last ${selectedTimeFilter.label}."
                )
            } else {
                LogListContent(
                    entries = timeFilteredErrors,
                    crashReport = crashReportText,
                    emptyMessage = if (selectedTimeFilter == TimeFilter.ALL)
                        "No errors or warnings recorded.\nAll subsystems operating normally."
                    else "No errors in the last ${selectedTimeFilter.label}."
                )
            }
        }
    }
}

@Composable
fun LogListContent(
    entries: List<LogCatcher.LogEntry>,
    crashReport: String?,
    emptyMessage: String
) {
    if (entries.isEmpty() && crashReport.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp)
        ) {
            if (!crashReport.isNullOrBlank()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val context = LocalContext.current
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "FATAL CRASH INTERCEPTED",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("FatalCrashTrace", crashReport))
                                        Toast.makeText(context, "Crash report copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.sym_keyboard_copy_rounded),
                                        contentDescription = "Copy Crash Trace",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = crashReport,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 15
                            )
                        }
                    }
                }
            }

            items(entries.reversed()) { entry ->
                LogCardItem(entry = entry)
            }
        }
    }
}

@Composable
fun LogCardItem(entry: LogCatcher.LogEntry) {
    val context = LocalContext.current
    Card(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LogItem", entry.toExportString()))
            Toast.makeText(context, "Log entry copied", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.formattedTime(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = entry.tag,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                val (badgeColor, textColor) = when (entry.level) {
                    'E', 'F' -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    'W' -> Color(0xFFFFE0B2) to Color(0xFFE65100)
                    else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = badgeColor
                ) {
                    Text(
                        text = "[${entry.level}]",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = textColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = entry.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!entry.stackTrace.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.stackTrace,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ActiveSubsystemsCard(
    components: List<LogCatcher.ComponentInfo>,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF4CAF50), CircleShape)
                    )
                    Text(
                        text = "Active Subsystems (${components.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (isExpanded) "Hide ▲" else "Show ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    components.forEach { comp ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(vertical = 1.dp)
                                ) {
                                    Text(
                                        text = comp.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = comp.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = comp.status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

