# Implementation Plan: Privacy Vault, Security Vault (KDBX/TOTP), Pattern Unlock & Modular Backup

## 1. System Architecture & Objectives

This document establishes the architecture and execution roadmap for:
1. **Privacy Vault**: An isolated "Twin" of HeliBoard's Personal Dictionary for sensitive words/phrases, displaying masked suggestions (`jo***om`) on the suggestion strip, gated by a 5-minute Pattern Unlock session, with zero learning or clipboard history recording.
2. **Security Vault**: A lightweight, headless KeePass (`.kdbx`) and RFC 6238 TOTP engine supporting full Entry and Folder (Group) CRUD in Settings, dual-ingress (package-matched pill or in-keyboard explorer), live circular countdown timers, and an action deck modal with a 4-button dock.
3. **Pattern Unlock Engine**: A modular in-keyboard 3x3 touch pattern lock (within keyboard height) with independent session timers (5m Privacy / 3m Security), extensible to a visual fake keyboard disguise.
4. **Interactive Backup & Restore**: Pattern-gated checklist for exporting/importing sensitive vaults, including a dedicated future-ready toggle for offline Voice Input.
5. **Log Keeper Sanitization**: High-integrity action/event telemetry with absolute zero-PII/zero-secret leakage.

---

## 2. Component Specifications

### 2.1 Pattern Unlock Engine (`helium314.keyboard.security.pattern`)
* **Geometry**: Rendered strictly within `MainKeyboardView` container height.
* **Touch Interface**: 3x3 tactile node matrix styled using active keyboard theme colors (`ColorType.KEY_TEXT`, `ColorType.ACCENT`).
* **Session Management**:
  - Independent hardware Keystore-backed session clocks:
    - **Privacy Vault Session**: 5 minutes of validity from last unlock.
    - **Security Vault Session**: 3 minutes of validity from last unlock.
  - Manual Lock: Immediate session purge upon tapping the `Lock` button or upon keyboard close (configurable).
* **Disguise Readiness**: Implemented via `UnlockViewContract`, allowing seamless swap with a visual "fake QWERTY" disguise in future iterations without refactoring core unlock logic.

### 2.2 Privacy Vault: The Personal Dictionary Twin (`helium314.keyboard.security.privacy`)
* **Architecture**: Direct architectural twin of HeliBoard's `UserDictionary` / `PersonalDictionary`, but completely decoupled from Android's public `UserDictionary` ContentProvider and unencrypted binary dictionaries.
* **Storage**: Encrypted SQLite table `privacy_vault_dictionary` (`id`, `word`, `shortcut`, `locale`, `created_at`, `frequency`).
* **Settings CRUD**: Replicates `UserDictionaryList.kt` and `UserDictionaryAddWordContents.kt` under `Settings -> Security -> Privacy Vault` (Add, Edit, Delete, Search words and shortcuts).
* **Suggestion Matching & Masking**:
  - In `Suggest.java` / `SuggestionStripView`, active composing text is checked against privacy shortcuts/words.
  - On match, generates a **Masked Suggestion Pill**:
    - Strings > 6 chars: First 2 chars + `****` + Last 2 chars (e.g. `john.doe@gmail.com` -> `jo****om`).
    - Strings ≤ 6 chars: First 1 char + `**` + Last 1 char (e.g. `pin12` -> `p**2`).
* **Unlock & Injection Flow**:
  - Tap masked pill -> Check 5m Privacy session timer.
  - If locked -> Show Pattern Unlock modal in keyboard.
  - On success -> Directly commit plain text via `InputConnection.commitText()`.
  - **Zero Learning / Zero Clipboard**: Never added to `UserHistoryDictionary`, predictive bigrams, or clipboard history.

### 2.3 Security Vault: Headless KDBX & TOTP Engine (`helium314.keyboard.security.kdbx`)
* **Headless Engine**: Lightweight Kotlin KDBX 3.x/4.x parser (pure headless engine without heavy UI bloat) supporting Argon2id, AES-KDF, ChaCha20, and Twofish.
* **File Binding**: Android Storage Access Framework (SAF) URI persisted via `takePersistableUriPermission`.
* **TOTP Engine**: Pure Kotlin RFC 6238 implementation (HMAC-SHA1/SHA256, 30s period, 6/8 digits) with live timestamp drift compensation.
* **Settings CRUD**: Under `Settings -> Security -> Security Vault`:
  - **Folder (Group) Management**: Create, Rename, Move, and Delete folders.
  - **Entry Management**: Create, Edit, Delete entries (Title, Username, Password, URL/Package, TOTP Seed, Attachments placeholder).
* **In-Keyboard Explorer Modal (Keyboard Height)**:
  - **Toolbar**: Filter & sort pills (`All`, `Recent`, `Folder`). No search bar (eliminates recursive IME typing bugs).
  - **Body**: Scrollable list of expandable folder nodes and entry cards.
  - **Entry Card**: Title (bold), Username (subtle), and dynamic circular TOTP countdown ring if 2FA is present.
