// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.utils.LogCatcher
import java.io.File

/**
 * JNI wrapper and lifecycle manager for on-device Whisper inference.
 * Safely bridges Kotlin audio floats to the native C/C++ libwhisper runtime.
 * Rigorously connected to LogCatcher for operational telemetry without logging PII.
 */
class WhisperEngine {

    companion object {
        private const val TAG = "WhisperEngine"
        private const val COMPONENT_NAME = "WhisperEngine"
        const val SAMPLE_RATE_HZ = 16000

        @Volatile
        private var isLibraryLoaded: Boolean = false

        val primaryAbi: String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val isArm64: Boolean = primaryAbi.contains("arm64")
        val isArmV7: Boolean = primaryAbi.contains("armeabi") || primaryAbi.contains("armv7")

        init {
            LogCatcher.i(TAG, "Initializing WhisperEngine (Architecture: $primaryAbi, 64-bit=$isArm64, 32-bit=$isArmV7)")
            try {
                System.loadLibrary("whisper")
                isLibraryLoaded = true
                LogCatcher.i(TAG, "Native whisper library (libwhisper.so) loaded successfully")
                LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Native Library Ready ($primaryAbi)")
            } catch (e: UnsatisfiedLinkError) {
                isLibraryLoaded = false
                LogCatcher.w(TAG, "libwhisper.so not loaded (requires CI compilation): ${e.message}")
            } catch (t: Throwable) {
                isLibraryLoaded = false
                LogCatcher.e(TAG, "Unexpected error loading libwhisper.so", t)
            }
        }

        fun isNativeAvailable(): Boolean = isLibraryLoaded

        /**
         * Strips common Whisper hallucination artifacts and control tokens.
         * Suppresses bracketed non-speech annotations like [cough], [music], [applause], etc.
         */
        fun cleanWhisperOutput(raw: String, suppressAnnotations: Boolean = true): String {
            var text = raw.replace(Regex("<\\|.*?\\|>"), "")
            if (suppressAnnotations) {
                // Universal non-speech acoustic annotations: [cough], [music], [laughter], [applause], etc.
                text = text.replace(Regex("\\[[a-zA-Z\\s\\-_]+\\]"), "")
                text = text.replace(Regex("\\([a-zA-Z\\s\\-_]+\\)"), "")
            }
            return text.replace(Regex("\\s+"), " ").trim()
        }
    }

    private var contextPtr: Long = 0L

    val isModelLoaded: Boolean
        get() = contextPtr != 0L

    @Synchronized
    fun loadModel(modelFile: File): Boolean {
        if (!isLibraryLoaded) {
            LogCatcher.w(TAG, "Cannot load model: native libwhisper.so is not available")
            return false
        }
        if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
            LogCatcher.e(TAG, "Model file does not exist or is invalid (<1MB)")
            return false
        }

        // Release prior model if active
        if (contextPtr != 0L) {
            release()
        }

        if (isArmV7 && modelFile.length() > 80 * 1024 * 1024) {
            LogCatcher.w(TAG, "High memory risk on 32-bit ARM: Model size (${modelFile.length() / (1024 * 1024)}MB) > 80MB. Recommend ggml-tiny.en-q5_1.bin (~31MB) to prevent OOM.")
        }

        return try {
            val startTime = System.currentTimeMillis()
            LogCatcher.i(TAG, "Initializing Whisper context from file (size: ${modelFile.length()} bytes)")
            val ptr = safeInitContext(modelFile.absolutePath)
            val elapsed = System.currentTimeMillis() - startTime

            if (ptr != 0L) {
                contextPtr = ptr
                LogCatcher.i(TAG, "Whisper context initialized successfully in ${elapsed}ms (ptr=$ptr)")
                LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Active (Context: $ptr)")
                true
            } else {
                LogCatcher.e(TAG, "Native initContext returned null pointer for model")
                LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Error: Null Context")
                false
            }
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Exception during native model initialization", t)
            LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Init Exception: ${t.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int = minOf(4, Runtime.getRuntime().availableProcessors()),
        useBeamSearch: Boolean = false,
        initialPrompt: String? = null,
        suppressAnnotations: Boolean = true
    ): String? {
        if (contextPtr == 0L) {
            LogCatcher.w(TAG, "transcribe called without active model context")
            return null
        }
        if (audioSamples.isEmpty()) {
            return null
        }

        return try {
            val durationSec = audioSamples.size / SAMPLE_RATE_HZ.toFloat()
            val modeStr = if (useBeamSearch) "BeamSearch(5)" else "Greedy"
            LogCatcher.i(TAG, "Starting inference: ${audioSamples.size} samples (~${"%.2f".format(durationSec)}s, $numThreads threads, mode=$modeStr, prompt=${!initialPrompt.isNullOrEmpty()})")
            LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Transcribing (${"%.1f".format(durationSec)}s, $modeStr)")

            val startTime = System.currentTimeMillis()
            val rawResult = safeFullTranscribe(contextPtr, numThreads, useBeamSearch, initialPrompt, audioSamples)
            val elapsed = System.currentTimeMillis() - startTime
            val rtf = if (durationSec > 0f) elapsed / (durationSec * 1000f) else 0f

            LogCatcher.i(TAG, "Inference completed in ${elapsed}ms (RTF: ${"%.2f".format(rtf)}x realtime)")
            LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Idle (Last inference: ${elapsed}ms, ${"%.2f".format(rtf)}x RTF)")

            if (rawResult != null) {
                val cleaned = cleanWhisperOutput(rawResult, suppressAnnotations)
                cleaned
            } else {
                LogCatcher.w(TAG, "Native fullTranscribe returned null")
                null
            }
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Exception during native inference", t)
            LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Inference Exception")
            null
        }
    }

    @Synchronized
    fun release() {
        if (contextPtr != 0L) {
            try {
                LogCatcher.i(TAG, "Releasing Whisper context (ptr=$contextPtr)")
                safeFreeContext(contextPtr)
            } catch (t: Throwable) {
                LogCatcher.e(TAG, "Exception releasing Whisper context", t)
            } finally {
                contextPtr = 0L
                LogCatcher.markComponentInactive(COMPONENT_NAME, "Released")
            }
        }
    }

    private fun safeInitContext(modelPath: String): Long {
        return try {
            initContext(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            LogCatcher.i(TAG, "Delegating initContext to FUTO bridge: ${e.message}")
            org.futo.voiceinput.whisper.WhisperEngine.initContext(modelPath)
        }
    }

    private fun safeFreeContext(ptr: Long) {
        try {
            freeContext(ptr)
        } catch (e: UnsatisfiedLinkError) {
            LogCatcher.i(TAG, "Delegating freeContext to FUTO bridge")
            org.futo.voiceinput.whisper.WhisperEngine.freeContext(ptr)
        }
    }

    private fun safeFullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        useBeamSearch: Boolean,
        initialPrompt: String?,
        audioData: FloatArray
    ): String? {
        return try {
            fullTranscribe(contextPtr, numThreads, useBeamSearch, initialPrompt, audioData)
        } catch (e: UnsatisfiedLinkError) {
            LogCatcher.i(TAG, "Delegating fullTranscribe to FUTO bridge")
            org.futo.voiceinput.whisper.WhisperEngine.fullTranscribe(
                contextPtr, numThreads, useBeamSearch, initialPrompt, audioData
            )
        }
    }

    // --- Native JNI Method Declarations ---
    private external fun initContext(modelPath: String): Long
    private external fun freeContext(contextPtr: Long)
    private external fun fullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        useBeamSearch: Boolean,
        initialPrompt: String?,
        audioData: FloatArray
    ): String?
}
