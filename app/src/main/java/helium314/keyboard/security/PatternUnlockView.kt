// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import kotlin.math.min

@SuppressLint("CustomViewStyleable")
class PatternUnlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.clipboardHistoryViewStyle
) : FrameLayout(context, attrs, defStyle) {

    private lateinit var gridView: PatternGridView
    private var chevronButton: ImageButton? = null
    private var selectButton: ImageButton? = null
    private var copyButton: ImageButton? = null
    private var pasteButton: ImageButton? = null
    private var spacerView: View? = null
    private var closeButton: ImageButton? = null
    private var statusTextView: TextView? = null
    private var pinnedKeysContainer: LinearLayout? = null

    private var keyboardActionListener: KeyboardActionListener? = null
    private var onUnlockSuccessCallback: (() -> Unit)? = null
    private var isUnlocked = false

    var isStealth: Boolean = true
        private set

    var isShowingPattern: Boolean = false
        private set

    private var navBarBottomInset: Int = 0

    init {
        fitsSystemWindows = false
        setPadding(0, 0, 0, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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
            setPadding(paddingLeft, paddingTop, paddingRight, effectiveInset)
            requestLayout()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        gridView = findViewById(R.id.pattern_grid_view)

        gridView.onPatternStarted = {
            // Stealth mode: completely silent with zero visual tell on strip
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

    private var isSecurityVaultTarget: Boolean = true

    @JvmOverloads
    fun startPatternUnlock(
        listener: KeyboardActionListener,
        keyVisualAttributes: KeyVisualAttributes?,
        isStealthMode: Boolean = true,
        isSecurityVault: Boolean = true,
        onSuccess: () -> Unit
    ) {
        keyboardActionListener = listener
        onUnlockSuccessCallback = onSuccess
        isUnlocked = false
        isStealth = isStealthMode
        isSecurityVaultTarget = isSecurityVault
        isShowingPattern = true
        gridView.isStealthMode = isStealthMode

        val strip = KeyboardSwitcher.getInstance().patternUnlockStrip
        if (strip != null) {
            chevronButton = strip.findViewById(R.id.pattern_strip_chevron_btn)
            selectButton = strip.findViewById(R.id.pattern_strip_select_btn)
            copyButton = strip.findViewById(R.id.pattern_strip_copy_btn)
            pasteButton = strip.findViewById(R.id.pattern_strip_paste_btn)
            spacerView = strip.findViewById(R.id.pattern_strip_spacer)
            closeButton = strip.findViewById(R.id.pattern_strip_close_btn)
            statusTextView = strip.findViewById(R.id.pattern_strip_status_text)
            pinnedKeysContainer = strip.findViewById(R.id.pattern_strip_pinned_keys)

            if (isStealthMode) {
                // Stealth mode: disguised toolbar mimicking active keyboard suggestions strip
                chevronButton?.visibility = View.VISIBLE
                statusTextView?.visibility = View.GONE
                pinnedKeysContainer?.visibility = View.VISIBLE
                closeButton?.visibility = View.GONE

                // Disguised paste button acts as dismiss/close
                pasteButton?.setOnClickListener {
                    stopPatternUnlock()
                    KeyboardSwitcher.getInstance().closeSecondaryKeyboard()
                }

                // Chevron, Select, Copy: "Only look not action. So better disguise."
                chevronButton?.setOnClickListener(null)
                chevronButton?.isClickable = false
                selectButton?.setOnClickListener(null)
                selectButton?.isClickable = false
                copyButton?.setOnClickListener(null)
                copyButton?.isClickable = false
            } else {
                // Normal mode: dedicated tactile pattern unlock
                chevronButton?.visibility = View.GONE
                pinnedKeysContainer?.visibility = View.GONE
                statusTextView?.visibility = View.VISIBLE
                statusTextView?.text = context.getString(R.string.pattern_draw_to_unlock)
                closeButton?.visibility = View.VISIBLE
                closeButton?.setOnClickListener {
                    stopPatternUnlock()
                    KeyboardSwitcher.getInstance().closeSecondaryKeyboard()
                }
            }

            strip.visibility = View.VISIBLE
        }

        applyThemeColors()
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
        val matches = if (isSecurityVaultTarget && VaultSessionManager.isSeparatePatternsEnabled(context)) {
            VaultSessionManager.verifySecurityPattern(context, pattern)
        } else {
            VaultSessionManager.verifyPattern(context, pattern)
        }
        if (matches) {
            isUnlocked = true
            if (isSecurityVaultTarget) {
                VaultSessionManager.startSecuritySession(context)
            } else {
                VaultSessionManager.startPrivacySession(context)
            }
            if (!isStealth) {
                statusTextView?.text = context.getString(R.string.pattern_unlocked)
            }
            postDelayed({
                val cb = onUnlockSuccessCallback
                stopPatternUnlock()
                cb?.invoke()
            }, 80)
        } else {

            if (isStealth) {
                // Stealth mode: silently clear pattern with zero error banners or visual tells
                gridView.clearPattern()
            } else {
                // Normal mode: visual error feedback and timed reset
                gridView.setErrorState()
                statusTextView?.text = context.getString(R.string.pattern_incorrect)
                postDelayed({
                    if (isShowingPattern && !isUnlocked) {
                        gridView.clearPattern()
                        statusTextView?.text = context.getString(R.string.pattern_draw_to_unlock)
                    }
                }, 700)
            }
        }
    }

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        val strip = KeyboardSwitcher.getInstance().patternUnlockStrip
        val toolbarHeight = min(
            resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
            resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()
        )
        if (colors != null) {
            strip?.let {
                colors.setBackground(it, ColorType.STRIP_BACKGROUND)
            }
            chevronButton?.let { btn ->
                btn.layoutParams.height = toolbarHeight
                btn.layoutParams.width = toolbarHeight
                btn.setBackgroundResource(R.drawable.toolbar_expand_key_background)
                colors.setColor(btn.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)
                val isRtl = strip?.layoutDirection == View.LAYOUT_DIRECTION_RTL
                btn.scaleX = if (isRtl) -1f else 1f
                val arrowIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context)
                if (arrowIcon != null) btn.setImageDrawable(arrowIcon)
                colors.setColor(btn, ColorType.TOOL_BAR_EXPAND_KEY)
            }
            selectButton?.let { btn ->
                val selectIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.SELECT_WORD.name, context)
                if (selectIcon != null) btn.setImageDrawable(selectIcon)
                colors.setColor(btn, ColorType.TOOL_BAR_KEY)
            }
            copyButton?.let { btn ->
                val copyIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.COPY.name, context)
                if (copyIcon != null) btn.setImageDrawable(copyIcon)
                colors.setColor(btn, ColorType.TOOL_BAR_KEY)
            }
            pasteButton?.let { btn ->
                val pasteIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.PASTE.name, context)
                if (pasteIcon != null) btn.setImageDrawable(pasteIcon)
                colors.setColor(btn, ColorType.TOOL_BAR_KEY)
            }
            closeButton?.let { btn ->
                val closeIcon = ContextCompat.getDrawable(context, R.drawable.ic_close_rounded)
                if (closeIcon != null) btn.setImageDrawable(closeIcon)
                colors.setColor(btn, ColorType.TOOL_BAR_KEY)
            }
            statusTextView?.let { tv ->
                tv.setTextColor(colors.get(ColorType.KEY_TEXT))
            }

            if (isStealth) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            }
        } else {
            val isNight = ResourceUtils.isNight(context.resources)
            val iconColor = if (isNight) Color.WHITE else Color.BLACK
            val stripBg = if (isNight) Color.parseColor("#202124") else Color.parseColor("#EEEEEE")
            strip?.setBackgroundColor(stripBg)
            chevronButton?.setBackgroundResource(R.drawable.toolbar_expand_key_background)
            chevronButton?.background?.setTint(if (isNight) Color.parseColor("#33888888") else Color.parseColor("#D0D0D0"))
            chevronButton?.setColorFilter(iconColor)
            selectButton?.setColorFilter(iconColor)
            copyButton?.setColorFilter(iconColor)
            pasteButton?.setColorFilter(iconColor)
            closeButton?.setColorFilter(iconColor)
            statusTextView?.setTextColor(iconColor)

            if (isStealth) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                setBackgroundColor(if (isNight) Color.parseColor("#1C1B1F") else Color.parseColor("#F5F5F5"))
            }
        }
    }
}
