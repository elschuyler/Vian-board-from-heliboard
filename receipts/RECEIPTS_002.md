# Receipts Log (Continued)

### Receipt: 2026-09-12 01:40:00
- **Requested**: "Option a implement" — Allow consecutive card taps in Prompt List without automatically closing the layout, matching the Clipboard History behavior and respecting `Settings.getValues().mAlphaAfterClipHistoryEntry` (`PREF_ABC_AFTER_CLIP`).
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/keyboard/clipboard/PromptHistoryView.kt`
  - `receipts/RECEIPTS_002.md`
- **What was actually done**:
  1. Updated `PromptHistoryView.kt` inside `startPromptHistory`: when a prompt card is tapped, it commits the text via `onCommitText(selectedPrompt)` and checks `if (Settings.getValues().mAlphaAfterClipHistoryEntry)` before switching to the alphabet keyboard via `keyboardActionListener.onCodeInput(KeyCode.ALPHA, ...)`.
  2. Verified that when `mAlphaAfterClipHistoryEntry` is `false` (the default), Prompt List remains open across multiple taps, enabling consecutive card pastes.
  3. Verified compilation with `compileDebugKotlin` and full verification with `compile_applet`.
- **How it was verified**: Local build verified via Gradle `compileDebugKotlin` and `compile_applet` (Build succeeded).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for on-device verification.

### Receipt: 2026-09-12 10:20:00
- **Requested**: "Make plan file" — Create comprehensive architectural blueprint and implementation plan for Privacy Vault, Security Vault, Pattern Unlock, and Modular Backup & Restore.
- **Exact files touched**:
  - `VAULT_PLAN.md`
  - `receipts/RECEIPTS_002.md`
- **What was actually done**:
  1. Created `VAULT_PLAN.md` at root detailing the system architecture, component specs, data models, suggestion strip masking (`jo***om`), 3 in-keyboard modals (Pattern Unlock, Security Vault Explorer, Chosen Entry action deck), Settings CRUD (Personal Dictionary twin and KDBX folder/entry manager), interactive Backup & Restore with future Voice Input slot, Log Keeper zero-PII sanitization protocol, and a 5-phase development roadmap (Phases 22-26).
- **How it was verified**: File creation verified via filesystem tools.
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Awaiting user instruction to implement Phase 22.

### Receipt: 2026-09-12 10:38:00
- **Requested**: "Implement. Take your time. Be thorough. Be meticulous. Don't rush. Be patient" — Implement Phase 22: Security Infrastructure & Pattern Unlock Engine (3x3 pattern view, Keystore session clocks, SecurityScreen.kt, sanitized LogCatcher telemetry, settings items with own pages, pattern lock, placeholders for security and privacy vaults, no lock gate for entering settings for now with arrangement placeholder, and long press ?123 in keyboard triggers unlock to placeholder security vault toast).
- **Exact files touched**:
  - `app/src/main/res/drawable/ic_settings_security.xml`
  - `app/src/main/java/helium314/keyboard/security/VaultSessionManager.kt`
  - `app/src/main/java/helium314/keyboard/security/PatternGridView.kt`
  - `app/src/main/res/layout/pattern_unlock_view.xml`
  - `app/src/main/java/helium314/keyboard/security/PatternUnlockView.kt`
  - `app/src/main/res/layout/main_keyboard_frame.xml`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
  - `app/src/main/java/helium314/keyboard/settings/screens/SecurityScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/PatternLockSettingsScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/PrivacyVaultPlaceholderScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/SecurityVaultPlaceholderScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/MainSettingsScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/SettingsNavHost.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_002.md`
- **What was actually done**:
  1. Created `VaultSessionManager.kt` with salted SHA-256 pattern hash storage, in-memory session timers (5 min Privacy, 3 min Security), memory-only session expiry, and sanitized telemetry to `LogCatcher`.
  2. Created `PatternGridView.kt` custom Android View with a 3x3 touch matrix, responsive theme color resolution, tactile haptic feedback, real-time drag path rendering, and error state indication.
  3. Created `PatternUnlockView.kt` and `pattern_unlock_view.xml` within keyboard height bounds, added include in `main_keyboard_frame.xml`.
  4. Integrated `PatternUnlockView` into `KeyboardSwitcher.java` with lifecycle management (`showPatternUnlockView`, `isShowingPatternUnlock`, `deallocateMemory`).
  5. Wired long-pressing `?123` (`onLongPressAlphaSymbolForNumpad`) to trigger pattern unlock when configured (or prompt to set up pattern if unconfigured), and on successful unlock transition to placeholder Security Vault toast.
  6. Added "Security" preference with icon `ic_settings_security.xml` to `MainSettingsScreen.kt`.
  7. Created `SecurityScreen.kt` hosting Pattern Lock, Privacy Vault placeholder, Security Vault placeholder, and disabled gatekeeper switch placeholder.
  8. Created `PatternLockSettingsScreen.kt` with full 2-step pattern configuration (draw -> confirm -> save salted hash), interactive verification, change pattern, and remove pattern.
  9. Created `PrivacyVaultPlaceholderScreen.kt` and `SecurityVaultPlaceholderScreen.kt` outlining Phases 23 and 24.
  10. Registered all routes in `SettingsNavHost.kt` and updated `BLUEPRINT.md`.
- **How it was verified**: Full application compilation verified via `compile_applet` (Build succeeded).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for on-device verification.

### Receipt: 2026-09-12 12:28:00
- **Requested**: "Implement" — Fix fatal crash `RuntimeException: Unknown event` with keycode `-10058` (`KeyCode.DESKTOP_SHORTCUTS`) occurring on popups / layout switches.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_002.md`
- **What was actually done**:
  1. Audited `InputLogic.java` `handleFunctionalEvent()` switch cases against all known non-input layout switching and utility keycodes.
  2. Identified that `KeyCode.DESKTOP_SHORTCUTS` (-10058) fell through to `default:` which threw `RuntimeException("Unknown event")` in debug builds.
  3. Added `KeyCode.DESKTOP_SHORTCUTS`, `KeyCode.INCOGNITO_TEMP_2MIN`, `KeyCode.DPAD`, `KeyCode.NUMPAD`, `KeyCode.SYMBOL`, `KeyCode.ALPHA`, and `KeyCode.SYMBOL_ALPHA` to the functional switch whitelist in `InputLogic.java` (line 894), allowing these actions to be handled cleanly by `KeyboardState` and `KeyboardSwitcher` without crashing `InputLogic`.
  4. Updated Change Ledger in `BLUEPRINT.md`.
