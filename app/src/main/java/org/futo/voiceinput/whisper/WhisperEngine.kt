// SPDX-License-Identifier: GPL-3.0-only
package org.futo.voiceinput.whisper

import helium314.keyboard.latin.utils.LogCatcher

/**
 * FUTO-compatible JNI Bridge for whisper.cpp inference.
 * Matches org.futo.voiceinput package naming for 100% binary interoperability with
 * prebuilt FUTO libwhisper.so libraries.
 */
object WhisperEngine {
    private const val TAG = "FutoWhisperBridge"

    init {
        try {
            System.loadLibrary("whisper")
            LogCatcher.i(TAG, "libwhisper.so linked to FUTO package bridge successfully")
        } catch (t: Throwable) {
            LogCatcher.w(TAG, "libwhisper.so not pre-loaded in FUTO bridge: ${t.message}")
        }
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun fullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        useBeamSearch: Boolean,
        initialPrompt: String?,
        audioData: FloatArray
    ): String?
}
