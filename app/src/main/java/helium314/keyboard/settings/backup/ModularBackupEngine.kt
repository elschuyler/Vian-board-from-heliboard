// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.backup

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.database.getStringOrNull
import androidx.core.database.sqlite.transaction
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.database.PromptDao
import helium314.keyboard.latin.database.VoiceReplacementDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.transferOldPinnedClips
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Modular backup & selective restore engine for VianBoard with complete LogKeeper diagnostics.
 */
object ModularBackupEngine {
    private const val TAG = "BackupRestoreEngine"
    private const val COMPONENT_NAME = "BackupRestoreEngine"

    private const val PREF_PATTERN_SALT = "pref_vault_pattern_salt"
    private const val PREF_PATTERN_HASH = "pref_vault_pattern_hash"

    private const val PREFS_FILE_NAME = "preferences.json"
    private const val PROTECTED_PREFS_FILE_NAME = "protected_preferences.json"

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val userDictPatterns = listOf(
        "blacklists${File.separator}.*\\.txt".toRegex(),
        "dicts${File.separator}.*${File.separator}.*user\\.dict".toRegex(),
        "UserHistoryDictionary.*${File.separator}UserHistoryDictionary.*\\.(body|header)".toRegex(),
    )

    private val layoutPatterns = listOf(
        "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex()
    )

