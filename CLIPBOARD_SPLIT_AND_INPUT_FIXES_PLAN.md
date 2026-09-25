# Clipboard Split & Input Fixes Architecture Plan

## 1. Overview
This plan establishes the architecture and execution strategy for three key system improvements:
1. **Backspace / Delete Runaway Deletion Fix**: Eliminate accidental continuous deletion during rapid tapping.
2. **`?123` (`KEYCODE_SYMBOL_ALPHA`) Long-Press Parity**: Standardize long-press timing and suppress tap events when long-pressing `?123`.
3. **Clipboard & Prompt List Modal Split**: Dedicated presentation for Clipboard and Prompt List modals sharing the common modal shell and SQLite backend.

---

## 2. Core Architecture Specifications

### A. Input Engine: Backspace Tap Isolation
- **Problem**: In `InputLogic.java`, `mDeleteCount` increases on every backspace event and triggers accelerated deletion (`DELETE_ACCELERATE_AT = 20`) without differentiating between discrete key taps and continuous key repeats. Rapid taps prevent the timeout counter from resetting, causing runaway deletion.
- **Specification**:
  - 1 tap = exactly 1 character deleted.
  - Multi-character / accelerated deletion is strictly locked behind `event.isKeyRepeat()`.
  - Non-repeat tap releases immediately reset `mDeleteCount = 0`.
  - Key repeat timers are explicitly cancelled on pointer up in `PointerTracker.java`.

### B. Input Engine: `?123` Long-Press Parity
- **Problem**: `KEYCODE_SYMBOL_ALPHA` (`?123`) currently uses a 1.5× delay multiplier (`longpressTimeout * 3 / 2`), while standard alphabet keys use the base `longpressTimeout`. Additionally, releasing after a long press can trigger an accidental tap release.
- **Specification**:
  - Normalize long-press timeout for `KEYCODE_SYMBOL_ALPHA` to match standard alphabet keys (`Settings.mKeyLongpressTimeout`).
  - Upon firing `onLongPressed()`, immediately set `cancelKeyTracking()` and `mIsTrackingForActionDisabled = true` so `onUpEventInternal()` cannot register a subsequent tap release.
  - Retain touch-slop tolerance so minor finger drift during hold does not cancel long-press detection.

### C. Modals: Clipboard & Prompt List Separation
- **Shared Architecture**: Both modals share the unified outer modal shell (upper toolbar strip, staggered grid recycler, and bottom pinned keyboard dock).
- **Clipboard Modal**:
  - Retains multi-line text and media previews.
  - Long-press popup menu: **"Send to Prompt List"** (`ic_plus`), **Pin / Unpin**, and **Delete**.
  - **Edit** option is removed from the clipboard popup menu.
- **Prompt List Modal**:
  - **Position 0**: Dedicated 1-line card containing a centered `+` icon, placed in the first column like any standard card (not stretched across all columns). Tapping opens `showAddDialog()`.
  - **Positions 1..N**: Prompt item cards limited to **2-line height** (`maxLines = 2`, `ellipsize = END`).
  - Long-press popup menu on prompt items: **Edit**, **Pin / Unpin**, and **Delete**.

---

## 3. Phased Implementation Roadmap

### **Phase 1: Input Engine & Keystroke Isolation**
- **Objective**: Fix backspace runaway deletion and normalize `?123` long-press behavior.
- **Tasks**:
  1. `InputLogic.java`:
     - Update `handleBackspaceEvent` so acceleration is guarded by `event.isKeyRepeat()`.
     - In `onCodeInput`, ensure discrete non-repeat backspace inputs reset `mDeleteCount = 0`.
  2. `PointerTracker.java`:
     - Remove `KeyCode.SYMBOL_ALPHA` from the 1.5× timeout multiplier in `getLongPressTimeout()`.
     - In `onLongPressed()`, ensure `mIsTrackingForActionDisabled = true` is set when long-pressing `SYMBOL_ALPHA` to lock out any trailing tap release.
     - Verify explicit cancellation of repeat and long-press timers in `onUpEventInternal()`.
- **Milestone Gate**: Discrete tapping of backspace deletes 1 character per tap regardless of tap frequency. Long-pressing `?123` activates reliably with alphabet-key timing and zero phantom tap on release.

---

### **Phase 2: Clipboard Modal Action Refinement**
- **Objective**: Streamline the clipboard popup actions.
- **Tasks**:
  1. `ClipboardAdapter.kt`:
     - Remove the "Edit" button and its dialog logic from `showCompactActionMenu()`.
     - Retain and verify **Pin/Unpin**, **"Send to Prompt List"** (`PromptDao.addPrompt`), and **Delete**.
     - Verify existing multi-line card layout and item properties are preserved.
- **Milestone Gate**: Clipboard card long-press popup displays exactly 3 actions (Pin, Send to Prompt List, Delete), and saving a clip to prompts functions cleanly.

---

### **Phase 3: Prompt List Modal & Layout Customization**
- **Objective**: Implement the dedicated Prompt List layout and interaction model.
- **Tasks**:
  1. `PromptHistoryView.kt` (`PromptAdapter`):
     - Update `getItemCount()` to `(promptDao?.count ?: 0) + 1` to account for the add card.
     - Introduce two view types: `VIEW_TYPE_ADD_BUTTON` (0) and `VIEW_TYPE_PROMPT_ITEM` (1).
     - Build `AddButtonViewHolder`: 1-line height card with a centered `+` icon in the first column like a normal card; clicking triggers `showAddDialog()`.
     - Build/update `PromptViewHolder`: Card content restricted to 2 lines of text (`maxLines = 2`).
     - Wire long-press popup for prompt items to **Edit**, **Pin/Unpin**, and **Delete**.
     - Wire click on prompt items to commit text to the active input.
  2. Dialogs:
     - Implement/verify `showAddDialog()` for creating new prompts directly.
     - Retain `showEditDialog()` for editing existing prompts.
- **Milestone Gate**: Prompt list displays the 1-line `+` card at Position 0 in column 1, subsequent prompt cards display 2 lines of text, and both add and edit workflows operate cleanly.

---

### **Phase 4: Build Verification & Regression Testing**
- **Objective**: Verify full compilation, performance, and UI consistency across modals.
- **Tasks**:
  1. Run `compile_applet` and resolve any compilation issues.
  2. Verify smooth transitions between Main Keyboard, Clipboard Modal, and Prompt List Modal.
  3. Ensure no memory leaks or dangling database cursors across modal switches.
- **Milestone Gate**: Full applet compilation succeeds with zero errors.
