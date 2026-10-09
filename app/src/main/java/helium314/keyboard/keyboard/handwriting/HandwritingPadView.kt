// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.handwriting

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.ResourceUtils

/**
 * On-demand handwriting draw pad modal for recognizing individual characters,
 * digits, and punctuation with auto-commit on pause and editing gestures.
 */
class HandwritingPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), HandwritingCanvasView.OnStrokeListener {

    companion object {
        private const val TAG = "HandwritingPadView"
        private const val AUTO_COMMIT_DELAY_MS = 500L
    }

    private lateinit var topBar: View
    private lateinit var canvasView: HandwritingCanvasView
    private var bottomRowKeyboard: MainKeyboardView? = null

    private lateinit var chipCandidate1: TextView
    private lateinit var chipCandidate2: TextView
    private lateinit var chipCandidate3: TextView
    private lateinit var chipCandidate4: TextView
    private lateinit var txtHint: TextView
    private lateinit var btnClear: TextView

    private val candidateChips = mutableListOf<TextView>()
    private var activeCandidates = emptyList<HandwritingRecognizer.Candidate>()

    private var keyboardActionListener: KeyboardActionListener? = null
    private val handler = Handler(Looper.getMainLooper())

    private val autoCommitRunnable = Runnable {
        commitTopCandidate()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        topBar = findViewById(R.id.handwriting_top_bar)
        canvasView = findViewById(R.id.handwriting_canvas)
        bottomRowKeyboard = findViewById(R.id.bottom_row_keyboard)

        chipCandidate1 = findViewById(R.id.chip_candidate_1)
        chipCandidate2 = findViewById(R.id.chip_candidate_2)
        chipCandidate3 = findViewById(R.id.chip_candidate_3)
        chipCandidate4 = findViewById(R.id.chip_candidate_4)
        txtHint = findViewById(R.id.txt_handwriting_hint)
        btnClear = findViewById(R.id.btn_handwriting_clear)

        candidateChips.clear()
        candidateChips.add(chipCandidate1)
        candidateChips.add(chipCandidate2)
        candidateChips.add(chipCandidate3)
        candidateChips.add(chipCandidate4)

        canvasView.strokeListener = this

        candidateChips.forEach { chip ->
            chip.setOnClickListener {
                val text = chip.text.toString()
                if (text.isNotEmpty()) {
                    handler.removeCallbacks(autoCommitRunnable)
                    keyboardActionListener?.onTextInput(text)
                    canvasView.clearCanvas(animated = false)
                    resetCandidates()
                }
            }
        }

        btnClear.setOnClickListener {
            handler.removeCallbacks(autoCommitRunnable)
            canvasView.clearCanvas(animated = false)
            resetCandidates()
        }
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (enabled) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            canvasView.setLayerType(LAYER_TYPE_HARDWARE, null)
        } else {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            canvasView.setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
        measureChildren(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    fun startHandwriting(editorInfo: EditorInfo?, listener: KeyboardActionListener) {
        LogCatcher.i(TAG, "Starting handwriting draw pad modal")
        keyboardActionListener = listener
        handler.removeCallbacks(autoCommitRunnable)
        canvasView.clearCanvas(animated = false)
        resetCandidates()
        setupBottomRowKeyboard(editorInfo, listener)
        applyThemeColors()
        visibility = View.VISIBLE
    }

    fun stopHandwriting() {
        LogCatcher.i(TAG, "Stopping handwriting draw pad modal")
        handler.removeCallbacks(autoCommitRunnable)
        canvasView.clearCanvas(animated = false)
        resetCandidates()
        visibility = View.GONE
    }

    override fun onStrokeStarted() {
        // In-flight stroke cancels pending auto-commit from earlier stroke (allows multi-stroke chars)
        handler.removeCallbacks(autoCommitRunnable)
    }

    override fun onStrokeFinished(
        rawPoints: List<HandwritingRecognizer.Point>,
        isGesture: HandwritingRecognizer.Gesture
    ) {
        when (isGesture) {
            HandwritingRecognizer.Gesture.BACKSPACE -> {
                LogCatcher.i(TAG, "Gesture recognized: BACKSPACE")
                handler.removeCallbacks(autoCommitRunnable)
                keyboardActionListener?.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                canvasView.clearCanvas(animated = false)
                resetCandidates()
                return
            }
            HandwritingRecognizer.Gesture.SPACE -> {
                LogCatcher.i(TAG, "Gesture recognized: SPACE")
                handler.removeCallbacks(autoCommitRunnable)
                keyboardActionListener?.onTextInput(" ")
                canvasView.clearCanvas(animated = false)
                resetCandidates()
                return
            }
            HandwritingRecognizer.Gesture.ENTER -> {
                LogCatcher.i(TAG, "Gesture recognized: ENTER")
                handler.removeCallbacks(autoCommitRunnable)
                keyboardActionListener?.onCodeInput(Constants.CODE_ENTER, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                canvasView.clearCanvas(animated = false)
                resetCandidates()
                return
            }
            HandwritingRecognizer.Gesture.NONE -> {
                // Classify character strokes
                val allStrokes = canvasView.getAllStrokes()
                val candidates = HandwritingRecognizer.recognize(allStrokes)
                activeCandidates = candidates
                updateCandidateChips(candidates)

                // Schedule auto-commit debounce
                if (candidates.isNotEmpty()) {
                    handler.postDelayed(autoCommitRunnable, AUTO_COMMIT_DELAY_MS)
                }
            }
        }
    }

    private fun commitTopCandidate() {
        val top = activeCandidates.firstOrNull() ?: return
        LogCatcher.i(TAG, "Auto-committing top candidate: '${top.text}' (confidence=${top.confidence})")
        keyboardActionListener?.onTextInput(top.text)
        canvasView.clearCanvas(animated = true)
        resetCandidates()
    }

    private fun updateCandidateChips(candidates: List<HandwritingRecognizer.Candidate>) {
        if (candidates.isEmpty()) {
            resetCandidates()
            return
        }

        txtHint.visibility = View.GONE
        candidates.forEachIndexed { index, candidate ->
            if (index < candidateChips.size) {
                val chip = candidateChips[index]
                chip.text = candidate.text
                chip.visibility = View.VISIBLE
            }
        }
        for (i in candidates.size until candidateChips.size) {
            candidateChips[i].visibility = View.GONE
        }
    }

    private fun resetCandidates() {
        activeCandidates = emptyList()
        candidateChips.forEach {
            it.text = ""
            it.visibility = View.GONE
        }
        txtHint.visibility = View.VISIBLE
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo?, listener: KeyboardActionListener) {
        val keyboardView = bottomRowKeyboard ?: return
        val wrappedListener = object : KeyboardActionListener by listener {
            override fun onCodeInput(code: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
                if (code == KeyCode.ALPHA) {
                    stopHandwriting()
                    KeyboardSwitcher.getInstance().closeSecondaryKeyboard()
                    return
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

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        if (colors != null) {
            colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            colors.setBackground(topBar, ColorType.STRIP_BACKGROUND)

            val textColor = colors.get(ColorType.KEY_TEXT)
            val hintColor = colors.get(ColorType.KEY_HINT_TEXT)
            val accentColor = runCatching { colors.get(ColorType.SUGGESTION_AUTO_CORRECT) }.getOrDefault(Color.parseColor("#4FC3F7"))

            btnClear.setTextColor(hintColor)
            txtHint.setTextColor(hintColor)
            KeyboardTypeface.applyToTextView(btnClear)
            KeyboardTypeface.applyToTextView(txtHint)

            candidateChips.forEachIndexed { idx, chip ->
                chip.setTextColor(if (idx == 0) accentColor else textColor)
                KeyboardTypeface.applyToTextView(chip)
            }

            canvasView.setInkColor(accentColor)
        }
    }
}
