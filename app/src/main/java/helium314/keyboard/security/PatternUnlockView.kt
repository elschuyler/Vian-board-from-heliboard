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
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
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
    private var pasteButton: ImageButton? = null
    private var spacerView: View? = null

    private var keyboardActionListener: KeyboardActionListener? = null
    private var onUnlockSuccessCallback: (() -> Unit)? = null
    private var isUnlocked = false

    var isStealth: Boolean = false
        private set

    var isShowingPattern: Boolean = false
        private set

    init {
        fitsSystemWindows = true
        setPadding(0, 0, 0, 0)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        gridView = findViewById(R.id.pattern_grid_view)

        gridView.onPatternStarted = {
            if (!isStealth) {
                statusText?.text = context.getString(R.string.pattern_verifying)
            }
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

    @JvmOverloads
    fun startPatternUnlock(
        listener: KeyboardActionListener,
        keyVisualAttributes: KeyVisualAttributes?,
        isStealthMode: Boolean = false,
        onSuccess: () -> Unit
    ) {
        keyboardActionListener = listener
        onUnlockSuccessCallback = onSuccess
        isUnlocked = false
        isStealth = isStealthMode
        isShowingPattern = true
        gridView.isStealthMode = isStealthMode

        val strip = KeyboardSwitcher.getInstance().patternUnlockStrip
        if (strip != null) {
            statusText = strip.findViewById(R.id.pattern_strip_status_text)
            closeButton = strip.findViewById(R.id.pattern_strip_close_btn)
            pasteButton = strip.findViewById(R.id.pattern_strip_paste_btn)
            spacerView = strip.findViewById(R.id.pattern_strip_spacer)

            val closeAction = View.OnClickListener {
                stopPatternUnlock()
                KeyboardSwitcher.getInstance().closeSecondaryKeyboard()
            }
            closeButton?.setOnClickListener(closeAction)
            pasteButton?.setOnClickListener(closeAction)

            if (isStealthMode) {
                statusText?.visibility = View.GONE
                closeButton?.visibility = View.GONE
                spacerView?.visibility = View.VISIBLE
                pasteButton?.visibility = View.VISIBLE
            } else {
                statusText?.visibility = View.VISIBLE
                closeButton?.visibility = View.VISIBLE
                spacerView?.visibility = View.GONE
                pasteButton?.visibility = View.GONE
            }
            strip.visibility = View.VISIBLE
        }

        applyThemeColors()
        if (!isStealthMode) {
            statusText?.text = context.getString(R.string.pattern_draw_to_unlock)
        }
        gridView.clearPattern()
        visibility = View.VISIBLE
    }

    fun stopPatternUnlock() {
        visibility = View.GONE
        isShowingPattern = false
        gridView.clearPattern()
        isUnlocked = false
        onUnlockSuccessCallback = null
    }

    private fun handlePatternEntered(pattern: List<Int>) {
        val matches = VaultSessionManager.verifyPattern(context, pattern)
        if (matches) {
            isUnlocked = true
            if (!isStealth) {
                statusText?.text = context.getString(R.string.pattern_unlocked)
            }
            VaultSessionManager.startSecuritySession()
            postDelayed({
                val cb = onUnlockSuccessCallback
                stopPatternUnlock()
                cb?.invoke()
            }, if (isStealth) 80 else 250)
        } else {
            if (!isStealth) {
                statusText?.text = context.getString(R.string.pattern_incorrect)
                gridView.setErrorState()
                postDelayed({
                    if (!isUnlocked) {
                        gridView.clearPattern()
                        statusText?.text = context.getString(R.string.pattern_draw_to_unlock)
                    }
                }, 650)
            } else {
                gridView.clearPattern()
            }
        }
    }

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        val strip = KeyboardSwitcher.getInstance().patternUnlockStrip
        if (colors != null) {
            val textColor = colors.get(ColorType.KEY_TEXT)

            statusText?.let { tv: TextView ->
                tv.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(tv)
            }
            closeButton?.let { btn: ImageButton ->
                colors.setColor(btn, ColorType.KEY_ICON)
            }
            pasteButton?.let { btn: ImageButton ->
                colors.setColor(btn, ColorType.TOOL_BAR_KEY)
            }
            strip?.let {
                colors.setBackground(it, ColorType.STRIP_BACKGROUND)
            }
            if (isStealth) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            }
        } else {
            statusText?.setTextColor(Color.WHITE)
            closeButton?.setColorFilter(Color.WHITE)
            pasteButton?.setColorFilter(Color.WHITE)
            if (isStealth) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                setBackgroundColor(Color.parseColor("#202124"))
            }
        }
    }
}