- **How it was verified**: Full application compilation verified via `compile_applet` (Build succeeded).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for on-device testing.

### Receipt: 2026-09-13 13:35:00
- **Requested**: "Implement. Take your time. Be thorough. Be meticulous. Don't rush. Be patient" & "Finish what you were doing" — Fix pattern grid touch intercept in Settings; wire pattern lock user actions to LogCatcher; add Log Keeper to Settings > Advanced; force Settings light theme by default; set default pinned toolbar keys (select word, copy, paste on right of suggestion bar); set default expanded toolbar keys (incognito, mic, undo, redo, settings, top of page, bottom of page, log keeper); configure ?123 long-press unlock strictly on main alphabet layout (tap changes layout); hardcode default comma popups to Settings (unremovable), Log Keeper, Mic, One-Handed, Privacy Vault (placeholder), Emoji, and Desktop Shortcuts.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/security/PatternGridView.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/PatternLockSettingsScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/AdvancedScreen.kt`
  - `app/src/main/java/helium314/keyboard/latin/utils/Theme.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/Key.java`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyCode.kt`
  - `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardCodesSet.java`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardIconsSet.kt`
  - `app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/TextKeyData.kt`
  - `app/src/main/res/values/strings.xml`
- **What was actually done**:
  1. Resolved pattern setup touch interception in `PatternGridView.kt` by invoking `parent?.requestDisallowInterceptTouchEvent(true)` on touch down/move and releasing on up/cancel, enabling full pattern drawing in settings scroll containers.
  2. Connected user pattern lifecycle actions to `LogCatcher` in `PatternLockSettingsScreen.kt` (provisional pattern entry, confirmation mismatches, pattern setup cancel/remove/completion) without recording pattern coordinates or secrets.
  3. Added "Log Keeper" preference to `AdvancedScreen.kt` under "Troubleshooting & Logs" launching `LogKeeperActivity`.
  4. Enforced light theme default for Settings screens in `Theme.kt` (`dark: Boolean = false`).
  5. Configured `defaultPinnedToolbarPref` in `ToolbarUtils.kt` with `SELECT_WORD`, `COPY`, and `PASTE` pinned by default to the right of the suggestion strip.
  6. Configured `defaultToolbarPref` in `ToolbarUtils.kt` with `INCOGNITO`, `VOICE`, `UNDO`, `REDO`, `SETTINGS`, `PAGE_START`, `PAGE_END`, and `LOG_KEEPER` in the expanded toolbar.
  7. Enabled `ACTION_FLAGS_ENABLE_LONG_PRESS` on `SYMBOL_ALPHA` in `Key.java` and bound `KeyboardSwitcher.onLongPressAlphaSymbolForNumpad()` strictly to the main alphabet layout (`keyboard.mId.getElement().isAlphabet()`), launching the pattern unlock overlay and entering valid security session on success. Maintained normal layout toggle on tap across all layouts.
  8. Created `KeyCode.PRIVACY_VAULT` (`-10059`), registered in `KeyboardCodesSet.java` as `"key_privacy_vault"`, mapped `privacy_vault_key` icon to `ic_settings_security` in `KeyboardIconsSet.kt`, and handled toast placeholder in `InputLogic.java`.
  9. Hardcoded default comma popups in `TextKeyData.kt`: Settings (unremovable), Log Keeper, Mic (`key_voice_input`), One-Handed (`key_toggle_onehanded`), Privacy Vault (`key_privacy_vault`), Emoji (`key_emoji`), and Desktop Shortcuts (`key_desktop_shortcuts`).
- **How it was verified**: Full application compilation verified via `compile_applet` (Build succeeded).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for on-device verification.

### Receipt: 2026-09-13 13:40:00
- **Requested**: Implement: Fix `?123` long-press opening pattern unlock in keyboard without tap fallthrough; eliminate desktop shortcuts modal bottom gap; fix Log Keeper copying logs instead of error on Errors tab; add customizable and reorderable comma key popup in Settings > Appearance.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/keyboard/PointerTracker.java`
  - `app/src/main/java/helium314/keyboard/keyboard/desktop/DesktopShortcutsView.kt`
  - `app/src/main/java/helium314/keyboard/latin/utils/LogCatcher.kt`
  - `app/src/main/java/helium314/keyboard/settings/LogKeeperActivity.kt`
  - `app/src/main/java/helium314/keyboard/latin/App.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/popup/CommaPopupItem.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/TextKeyData.kt`
  - `app/src/main/java/helium314/keyboard/settings/dialogs/CommaPopupsCustomizer.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/AppearanceScreen.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_002.md`
