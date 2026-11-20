// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
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
    private lateinit var upgradeButton: TextView
    private lateinit var micButton: ImageView
    private var voiceActionsRow: LinearLayout? = null
    private var bottomRowKeyboard: MainKeyboardView? = null

    private var previewStrip: LinearLayout? = null
    private var previewText: TextView? = null
    private var previewClear: ImageView? = null

    private var keyboardActionListener: KeyboardActionListener? = null
    private var isVoiceActive: Boolean = false
    private var isVoicePaused: Boolean = false
    private var currentGainMultiplier: Int = 1
    private var voiceConnection: VoiceInputConnection? = null

    private var pauseReasonBanner: String? = null
    private var pendingCommitText: String? = null
    private var hasLiveSpeechInUtterance: Boolean = false
    private var suppressFlushOnStop: Boolean = false
    private var deferredKeyAction: (() -> Unit)? = null
    private var deferredFlushListener: KeyboardActionListener? = null
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
    private val deferredKeyFailSafeRunnable = Runnable { runDeferredKeyNow() }
    private val flushDisconnectRunnable = Runnable { completeFlushDisconnect() }

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
            commitHandler.post {
                if (isVoiceActive && !isVoicePaused) {
                    pulseView.setRms(rms)
                }
            }
        }

        override fun onStateChanged(state: VoiceIpcProtocol.ServiceState) {
            commitHandler.post {
                when (state) {
                    VoiceIpcProtocol.ServiceState.RECORDING -> {
                        pauseReasonBanner = null
                        isVoicePaused = false
                        updateMicButtonState()
                        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
                        val currentText = streamingText.text?.toString().orEmpty()
                        if (currentText == context.getString(R.string.voice_status_paused) ||
                            currentText.startsWith("Paused:")
                        ) {
                            streamingText.setText(R.string.voice_status_listening)
                        }
                    }
                    VoiceIpcProtocol.ServiceState.PAUSED -> {
                        isVoicePaused = true
                        hasLiveSpeechInUtterance = false
                        updateMicButtonState()
                        pulseView.pulseState = VoicePulseView.PulseState.PAUSED
                        val reason = pauseReasonBanner
                        if (!reason.isNullOrBlank()) {
                            streamingText.text = "Paused: $reason (tap to retry)"
                        } else {
                            streamingText.setText(R.string.voice_status_paused)
                        }
                        if (deferredFlushListener != null) {
                            completeFlushDisconnect()
                        }
                    }
                    VoiceIpcProtocol.ServiceState.ERROR -> {
                        // Treat engine errors identically to tapping bouncing bars: pause modal cleanly without crashing
                        isVoicePaused = true
                        hasLiveSpeechInUtterance = false
                        updateMicButtonState()
                        pulseView.pulseState = VoicePulseView.PulseState.PAUSED
                        val reason = pauseReasonBanner
                        streamingText.text = if (!reason.isNullOrBlank()) {
                            "Paused: $reason (tap to retry)"
                        } else {
                            "Paused: Engine stopped (tap to retry)"
                        }
                        runDeferredKeyNow()
                        if (deferredFlushListener != null) {
                            completeFlushDisconnect()
                        }
                    }
                    VoiceIpcProtocol.ServiceState.IDLE -> {
                        hasLiveSpeechInUtterance = false
                        if (!isVoiceActive) {
                            pulseView.pulseState = VoicePulseView.PulseState.IDLE
                            runDeferredKeyNow()
                            if (deferredFlushListener != null) {
                                completeFlushDisconnect()
                            }
                        }
                    }
                }
            }
        }

        override fun onSpeechActivity(isSpeaking: Boolean) {
            commitHandler.post {
                if (isSpeaking && isVoiceActive && !isVoicePaused) {
                    hasLiveSpeechInUtterance = true
                }
            }
        }

        override fun onError(message: String) {
            commitHandler.post {
                LogCatcher.w(TAG, "Voice input engine paused/error: $message")
                pauseReasonBanner = message
                isVoicePaused = true
                hasLiveSpeechInUtterance = false
                updateMicButtonState()
                pulseView.pulseState = VoicePulseView.PulseState.PAUSED
                streamingText.text = "Paused: $message (tap to retry)"
                runDeferredKeyNow()
                if (deferredFlushListener != null) {
                    completeFlushDisconnect()
                }
            }
        }

        override fun onTranscriptionPreview(text: String) {
            commitHandler.post {
                if (!isVoiceActive) return@post
                if (text.isNotBlank() && !isVoicePaused) {
                    hasLiveSpeechInUtterance = true
                }
                // Never clobber any pending finalized text when a live preview update arrives
                if (pendingCommitText != null) {
                    commitPendingImmediately()
                }
                updateStreamingText(text)
                updatePreviewStripText(text)
            }
        }

        override fun onFinalTranscription(text: String) {
            commitHandler.post {
                hasLiveSpeechInUtterance = false
                val flushListener = deferredFlushListener
                if (flushListener != null && !isVoiceActive) {
                    if (text.isNotEmpty()) {
                        LogCatcher.i(TAG, "Committing flushed voice transcription on close: length=${text.length}")
                        flushListener.onTextInput(text)
                    }
                    completeFlushDisconnect()
                    return@post
                }
                if (text.isNotEmpty() && isVoiceActive) {
                    if (!isVoicePaused) {
                        streamingText.text = text.replace("\n", " ↵ ")
                    }
                    commitTextImmediately(text)
                }
                runDeferredKeyNow()
            }
        }

        override fun onFinalizationComplete() {
            commitHandler.post {
                hasLiveSpeechInUtterance = false
                runDeferredKeyNow()
                if (deferredFlushListener != null) {
                    completeFlushDisconnect()
                }
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        pulseView = findViewById(R.id.voice_pulse_view)
        streamingText = findViewById(R.id.voice_streaming_text)
        gainPill = findViewById(R.id.voice_gain_pill)
        upgradeButton = findViewById(R.id.voice_upgrade_button)
        micButton = findViewById(R.id.voice_mic_button)
        voiceActionsRow = findViewById(R.id.voice_actions_row)
        bottomRowKeyboard = findViewById(R.id.bottom_row_keyboard)

        setupListeners()
    }

    private fun setupListeners() {
        val togglePause = {
            if (isVoicePaused) {
                resumeVoiceInput()
            } else {
                pauseVoiceInput()
            }
        }

        val launchModelPicker = {
            VoiceModelImportBridge.launchImport(context) { success, info ->
                if (success && info != null) {
                    post {
                        upgradeButton.visibility = GONE
                        streamingText.setText(R.string.voice_status_listening)
                        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
                        if (voiceConnection == null) {
                            voiceConnection = VoiceInputConnection(context.applicationContext, connectionListener)
                        }
                        voiceConnection?.connectAndStart(currentGainMultiplier)
                        LogCatcher.i(TAG, "Whisper model loaded into active voice view: ${info.fileName}")
                    }
                }
            }
        }

        upgradeButton.setOnClickListener { launchModelPicker() }

        streamingText.setOnClickListener {
            if (!VoiceModelManager.hasActiveModel(context)) {
                launchModelPicker()
            } else {
                togglePause()
            }
        }
        pulseView.setOnClickListener { togglePause() }
        micButton.setOnClickListener {
            if (!VoiceModelManager.hasActiveModel(context)) {
                launchModelPicker()
            } else {
                togglePause()
            }
        }

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
        if (deferredFlushListener != null) {
            completeFlushDisconnect()
        }
        commitHandler.removeCallbacks(deferredKeyFailSafeRunnable)
        deferredKeyAction = null
        suppressFlushOnStop = false
        hasLiveSpeechInUtterance = false
        pauseReasonBanner = null
        this.keyboardActionListener = actionListener
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.MAIN_BACKGROUND)

        // Setup 5 customizable Voice Action symbol toggle slots and bottom row keyboard
        setupVoiceActionsRow()
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
                    KeyboardSwitcher.getInstance().closeVoiceInput()
                }
            }
        }

        // Setup gain pill with theme key background and colors
        if (keyBackgroundId != 0) {
            gainPill.setBackgroundResource(keyBackgroundId)
            upgradeButton.setBackgroundResource(keyBackgroundId)
        }
        colors.setBackground(gainPill, ColorType.FUNCTIONAL_KEY_BACKGROUND)
        gainPill.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(gainPill)

        colors.setBackground(upgradeButton, ColorType.ACTION_KEY_BACKGROUND)
        upgradeButton.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(upgradeButton)

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

        val hasModel = VoiceModelManager.hasActiveModel(context)
        isVoiceActive = true
        isVoicePaused = false
        pulseView.pulseState = VoicePulseView.PulseState.LISTENING

        if (hasModel) {
            streamingText.setText(R.string.voice_status_listening)
            upgradeButton.visibility = GONE
        } else {
            streamingText.text = "Recording raw audio (Tap to import Whisper model)"
            upgradeButton.visibility = VISIBLE
        }
        updateMicButtonState()
        LogCatcher.i(TAG, "Voice input modal presented (hasModel=$hasModel, gain=${currentGainMultiplier}x)")
        LogCatcher.markComponentActive("VoiceInputModal", "UI", if (hasModel) "Active (Whisper)" else "Active (Raw Audio)")

        if (voiceConnection == null) {
            voiceConnection = VoiceInputConnection(context.applicationContext, connectionListener)
        }
        voiceConnection?.connectAndStart(currentGainMultiplier)
    }

    private fun updateMicButtonState() {
        val colors = Settings.getValues().mColors
        if (isVoicePaused) {
            micButton.setImageResource(R.drawable.sym_keyboard_voice_off_rounded)
            micButton.setColorFilter(Color.parseColor("#FBBC05"))
        } else {
            micButton.setImageResource(R.drawable.sym_keyboard_voice_rounded)
            micButton.setColorFilter(colors.get(ColorType.ACTION_KEY_BACKGROUND))
        }
    }

    private fun commitTextImmediately(text: String) {
        commitHandler.removeCallbacks(commitRunnable)
        pendingCommitText = null
        if (text.isNotEmpty() && isVoiceActive) {
            LogCatcher.i(TAG, "Committing voice transcription immediately: length=${text.length}")
            keyboardActionListener?.onTextInput(text)
            updatePreviewStripText("")
        }
    }

    fun clearPreview() {
        LogCatcher.i(TAG, "Voice preview cleared by user")
        suppressFlushOnStop = true
        hasLiveSpeechInUtterance = false
        commitHandler.removeCallbacks(deferredKeyFailSafeRunnable)
        deferredKeyAction = null
        commitHandler.removeCallbacks(commitRunnable)
        pendingCommitText = null
        updatePreviewStripText("")
        streamingText.setText(R.string.voice_status_listening)
    }

    fun commitPendingImmediately() {
        commitHandler.removeCallbacks(commitRunnable)
        val textToCommit = pendingCommitText
        pendingCommitText = null
        if (!textToCommit.isNullOrEmpty() && isVoiceActive) {
            LogCatcher.i(TAG, "Committing pending voice transcription immediately: length=${textToCommit.length}")
            keyboardActionListener?.onTextInput(textToCommit)
            updatePreviewStripText("")
        }
    }

    private fun updatePreviewStripText(text: String) {
        previewText?.text = text.replace("\n", " ↵ ")
    }

    private fun setupVoiceActionsRow() {
        val container = voiceActionsRow ?: return
        container.removeAllViews()
        val actions = VoiceActionCatalog.getSlottedActions(context.prefs())
        if (actions.isEmpty()) {
            container.visibility = GONE
            return
        }
        container.visibility = VISIBLE
        val density = context.resources.displayMetrics.density
        val marginPx = (3 * density).toInt()

        actions.forEachIndexed { index, action ->
            var currentEnabled = action.isEnabled
            val btn = TextView(context).apply {
                text = action.symbol
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, Typeface.BOLD)
                KeyboardTypeface.applyToTextView(this)
                isClickable = true
                isFocusable = true
                tag = "voice_action_slot_$index"
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }
            }
            styleVoiceActionSlotButton(btn, action, currentEnabled)
            btn.setOnClickListener {
                currentEnabled = VoiceActionCatalog.toggleActionEnabled(context.prefs(), action.id)
                styleVoiceActionSlotButton(btn, action, currentEnabled)
                LogCatcher.i(TAG, "Voice action slot '${action.id}' toggled: enabled=$currentEnabled")
            }
            container.addView(btn)
        }
    }

    private fun styleVoiceActionSlotButton(btn: TextView, action: VoiceActionItem, isEnabled: Boolean) {
        val colors = Settings.getValues().mColors
        val density = context.resources.displayMetrics.density
        val keyTextColor = colors.get(ColorType.KEY_TEXT)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            if (isEnabled) {
                setColor(colors.get(ColorType.ACTION_KEY_BACKGROUND))
                setStroke(
                    (1.5f * density).toInt().coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(keyTextColor, 200)
                )
            } else {
                setColor(ColorUtils.setAlphaComponent(colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND), 75))
                setStroke(
                    (1f * density).toInt().coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(keyTextColor, 65)
                )
            }
        }
        btn.background = background
        btn.setTextColor(keyTextColor)
        btn.alpha = if (isEnabled) 1.0f else 0.42f
        val stateLabel = context.getString(
            if (isEnabled) R.string.voice_action_state_on else R.string.voice_action_state_off
        )
        btn.contentDescription = "${action.title}: $stateLabel"
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo?, listener: KeyboardActionListener) {
        val keyboardView = bottomRowKeyboard ?: return
        val wrappedListener = object : KeyboardActionListener by listener {
            override fun onCodeInput(code: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
                if (code == KeyCode.ALPHA) {
                    KeyboardSwitcher.getInstance().closeVoiceInput()
                    return
                }
                flushAndRunKeyAction {
                    listener.onCodeInput(code, x, y, isKeyRepeat)
                }
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

    private fun flushAndRunKeyAction(action: () -> Unit) {
        commitPendingImmediately()
        val shouldDeferForSpeech = isVoiceActive && !isVoicePaused && hasLiveSpeechInUtterance &&
            voiceConnection?.isConnected() == true
        if (!shouldDeferForSpeech) {
            pauseVoiceInput()
            action()
            return
        }
        runDeferredKeyNow()
        deferredKeyAction = action
        commitHandler.removeCallbacks(deferredKeyFailSafeRunnable)
        commitHandler.postDelayed(deferredKeyFailSafeRunnable, 1200L)
        pauseVoiceInput()
    }

    private fun runDeferredKeyNow() {
        commitHandler.removeCallbacks(deferredKeyFailSafeRunnable)
        val pendingAction = deferredKeyAction ?: return
        deferredKeyAction = null
        pendingAction.invoke()
    }

    private fun completeFlushDisconnect() {
        commitHandler.removeCallbacks(flushDisconnectRunnable)
        deferredFlushListener = null
        voiceConnection?.stopAndDisconnect()
        voiceConnection = null
    }

    fun pauseVoiceInput() {
        commitPendingImmediately()
        updatePreviewStripText("")
        if (!isVoiceActive || isVoicePaused) return
        isVoicePaused = true
        pulseView.pulseState = VoicePulseView.PulseState.PAUSED
        if (pauseReasonBanner.isNullOrBlank()) {
            streamingText.setText(R.string.voice_status_paused)
        }
        updateMicButtonState()
        LogCatcher.i(TAG, "Voice input paused")
        voiceConnection?.pause()
    }

    fun resumeVoiceInput() {
        if (!isVoiceActive || !isVoicePaused) return
        pauseReasonBanner = null
        isVoicePaused = false
        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
        streamingText.setText(R.string.voice_status_listening)
        updateMicButtonState()
        LogCatcher.i(TAG, "Voice input resumed")
        if (voiceConnection == null) {
            voiceConnection = VoiceInputConnection(context.applicationContext, connectionListener)
            voiceConnection?.connectAndStart(currentGainMultiplier)
        } else {
            voiceConnection?.resume()
        }
    }

    fun updateStreamingText(text: String) {
        if (!isVoiceActive || isVoicePaused) return
        streamingText.text = text.replace("\n", " ↵ ")
    }

    fun updateRms(rms: Float) {
        if (!isVoiceActive || isVoicePaused) return
        pulseView.setRms(rms)
    }

    fun isVoiceActive(): Boolean = isVoiceActive

    fun stopVoiceInput() {
        if (!isVoiceActive && visibility == GONE && voiceConnection == null && deferredFlushListener == null) return
        val wasActive = isVoiceActive || visibility != GONE || voiceConnection != null
        runDeferredKeyNow()
        commitPendingImmediately()
        val shouldFlush = isVoiceActive && !isVoicePaused && !suppressFlushOnStop &&
            hasLiveSpeechInUtterance && voiceConnection?.isConnected() == true
        val retainedListener = if (shouldFlush) keyboardActionListener else null

        pauseReasonBanner = null
        isVoiceActive = false
        isVoicePaused = false
        hasLiveSpeechInUtterance = false
        suppressFlushOnStop = false
        updatePreviewStripText("")
        previewStrip = null
        previewText = null
        previewClear = null
        pulseView.release()
        keyboardActionListener = null
        visibility = GONE
        if (shouldFlush && retainedListener != null) {
            deferredFlushListener = retainedListener
            commitHandler.removeCallbacks(flushDisconnectRunnable)
            commitHandler.postDelayed(flushDisconnectRunnable, 1500L)
            voiceConnection?.requestFinalStop()
        } else {
            completeFlushDisconnect()
        }
        if (wasActive) {
            LogCatcher.i(TAG, "Voice input modal dismissed")
            LogCatcher.markComponentActive("VoiceInputModal", "UI", "Dismissed")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        commitHandler.removeCallbacks(commitRunnable)
        commitHandler.removeCallbacks(deferredKeyFailSafeRunnable)
        // Keep deferredFlushListener + flushDisconnectRunnable alive so Lite Mode view detach on ABC still commits flushed speech
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val density = res.displayMetrics.density
        val keyboardHeight = ResourceUtils.getKeyboardHeight(res, Settings.getValues())
        // Compact modal height (~60% of keyboard height + 40dp for the 5-button action bar, clamped 175dp..195dp)
        val compactTargetDp = (keyboardHeight / density * 0.60f + 40f).coerceIn(175f, 195f)
        val modalHeight = (compactTargetDp * density).toInt()
        val totalHeight = modalHeight + paddingTop + paddingBottom

        val exactWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
        super.onMeasure(exactWidthSpec, exactHeightSpec)
    }
}
