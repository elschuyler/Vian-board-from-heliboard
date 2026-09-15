# Voice Input Import & Modular Architecture Plan

This document outlines the step-by-step strategy for importing the offline Voice Input subsystem from the `vianboard/` repository into the main VianBoard codebase, connecting all components to **LogKeeper (`LogCatcher`)**, and implementing a **Modular Backup & Restore** system.

---

## 1. Architectural Guardrails & Principles

1. **Zero Regression Guarantee**:
   - The main repository currently contains:
     - **Security & Pattern Lock Vault** (`SecurityScreen.kt`, `PatternLockSettingsScreen.kt`, `PatternGridView.kt`, etc.)
     - **Desktop Shortcuts** (`DesktopShortcutsView.kt`, `desktop_shortcuts_view.xml`, `DesktopShortcutsCustomizer.kt`)
     - **Comma Popups Customizer** (`CommaPopupsCustomizer.kt`, `CommaPopupsCatalog.kt`)
   - All imports and file merges must preserve these existing features without overwriting layout structures or keycode routing.

2. **Isolated Multi-Process Architecture (`:voice`)**:
   - Audio recording and native Whisper AI inference execute in an isolated background service process (`android:process=":voice"`).
   - This keeps the primary keyboard UI thread immune to audio buffer locks, CPU-intensive inference delays, and native memory pressure.
   - Automatically unbinds and terminates after 60 seconds of idle inactivity.

3. **Universal LogKeeper (`LogCatcher`) Telemetry**:
   - Every module (Audio Pipeline, VAD, IPC Bridge, Whisper Engine, File Manager, Backup & Restore) reports state transitions, timing, and errors to `LogCatcher`.
   - **Privacy Policy**: Strictly ZERO audio content, audio waveforms, or transcribed speech text are recorded in logs. Logs track only component states, error codes, buffer performance, and execution latency.

4. **Modular Backup & Restore**:
   - Users can choose which modules to back up and restore independently via a modular dialog with granular checkboxes:
     - Settings & Layouts
     - User Dictionaries & History
     - Clipboard & Prompt Snippets
     - Voice Improvement Dictionary (phonetic replacements)
     - Security & Vault configurations
   - Heavy binary voice models (`.bin` files, 40MB–150MB+) are stored in `no_backup/` and excluded from default backup archives to keep backups fast, lightweight (<100KB), and portable.

---

## 2. Granular Mini-Phases

### Mini-Phase 1: Database Schema Upgrade & Voice Model Storage
* **Objective**: Establish persistent data structures for voice phonetic corrections and local model file management.
* **Tasks**:
  1. Copy `VoiceReplacementDao.kt` into `helium314.keyboard.latin.database`.
  2. Upgrade `Database.kt` schema from version 3 to 4:
     - Add `VoiceReplacementEntity` (`VOICE_REPLACEMENTS` table).
     - Add database migration handler `MIGRATION_3_4`.
  3. Copy `VoiceModelManager.kt` to `helium314.keyboard.latin.voice`:
     - Store Whisper models in `context.noBackupFilesDir / voice_models`.
     - Implement binary header validation (`0x67676d6c` / `ggml` magic bytes).
     - Safe deletion and storage size reporting.
* **LogKeeper Integration**:
  - `LogCatcher.markComponentActive("VoiceModelManager", "Storage", "Initialized")`
  - Log model import file names, byte sizes, and validation success/failure.
  - Log database migration events.
* **Verification**: Run `compile_applet`.

---

### Mini-Phase 2: Native Whisper JNI Engine & CI Workflow
* **Objective**: Vendor the C/C++ Whisper inference engine with defensive runtime binding and automated GitHub Actions compilation.
* **Tasks**:
  1. Copy the C/C++ Whisper source tree to `app/src/main/jni/whisper/`:
     - Upstream MIT `whisper.cpp` (v1.5.4) and `ggml`.
     - `jni_whisper.cpp` JNI wrapper with dynamic audio context sizing (`audio_ctx = (n_samples / 160) + 32`).
     - `CMakeLists.txt` configured with ARM NEON intrinsics and `-O3`.
  2. Copy `WhisperEngine.kt` to `helium314.keyboard.latin.voice`:
     - Defensive JNI loading (`try { System.loadLibrary("whisper") }`) to allow compilation in environments without NDK.
     - Token post-processing, whitespace trimming, and hallucination cleanup.
  3. Update `.github/workflows/build-apk.yml`:
     - Ensure automated CMake compilation for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
* **LogKeeper Integration**:
  - `WhisperEngine` updates `LogCatcher` component status:
    - `"Native Library Ready"` upon successful `.so` load.
    - `"Active (Context: <ptr>)"` on model load.
    - `"Transcribing (<duration>s audio)"` during inference.
    - `"Idle (Last inference: <ms>ms)"` on completion.
    - Detailed exception traces on JNI or OOM errors.