    /**
     * Creates a compressed modular backup archive containing only the requested modules.
     */
    fun createBackup(context: Context, uri: Uri, selectedModules: Set<BackupModule>): Long {
        val startTime = SystemClock.elapsedRealtime()
        LogCatcher.markComponentActive(COMPONENT_NAME, "Data", "Backup Started")
        LogCatcher.i(TAG, "Starting modular backup: modules=${selectedModules.map { it.key }}")

        val stats = mutableMapOf<String, Int>()
        var totalBytes = 0L

        try {
            val appVersionName = try {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                pInfo.versionName ?: "unknown"
            } catch (t: Throwable) {
                "unknown"
            }

            val appVersionCode = try {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
            } catch (t: Throwable) {
                0L
            }

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zip ->
                    // 1. Settings & Appearance
                    if (BackupModule.SETTINGS in selectedModules) {
                        val rawPrefs = context.prefs().all.toMutableMap()
                        // If Security Vault is not selected, sanitize vault credentials from settings
                        if (BackupModule.SECURITY_VAULT !in selectedModules) {
                            rawPrefs.remove(PREF_PATTERN_SALT)
                            rawPrefs.remove(PREF_PATTERN_HASH)
                        }
                        val rawProtectedPrefs = context.protectedPrefs().all

                        // Write preferences in preferences/ folder and root for backward compatibility
                        val prefBytes = serializeSettings(rawPrefs)
                        val protectedPrefBytes = serializeSettings(rawProtectedPrefs)

                        zip.putNextEntry(ZipEntry("preferences/$PREFS_FILE_NAME"))
                        zip.write(prefBytes)
                        zip.closeEntry()

                        zip.putNextEntry(ZipEntry(PREFS_FILE_NAME))
                        zip.write(prefBytes)
                        zip.closeEntry()

                        zip.putNextEntry(ZipEntry("preferences/$PROTECTED_PREFS_FILE_NAME"))
                        zip.write(protectedPrefBytes)
                        zip.closeEntry()

                        zip.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
                        zip.write(protectedPrefBytes)
                        zip.closeEntry()

                        stats["settings_count"] = rawPrefs.size + rawProtectedPrefs.size

                        // Layout files
                        val filesDir = context.filesDir
                        if (filesDir != null) {
                            val layoutsDir = File(filesDir, "layouts")
                            if (layoutsDir.exists()) {
                                var layoutCount = 0
                                layoutsDir.walk().filter { it.isFile }.forEach { file ->
                                    val relPath = file.path.replace(filesDir.path + File.separator, "")
                                    if (layoutPatterns.any { relPath.matches(it) }) {
                                        zip.putNextEntry(ZipEntry(relPath))
                                        FileInputStream(file).use { it.copyTo(zip) }
                                        zip.closeEntry()
                                        layoutCount++
                                    }
                                }
                                stats["custom_layouts_count"] = layoutCount
                            }
                        }
                    }

                    // 2. User Dictionaries & History
                    if (BackupModule.USER_DICTIONARIES in selectedModules) {
                        var dictFilesCount = 0
                        val filesDir = context.filesDir
                        if (filesDir != null) {
                            filesDir.walk().filter { it.isFile }.forEach { file ->
                                val relPath = file.path.replace(filesDir.path + File.separator, "")
                                if (shouldExcludeFile(relPath)) return@forEach
                                if (userDictPatterns.any { relPath.matches(it) }) {
                                    zip.putNextEntry(ZipEntry(relPath))
                                    FileInputStream(file).use { it.copyTo(zip) }
                                    zip.closeEntry()
                                    dictFilesCount++
                                }
                            }
                        }
                        val protectedFilesDir = DeviceProtectedUtils.getFilesDir(context)
                        protectedFilesDir.walk().filter { it.isFile }.forEach { file ->
                            val relPath = file.path.replace(protectedFilesDir.path + File.separator, "")
                            if (shouldExcludeFile(relPath)) return@forEach
                            if (userDictPatterns.any { relPath.matches(it) }) {
                                zip.putNextEntry(ZipEntry("unprotected${File.separator}$relPath"))
                                FileInputStream(file).use { it.copyTo(zip) }
                                zip.closeEntry()
                                dictFilesCount++
                            }
                        }
                        stats["user_dict_files_count"] = dictFilesCount
                    }

                    // 3. Clipboard & Prompts
                    if (BackupModule.CLIPBOARD_PROMPTS in selectedModules) {
                        val db = Database.getInstance(context)
                        // Clipboard table export
                        val clipList = mutableListOf<ClipboardBackupEntry>()
                        try {
                            db.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TEXT, FILE, MIME_TYPE FROM CLIPBOARD", null).use { c ->
                                while (c.moveToNext()) {
                                    clipList.add(
                                        ClipboardBackupEntry(
                                            timestamp = c.getLong(0),
                                            isPinned = c.getInt(1) != 0,
                                            text = c.getStringOrNull(2),
                                            file = c.getStringOrNull(3),
                                            mimeTypes = c.getStringOrNull(4)?.split("§")
                                        )
                                    )
                                }
                            }
                        } catch (t: Throwable) {
                            LogCatcher.w(TAG, "Reading clipboard table error: ${t.message}")
                        }
                        val clipJsonBytes = json.encodeToString(clipList).toByteArray(Charsets.UTF_8)
                        zip.putNextEntry(ZipEntry("database/clipboard.json"))
                        zip.write(clipJsonBytes)
                        zip.closeEntry()
                        stats["clipboard_count"] = clipList.size

                        // Prompts table export
                        val promptList = mutableListOf<PromptBackupEntry>()
                        try {
                            PromptDao.ensureTableExists(db.readableDatabase)
                            db.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TITLE, TEXT FROM ${PromptDao.TABLE}", null).use { c ->
                                while (c.moveToNext()) {
                                    promptList.add(
                                        PromptBackupEntry(
                                            timestamp = c.getLong(0),
                                            isPinned = c.getInt(1) != 0,
                                            title = c.getStringOrNull(2) ?: "",
                                            text = c.getStringOrNull(3) ?: ""
                                        )
                                    )
                                }
                            }
                        } catch (t: Throwable) {
                            LogCatcher.w(TAG, "Reading prompts table error: ${t.message}")
                        }
                        val promptJsonBytes = json.encodeToString(promptList).toByteArray(Charsets.UTF_8)
                        zip.putNextEntry(ZipEntry("database/prompts.json"))
                        zip.write(promptJsonBytes)
                        zip.closeEntry()
                        stats["prompts_count"] = promptList.size

                        // Clipboard files
                        val filesDir = context.filesDir
                        if (filesDir != null) {
                            val clipDir = File(filesDir, "clipboard")
                            if (clipDir.exists()) {
                                clipDir.walk().filter { it.isFile }.forEach { file ->
                                    val relPath = file.path.replace(filesDir.path + File.separator, "")
                                    zip.putNextEntry(ZipEntry(relPath))
                                    FileInputStream(file).use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    }

                    // 4. Word Improvement Dictionary
                    if (BackupModule.WORD_IMPROVEMENT in selectedModules) {
                        val db = Database.getInstance(context)
                        val replacements = mutableListOf<VoiceReplacementBackupEntry>()
                        try {
                            VoiceReplacementDao.ensureTableExists(db.readableDatabase)
                            db.readableDatabase.rawQuery(
                                "SELECT ORIGINAL_WORD, REPLACEMENT_WORD, IS_WHOLE_WORD, TIMESTAMP FROM ${VoiceReplacementDao.TABLE}", null
                            ).use { c ->
                                while (c.moveToNext()) {
                                    replacements.add(
                                        VoiceReplacementBackupEntry(
                                            originalWord = c.getString(0) ?: "",
                                            replacementWord = c.getString(1) ?: "",
                                            isWholeWord = c.getInt(2) != 0,
                                            timestamp = c.getLong(3)
                                        )
                                    )
                                }
                            }
                        } catch (t: Throwable) {
                            LogCatcher.w(TAG, "Reading voice replacements error: ${t.message}")
                        }
                        val replJsonBytes = json.encodeToString(replacements).toByteArray(Charsets.UTF_8)
                        zip.putNextEntry(ZipEntry("database/voice_replacements.json"))
                        zip.write(replJsonBytes)
                        zip.closeEntry()
                        stats["voice_replacements_count"] = replacements.size
                    }

                    // 5. Security Vault
                    if (BackupModule.SECURITY_VAULT in selectedModules) {
                        val sp = context.prefs()
                        val salt = sp.getString(PREF_PATTERN_SALT, null)
                        val hash = sp.getString(PREF_PATTERN_HASH, null)
                        val vaultData = VaultBackupData(
                            hasPattern = salt != null && hash != null,
                            saltHex = salt,
                            hashHex = hash
                        )
                        val vaultBytes = json.encodeToString(vaultData).toByteArray(Charsets.UTF_8)
                        zip.putNextEntry(ZipEntry("security/vault.json"))
                        zip.write(vaultBytes)
                        zip.closeEntry()
                        stats["vault_configured"] = if (vaultData.hasPattern) 1 else 0
                    }

                    // 6. Manifest File (Always created)
                    val manifest = BackupManifest(
                        schemaVersion = BackupManifest.CURRENT_SCHEMA_VERSION,
                        appVersion = appVersionName,
                        appVersionCode = appVersionCode,
                        timestamp = System.currentTimeMillis(),
                        modules = selectedModules.map { it.key },
                        stats = stats
                    )
                    val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                    zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_FILE_NAME))
                    zip.write(manifestBytes)
                    zip.closeEntry()

                    // Also generate a legacy heliboard.db for full backward compatibility if database modules selected
                    if (BackupModule.CLIPBOARD_PROMPTS in selectedModules || BackupModule.WORD_IMPROVEMENT in selectedModules) {
                        val dbFile = context.getDatabasePath(Database.NAME)
                        if (dbFile.exists()) {
                            zip.putNextEntry(ZipEntry(Database.NAME))
                            FileInputStream(dbFile).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }

            val elapsed = SystemClock.elapsedRealtime() - startTime
            totalBytes = context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
            LogCatcher.i(TAG, "Backup successfully created: size=$totalBytes bytes, elapsed=${elapsed}ms, stats=$stats")
            LogCatcher.markComponentInactive(COMPONENT_NAME, "Backup Complete (${totalBytes / 1024} KB)")
            return totalBytes
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Failed creating backup archive: ${t.message}", t)
            LogCatcher.markComponentInactive(COMPONENT_NAME, "Backup Failed")
            throw t
        }
    }

