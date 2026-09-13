// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.ResourceUtils

@SuppressLint("CustomViewStyleable")
class PatternUnlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle) {

    private lateinit var topBar: LinearLayout
    private lateinit var closeButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var gridView: PatternGridView

    private var keyboardActionListener: KeyboardActionListener? = null
    private var onUnlockSuccessCallback: (() -> Unit)? = null
    private var isUnlocked = false

    init {
        orientation = VERTICAL
        fitsSystemWindows = true
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        topBar = findViewById(R.id.pattern_top_bar)
        closeButton = findViewById(R.id.pattern_close_button)
        statusText = findViewById(R.id.pattern_status_text)
        gridView = findViewById(R.id.pattern_grid_view)

        closeButton.setOnClickListener {
            stopPatternUnlock()
            keyboardActionListener?.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }

        gridView.onPatternStarted = {
            statusText.text = "Verifying..."
        }

        gridView.onPatternCompleted = { pattern ->
            handlePatternEntered(pattern)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    fun startPatternUnlock(
        listener: KeyboardActionListener,
        keyVisualAttributes: KeyVisualAttributes?,
        onSuccess: () -> Unit
    ) {
        keyboardActionListener = listener
        onUnlockSuccessCallback = onSuccess
        isUnlocked = false

        applyThemeColors()
        statusText.text = "Draw pattern to unlock"
        gridView.clearPattern()
        visibility = View.VISIBLE
    }

    fun stopPatternUnlock() {
        visibility = View.GONE
        gridView.clearPattern()
        isUnlocked = false
        onUnlockSuccessCallback = null
    }

    private fun handlePatternEntered(pattern: List<Int>) {
        val matches = VaultSessionManager.verifyPattern(context, pattern)
        if (matches) {
            isUnlocked = true
            statusText.text = "Unlocked"
            VaultSessionManager.startSecuritySession()
            postDelayed({
                val cb = onUnlockSuccessCallback
                stopPatternUnlock()
                cb?.invoke()
            }, 250)
        } else {
            statusText.text = "Incorrect pattern, try again"
            gridView.setErrorState()
            postDelayed({
                if (!isUnlocked) {
                    gridView.clearPattern()
                    statusText.text = "Draw pattern to unlock"
                }
            }, 650)
        }
    }

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        if (colors != null) {
            val textColor = colors.get(ColorType.KEY_TEXT)
            val stripBg = colors.get(ColorType.STRIP_BACKGROUND)
            val keyBg = colors.get(ColorType.KEY_BACKGROUND)

            statusText.setTextColor(textColor)
            closeButton.setColorFilter(textColor)
            topBar.setBackgroundColor(stripBg)
            setBackgroundColor(keyBg)
        } else {
            statusText.setTextColor(Color.WHITE)
            closeButton.setColorFilter(Color.WHITE)
            setBackgroundColor(Color.parseColor("#202124"))
        }
    }
}