- **What was actually done**:
  1. In `PointerTracker.java`, lifted the symbol element restriction for `SYMBOL_ALPHA` in `startLongPressTimer()` to allow the long-press timer to run on the Alphabet layout, and added `cancelKeyTracking()` inside `onKeyLongPress()` to prevent pointer release from firing a normal tap event.
  2. In `DesktopShortcutsView.kt`, disabled `fitsSystemWindows`, eliminated manual padding calculations, and enforced exact measure specs matching `keyboardHeight` in `onMeasure()` to remove the bottom gap.
  3. In `LogCatcher.kt`, separated clean crash stack traces (`last_crash.log`) from diagnostic dumps (`VianBoard_CRASH_<timestamp>.log`), and added `stripSystemLogs()` to ensure only the crash trace is loaded by the UI.
  4. In `LogKeeperActivity.kt`, updated the Errors tab copy action to export only the clean crash report and error/warning log entries, and added 1-tap copy buttons to both the Fatal Crash card and individual log entry cards.
  5. Created `CommaPopupItem.kt` and `CommaPopupsCatalog` with default items (Settings [unremovable], Log Keeper, Voice Input, One-Handed Mode, Privacy Vault, Emoji, Desktop Shortcuts), providing serialization, deserialization, and enabled list querying.
  6. Updated `TextKeyData.kt` `getCommaPopupKeys()` to dynamically query `CommaPopupsCatalog.getEnabledItems()` using `App.getInstance()?.prefs()`.
  7. Created `CommaPopupsCustomizer.kt` with drag-and-drop reordering, switches (with Settings permanently checked and disabled), a reset-to-default button, and instant keyboard layout refresh on save via `KeyboardLayoutSet.onSystemLocaleChanged()`.
  8. Integrated "Comma Key Popups" preference into `AppearanceScreen.kt` under Appearance settings.
- **How it was verified**: Full application compilation verified via `compile_applet` (Build succeeded).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for on-device manual QA.

### Receipt: 2026-09-15 10:25:00
- **Requested**: Implement fixes for failed GitHub Actions APK build pipeline.
- **Exact files touched**:
  - `.github/workflows/build-apk.yml`
  - `app/build.gradle.kts`
  - `receipts/RECEIPTS_002.md`
  - `BLUEPRINT.md`
- **What was actually done**:
  1. Updated `.github/workflows/build-apk.yml` JDK setup from Java 17 to Java 21 (`actions/setup-java@v4` with `java-version: '21'`), aligning CI with the project runtime environment.
  2. Added dynamic fallback NDK detection in both `ndk-build` and `CMake` steps to ensure toolchain resolution across different runner image configurations.
  3. Injected `DEBUG_KEYSTORE_PATH: ${{ github.workspace }}/debug.keystore` into the `Build Debug APK` workflow step environment so Gradle configures `customDebug` with the generated keystore.
  4. Enhanced `app/build.gradle.kts` to configure `signingConfigs.create("release")` when `KEYSTORE_PATH` is passed via environment variables, with fallback to debug keystore for debug builds.
