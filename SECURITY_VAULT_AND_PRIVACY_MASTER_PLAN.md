# Master Plan: Security Vault, Privacy Vault & Stealth Security Engine

## Executive Summary
This document establishes the end-to-end architecture, technical specifications, and execution roadmap for HeliBoard's unified security ecosystem. It integrates:
1. **Security Vault (KDBX / TOTP / Package & Link Recognition)**: Secure credential storage supporting auto-detection of current package names and website URLs to display matching password/account pills directly in the suggestion strip, alongside a full in-keyboard action deck and explorer.
2. **Privacy Vault (Isolated Personal Dictionary Twin)**: Private phrase/credential sandbox stored exclusively in `heliboard.db` (`VaultDao.kt`), with dual shortcut and phrase-prefix ($\ge 3$ chars) matching, shoulder-surfing masking (`🔒 jo****om`), and zero-learning enforcement.
3. **Pattern Gatekeeper & Disguise Mode**: In-keyboard and full-screen pattern challenges, with an optional "Stealth QWERTY" disguise mapping the 3x3 pattern grid onto 9 normal keyboard keys (`E, T, U / D, G, J / C, B, M`) with zero height shift and no glowing trails.
4. **Unified vs. Separate Pattern Architecture**: User-configurable option to use a single master pattern or dual independent patterns for Privacy vs. Security vaults.
5. **Input Pipeline Hardening**: Elimination of the `?123` / `ABC` long-press inversion bug caused by sliding layout transitions on `ACTION_DOWN`.

---

## 1. System Architecture & Vault Topology

```
┌────────────────────────────────────────────────────────────────────────┐
│                        MAIN KEYBOARD UI & IME                          │
├────────────────────────────────────────────────────────────────────────┤
│  [Suggestion Strip / Bar]                                              │
│   ├─ Privacy Vault: Masked Shortcut & Prefix Pills (🔒 jo****om)      │
│   └─ Security Vault: Auto Link/Package Matched Pills ([🔑 user1] [🔑 user2]) │
├────────────────────────────────────────────────────────────────────────┤
│  [Main Keyboard Surface]                                               │
│   ├─ Normal QWERTY Mode                                                │
│   ├─ Stealth Pattern Disguise (9-key anchor overlay on E,T,U / D,G,J / C,B,M) │
│   ├─ Standard Pattern Unlock Card (3x3 dot matrix within keyboard height)│
│   └─ In-Keyboard Action Deck (User, Password, TOTP circular ring, Dock) │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
                 ┌───────────────────┴───────────────────┐
                 ▼                                       ▼
  ┌──────────────────────────────┐       ┌──────────────────────────────┐
  │        PRIVACY VAULT         │       │        SECURITY VAULT        │
  │  (Isolated Personal Words)   │       │  (KDBX 4.x / TOTP / SAF URI) │
  ├──────────────────────────────┤       ├──────────────────────────────┤
  │ • Storage: heliboard.db      │       │ • Storage: Encrypted KDBX DB │
  │ • Speed: O(1) in-memory cache│       │ • Package/URL Auto-Matcher   │
  │ • Masking: 🔒 jo****om       │       │ • Live RFC 6238 TOTP engine  │
  │ • Matching: Shortcut + Prefix│       │ • Group / Folder hierarchy   │
  │ • Session: 5-minute timeout  │       │ • Session: 3-minute timeout  │
  │ • Public Dictionary: BLOCKED │       │ • Learning / History: BLOCKED│
  └──────────────────────────────┘       └──────────────────────────────┘
```

---

## 2. Component Specifications

### 2.1 Security Vault: Link & Package Recognition with Suggestion Strip Pills
* **The Concept**: When focused on an app or website, the keyboard automatically identifies the context and displays all matching password pills right on the suggestion strip without requiring the user to open any menus.
* **Context Detection (`EditorInfo` & Browser Detection)**:
  1. **Native App Matching**: Detects `editorInfo.packageName` (e.g. `com.twitter.android`, `org.telegram.messenger`, `com.github.android`).
  2. **Web Browser & Link Matching**:
     - For web browsers (Chrome, Firefox, Brave, DuckDuckGo, Vivaldi), retrieves the domain/URL from:
       * Browser package domain inspection.
       * `editorInfo.hintText` or `editorInfo.fieldName` when provided by web view autofill structures.
       * Active composing URL if the user navigates or types a link.
     - Performs domain normalization (e.g. `https://auth.github.com/login` $\rightarrow$ `github.com`).
