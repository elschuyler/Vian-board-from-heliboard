// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import helium314.keyboard.latin.utils.LogCatcher
import kotlin.math.sqrt

/**
 * High-performance, low-latency audio capture pipeline for speech recognition.
 * Captures 16kHz 16-bit mono PCM, applies digital gain with anti-clipping limiter,
 * calculates smoothed RMS levels, and detects speech activity via EnergyVad.
 */
class AudioRecordPipeline(
    private val listener: AudioPipelineListener
) {
    companion object {
        private const val TAG = "AudioRecordPipeline"
        private const val COMPONENT_NAME = "AudioPipeline"
        const val SAMPLE_RATE_HZ = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RMS_DISPATCH_INTERVAL_MS = 40L
    }

    interface AudioPipelineListener {
        fun onRmsChanged(rms: Float)
        fun onSpeechActivityChanged(isSpeaking: Boolean)
        fun onAudioChunkAvailable(buffer: ShortArray, readSize: Int)
        fun onError(message: String)
    }

    @Volatile
    private var isRunning: Boolean = false

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    var gainMultiplier: Int = 1
        set(value) {
            val newGain = if (value in listOf(1, 2, 4)) value else 1
            if (field != newGain) {
                field = newGain
                LogCatcher.i(TAG, "Digital microphone sensitivity gain updated to ${newGain}x")
            }
        }

    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val energyVad = EnergyVad()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (minBufferSize <= 0) {
            LogCatcher.e(TAG, "Audio hardware does not support 16kHz mono recording (minBufferSize: $minBufferSize)")
            listener.onError("Audio hardware does not support 16kHz mono recording")
            return false
        }

        // Allocate buffer size (at least 2x minBufferSize or 4096 bytes)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        try {
            // Prefer VOICE_RECOGNITION audio source for hardware noise reduction & echo cancellation
            var record: AudioRecord? = null
            var sourceUsed = "VOICE_RECOGNITION"
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (e: Exception) {
                LogCatcher.w(TAG, "VOICE_RECOGNITION audio source unavailable, falling back to MIC")
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                sourceUsed = "MIC"
                LogCatcher.i(TAG, "Initializing AudioRecord with fallback source: MIC")
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                LogCatcher.e(TAG, "Failed to initialize AudioRecord with source $sourceUsed (state=${record.state})")
                listener.onError("Failed to initialize AudioRecord (mic may be in use)")
                return false
            }

            record.startRecording()
            audioRecord = record
            isRunning = true
            isPaused = false
            energyVad.reset()

            workerThread = Thread({ recordLoop(record, bufferSize) }, "VianVoiceAudioThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            LogCatcher.i(TAG, "AudioRecordPipeline started successfully at 16kHz mono (source: $sourceUsed, buffer: $bufferSize bytes, gain: ${gainMultiplier}x)")
            LogCatcher.markComponentActive(COMPONENT_NAME, "AudioCapture", "Recording (16kHz mono, ${gainMultiplier}x)")
            return true
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Exception starting audio pipeline", t)
            LogCatcher.markComponentActive(COMPONENT_NAME, "AudioCapture", "Error Starting")
            listener.onError("Audio capture initialization error: ${t.message}")
            release()
            return false
        }
    }

    private fun recordLoop(record: AudioRecord, bufferSize: Int) {
        // Read chunks of 1024 samples (64ms at 16kHz)
        val chunkSize = 1024
        val buffer = ShortArray(chunkSize)
        var lastRmsTime = 0L
        var lastSpeechState = false

        while (isRunning) {
            if (isPaused) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            val read = record.read(buffer, 0, chunkSize)
            if (read > 0) {
                // 1. Apply digital gain with hard clipping prevention
                val currentGain = gainMultiplier
                var sumSquares = 0.0
                if (currentGain != 1) {
                    for (i in 0 until read) {
                        val amplified = (buffer[i] * currentGain).coerceIn(
                            Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                        ).toShort()
                        buffer[i] = amplified
                        sumSquares += amplified.toDouble() * amplified.toDouble()
                    }
                } else {
                    for (i in 0 until read) {
                        val s = buffer[i].toDouble()
                        sumSquares += s * s
                    }
                }

                // 2. Compute RMS & dispatch at throttled intervals
                val now = System.currentTimeMillis()
                if (now - lastRmsTime >= RMS_DISPATCH_INTERVAL_MS) {
                    lastRmsTime = now
                    val rms = sqrt(sumSquares / read).toFloat()
                    val normalizedRms = (rms / 32767f * 3f).coerceIn(0f, 1f)
                    listener.onRmsChanged(normalizedRms)
                }

                // 3. Process VAD
                val isSpeaking = energyVad.processFrame(buffer, read)
                if (isSpeaking != lastSpeechState) {
                    lastSpeechState = isSpeaking
                    LogCatcher.i(TAG, "VAD speech state transition: isSpeaking=$isSpeaking")
                    listener.onSpeechActivityChanged(isSpeaking)
                }

                // 4. Dispatch raw PCM frame for transcription pipeline
                listener.onAudioChunkAvailable(buffer, read)
            } else if (read < 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                    LogCatcher.e(TAG, "AudioRecord ERROR_INVALID_OPERATION")
                    listener.onError("Audio recording error: Invalid operation")
                    break
                } else if (read == AudioRecord.ERROR_BAD_VALUE) {
                    LogCatcher.e(TAG, "AudioRecord ERROR_BAD_VALUE")
                    listener.onError("Audio recording error: Bad value")
                    break
                }
            }
        }
    }

    @Synchronized
    fun pause() {
        isPaused = true
        listener.onRmsChanged(0f)
        listener.onSpeechActivityChanged(false)
        LogCatcher.markComponentActive(COMPONENT_NAME, "AudioCapture", "Paused")
    }

    @Synchronized
    fun resume() {
        isPaused = false
        LogCatcher.markComponentActive(COMPONENT_NAME, "AudioCapture", "Recording (16kHz mono, ${gainMultiplier}x)")
    }

    @Synchronized
    fun stop() {
        isRunning = false
        isPaused = false
        try {
            workerThread?.interrupt()
            workerThread?.join(500)
        } catch (e: Exception) {
            // Ignore interruption
        }
        workerThread = null

        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED && it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            LogCatcher.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        listener.onRmsChanged(0f)
        listener.onSpeechActivityChanged(false)
        LogCatcher.markComponentInactive(COMPONENT_NAME, "Stopped")
    }

    @Synchronized
    fun release() {
        stop()
    }
}
