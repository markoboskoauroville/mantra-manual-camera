package com.mantraproductions.ndi

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * v190: the padlock, drawn as a vector in two states: CLOSED (the shackle goes down into the body on both sides) and
 * OPEN (the shackle is lifted, its right leg free above the body). Used by the LOCK key and by the lock screen, so the
 * two never disagree. [size] is the width of the body; the closed padlock is centred on (cx, cy) by its drawn box,
 * and the open one keeps the same body place, so the body does not jump between the two states.
 */
object Padlock {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val box = RectF()

    fun draw(canvas: Canvas, cx: Float, cy: Float, size: Float, open: Boolean, colour: Int, hole: Int) {
        val s = size
        val bodyW = s
        val bodyH = s * 0.74f
        val legX = s * 0.30f              // the shackle's legs, either side of the middle
        val rise = s * 0.30f              // the legs above the body before the arc starts
        val lift = if (open) s * 0.24f else 0f
        // the closed padlock's drawn box: shackle top to body bottom; centred optically (the body is the heavy part, so
        // the box sits a touch low: 3 % of the size)
        val boxH = bodyH + rise + legX + s * 0.09f
        val top = cy - boxH / 2f + s * 0.03f
        val bodyTop = top + rise + legX + s * 0.09f
        val bw = s * 0.15f                 // the shackle's stroke

        // body
        fill.color = colour
        box.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH)
        canvas.drawRoundRect(box, s * 0.14f, s * 0.14f, fill)

        // keyhole: a dot and a tapering slot, in the background colour
        fill.color = hole
        val kcy = bodyTop + bodyH * 0.42f
        canvas.drawCircle(cx, kcy, s * 0.095f, fill)
        path.reset()
        path.moveTo(cx - s * 0.045f, kcy + s * 0.05f)
        path.lineTo(cx + s * 0.045f, kcy + s * 0.05f)
        path.lineTo(cx + s * 0.07f, kcy + bodyH * 0.30f)
        path.lineTo(cx - s * 0.07f, kcy + bodyH * 0.30f)
        path.close()
        canvas.drawPath(path, fill)

        // shackle
        stroke.color = colour
        stroke.strokeWidth = bw
        val arcTop = bodyTop - rise - legX - lift       // top of the arc
        val arcCy = arcTop + legX                       // the arc's centre
        path.reset()
        path.moveTo(cx - legX, bodyTop + bw * 0.5f)
        path.lineTo(cx - legX, arcCy)
        box.set(cx - legX, arcTop, cx + legX, arcTop + 2 * legX)
        path.arcTo(box, 180f, 180f, false)
        if (open) path.lineTo(cx + legX, bodyTop - s * 0.13f)       // the free leg stops above the body
        else path.lineTo(cx + legX, bodyTop + bw * 0.5f)
        canvas.drawPath(path, stroke)
    }
}
