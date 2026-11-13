# Live Implementation Plan (VianBoard)

## Phase 0: Workspace Cleanup & Baseline Verification
- [x] Verify `HeliBoard_TextKeyData.kt` and `sidelined_features/` are unused in build/source
- [x] Delete 13 stale `*PLAN*.md` files (keep `BLUEPRINT.md`, `README.md`, `receipts/`)
- [x] Delete unused `HeliBoard_TextKeyData.kt` and `sidelined_features/`
- [x] Purge `./debug.keystore.base64`, `./.build-outputs/` (and purge any generated `./debug.keystore*` after Phase 5 builds)
- [x] Verify existing tests & baseline state

## Phase 1: Privacy Vault Pills, Half Board Placement & Security Vault Pill Disable
- [x] `VaultDao.kt`: Preserve spaces in shortcuts (remove `shortcut.trim()` on save/lookup); track `unDecryptableCount` and warn cleanly; move initial DB load off UI thread (`preloadAsync` / non-blocking init)
- [x] `PrivacyVaultScreen.kt`: Show un-decryptable entry count warning in subtitle if `unDecryptableCount > 0`; keep shortcut field space-safe
- [x] `LatinIME.java`: Remove `!mLiteMode` gating from `setSuggestedWords()` and `tryShowVaultSuggestion()`; evaluate `tryShowVaultSuggestion()` before `mAutoShowToolbar` forces toolbar open; do NOT set `VaultDao.isSuspended(true)` in `onTrimMemory()`; gate Security Vault context pills off in both sites without deleting Explorer/ChosenEntry/DB
- [x] `InputLogic.java`: Do not let `tryShowClipboardSuggestion()` swallow `tryShowVaultSuggestion()` on `INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION` (post-space shortcuts like `"sh "`)
- [x] `SecurityVaultContextSniffer.kt`: Gate context sniffing/pills disabled by `CONTEXT_PILLS_ENABLED = false`
- [x] `Ktx.kt` & `FloatingKeyboardUtils.kt`: Keep `inputArea` & `inputView` `MATCH_PARENT` in Half Board mode; anchor `main_keyboard_frame` to `Gravity.BOTTOM or Gravity.END` at 50% width; reset cleanly on exit
- [x] Phase 1 Check: Verify Privacy Vault matching, Half Board bottom-right anchoring, and compilation (`compile_applet`)

## Phase 2: AES-GCM Encrypted `.zip` Backups & Batched SQLite Restore
- [x] Inspect backup models (`BackupManifest`, `BackupArchiveInfo`, etc.)
- [x] `ModularBackupEngine.kt`:
  - Add PBKDF2WithHmacSHA256 (120,000 iterations, random 16B salt) + AES-256-GCM (random 12B IV, ciphertext + GCM tag) helpers
  - Encrypt `PRIVACY_VAULT` entries + unlock pattern (`PREF_PATTERN_SALT`, `PREF_PATTERN_HASH`) into `database/privacy_vault.enc` when passphrase provided; store `encryptedVault = true` + KDF salt/iterations in `backup_manifest.json`
  - Require non-blank passphrase whenever `BackupModule.PRIVACY_VAULT` is in `selectedModules` on export; leave non-vault `.zip` exports unencrypted without prompt
  - Never include raw `heliboard.db` inside `.zip` exports containing `PRIVACY_VAULT` (eliminates plaintext DB side-channel leak)
  - On `inspectArchive()`, expose `requiresPassphrase` cleanly
  - On `restoreArchive(context, uri, selectedModules, passphrase)`, decrypt `database/privacy_vault.enc` inside AES-GCM, fail cleanly on `AEADBadTagException` (wrong password) before touching existing vault/pattern data, and keep legacy unencrypted HeliBoard restore fallback
  - Wrap Clipboard, Prompts, Word Improvement, and Privacy Vault restore writes in SQLite transactions + single post-batch reload
- [x] `BackupRestoreScreen.kt` & `BackupRestorePreference.kt` & `ModularBackupDialogs.kt`:
  - Prompt for encryption passphrase + confirmation on export when `PRIVACY_VAULT` is selected
  - Prompt for decryption passphrase on restore when `archiveInfo.requiresPassphrase == true` (covering all 3 launch paths: `BackupRestoreScreen`, `BackupRestorePreference`, `restoreLauncher`)
