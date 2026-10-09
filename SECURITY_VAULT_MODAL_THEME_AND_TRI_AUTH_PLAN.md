# Security Vault Modal, Sky Blue Theme & Tri-Method Authentication Plan
**Phase Target**: Phase 46  
**Document Version**: 1.0.0  
**Status**: DRAFT / READY FOR USER REVIEW  

---

## 1. Executive Summary & Objective

This execution plan provides a surgical, comprehensive blueprint to implement the agreed-upon enhancements across the Security Vault, Settings Architecture, Visual Styling, and Multi-Modal Authentication Pipeline:

1. **Explorer Topbar Pills & Sort Toggle**: Implement rounded outline drawables for inactive pills, solid Material 3 container fill for active pills, and a matching circular outline for `btn_sort_toggle`.
2. **Modal Inset & Button Verification**: Confirm explorer modal retains zero credential action buttons (strictly isolated to Chosen Entry), and flatten the root folder row so child folders and root entries display immediately without wasting vertical IME viewport height.
3. **Tri-Method Authentication Architecture**: Expand beyond pattern unlock to support **3 distinct unlock methods**:
   - **Method 1: Pattern Unlock** (Default; fast in-IME 3x3 tactile grid and stealth QWERTY mode).
   - **Method 2: Biometric Authentication** (Fingerprint / Face Unlock via Android `BiometricPrompt`).
   - **Method 3: Phone PIN / Password** (Device Lock Screen Credential via `KeyguardManager` or dedicated secure PIN).
   - Configurable **Primary** and **Backup** fallback assignment.
4. **Settings Security Page Restructuring**: Neatly organize `SecurityScreen.kt` into three dedicated sections:
   - **Section 1: Privacy Vault**
   - **Section 2: Security Vault**
   - **Section 3: Unlock Mechanism & Security Gatekeeper** (Settings gatekeeper switch, 3-method selector, and unified vs independent target binding).
5. **Settings Material Theme Overhaul**: Replace the yellow/ochre dynamic palette in `Theme.kt` with a custom **Material 3 Light Sky Blue** palette across all settings screens, while strictly preserving the keyboard IME theme without modification.
6. **KeePassDX KDBX Onboarding & Visual Parity**:
   - Support composite credentials: **Password + Key File** (`.key` / `.keyx`) via Kotpass.
   - Support custom string fields and TOTP formats so no KeePassDX metadata is lost.
   - Adopt KeePassDX-inspired card groupings, security badges, and descriptive subtitles.

---

## 2. Component Breakdown & Architectural Design

### Component 1: Explorer Modal Topbar Pills & Sort Toggle (`SecurityVaultExplorerView`)

#### Problem Statement
In `security_vault_explorer_view.xml` and `SecurityVaultExplorerView.kt`, the filter pills (`All`, `Recent`, `Folders`) are plain `TextView`s with `selectableItemBackground` and fake-bold styling. They lack background shapes, borders, and corner radii. `btn_sort_toggle` is a borderless button lacking visual alignment with the pills.

#### Architectural Solution
1. **Drawable Resources (`res/drawable/`)**:
   - `bg_vault_chip_inactive.xml`: Shape drawable with `corners radius="16dp"`, stroke `1.2dp` (using dynamic theme hint/outline color), and transparent/surface background.
   - `bg_vault_chip_active.xml`: Solid filled shape drawable with `corners radius="16dp"` (using theme primary accent / secondary container color).
   - `bg_vault_circle_button.xml`: Oval shape drawable with `1.2dp` stroke matching the chip outline, sized to `36dp x 36dp`.
2. **View Logic (`SecurityVaultExplorerView.kt`)**:
   - In `updateChipHighlight(activeChip)`:
     - Apply `bg_vault_chip_active.xml` (or dynamically tinted `GradientDrawable`) with high-contrast text to the selected chip.
     - Apply `bg_vault_chip_inactive.xml` with secondary text color to inactive chips.
   - In `applyThemeColors()`:
     - Assign `bg_vault_circle_button.xml` to `btn_sort_toggle`, tinting stroke and icon to match the keyboard palette.

---

### Component 2: Explorer Modal Layout & Root Folder Flattening

#### Problem Statement
In `AccordionFolderAdapter.kt` (`rebuildDisplayItems()`), the adapter iterates through all groups and adds a `ListItem.Folder(...)` header row for every group, including the database root group (typically `"Root"` or the `.kdbx` filename). Because the user is already inside the database, this header wastes 48–56dp of vertical screen space in a constrained IME window.

#### Architectural Solution
1. **Root Group Flattening**:
   - In `AccordionFolderAdapter.kt`: Identify the root group (`parentGroupUuid == null` or database root group).
   - When browsing in `ALL` or `FOLDERS` mode:
     - Display **child/subfolders** first as expandable accordion headers (`ListItem.Folder`).
     - Display the root group's own direct entries directly beneath subfolders as `ListItem.Entry` rows.
     - Omit the root folder header itself.
