package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.max

/**
 * Hairline audio meter down the edge of the picture.
 *
 * Deliberately thin: on a camera the picture is what you are looking at, and a
 * meter only needs to answer "is there audio, and is it clipping". The scale
 * is dB rather than linear, because linear spends most of its length on levels
 * nobody ever uses.
 *
 * Peak hold falls back slowly so a transient clip stays visible long enough to
 * notice, which is the whole reason anybody glances at a meter mid-take.
 *
 * This is the meter from the old build, unchanged in its arithmetic. What has
 * changed is where its samples come from: one reader on the microphone now,
 * rather than a meter and an encoder taking turns.
 */
class VuMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55000000")
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
    }

    /**
     * v194, ONE METER, SPLIT FOR STEREO (Marko, 14.11.2026; MANTRA_MANIFEST modules/vu-meter-mono-stereo.md): a mono
     * source is one bar; a stereo source is the same meter in the same place and size, split down the middle by a
     * hairline, left level on the left and right on the right, each with its own peak hold, and small L and R at the
     * top. The count comes from the recorder actually opened ([setLevels] gets one level per channel), never a setting.
     */
    var channels = 1
        private set

    /** 0..1, already dB-scaled, per channel. */
    private val level = FloatArray(2)
    private val peak = FloatArray(2)
    private val peakHoldFrames = IntArray(2)

    private val hairlinePaint = Paint().apply { color = Color.argb(200, 255, 255, 255); strokeWidth = 1f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /** True while nothing is reading the microphone at all. */
    var dead: Boolean = false
        set(value) { if (field != value) { field = value; postInvalidateOnAnimation() } }

    /** @param rms linear RMS, 0..1 (mono) */
    fun setLevel(rms: Float) = setLevels(floatArrayOf(rms))

    /** @param levels linear RMS 0..1, one per channel: one is mono, two is stereo (left, right) */
    fun setLevels(levels: FloatArray) {
        val n = levels.size.coerceIn(1, 2)
        if (n != channels) {
            channels = n
            reset()
        }
        for (c in 0 until n) step(c, levels[c])
        postInvalidateOnAnimation()
    }

    private fun step(c: Int, rms: Float) {
        val db = if (rms <= 0.0000001f) MIN_DB else (20f * log10(rms)).coerceAtLeast(MIN_DB)
        val normalised = ((db - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)

        // Fast attack so peaks register, slow release so the meter is readable.
        level[c] = if (normalised > level[c]) normalised else level[c] * 0.82f + normalised * 0.18f

        if (normalised >= peak[c]) {
            peak[c] = normalised
            peakHoldFrames[c] = PEAK_HOLD_FRAMES
        } else if (peakHoldFrames[c] > 0) {
            peakHoldFrames[c]--
        } else {
            peak[c] = max(level[c], peak[c] - 0.012f)
        }
    }

    fun reset() {
        level.fill(0f)
        peak.fill(0f)
        peakHoldFrames.fill(0)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // the L and R labels sit in a strip at the top, in mono too, so the bars never change length
        val labels = LABEL_DP * resources.displayMetrics.density
        val bars = h - labels

        // −6 and −18 marked, because those are the two numbers anybody is
        // actually looking for: headroom left, and whether it is up at all.
        for (db in intArrayOf(-6, -18)) {
            val y = labels + bars * (1f - ((db - MIN_DB) / -MIN_DB))
            canvas.drawLine(0f, y, w, y, scalePaint)
        }

        val stereo = channels == 2
        val half = if (stereo) w / 2f else w
        if (stereo) {
            labelPaint.textSize = labels * 0.8f
            canvas.drawText("L", w / 4f, labels * 0.82f, labelPaint)
            canvas.drawText("R", w * 3f / 4f, labels * 0.82f, labelPaint)
        }

        if (!dead) {
            for (c in 0 until channels) {
                val x0 = if (c == 0) 0f else half
                val x1 = if (stereo && c == 0) half else w
                val top = labels + bars * (1f - level[c])
                levelPaint.color = when {
                    level[c] > 0.93f -> Color.parseColor("#FF3B30")
                    level[c] > 0.80f -> Color.parseColor("#FFB300")
                    else -> Color.parseColor("#00E0A0")
                }
                canvas.drawRect(x0, top, x1, h, levelPaint)
                if (peak[c] > 0.01f) {
                    val peakY = labels + bars * (1f - peak[c])
                    canvas.drawRect(x0, peakY, x1, peakY + PEAK_LINE_PX, peakPaint)
                }
            }
        }

        // the hairline down the middle, over the bars
        if (stereo) canvas.drawLine(w / 2f, 0f, w / 2f, h, hairlinePaint)
    }

    private companion object {
        const val MIN_DB = -54f
        const val PEAK_HOLD_FRAMES = 45
        const val PEAK_LINE_PX = 2f
        const val LABEL_DP = 9f
    }
}
