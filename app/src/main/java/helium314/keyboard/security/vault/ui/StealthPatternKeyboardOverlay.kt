// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.security.VaultSessionManager
import kotlin.math.hypot

/**
 * StealthPatternKeyboardOverlay intercepts touch gestures over the normal QWERTY alphabet
 * keyboard to authenticate the Security Vault pattern with zero visual cues, lines, or modals.
 *
 * 3x3 Pattern Matrix mapped to 9 tactile anchor keys:
 * Row 1: [Q] [W] (E)0 [R] (T)1 [Y] (U)2 [I] [O] [P]
 * Row 2:  [A] [S] (D)3 [F] (G)4 [H] (J)5 [K] [L]
 * Row 3:    [Z] [X] (C)6 [V] (B)7 [N] (M)8 [⌫]
 */
class StealthPatternKeyboardOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pattern = mutableListOf<Int>()
    private var onUnlockSuccessCallback: (() -> Unit)? = null
    private var onCancelCallback: (() -> Unit)? = null
    private var isActive = false
    private var failureCount = 0

    // Coordinates of the 9 anchor keys (0..8)
    private val anchorPoints = Array(9) { FloatArray(2) }
    private var hasCalculatedAnchors = false

    private val catchmentRadiusPx: Float
        get() = 28f * resources.displayMetrics.density

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        setBackgroundColor(0) // completely transparent
    }

    /**
     * Activates stealth interception mode over the visible QWERTY keyboard.
     */
    fun startStealthUnlock(onSuccess: () -> Unit, onCancel: (() -> Unit)? = null) {
        onUnlockSuccessCallback = onSuccess
        onCancelCallback = onCancel
        isActive = true
        pattern.clear()
        hasCalculatedAnchors = false
        visibility = VISIBLE
        bringToFront()
        LogCatcher.log('I', TAG, "Stealth pattern unlock challenge active (zero visual tell)")
    }

    fun stopStealthUnlock() {
        isActive = false
        pattern.clear()
        visibility = GONE
        onUnlockSuccessCallback = null
        onCancelCallback = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateAnchorPoints()
    }

    private fun calculateAnchorPoints() {
        val keyboard: Keyboard? = KeyboardSwitcher.getInstance().keyboard
        if (keyboard != null && !keyboard.sortedKeys.isNullOrEmpty()) {
            val anchorKeyCodes = intArrayOf(
                'e'.code, // 0
                't'.code, // 1
                'u'.code, // 2
                'd'.code, // 3
                'g'.code, // 4
                'j'.code, // 5
                'c'.code, // 6
                'b'.code, // 7
                'm'.code  // 8
            )

            var foundCount = 0
            for (node in 0..8) {
                val targetCode = anchorKeyCodes[node]
                val key = keyboard.sortedKeys.firstOrNull {
                    it.code == targetCode || it.code == targetCode.toChar().uppercaseChar().code
                }
                if (key != null) {
                    anchorPoints[node][0] = key.x + (key.width / 2f)
                    anchorPoints[node][1] = key.y + (key.height / 2f)
                    foundCount++
                }
            }

            if (foundCount == 9) {
                hasCalculatedAnchors = true
                return
            }
        }

        // Proportional geometric fallback for standard QWERTY aspect ratio
        val w = if (width > 0) width.toFloat() else 1080f
        val h = if (height > 0) height.toFloat() else 600f

        val row1Y = h * 0.16f
        val row2Y = h * 0.44f
        val row3Y = h * 0.72f

        // Row 1: E, T, U
        anchorPoints[0][0] = w * 0.25f; anchorPoints[0][1] = row1Y
        anchorPoints[1][0] = w * 0.45f; anchorPoints[1][1] = row1Y
        anchorPoints[2][0] = w * 0.65f; anchorPoints[2][1] = row1Y

        // Row 2: D, G, J
        anchorPoints[3][0] = w * 0.27f; anchorPoints[3][1] = row2Y
        anchorPoints[4][0] = w * 0.47f; anchorPoints[4][1] = row2Y
        anchorPoints[5][0] = w * 0.67f; anchorPoints[5][1] = row2Y

        // Row 3: C, B, M
        anchorPoints[6][0] = w * 0.32f; anchorPoints[6][1] = row3Y
        anchorPoints[7][0] = w * 0.52f; anchorPoints[7][1] = row3Y
        anchorPoints[8][0] = w * 0.72f; anchorPoints[8][1] = row3Y

        hasCalculatedAnchors = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isActive) return false

        if (!hasCalculatedAnchors) {
            calculateAnchorPoints()
        }

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pattern.clear()
                checkNodeProximity(x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                checkNodeProximity(x, y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pattern.size >= 4) {
                    verifyPattern()
                } else if (pattern.isEmpty()) {
                    // Tap with no pattern drawn - cancel stealth challenge
                    val cb = onCancelCallback
                    stopStealthUnlock()
                    cb?.invoke()
                } else {
                    // Pattern too short -> mute vibrate
                    triggerFailureFeedback()
                    pattern.clear()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val cb = onCancelCallback
                stopStealthUnlock()
                cb?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun checkNodeProximity(x: Float, y: Float) {
        val radius = catchmentRadiusPx
        for (node in 0..8) {
            val nx = anchorPoints[node][0]
            val ny = anchorPoints[node][1]
            val dist = hypot(x - nx, y - ny)
            if (dist <= radius) {
                if (!pattern.contains(node)) {
                    pattern.add(node)
                    // Dispatch tactile clock tick on node entry
                    dispatchTactileTick()
                }
                break
            }
        }
    }

    private fun dispatchTactileTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                performHapticFeedback(
                    HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            } else {
                performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }
        } catch (_: Throwable) {
            // Fallback subtle 10ms tick
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(10)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun verifyPattern() {
        val isMatch = VaultSessionManager.verifySecurityPattern(context, pattern)
        if (isMatch) {
            LogCatcher.log('I', TAG, "Stealth pattern authentication successful")
            VaultSessionManager.startSecuritySession()
            val cb = onUnlockSuccessCallback
            stopStealthUnlock()
            failureCount = 0
            cb?.invoke()
        } else {
            failureCount++
            LogCatcher.log('W', TAG, "Stealth pattern verification failed (attempt $failureCount)")
            triggerFailureFeedback()
            pattern.clear()

            if (failureCount >= 3) {
                // Abort stealth session after 3 consecutive failures
                val cb = onCancelCallback
                stopStealthUnlock()
                failureCount = 0
                cb?.invoke()
            }
        }
    }

    private fun triggerFailureFeedback() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(60, 100))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(60)
            }
        } catch (_: Throwable) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /**
     * Explicit zero-paint: zero visual lines, zero glowing trails, zero modal shift.
     */
    override fun onDraw(canvas: Canvas) {
        // Intentionally completely blank
    }

    companion object {
        private const val TAG = "StealthPatternOverlay"
    }
}