2. **Button Layout Verification**:
   - `SecurityVaultExplorerView`: Contains only the top bar, recycler view, and standard `bottom_row_keyboard` ([ABC], space, enter). Zero credential injection buttons.
   - `ChosenEntryView`: Retains the 5 credential action tiles (`Note`, `User`, `Password`, `TOTP`, `Files/Attachment`).

---

### Component 3: Tri-Method Authentication Architecture (Pattern, Biometric, Phone PIN)

#### The 3 Unlock Methods
1. **Method 1: Pattern Unlock (Default)**:
   - In-IME 3x3 tactile pattern grid (`PatternGridView`) and stealth QWERTY mode.
   - Zero permissions required, instant execution, completely offline.
2. **Method 2: Biometric Authentication**:
   - Standard Android `BiometricPrompt` supporting Fingerprint, Face, and Iris (`BIOMETRIC_STRONG` / `BIOMETRIC_WEAK`).
   - In `SettingsActivity`: Launched directly using `BiometricPrompt` bound to the `ComponentActivity` lifecycle.
   - In Keyboard IME: Since `InputMethodService` is a background service, launch a transparent, zero-animation trampoline activity (`BiometricAuthTrampolineActivity`) that prompts `BiometricPrompt` over the active window. Upon authentication success, it triggers `VaultSessionManager.startSecuritySession()` and calls `finish()`, returning focus to the IME immediately.
3. **Method 3: Phone PIN / App Password**:
   - **Phone PIN (Device Credential)**: Integrates with Android `KeyguardManager.createConfirmDeviceCredentialIntent()` or `BiometricPrompt` with `DEVICE_CREDENTIAL`. Uses the user's existing device lock screen PIN/password.
   - **App Custom PIN / Password**: Option for a secure 4-8 digit numeric PIN or alphanumeric master passphrase hashed via PBKDF2/SHA-256 with random salt.

#### Primary & Backup Mechanics
- **Primary Unlock Method**: User selects between [Pattern (Default), Biometric, Phone PIN / Password].
- **Backup Unlock Method**: When Biometric is chosen as primary, a fallback method (Pattern or PIN) is mandatory to ensure access if the sensor fails or fingers are wet.
- **Failover Trigger**: If biometric fails 3 times, biometric hardware is unavailable, or the user taps "Use Backup Method", the system seamlessly falls back to the configured backup.

#### 3-Way Target Binding (Gatekeeper, Privacy Vault, Security Vault)
- **Target 1: Settings Gatekeeper** (Protects access to Settings -> Security).
- **Target 2: Privacy Vault** (Protects private phrases and clipboard shortcuts in IME).
- **Target 3: Security Vault** (Protects KeePass KDBX credentials in IME).
- **Binding Modes**:
  - **Mode A (Unified Master Credential)**: All 3 targets share the same primary credential and unlock session.
  - **Mode B (Independent Credentials)**: Each target can be bound to its own independent credential (e.g. Biometric for Settings, Stealth Pattern for Security Vault, Quick PIN for Privacy Vault).

---

### Component 4: Settings -> Security Settings Page Reorganization (`SecurityScreen.kt`)

#### Revised Section Structure
Refactor `SecurityScreen.kt` into three distinct, card-grouped sections:

```
+-------------------------------------------------------------+
| SECTION 1: PRIVACY VAULT                                    |
| - Launch Privacy Vault Manager                              |
| - Unlock Binding: Unified Master vs Dedicated Privacy       |
| - Stealth Clipboard & History Security Options              |
+-------------------------------------------------------------+
| SECTION 2: SECURITY VAULT                                   |
| - Launch Security Vault Manager (KDBX, Folders, Sync)       |
| - Unlock Binding: Unified Master vs Dedicated Security      |
| - Auto-Fill Suggestion Pills Toggle                         |
| - KeePassDX Visual Configuration (Card style, badges)       |
+-------------------------------------------------------------+
| SECTION 3: UNLOCK MECHANISM & SECURITY GATEKEEPER           |
| - Require Unlock for Security Settings (Gatekeeper Toggle)  |
| - Primary Unlock Method: [Pattern (Default) | Biometric | PIN] |
| - Backup Unlock Method: [Pattern | PIN]                     |
| - Target Binding Mode: [Unified Master | Separate Vaults]   |
| - Configure Primary / Backup Credentials                    |
| - Lock All Vault Sessions Button                            |
+-------------------------------------------------------------+
```

---

### Component 5: Settings Material 3 Theme Overhaul (Light Sky Blue vs Yellow)

#### Root Cause Analysis
In `Theme.kt`, `dynamicLightColorScheme(LocalContext.current)` extracts the palette from the host OS wallpaper. In the cloud container emulator, this defaults to an ochre/yellow palette.

