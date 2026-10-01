// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Custom lightweight view rendering a live 30s circular countdown progress ring.
 */
class TotpCircleProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progressFraction = 1f
    private var secondsText = "30"
    private var showText = true

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * context.resources.displayMetrics.density
        color = Color.parseColor("#44888888")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#4CAF50")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * context.resources.displayMetrics.density
        color = Color.WHITE
        isFakeBoldText = true
    }

    private val oval = RectF()

    fun setProgress(fraction: Float, remainingSeconds: Int) {
        this.progressFraction = fraction.coerceIn(0f, 1f)
        this.secondsText = remainingSeconds.toString()
        if (remainingSeconds <= 5) {
            progressPaint.color = Color.parseColor("#F44336") // Red warning
        } else {
            progressPaint.color = Color.parseColor("#4CAF50") // Green active
        }
        invalidate()
    }

    fun setColors(accentColor: Int, trackColor: Int, textColor: Int) {
        progressPaint.color = accentColor
        trackPaint.color = trackColor
        textPaint.color = textColor
        invalidate()
    }

    fun setShowText(show: Boolean) {
        showText = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = progressPaint.strokeWidth
        val diameter = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) - stroke
        if (diameter <= 0) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = diameter / 2f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Draw background track ring
        canvas.drawOval(oval, trackPaint)

        // Draw sweep arc (starts from top at -90 degrees)
        val sweepAngle = progressFraction * 360f
        canvas.drawArc(oval, -90f, sweepAngle, false, progressPaint)

        // Draw centered seconds text if enabled
        if (showText && secondsText.isNotEmpty()) {
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(secondsText, cx, textY, textPaint)
        }
    }
}
