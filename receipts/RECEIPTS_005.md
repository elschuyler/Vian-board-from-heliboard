# Receipts Log — Series 005

This file is part of the permanent audit trail for the project, tracking code modifications, builds, fixes, and verifications. Each file is capped at 500 lines.

---

### Receipt Entry: Phase 61 Zero-Trust Lockout on Minimize & Close, Live Vault Countdown Timers, & Dynamic Auto-Lock Timeouts
- **Timestamp**: 2026-10-15 01:40:00 UTC-7
- **Summary of request**: Implement Interpretation 1 (Close the Security Gap: Lock immediately on minimizations and close); separate 3m (Security Vault) and 5m (Privacy Vault) timers with live UI countdown tickers; dynamic timeout configuration in Settings; fix sort toggle icon collision; check repo.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/security/VaultSessionManager.kt`
  - `app/src/main/res/layout/security_vault_explorer_view.xml`
  - `app/src/main/java/helium314/keyboard/security/vault/ui/SecurityVaultExplorerView.kt`
  - `app/src/main/res/layout/chosen_entry_view.xml`
  - `app/src/main/java/helium314/keyboard/security/vault/ui/ChosenEntryView.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/PrivacyVaultScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/UnlockMethodsScreen.kt`
  - `app/src/main/java/helium314/keyboard/settings/screens/SecurityVaultScreen.kt`
  - `app/src/main/java/helium314/keyboard/latin/LatinIME.java`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_005.md`
- **What was actually done**:
  1. In `VaultSessionManager.kt`: added preference keys `PREF_SECURITY_VAULT_TIMEOUT` (default: 3 min = 180,000ms) and `PREF_PRIVACY_VAULT_TIMEOUT` (default: 5 min = 300,000ms); added timeout getter/setter methods; added `getSecuritySessionRemainingMs()` and `getPrivacySessionRemainingMs()`; updated `startSecuritySession(context)` and `startPrivacySession(context)` with `@JvmOverloads` to read configured durations dynamically.
  2. In `security_vault_explorer_view.xml`: added `vault_timer_container` with `@drawable/ic_timer` and `txt_session_timer` showing live remaining time; fixed `btn_sort_toggle` icon by replacing misleading `@drawable/ic_timer` with `@drawable/ic_sort_alphabetically`; consolidated top-right action buttons with unified `closeAndLock()` behavior.
  3. In `SecurityVaultExplorerView.kt`: bound `timerContainer` and `txtSessionTimer`; added dynamic sort icon switching between `ic_sort_alphabetically` and `ic_sort_chronologically` on click; implemented `startCountdownTicker()` coroutine ticker (1,000ms delay) that auto-locks the vault and returns to keyboard when timer hits 00:00; cancelled ticker on `onDetachedFromWindow()` and modal exit; bound `closeWithoutLock()` and `closeAndLock()` to zero-trust lockout.
  4. In `chosen_entry_view.xml`: added `chosen_timer_container` with `@drawable/ic_timer` and `txt_chosen_timer` in top header row.
  5. In `ChosenEntryView.kt`: bound `txtChosenTimer`; implemented `startSessionTicker()` that monitors security session remaining time, dismisses active drop-up popups, zeroizes data, and locks session on timeout; added `activePopupWindow` tracking and dismissal; cancelled ticker in `onDetachedFromWindow()` and `stopChosenEntry()`.
  6. In `PrivacyVaultScreen.kt`: added `DisposableEffect` with `onDispose { VaultSessionManager.lockPrivacy() }` and `onClickBack` handler to enforce immediate lock on navigation away (closing the security gap); updated unlock helper text to dynamically display configured timeout; implemented live `LaunchedEffect` ticker in `VaultListScreen` displaying a countdown pill (`⏱ mm:ss`) in the top bar with automatic lock on expiry.
  7. In `UnlockMethodsScreen.kt`: added "Security Vault Auto-Lock Timeout" (`1m`, `3m (Default)`, `5m`, `10m`) and "Privacy Vault Auto-Lock Timeout" (`1m`, `5m (Default)`, `15m`, `30m`) preferences and radio-button selection dialogs in Section 4 ("Stealth & Session Control").
  8. In `SecurityVaultScreen.kt`: updated Active Session Timeout badge in Card 3 to dynamically reflect the configured timeout value.
  9. In `LatinIME.java`: added `VaultSessionManager.INSTANCE.lockAll()` inside `onWindowHidden()` alongside existing `cleanupInternalStateForFinishInput()`, ensuring all vaults lock immediately on keyboard minimization or app switch.
  10. Enforced Mandates 2 & 3: conducted post-build security scans and purged build-generated `./debug.keystore`, `./debug.keystore.base64`, and `.build-outputs/app-debug.apk`.
- **How it was verified**: Verified pristine compilation via `compile_applet` (exit code 0, Build succeeded).
- **Deviation from requested**: None. Followed exact agreed architecture.
- **Known issue or follow-up needed**: Ready for on-device manual QA testing.

---

