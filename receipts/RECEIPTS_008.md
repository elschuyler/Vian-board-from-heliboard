# Receipts Log 008

### Receipt 008-01
- **Timestamp**: 2026-11-17T01:52:00-08:00
- **Requested**:
  - Audit and fill R8/ProGuard and resource-shrinking gaps (`app/proguard-rules.pro`, dynamic resources), then fix the `JNI_OnLoad` `UnsatisfiedLinkError` regression where `libjni_latinime.so` failed to register natives in `minifiedDebug`, breaking dictionary/prediction while Privacy Vault pills kept working.
- **Exact files touched**:
  - `app/proguard-rules.pro`
  - `app/src/main/res/raw/keep.xml`
  - `app/src/main/res/values/strings.xml`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_008.md` (new; `RECEIPTS_007.md` closed at 456 lines under the 500-line cap)
- **What was actually done**:
  1. Updated `app/proguard-rules.pro`:
     - Kept `WordInputEventForPersonalization` (`{ *; }`) for JNI `GetFieldID` access in `com_android_inputmethod_latin_BinaryDictionary.cpp`.
     - Used `-keepclasseswithmembers,includedescriptorclasses class * { native <methods>; }` (fixing an intermediate `-keepclasseswithmembernames` regression that allowed R8 shrinking to strip 8 uncalled `native` methods from `BinaryDictionary` and `BinaryDictionaryUtils`, causing `JNI_OnLoad` `RegisterNatives` to fail on device).
     - Added explicit `$Companion` `serializer(...)`, `KeyTypeSerializer`, and defensive `enum` `values()`/`valueOf(String)` keep rules under `helium314.keyboard.**`.
  2. Added `app/src/main/res/raw/keep.xml` (`tools:shrinkMode="safe"`) to protect dynamically resolved string prefixes (`layout_*`, `theme_name_*`, `label_*`, `popup_keys_*`, `toolbar_mode_*`, `subtype_*`) and `@drawable/sym_keyboard_*` icons.
  3. Fixed the `tools:keep` typo on `layout_functional_keys_khipro` in `app/src/main/res/values/strings.xml:579`.
  4. Verified R8 mapping/seeds/usage outputs (`app/build/outputs/mapping/minifiedDebug/`) confirming `31/31` C++ `RegisterNatives` methods retained in `seeds.txt` and `0` stripped in `usage.txt`.
- **How it was verified**: Local Gradle R8/lint/unit verification only (`gradle :app:assembleMinifiedDebug :app:minifyReleaseWithR8 :app:lintVitalMinifiedDebug :app:lintVitalRelease --no-daemon` succeeded; `seeds.txt`/`usage.txt`/`resources.txt` verified; `gradle :app:assembleDebug :app:testDebugUnitTest --tests ... --no-daemon` passed 22/22 across `VaultLockoutTest` [7], `BackupCryptoTest` [5], `InputTest` [6], `SubtypeTest` [2], `LiteDictionaryDropTest` [1], `KdbxAttachmentTest` [1] with 0 failures/errors/skipped). `compile_applet` hit a 504 gateway timeout earlier in the session; physical device testing with a newly synced CI APK has not yet run.
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Sync `app/proguard-rules.pro`, `app/src/main/res/raw/keep.xml`, and `app/src/main/res/values/strings.xml` to GitHub and re-run the GitHub Actions APK pipeline so the `minifiedDebug` APK includes all 31 kept `native` methods, then verify predictions/autocorrect on device. Also previously flagged workspace security cleanup items (`/debug.keystore`, `/debug.keystore.base64`, `/.build-outputs/app-debug.apk`, and `signingConfigs` fallback literals in `app/build.gradle.kts`) remain untouched per surgical-edit scope.

### Receipt 008-02
- **Timestamp**: 2026-11-17T11:53:30-08:00
- **Requested**:
  - Enhance Voice Input Word Improvement so spaced/punctuated spoken letters (e.g. `"s k p"`) reliably become target words (`"skp"`), and implement a Desktop-Shortcuts-style 5-slot customizable Voice Actions system (with CRUD for choices, symbol-only toggles in a new row directly above the bottom 4 buttons in the voice modal, and editable triggers in Settings) defaulting to Next Line (`↵`, ON), Brackets (`( )`, ON), Star (`*`, OFF), Multiply (`×` -> `2x`, ON), and Spoken Numbers (`123`, ON).
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/latin/settings/Settings.java`
  - `app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt`
  - `app/src/main/java/helium314/keyboard/latin/database/VoiceReplacementDao.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceActionCatalog.kt` (new)
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceActionProcessor.kt` (new)
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputService.kt`
  - `app/src/main/res/layout/voice_input_view.xml`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputView.kt`
  - `app/src/main/java/helium314/keyboard/settings/dialogs/VoiceActionsCustomizerDialog.kt` (new)
  - `app/src/main/java/helium314/keyboard/settings/screens/VoiceInputScreen.kt`
  - `app/src/main/res/values/strings.xml`
  - `app/src/test/java/helium314/keyboard/latin/voice/VoiceActionsTest.kt` (new)
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_008.md`
- **What was actually done**:
  1. Upgraded `VoiceReplacementDao` (`applyReplacementsWithMask`) to sort rules longest-first, match multi-word whole-word phrases across optional Whisper punctuation/hyphens (`[,\.\-\s]+`), handle literal `$`/`\` safely without regex backreference crashes, and track replaced character spans in a `BooleanArray` mask so downstream Voice Actions do not overwrite custom Word Improvement outputs.
  2. Added `VoiceActionCatalog` (`Settings.PREF_VOICE_ACTIONS_CONFIG` JSON via `org.json`), defaulting to 5 slotted actions (`↵` Next Line [ON], `( )` Brackets [ON], `*` Star [OFF], `×` Multiply [ON], `123` Spoken Numbers [ON]) with custom `CUSTOM_SYMBOL` (Word/Tight/Attach-Left/Newline spacing) and `CUSTOM_WRAPPER` CRUD and strict `MAX_MODAL_SLOTS = 5` enforcement.
  3. Implemented `VoiceActionProcessor` and wired it into `VoiceInputService` (preview & final) to run Word Improvement followed by active slotted Voice Actions in deterministic order (Spoken Numbers -> Multiply `<n>x` -> Star -> Custom Symbols -> Brackets/Wrappers -> Next Line with pause-punctuation cleanup).
  4. Added `voice_actions_row` directly above `bottom_row_keyboard` in `voice_input_view.xml`, rendered the 5 symbol-only live toggle buttons in `VoiceInputView`, displayed ` ↵ ` visually in preview/streaming views for `\n`, and bumped `VoiceInputView.onMeasure()` compact height clamp to `175dp..195dp`.
  5. Added the `VoiceActionsSection` preview strip and `VoiceActionsCustomizerDialog` (drag-to-reorder, search, 5-slot cap, Create/Edit/Delete CRUD, Default reset) in `VoiceInputScreen`.
- **How it was verified**: Local Gradle builds and Robolectric/JVM unit tests (`gradle :app:assembleDebug`, `gradle :app:assembleMinifiedDebug` including `minifyMinifiedDebugWithR8` and `lintVitalMinifiedDebug`, `gradle :app:compileDebugKotlin`, and `VoiceActionsTest` [10/10 passed] alongside `InputTest`, `SubtypeTest`, `VaultLockoutTest`, `BackupCryptoTest`). Live microphone Whisper inference and modal UI layout have not yet been tested on a physical device.
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Verify Voice modal layout/sizing, live tap toggles, and Whisper spoken dictation on a physical device once exported/built via GitHub Actions.

### Receipt 008-03
- **Timestamp**: 2026-11-18T00:42:30-08:00
- **Requested**:
  - Implement all 5 finalized stabilization pillars: (1) Voice modal stability (`×`/`ABC` dismiss only; Space/Delete/Enter pause mic without closing), crisp ON/OFF pill contrast, full-width horizontal equalizer animation, greedy Whisper default (`1200ms`/96k rolling window) & gain-normalized VAD/RMS; (2) Desktop Shortcuts bottom system navigation bar inset clearance; (3) Privacy Vault backup/restore preserving unlock config + patterns without gibberish ciphertext import; (4) `applicationId = "shura.keyboard"` and `versionName = "1beta"`; (5) Locked HeliBoard 4.1–4.3 low-risk imports and normal-typing hide diagnostics/guards.
- **Exact files touched**:
  - `app/build.gradle.kts`
  - `app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/EnergyVad.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/AudioRecordPipeline.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputService.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoicePulseView.kt`
  - `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputView.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
  - `app/src/main/java/helium314/keyboard/latin/LatinIME.java`
  - `app/src/main/java/helium314/keyboard/keyboard/desktop/DesktopShortcutsView.kt`
  - `app/src/main/java/helium314/keyboard/settings/backup/BackupManifest.kt`
  - `app/src/main/java/helium314/keyboard/settings/backup/ModularBackupEngine.kt`
  - `app/src/main/java/helium314/keyboard/latin/database/Database.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/BackupRestoreScreen.kt`
  - `app/src/main/java/helium314/keyboard/latin/DictionaryFacilitator.java`
  - `app/src/main/java/helium314/keyboard/latin/SingleDictionaryFacilitator.kt`
  - `app/src/main/java/helium314/keyboard/latin/DictionaryFacilitatorImpl.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/Key.java`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/TextKeyData.kt`
  - `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt`
  - `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java`
  - `app/src/test/java/helium314/keyboard/BackupCryptoTest.kt`
  - `app/src/test/java/helium314/keyboard/StabilizationRegressionTest.kt` (new)
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_008.md`
- **What was actually done**:
  1. **Package & Version Identity (`app/build.gradle.kts`)**: Set `applicationId = "shura.keyboard"` and `versionName = "1beta"`, keeping `versionCode = 4100`, `namespace = "helium314.keyboard.latin"`, and existing `signingConfigs` untouched.
  2. **Voice Modal Stability, UI Contrast, Waveform & Whisper/VAD Pipeline**: Guarded `KeyboardSwitcher.setAlphabetKeyboard(ShiftMode)` when Voice Input is active and introduced `closeVoiceInput()` for explicit dismissal (`×`, `ABC`, OS Back, `onToggleKeyboard(VOICE)`, `reloadMainKeyboard`, IME hide/finish). Space/Delete/Enter in `VoiceInputView` now call `pauseVoiceInput()` and keep the modal open. Added crisp ON/OFF `GradientDrawable` pill borders/opacity on action toggles without changing the `175dp..195dp` compact vertical clamp. Upgraded `VoicePulseView` bar count dynamically (`21..96`) in `onSizeChanged` with a `postOnAnimation` fallback when animations are disabled. Normalized `EnergyVad` by `gainMultiplier`, switched `AudioRecordPipeline` RMS to a `sqrt` curve with gain-scaled ambient floor, defaulted `PREF_VOICE_USE_BEAM_SEARCH = false`, raised `INTERIM_INFERENCE_INTERVAL_MS` to `1200L`, capped interim samples to `96_000`, and avoided final inference on `onUnbind()`.
  3. **Desktop Shortcuts Nav Bar Insets (`DesktopShortcutsView.kt`)**: Enabled `fitsSystemWindows = true`, applied bottom `WindowInsets.Type.navigationBars()` (except in floating mode), and included padding in `onMeasure()`.
  4. **Privacy Vault Backup/Restore & Unlock Config (`BackupManifest.kt`, `ModularBackupEngine.kt`, `Database.kt`, `BackupRestoreScreen.kt`)**: Added full unlock settings and security pattern fields to `EncryptedVaultPayload`, restored them atomically + reset lockout/sessions, replaced blind prefix stripping with `unwrapVaultCiphertextOrNull` across both `ModularBackupEngine` and `Database.copyFromDb` (skipping undecryptable/gibberish entries and decrypting notes), and updated BackupRestoreScreen copy.
  5. **HeliBoard 4.1–4.3 Low-Risk Imports & Normal Typing Hardening**: Added sanitized `LogCatcher` window/hide breadcrumbs and zero-height `onComputeInsets` guards in `LatinIME.java`; removed unused `ValidSpellingWord*` caches across `DictionaryFacilitator*` (and fixed `synchronized(this@DictionaryFacilitatorImpl)` in `asyncReloadUninitializedMainDictionaries`); added empty popup-key length guard (`Key.java`), popup placeholder guard (`TextKeyData.kt`), more-suggestions dismissal on toolbar show (`SuggestionStripView.kt`), and deferred shift update after arrows (`InputLogic.java`).
