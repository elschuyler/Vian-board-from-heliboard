// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import helium314.keyboard.latin.database.VoiceReplacementDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speech recognition service running in the Main IME Process.
 * Host for AudioRecordPipeline, speech segmentation, and on-device Whisper AI inference.
 * Captures microphone under the active IME window with logs flowing directly into LogCatcher.
 */
class VoiceInputService : Service(), AudioRecordPipeline.AudioPipelineListener {

    companion object {
        private const val TAG = "VoiceInputService"
        private const val COMPONENT_NAME = "VoiceInputService"
        private const val IDLE_AUTO_SHUTDOWN_MS = 60_000L // 60 seconds of inactivity
        private const val MIN_SPEECH_SAMPLES = 8_000 // 0.5 seconds at 16kHz
        private const val MAX_SPEECH_SAMPLES = 480_000 // 30 seconds at 16kHz
        private const val INTERIM_INFERENCE_INTERVAL_MS = 800L
    }

    private val serviceHandler = Handler(Looper.getMainLooper(), ::handleClientMessage)
    private val serviceMessenger = Messenger(serviceHandler)
    private var clientMessenger: Messenger? = null

    private var audioPipeline: AudioRecordPipeline? = null
    private var currentState = VoiceIpcProtocol.ServiceState.IDLE

    private val whisperEngine = WhisperEngine()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val isTranscribing = AtomicBoolean(false)
    private var lastInterimInferenceTime = 0L

    private var audioBuffer: FloatArray? = null
    private var audioSampleCount = 0
    private val bufferLock = Any()

    private val autoShutdownRunnable = Runnable {
        LogCatcher.i(TAG, "Idle timeout reached (60s). Releasing audio pipeline and stopping service.")
        LogCatcher.markComponentInactive(COMPONENT_NAME, "Idle Auto-Shutdown (60s)")
        releaseAudioPipeline()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        val pid = android.os.Process.myPid()
        LogCatcher.i(TAG, "VoiceInputService created in Main IME Process (PID: $pid)")
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Main IME Process (PID: $pid)")
        audioPipeline = AudioRecordPipeline(this)
        resetIdleTimeout()
    }

    override fun onBind(intent: Intent?): IBinder? {
        LogCatcher.i(TAG, "Client bound to VoiceInputService")
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Client Bound")
        resetIdleTimeout()
        return serviceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LogCatcher.i(TAG, "Client unbound from VoiceInputService - Immediate Zero-RAM eviction")
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Client Unbound (Freeing Model)")
        clientMessenger = null
        pauseRecording()
        releaseAudioPipeline()
        releaseWhisperAsync()
        stopSelf()
        return false
    }