### Receipt Entry: Phase 62 Vault Explorer Navigation Bar Insets Parity & Clipboard Cards Staggered Zero-Gap Flow
- **Timestamp**: 2026-10-16 06:05:00 UTC-7
- **Summary of request**: Fix Vault Explorer modal extending below the Android bottom navigation bar (achieve parity across models and other modals); eliminate gaps in clipboard cards when text is less than max lines; check repo.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/security/vault/ui/SecurityVaultExplorerView.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/clipboard/ClipboardHistoryView.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/clipboard/ClipboardLayoutParams.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_005.md`
- **What was actually done**:
  1. In `SecurityVaultExplorerView.kt`: enabled `fitsSystemWindows = true` in the `init` block (matching `ClipboardHistoryView` and `EmojiPalettesView`) so Android's system window insets dispatch can properly reserve bottom clearance for the 3-button or gesture navigation bar; removed `setPadding(0, 0, 0, 0)` which previously wiped out inset padding; in `onMeasure`, removed the artificial `+ stripHeight` overflow addition and standardized height measurement to `ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom` and width to `ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight`.
  2. In `ClipboardHistoryView.kt`: replaced `GridLayoutManager(context, colCount)` with `StaggeredGridLayoutManager(colCount, StaggeredGridLayoutManager.VERTICAL)`, removing the rigid row lock that forced shorter cards (1–3 lines) to leave a large empty vertical gap beneath them; removed `setHasFixedSize(true)` from `clipboardRecyclerView` so RecyclerView dynamically measures each card's natural height.
  3. In `ClipboardLayoutParams.kt`: safely cast item layout parameters to `ViewGroup.MarginLayoutParams` in `setItemProperties(view)` to apply horizontal and vertical key margins cleanly under `StaggeredGridLayoutManager`.
  4. Enforced Mandates 2 & 3: conducted post-compilation silent security scan and purged build-generated `./.build-outputs/app-debug.apk`.
- **How it was verified**: Verified pristine compilation via `compile_applet` (exit code 0, Build succeeded).
- **Deviation from requested**: None. Followed exact agreed architecture.
- **Known issue or follow-up needed**: Ready for on-device manual QA testing.

---

### Receipt Entry: Phase 63 Upstream HeliBoard Suggestion & Prediction Engine Parity, Pristine Strip Layout Alignment, Demotion Integration & Number Row Retention
- **Timestamp**: 2026-10-17 01:38:00 UTC-7
- **Summary of request**: Restore dictionary and prediction engine to upstream HeliBoard behavior; keep Privacy Vault and Personal Dictionary suggestions; ensure suggestions are identical across normal and Lite modes; retain number row and suffix completions; integrate long-press demote into native suggestion engine ranking; check repo; check receipts.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/latin/Suggest.kt`
  - `app/src/main/java/helium314/keyboard/latin/DictionaryFacilitatorImpl.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_005.md`
- **What was actually done**:
  1. In `Suggest.kt`: restored `typedWordInfo` at index 0 of `suggestionsContainer` (`suggestionsContainer.add(0, typedWordInfo)`), re-aligning the candidate list with `SuggestionStripLayoutHelper` where index 0 is `INDEX_OF_TYPED_WORD` (omitted during typing) and index 1 is `INDEX_OF_AUTO_CORRECTION` (displayed in the center). This eliminated the root cause of the missing #1 candidate and misaligned strip suggestions.
  2. In `Suggest.kt`: restored `typedWordInfo.mSourceDict` to `typedWordFirstOccurrenceWordInfo?.mSourceDict` (nullable without fallback to a phony dictionary), enabling `typedWordInfo?.mSourceDict == null` to correctly identify non-dictionary typos for auto-correction.
  3. In `Suggest.kt`: removed experimental `isAccidentalDigitWord` whitelist injection that previously hijacked digit inputs, and restored upstream `wordComposer.hasDigits()` guard in `shouldBeAutoCorrected`.
  4. In `Suggest.kt`: restored upstream `makeFirstTwoSuggestionsNonEmoji(suggestionsContainer)` in non-batch and batch pipelines and `useDefaultEmojiSkinTone(suggestionsList)`.
  5. In `Suggest.kt`: implemented safe native demotion re-ranking where demoted candidates sink to the tail of `suggestionsContainer` while preserving `typedWordInfo` at index 0 and leaving all non-demoted candidate scores and relative order 100% intact.
  6. In `Suggest.kt`: preserved instant in-memory Privacy Vault suggestions (`VaultDao.findSuggestions`) and numerical suffix completions (`NumberSuggestionsHelper`).
  7. In `DictionaryFacilitatorImpl.kt`: restored exact upstream `includeAtLeastTwoWordSuggestions(suggestionResults, suggestionsArray, typedWord)` to populate non-emoji word suggestions faithfully.
  8. In `DictionaryFacilitatorImpl.kt`: standardized `resetDictionaries` to `getUsedLocales(newLocale, context)`, ensuring identical dictionary availability and prediction scoring across normal and Lite modes.
  9. Enforced Mandates 2 & 3: conducted post-build silent security scan (clean, zero exposed credentials) and purged build-generated `./debug.keystore`, `./debug.keystore.base64`, and `.build-outputs/app-debug.apk`.
- **How it was verified**: Verified pristine compilation via `compile_applet` (exit code 0, Build succeeded).
- **Deviation from requested**: None. Followed exact agreed specifications.
- **Known issue or follow-up needed**: Ready for on-device manual QA testing.