* **Chosen Entry Modal (Action Deck, Keyboard Height)**:
  - **Header**: Centered read-only context bubble (`Title • Username`) + tiny stacked symbol buttons (`[Lock]` and `[Back to Vault]`).
  - **Action Row**: 4 symbol-only high-touch buttons:
    1. `[User Icon]` -> Injects username.
    2. `[Key Icon]` -> Injects password.
    3. `[Clock/TOTP Icon]` -> Live 30s circular countdown progress ring; tap injects 6-digit TOTP code.
    4. `[Paperclip Icon]` -> Attachment viewer/extractor placeholder.
  - **Bottom Dock**: 4 standard utility keys (`[ABC]`, `[Space]`, `[Backspace]`, `[Enter]`).

### 2.4 Modular Backup & Restore (`helium314.keyboard.settings.preferences`)
* **Interactive Selection Sheet**:
  - `[x] Keyboard Settings & Styling`
  - `[x] Custom Layouts & Shortcuts`
  - `[x] Quick Notes (Prompt List)`
  - `[ ] Privacy Vault (Requires Pattern Unlock)` 🔒
  - `[ ] Security Vault Cache & DB Link (Requires Pattern Unlock)` 🔒
  - `[ ] Offline Voice Input Models & Custom Vocab (Future Slot)` 🎙️
* **Pattern Gate**: Ticking Privacy Vault or Security Vault immediately invokes a full-screen Pattern Unlock verification before granting inclusion into the encrypted backup payload.

### 2.5 Log Keeper Sanitization Protocol (`helium314.keyboard.latin.utils.LogCatcher`)
* **Zero-PII Guarantee**: Never log credentials, passwords, secret phrases, usernames, TOTP secrets, or pattern coordinates.
* **Allowed Telemetry**: Sanitized event status codes only:
  - `[INFO] SecurityVault: KDBX database loaded successfully (entries=N)`
  - `[INFO] SecurityVault: TOTP generated for entry hash=XXXX`
  - `[WARN] SecurityVault: Decryption error (corrupted payload or invalid master key)`
  - `[INFO] PrivacyVault: Pattern unlock session verified (duration=300s)`
  - `[INFO] PrivacyVault: Masked suggestion matched (trigger_len=N)`

---

## 3. Phased Implementation Roadmap

### Phase 22: Security Infrastructure & Pattern Unlock Engine
- [ ] Create `helium314.keyboard.security` package hierarchy.
- [ ] Build `PatternLockView.kt` (3x3 grid within keyboard height with theme-aware drawing).
- [ ] Build `VaultSessionManager.kt` handling independent Keystore-backed session clocks (5m Privacy / 3m Security).
- [ ] Create `SecurityScreen.kt` in Jetpack Compose Settings (Pattern setup, timeout configurations, biometric fallback).
- [ ] Connect sanitized action telemetry to `LogCatcher.kt`.

### Phase 23: Privacy Vault (Personal Dictionary Twin)
- [ ] Create `privacy_vault_dictionary` table and `PrivacyVaultDao.kt` in local Room/SQLite database.
- [ ] Replicate Personal Dictionary Compose UI under `Settings -> Security -> Privacy Vault` (`PrivacyVaultListScreen.kt`, `PrivacyVaultAddWordScreen.kt`).
- [ ] Implement masked suggestion generator (`jo****om`) and integrate with `Suggest.java` / `SuggestionStripView.kt`.
- [ ] Hook tap-to-unlock -> direct text injection flow, bypassing learning dictionaries and clipboard history.

### Phase 24: Security Vault Core (Headless KDBX & TOTP)
- [ ] Integrate headless KDBX 3.x/4.x parser and keystore session credentials.
- [ ] Implement RFC 6238 TOTP generator with circular timer tick dispatch.
- [ ] Build Settings UI for Folder (Group) CRUD and Entry CRUD in `SecurityVaultScreen.kt`.
- [ ] Implement SAF database file binding and auto-save on commit.

### Phase 25: In-Keyboard Security Vault Modals
- [ ] Register `KeyCode.SECURITY_VAULT`, `Utility.SECURITY_VAULT`, `KeyboardState.Mode.SECURITY_VAULT`.
- [ ] Create `SecurityVaultExplorerView.kt` (foldable folders, sort pills, entry list with live TOTP countdown rings).
- [ ] Create `ChosenEntryView.kt` (read-only title bubble, stacked lock/back buttons, 4 symbol actions: User, Key, TOTP, Attachment, and 4-key bottom dock).
- [ ] Integrate views into `KeyboardSwitcher.java` with responsive keyboard-height bounds.

### Phase 26: Modular Backup & Restore Integration
- [ ] Update `BackupRestorePreference.kt` with interactive multi-select checkboxes.
- [ ] Implement pattern verification gate before reading Privacy/Security vaults.
- [ ] Add future-ready toggle slot for Offline Voice Input data.
- [ ] Full end-to-end compilation, regression checks, and on-device test verification.
