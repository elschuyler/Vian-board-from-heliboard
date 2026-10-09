// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.handwriting

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import kotlin.math.abs

/**
 * Interactive touch canvas for ink drawing.
 * Renders smooth quadratic Bézier curves with anti-aliasing and rounded caps.
 * Provides touch isolation, gesture detection, and animated fade-out.
 */
class HandwritingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnStrokeListener {
        fun onStrokeStarted()
        fun onStrokeFinished(rawPoints: List<HandwritingRecognizer.Point>, isGesture: HandwritingRecognizer.Gesture)
    }

    var strokeListener: OnStrokeListener? = null

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 7.0f
        color = Color.parseColor("#4FC3F7") // Default vibrant ink cyan
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#44888888")
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }

    private class StrokePath(val path: Path, val points: List<HandwritingRecognizer.Point>)

    private val completedStrokes = mutableListOf<StrokePath>()
    private var currentPath = Path()
    private val currentPoints = mutableListOf<HandwritingRecognizer.Point>()

    private var lastX = 0f
    private var lastY = 0f
    private var inkAlpha = 255

    fun setInkColor(color: Int) {
        inkPaint.color = color
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentPath = Path()
                currentPath.moveTo(x, y)
                currentPoints.clear()
                currentPoints.add(HandwritingRecognizer.Point(x, y))
                lastX = x
                lastY = y
                inkAlpha = 255
                strokeListener?.onStrokeStarted()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(x - lastX)
                val dy = abs(y - lastY)
                if (dx >= 3f || dy >= 3f) {
                    currentPath.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
                    currentPoints.add(HandwritingRecognizer.Point(x, y))
                    lastX = x
                    lastY = y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(x, y)
                currentPoints.add(HandwritingRecognizer.Point(x, y))
                val strokeSnapshot = ArrayList(currentPoints)
                completedStrokes.add(StrokePath(currentPath, strokeSnapshot))

                val gesture = HandwritingRecognizer.detectGesture(strokeSnapshot)
                strokeListener?.onStrokeFinished(strokeSnapshot, gesture)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                currentPoints.clear()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw subtle horizontal baseline guide (lower 35%)
        val baselineY = h * 0.68f
        canvas.drawLine(w * 0.08f, baselineY, w * 0.92f, baselineY, guidePaint)

        // 2. Draw completed strokes
        inkPaint.alpha = inkAlpha
        for (stroke in completedStrokes) {
            canvas.drawPath(stroke.path, inkPaint)
        }

        // 3. Draw in-flight active stroke
        if (!currentPoints.isEmpty()) {
            canvas.drawPath(currentPath, inkPaint)
        }
    }

    /**
     * Retrieves all raw strokes currently on the canvas.
     */
    fun getAllStrokes(): List<List<HandwritingRecognizer.Point>> {
        return completedStrokes.map { it.points }
    }

    /**
     * Clears all strokes with optional fade animation.
     */
    fun clearCanvas(animated: Boolean = true) {
        if (completedStrokes.isEmpty() && currentPoints.isEmpty()) return

        if (!animated) {
            completedStrokes.clear()
            currentPoints.clear()
            currentPath.reset()
            inkAlpha = 255
            invalidate()
            return
        }

        val fadeAnim = ValueAnimator.ofInt(255, 0).apply {
            duration = 140
            interpolator = AccelerateInterpolator()
            addUpdateListener { anim ->
                inkAlpha = anim.animatedValue as Int
                invalidate()
            }
        }
        fadeAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                completedStrokes.clear()
                currentPoints.clear()
                currentPath.reset()
                inkAlpha = 255
                invalidate()
            }
        })
        fadeAnim.start()
    }
}
