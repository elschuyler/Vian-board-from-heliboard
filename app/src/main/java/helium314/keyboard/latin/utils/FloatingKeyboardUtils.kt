package helium314.keyboard.latin.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.setFloatingSize
import kotlin.math.abs

object FloatingKeyboardUtils {
    private val TAG = this::class.java.simpleName
    private val windowFrame = Rect()
    private var extraHeight = 0f

    @Volatile
    private var isFoldedState = false
    private var preFoldX = 0
    private var preFoldY = 0
    private var foldedX = 0
    private var foldedY = 0
    private var hasMovedWhileFolded = false

    @JvmStatic
    fun isFolded(): Boolean = isFoldedState

    @JvmStatic
    fun getFoldedPosition(context: Context): Pair<Int, Int> = foldedX to foldedY

    @JvmStatic
    fun getFoldedSizePx(context: Context): Int = (52 * context.resources.displayMetrics.density).toInt()

    @JvmStatic
    fun setFloating(view: View?) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        view.getWindowVisibleDisplayFrame(windowFrame)
        extraHeight = getSuggestionStripHeight(view.resources).toFloat()

        ViewLayoutUtils.updateLayoutGravityOf(view, android.view.Gravity.TOP or android.view.Gravity.START)
        view.findViewById<View>(R.id.main_keyboard_frame)?.let {
            it.layoutParams = it.layoutParams?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            ViewLayoutUtils.updateLayoutGravityOf(it, android.view.Gravity.TOP or android.view.Gravity.START)
            val radiusPx = 16f * view.resources.displayMetrics.density
            it.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
            it.clipToOutline = true
        }
        val inputArea = view.rootView.findViewById<View?>(android.R.id.inputArea)
        if (inputArea != null) {
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, android.view.Gravity.TOP or android.view.Gravity.START)
        }

        if (isFoldedState) {
            val size = getFoldedSizePx(view.context)
            view.findViewById<View>(R.id.strip_container)?.isGone = true
            view.findViewById<View>(R.id.keyboard_view_wrapper)?.isGone = true
            view.findViewById<View>(R.id.float_handle_container)?.isGone = true

            val bubble = view.findViewById<View>(R.id.floating_folded_button)
            bubble?.isVisible = true
            bubble?.setupFoldedButton(view)

            ViewLayoutUtils.placeViewAt(view, foldedX, foldedY, size, size)
            notifyInsetsChanged(view)
            return
        }

        // Unfolded setup
        view.findViewById<View>(R.id.floating_folded_button)?.isGone = true
        if (Settings.getValues().mToolbarMode != ToolbarMode.HIDDEN) {
            view.findViewById<View>(R.id.strip_container)?.isVisible = true
        }
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.isVisible = true
        view.findViewById<View>(R.id.float_handle_container)?.isVisible = true

        val (x, y) = readPosition(
            view.context,
            windowFrame.right - windowFrame.left - Settings.getValues().mFloatingWidth,
            windowFrame.bottom - windowFrame.top - extraHeight.toInt() - Settings.getValues().mFloatingHeight
        )
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "place floating view at $x, $y, width ${Settings.getValues().mFloatingWidth}, height ${Settings.getValues().mFloatingHeight}")
        ViewLayoutUtils.placeViewAt(view, x, y, Settings.getValues().mFloatingWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Setup 3 bottom buttons in order:
        // 1. Close: close floating mode and return to normal docked keyboard
        view.findViewById<View>(R.id.close_floating_handle)?.setOnClickListener {
            isFoldedState = false
            KeyboardSwitcher.getInstance()?.closeFloatingModal()
        }

        // 2. Minimize: fold into app icon bubble
        view.findViewById<View>(R.id.minimize_floating_handle)?.setOnClickListener {
            fold(view)
        }

        // 3. Resize (drag): drag to resize width/height
        view.findViewById<ImageView>(R.id.resize_handle)?.setResizeListener(lp, view)

        // Pre-bind folded button
        view.findViewById<View>(R.id.floating_folded_button)?.setupFoldedButton(view)

        // Setup chevron long-press drag
        val chevron = view.findViewById<View>(R.id.suggestions_strip_toolbar_key)
        setupChevronDrag(chevron, view)
    }

    fun fold(view: View) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        isFoldedState = true
        preFoldX = lp.leftMargin
        preFoldY = lp.topMargin
        foldedX = preFoldX
        foldedY = preFoldY
        hasMovedWhileFolded = false

        view.findViewById<View>(R.id.strip_container)?.isGone = true
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.isGone = true
        view.findViewById<View>(R.id.float_handle_container)?.isGone = true

        val bubble = view.findViewById<View>(R.id.floating_folded_button)
        bubble?.isVisible = true
        bubble?.setupFoldedButton(view)

        val size = getFoldedSizePx(view.context)
        ViewLayoutUtils.placeViewAt(view, foldedX, foldedY, size, size)
        view.requestLayout()

        notifyInsetsChanged(view)
    }

    fun unfold(view: View) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        isFoldedState = false

        view.getWindowVisibleDisplayFrame(windowFrame)
        extraHeight = getSuggestionStripHeight(view.resources).toFloat()
        val maxX = (windowFrame.right - windowFrame.left - Settings.getValues().mFloatingWidth).coerceAtLeast(0)
        val maxY = (windowFrame.bottom - windowFrame.top - extraHeight.toInt() - Settings.getValues().mFloatingHeight).coerceAtLeast(0)

        val targetX = if (hasMovedWhileFolded) foldedX.coerceIn(0, maxX) else preFoldX.coerceIn(0, maxX)
        val targetY = if (hasMovedWhileFolded) foldedY.coerceIn(0, maxY) else preFoldY.coerceIn(0, maxY)

        view.findViewById<View>(R.id.floating_folded_button)?.isGone = true

        if (Settings.getValues().mToolbarMode != ToolbarMode.HIDDEN) {
            view.findViewById<View>(R.id.strip_container)?.isVisible = true
        }
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.isVisible = true
        view.findViewById<View>(R.id.float_handle_container)?.isVisible = true

        view.findViewById<View>(R.id.main_keyboard_frame)?.let {
            val radiusPx = 16f * view.resources.displayMetrics.density
            it.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
            it.clipToOutline = true
        }

        ViewLayoutUtils.placeViewAt(view, targetX, targetY, Settings.getValues().mFloatingWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        savePosition(view.context, targetX, targetY)
        view.requestLayout()

        notifyInsetsChanged(view)
    }

    @JvmStatic
    fun disableFloating(view: View?) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        isFoldedState = false
        ViewLayoutUtils.updateLayoutGravityOf(view, android.view.Gravity.BOTTOM)
        view.findViewById<View>(R.id.main_keyboard_frame)?.let {
            it.layoutParams = it.layoutParams?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            ViewLayoutUtils.updateLayoutGravityOf(it, android.view.Gravity.BOTTOM)
            it.outlineProvider = null
            it.clipToOutline = false
        }
        val inputArea = view.rootView.findViewById<View?>(android.R.id.inputArea)
        if (inputArea != null) {
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, android.view.Gravity.BOTTOM)
        }
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) return // not floating
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "disable floating view")
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.leftMargin = 0
        lp.topMargin = 0

        view.findViewById<View>(R.id.float_handle_container)?.isGone = true
        view.findViewById<View>(R.id.floating_folded_button)?.isGone = true
        if (Settings.getValues().mToolbarMode != ToolbarMode.HIDDEN) {
            view.findViewById<View>(R.id.strip_container)?.isVisible = true
        }
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.isVisible = true
        view.requestLayout()

        notifyInsetsChanged(view)
    }

    @JvmStatic
    fun setHalfBoard(view: View?) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val displayWidth = view.context.resources.displayMetrics.widthPixels
        val targetWidth = (displayWidth * 0.50f).toInt().coerceIn(150, displayWidth)

        // Keep root InputView and inputArea MATCH_PARENT height anchored at bottom so
        // SoftInputWindow never collapses Half Board into the top half of the screen.
        ViewLayoutUtils.updateLayoutGravityOf(view, android.view.Gravity.BOTTOM)
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.leftMargin = 0
        lp.topMargin = 0
        lp.rightMargin = 0
        lp.bottomMargin = 0
        view.layoutParams = lp

        val inputArea = view.rootView.findViewById<View?>(android.R.id.inputArea)
        if (inputArea != null) {
            ViewLayoutUtils.updateLayoutHeightOf(inputArea, ViewGroup.LayoutParams.MATCH_PARENT)
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, android.view.Gravity.BOTTOM)
        }

        view.findViewById<View>(R.id.main_keyboard_frame)?.let { frame ->
            frame.layoutParams = frame.layoutParams?.apply {
                width = targetWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            ViewLayoutUtils.updateLayoutGravityOf(frame, android.view.Gravity.BOTTOM or android.view.Gravity.END)
            val radiusPx = 16f * view.resources.displayMetrics.density
            frame.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
            frame.clipToOutline = true
        }

        view.findViewById<View>(R.id.floating_folded_button)?.isGone = true
        view.findViewById<View>(R.id.float_handle_container)?.isGone = true
        if (Settings.getValues().mToolbarMode != ToolbarMode.HIDDEN) {
            view.findViewById<View>(R.id.strip_container)?.isVisible = true
        }
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.isVisible = true
        view.requestLayout()

        (view.context as? android.inputmethodservice.InputMethodService)?.window?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        notifyInsetsChanged(view)
    }

    @JvmStatic
    fun disableHalfBoard(view: View?) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        ViewLayoutUtils.updateLayoutGravityOf(view, android.view.Gravity.BOTTOM)
        view.findViewById<View>(R.id.main_keyboard_frame)?.let { frame ->
            frame.layoutParams = frame.layoutParams?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            ViewLayoutUtils.updateLayoutGravityOf(frame, android.view.Gravity.BOTTOM)
            frame.outlineProvider = null
            frame.clipToOutline = false
        }
        val inputArea = view.rootView.findViewById<View?>(android.R.id.inputArea)
        if (inputArea != null) {
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, android.view.Gravity.BOTTOM)
        }
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.leftMargin = 0
        lp.topMargin = 0
        lp.rightMargin = 0
        lp.bottomMargin = 0
        view.layoutParams = lp

        view.findViewById<View>(R.id.float_handle_container)?.isGone = true
        view.findViewById<View>(R.id.floating_folded_button)?.isGone = true
        if (Settings.getValues().mToolbarMode != ToolbarMode.HIDDEN) {
            view.findViewById<View>(R.id.strip_container)?.isVisible = true
        }
        view.findViewById<View>(R.id.keyboard_view_wrapper)?.isVisible = true
        view.requestLayout()

        notifyInsetsChanged(view)
    }

    private fun notifyInsetsChanged(view: View) {
        view.post {
            val ime = KeyboardSwitcher.getInstance()?.latinIME
            ime?.window?.window?.decorView?.requestLayout()
        }
    }

    @JvmStatic
    fun getFloatingHandleHeight(resources: Resources) = 0f

    @JvmStatic
    fun readPosition(context: Context, maxX: Int, maxY: Int): Pair<Int, Int> {
        val maxX = maxX.coerceAtLeast(0)
        val maxY = maxY.coerceAtLeast(0)
        val width = context.resources.displayMetrics.widthPixels
        val defaultX = ((width - Settings.getValues().mFloatingWidth) / 2).coerceIn(0, maxX)
        val defaultY = maxY.coerceAtLeast(0)
        val x = context.prefs().getInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, defaultX)
        val y = context.prefs().getInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, defaultY)
        if (x > maxX || y > maxY)
            savePosition(context, x.coerceIn(0, maxX), y.coerceIn(0, maxY))
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        val width = context.resources.displayMetrics.widthPixels
        context.prefs().edit {
            putInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, x)
            putInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, y)
        }
    }

    private fun getSuggestionStripHeight(resources: Resources) =
        if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
        else resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setupFoldedButton(rootView: View) {
        var startRawX = 0f
        var startRawY = 0f
        var startMarginX = 0
        var startMarginY = 0
        var isDragging = false
        val handler = Handler(Looper.getMainLooper())
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        val longPressRunnable = Runnable {
            isDragging = true
            hasMovedWhileFolded = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                } else {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } catch (_: Throwable) {}
        }

        setOnTouchListener { _, event ->
            val lp = rootView.layoutParams as ViewGroup.MarginLayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startMarginX = lp.leftMargin
                    startMarginY = lp.topMargin
                    isDragging = false
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        rootView.getWindowVisibleDisplayFrame(windowFrame)
                        val availableWidth = (windowFrame.right - windowFrame.left).coerceAtLeast(0)
                        val availableHeight = (windowFrame.bottom - windowFrame.top).coerceAtLeast(0)
                        val size = getFoldedSizePx(context)
                        val newX = (startMarginX + dx).toInt().coerceIn(0, (availableWidth - size).coerceAtLeast(0))
                        val newY = (startMarginY + dy).toInt().coerceIn(0, (availableHeight - size).coerceAtLeast(0))
                        lp.leftMargin = newX
                        lp.topMargin = newY
                        foldedX = newX
                        foldedY = newY
                        rootView.layoutParams = lp
                        notifyInsetsChanged(rootView)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        isDragging = false
                        notifyInsetsChanged(rootView)
                    } else {
                        val dx = event.rawX - startRawX
                        val dy = event.rawY - startRawY
                        if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                            unfold(rootView)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setDragListener(view: View) {
        var startX = 0f
        var startY = 0f
        val lp = view.layoutParams as ViewGroup.MarginLayoutParams
        var positionX = lp.leftMargin.toFloat()
        var positionY = lp.topMargin.toFloat()
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    positionX = lp.leftMargin.toFloat()
                    positionY = lp.topMargin.toFloat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    startX = event.rawX
                    startY = event.rawY
                    val sv = Settings.getValues()
                    val availableWidth = windowFrame.right - windowFrame.left
                    val availableHeight = windowFrame.bottom - windowFrame.top
                    positionX = (positionX + dx).coerceIn(0f, (availableWidth - sv.mFloatingWidth).toFloat().coerceAtLeast(0f))
                    positionY = (positionY + dy).coerceIn(0f, (availableHeight - extraHeight - sv.mFloatingHeight).coerceAtLeast(0f))
                    lp.leftMargin = positionX.toInt()
                    lp.topMargin = positionY.toInt()
                    savePosition(context, lp.leftMargin, lp.topMargin)
                    view.layoutParams = lp
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setResizeListener(lp: ViewGroup.MarginLayoutParams, rootInputView: View) {
        var downRawX = 0f
        var downRawY = 0f
        var initialWidth = 0
        var initialHeight = 0
        var isResizing = false

        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialWidth = if (lp.width > 0) lp.width else (if (rootInputView.width > 0) rootInputView.width else rootInputView.measuredWidth)
                    initialHeight = if (lp.height > 0) lp.height else (if (rootInputView.height > 0) rootInputView.height else rootInputView.measuredHeight)
                    isResizing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isResizing) return@setOnTouchListener false
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    val availableWidth = (windowFrame.right - windowFrame.left).coerceAtLeast(0)
                    val availableHeight = (windowFrame.bottom - windowFrame.top).coerceAtLeast(0)
                    val maxWidth = (availableWidth * 0.95f).toInt()
                    val maxHeight = (availableHeight * 0.85f).toInt()
                    val newWidth = (initialWidth + deltaX.toInt()).coerceIn(160, maxWidth)
                        .coerceAtMost(availableWidth - lp.leftMargin)
                    val newHeight = (initialHeight + deltaY.toInt()).coerceIn(120, maxHeight)
                        .coerceAtMost(availableHeight - lp.topMargin)
                    if (lp.width != newWidth || lp.height != newHeight) {
                        lp.width = newWidth
                        lp.height = newHeight
                        rootInputView.layoutParams = lp
                        rootInputView.requestLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isResizing && event.action == MotionEvent.ACTION_UP) {
                        val availableWidth = (windowFrame.right - windowFrame.left).coerceAtLeast(0)
                        val availableHeight = (windowFrame.bottom - windowFrame.top).coerceAtLeast(0)
                        val maxWidth = (availableWidth * 0.95f).toInt()
                        val maxHeight = (availableHeight * 0.85f).toInt()
                        val finalWidth = lp.width.coerceIn(160, maxWidth)
                        val finalHeight = (lp.height - extraHeight.toInt()).coerceIn(120, maxHeight)
                        setFloatingSize(context, finalWidth, finalHeight)
                        Settings.getInstance().loadSettings(context, Settings.getValues().mLocale, Settings.getValues().mInputAttributes)
                        KeyboardSwitcher.getInstance()?.reloadKeyboard()
                        notifyInsetsChanged(rootInputView)
                    }
                    isResizing = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun setupChevronDrag(chevron: View?, rootView: View?) {
        if (chevron == null || rootView == null) return
        var startRawX = 0f
        var startRawY = 0f
        var startMarginX = 0
        var startMarginY = 0
        var isDragging = false
        val handler = Handler(Looper.getMainLooper())
        val touchSlop = ViewConfiguration.get(chevron.context).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        val longPressRunnable = Runnable {
            isDragging = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    chevron.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                } else {
                    chevron.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } catch (_: Throwable) {}
        }

        chevron.setOnTouchListener { _, event ->
            val isFloating = KeyboardSwitcher.getInstance()?.isFloatingModalActive == true || Settings.getValues().mIsFloatingKeyboard
            if (!isFloating || isFoldedState) {
                return@setOnTouchListener false
            }
            val lp = rootView.layoutParams as? ViewGroup.MarginLayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startMarginX = lp.leftMargin
                    startMarginY = lp.topMargin
                    isDragging = false
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        rootView.getWindowVisibleDisplayFrame(windowFrame)
                        val sv = Settings.getValues()
                        val availableWidth = (windowFrame.right - windowFrame.left).coerceAtLeast(0)
                        val availableHeight = (windowFrame.bottom - windowFrame.top).coerceAtLeast(0)
                        val totalHeight = sv.mFloatingHeight + extraHeight.toInt()
                        val newX = (startMarginX + dx).toInt().coerceIn(0, (availableWidth - sv.mFloatingWidth).coerceAtLeast(0))
                        val newY = (startMarginY + dy).toInt().coerceIn(0, (availableHeight - totalHeight).coerceAtLeast(0))
                        lp.leftMargin = newX
                        lp.topMargin = newY
                        savePosition(rootView.context, newX, newY)
                        rootView.layoutParams = lp
                        notifyInsetsChanged(rootView)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        isDragging = false
                        notifyInsetsChanged(rootView)
                    } else {
                        val dx = event.rawX - startRawX
                        val dy = event.rawY - startRawY
                        if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                            chevron.performClick()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }
}
