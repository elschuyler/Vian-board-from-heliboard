# Live Implementation Plan — Phase 109 (VianBoard)

## Phase 1: Encryption Fail-Closed & SQLite Concurrency
- [x] `VaultDao.kt`: Remove plaintext fallback in `addOrUpdate()` (return `-1L` cleanly on encryption failure) and `replaceAllInTransaction()` (throw/abort transaction cleanly without wiping or writing plaintext rows)
- [x] `PrivacyVaultScreen.kt`: Handle `addOrUpdate(...) == -1L` in `EditVaultEntryDialog` with a clear error Toast instead of closing as success
- [x] `Database.kt`: Make `getInstance(context)` `@Volatile` + double-checked `synchronized(this)` storing `context.applicationContext`; enable `enableWriteAheadLogging()` in `onConfigure(db)`
- [x] `SecurityVaultDatabase.kt`: Enable `enableWriteAheadLogging()` in `onConfigure(db)` alongside `setForeignKeyConstraintsEnabled(true)`

## Phase 2: Brute-Force Pattern Lockout (5 Attempts -> 30s Cooldown)
- [x] `VaultSessionManager.kt`: Add persisted + in-memory lockout tracking (`MAX_FAILED_ATTEMPTS = 5`, `LOCKOUT_DURATION_MS = 30_000L`, clamped remaining time `[0, 30_000L]`, reset on valid pattern/biometric/PIN unlock; expose `isLockedOut(context)`, `getLockoutRemainingMs(context)`, `getFailedAttemptCount(context)`); short-circuit PBKDF2 in `verifyPattern()` / `verifySecurityPattern()` while locked out
- [x] `PatternUnlockView.kt`: Respect lockout in `handlePatternEntered`; keep `isStealth` silent (clear pattern without error banner), show countdown feedback in normal mode
- [x] `SecurityScreen.kt`: `SecurityGatekeeperUnlockScreen` enforces lockout cooldown with countdown status update
- [x] `PrivacyVaultScreen.kt`: `VaultUnlockScreen` enforces lockout cooldown with countdown status update
- [x] `PatternLockSettingsScreen.kt`: Test-verification branch enforces lockout cooldown with countdown status update

## Phase 3: KDBX Attachment Round-Trip, KeyFile Export & Config-Change Resilience
- [x] `KdbxRepository.kt`: Populate `attachmentsList` in `traverseGroup()` from `entry.binaries` + `db.getBinaries()` encrypted via `VaultCryptoManager.encrypt` with byte zeroization; attach decrypted binaries in `exportToUri()` + `buildKotpassGroup()` using `BinaryData.Uncompressed(false, bytes)` + `BinaryReference`, and update DB binaries via `modifyBinaries` / `removeUnusedBinaries()` before `encode()`
- [x] `BackupRestoreScreen.kt`: Add optional Key File picker (`Browse`/`Clear`) in Export `.kdbx` dialog passing `pendingKdbxKeyFileUri` to `KdbxRepository.exportToUri`; convert dialog URIs and selected modules to `rememberSaveable`; zeroize `pwdChars` in `finally`
- [x] `BackupRestorePreference.kt`: Convert `pendingRestoreUri`, `pendingRestorePassphraseUri`, `selectedExportModules`, `selectedRestoreModules`, `pendingRestorePassphraseModules` to `rememberSaveable`
- [x] `SecurityVaultScreen.kt`: Zeroize `pwdChars`/`pwd` in `finally` blocks around `importFromUri`, `computeDiff`, and `executeSync`

## Phase 4: Lite Mode Secondary Dictionary Unload & Voice Lifecycle Polish
- [x] `DictionaryFacilitatorImpl.kt`: Implement `dropSecondaryDictionaries()`, `shouldAutoRestoreSecondary()`, and `restoreSecondaryDictionaries()` under `synchronized(this)` — close secondary `DictionaryGroup` dictionaries (`ALL_DICTIONARY_TYPES`) while keeping primary `dictionaryGroups[0]` active, restore cleanly via `Settings.getValues()`
- [x] `LatinIME.java`: Call `mDictionaryFacilitator.dropSecondaryDictionaries()` in Lite Mode on `onTrimMemory(...)`, `hideWindow()`, and `cleanupInternalStateForFinishInput()` (keeping Main, Number, Clipboard, Prompt list, Suggestion bar, Toolbar intact)
- [x] `VoiceInputService.kt`: Guard `triggerInterimPreviewInference()` with `if (inferenceExecutor.isShutdown) return`; clear all handler callbacks in `onDestroy()` via `serviceHandler.removeCallbacksAndMessages(null)`
- [x] `AudioRecordPipeline.kt`: Stop/release `audioRecord` before `workerThread?.join(500)` in `stop()` to prevent 500ms blocking on caller thread; suppress error callback after `!isRunning`

## Phase 5: CI MinifiedDebug Fallback, `lintVital` Fix, Tests, Cleanup, Blueprint & Receipts
- [x] Add temporary `minifiedDebug` build type (`app/build.gradle.kts`) and CI fallback (`.github/workflows/build-apk.yml`) while release signing secrets are pending
- [x] Constrain `androidx.fragment:fragment:1.8.9` in `gradle/libs.versions.toml` and `app/build.gradle.kts` to eliminate all 136 `InvalidFragmentVersionForActivityResult` errors in `:app:lintVitalMinifiedDebug` and `:app:lintVitalRelease`
- [x] Verify `gradle :app:lintVitalMinifiedDebug :app:lintVitalRelease :app:assembleMinifiedDebug :app:minifyReleaseWithR8`, `compile_applet`, and 20 targeted unit/Robolectric tests (`VaultLockoutTest`, `KdbxAttachmentTest`, `LiteDictionaryDropTest`, `BackupCryptoTest`, `InputTest`, `SubtypeTest`)
- [x] Confirm clean repo security scan (no `.keystore*`, `.jks`, `.p12`, `.env`, `local.properties`, or `.apk`/`.aab` binaries committed)
- [x] Update `BLUEPRINT.md` ledger and append Receipt `007-14` to `receipts/RECEIPTS_007.md`
