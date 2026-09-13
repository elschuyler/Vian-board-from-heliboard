// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
import helium314.keyboard.keyboard.clipboard.ClipboardLayoutParams
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
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

    private val clipboardLayoutParams = ClipboardLayoutParams(context)
    private val keyBackgroundId: Int

    private lateinit var fatRow1: LinearLayout
    private lateinit var fatRow2: LinearLayout
    private lateinit var fatRow3: LinearLayout
    private lateinit var arrowRow1: LinearLayout
    private lateinit var arrowRow2: LinearLayout
    private var mainKeyboardView: MainKeyboardView? = null

    private var keyboardActionListener: KeyboardActionListener? = null
    private var inputConnection: InputConnection? = null

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

    init {
        orientation = VERTICAL
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        fitsSystemWindows = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
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

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            handleToolbarClick(tag)
        } else if (tag is DesktopShortcut) {
            DesktopShortcutsCatalog.executeShortcut(context, tag, inputConnection, keyboardActionListener)
        } else if (tag is Int) {
            // Hardware KeyEvent from Arrow/Nav cluster
            sendKeyEvent(tag)
        }
    }

    override fun onLongClick(view: View): Boolean {
        val tag = view.tag
        if (tag is ToolbarKey) {
            when (tag) {
                ToolbarKey.COPY -> {
                    keyboardActionListener?.onCodeInput(KeyCode.PROMPT_LIST, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                    return true
                }
                ToolbarKey.PASTE -> {
                    keyboardActionListener?.onCodeInput(KeyCode.CLIPBOARD, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                    return true
                }
                ToolbarKey.SELECT_WORD -> {
                    sendCtrlKey(KeyEvent.KEYCODE_A)
                    return true
                }
                else -> return false
            }
        } else if (tag is DesktopShortcut && tag.longPressCode != 0) {
            keyboardActionListener?.onCodeInput(tag.longPressCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            return true
        }
        return false
    }

    private fun handleToolbarClick(key: ToolbarKey) {
        when (key) {
            ToolbarKey.CLOSE_HISTORY -> {
                keyboardActionListener?.onCodeInput(KeyCode.DESKTOP_SHORTCUTS, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
            ToolbarKey.UNDO -> sendCtrlKey(KeyEvent.KEYCODE_Z)
            ToolbarKey.REDO -> sendCtrlKey(KeyEvent.KEYCODE_Y)
            ToolbarKey.PAGE_START -> sendCtrlKey(KeyEvent.KEYCODE_MOVE_HOME)
            ToolbarKey.PAGE_END -> sendCtrlKey(KeyEvent.KEYCODE_MOVE_END)
            ToolbarKey.SELECT_WORD -> sendCtrlKey(KeyEvent.KEYCODE_W)
            ToolbarKey.COPY -> sendCtrlKey(KeyEvent.KEYCODE_C)
            ToolbarKey.PASTE -> sendCtrlKey(KeyEvent.KEYCODE_V)
            else -> {}
        }
    }

    fun startDesktopShortcuts(
        actionListener: KeyboardActionListener,
        keyVisualAttributes: KeyVisualAttributes?,
        editorInfo: EditorInfo,
        ic: InputConnection?
    ) {
        this.keyboardActionListener = actionListener
        this.inputConnection = ic

        Settings.getValues().mColors.setBackground(this, ColorType.MAIN_BACKGROUND)
        initializeToolbar()
        toolbarKeys.forEach { it.isEnabled = false; it.isEnabled = true }

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttributes)
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }

        buildFatButtons(params)
        buildArrowCluster(params)

        mainKeyboardView?.let { kbView ->
            // Intercept bottom row keys so enter and delete act as desktop keys
            kbView.setKeyboardActionListener(createBottomRowActionListener(actionListener))
            PointerTracker.switchTo(kbView)
            val bottomKls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
            val keyboard = bottomKls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
            kbView.setKeyboard(keyboard)
        }
    }

    fun stopDesktopShortcuts() {
        // Cleanup if needed
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

        // Row 2: 2 buttons
        val row2Shortcuts = shortcuts.drop(3).take(2)
        row2Shortcuts.forEach { s ->
            fatRow2.addView(createFatButton(s, params, 1f))
        }

        // Row 3: 2 buttons
        val row3Shortcuts = shortcuts.drop(5).take(2)
        row3Shortcuts.forEach { s ->
            fatRow3.addView(createFatButton(s, params, 1f))
        }
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

        // Big word title
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

        // Small shortcut combination subtitle
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
        arrowRow1.removeAllViews()
        arrowRow2.removeAllViews()

        // Row 1: Home, Up, End
        arrowRow1.addView(createArrowButton("↖ Home", KeyEvent.KEYCODE_MOVE_HOME, params, 1f))
        arrowRow1.addView(createArrowButton("↑ Up", KeyEvent.KEYCODE_DPAD_UP, params, 1f))
        arrowRow1.addView(createArrowButton("↘ End", KeyEvent.KEYCODE_MOVE_END, params, 1f))

        // Row 2: Left, Down, Right
        arrowRow2.addView(createArrowButton("← Left", KeyEvent.KEYCODE_DPAD_LEFT, params, 1f))
        arrowRow2.addView(createArrowButton("↓ Down", KeyEvent.KEYCODE_DPAD_DOWN, params, 1f))
        arrowRow2.addView(createArrowButton("→ Right", KeyEvent.KEYCODE_DPAD_RIGHT, params, 1f))
    }

    private fun createArrowButton(
        label: String,
        keyCode: Int,
        params: KeyDrawParams,
        weight: Float
    ): View {
        val density = context.resources.displayMetrics.density
        val btn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            tag = keyCode
            isClickable = true
            isFocusable = true
            if (keyBackgroundId != 0) {
                setBackgroundResource(keyBackgroundId)
            }
            Settings.getValues().mColors.setBackground(this, ColorType.KEY_BACKGROUND)
            setOnClickListener(this@DesktopShortcutsView)

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

    private fun createBottomRowActionListener(original: KeyboardActionListener): KeyboardActionListener {
        return object : KeyboardActionListener by original {
            override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
                when (primaryCode) {
                    KeyCode.ALPHA -> {
                        // Switch back to alphabet typing
                        original.onCodeInput(KeyCode.DESKTOP_SHORTCUTS, x, y, false)
                    }
                    KeyCode.DELETE -> {
                        // Desktop Backspace variant
                        sendKeyEvent(KeyEvent.KEYCODE_DEL)
                    }
                    Constants.CODE_ENTER -> {
                        // Desktop Enter variant
                        sendKeyEvent(KeyEvent.KEYCODE_ENTER)
                    }
                    Constants.CODE_SPACE -> {
                        // Desktop Space variant
                        sendKeyEvent(KeyEvent.KEYCODE_SPACE)
                    }
                    else -> original.onCodeInput(primaryCode, x, y, isKeyRepeat)
                }
            }
        }
    }

    private fun sendKeyEvent(keyCode: Int) {
        try {
            val ic = inputConnection ?: return
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (t: Throwable) {
            LogCatcher.log('E', "DesktopShortcuts", "sendKeyEvent-$keyCode: ${t.message}", t)
        }
    }

    private fun sendCtrlKey(keyCode: Int) {
        try {
            val ic = inputConnection ?: return
            val now = SystemClock.uptimeMillis()
            val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        } catch (t: Throwable) {
            LogCatcher.log('E', "DesktopShortcuts", "sendCtrlKey-$keyCode: ${t.message}", t)
        }
    }
}
