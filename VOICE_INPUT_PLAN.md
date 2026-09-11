# Offline Voice Input (Whisper) Architectural & Implementation Plan

---

## 1. Executive Summary & Goals
* **Target Experience**: Offline, on-device voice input modeled after FUTO Keyboard and Whisper.cpp, strictly decoupled from Google/system voice typing.
* **Process Isolation**: Audio recording and native Whisper AI inference execute in a dedicated, on-demand secondary process (`:voice`). The main IME process remains impervious to native crashes or out-of-memory (OOM) conditions.
* **Form Factor**: Compact drawer modal (~140–160dp height) positioned cleanly above the system navigation bar.
* **Zero APK Bloat**: No binary model bundled in the base APK. Quantized models (`.bin`) are imported on-demand via Settings or downloaded post-install into `no_backup/voice_models/`.
* **Personal Dictionary**: "Word Improvement" table mapping recognized phonetic errors or domain terms to desired text.
* **Backup & Logging**: Word improvements and voice preferences are serialized into VianBoard's JSON backup (models excluded). Log Keeper catches and logs engine lifecycle and errors.

---

## 2. Technical Architecture & Constraints

```
┌────────────────────────────────────────────────────────┐
│ Main IME Process (:root)                               │
│                                                        │
│  [Toolbar Mic] / [Comma Popup]                         │
│         │                                              │
│         ▼                                              │
│  VoiceInputView (Modal, ~150dp)                        │
│  ├── [Pulse Circle] (Blue/Green/Red)                   │
│  ├── [3-Stage Gain Toggle] (1x / 2x / 4x)              │
│  ├── [Streaming Word Display] (Tap to Pause/Resume)    │
│  └── [Bottom Row: ABC | SPACE | Backspace | Enter]     │
│         │                                              │
│         │ (IPC: Messenger / Binder)                    │
└─────────┼──────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────┐
│ On-Demand Secondary Process (:voice)                   │
│                                                        │
│  VoiceInputService (Lifecycle: Lazy Start / 60s Idle)  │
│  ├── AudioRecord Pipeline (PCM 16-bit, 16kHz mono)     │
│  │    └── Software Digital Gain (1x, 2x, 4x limiter)   │
│  ├── WebRTC VAD / Energy Detector (Speech pauses)      │
│  ├── Whisper Engine JNI (libwhisper.so / GGML)         │
│  └── Post-Processor (Word Improvement Filter)          │
│                                                        │
│  Model Storage: /data/user/0/.../no_backup/models/     │
└────────────────────────────────────────────────────────┘
```

### Critical Constraints (GPS Protocol Verification)
1. **Memory Budget**: Unisoc / entry-level devices require small quantized models (e.g. `ggml-tiny-q5_1.bin` ~31 MB or `tiny.en.q8` ~42 MB). Peak memory must remain under 120 MB in `:voice`.
2. **Crash Insulation**: If native C++ code aborts or hits a SIGSEGV, only `:voice` dies. The main IME UI detects Binder death, resets the modal to idle/error, and keeps the keyboard fully functional.
3. **Backup Immunity**: Never serialize `.bin` model files in Android Cloud Backup or VianBoard JSON exports. Store models exclusively in `context.noBackupFilesDir`.

---

## 3. Mini-Phases Execution Plan

### Mini-Phase 1: Settings, Database & Decoupling Legacy Voice
1. **Decouple Legacy Voice**:
   * Sever `KeyCode.VOICE_INPUT` from `RichInputMethodManager.switchToShortcutIme()`.
   * Re-route `KeyCode.VOICE_INPUT` to trigger VianBoard's internal Voice Modal.
2. **Word Improvement Database (Room)**:
   * Create `VoiceReplacementEntity` (`id`, `originalWord`, `replacementWord`, `isWholeWord`, `createdTimestamp`).
   * Create `VoiceReplacementDao` with queries for exact-match replacement and list observation.
   * Add database migration to `VianBoardDatabase`.
3. **Settings Screen (`VoiceInputSettingsScreen.kt`)**:
   * Enable/Disable master switch.
   * Model status card (Active model name, size, or "No model imported").
   * **Import Model** button (using zero-permission `ActivityResultContracts.GetContent` / `OpenDocument`).
   * **Word Improvement** manager screen (add, edit, delete phonetic corrections).
   * Default sensitivity selector (1x, 2x, 4x).
4. **Backup & Log Integration**:
   * Add `voice_replacements` and voice preference keys to `BackupRestoreHelper.kt`.
   * Explicitly exclude `no_backup/voice_models/` directory from backup.
   * Register error codes in `LogCatcher` for voice initialization and audio failures.

---

### Mini-Phase 2: Compact Modal UI & State Transitions
1. **View Component (`VoiceInputView.kt`)**:
   * Height fixed to ~150dp with dynamic `WindowInsets` padding for system navigation bar.
   * Themed using existing `ColorType` and `KeyboardTypeface` standards.
