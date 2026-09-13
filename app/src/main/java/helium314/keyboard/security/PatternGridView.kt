// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.hypot
import kotlin.math.min

/**
 * An interactive 3x3 tactile pattern unlock grid view.
 * Designed to fit within keyboard height and adapt smoothly to the keyboard's active color scheme.
 */
class PatternGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onPatternCompleted: ((List<Int>) -> Unit)? = null
    var onPatternStarted: (() -> Unit)? = null

    private val selectedNodes = mutableListOf<Int>()
    private var isErrorState = false
    private var isDragging = false
    private var currentTouchX = 0f
    private var currentTouchY = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val nodeNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val nodeSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val nodeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()

    // Dynamic grid layout coordinates for 9 nodes (0..8)
    private val nodePositions = Array(9) { FloatArray(2) }
    private var hitRadius = 0f
    private var dotRadius = 0f
    private var selectedDotRadius = 0f
    private var haloRadius = 0f

    init {
        isClickable = true
        isFocusable = true
    }

    fun clearPattern() {
        selectedNodes.clear()
        isErrorState = false
        isDragging = false
        invalidate()
    }

    fun setErrorState() {
        isErrorState = true
        isDragging = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availableSize = min(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom).toFloat()
        val cellSize = availableSize / 3f
        val startX = (w - availableSize) / 2f + cellSize / 2f
        val startY = (h - availableSize) / 2f + cellSize / 2f

        dotRadius = cellSize * 0.12f
        selectedDotRadius = cellSize * 0.16f
        haloRadius = cellSize * 0.32f
        hitRadius = cellSize * 0.42f
        linePaint.strokeWidth = cellSize * 0.09f

        for (row in 0..2) {
            for (col in 0..2) {
                val index = row * 3 + col
                nodePositions[index][0] = startX + col * cellSize
                nodePositions[index][1] = startY + row * cellSize
            }
        }
    }

    private fun resolveColors(): Triple<Int, Int, Int> {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        val baseNormal = colors?.get(ColorType.KEY_TEXT) ?: Color.GRAY
        val baseAccent = colors?.get(ColorType.GESTURE_TRAIL) ?: colors?.get(ColorType.ACTION_KEY_BACKGROUND) ?: Color.CYAN
        val baseError = Color.parseColor("#E53935")

        val normalColor = ColorUtils.setAlphaComponent(baseNormal, 140)
        val activeColor = if (isErrorState) baseError else baseAccent
        val haloColor = ColorUtils.setAlphaComponent(activeColor, 50)
        return Triple(normalColor, activeColor, haloColor)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (normalColor, activeColor, haloColor) = resolveColors()

        linePaint.color = activeColor
        nodeNormalPaint.color = normalColor
        nodeSelectedPaint.color = activeColor
        nodeHaloPaint.color = haloColor

        // 1. Draw connecting lines between selected nodes
        if (selectedNodes.isNotEmpty()) {
            path.reset()
            val firstNode = selectedNodes[0]
            path.moveTo(nodePositions[firstNode][0], nodePositions[firstNode][1])
            for (i in 1 until selectedNodes.size) {
                val node = selectedNodes[i]
                path.lineTo(nodePositions[node][0], nodePositions[node][1])
            }
            if (isDragging && !isErrorState) {
                path.lineTo(currentTouchX, currentTouchY)
            }
            canvas.drawPath(path, linePaint)
        }

        // 2. Draw all 9 dots
        for (i in 0..8) {
            val cx = nodePositions[i][0]
            val cy = nodePositions[i][1]
            if (selectedNodes.contains(i)) {
                canvas.drawCircle(cx, cy, haloRadius, nodeHaloPaint)
                canvas.drawCircle(cx, cy, selectedDotRadius, nodeSelectedPaint)
            } else {
                canvas.drawCircle(cx, cy, dotRadius, nodeNormalPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || isErrorState) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selectedNodes.clear()
                isErrorState = false
                isDragging = true
                onPatternStarted?.invoke()
                checkNodeTouch(event.x, event.y)
                currentTouchX = event.x
                currentTouchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentTouchX = event.x
                currentTouchY = event.y
                checkNodeTouch(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDragging = false
                invalidate()
                if (selectedNodes.isNotEmpty()) {
                    onPatternCompleted?.invoke(selectedNodes.toList())
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun checkNodeTouch(x: Float, y: Float) {
        for (i in 0..8) {
            if (!selectedNodes.contains(i)) {
                val dist = hypot((x - nodePositions[i][0]).toDouble(), (y - nodePositions[i][1]).toDouble()).toFloat()
                if (dist <= hitRadius) {
                    selectedNodes.add(i)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    break
                }
            }
        }
    }
}