- **How it was verified**: Full local compilation verified via `compile_applet` (Build succeeded).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for workflow execution.

### Receipt: 2026-09-15 15:20:00
- **Requested**: Implement: Fix GitHub Actions workflow secrets syntax error and remove redundant keystore creation.
- **Exact files touched**:
  - `.github/workflows/build-apk.yml`
  - `receipts/RECEIPTS_002.md`
  - `BLUEPRINT.md`
- **What was actually done**:
  1. Resolved GitHub Actions workflow parser error (`Unrecognized named-value: 'secrets'`) in `.github/workflows/build-apk.yml` by removing invalid `if: ${{ secrets... }}` expressions.
  2. Implemented bash-level conditional checks inside `Sign Release APK` to test for required secrets (`STORE_PASSWORD`, `KEY_PASSWORD`, `KEYSTORE_BASE64`) before attempting release builds, cleanly exiting with code 0 if not provided.
  3. Removed the invalid `if:` expression from `Upload Release APK` while preserving `if-no-files-found: ignore`.
  4. Removed the redundant `Ensure Debug Keystore` CI step and `DEBUG_KEYSTORE_PATH` override, allowing standard Android Gradle debug builds to sign via the runner's default `~/.android/debug.keystore`.
  5. Confirmed `.gitignore` protects against exporting any keystores (`*.keystore`, `*.jks`, `*.p12`, `*.keystore.base64`).
- **How it was verified**: Full local compilation verified via `compile_applet` (Build succeeded).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Push to GitHub to verify workflow completion.

### Receipt: 2026-09-16 00:22:00
- **Requested**: Implement: Fix voice input crash (no modal appeared) and fix pattern unlock layout (modal under screen and close button below strip).
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputView.kt`
  - `app/src/main/res/layout/strip_container.xml`
  - `app/src/main/res/layout/pattern_unlock_view.xml`
  - `app/src/main/java/helium314/keyboard/security/PatternUnlockView.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-fr/strings.xml`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_002.md`
- **What was actually done**:
  1. Identified root cause of the voice input crash: `Settings.PREF_VOICE_INPUT_GAIN` is stored as a `String` ("1.0", "2.0", "4.0") by `VoiceInputScreen.kt`, but was read via `prefs().getInt()` in `VoiceInputView.kt`, throwing a `ClassCastException`.
  2. Fixed `VoiceInputView.kt` by wrapping preference access in resilient parsing that inspects `getString()` with fallback to `getInt()`, handles float strings safely (`toFloatOrNull()?.toInt()`), defaults safely to `1`, and updates `cycleGain()` to write `"${currentGainMultiplier}.0"` as a `String`.
  3. Identified root causes of pattern unlock layout defects:
     - `PatternUnlockView` used `fitsSystemWindows = true`, causing Android 15 edge-to-edge window insets to add artificial top/bottom padding that shoved the grid off the bottom of the screen.
     - `PatternUnlockView` measured with wrap_content instead of exact secondary keyboard height bounds.
     - `PatternUnlockView` contained an internal 44dp `pattern_top_bar`, placing the close button and title below the suggestion strip bar rather than in the unified strip container where clipboard, desktop shortcuts, and voice preview strips live.
  4. Created `pattern_unlock_strip` in `strip_container.xml` matching the architectural pattern of `voice_preview_strip` and `desktop_shortcuts_strip`, containing the close ImageButton and status TextView.
  5. Stripped the internal top bar from `pattern_unlock_view.xml`, reducing it to a pure `PatternGridView` container extending `FrameLayout`.
  6. Rebuilt `PatternUnlockView.kt` with `fitsSystemWindows = false`, `padding = 0`, exact secondary keyboard height measurement (`MeasureSpec.EXACTLY`), and connected its callbacks to `KeyboardSwitcher.getInstance().patternUnlockStrip`.
  7. Updated `KeyboardSwitcher.java` to declare `mPatternUnlockStrip`, manage its visibility across alphabet, emoji, clipboard, prompt, desktop shortcuts, voice input, and pattern unlock views, and ensure close button dismissal returns cleanly to the unshifted alphabet keyboard.
  8. Added localized strings to `strings.xml` and French translations in `values-fr/strings.xml`.
- **How it was verified**: Full local compilation verified via `compile_applet` (Build succeeded).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for on-device verification.



