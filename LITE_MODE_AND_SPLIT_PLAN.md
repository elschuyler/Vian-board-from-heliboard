# Vianboard: Lite Mode & Split Modals Architecture Plan

This document details the architectural specifications for:
1. **System-Wide Lite Mode** (low-overhead, high-responsiveness mode)
2. **Clipboard & Prompt List Separation** (distinct visual modals sharing a unified SQLite database)

---

## Part 1: System-Wide Lite Mode

### 1. Overview & Philosophy
Lite Mode is a high-performance, resource-efficient operational mode designed to minimize RAM, CPU cycles, and battery consumption—ideal for budget devices—without sacrificing core typing intelligence, visual polish, or essential daily workflows.

### 2. User Configuration
- **Location**: Added to **Advanced Settings** (`AdvancedSettingsScreen.kt`).
- **Control**: Toggle switch between:
  - **Normal Mode** (Default/Full Baseline): Standard HeliBoard layout caching, pre-warmed modals, multi-candidate scoring.
  - **Lite Mode** (Performance Driver): Lightweight engine constraints, strict single-modal lifecycle, on-demand secondary layouts.

### 3. Engine Optimization & Scoring
- **Candidate Pool**:
  - Raw prediction engine search is capped at **8–9 candidates** (down from 15–18).
  - The suggestion strip continues to display the top 3 visible candidates plus auto-correct/typed word.
  - Saves over 50% CPU branch traversal and scoring overhead per keystroke.
- **Gesture Typing**:
  - Disabled in Lite Mode to eliminate background touch glide sampling and native path tracing overhead. Tap typing remains fluid and responsive.

### 4. Keyboard Layout Warmth & Lazy Loading
- **Kept Warm (Instant)**:
  - **Main Alphabet**: Always loaded and active.
  - **Symbols (`?123`)**: Always warm for rapid punctuation/symbol entry.
  - **Number Pad (PIN / Numeric input)**: Kept warm or directly loaded on demand for instant entry in password/PIN/numeric fields.
- **On-Demand (Lazy Loaded)**:
  - **More Symbols (`=<\`)** and **Phone** layouts: Only parsed and inflated when explicitly navigated to, and released when returning to alphabet.

### 5. Memory Trimming & Modal Lifecycle
- **Strict Single-Modal Rule**: Only one secondary view exists in RAM at a time. Opening a new modal closes and deallocates any prior view.
- **Zero Inactive Overhead**: Closing a modal (`X` button or returning to typing) tears down the view completely.
- **System Memory Hooks**: Integrates with `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` to drop transient drawables, caches, and inactive buffers whenever the keyboard is dismissed.

---

## Part 2: Clipboard & Prompt List Separation

### 1. Overview
Clipboard and Prompt List are maintained as **separate, dedicated modals with distinct visual identities and specialized features**, while sharing a single, lightweight SQLite database backend on disk.

### 2. Backend Architecture (Shared SQLite)
- **Single Database**: Managed entirely via `Database.kt` (single file on disk, single SQLite connection pool, zero duplicate daemons or background IPC).
  - **`CLIPBOARD` Table** (`ClipboardDao.kt`):
    - Dedicated to transient clipboard history, timestamps, size limits, pinned status.
    - Automatic FIFO eviction for unpinned items to prevent unbounded database growth.
  - **`PROMPTS` Table** (`PromptDao.kt`):
    - Dedicated to permanent quick notes, canned messages, snippets, templates, and category tags.
    - Permanent retention (immune to FIFO eviction).

### 3. Frontend / UI Separation
- **Separate Dedicated Modals**:
  - `ClipboardHistoryView`: Accessible via the dedicated Clipboard toolbar/key action.
  - `PromptHistoryView`: Accessible via the dedicated Prompt List toolbar/key action.
- **Preserved Visual Identity**:
  - The **current visual UI** for both Clipboard and Prompt List is strictly preserved (keeping cards, headers, fonts, spacing, and styling intact).
  - No merging of views or confusing hybrid layouts.

### 4. Specialized Feature Adjustments
- **Clipboard Modal**:
  - Card-based layout with timestamps and pin/unpin indicators.
  - Quick action chips: Pin, Delete, Paste.
  - **Action Menu Refinement**: The **"Edit"** option is removed from the long-press card popup dialog, leaving clean, essential actions (Pin/Unpin, Delete).
- **Prompt List Modal**:
  - Retains its structured, high-density note layout.
  - Fast tap-to-insert mechanism into the active text field.
  - Category tags and quick "+ Add Note" action bar.

---

## Implementation Roadmap

### Phase A: Clipboard Popup Refinement
1. Modify `ClipboardAdapter.kt` / `ClipboardHistoryView.kt` to remove the "Edit" action from the card long-press dialog.
2. Ensure Pin/Unpin and Delete actions remain responsive and clean.

### Phase B: Advanced Settings Preference
1. Add `PREF_LITE_MODE` key to `Settings.java` / `SettingsValues.kt`.
2. Add a switch preference in `AdvancedSettingsScreen.kt` for "Lite Mode" with descriptive summary text.

### Phase C: Engine Candidate Tuning (Lite Mode)
1. In `Suggest.kt` / `DictionaryFacilitatorImpl.kt`, inspect Lite Mode state:
   - Cap candidate search collection to 8–9 candidates when Lite Mode is active.
2. In gesture settings / input logic, bypass gesture touch tracking when Lite Mode is active.

### Phase D: Keyboard Layout Caching & On-Demand Loading
1. In `KeyboardSwitcher.java`, keep Alphabet, Symbol (`?123`), and Number Pad hot/ready.
2. Make secondary symbol variants (`=<\`) and Phone layouts lazy-loaded on demand under Lite Mode.

### Phase E: Lifecycle & Memory Trimming
1. Enforce single-modal dismissal and view teardown in `KeyboardSwitcher.java` and `LatinIME.java`.
2. Hook into `onTrimMemory` to clear non-essential caches when the keyboard is hidden.
