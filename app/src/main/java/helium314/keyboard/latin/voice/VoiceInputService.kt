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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        private const val MAX_SPEECH_SAMPLES = 240_000 // 15 seconds at 16kHz (<1MB FloatArray)
        private const val MAX_INTERIM_SAMPLES = 48_000 // 3 seconds rolling preview window
        private const val INTERIM_INFERENCE_INTERVAL_MS = 1400L
    }

    private val serviceHandler = Handler(Looper.getMainLooper(), ::handleClientMessage)
    private val serviceMessenger = Messenger(serviceHandler)
    private var clientMessenger: Messenger? = null

    private var audioPipeline: AudioRecordPipeline? = null
    @Volatile
    private var currentState = VoiceIpcProtocol.ServiceState.IDLE

    private val whisperEngine = WhisperEngine()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val isTranscribing = AtomicBoolean(false)
    private val pendingFinalCount = AtomicInteger(0)
    private val utteranceGeneration = AtomicLong(0L)
    private var lastInterimInferenceTime = 0L
    private var lastGainMultiplier: Int = 1
    @Volatile
    private var startErrorDispatched: Boolean = false

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
        pauseRecording(triggerFinalInference = false)
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

    private fun prewarmModelAsync() {
        if (whisperEngine.isModelLoaded || inferenceExecutor.isShutdown) return
        try {
            inferenceExecutor.execute {
                try {
                    if (whisperEngine.isModelLoaded || currentState != VoiceIpcProtocol.ServiceState.RECORDING) return@execute
                    val modelFile = VoiceModelManager.getActiveModelFile(applicationContext)
                    if (!modelFile.exists() || modelFile.length() <= 1024 * 1024) return@execute
                    val memIssue = WhisperEngine.checkMemoryHeadroom(applicationContext, modelFile, requireModelOverhead = true)
                    if (memIssue != null) {
                        pauseEngineOnFailure(memIssue, releasePipeline = true, releaseModel = true)
                        return@execute
                    }
                    LogCatcher.i(TAG, "Pre-warming Whisper model: ${modelFile.name}")
                    val loaded = whisperEngine.loadModel(modelFile)
                    if (!loaded && WhisperEngine.isNativeAvailable()) {
                        pauseEngineOnFailure("Model init failed", releasePipeline = true, releaseModel = true)
                    }
                } catch (t: Throwable) {
                    LogCatcher.e(TAG, "Exception pre-warming Whisper model", t)
                    pauseEngineOnFailure("Engine stopped (${t.javaClass.simpleName})", releasePipeline = true, releaseModel = true)
                }
            }
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            LogCatcher.w(TAG, "Pausing voice & releasing Whisper model off-thread onTrimMemory (level=$level)")
            if (currentState == VoiceIpcProtocol.ServiceState.RECORDING) {
                pauseEngineOnFailure("Low memory", releasePipeline = false, releaseModel = true)
            } else {
                synchronized(bufferLock) {
                    audioBuffer = null
                    audioSampleCount = 0
                }
                releaseWhisperAsync()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogCatcher.i(TAG, "VoiceInputService onDestroy")
        LogCatcher.markComponentInactive(COMPONENT_NAME, "Destroyed")
        serviceHandler.removeCallbacksAndMessages(null)
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
                if (gain in listOf(1, 2, 4)) lastGainMultiplier = gain
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

    private fun allocateAudioBufferLocked(): Boolean {
        return try {
            if (audioBuffer == null) {
                audioBuffer = FloatArray(MAX_SPEECH_SAMPLES)
            }
            audioSampleCount = 0
            true
        } catch (oom: OutOfMemoryError) {
            LogCatcher.e(TAG, "OOM allocating audio buffer", oom)
            audioBuffer = null
            audioSampleCount = 0
            false
        }
    }

    private fun startRecording(gain: Int) {
        lastGainMultiplier = gain
        if (audioPipeline == null) {
            audioPipeline = AudioRecordPipeline(this)
        }
        audioPipeline?.gainMultiplier = gain
        val allocated = synchronized(bufferLock) { allocateAudioBufferLocked() }
        if (!allocated) {
            pauseEngineOnFailure("Low memory", releasePipeline = true, releaseModel = true)
            return
        }
        startErrorDispatched = false
        val success = try {
            audioPipeline?.start() ?: false
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Throwable starting AudioRecordPipeline", t)
            false
        }
        if (success) {
            currentState = VoiceIpcProtocol.ServiceState.RECORDING
            notifyStateChanged(currentState)
            LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Recording (Gain: ${gain}x)")
            prewarmModelAsync()
        } else if (!startErrorDispatched && currentState != VoiceIpcProtocol.ServiceState.PAUSED) {
            pauseEngineOnFailure("Mic unavailable", releasePipeline = true, releaseModel = false)
        }
    }

    private fun pauseRecording(triggerFinalInference: Boolean = true) {
        currentState = VoiceIpcProtocol.ServiceState.PAUSED
        audioPipeline?.pause()
        notifyStateChanged(currentState)
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Paused")
        if (triggerFinalInference) {
            triggerInference()
        }
    }

    private fun resumeRecording() {
        val pipeline = audioPipeline
        if (pipeline == null || !pipeline.isActive()) {
            startRecording(lastGainMultiplier)
            return
        }
        val allocated = synchronized(bufferLock) {
            if (audioBuffer == null) allocateAudioBufferLocked() else true
        }
        if (!allocated) {
            pauseEngineOnFailure("Low memory", releasePipeline = true, releaseModel = true)
            return
        }
        pipeline.resume()
        currentState = VoiceIpcProtocol.ServiceState.RECORDING
        notifyStateChanged(currentState)
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Recording")
        prewarmModelAsync()
    }

    private fun stopRecording() {
        currentState = VoiceIpcProtocol.ServiceState.IDLE
        audioPipeline?.stop()
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

    /**
     * Stops audio & releases Whisper engine cleanly on any non-fatal exception, memory pressure,
     * or hardware error inside the single IME process, placing the Voice modal in PAUSED state
     * so the rest of the keyboard stays alive and the user can tap to retry.
     */
    private fun pauseEngineOnFailure(
        reason: String,
        releasePipeline: Boolean = true,
        releaseModel: Boolean = true
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            serviceHandler.post { pauseEngineOnFailure(reason, releasePipeline, releaseModel) }
            return
        }
        if (currentState == VoiceIpcProtocol.ServiceState.IDLE && clientMessenger == null) {
            // Already closed/unbound — clean up silently without resurrecting PAUSED state
            if (releasePipeline) {
                try {
                    audioPipeline?.release()
                } catch (_: Throwable) {
                }
                audioPipeline = null
            }
            synchronized(bufferLock) {
                audioBuffer = null
                audioSampleCount = 0
            }
            if (releaseModel) {
                releaseWhisperAsync()
            }
            return
        }
        utteranceGeneration.incrementAndGet()
        currentState = VoiceIpcProtocol.ServiceState.PAUSED
        if (releasePipeline) {
            try {
                audioPipeline?.release()
            } catch (_: Throwable) {
            }
            audioPipeline = null
        } else {
            try {
                audioPipeline?.pause()
            } catch (_: Throwable) {
            }
        }
        synchronized(bufferLock) {
            audioBuffer = null
            audioSampleCount = 0
        }
        if (releaseModel) {
            releaseWhisperAsync()
        }
        LogCatcher.w(TAG, "Voice engine paused cleanly after failure: $reason")
        LogCatcher.markComponentActive(COMPONENT_NAME, "IPC Service", "Paused ($reason)")
        notifyErrorMessage(reason)
        notifyStateChanged(VoiceIpcProtocol.ServiceState.PAUSED)
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

    private fun notifyErrorMessage(message: String) {
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

    private fun notifyFinalizationComplete() {
        val messenger = clientMessenger ?: return
        try {
            messenger.send(Message.obtain(null, VoiceIpcProtocol.EVENT_FINALIZATION_COMPLETE))
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    private fun triggerInterimPreviewInference() {
        if (currentState != VoiceIpcProtocol.ServiceState.RECORDING) return
        val now = System.currentTimeMillis()
        if (now - lastInterimInferenceTime < INTERIM_INFERENCE_INTERVAL_MS) return
        if (isTranscribing.get() || pendingFinalCount.get() > 0) return

        val gen = utteranceGeneration.get()
        val samples: FloatArray
        try {
            synchronized(bufferLock) {
                val buf = audioBuffer ?: return
                if (audioSampleCount < MIN_SPEECH_SAMPLES) return
                val startIdx = (audioSampleCount - MAX_INTERIM_SAMPLES).coerceAtLeast(0)
                samples = buf.copyOfRange(startIdx, audioSampleCount)
            }
        } catch (oom: OutOfMemoryError) {
            LogCatcher.e(TAG, "OOM copying interim preview audio", oom)
            pauseEngineOnFailure("Low memory", releasePipeline = true, releaseModel = true)
            return
        }

        if (inferenceExecutor.isShutdown) return
        lastInterimInferenceTime = now
        inferenceExecutor.execute {
            if (pendingFinalCount.get() > 0 || utteranceGeneration.get() != gen || currentState != VoiceIpcProtocol.ServiceState.RECORDING) {
                return@execute
            }
            isTranscribing.set(true)
            try {
                val modelFile = VoiceModelManager.getActiveModelFile(applicationContext)
                if (!whisperEngine.isModelLoaded) {
                    if (!modelFile.exists() || modelFile.length() <= 1024 * 1024) {
                        return@execute
                    }
                    val memIssue = WhisperEngine.checkMemoryHeadroom(applicationContext, modelFile, requireModelOverhead = true)
                    if (memIssue != null) {
                        pauseEngineOnFailure(memIssue, releasePipeline = true, releaseModel = true)
                        return@execute
                    }
                    if (!whisperEngine.loadModel(modelFile) && WhisperEngine.isNativeAvailable()) {
                        pauseEngineOnFailure("Model init failed", releasePipeline = true, releaseModel = true)
                        return@execute
                    }
                } else {
                    val memIssue = WhisperEngine.checkMemoryHeadroom(applicationContext, modelFile, requireModelOverhead = false)
                    if (memIssue != null) {
                        pauseEngineOnFailure(memIssue, releasePipeline = true, releaseModel = true)
                        return@execute
                    }
                }

                if (pendingFinalCount.get() > 0 || utteranceGeneration.get() != gen || currentState != VoiceIpcProtocol.ServiceState.RECORDING) {
                    return@execute
                }

                val prefs = applicationContext.prefs()
                val suppressAnnotations = prefs.getBoolean(Settings.PREF_VOICE_SUPPRESS_ANNOTATIONS, true)

                // Fast greedy rolling preview over <=3s window
                val rawResult = whisperEngine.transcribe(
                    audioSamples = samples,
                    numThreads = minOf(2, WhisperEngine.recommendedThreads(applicationContext)),
                    useBeamSearch = false,
                    initialPrompt = null,
                    suppressAnnotations = suppressAnnotations
                )
                if (rawResult == null && whisperEngine.isModelLoaded) {
                    pauseEngineOnFailure("Engine stopped", releasePipeline = true, releaseModel = true)
                    return@execute
                }
                if (!rawResult.isNullOrBlank() && utteranceGeneration.get() == gen && currentState == VoiceIpcProtocol.ServiceState.RECORDING) {
                    val processedText = VoiceActionProcessor.processText(applicationContext, rawResult)
                    notifyTranscriptionPreview(processedText)
                }
            } catch (t: Throwable) {
                LogCatcher.e(TAG, "Exception during preview inference", t)
                pauseEngineOnFailure("Engine stopped (${t.javaClass.simpleName})", releasePipeline = true, releaseModel = true)
            } finally {
                isTranscribing.set(false)
            }
        }
    }

    private fun triggerInference(releaseBufferAfter: Boolean = false) {
        lastInterimInferenceTime = 0L
        utteranceGeneration.incrementAndGet()
        val samples: FloatArray
        try {
            synchronized(bufferLock) {
                val buf = audioBuffer
                if (buf == null || audioSampleCount < MIN_SPEECH_SAMPLES) {
                    // Ignore audio clips shorter than 0.5s to avoid transcribing clicks
                    audioSampleCount = 0
                    if (releaseBufferAfter) {
                        audioBuffer = null
                    }
                    notifyFinalizationComplete()
                    return
                }
                samples = buf.copyOf(audioSampleCount)
                audioSampleCount = 0
                if (releaseBufferAfter) {
                    audioBuffer = null
                }
            }
        } catch (oom: OutOfMemoryError) {
            LogCatcher.e(TAG, "OOM copying final inference audio", oom)
            pauseEngineOnFailure("Low memory", releasePipeline = true, releaseModel = true)
            return
        }

        if (inferenceExecutor.isShutdown) {
            notifyFinalizationComplete()
            return
        }
        pendingFinalCount.incrementAndGet()
        try {
            inferenceExecutor.execute {
                isTranscribing.set(true)
                try {
                    val modelFile = VoiceModelManager.getActiveModelFile(applicationContext)
                    if (!whisperEngine.isModelLoaded) {
                        if (modelFile.exists() && modelFile.length() > 1024 * 1024) {
                            val memIssue = WhisperEngine.checkMemoryHeadroom(applicationContext, modelFile, requireModelOverhead = true)
                            if (memIssue != null) {
                                pauseEngineOnFailure(memIssue, releasePipeline = true, releaseModel = true)
                                return@execute
                            }
                            LogCatcher.i(TAG, "Loading Whisper model into native runtime: ${modelFile.name}")
                            val loaded = whisperEngine.loadModel(modelFile)
                            if (!loaded) {
                                if (WhisperEngine.isNativeAvailable()) {
                                    pauseEngineOnFailure("Model init failed", releasePipeline = true, releaseModel = true)
                                } else {
                                    LogCatcher.w(TAG, "Whisper model not initialized (requires native lib); raw audio captured successfully")
                                }
                                return@execute
                            }
                        } else {
                            LogCatcher.i(TAG, "Raw audio captured (${samples.size} samples); no Whisper model loaded")
                            return@execute
                        }
                    } else {
                        val memIssue = WhisperEngine.checkMemoryHeadroom(applicationContext, modelFile, requireModelOverhead = false)
                        if (memIssue != null) {
                            pauseEngineOnFailure(memIssue, releasePipeline = true, releaseModel = true)
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
                        numThreads = WhisperEngine.recommendedThreads(applicationContext),
                        useBeamSearch = useBeamSearch,
                        initialPrompt = initialPrompt,
                        suppressAnnotations = suppressAnnotations
                    )
                    if (rawResult == null && whisperEngine.isModelLoaded) {
                        pauseEngineOnFailure("Engine stopped", releasePipeline = true, releaseModel = true)
                        return@execute
                    }
                    if (!rawResult.isNullOrBlank()) {
                        val processedText = VoiceActionProcessor.processText(applicationContext, rawResult)
                        LogCatcher.i(TAG, "Transcription finalized: ${processedText.length} chars (sanitized)")
                        notifyFinalTranscription(processedText)
                    } else {
                        LogCatcher.i(TAG, "Inference returned empty result (silence/noise)")
                    }
                } catch (t: Throwable) {
                    LogCatcher.e(TAG, "Exception during inference processing", t)
                    pauseEngineOnFailure("Engine stopped (${t.javaClass.simpleName})", releasePipeline = true, releaseModel = true)
                } finally {
                    isTranscribing.set(false)
                    pendingFinalCount.decrementAndGet()
                    notifyFinalizationComplete()
                }
            }
        } catch (t: Throwable) {
            pendingFinalCount.decrementAndGet()
            LogCatcher.e(TAG, "Failed to enqueue final inference", t)
            pauseEngineOnFailure("Engine stopped", releasePipeline = true, releaseModel = true)
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

        if (!isSpeaking && currentState == VoiceIpcProtocol.ServiceState.RECORDING) {
            // Trailing pause detected by EnergyVad! Process speech chunk
            LogCatcher.i(TAG, "Speech pause detected by VAD, triggering final chunk inference")
            triggerInference()
        }
    }

    override fun onAudioChunkAvailable(buffer: ShortArray, readSize: Int) {
        if (currentState != VoiceIpcProtocol.ServiceState.RECORDING) return
        var bufferFull = false
        synchronized(bufferLock) {
            if (currentState != VoiceIpcProtocol.ServiceState.RECORDING) return
            val buf = audioBuffer ?: return
            val maxToAdd = minOf(readSize, MAX_SPEECH_SAMPLES - audioSampleCount)
            for (i in 0 until maxToAdd) {
                buf[audioSampleCount++] = buffer[i] / 32768.0f
            }
            if (audioSampleCount >= MAX_SPEECH_SAMPLES) {
                bufferFull = true
            }
        }

        if (bufferFull) {
            LogCatcher.i(TAG, "Audio buffer reached 15s cap, auto-chunking speech segment")
            triggerInference()
        } else if (currentState == VoiceIpcProtocol.ServiceState.RECORDING) {
            triggerInterimPreviewInference()
        }
    }

    override fun onError(message: String) {
        startErrorDispatched = true
        LogCatcher.e(TAG, "Audio error: $message")
        pauseEngineOnFailure(message, releasePipeline = true, releaseModel = false)
    }
}
