# Architecture & System Specification: Security Vault Ecosystem (KeePassDX Magikeyboard Model)

## 1. Executive Summary & Core Principles

This specification defines the complete technical architecture for VianBoard's **Security Vault**, designed on the **KeePassDX Magikeyboard** pattern. The subsystem provides an offline, encrypted, hardware-isolated credential repository with dual ingress:

1. **In-Board Security Vault (Keyboard Interface)**: Purely a **View & Paste Engine** (zero CRUD). It does **NOT** bundle or run the heavy KDBX engine (keeps keyboard process <35MB RAM). It contains only a fast read-only SQLite cache helper, lightweight context heuristics, a pure Kotlin RFC 6238 TOTP generator, a full-height scrollable **Vault Explorer** with foldable accordion folders, and a compact **Chosen Entry Modal** (~140dp–150dp) with persistent multi-field filling, a dedicated 5-button action row separating Notes and Attachments, vertically stacked lock/back buttons, and a 4-button bottom utility dock.
2. **Settings Vault Manager (Compose Interface)**: The exclusive host for the **Full KDBX Engine** (Kotpass parser/serializer, Argon2id KDF, SAF file I/O), **Full CRUD** (creating, editing, deleting, moving, and organizing entries and hierarchical folder groups), the **2-Way Sync Engine with Visual Diff & Conflict Detection**, and the standalone **Password Generator & Entropy Strength Meter**.

---

## 2. System Architecture: The Decoupled Engine Split

```
┌────────────────────────────────────────────────────────────────────────┐
│                          1. IN-BOARD MODALS                            │
│                       (Pure View & Paste Engine)                       │
├────────────────────────────────────────────────────────────────────────┤
│ • SecurityVaultExplorerView: Full-height, foldable tree, sort/filters  │
│ • ChosenEntryView: Compact ~140dp, bottom-up dock, stays open for input│
│ • 5-Action Row: [📝 Note] [👤 User] [🔑 Pass] [⏱️ TOTP] [📎 Attach]    │
│ • Note Drop-Up: Anchored popup with Note text & Copy to Clipboard      │
│ • Attachment Drop-Up: Anchored list of attached binary files           │
│ • SuggestionStrip: Lightweight [🔑 user] / [🔑 dropdown] pills         │
│ • StealthPatternOverlay: 9 QWERTY keys (E,T,U / D,G,J / C,B,M)         │
│ • In-Board Engine: Fast SQLite read-only query + RFC 6238 TOTP ONLY    │
│   (Zero Kotpass/Argon2 overhead in keyboard process)                   │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │ Query & Decrypt (<1ms)
                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│                               2. ENGINE                                │
│                     (Storage, Crypto, Sync & TOTP)                     │
├────────────────────────────────────────────────────────────────────────┤
│ • Settings-Only KDBX Engine: Kotpass parser/serializer (Argon2 / AES)  │
│ • Hardware Escrow: AndroidKeyStore (AES-256-GCM hardware key)          │
│ • Local Sandbox Cache: SQLite (no_backup/security_vault.db)            │
│ • RFC 6238 TOTP Engine: Pure Kotlin live ticker (30s countdown ring)   │
│ • 2-Way Diff Engine: UUID comparator (+Added, ~Mod, -Del, Conflict Chk)│
└────────────────────────────────────▲───────────────────────────────────┘
                                     │ Full CRUD & Sync Control
                                     │
┌────────────────────────────────────────────────────────────────────────┐
│                           3. SETTINGS HUB                              │
│                      (Full CRUD Compose UI)                            │
├────────────────────────────────────────────────────────────────────────┤
│ • SecurityVaultScreen: SAF KDBX file picker & master password dialog   │
│ • Folder & Entry Manager: Add, Edit, Move, Delete (1:1 with KDBX tree) │
│ • Password Generator & NIST Entropy Meter: Character sets & passphrases│
│ • Visual Diff Sync Sheet: Interactive review before external SAF commit│
└────────────────────────────────────┴───────────────────────────────────┘
```

---

