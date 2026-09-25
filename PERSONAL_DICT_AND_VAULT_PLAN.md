# Personal Dictionary & Privacy Vault Architecture Plan

## Executive Summary
This document establishes the streamlined, high-performance architecture for HeliBoard's **Personal Dictionary** and **Privacy Vault**. By leveraging HeliBoard's mature native typing engine for standard personal words and introducing a strictly isolated, lightweight in-memory storage for the Privacy Vault, this design achieves zero data leakage, instant typing performance ($O(1)$), shoulder-surfing protection (masked pills), and total privacy enforcement (zero-learning). Furthermore, background bloat from `TYPE_CONTACTS` and `TYPE_APPS` is completely eliminated.

---

## 1. Core Principles & Separation of Concerns

### 1.1 Normal Personal Dictionary (Mature HeliBoard Implementation)
* **Storage & Engine**: Continues using HeliBoard's mature `UserBinaryDictionary.java` and Android's `UserDictionary.Words`.
* **Typing Speed**: Compiled into native binary unigrams via JNI/C++ (`ExpandableBinaryDictionary`).
* **System Interoperability**: Normal vocabulary, custom jargon, and public shortcuts (`omw` -> `on my way`) remain visible to the Android system and other apps.
* **Settings UI**: Accessible via standard `PersonalDictionaryScreen.kt`.

### 1.2 Privacy Vault (Dedicated Isolated Storage)
* **Strict Sandbox Isolation**: Stored exclusively inside HeliBoard's private database (`heliboard.db`), wrapped by `VaultDao.kt` (following `ClipboardDao` / `VoiceReplacementDao`).
* **Zero Public Bleed**: Physically blocked from Android's system `UserDictionary.Words`. Third-party apps and OS spellcheckers have zero visibility.
* **In-Memory Cache**: Loaded at startup into a high-speed in-memory hash table `vaultCache: HashMap<String, VaultEntry>`.
  * Keystroke checks take $\sim 0.0001\,\text{ms}$ ($O(1)$ in RAM).
  * Consumes under 10 KB of memory.
* **Settings UI**: Dedicated screen at `Settings -> Security -> Privacy Vault`, mirroring the clean Material 3 list/search/dialog design of `PersonalDictionaryScreen.kt`.

### 1.3 Pruning Unwanted Dictionaries (`TYPE_CONTACTS` & `TYPE_APPS`)
* **Rationale**: Device contacts and installed apps introduce unnecessary background processing, privacy exposure, and redundant lookups on every keystroke.
* **Changes**:
  * Permanently remove `Dictionary.TYPE_CONTACTS` and `Dictionary.TYPE_APPS` from active lookup in `DictionaryFacilitatorImpl.kt`.
  * Ensure `DictionaryFacilitator.ALL_DICTIONARY_TYPES` only queries `TYPE_MAIN`, `TYPE_USER_HISTORY`, and `TYPE_USER`.
  * Eliminates permission requests and background polling for contacts and packages.

---

## 2. Typing Engine & Suggestion Strip Integration

### 2.1 Why the Vault is Kept Separate from the C++ Native Engine
1. **Masking Support**: The C++ native engine only outputs raw text (`mWord`) and has no concept of masked display labels (`mLabel`). A separate in-memory table allows setting `mLabel = "🔒 jo****om"` directly at generation.
2. **Auto-Correction Safety**: The native C++ engine fuzzy-matches typos against all unigrams. If vault words lived in C++, typing errors could auto-correct into private passwords or PINs.
3. **Zero-Learning**: Bypasses `UserHistoryDictionary`, ensuring passwords and private phrases are never learned or predicted after common words.

### 2.2 Suggestion Generation (`Suggest.kt`)
* When user types a shortcut that matches an entry in `vaultCache`:
  * Create a high-priority `SuggestedWordInfo`:
    * `mWord`: Raw secret text (e.g. `john@example.com`).
    * `mLabel`: Masked display pill (e.g. `🔒 jo****om`).
    * `mKindAndFlags`: Tagged with `KIND_VAULT_ENTRY` (or custom flag).
  * Partial Masking Rules:
    * Length $> 6$: First 2 characters + `****` + Last 2 characters (e.g. `🔒 jo****om`).
    * Length $\le 6$: First 1 character + `**` + Last 1 character (e.g. `🔒 p**9`).

### 2.3 Strip Rendering & Tap-to-Unlock (`LatinIME.java` & `InputLogic.java`)
1. **Rendering**: `SuggestionStripLayoutHelper.java` (line 194) automatically renders `mLabel` on the suggestion strip, protecting against shoulder-surfing.
2. **Tap Event**:
   * If `VaultSessionManager.isSecuritySessionValid()` is active (within 5-minute timeout): The secret `mWord` is committed immediately.
   * If session is expired or locked:
     * Keyboard displays `PatternUnlockView`.
     * Upon correct pattern entry: 5-minute session begins, pattern view closes, and secret is committed.
     * If no master pattern is set: Secret commits immediately with a hint to configure a pattern lock.