    private fun releaseWhisperAsync() {
        if (!inferenceExecutor.isShutdown) {
            try {
                inferenceExecutor.execute {
                    whisperEngine.release()
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            LogCatcher.i(TAG, "Pausing voice & releasing Whisper model off-thread onTrimMemory (level=$level)")
            if (currentState == VoiceIpcProtocol.ServiceState.RECORDING) {
                audioPipeline?.pause()
                currentState = VoiceIpcProtocol.ServiceState.PAUSED
                notifyStateChanged(currentState)
                LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Paused (Memory Trim)")
            } else if (currentState == VoiceIpcProtocol.ServiceState.IDLE) {
                synchronized(bufferLock) {
                    audioBuffer = null
                    audioSampleCount = 0
                }
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                synchronized(bufferLock) {
                    audioBuffer = null
                    audioSampleCount = 0
                }
            }
            // Release off main thread to avoid JNI monitor contention ANR; do NOT trigger inference/reload here.
            releaseWhisperAsync()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogCatcher.i(TAG, "VoiceInputService onDestroy")
        LogCatcher.markComponentInactive(COMPONENT_NAME, "Destroyed")
        serviceHandler.removeCallbacks(autoShutdownRunnable)
        releaseAudioPipeline()
        if (!inferenceExecutor.isShutdown) {
            try {
                inferenceExecutor.execute {
                    try {
                        whisperEngine.release()
                    } finally {
                        inferenceExecutor.shutdown()
                    }
                }
            } catch (_: Throwable) {
                inferenceExecutor.shutdown()
            }
        }
    }

    private fun handleClientMessage(msg: Message): Boolean {
        resetIdleTimeout()
        when (msg.what) {
            VoiceIpcProtocol.CMD_REGISTER_CLIENT -> {
                clientMessenger = msg.replyTo
                if (currentState != VoiceIpcProtocol.ServiceState.IDLE) {
                    notifyStateChanged(currentState)
                }
            }
            VoiceIpcProtocol.CMD_UNREGISTER_CLIENT -> {
                clientMessenger = null
            }
            VoiceIpcProtocol.CMD_START_RECORDING -> {
                val gain = if (msg.arg1 in listOf(1, 2, 4)) msg.arg1 else 1
                startRecording(gain)
            }
            VoiceIpcProtocol.CMD_PAUSE_RECORDING -> {
                pauseRecording()
            }
            VoiceIpcProtocol.CMD_RESUME_RECORDING -> {
                resumeRecording()
            }
            VoiceIpcProtocol.CMD_STOP_RECORDING -> {
                stopRecording()
            }
            VoiceIpcProtocol.CMD_SET_GAIN -> {
                val gain = msg.arg1
                audioPipeline?.gainMultiplier = gain
            }
            VoiceIpcProtocol.CMD_FORCE_RELEASE -> {
                releaseAudioPipeline()
                releaseWhisperAsync()
                stopSelf()
            }
        }
        return true
    }

    private fun startRecording(gain: Int) {
        if (audioPipeline == null) {
            audioPipeline = AudioRecordPipeline(this)
        }
        audioPipeline?.gainMultiplier = gain
        synchronized(bufferLock) {
            if (audioBuffer == null) {
                audioBuffer = FloatArray(MAX_SPEECH_SAMPLES)
            }
            audioSampleCount = 0
        }
        val success = audioPipeline?.start() ?: false
        if (success) {
            currentState = VoiceIpcProtocol.ServiceState.RECORDING
            notifyStateChanged(currentState)
            LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Recording (Gain: ${gain}x)")
        } else {
            synchronized(bufferLock) {
                audioBuffer = null
                audioSampleCount = 0
            }
            currentState = VoiceIpcProtocol.ServiceState.ERROR
            notifyStateChanged(currentState)
            LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Error Starting Recording")
        }
    }

    private fun pauseRecording() {
        audioPipeline?.pause()
        currentState = VoiceIpcProtocol.ServiceState.PAUSED
        notifyStateChanged(currentState)
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Paused")
        triggerInference()
    }

    private fun resumeRecording() {
        synchronized(bufferLock) {
            if (audioBuffer == null) {
                audioBuffer = FloatArray(MAX_SPEECH_SAMPLES)
                audioSampleCount = 0
            }
        }
        audioPipeline?.resume()
        currentState = VoiceIpcProtocol.ServiceState.RECORDING
        notifyStateChanged(currentState)
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Recording")
    }

    private fun stopRecording() {
        audioPipeline?.stop()
        currentState = VoiceIpcProtocol.ServiceState.IDLE
        notifyStateChanged(currentState)
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Idle (Processing Final Audio)")
        triggerInference(releaseBufferAfter = true)
    }

    private fun releaseAudioPipeline() {
        audioPipeline?.release()
        audioPipeline = null
        synchronized(bufferLock) {
            audioBuffer = null
            audioSampleCount = 0
        }
        currentState = VoiceIpcProtocol.ServiceState.IDLE
        notifyStateChanged(currentState)
    }

    private fun resetIdleTimeout() {
        serviceHandler.removeCallbacks(autoShutdownRunnable)
        serviceHandler.postDelayed(autoShutdownRunnable, IDLE_AUTO_SHUTDOWN_MS)
    }

    private fun notifyStateChanged(state: VoiceIpcProtocol.ServiceState) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_STATE_CHANGED)
            msg.arg1 = state.ordinal
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    private fun notifyFinalTranscription(text: String) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_FINAL_TRANSCRIPTION)
            val bundle = Bundle().apply {
                putString(VoiceIpcProtocol.KEY_FINAL_TEXT, text)
                putString(VoiceIpcProtocol.KEY_TRANSCRIPTION_TEXT, text)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    private fun notifyTranscriptionPreview(text: String) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_TRANSCRIPTION_PREVIEW)
            val bundle = Bundle().apply {
                putString(VoiceIpcProtocol.KEY_TRANSCRIPTION_TEXT, text)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    private fun triggerInterimPreviewInference() {
        val now = System.currentTimeMillis()
        if (now - lastInterimInferenceTime < INTERIM_INFERENCE_INTERVAL_MS) return
        if (isTranscribing.get()) return

        val samples: FloatArray
        synchronized(bufferLock) {
            val buf = audioBuffer ?: return
            if (audioSampleCount < MIN_SPEECH_SAMPLES) return
            samples = buf.copyOf(audioSampleCount)
        }

        lastInterimInferenceTime = now
        inferenceExecutor.execute {
            if (isTranscribing.compareAndSet(false, true)) {
                try {
                    if (!whisperEngine.isModelLoaded) {
                        val modelFile = VoiceModelManager.getActiveModelFile(applicationContext)
                        if (modelFile.exists() && modelFile.length() > 1024 * 1024) {
                            whisperEngine.loadModel(modelFile)
                        } else {
                            return@execute
                        }
                    }

                    val prefs = applicationContext.prefs()
                    val suppressAnnotations = prefs.getBoolean(Settings.PREF_VOICE_SUPPRESS_ANNOTATIONS, true)

                    // 2-thread preview inference with dynamic audio_ctx and fast greedy sampling
                    val rawResult = whisperEngine.transcribe(
                        audioSamples = samples,
                        numThreads = 2,
                        useBeamSearch = false,
                        initialPrompt = null,
                        suppressAnnotations = suppressAnnotations
                    )
                    if (!rawResult.isNullOrBlank()) {
                        val replacedText = VoiceReplacementDao.getInstance(applicationContext).applyReplacements(rawResult)
                        notifyTranscriptionPreview(replacedText)
                    }
                } catch (t: Throwable) {
                    LogCatcher.e(TAG, "Exception during preview inference", t)
                } finally {
                    isTranscribing.set(false)
                }
            }
        }
    }

    private fun triggerInference(releaseBufferAfter: Boolean = false) {
        lastInterimInferenceTime = 0L
        val samples: FloatArray
        synchronized(bufferLock) {
            val buf = audioBuffer
            if (buf == null || audioSampleCount < MIN_SPEECH_SAMPLES) {
                // Ignore audio clips shorter than 0.5s to avoid transcribing clicks
                audioSampleCount = 0
                if (releaseBufferAfter) {
                    audioBuffer = null
                }
                return
            }
            samples = buf.copyOf(audioSampleCount)
            audioSampleCount = 0
            if (releaseBufferAfter) {
                audioBuffer = null
            }
        }

        if (inferenceExecutor.isShutdown) return
        inferenceExecutor.execute {
            if (isTranscribing.compareAndSet(false, true)) {
                try {
                    // Check and load model if needed
                    if (!whisperEngine.isModelLoaded) {
                        val modelFile = VoiceModelManager.getActiveModelFile(applicationContext)
                        if (modelFile.exists() && modelFile.length() > 1024 * 1024) {
                            LogCatcher.i(TAG, "Loading Whisper model into native runtime: ${modelFile.name}")
                            val loaded = whisperEngine.loadModel(modelFile)
                            if (!loaded) {
                                LogCatcher.w(TAG, "Whisper model not initialized (requires native lib); raw audio captured successfully")
                                return@execute
                            }
                        } else {
                            LogCatcher.i(TAG, "Raw audio captured (${samples.size} samples); no Whisper model loaded")
                            return@execute
                        }
                    }

                    val prefs = applicationContext.prefs()
                    val suppressAnnotations = prefs.getBoolean(Settings.PREF_VOICE_SUPPRESS_ANNOTATIONS, Defaults.PREF_VOICE_SUPPRESS_ANNOTATIONS)
                    val useBeamSearch = prefs.getBoolean(Settings.PREF_VOICE_USE_BEAM_SEARCH, Defaults.PREF_VOICE_USE_BEAM_SEARCH)
                    val verboseMode = prefs.getBoolean(Settings.PREF_VOICE_VERBOSE_MODE, Defaults.PREF_VOICE_VERBOSE_MODE)

                    // Prompt-based vocabulary boosting from custom replacements
                    val vocabList = VoiceReplacementDao.getInstance(applicationContext).getAll()
                    val initialPrompt = if (vocabList.isNotEmpty()) {
                        vocabList.map { it.replacementWord }.distinct().take(30).joinToString(", ")
                    } else null

                    if (verboseMode) {
                        LogCatcher.i(TAG, "Inference telemetry: samples=${samples.size}, durationMs=${samples.size * 1000 / 16000}, beamSearch=$useBeamSearch, suppressAnnotations=$suppressAnnotations, promptVocabCount=${vocabList.size}")
                    }

                    val rawResult = whisperEngine.transcribe(
                        audioSamples = samples,
                        numThreads = minOf(4, Runtime.getRuntime().availableProcessors()),
                        useBeamSearch = useBeamSearch,
                        initialPrompt = initialPrompt,
                        suppressAnnotations = suppressAnnotations
                    )
                    if (!rawResult.isNullOrBlank()) {
                        val replacedText = VoiceReplacementDao.getInstance(applicationContext).applyReplacements(rawResult)
                        LogCatcher.i(TAG, "Transcription finalized: ${replacedText.length} chars (sanitized)")
                        if (verboseMode) {
                            LogCatcher.i(TAG, "Transcription text: \"$replacedText\"")
                        }
                        notifyFinalTranscription(replacedText)
                    } else {
                        LogCatcher.i(TAG, "Inference returned empty result (silence/noise)")
                    }
                } catch (t: Throwable) {
                    LogCatcher.e(TAG, "Exception during inference processing", t)
                    onError("Inference error: ${t.javaClass.simpleName}")
                } finally {
                    isTranscribing.set(false)
                }
            }
        }
    }

    // --- AudioRecordPipeline.AudioPipelineListener Callbacks ---

    override fun onRmsChanged(rms: Float) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_RMS_UPDATE)
            val bundle = Bundle().apply {
                putFloat(VoiceIpcProtocol.KEY_RMS, rms)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    override fun onSpeechActivityChanged(isSpeaking: Boolean) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_SPEECH_ACTIVITY)
            msg.arg1 = if (isSpeaking) 1 else 0
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }

        if (!isSpeaking) {
            // Trailing pause detected by EnergyVad! Process speech chunk
            LogCatcher.i(TAG, "Speech pause detected by VAD, triggering final chunk inference")
            triggerInference()
        }
    }

    override fun onAudioChunkAvailable(buffer: ShortArray, readSize: Int) {
        var bufferFull = false
        synchronized(bufferLock) {
            val buf = audioBuffer ?: FloatArray(MAX_SPEECH_SAMPLES).also { audioBuffer = it }
            val maxToAdd = minOf(readSize, MAX_SPEECH_SAMPLES - audioSampleCount)
            for (i in 0 until maxToAdd) {
                buf[audioSampleCount++] = buffer[i] / 32768.0f
            }
            if (audioSampleCount >= MAX_SPEECH_SAMPLES) {
                bufferFull = true
            }
        }

        if (bufferFull) {
            val prefs = applicationContext.prefs()
            val continuous = prefs.getBoolean(Settings.PREF_VOICE_CONTINUOUS_STREAMING, Defaults.PREF_VOICE_CONTINUOUS_STREAMING)
            if (continuous) {
                LogCatcher.i(TAG, "Audio buffer reached 30s threshold, auto-chunking continuous speech segment")
                triggerInference()
            }
        } else if (currentState == VoiceIpcProtocol.ServiceState.RECORDING) {
            triggerInterimPreviewInference()
        }
    }

    override fun onError(message: String) {
        LogCatcher.e(TAG, "Audio error: $message")
        currentState = VoiceIpcProtocol.ServiceState.ERROR
        notifyStateChanged(currentState)
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_ERROR)
            val bundle = Bundle().apply {
                putString(VoiceIpcProtocol.KEY_ERROR_MESSAGE, message)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }
}
