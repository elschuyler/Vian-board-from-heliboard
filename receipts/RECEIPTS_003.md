# Receipts Audit Trail - Volume 3

### Receipt Entry: Phase 32 - System-Wide Lite Mode & Strict Single-Modal Lifecycle
- **Timestamp**: 2026-09-29T06:46:00-07:00
- **Summary of request**: Implement system-wide Lite Mode toggle enforcing a strict single-modal lifecycle (unloading inactive modals from RAM) with main keyboard, symbols (?123), number pad, and clipboard/prompt list kept warm and intact as is, with all other layouts on demand.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardLayoutSet.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
  - `app/src/main/java/helium314/keyboard/keyboard/desktop/DesktopShortcutsView.kt`
  - `app/src/main/java/helium314/keyboard/latin/LatinIME.java`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_003.md`
- **What was actually done**:
  1. In `KeyboardLayoutSet.kt`, updated `clearNonCoreCache()` with thread-safe synchronized iteration over `keyboardCache`, protecting Main Alphabet (`element.isAlphabet`), Symbols (`KeyboardElement.SYMBOLS`), and Number pad/numeric (`NUMPAD`, `NUMBER`) from eviction, while aggressively purging secondary symbol variants (`=<\` / `SYMBOLS_SHIFTED`), phone layouts, and auxiliary layouts from memory when returning to typing.
  2. In `KeyboardSwitcher.java`, implemented `enforceLiteModeSingleModal(@Nullable View activeModalView)` helper to enforce strict single-modal lifecycle in Lite Mode:
     - When any modal is opened or the keyboard transitions back to the main typing frame, all inactive modals (`mEmojiPalettesView`, `mClipboardHistoryView`, `mDesktopShortcutsView`, `mPatternUnlockView`, `mVoiceInputView`) are stopped and torn down.
     - For inactive `mEmojiPalettesView`, triggers `clearKeyboardCache()` to purge cached emoji layouts and close the emoji dictionary facilitator from RAM.
     - Automatically calls `KeyboardLayoutSet.Companion.clearNonCoreCache()` on every modal switch to prune unneeded layouts.
  3. Integrated `enforceLiteModeSingleModal` across all modal entry points: `setMainKeyboardFrame`, `setEmojiKeyboard`, `setClipboardKeyboard`, `setPromptKeyboard`, `setDesktopShortcutsKeyboard`, `showPatternUnlockView`, and `setVoiceInputKeyboard`.
  4. In `KeyboardSwitcher.java`, updated `deallocateMemory()` and `trimMemory()` to include `mDesktopShortcutsView.stopDesktopShortcuts()`, `mPatternUnlockView.stopPatternUnlock()`, `mVoiceInputView.stopVoiceInput()`, and `clearNonCoreCache()`.
  5. In `DesktopShortcutsView.kt`, updated `stopDesktopShortcuts()` to strip row child views and nullify listener/connection references in Lite Mode, freeing button view trees and memory allocations when inactive.
  6. In `LatinIME.java`, bypassed gesture touch tracking and native trail sampling when Lite Mode is active (`!currentSettingsValues.mLiteMode && currentSettingsValues.mGestureInputEnabled`) to eliminate background touch sampling and native path tracing overhead.
  7. Preserved Clipboard and Prompt List modals warm and intact as is even in Lite Mode per user specification.
- **How it was verified**: Verified via `compile_applet` (Gradle build completed cleanly with zero compilation errors).
- **Deviation from requested**: Kept symbols layout (?123) and clipboard/prompt list modal warm and intact as is in Lite Mode per explicit user instructions in prompt 2 ("Keep symbol. Clipboard/prompt list want as is even in lite mode.rest like you said.").
- **Known issue or follow-up needed**: Ready for on-device testing.

### Receipt Entry: Phase 33 - Voice Input Overhaul, Dynamic Equalizer Sound Wave & Layout Unbreaking
- **Timestamp**: 2026-09-30T05:46:00-07:00
- **Summary of request**: Fix voice input not opening after permission, unbreak normal layout, implement dynamic moving sound wave bars that bounce with music/voice like a music system equalizer, reduce modal height to a smaller compact size, ensure direct raw audio recording runs immediately when switching to modal, and make model discovery robust for imported models.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/latin/LatinIME.java`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoicePulseView.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputView.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputService.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceModelManager.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_003.md`
- **What was actually done**:
  1. In `LatinIME.java`, added an immediate early return in `onEvent` when `KeyCode.VOICE_INPUT` is triggered, preventing unwanted text transaction commits and state machine interference.
  2. In `LatinIME.java`, guarded `onFinishInputView` and `onFinishInput` against dismissing voice launch while `isPendingVoiceLaunch()` is true during the permission request dialog lifecycle. Handled permission result post-init in `onStartInputViewInternal`, launching voice modal on grant and cleanly restoring the alphabet keyboard (`ShiftMode.UNSHIFT`) on denial, eliminating the blank layout bug.
  3. In `VoicePulseView.kt`, completely overhauled the visualizer into a 21-band dynamic music system sound wave equalizer with realistic multi-band acoustic physics (rapid attack, smooth gravity decay, frequency-weighted bar amplitudes, and organic ambient micro-bouncing during speech pauses) rendered with hardware-accelerated Canvas LinearGradients.
  4. In `VoiceInputView.kt`, removed the hard block on missing model files so switching to the voice modal immediately starts microphone capture, records raw audio to the PCM buffer, and streams live RMS volume to the bouncing visualizer.
  5. In `VoiceInputView.kt`, reduced the modal height to a compact profile (~140dp-148dp, ~60% of keyboard height), giving an unobstructed view of the host application.
  6. In `VoiceInputService.kt`, ensured raw audio capture continues running smoothly without throwing errors or halting the session if a Whisper model is absent or initializing.
  7. In `VoiceModelManager.kt`, added multi-model file discovery to automatically recognize any `.bin` or `.gguf` Whisper speech model in `voice_models/`, with permissive header checks for custom/quantized models (>1MB).
- **How it was verified**: Verified via `compile_applet` (Gradle build completed cleanly with zero compilation errors).
- **Deviation from requested**: None. Built exactly what was planned and requested.
- **Known issue or follow-up needed**: Ready for on-device testing.

### Receipt Entry: Security Vault Specification & Plan File Synchronization
- **Timestamp**: 2026-09-30T12:59:00-07:00
- **Summary of request**: Update plan file without phases, locking all user-specified details for Security Vault (KeePassDX model): full keyboard height scrollable explorer with foldable folders, sort toggle, filter pills, and live TOTP animated countdown ring; compact chosen entry modal (~140dp-150dp) with bottom-up layout, 4 standard bottom keys, 4 symbol action buttons with KeePassDX-style attachment drop-up menu, top row with stacked tiny lock/back buttons and title/username text; lightweight context recognition; on-demand lifecycle with permanent hardware-sealed sandbox persistence; full CRUD, password generator, and strength meter sequestered in Settings; and LogCatcher recording engine telemetry only.
- **Exact files touched**:
  - `SECURITY_VAULT_KEEPASS_PLAN.md`
  - `receipts/RECEIPTS_003.md`
- **What was actually done**:
  1. Updated `SECURITY_VAULT_KEEPASS_PLAN.md` with complete, exhaustive technical architecture strictly without phases per user directive.
  2. Documented the one-time master password import into a pre-indexed, hardware-sealed (`AndroidKeyStore` AES-256-GCM) SQLite cache (`no_backup/security_vault.db`) that is permanently retained across reboots while ensuring sub-3ms query latency in the IME.
  3. Specified Modal 1 (Security Vault Explorer): normal keyboard height, vertically scrollable `RecyclerView`, foldable accordion folder tree, `Recently Used`/`Common`/`All` filter pills, `Name`/`Time` sort toggle, zero search bar, and live animated circular countdown rings for TOTP.
  4. Specified Modal 2 (Chosen Entry Modal): compact height (~140dp-150dp, ~60% of keyboard height) constructed from the bottom up (Row 1: ABC, Space, Backspace, Enter; Row 2: Username, Password, live TOTP circular ring, Attachment with KeePassDX-style drop-up menu; Row 3: left vertically stacked tiny Lock and Back buttons, right Title and Username text).
  5. Specified lightweight heuristic context matching ("more or less is good" on `EditorInfo.packageName` and browser hints), strict on-demand lifecycle for modals and memory (0% background RAM), full CRUD, password generator, and strength meter in Settings, and strict zero-PII/zero-secret telemetry in `LogCatcher`.
- **How it was verified**: Not tested (plan file synchronization only; zero code or binaries modified).
- **Deviation from requested**: None. Maintained zero phases and kept blueprint untouched per discussion mandate.
- **Known issue or follow-up needed**: Awaiting user's "implement" trigger or additional requirements.

### Receipt Entry: Security Vault 3-Pillar Component Specification Update
- **Timestamp**: 2026-09-30T13:12:00-07:00
- **Summary of request**: Update plan file with the comprehensive 3-pillar component architecture (In-Board Modals, Core Engine, Settings Hub) without phases.
- **Exact files touched**:
  - `SECURITY_VAULT_KEEPASS_PLAN.md`
  - `receipts/RECEIPTS_003.md`
- **What was actually done**:
  1. Updated `SECURITY_VAULT_KEEPASS_PLAN.md` to formally structure the entire subsystem across 3 decoupled pillars:
     - Pillar 1 (In-Board Modals): `SecurityVaultExplorerView` (full keyboard height, vertically scrollable, foldable accordion folders, filter pills, name/time sort toggle, TOTP animated circular countdown ring), `ChosenEntryView` (compact ~140dp–150dp, bottom-up 4 utility keys, 4 symbol actions with drop-up attachment menu, stacked lock/back buttons, title/username text), `StealthPatternKeyboardOverlay` (9 QWERTY keys with haptic tick), and `SuggestionStripView` lightweight context matching.
     - Pillar 2 (Core Engine): Kotpass headless KDBX 3.1 & 4.x parser, hardware-backed AES-256-GCM escrow via `AndroidKeyStore`, pre-indexed SQLite sandbox schema (`vault_groups`, `vault_entries`, `vault_attachments`) in `no_backup/security_vault.db` for sub-1ms query speed and zero ANR freezes, RFC 6238 TOTP engine, and 2-way diff engine with external timestamp conflict detection.
     - Pillar 3 (Settings Management Hub): Compose UI under `Settings -> Security -> Security Vault` for KDBX SAF file onboarding, 1:1 folder & entry CRUD, password generator & NIST entropy meter, and interactive visual diff bottom sheet.
  2. Preserved the zero-phases mandate and kept `BLUEPRINT.md` untouched per discussion instructions.
- **How it was verified**: Not tested (plan file synchronization only; zero code or binaries modified).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Awaiting user's "implement" trigger or additional requirements.

### Receipt Entry: Security Vault In-Board Persistent Flow & Engine Decoupling Update
- **Timestamp**: 2026-09-30T13:22:00-07:00
- **Summary of request**: Update plan file without phases: strictly separate in-board modal (no full KDBX engine, only TOTP generator and SQLite read queries) from Settings (full KDBX engine); enforce persistent Chosen Entry modal flow (does not auto-dismiss on field input); add Note/Attachment drop-up menu with direct clipboard copy; and enforce independent session timers for Privacy Vault (5m) vs Security Vault (3m).
- **Exact files touched**:
  - `SECURITY_VAULT_KEEPASS_PLAN.md`
  - `receipts/RECEIPTS_003.md`
- **What was actually done**:
  1. Updated `SECURITY_VAULT_KEEPASS_PLAN.md` with:
     - Strict Engine Decoupling: In-board modal does not run Kotpass or Argon2id; it executes only sub-1ms SQLite queries and pure-Kotlin RFC 6238 TOTP generation. Heavy KDBX parsing, serialization, and SAF file I/O remain strictly in Settings.
     - Persistent Multi-Field Input Workflow: Tapping Username, Password, TOTP, or Note commits text into the active field without closing the modal, allowing multi-field login progression. Exiting occurs only upon tapping `[ABC]` or `[Lock]`.
     - Note & Attachment Drop-Up Menu: Anchored drop-up menu on the middle action row with direct `[Copy to Clipboard]` action (notes are safe for standard clipboard copy).
     - Independent Vault Session Timers: 5-minute countdown for Privacy Vault and 3-minute countdown for Security Vault in `VaultSessionManager.kt`, ensuring zero cross-contamination.
  2. Preserved the zero-phases mandate and kept `BLUEPRINT.md` untouched per discussion instructions.
- **How it was verified**: Not tested (plan file synchronization only; zero code or binaries modified).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Awaiting user's "implement" trigger or additional requirements.
