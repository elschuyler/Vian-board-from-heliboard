# Receipts Ledger: Series 006

Audit trail for code changes, builds, and verifications in Vianboard (`shura.keyboard`).
Strictly capped at 500 lines per file.

---

### Receipt Entry: Phase 79 Number Pad Space Bar Vault Explorer Long-Press, Toolbar Vault Keys, and Comma Key Popups Catalog Expansion
- **Timestamp**: 2026-10-28 05:51:00 UTC-7
- **Summary of request**: Configure long press on space bar of number pad layout to open Security Vault Explorer modal (with pattern unlock challenge first) instead of keyboard app switch, strictly isolated to number pad layout; add Security Vault Explorer and Privacy Vault tools to the keyboard toolbar; and expand Comma Key Popups controls in Settings to include all toolbar tools while preserving the current 7 default tools as factory default.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyCode.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardCodesSet.java`
  - `app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardIconsSet.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListener.java`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/PointerTracker.java`
  - `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java`
  - `app/src/main/java/helium314/keyboard/keyboard/popup/CommaPopupItem.kt`
  - `app/src/main/java/helium314/keyboard/settings/dialogs/CommaPopupsCustomizer.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_006.md`
- **What was actually done**:
  1. In `PointerTracker.java`: Added a dedicated check when `code == Constants.CODE_SPACE && mKeyboard != null && mKeyboard.mId.getElement() == KeyboardElement.NUMPAD`. It cancels key tracking and dispatches `CustomAction.SHOW_SECURITY_VAULT_EXPLORER`, suppressing the system IME switcher dialog. On all other layouts (alphabet, symbols, etc.), normal space bar behavior remains 100% untouched.
  2. In `KeyboardSwitcher.java`: Extracted `showSecurityVaultExplorerWithAuth()` which checks active security session validity, and if locked, challenges with stealth pattern or primary biometric/PIN before calling `setSecurityVaultExplorerKeyboard()`. Wired `CustomAction.SHOW_SECURITY_VAULT_EXPLORER` in `KeyboardActionListener.java` and `KeyboardActionListenerImpl.kt` to invoke this method.
  3. In `KeyCode.kt` & `KeyboardCodesSet.java`: Added `KeyCode.SECURITY_VAULT (-10060)`, mapped `"key_security_vault"`, and added to `checkAndConvertCode`.
  4. In `ToolbarUtils.kt`: Added `SECURITY_VAULT` and `PRIVACY_VAULT` to `ToolbarKey` enum and mapped them in `getCodeForToolbarKey()`. Kept `defaultToolbarPref` restricted to the 8 existing default tools so vault tools are available in the customizer without altering default toolbar density.
  5. In `KeyboardIconsSet.kt`: Bound `ToolbarKey.SECURITY_VAULT -> R.drawable.ic_vpn_key` and `ToolbarKey.PRIVACY_VAULT -> R.drawable.ic_settings_security` across both standard and rounded icon sets.
  6. In `InputLogic.java`: Handled `KeyCode.SECURITY_VAULT` to trigger `showSecurityVaultExplorerWithAuth()`, and `KeyCode.PRIVACY_VAULT` to launch Privacy Vault management settings.
  7. In `CommaPopupItem.kt`: Expanded `CommaPopupsCatalog.ALL_ITEMS` to include all toolbar tools (Security Vault, Clipboard History, Numpad, DPAD, Undo, Redo, Cut, Copy, Paste, Select All, Select Word, Incognito, Autocorrect, Clear Clipboard, Split, Floating, Navigation Arrows, Start/End of Line). Preserved `DEFAULT_IDS` strictly to the 7 original items, defaulting all additional items to disabled.
  8. In `CommaPopupsCustomizer.kt`: Configured the "Default" button to reset strictly to `DEFAULT_IDS`, and added an ergonomic recommendation tip (5–8 active items).
  9. Answered user query regarding SLM Whisper model storage: confirmed storage resides in persistent internal App Data (`context.noBackupFilesDir/voice_models/model.bin`), protected from Android OS cache cleaning and excluded from Google Cloud backup limits.
  10. Verified compilation via Gradle (`compileDebugKotlin`, `compileDebugJavaWithJavac` passed in 1m 45s). Conducted silent security scan per Mandates 2 & 3.
- **How it was verified**: local build only (`gradle compileDebugKotlin compileDebugJavaWithJavac` succeeded in 1m 45s).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for on-device manual QA testing.

---