3. **Zero-Learning Enforcement**:
   * In `InputLogic.java`, `commitChosenWord()` explicitly skips `performAdditionToUserHistoryDictionary` for vault entries.
   * Suppresses suggestion spans and bigram learning.

---

## 3. Settings UI: `Settings -> Security -> Privacy Vault`

### 3.1 Route & Access Control
* **Route**: `SettingsDestination.PrivacyVault = "privacy_vault"`.
* **Pattern Gate**: Entering the screen checks `VaultSessionManager`. If locked, prompts for master pattern before granting access.

### 3.2 Visual Structure (Reusing `PersonalDictionaryScreen.kt` Pattern)
* **Search Header**: Filter entries by shortcut, phrase, or note.
* **List Item**:
  * Obscured / masked preview with a "peek" icon to reveal plaintext.
  * Shortcut pill (e.g. `[eml]`).
  * Note / identifier label (e.g. *"Personal Email"*).
  * Edit & Delete actions.
* **Floating Action Button (+)**: Opens "Add Vault Phrase" dialog.
* **Add / Edit Dialog**:
  * Secret Phrase input (with show/hide password toggle).
  * Shortcut trigger (e.g. `eml`, `pin`, `addr`).
  * Note / description field.
  * Action buttons: `Delete`, `Cancel`, `Save`.

---

## 4. Pruning Plan for `TYPE_CONTACTS` & `TYPE_APPS`

| Location | Action | Impact |
| :--- | :--- | :--- |
| `DictionaryFacilitator.java` | Remove `TYPE_CONTACTS` and `TYPE_APPS` from `ALL_DICTIONARY_TYPES` and `DYNAMIC_DICTIONARY_TYPES`. | Stops iteration and memory allocations for contacts/apps. |
| `DictionaryFacilitatorImpl.kt` | Remove `AppsBinaryDictionary` and `ContactsBinaryDictionary` creation in `createSubDict`. | Saves disk space, eliminates background package/contacts observers. |
| `SettingsValues.java` / `TextCorrectionScreen.kt` | Deprecate / remove "Suggest Contact names" and "Suggest Apps" preferences. | Cleans up settings UI and avoids dead toggles. |

---

## 5. File Modifications & Step-by-Step Roadmap

| Step | Target File | Description |
| :--- | :--- | :--- |
| **1** | `helium314/keyboard/latin/database/Database.kt` | Create `vault_entries` table in `heliboard.db` (`_id`, `shortcut`, `phrase`, `notes`, `timestamp`). |
| **2** | `helium314/keyboard/latin/database/VaultDao.kt` | Implement DAO with CRUD operations and fast in-memory cache (`vaultCache`). |
| **3** | `helium314/keyboard/latin/DictionaryFacilitator.java` & `Impl.kt` | Remove `TYPE_CONTACTS` and `TYPE_APPS` from dictionary types and subdict creation. |
| **4** | `helium314/keyboard/latin/Suggest.kt` | Add shortcut lookup against `VaultDao.vaultCache` and generate masked `SuggestedWordInfo`. |
| **5** | `helium314/keyboard/latin/inputlogic/InputLogic.java` | Check `VaultSessionManager` on vault suggestion click and bypass `UserHistoryDictionary`. |
| **6** | `helium314/keyboard/settings/screens/PrivacyVaultScreen.kt` | Replace placeholder screen with full Compose UI matching `PersonalDictionaryScreen.kt`. |
| **7** | `helium314/keyboard/settings/SettingsNavHost.kt` | Wire route, navigation icon, and pattern-gate check. |
| **8** | `res/values/strings.xml` | Add strings for vault dialogs, hints, mask labels, and security prompts. |

---

## 6. Verification & Security Assurance

1. **Zero Data Bleed**: Verify that vault entries never appear in `android.provider.UserDictionary.Words` or external content providers.
2. **Keystroke Performance**: Benchmark keystroke latency — in-memory cache lookup takes $\le 0.001\,\text{ms}$, running faster than before due to removal of contacts/apps lookups.
3. **Shoulder-Surfing Protection**: Verify suggestion strip displays `🔒 jo****om` instead of the raw email.
4. **Unlock Flow**: Verify pattern lock screen prompts when locked, and commits phrase upon correct unlock.
5. **No History Poisoning**: Confirm that typing or committing vault items records nothing in `UserHistoryDictionary`.
