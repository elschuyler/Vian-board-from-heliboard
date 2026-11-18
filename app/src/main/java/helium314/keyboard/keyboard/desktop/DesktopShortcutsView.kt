// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.clipboard.ClipboardLayoutParams
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.prefs

@SuppressLint("CustomViewStyleable")
class DesktopShortcutsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), View.OnClickListener, View.OnLongClickListener {

    companion object {
        internal const val ARROW_REPEAT_INITIAL_DELAY_MS = 400L
        internal const val ARROW_REPEAT_INTERVAL_MS = 50L
    }

    private val clipboardLayoutParams = ClipboardLayoutParams(context)
    private val keyBackgroundId: Int

    private lateinit var fatRow1: LinearLayout
    private lateinit var fatRow2: LinearLayout
    private lateinit var fatRow3: LinearLayout
    private lateinit var arrowRow1: LinearLayout
    private lateinit var arrowRow2: LinearLayout
    private var mainKeyboardView: MainKeyboardView? = null

    private var keyboardActionListener: KeyboardActionListener? = null
    private var bottomRowActionListener: KeyboardActionListener? = null
    private var inputConnectionProvider: (() -> InputConnection?)? = null
    private var editorInfoProvider: (() -> EditorInfo?)? = null
    private var flushComposingAction: (() -> Unit)? = null
    private var fallbackEditorInfo: EditorInfo? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var activeRepeatRunnable: Runnable? = null

    private val toolbarKeys = listOf(
        ToolbarKey.UNDO,
        ToolbarKey.REDO,
        ToolbarKey.PAGE_START,
        ToolbarKey.PAGE_END,
        ToolbarKey.SELECT_WORD,
        ToolbarKey.COPY,
        ToolbarKey.PASTE,
        ToolbarKey.CLOSE_HISTORY
    ).map { createToolbarKey(context, it) }
    private var isToolbarInitialized = false

    private var navBarBottomInset: Int = 0
    private val basePaddingBottom: Int

    init {
        orientation = VERTICAL
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        fitsSystemWindows = true
        basePaddingBottom = paddingBottom
        updateNavBarInset()
    }

    private fun updateNavBarInset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val windowInsets = wm?.currentWindowMetrics?.windowInsets
            if (windowInsets != null) {
                val insets = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
                applyBottomInset(insets.bottom)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        updateNavBarInset()
    }

    override fun onDetachedFromWindow() {
        cancelArrowRepeat()
        super.onDetachedFromWindow()
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        super.onMeasure(widthSpec, heightSpec)
        setMeasuredDimension(width, height)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        fatRow1 = findViewById(R.id.fat_row_1)
        fatRow2 = findViewById(R.id.fat_row_2)
        fatRow3 = findViewById(R.id.fat_row_3)
        arrowRow1 = findViewById(R.id.arrow_row_1)
        arrowRow2 = findViewById(R.id.arrow_row_2)
        mainKeyboardView = findViewById(R.id.bottom_row_keyboard)
    }

    private fun setupToolbarKeys() {
        val toolbarKeyLayoutParams = LayoutParams(
            resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
            LayoutParams.MATCH_PARENT
        )
        toolbarKeys.forEach { it.layoutParams = toolbarKeyLayoutParams }
    }

    private fun initializeToolbar() {
        if (isToolbarInitialized) return
        val colors = Settings.getValues().mColors
        val desktopStrip = KeyboardSwitcher.getInstance().desktopShortcutsStrip ?: return
        setupToolbarKeys()
        toolbarKeys.forEach { keyView ->
            desktopStrip.addView(keyView)
            keyView.setOnClickListener(this)
            keyView.setOnLongClickListener(this)
            colors.setColor(keyView, ColorType.TOOL_BAR_KEY)
            colors.setBackground(keyView, ColorType.STRIP_BACKGROUND)
        }
        isToolbarInitialized = true
    }

    private fun currentInputConnection(): InputConnection? = inputConnectionProvider?.invoke()

