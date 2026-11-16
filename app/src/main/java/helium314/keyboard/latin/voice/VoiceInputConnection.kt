// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import helium314.keyboard.latin.utils.LogCatcher

/**
 * Client-side connection manager in the main
 * IME process. Binds to VoiceInputService,
 * dispatches user controls, and listens to
 * RMS/speech events.
 */
class VoiceInputConnection(
    private val context: Context,
    private val listener: VoiceConnectionListener
) {
    companion object {
        private const val TAG =
            "VoiceInputConnection"
        private const val COMPONENT_NAME =
            "VoiceInputConnection"
    }

    interface VoiceConnectionListener {
        fun onRmsChanged(rms: Float)
        fun onStateChanged(
            state: VoiceIpcProtocol.ServiceState
        )
        fun onSpeechActivity(isSpeaking: Boolean)
        fun onError(message: String)
        fun onTranscriptionPreview(text: String)
        fun onFinalTranscription(text: String)
    }

    private val clientHandler = Handler(
        Looper.getMainLooper(),
        ::handleServiceMessage
    )
    private val clientMessenger =
        Messenger(clientHandler)
    private var serviceMessenger: Messenger? =
        null
    private var isBound: Boolean = false
    private var pendingStartGain: Int? = null

    private val deathRecipient =
        IBinder.DeathRecipient {
            LogCatcher.w(
                TAG,
                "VoiceInputService died or was " +
                    "terminated by OS"
            )
            LogCatcher.markComponentActive(
                COMPONENT_NAME,
                "IPC Client",
                "Service Died - Recovering"
            )
            serviceMessenger = null
            isBound = false
            listener.onError(
                "Voice service stopped"
            )
            listener.onStateChanged(
                VoiceIpcProtocol.ServiceState.ERROR
            )
        }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {
                LogCatcher.i(
                    TAG,
                    "Connected to VoiceInputService"
                )
                LogCatcher.markComponentActive(
                    COMPONENT_NAME,
                    "IPC Client",
                    "Connected"
                )
                val messenger = Messenger(service)
                serviceMessenger = messenger
                isBound = true

                try {
                    service?.linkToDeath(
                        deathRecipient,
                        0
                    )
                    val registerMsg =
                        Message.obtain(
                            null,
                            VoiceIpcProtocol
                                .CMD_REGISTER_CLIENT
                        )
                    registerMsg.replyTo =
                        clientMessenger
                    messenger.send(registerMsg)

                    pendingStartGain?.let { gain ->
                        val startMsg =
                            Message.obtain(
                                null,
                                VoiceIpcProtocol
                                    .CMD_START_RECORDING
                            )
                        startMsg.arg1 = gain
                        messenger.send(startMsg)
                        pendingStartGain = null
                    }
                } catch (e: RemoteException) {
                    LogCatcher.e(
                        TAG,
                        "RemoteException during " +
                            "service handshake",
                        e
                    )
                    listener.onError(
                        "Voice service failure: " +
                            "${e.message}"
                    )
                }
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {
                LogCatcher.i(
                    TAG,
                    "Disconnected from " +
                        "VoiceInputService"
                )
                LogCatcher.markComponentActive(
                    COMPONENT_NAME,
                    "IPC Client",
                    "Disconnected"
                )
                serviceMessenger = null
                isBound = false
                listener.onStateChanged(
                    VoiceIpcProtocol
                        .ServiceState.IDLE
                )
            }
        }

    fun connectAndStart(gainMultiplier: Int) {
        pendingStartGain = gainMultiplier
        if (!isBound) {
            LogCatcher.i(
                TAG,
                "Binding to VoiceInputService " +
                    "with initial gain: " +
                    "${gainMultiplier}x"
            )
            val intent = Intent(
                context,
                VoiceInputService::class.java
            )
            try {
                context.startService(intent)
                val bound = context.bindService(
                    intent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                )
                if (!bound) {
                    LogCatcher.e(
                        TAG,
                        "bindService returned " +
                            "false for " +
                            "VoiceInputService"
                    )
                    listener.onError(
                        "Unable to bind " +
                            "VoiceInputService"
                    )
                }
            } catch (t: Throwable) {
                LogCatcher.e(
                    TAG,
                    "Failed to bind " +
                        "VoiceInputService",
                    t
                )
                listener.onError(
                    "Failed to start voice " +
                        "service: ${t.message}"
                )
            }
        } else {
            serviceMessenger?.let { messenger ->
                try {
                    val startMsg = Message.obtain(
                        null,
                        VoiceIpcProtocol
                            .CMD_START_RECORDING
                    )
                    startMsg.arg1 = gainMultiplier
                    messenger.send(startMsg)
                    pendingStartGain = null
                } catch (e: RemoteException) {
                    listener.onError(
                        "Failed to send start " +
                            "command: ${e.message}"
                    )
                }
            }
        }
    }

    fun pause() {
        val messenger = serviceMessenger ?: return
        try {
            val msg = Message.obtain(
                null,
                VoiceIpcProtocol.CMD_PAUSE_RECORDING
            )
            messenger.send(msg)
        } catch (e: RemoteException) {
            LogCatcher.w(
                TAG,
                "Failed to send pause command: " +
                    "${e.message}"
            )
        }
    }

    fun resume() {
        val messenger = serviceMessenger ?: return
        try {
            val msg = Message.obtain(
                null,
                VoiceIpcProtocol
                    .CMD_RESUME_RECORDING
            )
            messenger.send(msg)
        } catch (e: RemoteException) {
            LogCatcher.w(
                TAG,
                "Failed to send resume command: " +
                    "${e.message}"
            )
        }
    }

    fun setGain(gainMultiplier: Int) {
        val messenger = serviceMessenger ?: return
        try {
            val msg = Message.obtain(
                null,
                VoiceIpcProtocol.CMD_SET_GAIN
            )
            msg.arg1 = gainMultiplier
            messenger.send(msg)
        } catch (e: RemoteException) {
            LogCatcher.w(
                TAG,
                "Failed to send setGain command: " +
                    "${e.message}"
            )
        }
    }

    fun stopAndDisconnect() {
        pendingStartGain = null
        if (isBound) {
            serviceMessenger?.let { messenger ->
                try {
                    val unregisterMsg =
                        Message.obtain(
                            null,
                            VoiceIpcProtocol
                                .CMD_UNREGISTER_CLIENT
                        )
                    messenger.send(unregisterMsg)
                    val stopMsg = Message.obtain(
                        null,
                        VoiceIpcProtocol
                            .CMD_STOP_RECORDING
                    )
                    messenger.send(stopMsg)
                    val releaseMsg = Message.obtain(
                        null,
                        VoiceIpcProtocol
                            .CMD_FORCE_RELEASE
                    )
                    messenger.send(releaseMsg)
                    messenger.binder?.unlinkToDeath(
                        deathRecipient,
                        0
                    )
                } catch (_: Exception) {
                }
            }
            try {
                context.unbindService(
                    serviceConnection
                )
            } catch (_: Exception) {
            }
            try {
                context.stopService(Intent(context, VoiceInputService::class.java))
            } catch (_: Exception) {
            }
            isBound = false
            serviceMessenger = null
            LogCatcher.markComponentInactive(
                COMPONENT_NAME,
                "Disconnected, Unbound, and Stopped"
            )
        }
        listener.onRmsChanged(0f)
        listener.onSpeechActivity(false)
    }

    private fun handleServiceMessage(
        msg: Message
    ): Boolean {
        when (msg.what) {
            VoiceIpcProtocol.EVENT_RMS_UPDATE -> {
                val rms = msg.data?.getFloat(
                    VoiceIpcProtocol.KEY_RMS
                ) ?: 0f
                listener.onRmsChanged(rms)
            }
            VoiceIpcProtocol
                .EVENT_STATE_CHANGED -> {
                val stateOrdinal = msg.arg1
                val state = VoiceIpcProtocol
                    .ServiceState.entries
                    .getOrNull(stateOrdinal)
                    ?: VoiceIpcProtocol
                        .ServiceState.IDLE
                listener.onStateChanged(state)
            }
            VoiceIpcProtocol
                .EVENT_SPEECH_ACTIVITY -> {
                val isSpeaking = msg.arg1 == 1
                listener.onSpeechActivity(
                    isSpeaking
                )
            }
            VoiceIpcProtocol.EVENT_ERROR -> {
                val error = msg.data?.getString(
                    VoiceIpcProtocol
                        .KEY_ERROR_MESSAGE
                ) ?: "Voice input error"
                listener.onError(error)
            }
            VoiceIpcProtocol
                .EVENT_TRANSCRIPTION_PREVIEW -> {
                val text = msg.data?.getString(
                    VoiceIpcProtocol
                        .KEY_TRANSCRIPTION_TEXT
                ) ?: ""
                listener.onTranscriptionPreview(
                    text
                )
            }
            VoiceIpcProtocol
                .EVENT_FINAL_TRANSCRIPTION -> {
                val text = msg.data?.getString(
                    VoiceIpcProtocol.KEY_FINAL_TEXT
                ) ?: ""
                listener.onFinalTranscription(text)
            }
        }
        return true
    }
}