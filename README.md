# Vianboard

**Vianboard** is a modern, privacy-respecting, lightweight offline keyboard for Android, built and customized in Google AI Studio. It is a fork and evolution of **HeliBoard** (and upstream AOSP / OpenBoard), tailored for power users, developers, and privacy-conscious typists.

---

## 📌 Project Overview

- **App Name:** Vianboard
- **Creator:** Lewis
- **Series:** Viabhron
- **Package Name:** `shura.keyboard`
- **Development Platform:** Google AI Studio
- **Base / Heritage:** Fork of HeliBoard (with AOSP & OpenBoard foundations)
- **License:** GNU General Public License v3.0 (GPL-3.0)

---

## 🚀 Key Improvements & Highlights (Over HeliBoard)

### 1. 🪶 Lite Mode
An optimized, low-overhead operational mode designed to minimize background memory footprint, reduce CPU spikes, and provide instant keyboard response on lower-end devices or battery-saving conditions. When active, non-essential background daemons are gracefully suspended.

### 2. 📝 Prompt List / Quick Notes
A quick-access notes and prompt repository situated directly beside the clipboard history:
- Save, edit, organize, and quickly insert frequently used prompts, boilerplate responses, markdown templates, or code snippets.
- **Quick Access:** Long-press the copy tool icon in the keyboard toolbar to instantly summon the quick notes modal without leaving your current app.

### 3. ⌨️ Desktop Shortcuts Modal
Empowers mobile and tablet users with desktop productivity:
- Customizable virtual buttons for common desktop key combinations (e.g., `Ctrl+C`, `Ctrl+V`, `Ctrl+Z`, `Ctrl+A`, `Ctrl+Shift+T`, cursor jumps, and terminal shortcuts).
- Ideal for remote desktop clients, SSH sessions, termux terminals, code editors, and multi-window productivity.

### 4. 🎙️ Private Offline Voice Input (SLM)
Simple, completely offline voice-to-text transcription:
- Eliminates cloud tracking and remote telemetry.
- Direct user import of `.bin` Whisper / Small Language Model (SLM) weights for local neural inference.
- Automatic audio resource release on app backgrounding and system memory pressure (`TRIM_MEMORY_RUNNING_CRITICAL`).

### 5. 🛡️ Privacy Vault
Quick, shoulder-surfing-resistant text replacement for non-password private snippets:
- Store frequent private info (home/work addresses, ID numbers, personal phrases, and custom shortcuts).
- Renders as a dedicated, discreet masked pill (`••••`) in the suggestion strip when a 4+ letter trigger word is typed, hiding regular dictionary suggestions.
- Protected by stealth pattern authentication so curious onlookers never see sensitive snippets unmasked on screen.
- Cleanly replaces the active composing shortcut on tap.

### 6. 🔐 Security Vault
An encrypted offline credentials and password repository deeply integrated into the input pipeline:
- **Zero-Cloud Storage:** All secrets are stored locally with strong encryption.
- **Stealth Pattern Unlock:** Unlock challenge can be disguised directly over the standard QWERTY keyboard layout without revealing a biometric prompt or PIN keypad.
- **Decoy Explorer Mode:** Long-press timer routes to decoy or real entries based on access pattern.
- **Auto-Lock Lifecycle:** Automatically purges active session tokens on system memory trim (`ComponentCallbacks2.onTrimMemory`) and input view finish to guarantee zero memory leakage.

---

## 🛠️ Diagnostics & Log Keeper

Vianboard incorporates an internal **Log Keeper** diagnostic engine:
- Master On/Off switch accessible in Settings.
- Logs strictly sanitized diagnostic data (component status, error stack traces, render state).
- **Zero Credential / PII Guarantee:** Passwords, vault phrases, keystores, and user inputs are strictly excluded from all logs.
- Direct export to local text file for troubleshooting.

---

## 🔒 Privacy & Permissions

Vianboard operates strictly on-device:
- **No Internet Permission:** Does not request or declare `android.permission.INTERNET`. Zero network access guaranteed.
- **Audio Permission:** `RECORD_AUDIO` is requested at runtime exclusively for the optional offline voice input engine.
- **Biometric Permission:** `USE_BIOMETRIC` is utilized locally for Android BiometricPrompt hardware authentication.

---

## 📦 Building from Source

### Prerequisites
- Android Studio Ladybug or newer
- JDK 17 or JDK 21
- Android SDK 36 (minSdk 24)
- CMake & Android NDK (`arm64-v8a`, `armeabi-v7a`)

### Build Commands
```bash
# Compile Kotlin and Java sources
gradle compileDebugKotlin compileDebugJavaWithJavac

# Assemble Debug APK
gradle assembleDebug
```
The output APK will be generated under `app/build/outputs/apk/debug/`.

---

## 📜 Credits & Heritage

- **Original Base:** [HeliBoard](https://github.com/helium314/HeliBoard) by helium314
- **Foundations:** AOSP LatinIME & OpenBoard
- **Series:** Viabhron by Lewis
- **Built with:** Google AI Studio