* **Suggestion Bar Password Pills**:
  * If the detected link/package matches one or more vault entries:
    * The suggestion bar displays prominent pills for each account:
      * Single match: `[🔑 alice@example.com]`
      * Multiple matches: `[🔑 personal: alice]` `[🔑 work: alice_corp]`
    * Visual cue: Key icon `🔑` with username/entry title and optional TOTP badge.
* **Tap & Autofill Flow**:
  1. User taps the `[🔑 user]` pill on the suggestion strip.
  2. **Security Gate**:
     * If session is active ($\le 3$ min): Immediately commits username (if in username/email field) or password (if in password field).
     * If locked: Invokes Pattern Unlock (either standard 3x3 view or Stealth Disguise).
  3. **Multi-Action Mini Strip / Action Deck**:
     * Tapping and holding or tapping a pill can also switch the suggestion strip into a quick-fill deck:
       * `[Fill User]` `[Fill Pass]` `[Copy TOTP (18s)]` `[More]`.

### 2.2 Security Vault: Headless KDBX & RFC 6238 TOTP Engine
* **KeePass Compatibility**: Pure Kotlin headless parser supporting KDBX 3.x and 4.x files (Argon2id, AES-KDF, ChaCha20 cipher).
* **Storage Access Framework (SAF)**: Binds to user's existing `.kdbx` file on device storage or cloud drive with `takePersistableUriPermission`. Zero proprietary lock-in.
* **TOTP Engine**: RFC 6238 implementation supporting SHA1/SHA256, 30s/60s time steps, 6/8 digits, and dynamic drift compensation.
* **In-Keyboard Action Deck (`ChosenEntryView.kt`)**:
  * Displayed directly within keyboard height when user selects an entry.
  * 4 symbol-only action targets:
    1. `[User Icon]` $\rightarrow$ Injects username.
    2. `[Key Icon]` $\rightarrow$ Injects password.
    3. `[Clock Icon]` $\rightarrow$ Injects 6-digit TOTP code, surrounded by an animated circular countdown sweep ring.
    4. `[Lock Icon]` $\rightarrow$ Instantly locks the session.
  * 4-key utility dock at the bottom: `[ABC]`, `[Space]`, `[Backspace]`, `[Enter]`.

---

### 2.3 Privacy Vault: Phrase-Prefix Matching & Zero-Learning
* **Status**: Base implementation completed in Phase 23 (`VaultDao.kt`, `PrivacyVaultScreen.kt`).
* **Phrase-Prefix Matching Enhancement**:
  * Users can trigger suggestions using **either** the explicit shortcut (`eml`) **or** the beginning of the secret phrase (`joh...` for `john.doe@protonmail.com`).
  * **Safety Guardrail**: Minimum prefix threshold of **$\ge 3$ characters** (case-insensitive) to prevent false positives when typing common 2-letter English words (`pa`, `in`, `on`, `to`).
  * **Masking Guaranteed**: Whether matched by shortcut or phrase prefix, the suggestion pill is **always masked** (`🔒 jo****om`), preventing shoulder-surfing.
  * **Zero-Learning**: Bypasses `UserHistoryDictionary` completely so passwords and secret emails are never learned as predictive bigrams.

---

### 2.4 Pattern Gatekeeper & Stealth Disguise Mode
* **Main Settings Gatekeeper**:
  * Entering `Settings -> Security` now checks `VaultSessionManager.isSecuritySessionValid()`.
  * If locked: Intercepts navigation with a full-screen pattern verification challenge.
  * If no pattern is set: Prompts the user to set up a master pattern with an educational onboarding card.
