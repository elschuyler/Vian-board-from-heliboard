# FUTO-Aligned Whisper Voice Engine, Lite Mode Prediction Parity & Vault Lifecycle

Comprehensive plan to achieve 100% parity with FUTO Voice Input architecture, restore complete dictionary prediction depth in Lite Mode (fixing spacebar/'v' proximity typos), and implement an on-demand Vault session lifecycle.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions synthesize your inputs and finalized specifications:

- **Voice Model Delivery**: Model `.bin` files will be imported by the user directly after installation via the Android Storage Access Framework (SAF) document picker. No external cloud download or hidden background fetch.
- **Mic Upgrade Button**: When no offline model is loaded, the microphone modal features a functional "Upgrade / Import Model" button that immediately opens the system file picker to select a compatible Whisper GGML `.bin` file, validates the model headers, and enables offline transcription.
- **Pure Offline Engine (No System STT)**: All fallbacks to Android's platform `SpeechRecognizer` are completely severed. The app functions exclusively as a self-contained local whisper engine.
- **Lite Mode Dictionary Parity**: Lite Mode will maintain full algorithmic parity with standard mode for all dictionary lookups, two-word corrections, and space/proximity candidate generation (resolving typing 'v' instead of space). Lite Mode optimizations will remain restricted to rendering and layout cache management.
- **Vault Session Lifecycle**: Vault sessions open only on explicit gate unlock and automatically close (clearing all plaintext secret buffers and encryption keys) whenever the keyboard is hidden or the modal is dismissed. Pasting does not prematurely terminate the active modal session.

---

## 1. Overview & Core Concept

This milestone addresses three foundational systems in Vianboard:
1. **FUTO Voice Input Engine Integration**: A standalone, offline speech-to-text pipeline matching the FUTO Voice Input architecture (`futo-org/voice-input`). It operates without any system speech recognition service, compiles `libwhisper.so` via GitHub Actions CI for all target ABIs, supports user-driven `.bin` model imports with validation, provides real-time animated bouncing audio waveforms, and bridges all diagnostic events to Log Keeper.
2. **Linguistic & Suggestion Parity in Lite Mode**: Eliminates the regression where typing 'v' instead of space failed to autocorrect. Restores full candidate evaluation (`includeAtLeastTwoWordSuggestions`, space-proximity scoring) while maintaining Lite Mode's low-memory footprint on rendering.
3. **Vault Session Containment**: Isolates decrypted vault credentials to an active modal session. The vault automatically locks and zeroes sensitive buffers when the keyboard is dismissed or the modal closes, without disrupting multi-item access during active use.

---

## 2. User Experience & Visual Design

### Key User Flows

#### A. Voice Input & Model Import Flow
```
[Mic Key Pressed]
       │
       ├── Model Missing? ──► [Voice Modal Opens]
       │                            │
       │                            ├── "Offline Whisper Required"
       │                            └── [Import .bin Model Button]
       │                                           │
       │                                           ▼
       │                                  [System File Picker]
       │                                           │
       │                                   (Selects model.bin)
       │                                           │
       │                                           ▼
       │                                  [Validates & Installs]
       │                                           │
       │                                           ▼
       │                                   [Mic State: Ready]
       │
       └── Model Present ──► [Voice Modal Opens & Records]
                                    │
                                    ├── Live Bouncing Waveform Bars (16 bands)
                                    ├── Real-time RMS Amplitude Feedback
                                    └── [Transcribe Pass] ──► Text Committed
```

#### B. Vault Gate Unlock & Dismissal Flow
```
[Vault Access Key] ──► [Gate Unlock Prompt (PIN/Biometric)]
                              │
                              ▼ (Unlocked)
                     [Vault Explorer Modal]
                              │
               ┌──────────────┴──────────────┐
               ▼                             ▼
       [Copy/Paste Items]           [Exit or Dismiss]
       (Session stays alive)                 │
                                             ▼
                                    [Wipe Session Keys]
                                    [Lock Vault Instantly]
```

### Visual Specifications & Feedback
- **Audio Waveform Visualizer**: 16 reactive vertical bars rendered via Canvas / Compose with smooth spring dampening (`Animatable`). Bar heights scale proportionally from 4.dp (idle) to 48.dp (peak vocal amplitude).
- **Status Indicators**:
  - *No Model State*: Amber pill badge indicating "No Model Loaded", with a prominent high-contrast "Import Whisper Model" button.
  - *Recording State*: Pulsing coral/red mic ring indicator with synchronized audio bars.
  - *Transcribing State*: Subtle shimmer progress bar across the voice dock while `libwhisper` processes audio chunks.
- **Log Keeper Transparency**: Any I/O error, JNI linking issue, or model validation failure surfaces an actionable error pill and records an exact sanitized trace to the Log Keeper.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Pure Offline Whisper vs. System STT Fallback
- **Chosen Approach**: Completely remove and bypass Android's standard `SpeechRecognizer` API.
- **Why**: The user's device lacks proprietary or platform speech-to-text engines. Offering or falling back to system STT causes silent freezes or confusing "Recognition service not found" dialogs. The keyboard must rely solely on its embedded Whisper C++ engine.
- **Trade-off**: Requires users to provide a GGML `.bin` model file (e.g., Whisper Tiny/Base/Small en/multilingual) on initial use. Handled gracefully via the direct file picker integration.

