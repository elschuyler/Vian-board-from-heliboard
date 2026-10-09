# Security Vault Ecosystem: Phased Implementation Roadmap
**Document**: SECURITY_VAULT_PHASED_IMPLEMENTATION_PLAN.md  
**Reference**: `SECURITY_VAULT_MODAL_THEME_AND_TRI_AUTH_PLAN.md`  
**Execution Standard**: Clean Architecture, Zero-Regression IME, Non-Destructive Modularity  
**Status**: APPROVED ROADMAP FOR IMPLEMENTATION  

---

## Roadmap Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ PHASE 46: EXPLORER MODAL POLISH, TOPBAR PILLS, SORT & ROOT FLATTENING       │
│ • Solid active pills & rounded inactive pill outlines (16dp radius)         │
│ • Circular outline sort order toggle button (36dp circle)                   │
│ • Root folder flattening: child folders first, root entries direct, -50dp   │
│ • Verification: Confirm zero credential buttons on Explorer modal           │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ PHASE 47: SETTINGS SKY BLUE M3 THEME & SECURITY SCREEN RESTRUCTURING        │
│ • Material 3 Light Sky Blue palette in Theme.kt (Primary #0288D1, Ice Blue) │
│ • Strict theme boundary: 100% isolated to Settings; zero keyboard change    │
│ • SecurityScreen.kt 3-section division:                                     │
│     1. Privacy Vault  |  2. Security Vault  |  3. Unlock & Gatekeeper       │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ PHASE 48: TRI-METHOD AUTHENTICATION ENGINE (PATTERN, BIOMETRIC, PHONE PIN)   │
│ • 3 Unlock Methods: Pattern (Default), Biometric, Phone PIN / Password      │
│ • Primary vs Backup assignment with failover fallback on sensor failure     │
│ • Transparent BiometricAuthTrampolineActivity for background IME window     │
│ • 3-Way Target Binding: Unified Master vs Independent Vault credentials     │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ PHASE 49: KEEPASS KDBX KEY FILE ONBOARDING, CUSTOM FIELDS & KEEPASSDX UI    │
│ • Composite credentials in KdbxRepository: Password + Key File (.key/.keyx) │
│ • Custom String Fields & OTPAuth URI parsing (zero metadata loss)           │
│ • KeePassDX visual card layout, security badges (Argon2, KDBX 4.1, AES-256) │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Phase Specifications

### Phase 46: Explorer Modal Polish, Topbar Pills, Sort & Root Flattening

#### Objective
Elevate the visual fidelity and vertical efficiency of the in-IME Security Vault Explorer modal (`SecurityVaultExplorerView`).

#### Detailed Tasks
1. **Pill Drawables & State Styling**:
   - `res/drawable/bg_vault_chip_inactive.xml`: Shape drawable, `corners radius="16dp"`, stroke `1.2dp` matching keyboard border/hint text color, transparent surface.
   - `res/drawable/bg_vault_chip_active.xml`: Solid filled shape drawable, `corners radius="16dp"`, background tinted to theme primary accent / secondary container.
   - In `SecurityVaultExplorerView.kt` (`updateChipHighlight`):
     - Bind `bg_vault_chip_active.xml` with contrasting bold text to the selected filter pill (`All`, `Recent`, or `Folders`).
     - Bind `bg_vault_chip_inactive.xml` with secondary text color to unselected pills.
2. **Circular Sort Toggle Button**:
   - `res/drawable/bg_vault_circle_button.xml`: 36dp oval shape with `1.2dp` stroke matching chip outline.
   - Assign to `btn_sort_toggle` in `security_vault_explorer_view.xml` and dynamically tint stroke and icon in `SecurityVaultExplorerView.applyThemeColors()`.
3. **Root Folder Flattening**:
   - In `AccordionFolderAdapter.kt` (`rebuildDisplayItems`):
     - Identify root group (`parentGroupUuid == null` or base database group).
     - Suppress rendering of the root folder header row (`ListItem.Folder`).
     - Render immediate subfolders at the top level as accordion cards.
     - Render root group direct entries immediately beneath subfolders as `ListItem.Entry` rows.
     - Recover 48–56dp of vertical viewport height.
4. **Button Isolation Verification**:
   - Verify `security_vault_explorer_view.xml` contains strictly `vault_top_bar`, `recycler_vault_items`, and standard `bottom_row_keyboard`. Zero credential action buttons.
   - Confirm the 5 action tiles (`Note`, `User`, `Password`, `TOTP`, `Files`) reside exclusively in `chosen_entry_view.xml`.

#### Verification Criteria
- Active pill displays solid container fill; inactive pills display rounded 16dp outline.
- Sort button has a circular outline matching the pill height.
- Subfolders and root entries appear directly in list with no redundant "Root" folder row.
- Zero layout shift or regression on keyboard bottom row `[ABC]` navigation.

---

### Phase 47: Settings Sky Blue M3 Theme & Security Screen Restructuring

#### Objective
Replace the yellow wallpaper-extracted theme in Settings with a cohesive Material 3 Light Sky Blue color scheme and reorganize the Security settings screen into three unambiguous sections.

#### Detailed Tasks
1. **Material 3 Light Sky Blue Theme (`Theme.kt`)**:
   - In `helium314/keyboard/latin/utils/Theme.kt`:
     - Discard OS wallpaper palette `dynamicLightColorScheme(context)` and `dynamicDarkColorScheme(context)`.
     - Construct dedicated `LightSkyBlueColorScheme`:
       - `primary = Color(0xFF0288D1)` (Vibrant Light Sky Blue)
       - `onPrimary = Color(0xFFFFFFFF)`
       - `primaryContainer = Color(0xFFE1F5FE)` (Soft Sky Tint)
       - `onPrimaryContainer = Color(0xFF01579B)` (Deep Navy)
       - `secondary = Color(0xFF0277BD)`
       - `secondaryContainer = Color(0xFFE0F2F1)`
       - `surface = Color(0xFFF8FAFD)` (Crisp Ice Blue Slate)
       - `onSurface = Color(0xFF191C1E)`
       - `surfaceVariant = Color(0xFFE1E8ED)`
       - `outline = Color(0xFF71787E)`
     - Construct matching `DarkSkyBlueColorScheme`:
       - `surface = Color(0xFF0B131E)` (Midnight Navy)
       - `primary = Color(0xFF4FC3F7)` (Luminous Sky Blue)
       - `onPrimary = Color(0xFF00344F)`
       - `primaryContainer = Color(0xFF004C71)`
       - `onPrimaryContainer = Color(0xFFBBE9FF)`
   - **Theme Boundary**: Wrap strictly around `SettingsActivity` and Compose settings screens. Keyboard IME theme, `KeyboardTheme`, and `ColorType` remain completely untouched.
2. **Security Settings Page Three-Section Restructuring (`SecurityScreen.kt`)**:
   - Refactor `SecurityScreen.kt` into three distinct card categories:
     - **Section 1: Privacy Vault**:
       - Open Privacy Vault manager.
       - Privacy Vault unlock credential status (Unified vs Dedicated).
       - Stealth clipboard & history security toggles.
     - **Section 2: Security Vault**:
       - Open Security Vault manager (KDBX, folders, credentials, sync).
       - Security Vault unlock credential status (Unified vs Dedicated).
       - Context auto-fill suggestion pills toggle.
     - **Section 3: Unlock Mechanism & Security Gatekeeper**:
       - Require unlock for Security Settings (Gatekeeper switch).
       - Primary unlock method selector [Pattern (Default) | Biometric | Phone PIN].
       - Backup unlock method selector [Pattern | Phone PIN].
       - Target binding mode [Unified Master Pattern | Dedicated Vault Patterns].
       - Lock All Sessions button.

#### Verification Criteria
- Settings pages render with crisp Sky Blue primary headers, cards, switches, and navigation icons.
- Keyboard layout, keys, suggestion strip, and modals maintain their existing keyboard theme with zero color bleeding.
- Security settings page displays 3 clear, non-confusing sections with distinct credential status indicators.

---

### Phase 48: Tri-Method Authentication Engine (Pattern, Biometric, Phone PIN)

#### Objective
Implement a multi-modal authentication framework allowing users to authenticate via Pattern, Biometric, or Phone PIN / Password, with backup failover and isolated target bindings.

#### Detailed Tasks
1. **Core Credential Architecture (`VaultSessionManager.kt`)**:
   - Define `UnlockMethod` enum: `PATTERN` (Default), `BIOMETRIC`, `DEVICE_CREDENTIAL_PIN`, `APP_PIN`.
   - Store preferences:
     - `pref_vault_primary_method` (Default: `PATTERN`)
     - `pref_vault_backup_method` (Default: `PATTERN`)
     - `pref_vault_binding_mode` (`UNIFIED` vs `INDEPENDENT`)
   - Maintain independent session clocks:
     - `settingsSessionExpiryMs` (Settings Gatekeeper)
     - `privacySessionExpiryMs` (Privacy Vault)
     - `securitySessionExpiryMs` (Security Vault)
2. **Biometric Authentication Pipeline (`BiometricPrompt`)**:
   - In Settings (`ComponentActivity`): Trigger `BiometricPrompt` directly with title, subtitle, and negative button ("Use Backup Method").
   - In Keyboard (`InputMethodService`):
     - Create transparent trampoline activity `BiometricAuthTrampolineActivity` (manifest: `Theme.Translucent.NoTitleBar`, zero window animations).
     - When keyboard vault requires unlock and primary method is `BIOMETRIC`, launch `BiometricAuthTrampolineActivity`.
     - Trampoline presents `BiometricPrompt`; upon `onAuthenticationSucceeded()`, calls `VaultSessionManager.startSecuritySession()`, finishes immediately, and returns focus to the IME.
3. **Phone PIN / Device Credential Pipeline**:
   - Device Credential verification via `KeyguardManager.createConfirmDeviceCredentialIntent()` or `BiometricPrompt.PromptInfo.Builder().setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`.
   - On fallback or manual selection, prompt device lock screen PIN/password.
4. **Backup Failover Logic**:
   - If Biometric fails 3 times, biometric hardware is un-enrolled, or the user taps "Use Backup Method", smoothly fallback to the configured backup method (Pattern or PIN).
   - Out-of-the-box default: Primary = Pattern Unlock, Backup = Phone PIN.

#### Verification Criteria
- Default pattern unlock works instantly in both IME and Settings.
- Biometric unlock authenticates successfully in Settings and through the IME trampoline.
- Phone PIN/Device Credential validates correctly against Android system credentials.
- Automatic fallback smoothly triggers backup method when primary fails or is bypassed.

---

### Phase 49: KeePass KDBX Key File Onboarding, Custom Fields & KeePassDX UI

#### Objective
Bring full real-world KeePassDX compatibility to the Security Vault: support composite password + key file databases, retain all custom entry fields, and replicate KeePassDX's structured visual card layout.

#### Detailed Tasks
1. **Composite Master Credentials (Password + Key File)**:
   - In `KdbxRepository.kt`:
     - Update `importFromUri(context, uri, masterPassword, keyFileUri)` and `exportToUri(...)`.
     - In Kotpass, decode credentials using:
       ```kotlin
       val credentials = if (keyFileBytes != null) {
           Credentials.from(
               passphrase = EncryptedValue.fromString(String(masterPassword)),
               keyFile = keyFileBytes
           )
       } else {
           Credentials.from(EncryptedValue.fromString(String(masterPassword)))
       }
       ```
     - Store key file URI securely in encrypted preferences (`pref_security_vault_keyfile_uri`).
   - In `SecurityVaultScreen.kt`:
     - Add "Select Key File (.key / .keyx)" SAF file picker inside the import dialog.
     - Display attached key file badge when configured.
2. **Custom String Fields & Comprehensive Entry Data**:
   - In `KdbxRepository.traverseGroup()`:
     - Parse all key-value entries in `entry.fields` beyond the standard 5 (Title, UserName, Password, URL, Notes).
     - Serialize custom string fields to `VaultEntryEntity.customFieldsJson` or custom entity table.
     - Parse `otpauth://totp/...` URIs and custom time-step/digit parameters for the TOTP engine.
3. **KeePassDX Visual Card Styling in `SecurityVaultScreen.kt`**:
   - Reorganize screen into rounded M3 `OutlinedCard` containers with 16dp radii:
     - Card 1: Active Database Overview & Status Badges (`KDBX 4.1`, `Argon2id`, `AES-256`, `Key File Bound`).
     - Card 2: 2-Way Sync Engine & Visual Diff Sheet.
     - Card 3: Security & Session Rules (timeout, stealth gatekeeper, auto-fill pills).
     - Card 4: Import / Export / Backup Actions.
   - Include clear 2-line subtitles explaining the technical impact of every setting.

#### Verification Criteria
- Standard password-only KDBX imports and syncs seamlessly.
- Password + Key File composite KDBX imports and syncs seamlessly.
- Entries with custom fields retain 100% of their extra key-value pairs without data loss.
- Security Vault settings screen reflects clean, modern KeePassDX card aesthetics.