* **Stealth Disguise Mode (QWERTY Pattern Overlay)**:
  * **The Problem**: A dark modal with 9 glowing dots alerts anyone looking over your shoulder that you are unlocking a secure vault.
  * **The Solution**: An optional setting (`Settings -> Security -> Disguise Mode`).
  * **Layout Mechanics**:
    * Retains the normal QWERTY keyboard appearance.
    * Keyboard height remains 100% constant—**zero jumping, zero visual tell**.
    * Maps the 3x3 pattern grid directly to 9 tactile QWERTY anchor keys:
      ```
      [Q] [W] (E)1 [R] (T)2 [Y] (U)3 [I] [O] [P]
       [A] [S] (D)4 [F] (G)5 [H] (J)6 [K] [L]
         [Z] [X] (C)7 [V] (B)8 [N] (M)9 [⌫]
      ```
  * **Feedback Stealth**:
    * **No visible glowing lines** drawn across the keyboard.
    * Subtle, crisp haptic feedback (`HapticFeedbackConstants.CLOCK_TICK`) as the finger connects with each anchor key.
    * Correct pattern: Silently unlocks and proceeds.
    * Incorrect pattern: Single muted error buzz without red flashing alerts.

---

### 2.5 Unified vs. Separate Patterns
* **Setting in `Settings -> Security`**:
  * `[ ] Separate Vault Patterns`:
    * **Unchecked (Unified / Default)**: A single master pattern unlocks Settings, Privacy Vault, and Security Vault.
    * **Checked (Separate / High Security)**:
      * **Pattern 1 (Privacy Vault)**: Protects quick phrases, personal emails, and typing pills.
      * **Pattern 2 (Security Vault)**: Protects passwords, KDBX database, bank credentials, and settings gatekeeper.
      * **Advantage**: If an observer shoulder-surfs your pattern while you fill an email in a chat app, they cannot use it to open your password vault or settings.

---

### 2.6 Input Pipeline Hardening: Fixing the `?123` / `ABC` Inversion Bug
* **The Root Cause**:
  * In `KeyboardState.kt` (line 284), placing a finger **down** on `?123` executes an immediate sliding layout transition to `SYMBOLS`.
  * By the time the 300ms long-press timer finishes, the current keyboard element is already `SYMBOLS`.
  * `KeyboardSwitcher.java` checks `if (!keyboard.mId.getElement().isAlphabet()) return;`, which aborts the vault action, leaving the fallback to open `NUMPAD` on lift.
  * Conversely, pressing `ABC` switches on down to `ALPHABET`, so long-pressing `ABC` erroneously satisfies the condition and triggers the vault toast!
* **The Surgical Fix**:
  * In `PointerTracker.java`, capture the key code at initial touch `ACTION_DOWN` (`key.getCode() == Constants.CODE_SWITCH_ALPHA_SYMBOL`).
  * If a long-press on `CODE_SWITCH_ALPHA_SYMBOL` begins on an alphabet keyboard, lock the long-press action to `SECURITY_VAULT` and suppress the automatic release switch to `NUMPAD`.

---

### 2.7 Suggestion Bar: "More Suggestions" Panel with Integrated Delete & Demote
* **Preserving the Panel**: The user wants the comprehensive `MoreSuggestionsView` panel when long-pressing a suggestion word.
* **The Problem**: Currently, `onLongClickSuggestion` attempts to set a compound drawable icon on the word view, but immediately returns `showMoreSuggestions()`. The popup panel intercepts all touch events for drag-selection, making the delete/demote icon impossible to tap and creating an accidental "drag-button" feel.
* **The Solution**:
  * Keep `MoreSuggestionsView` active for secondary suggestions.
  * Integrate explicit action buttons directly inside the interaction flow:
    * **Personal / User History Word (`TYPE_USER`, `TYPE_USER_HISTORY`)**: Display an unambiguous **Delete (Trash bin)** action button. Tapping or releasing on it immediately purges the word from `heliboard.db` / `UserBinaryDictionary`.
    * **Original Dictionary Word (`TYPE_MAIN`)**: Display an unambiguous **Demote (Down arrow)** action button. Tapping or releasing on it demotes the unigram score and blacklists it from top candidate slots.
  * Eliminates the touch-trap conflict and makes deleting/demoting immediate and intuitive.

---

