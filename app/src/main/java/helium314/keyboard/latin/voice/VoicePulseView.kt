// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class VoicePulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PulseState {
        IDLE,
        LISTENING,
        PAUSED,
        ERROR
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    var pulseState: PulseState = PulseState.IDLE
        set(value) {
            if (field != value) {
                field = value
                updateAnimationState()
                invalidate()
            }
        }

    private var targetRms: Float = 0f
    private var smoothedRms: Float = 0f
    private var wavePhase: Float = 0f
    private var waveAnimator: ValueAnimator? = null

    // 5 sound wave bars
    private val numBars = 5
    private val barWeights = floatArrayOf(0.45f, 0.80f, 1.0f, 0.75f, 0.40f)
    private val barRect = RectF()

    // Color definitions
    private val colorListeningActive = Color.parseColor("#34A853") // Active vocal green
    private val colorListeningAmbient = Color.parseColor("#4285F4") // Ambient blue
    private val colorPaused = Color.parseColor("#FBBC05")          // Amber / Yellow
    private val colorError = Color.parseColor("#EA4335")           // Red
    private val colorIdle = Color.parseColor("#9AA0A6")            // Neutral gray

    init {
        updateAnimationState()
    }

    fun setRms(rms: Float) {
        targetRms = rms.coerceIn(0f, 1f)
        if (pulseState == PulseState.LISTENING) {
            invalidate()
        }
    }

    private fun updateAnimationState() {
        waveAnimator?.cancel()
        waveAnimator = null

        if (pulseState == PulseState.LISTENING) {
            waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
                duration = 1200L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    wavePhase = animator.animatedValue as Float
                    // Smoothly approach target RMS
                    smoothedRms = smoothedRms * 0.7f + targetRms * 0.3f
                    invalidate()
                }
                start()
            }
        } else {
            smoothedRms = 0f
            targetRms = 0f
            wavePhase = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val (barColor, isListening) = when (pulseState) {
            PulseState.LISTENING -> {
                val c = if (smoothedRms > 0.12f) colorListeningActive else colorListeningAmbient
                c to true
            }
            PulseState.PAUSED -> colorPaused to false
            PulseState.ERROR -> colorError to false
            PulseState.IDLE -> colorIdle to false
        }

        barPaint.color = barColor

        val density = resources.displayMetrics.density
        val barWidth = 3.5f * density
        val barSpacing = 3.0f * density
        val totalBarsWidth = numBars * barWidth + (numBars - 1) * barSpacing
        val startX = (w - totalBarsWidth) / 2f
        val centerY = h / 2f
        val minBarHeight = barWidth // Pill dot when silent
        val maxAdditionalHeight = (h * 0.78f) - minBarHeight

        for (i in 0 until numBars) {
            val barX = startX + i * (barWidth + barSpacing)
            val weight = barWeights[i]

            val currentHeight = if (isListening) {
                // Combine real-time RMS with subtle ambient phase ripple
                val ambientRipple = (sin(wavePhase + i * 1.1) * 0.15f + 0.15f).toFloat()
                val activeHeightFraction = (smoothedRms * weight + ambientRipple * (1f - smoothedRms)).coerceIn(0f, 1f)
                minBarHeight + maxAdditionalHeight * activeHeightFraction
            } else if (pulseState == PulseState.PAUSED) {
                minBarHeight + maxAdditionalHeight * 0.25f * weight
            } else {
                minBarHeight
            }

            val top = centerY - currentHeight / 2f
            val bottom = centerY + currentHeight / 2f
            barRect.set(barX, top, barX + barWidth, bottom)
            val cornerRadius = barWidth / 2f
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        waveAnimator?.cancel()
        waveAnimator = null
    }

    fun release() {
        waveAnimator?.cancel()
        waveAnimator = null
        pulseState = PulseState.IDLE
    }
}
