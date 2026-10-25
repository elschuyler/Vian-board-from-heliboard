// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.security.vault.context.SecurityVaultContextSniffer
import helium314.keyboard.security.vault.crypto.VaultCryptoManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.data.VaultEntryEntity
import helium314.keyboard.security.vault.data.VaultGroupEntity
import helium314.keyboard.security.vault.generator.PasswordGenerator
import helium314.keyboard.security.vault.totp.TotpGenerator
import helium314.keyboard.settings.SearchSettingsScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import helium314.keyboard.security.vault.data.VaultAttachmentEntity
import java.util.UUID

/**
 * Full dedicated page for KeePass KDBX Entry CRUD (Create, Read, Update, Delete).
 * Eliminates cramped modal dialogs and resolves virtual keyboard occlusion.
 */
@Composable
fun KdbxEntryEditorScreen(
    entryUuid: String? = null,
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { SecurityVaultDao.getInstance(context) }
    val groups = remember { dao.getAllGroups() }
    val entry = remember { entryUuid?.let { dao.getEntry(it) } }
    val isEditing = entry != null
    val suggestedLinks = remember { SecurityVaultContextSniffer.getSuggestedLinks() }

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
            val unpacked = VaultCryptoManager.unpackCustomFieldsJson(entry?.customFieldsJson)
            if (!unpacked.isNullOrBlank()) {
                val json = Json.parseToJsonElement(unpacked) as? JsonObject
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

    val targetEntryUuid = remember { entry?.entryUuid ?: UUID.randomUUID().toString() }
    var attachmentsList by remember {
        mutableStateOf(if (isEditing) dao.getAttachmentsForEntry(entry!!.entryUuid).toMutableList() else mutableListOf<VaultAttachmentEntity>())
    }
    val deletedAttachmentUuids = remember { mutableListOf<String>() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                var fileName = "attachment.bin"
                var mimeType = "application/octet-stream"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIdx)
                    }
                }
                context.contentResolver.getType(uri)?.let { mimeType = it }

                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    val encryptedBlob = VaultCryptoManager.encrypt(bytes)
                    val newAtt = VaultAttachmentEntity(
                        attachmentUuid = UUID.randomUUID().toString(),
                        entryUuid = targetEntryUuid,
                        filename = fileName,
                        mimeType = mimeType,
                        dataBlobEncrypted = encryptedBlob
                    )
                    val updated = attachmentsList.toMutableList()
                    updated.add(newAtt)
                    attachmentsList = updated
                    Toast.makeText(context, "Attached: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    val totpPreview = remember(totpSecret) {
        if (totpSecret.isNotBlank()) TotpGenerator.generateFromSecret(totpSecret) else null
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = if (isEditing) "Edit KDBX Entry" else "New KDBX Entry",
        settings = emptyList()
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            bottomBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isEditing) {
                                    TextButton(
                                        onClick = {
                                            dao.deleteEntry(entry!!.entryUuid)
                                            Toast.makeText(context, "Entry deleted", Toast.LENGTH_SHORT).show()
                                            onClickBack()
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text("Delete")
                                    }
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(onClick = onClickBack) {
                                    Text("Cancel")
                                }
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
                                            VaultCryptoManager.packCustomFieldsJson(jsonObject.toString())
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
                                        dao.insertOrUpdateEntry(updated)
                                        for (att in attachmentsList) {
                                            dao.insertOrUpdateAttachment(att.copy(entryUuid = updated.entryUuid))
                                        }
                                        for (delUuid in deletedAttachmentUuids) {
                                            dao.deleteAttachment(delUuid)
                                        }
                                        Toast.makeText(context, "Entry saved successfully", Toast.LENGTH_SHORT).show()
                                        onClickBack()
                                    },
                                    enabled = title.isNotBlank()
                                ) {
                                    Text("Save Entry")
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Core Credentials Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Core Credentials",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title *") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = if (isPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password
                            ),
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
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
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

                        // Password Generator Accordion Toggle
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
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    FilterChip(selected = genUppercase, onClick = { genUppercase = !genUppercase }, label = { Text("A-Z") })
                                    FilterChip(selected = genLowercase, onClick = { genLowercase = !genLowercase }, label = { Text("a-z") })
                                    FilterChip(selected = genDigits, onClick = { genDigits = !genDigits }, label = { Text("0-9") })
                                    FilterChip(selected = genSymbols, onClick = { genSymbols = !genSymbols }, label = { Text("#$%") })
                                }

                                Button(
                                    onClick = { generateNewPassword() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Generate Password")
                                }
                            }
                        }
                    }
                }

                // Section 2: Autofill & 2FA Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Autofill & Two-Factor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        OutlinedTextField(
                            value = urlOrPackage,
                            onValueChange = { urlOrPackage = it },
                            label = { Text("URL / App Package") },
                            placeholder = { Text("e.g. google.com or com.android.chrome") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        if (suggestedLinks.isNotEmpty() && (entry == null || urlOrPackage.isBlank())) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                suggestedLinks.forEach { link ->
                                    if (urlOrPackage != link) {
                                        SuggestionChip(
                                            onClick = {
                                                urlOrPackage = link
                                                if (title.isBlank()) {
                                                    val kw = SecurityVaultContextSniffer.extractKeywordFromPackage(link)
                                                    if (kw.isNotBlank()) {
                                                        title = kw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                                                    }
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = "Link to: $link",
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            icon = {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_link),
                                                    contentDescription = "Link to app or site",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = totpSecret,
                            onValueChange = { totpSecret = it },
                            label = { Text("TOTP Secret Key (Base32 or otpauth://)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        if (totpPreview != null) {
                            Text(
                                text = "Live Token: ${totpPreview.code} (${totpPreview.remainingSeconds}s remaining)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Section 3: Notes Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Secure Notes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Notes (Encrypted with AES-256-GCM)") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                // Section 3.5: Attachments Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Attachments (${attachmentsList.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_attachment),
                                    contentDescription = "Add Attachment",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (attachmentsList.isEmpty()) {
                            Text(
                                text = "No files attached to this credential entry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            for ((index, att) in attachmentsList.withIndex()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_attachment),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = att.filename,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${att.mimeType} • ${((att.dataBlobEncrypted?.size ?: 0) / 1024).coerceAtLeast(1)} KB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val updated = attachmentsList.toMutableList()
                                            val removed = updated.removeAt(index)
                                            attachmentsList = updated
                                            deletedAttachmentUuids.add(removed.attachmentUuid)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_attachment),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("+ Attach File")
                        }
                    }
                }

                // Section 4: Custom Fields Card (Zero Metadata Loss)
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Custom Fields (${customFieldsList.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        for ((index, pair) in customFieldsList.withIndex()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${pair.first}:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(110.dp)
                                )
                                Text(
                                    text = pair.second,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        val updated = customFieldsList.toMutableList()
                                        updated.removeAt(index)
                                        customFieldsList = updated
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        if (isAddingCustomField) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = newCustomKey,
                                    onValueChange = { newCustomKey = it },
                                    label = { Text("Field Name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                OutlinedTextField(
                                    value = newCustomVal,
                                    onValueChange = { newCustomVal = it },
                                    label = { Text("Field Value") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )
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
                                        Text("Add Field")
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { isAddingCustomField = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("+ Add Custom Field")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