    /**
     * Inspects an archive URI and extracts manifest information and present modules.
     */
    fun inspectArchive(context: Context, uri: Uri): BackupArchiveInfo {
        LogCatcher.markComponentActive(COMPONENT_NAME, "Data", "Inspecting Archive")
        LogCatcher.i(TAG, "Inspecting archive from: $uri")

        var manifestText: String? = null
        val detectedModules = mutableSetOf<BackupModule>()
        var hasLegacyDb = false
        var hasLegacyPrefs = false

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == BackupManifest.MANIFEST_FILE_NAME) {
                            manifestText = String(zip.readBytes(), Charsets.UTF_8)
                        } else if (name == PREFS_FILE_NAME || name.startsWith("preferences/") || name.startsWith("layouts/")) {
                            detectedModules.add(BackupModule.SETTINGS)
                            hasLegacyPrefs = true
                        } else if (name.contains("dict") || name.contains("UserHistoryDictionary") || name.startsWith("blacklists/")) {
                            detectedModules.add(BackupModule.USER_DICTIONARIES)
                        } else if (name.contains("clipboard") || name.contains("prompts")) {
                            detectedModules.add(BackupModule.CLIPBOARD_PROMPTS)
                        } else if (name.contains("voice_replacements")) {
                            detectedModules.add(BackupModule.WORD_IMPROVEMENT)
                        } else if (name.contains("vault.json")) {
                            detectedModules.add(BackupModule.SECURITY_VAULT)
                        } else if (name == Database.NAME) {
                            hasLegacyDb = true
                            detectedModules.add(BackupModule.CLIPBOARD_PROMPTS)
                            detectedModules.add(BackupModule.WORD_IMPROVEMENT)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            if (manifestText != null) {
                try {
                    val manifest = json.decodeFromString<BackupManifest>(manifestText!!)
                    val parsedModules = manifest.modules.mapNotNull { BackupModule.fromKey(it) }.toMutableSet()
                    if (parsedModules.isEmpty()) {
                        parsedModules.addAll(detectedModules)
                    }
                    LogCatcher.i(TAG, "Manifest parsed: schema=${manifest.schemaVersion}, v=${manifest.appVersion}, modules=${parsedModules.map { it.key }}")
                    LogCatcher.markComponentInactive(COMPONENT_NAME, "Archive Verified")
                    return BackupArchiveInfo(
                        schemaVersion = manifest.schemaVersion,
                        appVersion = manifest.appVersion,
                        timestamp = manifest.timestamp,
                        availableModules = parsedModules,
                        stats = manifest.stats,
                        isLegacy = false
                    )
                } catch (t: Throwable) {
                    LogCatcher.w(TAG, "Error parsing manifest json, falling back to entry detection: ${t.message}")
                }
            }

            // Legacy archive fallback
            if (hasLegacyPrefs) {
                detectedModules.add(BackupModule.SETTINGS)
            }
            if (hasLegacyDb) {
                detectedModules.add(BackupModule.CLIPBOARD_PROMPTS)
                detectedModules.add(BackupModule.WORD_IMPROVEMENT)
            }

            LogCatcher.i(TAG, "Legacy archive detected: modules=${detectedModules.map { it.key }}")
            LogCatcher.markComponentInactive(COMPONENT_NAME, "Legacy Archive Verified")
            return BackupArchiveInfo(
                schemaVersion = 1,
                appVersion = "Legacy",
                timestamp = System.currentTimeMillis(),
                availableModules = detectedModules,
                stats = emptyMap(),
                isLegacy = true
            )
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Error reading archive: ${t.message}", t)
            LogCatcher.markComponentInactive(COMPONENT_NAME, "Inspection Failed")
            throw t
        }
    }

    /**
     * Selectively restores chosen categories from the archive.
     * Guaranteed to never touch or overwrite unselected categories.
     */
    fun restoreArchive(context: Context, uri: Uri, selectedModules: Set<BackupModule>) {
        val startTime = SystemClock.elapsedRealtime()
        LogCatcher.markComponentActive(COMPONENT_NAME, "Data", "Restore Started")
        LogCatcher.i(TAG, "Starting selective restore: selected=${selectedModules.map { it.key }}")

        val filesDir = context.filesDir ?: throw IllegalStateException("Context filesDir is null")
        val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(context)

        // Backup existing vault keys in case Settings is restored without Security Vault
        val existingVaultSalt = context.prefs().getString(PREF_PATTERN_SALT, null)
        val existingVaultHash = context.prefs().getString(PREF_PATTERN_HASH, null)

        val restoredStats = mutableMapOf<String, Int>()

        try {
            LayoutUtilsCustom.onLayoutFileChanged()
            Settings.getInstance().stopListener()

            // In-memory or temporary caches
            var prefLines: List<String>? = null
            var protectedPrefLines: List<String>? = null
            var vaultData: VaultBackupData? = null
            var clipboardJson: String? = null
            var promptsJson: String? = null
            var voiceReplJson: String? = null
            val restoredDbFile = context.getDatabasePath(Database.NAME + "_modular_restored")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == PREFS_FILE_NAME || name == "preferences/$PREFS_FILE_NAME" -> {
                                if (BackupModule.SETTINGS in selectedModules) {
                                    prefLines = String(zip.readBytes(), Charsets.UTF_8).split("\n")
                                }
                            }
                            name == PROTECTED_PREFS_FILE_NAME || name == "preferences/$PROTECTED_PREFS_FILE_NAME" -> {
                                if (BackupModule.SETTINGS in selectedModules) {
                                    protectedPrefLines = String(zip.readBytes(), Charsets.UTF_8).split("\n")
                                }
                            }
                            name == "security/vault.json" -> {
                                if (BackupModule.SECURITY_VAULT in selectedModules) {
                                    val content = String(zip.readBytes(), Charsets.UTF_8)
                                    vaultData = json.decodeFromString<VaultBackupData>(content)
                                }
                            }
                            name == "database/clipboard.json" -> {
                                if (BackupModule.CLIPBOARD_PROMPTS in selectedModules) {
                                    clipboardJson = String(zip.readBytes(), Charsets.UTF_8)
                                }
                            }
                            name == "database/prompts.json" -> {
                                if (BackupModule.CLIPBOARD_PROMPTS in selectedModules) {
                                    promptsJson = String(zip.readBytes(), Charsets.UTF_8)
                                }
                            }
                            name == "database/voice_replacements.json" -> {
                                if (BackupModule.WORD_IMPROVEMENT in selectedModules) {
                                    voiceReplJson = String(zip.readBytes(), Charsets.UTF_8)
                                }
                            }
                            name == Database.NAME -> {
                                // Save for fallback if JSONs are missing
                                if (BackupModule.CLIPBOARD_PROMPTS in selectedModules || BackupModule.WORD_IMPROVEMENT in selectedModules) {
                                    FileUtils.copyStreamToNewFile(zip, restoredDbFile)
                                }
                            }
                            name.startsWith("layouts/") -> {
                                if (BackupModule.SETTINGS in selectedModules) {
                                    restoreEntryToDir(zip, filesDir, name)
                                }
                            }
                            name.startsWith("clipboard/") -> {
                                if (BackupModule.CLIPBOARD_PROMPTS in selectedModules) {
                                    restoreEntryToDir(zip, filesDir, name)
                                }
                            }
                            name.startsWith("unprotected${File.separator}") -> {
                                if (BackupModule.USER_DICTIONARIES in selectedModules) {
                                    val adjustedName = name.substringAfter("unprotected${File.separator}")
                                    if (userDictPatterns.any { adjustedName.matches(it) }) {
                                        restoreEntryToDir(zip, deviceProtectedFilesDir, adjustedName)
                                    }
                                }
                            }
                            userDictPatterns.any { name.matches(it) } -> {
                                if (BackupModule.USER_DICTIONARIES in selectedModules) {
                                    restoreEntryToDir(zip, filesDir, name)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            // 1. Restore Settings
            if (BackupModule.SETTINGS in selectedModules && prefLines != null) {
                val prefs = context.prefs()
                prefs.edit { clear() }
                readJsonLinesToSettings(prefLines!!, prefs)

                // Preserve vault credentials if Security Vault was NOT selected
                if (BackupModule.SECURITY_VAULT !in selectedModules) {
                    prefs.edit {
                        if (existingVaultSalt != null) putString(PREF_PATTERN_SALT, existingVaultSalt) else remove(PREF_PATTERN_SALT)
                        if (existingVaultHash != null) putString(PREF_PATTERN_HASH, existingVaultHash) else remove(PREF_PATTERN_HASH)
                    }
                }
                restoredStats["settings"] = 1
                LogCatcher.i(TAG, "Restored Settings & Appearance (vault preserved=${BackupModule.SECURITY_VAULT !in selectedModules})")
            }
            if (BackupModule.SETTINGS in selectedModules && protectedPrefLines != null) {
                val protectedPrefs = context.protectedPrefs()
                protectedPrefs.edit { clear() }
                readJsonLinesToSettings(protectedPrefLines!!, protectedPrefs)
            }

            // 2. Restore Security Vault
            if (BackupModule.SECURITY_VAULT in selectedModules) {
                if (vaultData != null) {
                    context.prefs().edit {
                        if (vaultData!!.hasPattern && vaultData!!.saltHex != null && vaultData!!.hashHex != null) {
                            putString(PREF_PATTERN_SALT, vaultData!!.saltHex)
                            putString(PREF_PATTERN_HASH, vaultData!!.hashHex)
                        } else {
                            remove(PREF_PATTERN_SALT)
                            remove(PREF_PATTERN_HASH)
                        }
                    }
                    restoredStats["security_vault"] = if (vaultData!!.hasPattern) 1 else 0
                    LogCatcher.i(TAG, "Restored Security Vault from vault.json (hasPattern=${vaultData!!.hasPattern})")
                } else if (BackupModule.SETTINGS !in selectedModules) {
                    // In case settings wasn't selected, check if prefLines contains vault keys
                    if (prefLines != null) {
                        val tempPrefs = context.getSharedPreferences("temp_restore_vault", Context.MODE_PRIVATE)
                        readJsonLinesToSettings(prefLines!!, tempPrefs)
                        val s = tempPrefs.getString(PREF_PATTERN_SALT, null)
                        val h = tempPrefs.getString(PREF_PATTERN_HASH, null)
                        context.prefs().edit {
                            if (s != null && h != null) {
                                putString(PREF_PATTERN_SALT, s)
                                putString(PREF_PATTERN_HASH, h)
                            }
                        }
                        tempPrefs.edit { clear() }
                    }
                }
            }

            // 3. Restore Clipboard & Prompts
            if (BackupModule.CLIPBOARD_PROMPTS in selectedModules) {
                val db = Database.getInstance(context)
                val clipDao = ClipboardDao.getInstance(context)

                if (clipboardJson != null && clipDao != null) {
                    val clips = json.decodeFromString<List<ClipboardBackupEntry>>(clipboardJson!!)
                    clipDao.clear()
                    clips.forEach { entry ->
                        clipDao.insertNewEntry(
                            entry.timestamp,
                            entry.isPinned,
                            entry.text,
                            entry.file,
                            entry.mimeTypes,
                            null
                        )
                    }
                    restoredStats["clipboard_count"] = clips.size
                    LogCatcher.i(TAG, "Restored ${clips.size} clipboard entries from json")
                } else if (restoredDbFile.exists() && clipDao != null) {
                    // Fallback to legacy restoredDb
                    val otherDb = Database(context, restoredDbFile.name)
                    try {
                        otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TEXT, FILE, MIME_TYPE FROM CLIPBOARD", null).use { c ->
                            clipDao.clear()
                            var count = 0
                            while (c.moveToNext()) {
                                clipDao.insertNewEntry(
                                    c.getLong(0),
                                    c.getInt(1) != 0,
                                    c.getStringOrNull(2),
                                    c.getStringOrNull(3),
                                    c.getStringOrNull(4)?.split("§"),
                                    null
                                )
                                count++
                            }
                            restoredStats["clipboard_count"] = count
                            LogCatcher.i(TAG, "Restored $count clipboard entries from legacy db")
                        }
                    } finally {
                        otherDb.close()
                    }
                }

                // Restore Prompts
                if (promptsJson != null) {
                    val prompts = json.decodeFromString<List<PromptBackupEntry>>(promptsJson!!)
                    PromptDao.ensureTableExists(db.writableDatabase)
                    db.writableDatabase.execSQL("DELETE FROM ${PromptDao.TABLE}")
                    prompts.forEach { p ->
                        val cv = ContentValues().apply {
                            put(PromptDao.COLUMN_TIMESTAMP, p.timestamp)
                            put(PromptDao.COLUMN_PINNED, if (p.isPinned) 1 else 0)
                            put(PromptDao.COLUMN_TITLE, p.title)
                            put(PromptDao.COLUMN_TEXT, p.text)
                        }
                        db.writableDatabase.insert(PromptDao.TABLE, null, cv)
                    }
                    restoredStats["prompts_count"] = prompts.size
                    LogCatcher.i(TAG, "Restored ${prompts.size} prompt templates from json")
                } else if (restoredDbFile.exists()) {
                    val otherDb = Database(context, restoredDbFile.name)
                    try {
                        val hasPrompts = otherDb.readableDatabase.rawQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name='${PromptDao.TABLE}'", null
                        ).use { it.moveToFirst() }
                        if (hasPrompts) {
                            PromptDao.ensureTableExists(db.writableDatabase)
                            db.writableDatabase.execSQL("DELETE FROM ${PromptDao.TABLE}")
                            var count = 0
                            otherDb.readableDatabase.rawQuery(
                                "SELECT TIMESTAMP, PINNED, TITLE, TEXT FROM ${PromptDao.TABLE}", null
                            ).use { c ->
                                while (c.moveToNext()) {
                                    val cv = ContentValues().apply {
                                        put(PromptDao.COLUMN_TIMESTAMP, c.getLong(0))
                                        put(PromptDao.COLUMN_PINNED, c.getInt(1))
                                        put(PromptDao.COLUMN_TITLE, c.getStringOrNull(2))
                                        put(PromptDao.COLUMN_TEXT, c.getStringOrNull(3))
                                    }
                                    db.writableDatabase.insert(PromptDao.TABLE, null, cv)
                                    count++
                                }
                            }
                            restoredStats["prompts_count"] = count
                            LogCatcher.i(TAG, "Restored $count prompts from legacy db")
                        }
                    } finally {
                        otherDb.close()
                    }
                }
            }

            // 4. Restore Word Improvement Dictionary
            if (BackupModule.WORD_IMPROVEMENT in selectedModules) {
                val voiceDao = VoiceReplacementDao.getInstance(context)
                if (voiceReplJson != null) {
                    val entries = json.decodeFromString<List<VoiceReplacementBackupEntry>>(voiceReplJson!!)
                    voiceDao.clear()
                    entries.forEach { e ->
                        voiceDao.addOrUpdate(e.originalWord, e.replacementWord, e.isWholeWord)
                    }
                    restoredStats["voice_replacements_count"] = entries.size
                    LogCatcher.i(TAG, "Restored ${entries.size} voice replacement rules from json")
                } else if (restoredDbFile.exists()) {
                    val otherDb = Database(context, restoredDbFile.name)
                    try {
                        val hasVoiceTable = otherDb.readableDatabase.rawQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name='${VoiceReplacementDao.TABLE}'", null
                        ).use { it.moveToFirst() }
                        if (hasVoiceTable) {
                            voiceDao.clear()
                            var count = 0
                            otherDb.readableDatabase.rawQuery(
                                "SELECT ORIGINAL_WORD, REPLACEMENT_WORD, IS_WHOLE_WORD FROM ${VoiceReplacementDao.TABLE}", null
                            ).use { c ->
                                while (c.moveToNext()) {
                                    voiceDao.addOrUpdate(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2) != 0)
                                    count++
                                }
                            }
                            restoredStats["voice_replacements_count"] = count
                            LogCatcher.i(TAG, "Restored $count voice replacement rules from legacy db")
                        }
                    } finally {
                        otherDb.close()
                    }
                }
            }

            // Clean up temporary database file
            if (restoredDbFile.exists()) {
                restoredDbFile.delete()
            }

            // 5. System Upgrades & Cache Invalidation
            checkVersionUpgrade(context)
            transferOldPinnedClips(context)
            PromptDao.getInstance(context).reload()
            VoiceReplacementDao.getInstance(context).reload()
            Settings.getInstance().startListener()
            SubtypeSettings.reloadEnabledSubtypes(context)

            val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
            context.sendBroadcast(newDictBroadcast)
            LayoutUtilsCustom.onLayoutFileChanged()
            LayoutUtilsCustom.removeMissingLayouts(context)
            SupportedEmojis.load(context)
            KeyboardSwitcher.getInstance().setThemeNeedsReload()

            val elapsed = SystemClock.elapsedRealtime() - startTime
            LogCatcher.i(TAG, "Selective restore finished successfully in ${elapsed}ms: $restoredStats")
            LogCatcher.markComponentInactive(COMPONENT_NAME, "Restore Complete")
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Error during selective restore: ${t.message}", t)
            LogCatcher.markComponentInactive(COMPONENT_NAME, "Restore Failed")
            throw t
        }
    }

    private fun shouldExcludeFile(path: String): Boolean {
        return path.contains("voice_models") || path.endsWith(".bin") || path.endsWith(".so") || path.endsWith(".tmp")
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializeSettings(settings: Map<String?, Any?>): ByteArray {
        val booleans = settings.filter { it.key is String && it.value is Boolean } as Map<String, Boolean>
        val ints = settings.filter { it.key is String && it.value is Int } as Map<String, Int>
        val longs = settings.filter { it.key is String && it.value is Long } as Map<String, Long>
        val floats = settings.filter { it.key is String && it.value is Float } as Map<String, Float>
        val strings = settings.filter { it.key is String && it.value is String } as Map<String, String>
        val stringSets = settings.filter { it.key is String && it.value is Set<*> } as Map<String, Set<String>>

        val out = ByteArrayOutputStream()
        out.write("boolean settings\n".toByteArray(Charsets.UTF_8))
        out.write(json.encodeToString(booleans).toByteArray(Charsets.UTF_8))
        out.write("\nint settings\n".toByteArray(Charsets.UTF_8))
        out.write(json.encodeToString(ints).toByteArray(Charsets.UTF_8))
        out.write("\nlong settings\n".toByteArray(Charsets.UTF_8))
        out.write(json.encodeToString(longs).toByteArray(Charsets.UTF_8))
        out.write("\nfloat settings\n".toByteArray(Charsets.UTF_8))
        out.write(json.encodeToString(floats).toByteArray(Charsets.UTF_8))
        out.write("\nstring settings\n".toByteArray(Charsets.UTF_8))
        out.write(json.encodeToString(strings).toByteArray(Charsets.UTF_8))
        out.write("\nstring set settings\n".toByteArray(Charsets.UTF_8))
        out.write(json.encodeToString(stringSets).toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun readJsonLinesToSettings(list: List<String>, prefs: SharedPreferences): Boolean {
        val i = list.iterator()
        val e = prefs.edit()
        try {
            while (i.hasNext()) {
                when (i.next()) {
                    "boolean settings" -> json.decodeFromString<Map<String, Boolean>>(i.next()).forEach { e.putBoolean(it.key, it.value) }
                    "int settings" -> json.decodeFromString<Map<String, Int>>(i.next()).forEach { e.putInt(it.key, it.value) }
                    "long settings" -> json.decodeFromString<Map<String, Long>>(i.next()).forEach { e.putLong(it.key, it.value) }
                    "float settings" -> json.decodeFromString<Map<String, Float>>(i.next()).forEach { e.putFloat(it.key, it.value) }
                    "string settings" -> json.decodeFromString<Map<String, String>>(i.next()).forEach { e.putString(it.key, it.value) }
                    "string set settings" -> json.decodeFromString<Map<String, Set<String>>>(i.next()).forEach { e.putStringSet(it.key, it.value) }
                }
            }
            e.apply()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun restoreEntryToDir(zip: ZipInputStream, baseDir: File, entryName: String): Boolean {
        val file = File(baseDir, entryName)
        val canonicalBase = baseDir.canonicalFile
        val canonicalTarget = file.canonicalFile
        if (canonicalTarget.path != canonicalBase.path && !canonicalTarget.path.startsWith(canonicalBase.path + File.separator)) {
            return false
        }
        FileUtils.copyStreamToNewFile(zip, file)
        return true
    }
}