* **Verification**: Run `compile_applet`.

---

### Mini-Phase 3: Real-Time Audio Pipeline & Isolated IPC Service
* **Objective**: Implement low-latency audio capture and the isolated multi-process service.
* **Tasks**:
  1. Update `AndroidManifest.xml`:
     - Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />`.
     - Declare `VoiceInputService` with `android:process=":voice"` and `android:exported="false"`.
  2. Copy `AudioRecordPipeline.kt`:
     - 16kHz, 16-bit mono PCM stream capture.
     - 3-stage gain multiplier (`1x`, `2x`, `4x`) with soft clipping limiter.
     - RMS audio level calculations throttled to 40ms.
     - Automatic fallback from `MediaRecorder.AudioSource.VOICE_RECOGNITION` to `MIC`.
  3. Copy `EnergyVad.kt`:
     - Energy-based voice activity detection with configurable threshold.
     - Speech onset detection and pause segment triggering.
  4. Copy `VoiceIpcProtocol.kt`:
     - Messenger IPC constants for commands (`START`, `STOP`, `PAUSE`, `RESUME`, `SET_GAIN`) and events (`RMS_UPDATE`, `STATE_CHANGE`, `INTERIM_TEXT`, `FINAL_TEXT`, `ERROR`).
  5. Copy `VoiceInputService.kt`:
     - Runs in `:voice` process.
     - Background speech accumulation, streaming preview worker (interim transcripts every 800ms on 2 threads), and 60-second idle auto-shutdown.
  6. Copy `VoiceInputConnection.kt`:
     - Service connection bridge with `DeathRecipient` for automatic recovery if the OS kills the `:voice` process.
* **LogKeeper Integration**:
  - Log audio pipeline start, buffer allocation, and mic fallback events.
  - Log `:voice` service process binding, disconnects, and termination.
  - Log VAD speech start and pause triggers.
* **Verification**: Run `compile_applet`.

---

### Mini-Phase 4: Permission Handling & Voice Settings UI
* **Objective**: Provide zero-flicker permission acquisition and full settings management.
* **Tasks**:
  1. Copy `VoicePermissionActivity.kt`:
     - Transparent, zero-history activity that requests `RECORD_AUDIO` permission without collapsing or flickering the active keyboard window.
  2. Register `VoicePermissionActivity` in `AndroidManifest.xml`.
  3. Copy `VoiceInputScreen.kt`:
     - Mic Sensitivity toggle (`1x Normal`, `2x Sensitive`, `4x High Gain`).
     - Whisper Model Card (status, imported file details, import `.bin` launcher, delete model).
     - Word Improvement Dictionary Editor (list, add, edit, and delete phonetic replacement pairs).
  4. Integrate `VoiceInputScreen` into `SettingsNavHost.kt` and `MainSettingsScreen.kt`.
* **LogKeeper Integration**:
  - Log permission request results (Granted / Denied / Rationale).
  - Log Word Improvement dictionary modifications.
  - Log microphone sensitivity setting changes.
* **Verification**: Run `compile_applet`.

---

### Mini-Phase 5: IME Keyboard Frame & Voice Modal UI
* **Objective**: Build the visual voice input interface within the keyboard window.
* **Tasks**:
  1. Copy `VoicePulseView.kt`:
     - Custom Canvas animated pulse visualizer responding to real-time RMS energy.
     - Visual states: Listening (pulsing green/accent), Paused (amber), Error (red), Idle.
  2. Copy `voice_input_view.xml` and `VoiceInputView.kt`:
     - Compact 150dp modal height.
     - Theme-adaptive colors matching active keyboard theme.
     - Bottom control bar with standard keyboard keys: `[ABC]`, `[SPACE]`, `[⌫]`, `[↵]`.
     - Cycle Gain pill button (`1x/2x/4x`).
  3. Update `strip_container.xml`:
     - Add `voice_preview_strip` (2-line auto-ellipsizing preview text + `[✕]` clear button).
  4. Update `main_keyboard_frame.xml`:
     - Add voice modal view container frame.
  5. Update `KeyboardSwitcher.java`:
     - Manage voice modal view visibility and preview strip lifecycle.
  6. Update `LatinIME.java`:
     - Route `KeyCode.VOICE_INPUT` to internal voice input modal (decoupling from external Google voice typing).
     - Intercept hardware Back key to dismiss voice modal before closing keyboard.
* **LogKeeper Integration**:
  - Log voice modal presentation and dismissal events.
  - Log auto-commit and cancel interactions.
* **Verification**: Run `compile_applet`.

---

### Mini-Phase 6: Modular Backup & Restore System
* **Objective**: Upgrade the backup/restore engine to support modular, granular export and restoration with full LogKeeper diagnostics.
* **Tasks**:
  1. Update `BackupRestorePreference.kt` to replace the single confirmation dialog with a **Modular Selection Dialog**:
     - Checkbox: **Settings & Appearance** (Shared preferences, custom toolbar keys, comma popups order)
     - Checkbox: **User Dictionaries & History** (Personal dictionaries, typed word frequencies)
     - Checkbox: **Clipboard & Prompts** (Saved clips, pinned notes, prompt library)
     - Checkbox: **Word Improvement Dictionary** (Phonetic voice replacements)
     - Checkbox: **Security Vault** (Pattern lock hash & secured items - with safety confirmation)
  2. Implement modular zip structure:
     - `manifest.json`: List of exported modules, timestamp, app version, and schema version.
     - `preferences/`: App preferences JSON.
     - `database/`: Extracted module JSONs or modular table dumps for selective restore.
     - `dictionaries/`: User dictionary files.
  3. Ensure binary models (`.bin`) remain excluded by default to avoid huge archives, with clear UI messaging.
  4. Implement selective restore logic:
     - Reads `manifest.json` from the chosen backup archive.
     - Displays checkboxes for only the components present in the archive.
     - Restores only selected categories without overwriting other unselected user data.
* **LogKeeper Integration**:
  - `LogCatcher.markComponentActive("BackupRestoreEngine", "Data", "Backup Started")`
  - Log list of selected modules, total archive size, and elapsed compression time.
  - On restore: log archive validation, schema check, record count per module, and completion status.
  - Comprehensive exception catching with full stack traces recorded in `LogCatcher.e()`.
* **Verification**: Run `compile_applet`.

---

### Mini-Phase 7: Gesture Library Loader & Accidental Number Typo Engine
* **Objective**: Integrate the remaining auxiliary enhancements from `vianboard`.
* **Tasks**:
  1. Copy `LoadGestureLibPreference.kt` into `helium314.keyboard.settings.preferences`:
     - Allows users to dynamically load external gesture typing libraries (`libjni_latinimegoogle.so`).
     - Includes ABI compatibility checks and SHA-256 verification.
  2. Wire `LoadGestureLibPreference` into `WordEngineScreen.kt`.
  3. Update `JniUtils.java` with library loader and checksum helper methods.
  4. Update `WordComposer.java` and `Suggest.kt`:
     - Add accidental number typo engine (e.g., handles accidental digit presses from number row proximity such as `w0rd` -> `word`).
* **LogKeeper Integration**:
  - Log gesture library import attempts, ABI validation, and checksum match results.
  - Log accidental digit autocorrect trigger counts in debug mode.
* **Verification**: Run `compile_applet`.

---

### Mini-Phase 8: End-to-End Diagnostic Audit & System Verification
* **Objective**: Comprehensive compilation, lifecycle verification, and visual audit.
* **Tasks**:
  1. Run full compilation check with `compile_applet`.
  2. Audit `LogKeeperActivity`:
     - Verify "Active Components" tab lists `VoiceInputService`, `WhisperEngine`, `AudioRecordPipeline`, and `BackupRestoreEngine` alongside `SecurityVault` and `System`.
     - Verify clean error and crash report formatting in the "Errors" tab.
  3. Verify zero regressions:
     - Security Vault / Pattern Lock unlock flow.
     - Desktop Shortcuts view.
     - Comma Popups menu customizations.
* **Verification**: Run `compile_applet` and verify clean build status.

---

## 3. LogKeeper Telemetry Matrix

| Subsystem | Tracked Events | Component Name in LogKeeper | Privacy Guarantee |
| :--- | :--- | :--- | :--- |
| **Audio Pipeline** | Buffer init, sample rate (16kHz), gain multiplier changes, audio source fallback | `AudioPipeline` | No audio samples logged |
| **Voice Service** | `:voice` process start, client connection, 60s idle timeout shutdown, OS kill/reconnect | `VoiceInputService` | Process PID and timestamps only |
| **Energy VAD** | Speech onset, silence detection, pause duration | `VoiceVad` | Timing and energy levels only |
| **Whisper Engine** | JNI `.so` load status, context pointer, audio duration, inference latency (ms), OOM | `WhisperEngine` | No transcribed text logged |
| **Model Storage** | `.bin` import, file size, GGML header check (`0x67676d6c`), delete action | `VoiceModelManager` | File metadata only |
| **Backup & Restore** | Modular category selection, archive size, entry counts, restore validation, I/O errors | `BackupRestoreEngine` | Record counts and file sizes only |
| **Security Vault** | Lock state changes, failed pattern attempts, timeout locks | `SecurityVault` | No passwords or pattern hashes logged |

---

## 4. Execution Readiness

Each mini-phase above is self-contained, keeps code changes small and reviewable, and compiles cleanly at every milestone.