## 3. Authentication & Independent Session Clocks

### 3.1 Independent Dual-Vault Timers (`VaultSessionManager.kt`)
The Security Vault and Privacy Vault maintain completely independent hardware Keystore-backed session clocks:
* **Privacy Vault Session**: 5 minutes validity from last unlock.
* **Security Vault Session**: 3 minutes validity from last unlock.
* **Zero Cross-Contamination**:
  * Unlocking Privacy Vault (e.g. to reveal masked dictionary phrases `🔒 jo****om`) does **NOT** unlock the Security Vault.
  * Unlocking Security Vault does not reset or alter the Privacy Vault session clock.
  * When either session expires, its in-memory decrypted buffers are immediately zeroized (`Arrays.fill(charArray, '\u0000')`).

### 3.2 Master Password Import & Hardware Escrow
* User enters master password **once** upon importing the external `.kdbx` file via SAF in Settings.
* Master key is sealed inside Android's hardware-backed `AndroidKeyStore` (AES-256-GCM).
* Database entries are unpacked into a permanent internal SQLite cache (`no_backup/security_vault.db`).
* Data is permanently retained: **never reset, never lost across app restarts or device reboots**.
* Subsequent unlocks in keyboard or settings require only VianBoard's internal unlock (Standard Pattern or Stealth QWERTY Disguise).

---

## 4. In-Board Modals & Persistent Input Workflow

### 4.1 Modal 1: Security Vault Explorer (`SecurityVaultExplorerView`)
* **Height**: **Full normal keyboard height** (matches `ResourceUtils.getKeyboardHeight()`).
* **Scrollability**: Fully vertically scrollable `RecyclerView`.
* **Header / Filter Bar (Top)**:
  * **Pill Filters**: `[ Recently Used ]` `[ Common ]` `[ All ]`
  * **Sort Toggle (Right)**: Switch between `[ 🔤 Name (A–Z) ]` $\longleftrightarrow$ `[ 🕒 Time (Recent) ]`
  * **Zero Search Bar**: Eliminates recursive IME focus traps.
* **Body: Foldable Tree & Entry List**:
  * **Foldable Folders (Accordion Tree)**:
    * Collapsed: `[ ▶ 📁 Work Accounts (4) ]`
    * Expanded: `[ ▼ 📁 Work Accounts ]` (reveals indented child entries; tap again to fold).
    * Exact 1:1 mirror of native KDBX groups and entry Titles (zero twisting/flattening).
  * **Entry Items**:
    * **Left**: App/Site Favicon or Key Icon `[ 🔑 ]`.
    * **Center**: Bold Title over subtle Username.
    * **Right**: If TOTP is configured, an **animated circle showing live countdown timer** (e.g. `(⏱️ 14s)`).
* **Selection**: Tapping an entry transitions smoothly to Modal 2.

```
┌────────────────────────────────────────────────────────────────────────┐
│ [ Recently Used ]  [ Common ]  [ All ]           [ 🔤 Name | 🕒 Time ]│
├────────────────────────────────────────────────────────────────────────┤
│ ▼ 📁 Personal Vault                                       [Scrollable] │
│   🔑 ProtonMail       privacy_alice@proton.me                          │
│   🔑 GitHub           alice-dev                         (⏱️ 18s)       │
│ ▶ 📁 Work Accounts (3)                                                 │
│ ▶ 📁 Finance & Banking (2)                                             │
│ 🔑 Wi-Fi Guest Pass   guest_vian                                       │
└────────────────────────────────────────────────────────────────────────┘
```

---

### 4.2 Modal 2: Chosen Entry Modal (`ChosenEntryView`) & Persistent Flow
* **Height**: **Smaller than normal keyboard** (~140dp–150dp compact profile, ~60% height). Keeps host app input fields visible.
* **Persistent Input Workflow (No Auto-Dismiss)**:
  * Tapping `[👤 Username]`, `[🔑 Password]`, `[⏱️ TOTP]`, `[📝 Note]`, or `[📎 Attachment]` directly commits text to the active field or opens drop-ups, but **does NOT close the modal**.
  * Enables seamless multi-field form completion (Username $\rightarrow$ Next Field $\rightarrow$ Password $\rightarrow$ TOTP) without re-opening the vault.
  * Modal closes **only** when user explicitly taps `[ ABC ]` on the bottom dock (returns to normal typing) or `[ 🔒 Lock ]` (locks vault and closes).