### Receipt Entry: Phase 80 Security Vault Crash Hardening, Keystore Mismatch Handling, and Modular Backup Separation (.zip & .kdbx)
- **Timestamp**: 2026-10-29 03:45:00 UTC-7
- **Summary of request**: Fix fatal app crash occurring when opening Security Vault settings page entry list (`KdbxEntriesScreen.kt`) caused by restoring old backups containing AndroidKeyStore-locked entries; decouple backup architecture so general keyboard data is saved as `.zip` and Security Vault credentials are kept separately as standard KeePass `.kdbx` files.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/security/vault/crypto/VaultCryptoManager.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/KdbxEntriesScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/backup/ModularBackupEngine.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/BackupRestoreScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/AdvancedScreen.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_006.md`
- **What was actually done**:
  1. Analyzed crash dump `/public/assets/aistudio/VianBoard_CRASH_20261029_000939.log_c77c694f.txt`: confirmed root cause was `javax.crypto.AEADBadTagException` occurring when attempting to decrypt legacy SQLite ciphertext using newly generated hardware-backed AndroidKeyStore AES keys on app reinstall.
  2. In `VaultCryptoManager.kt`: Added defensive exception wrapping in `decryptToString()`, `decryptToCharArray()`, and created `decryptOrNull()`. Decryption failures now return `null` safely rather than crashing the calling UI thread.
  3. In `KdbxEntriesScreen.kt`: Handled `null` decrypted values gracefully and introduced `isKeyMismatched` badge in `VaultEntryItemCard` (`⚠️ Key Mismatched • Re-import KDBX`), providing visual feedback to the user without interrupting Compose rendering.
  4. In `ModularBackupEngine.kt`: Removed raw `security_vault.db` SQLite database export from the general `.zip` backup archive to eliminate keystore mismatch conflicts on restore.
  5. In `BackupRestoreScreen.kt`: Added a dedicated "Security Vault (KeePass .kdbx)" category featuring distinct Preferences for Export (.kdbx) and Import (.kdbx), complete with Master Password setup, validation, confirmation, and optional Key File picker dialogs.
  6. In `AdvancedScreen.kt`: Updated preference description to clearly distinguish between modular `.zip` backups and KeePass `.kdbx` credential archives.
  7. Verified compilation cleanly using `compile_applet`. Zero errors.
  8. Executed silent security self-scan per Mandates 2 & 3: zero exposed credentials found in workspace.
- **How it was verified**: local build only (`compile_applet` passed cleanly).
- **Deviation from requested**: Floating window request was postponed as instructed ("Forget floating window for now").
- **Known issue or follow-up needed**: Ready for user on-device verification.

---

### Receipt Entry: Phase 81 Enforce Total Exclusion of Security Vault Database Entries from ZIP Backup and Restore
- **Timestamp**: 2026-10-29 04:30:00 UTC-7
- **Summary of request**: Ensure that if an old backup .zip contains Security Vault credential entries or database files, they are completely excluded and never imported/restored into the local database; strictly decouple all Security Vault credential data so .zip archives never read or write vault entries.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/settings/backup/ModularBackupEngine.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/BackupRestoreScreen.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_006.md`
- **What was actually done**:
  1. In `ModularBackupEngine.kt`: Excised `vault_portable.enc` generation and serialization from `createBackup()`. New .zip archives will never contain encrypted entry containers or databases.
  2. In `ModularBackupEngine.kt`: Updated `inspectArchive()` to bypass `vault_portable.enc` and `security_vault.db` so they are not detected as credential restore targets from .zip files.
  3. In `ModularBackupEngine.kt`: Excised `restoredVaultDbBytes` and `restoredVaultPortableBytes` variables and read logic from `restoreArchive()`. Any legacy vault database or entry container present inside an old .zip is explicitly skipped and logged.
  4. In `ModularBackupEngine.kt`: Completely eliminated database replacement/restoration logic (`dao.replaceAllWith()` and file-copying to `SecurityVaultDatabase.DATABASE_NAME`) from the restore execution sequence. If `SECURITY_VAULT` is selected, only the pattern lock configuration (`vault.json`) in `context.prefs()` is restored.
  5. In `BackupRestoreScreen.kt`: Updated UI description for Restore to explicitly clarify that `.zip` archives exclude vault credentials.
  6. Verified compilation cleanly using `compile_applet`. Zero errors.
  7. Performed silent security scan per Mandates 2 & 3: zero exposed credentials found.
- **How it was verified**: local build only (`compile_applet` passed cleanly).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for user on-device verification.
