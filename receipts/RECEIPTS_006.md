# Receipts Ledger: Series 006

Audit trail for code changes, builds, and verifications in Vianboard (`shura.keyboard`).
Strictly capped at 500 lines per file.

---

### Receipt Entry: Phase 79 Number Pad Space Bar Vault Explorer Long-Press, Toolbar Vault Keys, and Comma Key Popups Catalog Expansion
- **Timestamp**: 2026-10-28 05:51:00 UTC-7
- **Summary of request**: Configure long press on space bar of number pad layout to open Security Vault Explorer modal (with pattern unlock challenge first) instead of keyboard app switch, strictly isolated to number pad layout; add Security Vault Explorer and Privacy Vault tools to the keyboard toolbar; and expand Comma Key Popups controls in Settings to include all toolbar tools while preserving the current 7 default tools as factory default.
- **Exact files touched**:
  - `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyCode.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardCodesSet.java`
  - `app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardIconsSet.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListener.java`
  - `app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt`
  - `app/src/main/java/helium314/keyboard/keyboard/PointerTracker.java`
  - `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java`
  - `app/src/main/java/helium314/keyboard/keyboard/popup/CommaPopupItem.kt`
  - `app/src/main/java/helium314/keyboard/settings/dialogs/CommaPopupsCustomizer.kt`
  - `BLUEPRINT.md`
  - `receipts/RECEIPTS_006.md`
- **What was actually done**:
  1. In `PointerTracker.java`: Added a dedicated check when `code == Constants.CODE_SPACE && mKeyboard != null && mKeyboard.mId.getElement() == KeyboardElement.NUMPAD`. It cancels key tracking and dispatches `CustomAction.SHOW_SECURITY_VAULT_EXPLORER`, suppressing the system IME switcher dialog. On all other layouts (alphabet, symbols, etc.), normal space bar behavior remains 100% untouched.
  2. In `KeyboardSwitcher.java`: Extracted `showSecurityVaultExplorerWithAuth()` which checks active security session validity, and if locked, challenges with stealth pattern or primary biometric/PIN before calling `setSecurityVaultExplorerKeyboard()`. Wired `CustomAction.SHOW_SECURITY_VAULT_EXPLORER` in `KeyboardActionListener.java` and `KeyboardActionListenerImpl.kt` to invoke this method.
  3. In `KeyCode.kt` & `KeyboardCodesSet.java`: Added `KeyCode.SECURITY_VAULT (-10060)`, mapped `"key_security_vault"`, and added to `checkAndConvertCode`.
  4. In `ToolbarUtils.kt`: Added `SECURITY_VAULT` and `PRIVACY_VAULT` to `ToolbarKey` enum and mapped them in `getCodeForToolbarKey()`. Kept `defaultToolbarPref` restricted to the 8 existing default tools so vault tools are available in the customizer without altering default toolbar density.
  5. In `KeyboardIconsSet.kt`: Bound `ToolbarKey.SECURITY_VAULT -> R.drawable.ic_vpn_key` and `ToolbarKey.PRIVACY_VAULT -> R.drawable.ic_settings_security` across both standard and rounded icon sets.
  6. In `InputLogic.java`: Handled `KeyCode.SECURITY_VAULT` to trigger `showSecurityVaultExplorerWithAuth()`, and `KeyCode.PRIVACY_VAULT` to launch Privacy Vault management settings.
  7. In `CommaPopupItem.kt`: Expanded `CommaPopupsCatalog.ALL_ITEMS` to include all toolbar tools (Security Vault, Clipboard History, Numpad, DPAD, Undo, Redo, Cut, Copy, Paste, Select All, Select Word, Incognito, Autocorrect, Clear Clipboard, Split, Floating, Navigation Arrows, Start/End of Line). Preserved `DEFAULT_IDS` strictly to the 7 original items, defaulting all additional items to disabled.
  8. In `CommaPopupsCustomizer.kt`: Configured the "Default" button to reset strictly to `DEFAULT_IDS`, and added an ergonomic recommendation tip (5–8 active items).
  9. Answered user query regarding SLM Whisper model storage: confirmed storage resides in persistent internal App Data (`context.noBackupFilesDir/voice_models/model.bin`), protected from Android OS cache cleaning and excluded from Google Cloud backup limits.
  10. Verified compilation via Gradle (`compileDebugKotlin`, `compileDebugJavaWithJavac` passed in 1m 45s). Conducted silent security scan per Mandates 2 & 3.
- **How it was verified**: local build only (`gradle compileDebugKotlin compileDebugJavaWithJavac` succeeded in 1m 45s).
- **Deviation from requested**: None.
- **Known issue or follow-up needed**: Ready for on-device manual QA testing.