### 2.8 Voice Input: Full-Width Waveform & Lifecycle Dismissal Guard
* **Layout Geometry Overhaul**:
  * Replaces the tiny 52dp pulse box and dead blank space with a modern, dynamic acoustic layout:
    ```
    ┌────────────────────────────────────────────────────────┐
    │  [Preview Strip] "Streaming transcription text..." [✕] │  <-- Top preview strip
    ├────────────────────────────────────────────────────────┤
    │  [  |||||||||||||||||||||||||||||||||||||||||  ]  (🎤) │  <-- Stretched sound wave
    │     (Full-width dynamic waveform running to right)  (Mic) │      ending at Mic button
    ├────────────────────────────────────────────────────────┤
    │  [  ABC  ]  [          Space          ]  [ ⌫ ]  [ ↵ ]  │  <-- Standard 4-key dock
    └────────────────────────────────────────────────────────┘
    ```
  * **Sound Waveform**: Stretches across the middle row, dynamically scaling 16-24 vertical rounded cyan/blue capsule bars driven by real-time audio RMS levels.
  * **Dedicated Mic Button**: Positioned cleanly at the right edge of the waveform for 1-tap pause/resume/mute.
  * **No Dead Space**: The entire keyboard height is utilized purposefully.
* **Lifecycle Race Condition Fix (Premature Dismissal)**:
  * **Root Cause**: When microphone permission is granted in `VoicePermissionActivity`, `LatinIME.requestShowSelf(0)` triggers the Android OS to invoke `onStartInputView()`. In `KeyboardSwitcher.java`, `onStartInputView()` calls `mVoiceInputView.stopVoiceInput()`, instantly killing the voice input view before the user sees it.
  * **The Fix**: Introduce `mPendingVoiceLaunch = true` in `KeyboardSwitcher.java`. When `onStartInputView()` executes after returning from permissions, it detects the pending flag and **presents the voice input modal cleanly instead of dismissing it**.

---

### 2.9 Input IPC Stability & Key Event Resilience (Backspace & Space Bar)
* **Root Cause of Key Freezes**:
  * Log analysis revealed IPC calls like `GET_TEXT_AFTER_CURSOR` stalling for **2007 ms** in apps with heavy IPC.
  * When IPC lags, `mNestLevel` in `RichInputConnection.beginBatchEdit()` can become desynchronized or stuck if an unhandled state or timeout occurs.
  * Because `isBatchEdit()` remains true, `onUpdateSelection` ignores selection changes, and subsequent Backspace and Space bar events are dropped by `InputLogic`.
* **The Resilience Architecture**:
  * **Batch Timeout Guard**: If an IPC operation exceeds 500ms or drops connection, force-reset the batch edit nesting level (`mNestLevel = 0`) to unfreeze the input loop.
  * **Direct Hardware Key Event Fallback**: If `RichInputConnection.isConnected()` is false or stalled, Space and Backspace automatically fall back to sending direct key events (`KeyEvent.KEYCODE_SPACE` and `KeyEvent.KEYCODE_DEL`), guaranteeing the physical keys **never become unresponsive**, even in lagging third-party apps.

---

### 2.10 Diagnostic UI Typography Fix (Log Keeper Subsystems)
* **Visual Bug (`Screenshot_20260926-022209.png`)**:
  * In `LogKeeperActivity.kt`, the subsystem title ("Typo Engine") was crushed into a 1-character vertical line (`T\ny\np\no\nE\nn\ng\ni\nn\ne`) because the right status text lacked proper horizontal constraints and crowded the row.
* **The Fix**: Apply `Modifier.weight(1f)` with proper min-width constraints on the component title and constrain the status text with `TextOverflow.Ellipsis`, ensuring titles never wrap vertically regardless of status length.

---

### 2.11 Ultra-Lightweight Memory Reclamation: Single-Process Compose Teardown
* **The Architecture Decision**:
  * Multi-process separation (`:settings`) was analyzed and rejected because it breaks Android `SharedPreferences` real-time sync, creates duplicate SQLite database connection locks on `heliboard.db`, risks in-memory cache desynchronization, and introduces brittle IPC.
