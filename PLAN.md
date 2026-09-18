# Vianboard Master Implementation Plan

## 1. System Philosophy & Mode Architecture
The application supports two distinct whole-system operation modes (no bare-bones mode):
1. **Normal Mode (Backup & Full Baseline)**:
   - Full HeliBoard feature parity.
   - Pre-warmed modal containers.
   - Full clipboard manager with categorization.
   - Full spatial touch heuristics and n-grams.
2. **Lite Mode (Primary Daily Driver)**:
   - On-demand single modal rendering: only one secondary keyboard/modal loaded at a time; others torn down to 0 RAM.
   - Stripped secondary overhead: No rich pager tabs, no GIF/sticker previews, no search tags.
   - Retains full keyboard intelligence: Multi-word n-grams, spatial touch matrix, unlearning engine, personal dictionary, and vault partition.
   - Retains full visual polish: Candidate animations, dynamic pill sizing, blur/shadow key popups, key press shade effects.
   - Background hygiene: Dictionary background sync and copy-clipboard observer only.

### Universal Engine Stripping (Applies to BOTH Normal & Lite Modes):
- **Zero Contacts Integration**: Disconnect `ContactsBinaryDictionary`, `ContactsManager`, and `ContactsContentObserver`. No contacts permissions, zero contact IPC queries.
- **Zero Emoji Suggestion Search**: Decouple emoji candidate matching passes from `Suggest.kt` and `DictionaryFacilitatorImpl.kt`. The dedicated emoji key/palette remains accessible, but typed words never trigger unicode emoji scoring loops.

---

## 2. Privacy Vault as a Personal Dictionary Partition
- **Zero Background Services**: No separate Room database, no persistent worker threads, zero background polling.
- **Unified In-Memory Partition**:
  - `PARTITION_NORMAL` (0): Standard personal dictionary words.
  - `PARTITION_VAULT` (1): Sensitive words/credentials protected by the vault lock.
- **Lock Lifecycle**:
  - **Locked**: Suggestion candidate collection in `Suggest.kt` immediately prunes `PARTITION_VAULT` words before scoring.
  - **Unlocked**: Pattern unlock verifies the hash, sets `isUnlocked = true`, allowing vault words to score.
- **Suggestion Bar Rendering**:
  - Vault candidates render with `isVaultWord = true` as a **masked pill** (`••••• (Tag)` or `[Vault] Tag`) to prevent shoulder surfing.
- **Storage Hygiene**:
  - Both partitions stored in internal sandbox (`filesDir/personal_dict.bin`), with the Vault partition encrypted using AES-GCM derived from the pattern unlock hash.

---

## 3. Suggestion Bar Long-Press Actions
Attaches an `OnLongClickListener` to candidate views in `SuggestionStripView`:
1. **Personal / Learned Word** (`UserHistoryDictionary` / `PersonalDictionary`):
   - Displays popup with **Delete** (Trash icon).
   - Calls `unlearnWord(word)` and purges entry from dictionary immediately.
2. **Built-in System Dictionary Word** (`main_en.dict` / `main_fr.dict`):
   - Displays popup with **Demote** (Down Arrow / Thumbs Down).
   - Enters `(word, DEMOTION_PENALTY)` into the unlearning overlay.
   - Slashes score in `Suggest.kt` so the word drops off top suggestions.

---

## 4. Backspace Word Resumption & HeliBoard Bug Fixes
1. **Backspace Word Resumption**:
   - Detects backspace after a committed word + whitespace.
   - Extracts word boundary using `getTextBeforeCursor()`.
   - Re-engages composing span: `setComposingRegion(wordStart, cursorPosition)`.
   - Feeds characters into `WordComposer`, updating suggestions immediately.
2. **Text Duplication (`helhello`) Fix**:
   - Atomic replacement wrapping `beginBatchEdit()` -> `setComposingText("", 0)` -> `commitText(word)` -> `endBatchEdit()`.
3. **Cursor Jump Fix**:
   - Sequence generation counter for `onUpdateSelection` to reject stale IPC updates arriving after programmatic text changes.
4. **Google AI Studio / Browser Lag**:
   - Batch input updates for web fields to prevent DOM re-rendering churn.

---

## 5. View & Secondary Keyboard Defect Resolutions
1. **Pattern Unlock Nav Bar Overflow**:
   - In `PatternGridView.kt` and `PatternUnlockView.kt`, subtract navigation bar insets and enforce safe bottom padding so the grid does not touch Android 15 gesture pills.
2. **Pattern Unlock 'X' Folding Keyboard**:
   - Replace invalid `KeyCode.ALPHA` event with `KeyboardSwitcher.getInstance().closeSecondaryKeyboard()`.
3. **Voice Permission Dismissing Keyboard**:
   - When `VoicePermissionActivity` returns granted, signal `LatinIME` to immediately call `requestShowSelf(0)` and reopen `VoiceInputView`.
4. **Comma Popup Mic Missing**:
   - Update `isShortcutImeReady` in `RichInputMethodManager.kt` to return `true` when internal voice input is enabled (`Settings.PREF_VOICE_INPUT_ENABLED`).
5. **Voice Sound Wave / Sound Bar**:
   - Update `VoicePulseView.kt` to draw animated sound bars driven by real-time RMS amplitude callbacks from `AudioRecordPipeline`.
6. **Voice Preview 'X' Action**:
   - Bind `voice_preview_clear` to cancel recording, clear preview, and call `closeSecondaryKeyboard()`.
7. **Stray Cross at Bottom Bar**:
   - Ensure `showKeyboard(ELEMENT_ALPHABET)` atomically sets `mVoicePreviewStrip` and bottom row dock containers to `GONE`.

---

## 6. Execution Phases & Milestones

### Phase 1: View & Secondary Keyboard Fixes
- Fix Pattern navigation bar padding & height bounds.
- Fix Pattern 'X' close action.
- Fix Voice 'X' close action and clean up bottom bar stray crosses.
- Fix Comma popup mic visibility in `RichInputMethodManager.kt`.
- Replace pulse circles with real-time sound wave bars in `VoicePulseView.kt`.
- Auto-resume keyboard upon Voice Permission grant.

### Phase 2: Engine Pruning (Contacts & Emoji)
- Disconnect `ContactsBinaryDictionary`, `ContactsManager`, and `ContactsContentObserver`.
- Decouple emoji prediction candidate scoring from `Suggest.kt` and `DictionaryFacilitatorImpl.kt`.

### Phase 3: Suggestion Bar Long-Press (Delete & Demote)
- Implement long-press popup in `SuggestionStripView`.
- Wire Delete to history/personal unlearn.
- Wire Demote to penalty unlearning scoring overlay.

### Phase 4: Backspace Word Resumption & Input Bug Fixes
- Implement word resumption state machine on backspace.
- Implement atomic batch edits (`beginBatchEdit()`) and selection generation counter.

### Phase 5: Privacy Vault Personal Dictionary Partition
- Implement `PARTITION_NORMAL` vs `PARTITION_VAULT` in-memory structure.
- Add masked pill rendering in `SuggestionStripView`.
- Connect settings screen in `SecurityScreen.kt`.

### Phase 6: Full-App Lite Mode Implementation
- Add Settings toggle for Normal vs. Lite mode.
- Enforce strict single-modal lifecycle in Lite mode.
- Strip rich pager tabs, GIF previews, and search tags when Lite mode is active.