#### Architectural Solution
1. In `helium314/keyboard/latin/utils/Theme.kt`:
   - Replace dynamic wallpaper palette extraction with a dedicated **Material 3 Light Sky Blue** color scheme:
     ```kotlin
     val LightSkyBlueColorScheme = lightColorScheme(
         primary = Color(0xFF0288D1),           // Vibrant Light Sky Blue
         onPrimary = Color(0xFFFFFFFF),
         primaryContainer = Color(0xFFE1F5FE),  // Soft Sky Blue Tint
         onPrimaryContainer = Color(0xFF01579B),// Deep Navy Blue
         secondary = Color(0xFF0277BD),
         secondaryContainer = Color(0xFFE0F2F1),
         surface = Color(0xFFF8FAFD),           // Crisp Ice-White Surface
         onSurface = Color(0xFF191C1E),
         surfaceVariant = Color(0xFFE1E8ED),
         outline = Color(0xFF71787E)
     )

     val DarkSkyBlueColorScheme = darkColorScheme(
         primary = Color(0xFF4FC3F7),           // Glowing Light Sky Blue
         onPrimary = Color(0xFF00344F),
         primaryContainer = Color(0xFF004C71),
         onPrimaryContainer = Color(0xFFBBE9FF),
         surface = Color(0xFF0B131E),           // Deep Midnight Navy
         onSurface = Color(0xFFE1E2E5),
         surfaceVariant = Color(0xFF41474D)
     )
     ```
2. **Theme Isolation Boundary**:
   - `Theme.kt` wraps strictly `SettingsActivity`, `SettingsContainer`, and Compose settings screens.
   - The IME keyboard, suggestion strip, and modal popups continue using their own independent engine (`KeyboardTheme`, `ColorType`, `Settings.getValues().mColors`). **Zero changes to the keyboard appearance.**

---

### Component 6: KeePass KDBX Import Pipeline & Visual Parity

#### 1. Composite Master Credentials (Password + Key File)
- **Kotpass Integration**: Kotpass supports `Credentials.from(passphrase = ..., keyFile = ...)`.
- Update `KdbxRepository.kt` (`importFromUri` and `exportToUri`) to accept an optional `keyFileUri: Uri?`.
- In `SecurityVaultScreen.kt`: Add an optional **"Select Key File (.key / .keyx)"** SAF button inside the import dialog, enabling unlock of composite password + key file databases.

#### 2. Comprehensive Field Parsing
- Expand `KdbxRepository.traverseGroup()` to parse:
  - Custom string fields (`entry.fields` beyond the standard 5) and store them as JSON or in a custom fields table.
  - Advanced TOTP formats (`otpauth://totp/...`, custom time-step intervals and digit counts).
  - Binary attachments (saving binary metadata to `VaultAttachmentEntity`).

#### 3. KeePassDX Visual Parity in Settings
- Implement rounded M3 `OutlinedCard` / `Card` containers with 16dp radii for database info, security credentials, and sync settings.
- Add status badges (`KDBX 4.1`, `Argon2id`, `AES-256`, `Key File Bound`).
- Include informative 2-line descriptions on all settings toggles.

---

## 3. Implementation Phasing & Workstreams

| Phase | Title | Key Files | Verification |
|---|---|---|---|
| **Phase 46.1** | Explorer Modal Pills, Sort Button & Root Flattening | `security_vault_explorer_view.xml`, `SecurityVaultExplorerView.kt`, `AccordionFolderAdapter.kt` | UI check in IME, clean compile |
| **Phase 46.2** | Settings Sky Blue M3 Theme & Screen Restructuring | `Theme.kt`, `SecurityScreen.kt`, `colors.xml` | Visual check in Settings, verify no keyboard bleed |
| **Phase 46.3** | Tri-Method Auth Architecture (Pattern, Biometric, PIN) | `VaultSessionManager.kt`, `BiometricAuthTrampolineActivity.kt`, `PatternLockSettingsScreen.kt` | Authenticate via pattern, biometric, and fallback |
| **Phase 46.4** | KDBX Key File Import & KeePassDX Visual Styling | `KdbxRepository.kt`, `SecurityVaultScreen.kt`, `VaultEntities.kt` | Import password+keyfile KDBX, verify custom fields |

---

## 4. Verification & Testing Matrix

1. **Modal Visuals & Root Flattening**:
   - Verify active topbar pill has solid container background; inactive pills have rounded borders.
   - Verify `btn_sort_toggle` has circular matching border.
   - Verify Explorer modal displays child folders and root entries without showing a redundant root folder header row.
   - Verify Explorer modal has NO credential action buttons (only Chosen Entry has them).
2. **Settings Sky Blue Theme**:
   - Open Settings: Observe light sky blue accents on cards, switches, and navigation icons.
   - Open Keyboard: Verify keyboard layout, keys, suggestion strip, and modals are 100% unaffected.
3. **Tri-Method Unlock**:
   - Test default Pattern unlock on IME and Settings Gatekeeper.
   - Test Biometric unlock prompt in Settings and IME.
   - Test fallback to backup method when primary is bypassed or fails.
4. **KDBX Composite Import**:
   - Import standard password KDBX file -> Success.
   - Import password + key file KDBX file -> Success.
   - Verify custom fields and notes are preserved.