* **The Single-Process Zero-IPC Solution**:
  * Achieve identical ultra-lightweight RAM footprint (~35–45 MB) while keeping 100% of HeliBoard's single-process stability.
  * **Immediate Activity Teardown on Leave**:
    * Configure `SettingsActivity`, `SettingsActivity2`, and `LogKeeperActivity` in `AndroidManifest.xml` with `android:noHistory="true"`, `android:excludeFromRecents="true"`, and `android:autoRemoveFromRecents="true"`.
    * When switching to any app to type, Android immediately tears down the settings activity instead of leaving it frozen in background RAM.
  * **Explicit `disposeComposition()` on Destroy**:
    * In `SettingsActivity.onDestroy()`, explicitly invoke `composeView.disposeComposition()` and `removeAllViews()`, completely tearing down Jetpack Compose's node hierarchy, slot tables, and Material 3 state graphs.
  * **`onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` Optimization in LatinIME**:
    * Cleanly release temporary caches and request a lightweight GC pass whenever the keyboard window hides, ensuring zero background battery drain and immunity to Android 15 Low Memory Killer (LMK) process terminations.

---

## 3. Phased Implementation Roadmap

### Phase 24: Security Settings Gatekeeper & Input Inversion Fix
- [ ] Fix `PointerTracker.java` and `KeyboardSwitcher.java` to correctly bind long-press on `?123` to Security Vault and restore normal `ABC` behavior.
- [ ] Add pattern verification gate to `SettingsNavHost.kt` before entering `SecurityScreen.kt`.
- [ ] Add unified vs. separate pattern toggle in `VaultSessionManager.kt` and `SecurityScreen.kt`.

### Phase 25: Privacy Vault Phrase-Prefix Matching
- [ ] Enhance `VaultDao.kt` with `findByPrefix(prefix: String, minLength: Int = 3)`.
- [ ] Update `Suggest.kt` to query both exact shortcuts and phrase prefixes $\ge 3$ characters.
- [ ] Verify masked pills (`🔒 jo****om`) generate correctly for both lookup vectors.

### Phase 26: Stealth Disguise Mode (QWERTY Pattern Overlay)
- [ ] Create `StealthPatternKeyboardOverlay.kt` mapping 9 touch zones to `E, T, U / D, G, J / C, B, M`.
- [ ] Connect haptic feedback ticks without drawing visual trail lines.
- [ ] Wire toggle in `Settings -> Security -> Disguise Mode`.

### Phase 27: Security Vault Link/Package Matcher & Suggestion Strip Pills
- [ ] Add `PackageDomainMatcher.kt` to extract domain and package name from `EditorInfo`.
- [ ] Query active KDBX/Security vault entries for matching domains/packages.
- [ ] Render `[🔑 username]` pills at index 0/1 of the suggestion strip.
- [ ] Connect pill tap to session validation / pattern unlock and automatic credential insertion.

### Phase 28: In-Keyboard Action Deck & Explorer
- [ ] Build `ChosenEntryView.kt` with the 4-action symbol dock (User, Pass, TOTP circular sweep, Dock).
- [ ] Connect RFC 6238 TOTP live ticker.

### Phase 29: Suggestion Strip "More Suggestions" Delete/Demote & Voice Waveform Overhaul [COMPLETED]
- [x] Wire Delete (Trash) and Demote (Down arrow) actions directly into `SuggestionStripView` with dedicated hit-box and zero drag conflict.
- [x] Rebuild `VoiceInputView.kt` layout with full-width dynamic sound waveform stretching to the right-side Mic button.
- [x] Fix the `mPendingVoiceLaunch` flag in `KeyboardSwitcher.java` and `LatinIME.java` to prevent instant dismissal on permission grant.

### Phase 30: Input IPC Resilience & Log Keeper UI Fix
- [ ] Add batch edit timeout guard and direct `KeyEvent` fallback for Space and Backspace in `InputLogic.java` and `RichInputConnection.java`.
- [ ] Fix Row weights in `LogKeeperActivity.kt` so subsystem titles never wrap vertically.

### Phase 31: Ultra-Lightweight Settings Teardown & Lifecycle Memory Reclamation
- [ ] Configure `android:noHistory="true"` and `autoRemoveFromRecents="true"` for settings activities in `AndroidManifest.xml`.
- [ ] Add explicit `disposeComposition()` and view cleanup in `SettingsActivity.onDestroy()`.
- [ ] Harden `LatinIME.onTrimMemory()` to purge transient caches on `TRIM_MEMORY_UI_HIDDEN`.
- [ ] End-to-end regression testing and Receipts audit logging.
