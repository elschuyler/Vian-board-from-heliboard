# Phased Execution Plan: Security Vault Ecosystem (KeePassDX Model)

## Executive Summary
This execution plan defines the phased engineering roadmap for implementing VianBoard's **Security Vault** subsystem. Built on the KeePassDX Magikeyboard model, it enforces strict separation between the **In-Board View & Paste Engine** (lightweight, on-demand, <35MB RAM) and the **Settings Management Hub** (Kotpass KDBX parser, full CRUD, 2-way sync with Visual Diff).

The roadmap is strictly staged from the data/crypto foundation outward to eliminate mock data, avoid regressions, and enable independent on-device verification at every milestone.

---

## System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PHASE 3 & 4: IN-BOARD UI                        │
│   • SecurityVaultExplorerView (Full-height, foldable tree, sort/pills) │
│   • ChosenEntryView (Compact ~140dp, 5-action row, drop-up menus)      │
│   • StealthPatternOverlay (9 QWERTY keys: E,T,U / D,G,J / C,B,M)       │
│   • SuggestionStripView (Lightweight [🔑 user] / [🔑 dropdown] pills)  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Fast SQLite queries + TOTP (<1ms)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    PHASE 1: CORE DATA & CRYPTO FOUNDATION              │
│   • SQLite Sandbox Database (no_backup/security_vault.db)              │
│   • AndroidKeyStore AES-256-GCM Hardware Key Escrow                    │
│   • Pure-Kotlin RFC 6238 TOTP Engine (Live 30s countdown ring)         │
│   • Independent Session Clocks (3m Security vs 5m Privacy)             │
└───────────────────────────────────▲────────────────────────────────────┘
                                    │ Full CRUD & Sync Control
                                    │
┌────────────────────────────────────────────────────────────────────────┐
│                     PHASE 2: SETTINGS HUB & KDBX ENGINE                │
│   • Kotpass KDBX 3.1 & 4.x Headless Engine (Argon2 / AES / ChaCha)     │
│   • SAF File Picker (OpenDocument) + Master Password Onboarding        │
│   • Full Folder & Entry CRUD (1:1 native KDBX UUID hierarchy)          │
│   • Standalone Password Generator & NIST Entropy Meter                 │
│   • 2-Way Sync Engine with Conflict Detection & Interactive Diff Sheet │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Core Data & Crypto Foundation [COMPLETED]

### Objectives
Establish the encrypted local storage layer, hardware keystore escrow, and live TOTP computation engine without any UI dependencies or keyboard bloat.

### Tasks
- [x] **Room / SQLite Sandbox Database (`no_backup/security_vault.db`)**:
  - Create package `helium314.keyboard.security.vault.data`.
  - Define `VaultGroupEntity`:
    - `group_uuid` (TEXT PRIMARY KEY, 16-byte UUID hex)
    - `parent_group_uuid` (TEXT nullable)
    - `name` (TEXT NOT NULL)
    - `icon_id` (INTEGER DEFAULT 0)
  - Define `VaultEntryEntity`:
    - `entry_uuid` (TEXT PRIMARY KEY, 16-byte UUID hex)
    - `group_uuid` (TEXT NOT NULL, Foreign Key $\rightarrow$ `VaultGroupEntity`)
    - `title` (TEXT NOT NULL)
    - `username` (TEXT)
    - `password_encrypted` (BLOB)
    - `url_or_package` (TEXT)
    - `totp_secret_encrypted` (BLOB)
    - `notes_encrypted` (BLOB)
    - `updated_at` (INTEGER NOT NULL)
  - Define `VaultAttachmentEntity`:
    - `attachment_uuid` (TEXT PRIMARY KEY)
    - `entry_uuid` (TEXT NOT NULL, Foreign Key $\rightarrow$ `VaultEntryEntity`)
    - `filename` (TEXT NOT NULL)
    - `mime_type` (TEXT)
    - `data_blob_encrypted` (BLOB)
  - Implement `SecurityVaultDatabase.kt` and `SecurityVaultDao.kt` with fast indexed queries (`findByPackageOrUrl`, `getEntriesByGroup`, `getRecentEntries`, `getAllGroups`).
