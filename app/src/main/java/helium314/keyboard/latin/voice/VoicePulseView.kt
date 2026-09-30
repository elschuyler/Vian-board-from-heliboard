// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dynamic music-system sound wave equalizer view that bounces in real-time with voice input.
 * Implements multi-band equalizer physics: rapid attack, gravity decay, frequency-weighted
 * amplitudes, and organic ambient ripple.
 */
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

    // Audio input telemetry
    private var inputRms: Float = 0f
    private var smoothedRms: Float = 0f

    // Physics & Equalizer bar simulation
    private val numBars = 21
    private val barHeights = FloatArray(numBars)
    private val targetHeights = FloatArray(numBars)
    private val barDecay = FloatArray(numBars)
    private val barWeights = FloatArray(numBars)
    private val barPhaseOffsets = FloatArray(numBars)

    private val barRect = RectF()
    private var animator: ValueAnimator? = null
    private var animTime: Float = 0f
    private var lastFrameTimeNanos: Long = 0L

    // Color definitions
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorBlue = Color.parseColor("#2979FF")
    private val colorPurple = Color.parseColor("#7C4DFF")
    private val colorPaused = Color.parseColor("#FFB300")
    private val colorError = Color.parseColor("#FF5252")
    private val colorIdle = Color.parseColor("#78909C")

    private var cachedGradient: LinearGradient? = null
    private var lastGradientHeight: Float = 0f

    init {
        // Pre-compute frequency response curve: center-heavy with natural acoustic roll-off
        val random = Random(42)
        for (i in 0 until numBars) {
            val normalized = i.toFloat() / (numBars - 1) // 0.0 to 1.0
            // Bell curve with natural spread
            val bell = sin(normalized * Math.PI).toFloat()
            // Bass, speech vowels (mids), and fricatives (highs) variation
            val jitter = 0.85f + random.nextFloat() * 0.30f
            barWeights[i] = (0.35f + 0.65f * bell) * jitter
            barPhaseOffsets[i] = random.nextFloat() * 6.28f
            barDecay[i] = 0.70f + random.nextFloat() * 0.15f
        }
        updateAnimationState()
    }

    fun setRms(rms: Float) {
        inputRms = rms.coerceIn(0f, 1f)
        if (pulseState == PulseState.LISTENING) {
            invalidate()
        }
    }

    private fun updateAnimationState() {
        animator?.cancel()
        animator = null

        if (pulseState == PulseState.LISTENING) {
            lastFrameTimeNanos = System.nanoTime()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val now = System.nanoTime()
                    val deltaSec = if (lastFrameTimeNanos > 0L) {
                        ((now - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
                    } else 0.016f
                    lastFrameTimeNanos = now

                    animTime += deltaSec * 3.5f
                    // Smooth RMS attack
                    smoothedRms = smoothedRms * 0.65f + inputRms * 0.35f
                    updateBarPhysics(deltaSec)
                    invalidate()
                }
                start()
            }
        } else {
            smoothedRms = 0f
            inputRms = 0f
            lastFrameTimeNanos = 0L
            // Reset bar heights smoothly
            for (i in 0 until numBars) {
                targetHeights[i] = 0f
                barHeights[i] = 0f
            }
        }
    }

    private fun updateBarPhysics(deltaSec: Float) {
        for (i in 0 until numBars) {
            // Ambient micro-bounce: traveling music wave
            val ambientWave = sin(animTime * 2.2f + barPhaseOffsets[i])
            val ambientBounce = ((ambientWave + 1.0f) * 0.5f * 0.12f).toFloat()

            // Dynamic bounce when audio detected
            val activeEnergy = smoothedRms * barWeights[i]
            // Calculate target fraction for this bar
            val targetFraction = (activeEnergy * 1.35f + ambientBounce).coerceIn(0.04f, 1.0f)

            if (targetFraction > barHeights[i]) {
                // Instant punch / attack
                barHeights[i] = barHeights[i] * 0.4f + targetFraction * 0.6f
            } else {
                // Smooth gravity decay
                barHeights[i] = barHeights[i] * (1f - deltaSec * 8f) + targetFraction * (deltaSec * 8f)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedGradient = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val availableWidth = w - paddingLeft - paddingRight
        if (availableWidth <= 0f) return

        val barWidth = (3.2f * density).coerceAtLeast(3f)
        val barSpacing = (2.2f * density).coerceAtLeast(2f)
        val totalBarsWidth = numBars * barWidth + (numBars - 1) * barSpacing

        val startX = paddingLeft + (availableWidth - totalBarsWidth) / 2f
        val centerY = h / 2f
        val minBarHeight = barWidth // Pill dot when completely silent
        val maxAdditionalHeight = (h * 0.86f) - minBarHeight

        // Apply shader gradient for listening state
        if (pulseState == PulseState.LISTENING) {
            if (cachedGradient == null || lastGradientHeight != h) {
                cachedGradient = LinearGradient(
                    0f, centerY - maxAdditionalHeight / 2f,
                    0f, centerY + maxAdditionalHeight / 2f,
                    intArrayOf(colorCyan, colorBlue, colorPurple),
                    floatArrayOf(0.0f, 0.55f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                lastGradientHeight = h
            }
            barPaint.shader = cachedGradient
        } else {
            barPaint.shader = null
            barPaint.color = when (pulseState) {
                PulseState.PAUSED -> colorPaused
                PulseState.ERROR -> colorError
                PulseState.IDLE -> colorIdle
                else -> colorIdle
            }
        }

        val cornerRadius = barWidth / 2f

        for (i in 0 until numBars) {
            val barX = startX + i * (barWidth + barSpacing)
            val heightFraction = when (pulseState) {
                PulseState.LISTENING -> barHeights[i].coerceIn(0.04f, 1.0f)
                PulseState.PAUSED -> 0.22f * barWeights[i]
                PulseState.ERROR -> 0.40f * (0.8f + 0.2f * sin(i.toFloat()))
                PulseState.IDLE -> 0.05f
            }

            val currentHeight = minBarHeight + maxAdditionalHeight * heightFraction
            val top = centerY - currentHeight / 2f
            val bottom = centerY + currentHeight / 2f

            barRect.set(barX, top, barX + barWidth, bottom)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    fun release() {
        animator?.cancel()
        animator = null
        pulseState = PulseState.IDLE
        for (i in 0 until numBars) {
            barHeights[i] = 0f
            targetHeights[i] = 0f
        }
    }
}
