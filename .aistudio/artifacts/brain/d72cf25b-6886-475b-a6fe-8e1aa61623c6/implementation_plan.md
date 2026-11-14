# Live Implementation Plan — Phase 109 (VianBoard)

## Phase 1: Encryption Fail-Closed & SQLite Concurrency
- [ ] `VaultDao.kt`: Remove plaintext fallback in `addOrUpdate()` (return `-1L` cleanly on encryption failure) and `replaceAllInTransaction()` (throw/abort transaction cleanly without wiping or writing plaintext rows)
- [ ] `PrivacyVaultScreen.kt`: Handle `addOrUpdate(...) == -1L` in `EditVaultEntryDialog` with a clear error Toast instead of closing as success
- [ ] `Database.kt`: Make `getInstance(context)` `@Volatile` + double-checked `synchronized(this)` storing `context.applicationContext`; enable `enableWriteAheadLogging()` in `onConfigure(db)`
- [ ] `SecurityVaultDatabase.kt`: Enable `enableWriteAheadLogging()` in `onConfigure(db)` alongside `setForeignKeyConstraintsEnabled(true)`

## Phase 2: Brute-Force Pattern Lockout (5 Attempts -> 30s Cooldown)
- [ ] `VaultSessionManager.kt`: Add persisted + in-memory lockout tracking (`MAX_FAILED_ATTEMPTS = 5`, `LOCKOUT_DURATION_MS = 30_000L`, clamped remaining time `[0, 30_000L]`, reset on valid pattern/biometric/PIN unlock; expose `isLockedOut(context)`, `getLockoutRemainingMs(context)`, `getFailedAttemptCount(context)`); short-circuit PBKDF2 in `verifyPattern()` / `verifySecurityPattern()` while locked out
- [ ] `PatternUnlockView.kt`: Respect lockout in `handlePatternEntered`; keep `isStealth` silent (clear pattern without error banner), show countdown feedback in normal mode
- [ ] `SecurityScreen.kt`: `SecurityGatekeeperUnlockScreen` enforces lockout cooldown with countdown status update
- [ ] `PrivacyVaultScreen.kt`: `VaultUnlockScreen` enforces lockout cooldown with countdown status update
- [ ] `PatternLockSettingsScreen.kt`: Test-verification branch enforces lockout cooldown with countdown status update

## Phase 3: KDBX Attachment Round-Trip, KeyFile Export & Config-Change Resilience
- [ ] `KdbxRepository.kt`: Populate `attachmentsList` in `traverseGroup()` from `entry.binaries` + `db.getBinaries()` encrypted via `VaultCryptoManager.encrypt` with byte zeroization; attach decrypted binaries in `exportToUri()` + `buildKotpassGroup()` using `BinaryData.Uncompressed(false, bytes)` + `BinaryReference`, and update DB binaries via `modifyBinaries` / `removeUnusedBinaries()` before `encode()`
- [ ] `BackupRestoreScreen.kt`: Add optional Key File picker (`Browse`/`Clear`) in Export `.kdbx` dialog passing `pendingKdbxKeyFileUri` to `KdbxRepository.exportToUri`; convert dialog URIs and selected modules to `rememberSaveable`; zeroize `pwdChars` in `finally`
- [ ] `BackupRestorePreference.kt`: Convert `pendingRestoreUri`, `pendingRestorePassphraseUri`, `selectedExportModules`, `selectedRestoreModules`, `pendingRestorePassphraseModules` to `rememberSaveable`
- [ ] `SecurityVaultScreen.kt`: Zeroize `pwdChars`/`pwd` in `finally` blocks around `importFromUri`, `computeDiff`, and `executeSync`

## Phase 4: Lite Mode Secondary Dictionary Unload & Voice Lifecycle Polish
- [ ] `DictionaryFacilitatorImpl.kt`: Implement `dropSecondaryDictionaries()`, `shouldAutoRestoreSecondary()`, and `restoreSecondaryDictionaries()` under `synchronized(this)` — close secondary `DictionaryGroup` dictionaries (`ALL_DICTIONARY_TYPES`) while keeping primary `dictionaryGroups[0]` active, restore cleanly via `Settings.getValues()`
- [ ] `LatinIME.java`: Call `mDictionaryFacilitator.dropSecondaryDictionaries()` in Lite Mode on `onTrimMemory(...)`, `hideWindow()`, and `cleanupInternalStateForFinishInput()` (keeping Main, Number, Clipboard, Prompt list, Suggestion bar, Toolbar intact)
- [ ] `VoiceInputService.kt`: Guard `triggerInterimPreviewInference()` with `if (inferenceExecutor.isShutdown) return`; clear all handler callbacks in `onDestroy()` via `serviceHandler.removeCallbacksAndMessages(null)`
- [ ] `AudioRecordPipeline.kt`: Stop/release `audioRecord` before `workerThread?.join(500)` in `stop()` to prevent 500ms blocking on caller thread; suppress error callback after `!isRunning`

## Phase 5: Tests, Verification, Cleanup, Blueprint & Receipts
- [ ] Add targeted unit/Robolectric tests for lockout (5 attempts -> 30s cooldown, reset on unlock), `KdbxRepository` attachment round-trip, and `DictionaryFacilitatorImpl.dropSecondaryDictionaries()`
- [ ] Run targeted unit tests (`gradle :app:testDebugUnitTest ...`) and `compile_applet`
- [ ] Purge generated `./debug.keystore`, `./debug.keystore.base64`, and `./.build-outputs/` per Mandate 2 & 3; re-verify clean repo security scan
- [ ] Update `BLUEPRINT.md` ledger and append Receipt to `receipts/RECEIPTS_007.md` (or roll to `RECEIPTS_008.md` if >500 lines)
- [ ] Deliver concise summary and On-Device Verification Guide