- [x] **Hardware Keystore Escrow (`AndroidKeyStore`)**:
  - Implement `VaultCryptoManager.kt`:
    - Generates/retrieves master AES-256-GCM hardware key under alias `"vianboard_security_vault_master"`.
    - Encrypts/decrypts sensitive field byte arrays (`password`, `totp_secret`, `notes`, `attachments`).
    - Explicit memory zeroization utility (`zeroize(charArray)` / `zeroize(byteArray)`).
- [x] **RFC 6238 TOTP Engine**:
  - Implement `TotpGenerator.kt`:
    - Pure Kotlin implementation of HMAC-SHA1, HMAC-SHA256, and HMAC-SHA512.
    - Decodes raw Base32 secret strings and parses `otpauth://totp/...` URIs.
    - Computes live tokens with current 6/8-digit code, remaining seconds (0–30s), and progress fractions.
- [x] **Telemetry Sanitization**:
  - Connect sanitized event codes to `LogCatcher` (e.g. `VAULT_DB_INITIALIZED`, `TOTP_GENERATED`). Enforce zero logging of plaintext secrets or PII.

### Verification Milestone
- Unit verification of AES-256-GCM encrypt/decrypt cycles with immediate memory wiping.
- RFC 6238 test vectors validated against standard Google Authenticator / KeePass test tokens.
- SQLite queries benchmarked at <1ms.

---

## Phase 2: Settings Hub & KDBX Onboarding [COMPLETED]

### Objectives
Provide the user interface in `SettingsActivity2` to import external `.kdbx` files, enter master passwords once, manage credentials with full CRUD, generate strong passwords, and execute 2-way sync with interactive visual diffs.

### Tasks
- [x] **Kotpass KDBX Parser & Serializer Integration**:
  - Add `kotpass` dependency to `app/build.gradle.kts`.
  - Implement `KdbxRepository.kt` in `helium314.keyboard.security.vault.engine`:
    - Reads external `.kdbx` (v3.1, v4.0, v4.1) input streams via SAF URI.
    - Unpacks groups and entries, mapping them 1:1 to `VaultGroupEntity` and `VaultEntryEntity`.
    - Serializes SQLite cache back into KDBX format using `KeePassDatabase.Ver4x.create()` and `encode(outputStream)`.
- [x] **KDBX File Selection & Master Key Onboarding Screen**:
  - In `SettingsNavHost.kt`, route `SettingsDestination.SecurityVault` to `SecurityVaultScreen`.
  - Implement `SecurityVaultScreen.kt`:
    - SAF file picker card using `ActivityResultContracts.OpenDocument()`.
    - Master password dialog with toggleable visibility and hardware escrow.
    - Status card showing database name, entry count, last sync timestamp, and `[Sync Now]` button.
- [x] **Hierarchical Folder & Entry CRUD**:
  - Implement accordion group navigation and full entry editor dialogs in `SecurityVaultScreen.kt`:
    - Accordion tree matching native KDBX groups 1:1.
    - Add, edit, duplicate, and delete entries: Title, Username, Password (with visibility toggle), URL / Package Name, TOTP secret string, and Notes.
    - Group creation dialog.
- [x] **Standalone Password Generator & NIST Entropy Meter (Settings Only)**:
  - Implement `PasswordGenerator.kt` and generator dialog in Compose:
    - Length slider (8–64 chars), character set toggles (A-Z, a-z, 0-9, special), ambiguity filter (`1, l, I, 0, O`), passphrase word count.
    - Real-time bit-entropy meter with NIST strength categorization (Weak, Fair, Strong, Unbreakable) and copy button.