- [x] Phase 2 Check: Unit-test AES-GCM KDF backup round-trip & wrong-password failure + verify `compile_applet`

## Phase 3: Security, Lock Parameter, Voice Trim & Lifecycle Hardening
- [x] `KeyboardSwitcher.java`: Respect `if (lock)` in `closeSecurityVaultExplorer(boolean lock)` and `closeChosenEntry(boolean lock)` (keeping `hideWindow()`, `cleanupInternalStateForFinishInput()`, and Lock buttons locked)
- [x] `SecurityVaultContextSniffer.kt`: Remove `dao.getAllEntries()` fallback dump on unmatched password fields + key cache by `(pkg, isPasswordField, isSelfContained)`
- [x] `ChosenEntryView.kt` & `ClipboardHistoryManager.kt`: Mark Vault clipboard copies with `ClipDescription.EXTRA_IS_SENSITIVE = true` (`"android.content.extra.IS_SENSITIVE"` on API 24-32 + constant on 33+); skip persisting sensitive clips in `ClipboardHistoryManager.fetchPrimaryClip()`
- [x] `ChosenEntryView.kt` & `SecurityVaultExplorerView.kt`: Cancel/recreate `SupervisorJob()` coroutine scopes cleanly on `stop*()` and `onDetachedFromWindow()`
- [x] `VoiceInputService.kt` & `VoiceInputView.kt`:
  - On `onTrimMemory()`, pause + release `WhisperEngine` safely, never auto-reload on trim/main thread; synchronize `isVoicePaused = true` in `VoiceInputView` on `ServiceState.PAUSED` so user can tap to continue
  - Replace eager `ArrayList<Float>(MAX_SPEECH_SAMPLES)` with lazily allocated primitive `FloatArray?` freed on stop/release/`onTrimMemory`
- [x] Phase 3 Check: Verify lock, voice pause/resume, clipboard sensitivity, and `compile_applet`

## Phase 4: Lightweight Idle RAM, True Lite Mode Modal Detach & Release R8
- [x] `SettingsActivity.kt` & `SettingsContainer.kt`: Pass `applicationContext` to `SettingsContainer`, replace hard non-null `lateinit var` leakage with clearable/nullable reference cleared on `onDestroy()` when safe (keep `SearchScreen.kt` & `SettingsActivity.kt` safe)
- [x] `App.kt` & Emoji callers (`EmojiPalettesView`, `EmojiSearchActivity`, `EmojiParser`): Defer `SupportedEmojis.load()` out of `App.onCreate()` until Emoji/EmojiSearch/EmojiParser is used
- [x] `main_keyboard_frame.xml` & `KeyboardSwitcher.java`: Keep `KeyboardView`, `?123`, Numpad, and `ClipboardHistoryView` warm; dynamically inflate & detach heavy optional modals (`EmojiPalettesView`, `DesktopShortcutsView`, `PatternUnlockView`, `VoiceInputView`, `SecurityVaultExplorerView`, `ChosenEntryView`) so Lite Mode / `trimMemory()` / `deallocateMemory()` detaches & nulls inactive modals from RAM
- [x] `KeyboardLayoutSet.kt` & `LatinIME.java`: Trim `forcibleKeyboardCache` in Lite Mode / `trimMemory()`, and clear `uniqueKeysCache` + `LayoutParser.cache` cleanly on `trimMemory()`
- [x] `app/build.gradle.kts` & `proguard-rules.pro`: Enable `isMinifyEnabled = true` and `isShrinkResources = true` in `release`, verify JNI/Kotlin serialization `-keep` rules
- [x] Phase 4 Check: Verify all callers of `SupportedEmojis`, modal transitions, `compile_applet`, and unit tests

## Phase 5: Final Verification, Receipt & Blueprint Sync
- [x] Run unit tests (`gradle :app:testDebugUnitTest`) and final `compile_applet`
- [x] Purge any generated `./debug.keystore*` / temporary files per Mandate 2
- [x] Security self-scan (Mandate 3)
- [x] Update `BLUEPRINT.md` ledger & append entry to `receipts/RECEIPTS_007.md`
- [x] Provide concise summary & On-Device Verification Guide
