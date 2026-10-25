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
        extraHeight = getSuggestionStripHeight(view.resources) + getFloatingHandleHeight(view.resources)

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
            KeyboardSwitcher.getInstance()?.setFloatingKeyboardEnabled(false)
        }

        // 2. Minimize: fold into app icon bubble
        view.findViewById<View>(R.id.minimize_floating_handle)?.setOnClickListener {
            fold(view)
        }

        // 3. Resize (drag): drag to resize width/height, long-press to move
        view.findViewById<ImageView>(R.id.resize_handle)?.setResizeListener(lp, view)

        // Handle bar drag moves keyboard
        view.findViewById<View>(R.id.float_handle_container)?.setDragListener(view)

        // Pre-bind folded button
        view.findViewById<View>(R.id.floating_folded_button)?.setupFoldedButton(view)
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
        extraHeight = getSuggestionStripHeight(view.resources) + getFloatingHandleHeight(view.resources)
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

        ViewLayoutUtils.placeViewAt(view, targetX, targetY, Settings.getValues().mFloatingWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        savePosition(view.context, targetX, targetY)
        view.requestLayout()

        notifyInsetsChanged(view)
    }

    @JvmStatic
    fun disableFloating(view: View?) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        isFoldedState = false
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

    private fun notifyInsetsChanged(view: View) {
        view.post {
            val ime = KeyboardSwitcher.getInstance()?.latinIME
            ime?.window?.window?.decorView?.requestLayout()
        }
    }

    @JvmStatic
    fun getFloatingHandleHeight(resources: Resources) = resources.getDimension(R.dimen.config_floating_handle_height)

    @JvmStatic
    fun readPosition(context: Context, maxX: Int, maxY: Int): Pair<Int, Int> {
        val maxX = maxX.coerceAtLeast(0)
        val maxY = maxY.coerceAtLeast(0)
        val width = context.resources.displayMetrics.widthPixels
        val x = context.prefs().getInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, width / 2)
        val y = context.prefs().getInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, context.resources.displayMetrics.heightPixels / 2)
        if (x > maxX || y > maxY)
            savePosition(context, maxX, maxY)
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
        var startX = 0f
        var startY = 0f
        var isMoveMode = false
        var positionX = lp.leftMargin.toFloat()
        var positionY = lp.topMargin.toFloat()
        val handler = Handler(Looper.getMainLooper())
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val scale = 3 / context.resources.displayMetrics.density

        val moveRunnable = Runnable {
            isMoveMode = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                } else {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } catch (_: Throwable) {}
        }

        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    positionX = lp.leftMargin.toFloat()
                    positionY = lp.topMargin.toFloat()
                    isMoveMode = false
                    handler.postDelayed(moveRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    startX = event.rawX
                    startY = event.rawY
                    if (isMoveMode) {
                        val sv = Settings.getValues()
                        val availableWidth = windowFrame.right - windowFrame.left
                        val availableHeight = windowFrame.bottom - windowFrame.top
                        positionX = (positionX + dx).coerceIn(0f, (availableWidth - sv.mFloatingWidth).toFloat().coerceAtLeast(0f))
                        positionY = (positionY + dy).coerceIn(0f, (availableHeight - extraHeight - sv.mFloatingHeight).coerceAtLeast(0f))
                        lp.leftMargin = positionX.toInt()
                        lp.topMargin = positionY.toInt()
                        savePosition(context, lp.leftMargin, lp.topMargin)
                        rootInputView.layoutParams = lp
                    } else {
                        val availableWidth = windowFrame.right - windowFrame.left
                        val availableHeight = windowFrame.bottom - windowFrame.top
                        val maxWidth = (availableWidth * 0.9f).toInt()
                        val maxHeight = (availableHeight * 0.9f).toInt()
                        val newWidth = (Settings.getValues().mFloatingWidth + dx / scale).toInt().coerceIn(150, maxWidth)
                            .coerceAtMost(availableWidth - lp.leftMargin)
                        val newHeight = (Settings.getValues().mFloatingHeight + dy / scale).toInt().coerceIn(100, maxHeight)
                            .coerceAtMost(availableHeight - extraHeight.toInt() - lp.topMargin)
                        setFloatingSize(context, newWidth, newHeight)
                        KeyboardSwitcher.getInstance()?.reloadKeyboard()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(moveRunnable)
                    isMoveMode = false
                    true
                }
                else -> false
            }
        }
    }
}
