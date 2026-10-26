package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Record: a white circle when idle, red when recording, and nothing inside
 * (v88). *"Remove the running number inside the record button, because it is
 * now duplicated"* — the timecode at the bottom middle says it.
 *
 * It sits at the top of the right rail rather than being a key like the
 * others, and that is on purpose: it is the only control on this camera whose
 * state you must be able to read without reading anything.
 *
 * v145 (Marko, 26.10.2026): *"Inside the record button, instead of this white thing, you need to display the
 * thumbnail of the last clip, either it's photography or video ... and the record button is still the record
 * button"*. Idle, the disc is the last thing captured, photo or take; recording, it is red as always.
 */
class RecordButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var recording: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Dark when there is nothing to record — no camera, or no microphone. */
    var dead: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * v151 (Marko, 26.10.2026: "The round button, the record button, should still be the record button as before. But
     * this triangle, which is the playing button, should become this round button with the last picture of the last
     * clip. Then when I press it, it plays"): the same round key as PLAY — the last capture in the disc, never red, a dark
     * disc when there is nothing yet. REC itself is the white disc again and carries no picture.
     */
    var playMode = false
        set(value) { field = value; contentDescription = if (value) "Play the last capture" else "Record"; invalidate() }

    /** v145: the last capture, cropped to the disc (v151: on the PLAY key only); null is the plain disc. */
    var thumbnail: Bitmap? = null
        set(value) {
            field = value
            thumbPaint.shader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val thumbMatrix = Matrix()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        contentDescription = "Record"
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val density = resources.displayMetrics.density
        ringPaint.strokeWidth = 2f * density
        // v155 (Marko, 26.10.2026: "Make the outline in red color"): REC's ring red; PLAY keeps its grey ring
        ringPaint.color = if (dead) Color.parseColor("#3A3E42") else if (playMode) Color.parseColor("#33D17A") else Color.parseColor("#FF3B30")   // PLAY green (Marko, 26.10.2026)
        fillPaint.color = when {
            dead -> Color.parseColor("#3A3E42")
            playMode -> Color.parseColor("#22262B")          // PLAY with nothing to play yet
            recording -> Color.parseColor("#FF1F0F")
            else -> Color.BLACK                                  // v155: black until it records (Marko, 26.10.2026)
        }
        val radius = min(cx, cy) - ringPaint.strokeWidth

        canvas.drawCircle(cx, cy, radius, ringPaint)
        val inner = radius - 3f * density
        val thumb = thumbnail
        if (thumb != null && !dead && (playMode || !recording) && inner > 0f) {
            // centre-crop the picture into the disc
            val k = (2f * inner) / minOf(thumb.width, thumb.height)
            thumbMatrix.setScale(k, k)
            thumbMatrix.postTranslate(cx - thumb.width * k / 2f, cy - thumb.height * k / 2f)
            thumbPaint.shader?.setLocalMatrix(thumbMatrix)
            canvas.drawCircle(cx, cy, inner, thumbPaint)
        } else {
            canvas.drawCircle(cx, cy, inner, fillPaint)
        }
    }
}