    private fun currentEditorInfo(): EditorInfo? = editorInfoProvider?.invoke() ?: fallbackEditorInfo

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            handleToolbarClick(tag)
        } else if (tag is DesktopShortcut) {
            flushComposingAction?.invoke()
            DesktopShortcutsCatalog.executeShortcut(
                context = context,
                shortcut = tag,
                ic = currentInputConnection(),
                actionListener = keyboardActionListener
            )
        } else if (tag is Int) {
            flushComposingAction?.invoke()
            sendKeyEvent(tag)
        }
    }

    override fun onLongClick(view: View): Boolean {
        val tag = view.tag
        if (tag is ToolbarKey) {
            when (tag) {
                ToolbarKey.COPY -> {
                    flushComposingAction?.invoke()
                    keyboardActionListener?.onCodeInput(KeyCode.PROMPT_LIST, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                    return true
                }
                ToolbarKey.PASTE -> {
                    flushComposingAction?.invoke()
                    keyboardActionListener?.onCodeInput(KeyCode.CLIPBOARD, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                    return true
                }
                ToolbarKey.SELECT_WORD -> {
                    flushComposingAction?.invoke()
                    val ic = currentInputConnection()
                    val sent = DesktopShortcutsCatalog.sendDesktopKeyEvent(
                        ic,
                        KeyEvent.KEYCODE_A,
                        DesktopShortcutsCatalog.buildMetaState(isCtrl = true)
                    )
                    if (!sent) {
                        ic?.performContextMenuAction(android.R.id.selectAll)
                    }
                    return true
                }
                else -> return false
            }
        } else if (tag is DesktopShortcut && tag.longPressCode != 0) {
            flushComposingAction?.invoke()
            keyboardActionListener?.onCodeInput(tag.longPressCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            return true
        }
        return false
    }

    internal fun handleToolbarClick(key: ToolbarKey) {
        when (key) {
            ToolbarKey.CLOSE_HISTORY -> {
                keyboardActionListener?.onCodeInput(KeyCode.DESKTOP_SHORTCUTS, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
            ToolbarKey.UNDO -> {
                flushComposingAction?.invoke()
                val ic = currentInputConnection()
                val sent = DesktopShortcutsCatalog.sendDesktopKeyEvent(
                    ic,
                    KeyEvent.KEYCODE_Z,
                    DesktopShortcutsCatalog.buildMetaState(isCtrl = true)
                )
                if (!sent) {
                    ic?.performContextMenuAction(android.R.id.undo)
                }
            }
            ToolbarKey.REDO -> {
                flushComposingAction?.invoke()
                val ic = currentInputConnection()
                val sent = DesktopShortcutsCatalog.sendDesktopKeyEvent(
                    ic,
                    KeyEvent.KEYCODE_Z,
                    DesktopShortcutsCatalog.buildMetaState(isCtrl = true, isShift = true)
                )
                if (!sent) {
                    ic?.performContextMenuAction(android.R.id.redo)
                }
            }
            ToolbarKey.PAGE_START -> {
                flushComposingAction?.invoke()
                val listener = keyboardActionListener
                if (listener != null) {
                    listener.onCodeInput(KeyCode.MOVE_START_OF_PAGE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                } else {
                    DesktopShortcutsCatalog.sendDesktopKeyEvent(
                        currentInputConnection(),
                        KeyEvent.KEYCODE_MOVE_HOME,
                        DesktopShortcutsCatalog.buildMetaState(isCtrl = true)
                    )
                }
            }
            ToolbarKey.PAGE_END -> {
                flushComposingAction?.invoke()
                val listener = keyboardActionListener
                if (listener != null) {
                    listener.onCodeInput(KeyCode.MOVE_END_OF_PAGE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                } else {
                    DesktopShortcutsCatalog.sendDesktopKeyEvent(
                        currentInputConnection(),
                        KeyEvent.KEYCODE_MOVE_END,
                        DesktopShortcutsCatalog.buildMetaState(isCtrl = true)
                    )
                }
            }
            ToolbarKey.SELECT_WORD -> {
                flushComposingAction?.invoke()
                keyboardActionListener?.onCodeInput(KeyCode.CLIPBOARD_SELECT_WORD, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
            ToolbarKey.COPY -> {
                flushComposingAction?.invoke()
                val ic = currentInputConnection()
                if (ic?.performContextMenuAction(android.R.id.copy) != true) {
                    DesktopShortcutsCatalog.sendDesktopKeyEvent(
                        ic,
                        KeyEvent.KEYCODE_C,
                        DesktopShortcutsCatalog.buildMetaState(isCtrl = true)
                    )
                }
            }
            ToolbarKey.PASTE -> {
                flushComposingAction?.invoke()
                val ic = currentInputConnection()
                if (ic?.performContextMenuAction(android.R.id.paste) != true) {
                    DesktopShortcutsCatalog.sendDesktopKeyEvent(
                        ic,
                        KeyEvent.KEYCODE_V,
                        DesktopShortcutsCatalog.buildMetaState(isCtrl = true)
                    )
                }
            }
            else -> {}
        }
    }

    fun startDesktopShortcuts(
        actionListener: KeyboardActionListener,
        keyVisualAttributes: KeyVisualAttributes?,
        editorInfo: EditorInfo,
        ic: InputConnection?
    ) {
        startDesktopShortcuts(
            actionListener = actionListener,
            keyVisualAttributes = keyVisualAttributes,
            editorInfo = editorInfo,
            inputConnectionProvider = { ic },
            editorInfoProvider = { editorInfo },
            flushComposingAction = {}
        )
    }

    fun startDesktopShortcuts(
        actionListener: KeyboardActionListener,
        keyVisualAttributes: KeyVisualAttributes?,
        editorInfo: EditorInfo,
        inputConnectionProvider: () -> InputConnection?,
        editorInfoProvider: () -> EditorInfo?,
        flushComposingAction: () -> Unit
    ) {
        cancelArrowRepeat()
        this.keyboardActionListener = actionListener
        this.inputConnectionProvider = inputConnectionProvider
        this.editorInfoProvider = editorInfoProvider
        this.flushComposingAction = flushComposingAction
        this.fallbackEditorInfo = editorInfo

        Settings.getValues().mColors.setBackground(this, ColorType.MAIN_BACKGROUND)
        initializeToolbar()
        toolbarKeys.forEach { it.isEnabled = false; it.isEnabled = true }

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttributes)
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }

        buildFatButtons(params)
        buildArrowCluster(params)

        val bottomListener = createBottomRowActionListener(actionListener)
        this.bottomRowActionListener = bottomListener
        mainKeyboardView?.let { kbView ->
            // Intercept bottom row keys so enter, space, and delete use desktop-first dispatch
            kbView.setKeyboardActionListener(bottomListener)
            PointerTracker.switchTo(kbView)
            val bottomKls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
            val keyboard = bottomKls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
            kbView.setKeyboard(keyboard)
        }
    }

    fun stopDesktopShortcuts() {
        cancelArrowRepeat()
        keyboardActionListener = null
        bottomRowActionListener = null
        inputConnectionProvider = null
        editorInfoProvider = null
        flushComposingAction = null
        fallbackEditorInfo = null
        if (Settings.getValues().mLiteMode) {
            if (this::fatRow1.isInitialized) fatRow1.removeAllViews()
            if (this::fatRow2.isInitialized) fatRow2.removeAllViews()
            if (this::fatRow3.isInitialized) fatRow3.removeAllViews()
            if (this::arrowRow1.isInitialized) arrowRow1.removeAllViews()
            if (this::arrowRow2.isInitialized) arrowRow2.removeAllViews()
        }
    }

    internal fun dispatchBottomRowCodeForTesting(primaryCode: Int, isKeyRepeat: Boolean = false) {
        bottomRowActionListener?.onCodeInput(
            primaryCode,
            Constants.NOT_A_COORDINATE,
            Constants.NOT_A_COORDINATE,
            isKeyRepeat
        )
    }

    private fun buildFatButtons(params: KeyDrawParams) {
        fatRow1.removeAllViews()
        fatRow2.removeAllViews()
        fatRow3.removeAllViews()

        val shortcuts = DesktopShortcutsCatalog.getChosenShortcuts(context.prefs())

        // Row 1: 3 buttons
        val row1Shortcuts = shortcuts.take(3)
        row1Shortcuts.forEach { s ->
            fatRow1.addView(createFatButton(s, params, 1f))
        }
        fatRow1.visibility = if (row1Shortcuts.isEmpty()) View.GONE else View.VISIBLE

        // Row 2: 2 buttons
        val row2Shortcuts = shortcuts.drop(3).take(2)
        row2Shortcuts.forEach { s ->
            fatRow2.addView(createFatButton(s, params, 1f))
        }
        fatRow2.visibility = if (row2Shortcuts.isEmpty()) View.GONE else View.VISIBLE

        // Row 3: 2 buttons
        val row3Shortcuts = shortcuts.drop(5).take(2)
        row3Shortcuts.forEach { s ->
            fatRow3.addView(createFatButton(s, params, 1f))
        }
        fatRow3.visibility = if (row3Shortcuts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun createFatButton(
        shortcut: DesktopShortcut,
        params: KeyDrawParams,
        weight: Float
    ): View {
        val density = context.resources.displayMetrics.density
        val btn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            tag = shortcut
            contentDescription = "${shortcut.title} (${shortcut.subtitle})"
            isClickable = true
            isFocusable = true
            if (keyBackgroundId != 0) {
                setBackgroundResource(keyBackgroundId)
            }
            Settings.getValues().mColors.setBackground(this, ColorType.KEY_BACKGROUND)
            setOnClickListener(this@DesktopShortcutsView)
            setOnLongClickListener(this@DesktopShortcutsView)

            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                val m = (2 * density).toInt()
                setMargins(m, m, m, m)
            }
            layoutParams = lp
            val pad = (4 * density).toInt()
            setPadding(pad, (2 * density).toInt(), pad, (2 * density).toInt())
        }

        val titleText = TextView(context).apply {
            text = shortcut.title
            typeface = params.mTypeface ?: Typeface.DEFAULT_BOLD
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(params.mTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        btn.addView(titleText)

        val subText = TextView(context).apply {
            text = shortcut.subtitle
            typeface = params.mTypeface ?: Typeface.DEFAULT
            setTextColor(params.mTextColor)
            alpha = 0.72f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        btn.addView(subText)

        return btn
    }

    private fun buildArrowCluster(params: KeyDrawParams) {
        cancelArrowRepeat()
        arrowRow1.removeAllViews()
        arrowRow2.removeAllViews()

        // Row 1: Home, Up, End
        arrowRow1.addView(createArrowButton("↖ Home", KeyEvent.KEYCODE_MOVE_HOME, params, 1f, repeatable = false))
        arrowRow1.addView(createArrowButton("↑ Up", KeyEvent.KEYCODE_DPAD_UP, params, 1f, repeatable = true))
        arrowRow1.addView(createArrowButton("↘ End", KeyEvent.KEYCODE_MOVE_END, params, 1f, repeatable = false))

        // Row 2: Left, Down, Right
        arrowRow2.addView(createArrowButton("← Left", KeyEvent.KEYCODE_DPAD_LEFT, params, 1f, repeatable = true))
        arrowRow2.addView(createArrowButton("↓ Down", KeyEvent.KEYCODE_DPAD_DOWN, params, 1f, repeatable = true))
        arrowRow2.addView(createArrowButton("→ Right", KeyEvent.KEYCODE_DPAD_RIGHT, params, 1f, repeatable = true))
    }

    private fun createArrowButton(
        label: String,
        keyCode: Int,
        params: KeyDrawParams,
        weight: Float,
        repeatable: Boolean
    ): View {
        val density = context.resources.displayMetrics.density
        var handledByTouch = false
        val btn = object : LinearLayout(context) {
            override fun performClick(): Boolean {
                if (repeatable && !handledByTouch) {
                    flushComposingAction?.invoke()
                    sendKeyEvent(keyCode)
                }
                return super.performClick()
            }
        }.apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            tag = keyCode
            contentDescription = label
            isClickable = true
            isFocusable = true
            if (keyBackgroundId != 0) {
                setBackgroundResource(keyBackgroundId)
            }
            Settings.getValues().mColors.setBackground(this, ColorType.KEY_BACKGROUND)

            if (repeatable) {
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            cancelArrowRepeat()
                            v.isPressed = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            flushComposingAction?.invoke()
                            sendKeyEvent(keyCode)
                            val repeatRunnable = object : Runnable {
                                override fun run() {
                                    if (activeRepeatRunnable !== this || !v.isPressed) return
                                    sendKeyEvent(keyCode)
                                    repeatHandler.postDelayed(this, ARROW_REPEAT_INTERVAL_MS)
                                }
                            }
                            activeRepeatRunnable = repeatRunnable
                            repeatHandler.postDelayed(repeatRunnable, ARROW_REPEAT_INITIAL_DELAY_MS)
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val wasPressed = v.isPressed
                            v.isPressed = false
                            cancelArrowRepeat()
                            if (wasPressed) {
                                handledByTouch = true
                                try {
                                    v.performClick()
                                } finally {
                                    handledByTouch = false
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            cancelArrowRepeat()
                            true
                        }
                        else -> false
                    }
                }
            } else {
                setOnClickListener(this@DesktopShortcutsView)
            }

            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                val m = (2 * density).toInt()
                setMargins(m, m, m, m)
            }
            layoutParams = lp
            val pad = (2 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val text = TextView(context).apply {
            this.text = label
            typeface = params.mTypeface ?: Typeface.DEFAULT_BOLD
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(params.mTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        btn.addView(text)
        return btn
    }

    private fun cancelArrowRepeat() {
        activeRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        activeRepeatRunnable = null
    }

    private fun createBottomRowActionListener(original: KeyboardActionListener): KeyboardActionListener {
        return object : KeyboardActionListener by original {
            override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
                when (primaryCode) {
                    KeyCode.ALPHA -> {
                        original.onCodeInput(KeyCode.DESKTOP_SHORTCUTS, x, y, false)
                    }
                    KeyCode.DELETE -> {
                        if (!isKeyRepeat) {
                            flushComposingAction?.invoke()
                        }
                        val sent = DesktopShortcutsCatalog.sendDesktopKeyEvent(
                            currentInputConnection(),
                            KeyEvent.KEYCODE_DEL
                        )
                        if (!sent) {
                            original.onCodeInput(KeyCode.DELETE, x, y, isKeyRepeat)
                        }
                    }
                    Constants.CODE_SPACE -> {
                        if (!isKeyRepeat) {
                            flushComposingAction?.invoke()
                        }
                        val sent = DesktopShortcutsCatalog.sendDesktopKeyEvent(
                            currentInputConnection(),
                            KeyEvent.KEYCODE_SPACE
                        )
                        if (!sent) {
                            original.onCodeInput(Constants.CODE_SPACE, x, y, isKeyRepeat)
                        }
                    }
                    Constants.CODE_ENTER -> {
                        flushComposingAction?.invoke()
                        val ei = currentEditorInfo()
                        val actionId = if (ei != null) {
                            InputTypeUtils.getImeOptionsActionIdFromEditorInfo(ei)
                        } else {
                            EditorInfo.IME_ACTION_UNSPECIFIED
                        }
                        val hasExplicitImeAction = actionId == InputTypeUtils.IME_ACTION_CUSTOM_LABEL ||
                            (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED)
                        if (hasExplicitImeAction) {
                            original.onCodeInput(Constants.CODE_ENTER, x, y, false)
                        } else {
                            val sent = DesktopShortcutsCatalog.sendDesktopKeyEvent(
                                currentInputConnection(),
                                KeyEvent.KEYCODE_ENTER
                            )
                            if (!sent) {
                                original.onCodeInput(Constants.CODE_ENTER, x, y, isKeyRepeat)
                            }
                        }
                    }
                    else -> original.onCodeInput(primaryCode, x, y, isKeyRepeat)
                }
            }
        }
    }

    private fun sendKeyEvent(keyCode: Int) {
        try {
            DesktopShortcutsCatalog.sendDesktopKeyEvent(currentInputConnection(), keyCode)
        } catch (t: Throwable) {
            LogCatcher.log('E', "DesktopShortcuts", "sendKeyEvent-$keyCode: ${t.message}", t)
        }
    }
}