- [x] **2-Way Sync Engine with Conflict Detection & Visual Diff**:
  - Implement `KdbxSyncManager.kt`:
    - External timestamp audit (checks if desktop KeePassXC / mobile KeePassDX modified the file externally).
    - Generates diff model: `+ Added`, `~ Modified` (field-level comparison), `- Deleted`, `⚠️ Conflict`.
  - Implement interactive Visual Diff Bottom Sheet in `SecurityVaultScreen.kt`:
    - Color-coded diff badges for Added (green), Modified (amber), Deleted (red), Conflict (purple).
    - Commit occurs **only** when user taps `[Confirm & Sync]`.

### Verification Milestone
- User can import a real `.kdbx` file on device, view their exact folder hierarchy and entry titles, edit an entry, and verify that changes sync back to the original file with accurate diff highlights.
- Database remains permanently accessible in `no_backup/` across device reboots.

---

## Phase 3: In-Board View & Paste Modals

### Objectives
Build the two in-keyboard modals (full-height Explorer and compact Chosen Entry deck) with persistent multi-field input, anchored drop-ups, and zero background memory footprint.

### Tasks
- [x] **Modal 1: Security Vault Explorer (`SecurityVaultExplorerView.kt`)**:
  - Layout: `security_vault_explorer_view.xml`:
    - Height: Exactly matches `ResourceUtils.getKeyboardHeight()`.
    - Top bar: Horizontal layout with filter pills (`All`, `Recent`, `Folders`), sort toggle, and top-right `[🔒 Lock]` (close & lock) and `[✕ Close]` (close without lock) buttons.
    - Body: `RecyclerView` with `AccordionFolderAdapter`.
      - Folders: Expand/collapse indicator (`▶` / `▼`), folder icon, folder name, child count.
      - Entries: Key icon, bold Title over subtle Username, and `TotpCircleProgressView` with live countdown sweep.
    - Tap entry transitions to Modal 2.
- [x] **Modal 2: Chosen Entry Modal (`ChosenEntryView.kt`)**:
  - Layout: `chosen_entry_view.xml`:
    - Height: Compact profile (~140dp–150dp, ~60% height) via custom `onMeasure()`.
    - **Constructed bottom-up**:
      - **Row 1 (Bottom)**: Standard 4 utility keys (`[ABC]`, `[Space]`, `[⌫]`, `[↵]`).
      - **Row 2 (Middle - 5 Action Buttons)**:
        1. `[📝 Note]`: Anchored drop-up popup with scrollable note text and direct copy button. Dimmed (alpha 0.45) if no notes.
        2. `[👤 Username]`: Injects username directly into active field.
        3. `[🔑 Password]`: Injects password directly without clipboard reliance (zeroes CharArray).
        4. `[⏱️ TOTP]`: Live 30s circular countdown progress ring; tap injects 6-digit TOTP code.
        5. `[📎 Attachment]`: Anchored drop-up menu listing attached binary files with copy/view actions. Dimmed if no attachments.
      - **Row 3 (Top Row)**:
        - Left: Two controls (`[🔒 Lock]` and `[↩ Back]`).
        - Right: Title and Username text (`ellipsize="end"`, single-line).
- [x] **Persistent Multi-Field Input Workflow**:
  - In `ChosenEntryView.kt`, text injection executes via `KeyboardActionListener` without closing the modal.
  - Allows sequential filling: Username $\rightarrow$ Enter/Next $\rightarrow$ Password $\rightarrow$ TOTP.
  - Modal dismisses **only** when user taps `[ABC]` or `[🔒 Lock]`.
- [x] **`KeyboardSwitcher.java` Wiring**:
  - Add `setSecurityVaultExplorerKeyboard()` and `setChosenEntryKeyboard()`.
  - Connect long-press on `?123` to challenge pattern unlock and open Explorer modal.
  - Enforce Lite Mode single-modal lifecycle: unloads views from RAM when hidden.

### Verification Milestone
- Long-pressing `?123` opens the full-height Explorer modal on device with smooth accordion folder expansion.
- Tapping an entry opens the compact Chosen Entry deck (~140dp).
- Tapping Username commits text, tapping Enter moves focus, and tapping Password commits password without closing the modal.
- Tapping `[ABC]` cleanly restores the alphabet keyboard.

