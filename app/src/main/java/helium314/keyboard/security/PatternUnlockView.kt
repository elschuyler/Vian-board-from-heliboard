// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

@SuppressLint("CustomViewStyleable")
class PatternUnlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.clipboardHistoryViewStyle
) : FrameLayout(context, attrs, defStyle) {

    private lateinit var gridView: PatternGridView
    private var statusText: TextView? = null
    private var closeButton: ImageButton? = null

    private var keyboardActionListener: KeyboardActionListener? = null
    private var onUnlockSuccessCallback: (() -> Unit)? = null
    private var isUnlocked = false

    init {
        fitsSystemWindows = false
        setPadding(0, 0, 0, 0)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        gridView = findViewById(R.id.pattern_grid_view)

        gridView.onPatternStarted = {
            statusText?.text = context.getString(R.string.pattern_verifying)
        }

        gridView.onPatternCompleted = { pattern ->
            handlePatternEntered(pattern)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues())
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues())
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        super.onMeasure(widthSpec, heightSpec)
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

        val strip = KeyboardSwitcher.getInstance().patternUnlockStrip
        if (strip != null) {
            statusText = strip.findViewById(R.id.pattern_strip_status_text)
            closeButton = strip.findViewById(R.id.pattern_strip_close_btn)

            closeButton?.setOnClickListener {
                stopPatternUnlock()
                keyboardActionListener?.onCodeInput(
                    KeyCode.ALPHA,
                    Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE,
                    false
                )
            }
        }

        applyThemeColors()
        statusText?.text = context.getString(R.string.pattern_draw_to_unlock)
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
            statusText?.text = context.getString(R.string.pattern_unlocked)
            VaultSessionManager.startSecuritySession()
            postDelayed({
                val cb = onUnlockSuccessCallback
                stopPatternUnlock()
                cb?.invoke()
            }, 250)
        } else {
            statusText?.text = context.getString(R.string.pattern_incorrect)
            gridView.setErrorState()
            postDelayed({
                if (!isUnlocked) {
                    gridView.clearPattern()
                    statusText?.text = context.getString(R.string.pattern_draw_to_unlock)
                }
            }, 650)
        }
    }

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        val strip = KeyboardSwitcher.getInstance().patternUnlockStrip
        if (colors != null) {
            val textColor = colors.get(ColorType.KEY_TEXT)
            val stripBg = colors.get(ColorType.STRIP_BACKGROUND)
            val keyBg = colors.get(ColorType.KEY_BACKGROUND)

            statusText?.let { tv: TextView ->
                tv.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(tv)
            }
            closeButton?.let { btn: ImageButton ->
                colors.setColor(btn, ColorType.KEY_ICON)
            }
            strip?.let {
                colors.setBackground(it, ColorType.STRIP_BACKGROUND)
            }
            colors.setBackground(this, ColorType.MAIN_BACKGROUND)
        } else {
            statusText?.setTextColor(Color.WHITE)
            closeButton?.setColorFilter(Color.WHITE)
            setBackgroundColor(Color.parseColor("#202124"))
        }
    }
}