* **Layout Structure**: Constructed strictly **from the bottom up**:

```
                       ┌────────────────────────┐
                       │ 📝 Note: PIN is 4821   │
                       │ ────────────────────── │
                       │ 📋 Copy to Clipboard   │
                       └──────────▲─────────────┘
┌─────────────────────────────────┼──────────────────────────────────────┐
│ [🔒 Lock]   GitHub              │                                      │
│ [↩ Back]   alice-dev           │ (Note Drop-Up)                       │
├─────────────────────────────────┴──────────────────────────────────────┤
│ ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│ │    📝    │  │    👤    │  │    🔑    │  │   ⏱️     │  │    📎    │   │
│ │  (Note)  │  │  (User)  │  │  (Pass)  │  │  (TOTP)  │  │ (Attach) │   │
│ └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
├────────────────────────────────────────────────────────────────────────┤
│ [   ABC   ]     [      Space      ]     [     ⌫     ]     [    ↵    ]   │
└────────────────────────────────────────────────────────────────────────┘
```

* **Row 1 (Bottom - Standard 4 Utility Keys)**:
  * Uses the app's standard bottom utility row (`KeyboardElement.CLIPBOARD_BOTTOM_ROW`):
    * `[ ABC ]`: Closes modal and returns to normal alphabet typing.
    * `[ Space ]`: Inputs a space character.
    * `[ ⌫ Backspace ]`: Deletes previous character.
    * `[ ↵ Enter ]`: Advances to next field or submits form.
* **Row 2 (Middle - 5 Action Buttons with Separate Note & Attachment)**:
  1. `[ 📝 Note ]` **(In front of Username)**:
     * Tapping opens an anchored drop-up popup displaying the entry's `<Key>Notes</Key>` multi-line text (scrollable if long) with a direct **`[ 📋 Copy to Clipboard ]`** button (notes are safe for standard clipboard copy).
     * Dimmed (50% opacity) if entry has no notes.
  2. `[ 👤 Username ]`: Injects username directly via `InputConnection.commitText()`. Modal stays open.
  3. `[ 🔑 Password ]`: Injects password directly without clipboard reliance. Modal stays open.
  4. `[ ⏱️ TOTP ]`: Encircles icon with live 30s countdown ring; tapping injects current 6-digit TOTP code. Modal stays open.
  5. `[ 📎 Attachment ]`: Tapping opens an anchored drop-up menu listing attached binary files (e.g. `id_rsa.pub`, `cert.pem`) with direct copy/view options. Dimmed (50% opacity) if entry has no file attachments.
  * **Touch Target Ergonomics**: Width is distributed evenly across 5 buttons (~72dp–84dp per button), well exceeding the Material Design 3 minimum 48dp target.
* **Row 3 (Top Row - Header & Controls)**:
  * **Left Side (2 Tiny Buttons Vertically Stacked)**:
    * Top Button: `[ 🔒 Lock ]` -> Immediate vault lock, closes modal, purges memory.
    * Bottom Button: `[ ↩ Back ]` -> Returns to Modal 1 (Security Vault Explorer).
    * Enhanced touch-delegate hitboxes prevent accidental lockout.
  * **Right Side (Text Only)**:
    * Title and Username of the selected entry (`ellipsize="end"`, single-line).

---

