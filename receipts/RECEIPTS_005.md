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