### Decision 2: Dual-Namespace JNI ABI Compatibility
- **Chosen Approach**: Export JNI function bindings for both `org_futo_voiceinput_whisper_WhisperEngine` and `helium314_keyboard_latin_voice_WhisperEngine`.
- **Why**: Matches the official FUTO build outputs if prebuilt binaries are supplied or generated in GitHub Actions, while preserving seamless internal package references without class loader mismatches.
- **Alternatives Considered**: Forcing only internal package names breaks compatibility with official FUTO drop-in binaries. Dual-export solves this at zero runtime cost.

### Decision 3: Lite Mode Prediction Architecture
- **Chosen Approach**: Unlink dictionary evaluation depth from Lite Mode flags. Run identical scoring and multi-word proximity logic in both modes, while maintaining Lite Mode optimizations exclusively in view hierarchy retention and memory trimming.
- **Why**: Typing accuracy and spacebar correction (e.g. 'v' vs space) are core keyboard functionality. Crippling the dictionary search tree severely impacts usability for minimal RAM savings.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                          LatinIME Controller                           │
└──────────────┬─────────────────────────┬───────────────────────┬───────┘
               │                         │                       │
               ▼                         ▼                       ▼
   ┌───────────────────────┐ ┌───────────────────────┐ ┌────────────────┐
   │ DictionaryFacilitator │ │   VoiceInputView      │ │  VaultSession  │
   │  & Suggest Engine     │ │   & Audio Pipeline    │ │    Manager     │
   ├───────────────────────┤ ├───────────────────────┤ ├────────────────┤
   │ • Full Multi-Word     │ │ • AudioRecord (16kHz) │ │ • Gate Unlock  │
   │ • Space-Proximity     │ │ • 16-Band Waveform    │ │ • Auto-Lock on │
   │ • Unrestricted Lite   │ │ • Mic Upgrade Picker  │ │   Hide/Dismiss │
   │   Prediction Logic    │ │ • Pure Offline Only   │ │ • Zero Buffers │
   └───────────┬───────────┘ └───────────┬───────────┘ └────────────────┘
               │                         │
               ▼                         ▼
   ┌───────────────────────┐ ┌──────────────────────────────────────────┐
   │ Native Binary Dict    │ │              WhisperEngine               │
   │ libjni_latinime.so    │ ├──────────────────────────────────────────┤
   │                       │ │ • JNI C++ Bridge (Dual Export)           │
   │                       │ │ • User Imported .bin Model Loader        │
   │                       │ │ • Log Keeper Error & Metric Piping       │
   └───────────────────────┘ └───────────────────┬──────────────────────┘
                                                 │
                                                 ▼
                                     ┌───────────────────────┐
                                     │    libwhisper.so      │
                                     │ (Built via GH Actions)│
                                     └───────────────────────┘
```

### Component & State Mapping

1. **`VoiceModelManager.kt`**:
   - Manages active model file in `context.noBackupFilesDir / "model.bin"`.
   - `importModel(uri: Uri)`: Streams file via `ContentResolver`, verifies GGML magic header (`0x67676d6c` / `ggml`), atomically moves to destination, and notifies listeners.
   - Fully connected to `LogKeeper` for all I/O milestones and verification failures.

2. **`VoiceInputView.kt` & `AudioRecordPipeline.kt`**:
   - Audio recording at 16,000 Hz, 16-bit mono PCM.
   - Computes RMS amplitude every 50ms and publishes to waveform visualizer.
   - Dynamic UI state:
     - `NO_MODEL`: Displays "Offline Whisper model required" + "Import .bin Model" button.
     - `READY` / `RECORDING`: Displays active bouncing bars and stop/cancel controls.
     - `PROCESSING`: Displays transcription spinner and commits text to InputConnection.

3. **`WhisperEngine.kt` & `jni_whisper.cpp`**:
   - Dual-exported native methods (`initContext`, `fullTranscribe`, `freeContext`).
   - Graceful load detection: if `libwhisper.so` is missing, logs clear build advice and dispatches to Log Keeper without crashing.

4. **`DictionaryFacilitatorImpl.kt` & `Suggest.kt`**:
   - Restore `includeAtLeastTwoWordSuggestions` and remove artificial limits in Lite Mode.
   - Maintain full proximity matrix calculations for spacebar adjacent touches.

5. **`VaultSessionManager.kt` & `LatinIME.java`**:
   - Hook into `LatinIME.onFinishInputViewInternal()` and `hideWindow()` to trigger `VaultSessionManager.lockSecurity()`.
   - Ensure `dismiss()` on vault dialogs calls clean lock and memory purge.

6. **GitHub Actions Workflow (`.github/workflows/build-apk.yml`)**:
   - Validates NDK toolchain and CMake configuration to build `libwhisper.so` alongside LatinIME native libraries across `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
   - Links build status and artifact hashes directly into build logs.
