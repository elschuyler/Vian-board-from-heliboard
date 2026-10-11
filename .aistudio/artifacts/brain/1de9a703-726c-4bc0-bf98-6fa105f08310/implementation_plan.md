# Implementation Plan: Voice Input Engine Verification & SAF Model Import Lock

Per the user's explicit directive:
> **"Model import via safe [SAF]. As is now. Dont simply maje changes to this part"**
> and continue under discussion mode unless explicitly triggered.

---

## 1. Current Architecture Audit (Verified & Locked)

### A. Model Delivery & APK Footprint
- **APK Size**: Lean ~19MB (zero prebuilt / bundled models in assets).
- **Import Mechanism**: System Storage Access Framework (SAF) via `VoiceModelImportBridge.kt` and `VoiceModelImportActivity.kt`.
- **Validation**: Strict validation of GGML header magic (`0x67676d6c`) before copying to `noBackupFilesDir/voice_models/model.bin`.
- **UI Triggers**:
  - Top bar Upgrade / Import button (`voice_upgrade_button`) in `voice_input_view.xml`.
  - Tapping streaming prompt or mic button when no model is active directly launches the SAF document picker.
  - Active model indicator and model info pill.

### B. Audio Capture & Visual Equalizer
- **Capture**: `AudioRecordPipeline.kt` records 16kHz mono 16-bit PCM.
- **Visual Equalizer**: `VoicePulseView.kt` animates 21 multi-band equalizer bars responding to RMS acoustic energy in real-time.
- **Process Isolation**: Audio capture and Whisper execution run under isolated `android:process=":voice"` (`VoiceInputService.kt`), guaranteeing the main keyboard process is immune to native faults or audio driver hiccups.

### C. Native Inference Runtime
- **Engine**: `whisper.cpp` + `ggml` with ARM NEON SIMD optimizations (`-O3 -fPIC -std=c++14 -DWHISPER_BUILD -mfpu=neon`).
- **Dynamic Context**: `jni_whisper.cpp` scales `audio_ctx` dynamically based on utterance length (saving up to 65% CPU time).
- **Dual JNI Bindings**: Supports both `helium314.keyboard.latin.voice.WhisperEngine` and `org.futo.voiceinput.whisper.WhisperEngine`.

---

## 2. Proposed Plan / Action Items

1. **Lock Model Import Pipeline**:
   - Zero modifications to the SAF import flow, `VoiceModelImportBridge`, `VoiceModelImportActivity`, or `VoiceModelManager`.
   - Keep `.github/workflows/build-apk.yml` lean without any bundled model download.

2. **Security Compliance (Mandate 2 & 3)**:
   - Purge `./debug.keystore` and `./debug.keystore.base64` from repository root if present.
   - Maintain signing injection strictly through GitHub Actions secrets.

3. **Status & Discussion**:
   - Confirm with user if any specific edge cases or UI adjustments are needed before implementation.