---

## Phase 4: Stealth Gatekeeper & Context Suggestion Pills

### Objectives
Implement the stealth pattern unlock disguised over normal QWERTY keys, independent session timers, and lightweight context-aware suggestion strip pills.

### Tasks
- [x] **Stealth Disguise Pattern Unlock (`StealthPatternKeyboardOverlay.kt`)**:
  - Intercepts touch events on standard QWERTY alphabet keyboard when an unlock challenge is triggered.
  - Maps 3x3 pattern matrix to 9 tactile anchor keys:
    ```
    Row 1: [Q] [W] (E)1 [R] (T)2 [Y] (U)3 [I] [O] [P]
    Row 2:  [A] [S] (D)4 [F] (G)5 [H] (J)6 [K] [L]
    Row 3:    [Z] [X] (C)7 [V] (B)8 [N] (M)9 [⌫]
    ```
  - 28dp touch catchment radius around each anchor.
  - Dispatches `HapticFeedbackConstants.CLOCK_TICK` on each node entered.
  - Zero visual lines, zero glowing trails, zero modal shift.
  - Silent unlock on success; muted vibration on failure.
- [x] **Independent Dual-Vault Session Clocks**:
  - In `VaultSessionManager.kt`:
    - Privacy Vault session clock: 5 minutes.
    - Security Vault session clock: 3 minutes.
    - Strictly independent: Unlocking Privacy Vault never unlocks Security Vault, and vice versa.
- [x] **Lightweight Context Sniffer & Suggestion Strip Pills**:
  - In `SecurityVaultContextSniffer.kt`, `SuggestionStripView.kt`, and `LatinIME.java`:
    - Evaluates `editorInfo.packageName` (native apps) and `editorInfo.hintText` / browser hints on `onStartInputView`.
    - Single match: Displays direct `[🔑 alice@example.com]` pill at strip index 0.
    - Multiple matches: Displays dropdown `[🔑 2 Accounts ▼]` pill; tap reveals horizontal account list.
    - Tapping pill checks session validity $\rightarrow$ triggers Stealth Pattern Unlock if locked $\rightarrow$ commits credential directly into the active field.
- [x] **Backup & Restore and LogCatcher Integration**:
  - `ModularBackupEngine.kt` fully connected to `security_vault.db` sandbox export and restoration with clean instance resetting.
  - Full diagnostic logging via `LogCatcher` with strict zero-PII sanitization.

### Verification Milestone
- Focusing on a known app (e.g. GitHub, Twitter) immediately displays the matching account pill on the suggestion strip.
- Tapping the pill prompts for Stealth QWERTY unlock with subtle haptic ticks, unlocking the vault with zero visual tell.
- Privacy Vault and Security Vault session timers operate independently without cross-leakage.

---

## Phase 5: Hardening, Telemetry Audit & Memory Reclamation

### Objectives
Ensure production-grade stability, zero memory leaks, strict Lite Mode compliance, and verified zero-PII telemetry.

### Tasks
- [ ] **Memory Reclamation & Lite Mode Audit**:
  - Verify `KeyboardSwitcher.deallocateMemory()` and `enforceLiteModeSingleModal()` cleanly destroy `SecurityVaultExplorerView` and `ChosenEntryView` when inactive.
  - Verify all decrypted `CharArray` instances are zeroized immediately after `commitText()`.
  - Profile keyboard heap usage to confirm RAM stays <35MB during active typing.
- [ ] **LogCatcher Telemetry Verification**:
  - Audit all log calls across `helium314.keyboard.security`:
    - Confirm zero passwords, usernames, TOTP secrets, notes, URLs, attachment bytes, or pattern coordinates are logged.
    - Confirm only sanitized event status codes are recorded.
- [ ] **Receipts Audit Logging**:
  - Append finalized milestone entries to `/receipts/RECEIPTS_003.md`.

### Verification Milestone
- Full end-to-end regression suite passing cleanly with zero crashes, zero ANRs, zero memory leaks, and 100% compliant receipts audit trail.
