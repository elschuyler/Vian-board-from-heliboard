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

