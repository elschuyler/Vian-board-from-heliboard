// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity

private const val TAG = "VoiceInputView"

class VoiceInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var pulseView: VoicePulseView
    private lateinit var streamingText: TextView
    private lateinit var gainPill: TextView
    private var bottomRowKeyboard: MainKeyboardView? = null

    private var previewStrip: LinearLayout? = null
    private var previewText: TextView? = null
    private var previewClear: ImageView? = null

    private var keyboardActionListener: KeyboardActionListener? = null
    private var isVoiceActive: Boolean = false
    private var isVoicePaused: Boolean = false
    private var currentGainMultiplier: Int = 1
    private var voiceConnection: VoiceInputConnection? = null

    private var pendingCommitText: String? = null
    private val commitHandler = Handler(Looper.getMainLooper())
    private val commitRunnable = Runnable {
        val textToCommit = pendingCommitText
        if (!textToCommit.isNullOrEmpty() && isVoiceActive) {
            LogCatcher.i(TAG, "Auto-committing voice transcription: length=${textToCommit.length}")
            keyboardActionListener?.onTextInput(textToCommit)
            pendingCommitText = null
            updatePreviewStripText("")
        }
    }

    private var keyBackgroundId: Int = 0
    private var navBarBottomInset: Int = 0
    private val basePaddingBottom: Int

    init {
        @SuppressLint("UseKtx")
        val keyboardViewAttr = context.obtainStyledAttributes(
            attrs, R.styleable.KeyboardView, defStyleAttr, R.style.KeyboardView
        )
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()

        fitsSystemWindows = true
        orientation = VERTICAL
        basePaddingBottom = paddingBottom
        updateNavBarInset()
    }

    private fun updateNavBarInset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val windowMetrics = wm?.currentWindowMetrics
            val windowInsets = windowMetrics?.windowInsets
            if (windowInsets != null) {
                val insets = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
                applyBottomInset(insets.bottom)
            }
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        applyBottomInset(navBottom)
        return super.onApplyWindowInsets(insets)
    }

    private fun applyBottomInset(bottomInset: Int) {
        val effectiveInset = if (Settings.getValues().mIsFloatingKeyboard) 0 else bottomInset
        if (navBarBottomInset != effectiveInset) {
            navBarBottomInset = effectiveInset
            setPadding(paddingLeft, paddingTop, paddingRight, basePaddingBottom + effectiveInset)
            requestLayout()
        }
    }

    private val connectionListener = object : VoiceInputConnection.VoiceConnectionListener {
        override fun onRmsChanged(rms: Float) {
            post {
                if (isVoiceActive && !isVoicePaused) {
                    pulseView.setRms(rms)
                }
            }
        }

        override fun onStateChanged(state: VoiceIpcProtocol.ServiceState) {
            post {
                when (state) {
                    VoiceIpcProtocol.ServiceState.RECORDING -> {
                        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
                        if (streamingText.text == context.getString(R.string.voice_status_paused)) {
                            streamingText.setText(R.string.voice_status_listening)
                        }
                    }
                    VoiceIpcProtocol.ServiceState.PAUSED -> {
                        pulseView.pulseState = VoicePulseView.PulseState.PAUSED
                        streamingText.setText(R.string.voice_status_paused)
                    }
                    VoiceIpcProtocol.ServiceState.ERROR -> {
                        pulseView.pulseState = VoicePulseView.PulseState.ERROR
                    }
                    VoiceIpcProtocol.ServiceState.IDLE -> {
                        pulseView.pulseState = VoicePulseView.PulseState.IDLE
                    }
                }
            }
        }

        override fun onSpeechActivity(isSpeaking: Boolean) {
            // Speech presence indicator
        }

        override fun onError(message: String) {
            post {
                LogCatcher.w(TAG, "Voice input connection error: $message")
                pulseView.pulseState = VoicePulseView.PulseState.ERROR
                streamingText.text = message
            }
        }

        override fun onTranscriptionPreview(text: String) {
            post {
                updateStreamingText(text)
                if (pendingCommitText != null) {
                    commitHandler.removeCallbacks(commitRunnable)
                    pendingCommitText = null
                }
                updatePreviewStripText(text)
            }
        }

        override fun onFinalTranscription(text: String) {
            post {
                if (text.isNotEmpty()) {
                    updateStreamingText(text)
                    scheduleCommit(text)
                }
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        pulseView = findViewById(R.id.voice_pulse_view)
        streamingText = findViewById(R.id.voice_streaming_text)
        gainPill = findViewById(R.id.voice_gain_pill)
        bottomRowKeyboard = findViewById(R.id.bottom_row_keyboard)

        setupListeners()
    }

    private fun setupListeners() {
        val togglePause = {
            if (!VoiceModelManager.hasActiveModel(context)) {
                val intent = Intent(context, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } else if (isVoicePaused) {
                resumeVoiceInput()
            } else {
                pauseVoiceInput()
            }
        }

        streamingText.setOnClickListener { togglePause() }
        pulseView.setOnClickListener { togglePause() }

        gainPill.setOnClickListener {
            cycleGain()
        }
    }

    private fun cycleGain() {
        currentGainMultiplier = when (currentGainMultiplier) {
            1 -> 2
            2 -> 4
            else -> 1
        }
        context.prefs().edit().putString(Settings.PREF_VOICE_INPUT_GAIN, "${currentGainMultiplier}.0").apply()
        gainPill.text = "${currentGainMultiplier}x"
        LogCatcher.i(TAG, "Microphone sensitivity cycled to ${currentGainMultiplier}x")
        voiceConnection?.setGain(currentGainMultiplier)
    }

    fun startVoiceInput(actionListener: KeyboardActionListener) {
        startVoiceInput(null, actionListener)
    }

    fun startVoiceInput(editorInfo: EditorInfo?, actionListener: KeyboardActionListener) {
        this.keyboardActionListener = actionListener
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.MAIN_BACKGROUND)

        // Setup bottom row keyboard with theme keys
        setupBottomRowKeyboard(editorInfo, actionListener)

        // Setup voice preview strip
        val strip = KeyboardSwitcher.getInstance().voicePreviewStrip
        previewStrip = strip
        if (strip != null) {
            val pText = strip.findViewById<TextView>(R.id.voice_preview_text)
            val pClear = strip.findViewById<ImageView>(R.id.voice_preview_clear)
            previewText = pText
            previewClear = pClear

            colors.setBackground(strip, ColorType.STRIP_BACKGROUND)
            if (pText != null) {
                pText.setTextColor(colors.get(ColorType.KEY_TEXT))
                KeyboardTypeface.applyToTextView(pText)
                pText.text = ""
                pText.setOnClickListener { commitPendingImmediately() }
            }
            if (pClear != null) {
                colors.setColor(pClear, ColorType.KEY_ICON)
                pClear.setOnClickListener {
                    clearPreview()
                    stopVoiceInput()
                    KeyboardSwitcher.getInstance().closeSecondaryKeyboard()
                }
            }
        }

        // Setup gain pill with theme key background and colors
        if (keyBackgroundId != 0) {
            gainPill.setBackgroundResource(keyBackgroundId)
        }
        colors.setBackground(gainPill, ColorType.FUNCTIONAL_KEY_BACKGROUND)
        gainPill.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(gainPill)

        // Setup streaming text styling
        streamingText.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(streamingText)

        currentGainMultiplier = try {
            val gainStr = context.prefs().getString(Settings.PREF_VOICE_INPUT_GAIN, null)
            if (gainStr != null) {
                gainStr.toFloatOrNull()?.toInt() ?: 1
            } else {
                context.prefs().getInt(Settings.PREF_VOICE_INPUT_GAIN, 1)
            }
        } catch (e: Exception) {
            try {
                context.prefs().getInt(Settings.PREF_VOICE_INPUT_GAIN, 1)
            } catch (_: Exception) {
                1
            }
        }
        if (currentGainMultiplier !in listOf(1, 2, 4)) currentGainMultiplier = 1
        gainPill.text = "${currentGainMultiplier}x"

        if (!VoiceModelManager.hasActiveModel(context)) {
            pulseView.pulseState = VoicePulseView.PulseState.ERROR
            streamingText.setText(R.string.voice_status_no_model)
            isVoiceActive = false
            isVoicePaused = false
            LogCatcher.w(TAG, "Voice input modal started without active model installed")
            LogCatcher.markComponentActive("VoiceInputModal", "UI", "No Model")
            return
        }

        isVoiceActive = true
        isVoicePaused = false
        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
        streamingText.setText(R.string.voice_status_listening)
        LogCatcher.i(TAG, "Voice input modal presented (gain=${currentGainMultiplier}x)")
        LogCatcher.markComponentActive("VoiceInputModal", "UI", "Active")

        if (voiceConnection == null) {
            voiceConnection = VoiceInputConnection(context.applicationContext, connectionListener)
        }
        voiceConnection?.connectAndStart(currentGainMultiplier)
    }

    private fun scheduleCommit(text: String) {
        pendingCommitText = text
        updatePreviewStripText(text)
        commitHandler.removeCallbacks(commitRunnable)
        commitHandler.postDelayed(commitRunnable, 1000L)
    }

    fun clearPreview() {
        LogCatcher.i(TAG, "Voice preview cleared by user")
        commitHandler.removeCallbacks(commitRunnable)
        pendingCommitText = null
        updatePreviewStripText("")
        streamingText.setText(R.string.voice_status_listening)
    }

    fun commitPendingImmediately() {
        commitHandler.removeCallbacks(commitRunnable)
        val textToCommit = pendingCommitText
        if (!textToCommit.isNullOrEmpty() && isVoiceActive) {
            LogCatcher.i(TAG, "Committing pending voice transcription immediately: length=${textToCommit.length}")
            keyboardActionListener?.onTextInput(textToCommit)
            pendingCommitText = null
            updatePreviewStripText("")
        }
    }

    private fun updatePreviewStripText(text: String) {
        previewText?.text = text
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo?, listener: KeyboardActionListener) {
        val keyboardView = bottomRowKeyboard ?: return
        val wrappedListener = object : KeyboardActionListener by listener {
            override fun onCodeInput(code: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
                if (code == KeyCode.ALPHA) {
                    stopVoiceInput()
                    KeyboardSwitcher.getInstance().closeSecondaryKeyboard()
                    return
                } else {
                    pauseVoiceInput()
                }
                listener.onCodeInput(code, x, y, isKeyRepeat)
            }
        }
        keyboardView.setKeyboardActionListener(wrappedListener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        bottomRowKeyboard?.setHardwareAcceleratedDrawingEnabled(enabled)
        if (enabled) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
    }

    fun pauseVoiceInput() {
        commitHandler.removeCallbacks(commitRunnable)
        pendingCommitText = null
        updatePreviewStripText("")
        if (!isVoiceActive || isVoicePaused) return
        isVoicePaused = true
        pulseView.pulseState = VoicePulseView.PulseState.PAUSED
        streamingText.setText(R.string.voice_status_paused)
        LogCatcher.i(TAG, "Voice input paused")
        voiceConnection?.pause()
    }

    fun resumeVoiceInput() {
        if (!isVoiceActive || !isVoicePaused) return
        isVoicePaused = false
        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
        streamingText.setText(R.string.voice_status_listening)
        LogCatcher.i(TAG, "Voice input resumed")
        voiceConnection?.resume()
    }

    fun updateStreamingText(text: String) {
        if (!isVoiceActive || isVoicePaused) return
        streamingText.text = text
    }

    fun updateRms(rms: Float) {
        if (!isVoiceActive || isVoicePaused) return
        pulseView.setRms(rms)
    }

    fun stopVoiceInput() {
        LogCatcher.i(TAG, "Voice input modal dismissed")
        LogCatcher.markComponentActive("VoiceInputModal", "UI", "Dismissed")
        commitHandler.removeCallbacks(commitRunnable)
        pendingCommitText = null
        updatePreviewStripText("")
        previewStrip = null
        previewText = null
        previewClear = null
        isVoiceActive = false
        isVoicePaused = false
        voiceConnection?.stopAndDisconnect()
        voiceConnection = null
        pulseView.release()
        keyboardActionListener = null
        visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val density = res.displayMetrics.density
        val rowHeight = ResourceUtils.getKeyboardHeight(res, Settings.getValues()) / 4
        val topRowMinHeight = (52 * density).toInt()
        val compactContentHeight = maxOf((116 * density).toInt(), rowHeight + topRowMinHeight)
        val totalHeight = compactContentHeight + paddingTop + paddingBottom

        val exactWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
        super.onMeasure(exactWidthSpec, exactHeightSpec)
    }
}