2. **Bottom Control Row (4 Unified Buttons)**:
   * `[ABC]`: Closes voice modal and returns to primary QWERTY keyboard.
   * `[SPACE]`: Pauses listening and inserts space into active input connection.
   * `[⌫]`: Pauses listening and deletes previous character/token.
   * `[↵]`: Pauses listening and sends action/newline.
3. **Real-Time Word Streamer**:
   * Single-line text display previewing the latest recognized words.
   * Clicking the text area toggles between **Listening** and **Paused**.
4. **Status & Sensitivity Controls**:
   * Dynamic pulsating indicator:
     * **Blue**: Ready / Listening / Standby.
     * **Green**: Audio detected / Transcribing words.
     * **Red**: Error / Mic permission denied / Audio pipeline failure.
   * 3-Stage Gain Toggle: Compact pill button (`1x` $\to$ `2x` $\to$ `4x` $\to$ `1x`) updating the digital gain factor in real-time.
5. **Keyboard Integration**:
   * Add `VOICE` mode to `KeyboardState.Mode` and `KeyboardSwitcher.java`.
   * Add Mic icon to suggestion strip toolbar (`ToolbarKey.VOICE`).
   * Wire Mic shortcut into the comma key long-press popup list.

---

### Mini-Phase 3: Multi-Process Audio Pipeline & IPC Service
1. **Manifest & Process Declaration**:
   * Declare `VoiceInputService` with `android:process=":voice"`.
   * Ensure `RECORD_AUDIO` permission is declared and handled via runtime permission checks.
2. **IPC Protocol**:
   * High-throughput, low-latency Messenger/AIDL interface between `LatinIME` and `VoiceInputService`:
     * Commands: `START_LISTENING`, `PAUSE_LISTENING`, `SET_GAIN`, `STOP_AND_RELEASE`.
     * Events: `ON_PARTIAL_TRANSCRIPT`, `ON_FINAL_TRANSCRIPT`, `ON_STATE_CHANGE`, `ON_ERROR`.
3. **Audio Capture Engine**:
   * Standard 16-bit 16kHz PCM `AudioRecord` runner in `:voice`.
   * Software Gain Filter: Linear multiplication on 16-bit PCM shorts with peak-clipping protection.
   * Energy-based Voice Activity Detection (VAD) to segment incoming speech into discrete buffers without overheating mobile chips.
4. **Lifecycle & Power Safety**:
   * 60-second idle auto-shutdown: If modal is closed or user does not speak for 60 seconds, `:voice` releases the audio pipeline and frees model RAM.

---

### Mini-Phase 4: FUTO Whisper Engine & GitHub Actions CMake Pipeline
1. **FUTO JNI Architecture Adaptation**:
   * Integrate FUTO's streamlined Whisper JNI bridge (`WhisperEngine.kt` / `libwhisper.so` interface adapted from `futo-org/voice-input`).
   * Zero repo bloat: no massive C++ source trees or heavy binaries checked into Git.
2. **GitHub Actions CMake Build Pipeline**:
   * Update `.github/workflows/build-apk.yml` with an automated CMake NDK compilation step.
   * Compiles optimized `libwhisper.so` with ARM NEON for `arm64-v8a`, `armeabi-v7a`, and `x86_64` directly on the GitHub Actions runner at build time before `assembleRelease`/`assembleDebug`.
   * Places generated `.so` binaries into `app/src/main/jniLibs/` during CI execution.
3. **Model Import, Validation & Management**:
   * Safely stream imported GGML/Whisper `.bin` models into `/data/user/0/<package>/no_backup/voice_models/model.bin`.
   * Validate Whisper GGML magic header (`0x67676d6c` / `ggml`) and quantization type before activating.
4. **Word Improvement Post-Processing & Text Delivery**:
   * Intercept raw Whisper text output chunks.
   * Apply Room-backed `VoiceReplacement` substitutions (case-insensitive, whole-word boundaries, e.g., "knit" $\to$ "need").
   * Deliver final transformed text cleanly to the active document via `LatinIME.onTextInput()`.

---

## 4. Verification & QA Suite

* **Unit & IPC Tests**:
  * Word improvement substitution unit tests (case-insensitivity, boundary matches, punctuation handling).
  * Gain multiplier saturation and clipping tests.
* **On-Device Manual QA Flow**:
  1. Verify legacy Google voice switch is replaced by VianBoard internal modal.
  2. Verify 4 bottom buttons perform correct action and pause engine.
  3. Verify tap on streaming text pauses and resumes listening.
  4. Verify Word Improvement replaces configured words.
  5. Verify Settings model import and JSON backup/restore exclude `.bin` model files.