- **How it was verified**: Local Gradle R8/lint/unit/Robolectric verification and `compile_applet`:
  - `gradle :app:testDebugUnitTest` (full run): 212 tests run, 19 pre-existing/sandbox failures (`XLinkTest` [3: offline/repo paths], `ParserTest` [6: fork INR currency vs `$` & `de_DE`], `SuggestTest` [7: fork caps/center-suggestion defaults], `InputLogicTest.insertLetterIntoWordHangulFails` [1], `StringUtilsTest.detectEmojisAtEndFails` [1]) + 1 new Robolectric test adjusted for missing Keyguard/Fingerprint hardware shadows (`StabilizationRegressionTest`).
  - After updating `StabilizationRegressionTest` shadows (`ShadowKeyguardManager.setIsDeviceSecure(true)`, `ShadowPackageManager.setSystemFeature(FEATURE_FINGERPRINT, true)`): `gradle :app:testDebugUnitTest --tests ... :app:assembleMinifiedDebug :app:lintVitalMinifiedDebug --no-daemon` succeeded with **50/50 targeted tests passed** (`StabilizationRegressionTest` [6/6], `BackupCryptoTest` [7/7], `VoiceActionsTest` [10/10], `VaultLockoutTest` [7/7], `LiteDictionaryDropTest` [1/1], `KdbxAttachmentTest` [1/1], `InputTest` [6/6], `SubtypeTest` [2/2], `SpellCheckerTest` [2/2], `LayoutTest` [1/1], `KeySpecParserTest` [2/2], `SaveGestureDataTest` [3/3], `LocaleUtilsTest` [1/1], `ScriptUtilsTest` [1/1]). `output-metadata.json` verified `applicationId = "shura.keyboard"`, `versionName = "1beta"`, `versionCode = 4100`. `compile_applet` succeeded. On-device APK verification has not yet occurred.
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Because `applicationId` changed from `shura.keyboard.rbsdrd` to `shura.keyboard`, users must export an encrypted modular backup (`.zip` with passphrase for Privacy Vault and `.kdbx` for Security Vault) before uninstalling `shura.keyboard.rbsdrd` and clean-installing `shura.keyboard` via GitHub Actions CI.