### 4.3 Stealth Disguise Pattern Unlock (QWERTY Overlay)
* **Key Mapping & Geometry**: 3x3 pattern mapped directly to 9 tactile QWERTY anchor keys:
  ```
  Row 1: [Q] [W] (E)1 [R] (T)2 [Y] (U)3 [I] [O] [P]
  Row 2:  [A] [S] (D)4 [F] (G)5 [H] (J)6 [K] [L]
  Row 3:    [Z] [X] (C)7 [V] (B)8 [N] (M)9 [⌫]
  ```
  * **Node 1**: `E` | **Node 2**: `T` | **Node 3**: `U`
  * **Node 4**: `D` | **Node 5**: `G` | **Node 6**: `J`
  * **Node 7**: `C` | **Node 8**: `B` | **Node 9**: `M`
* **Zero Visual Change**: Keyboard retains exact normal appearance. Height remains 100% constant, zero layout shift, zero glowing trails, zero highlighted keys.
* **Haptic Anchors**: Subtle tactile tick (`HapticFeedbackConstants.CLOCK_TICK`) fires whenever touch enters the 28dp catchment radius of an anchor node.
* **Silent Outcome**: Unlocks vault session silently on success; muted single vibration on failure without alert dialogs.

---

### 4.4 Lightweight Context Recognition & Suggestion Strip Pills
Lightweight heuristics ("more or less is good" — zero heavy/brittle DOM scraping):
* **Native Apps**: Matches active `editorInfo.packageName` (e.g. `com.github.android`).
* **Web Browsers**: Evaluates web domain hints from `editorInfo.hintText`, `editorInfo.fieldName`, or basic host package match.
* **Suggestion Strip Pills**:
  * **Single Match**: Displays direct pill `[🔑 alice@example.com]`.
  * **Multiple Matches**: Displays a compact dropdown pill `[🔑 2 Accounts ▼]`. Tapping presents an inline horizontal list of matching accounts directly on the strip.
* **Tap-to-Fill**:
  * If unlocked: Directly commits username or password via `InputConnection.commitText()`.
  * If locked: Engages Pattern Unlock (Standard or Stealth Disguise), then immediately commits the credential.

---

## 5. Settings Hub: Full KDBX Engine & Utilities

All heavy operations and file mutations are sequestered in `SettingsActivity2` under `Settings -> Security -> Security Vault`:

### 5.1 Headless KDBX Engine (Kotpass)
* Full KDBX 3.1 & 4.x parser and serializer runs exclusively in Settings.
* Handles Argon2id KDF derivation, XML tree manipulation, and SAF file output streams.

### 5.2 Hierarchical Folder & Entry CRUD
* **Folder (Group) Management**: Create, rename, move, and delete hierarchical folders (1:1 native KDBX UUIDs).
* **Entry Management**: Create, edit, duplicate, and delete entries (Title, Username, Password, URL / Package, TOTP Secret String, Notes, Attachments).

### 5.3 Password Generator & Strength Meter (Settings CRUD Only)
* Embedded directly inside the New/Edit Entry CRUD dialog under the Password field (zero keyboard RAM bloat):
  * Direct `[ 🎲 Generate ]` button that rolls a fresh password straight into the active field.
  * Toggles: Uppercase (`A-Z`), Lowercase (`a-z`), Digits (`0-9`), Special Characters (`!@#$%^&*`).
  * Length Slider (8 to 64 characters) & Ambiguity Filter (`1, l, I, 0, O`).
  * Multi-word passphrase generator with word count slider.
  * Real-time bit-entropy calculation and NIST rating (Weak, Fair, Strong, Unbreakable).

### 5.4 2-Way Sync Engine with Visual Diff & Conflict Detection
1. **Timestamp & Conflict Check**: Checks if the external `.kdbx` file has been modified externally since last sync.
2. **Visual Diff Inspection**: Interactive bottom sheet showing `+ Added` (green), `~ Modified` (amber), `- Deleted` (red), and conflict warnings.
3. **User Confirmation**: Commits to external SAF URI **only upon explicit user confirmation**.

---

## 6. Telemetry & LogCatcher Contract

* **Monitored Engine Events**: Database open/close, session timeouts, SAF URI persist/release, TOTP ticks, match counts, sync status.
* **Zero-PII Guarantee**: Never log passwords, usernames, TOTP secrets, notes, URLs, attachment data, or pattern coordinates. Entries referenced by hashed identifiers only.
