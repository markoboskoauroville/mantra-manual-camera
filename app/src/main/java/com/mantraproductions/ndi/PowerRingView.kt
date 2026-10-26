package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * The POWER MONITOR's face (v155, Marko, 26.10.2026: "like the displays on batteries for Blackmagic cameras, V-mount
 * batteries, where they show a number ... a circle with different colors ... Maybe we can copy the Anker display").
 * A ring filled to the battery level (green above 50 %, amber to 20 %, red below; cyan while it charges), the time in
 * the middle, big, with ▲ charging, ▼ discharging, = balanced; the current under it. One rail key's room.
 */
class PowerRingView(context: Context) : View(context) {

    var reading: PowerMonitor.Reading? = null
        set(v) { field = v; contentDescription = describe(v); invalidate() }

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#2A2E33") }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val r = reading
        val size = minOf(width, height).toFloat()
        val stroke = (size * 0.09f).coerceAtLeast(2.5f * d)
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        val cx = width / 2f; val cy = height / 2f; val rad = size / 2f - stroke
        box.set(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawArc(box, 0f, 360f, false, track)
        if (r == null) return
        val colour = colourOf(r)
        arc.color = colour
        c.drawArc(box, -90f, 360f * r.level.coerceIn(0, 100) / 100f, false, arc)

        val arrow = when (r.flow) {
            PowerMonitor.Flow.CHARGING -> "▲"; PowerMonitor.Flow.DISCHARGING -> "▼"
            PowerMonitor.Flow.BALANCED -> "="; PowerMonitor.Flow.FULL -> "●"
        }
        val time = when (r.flow) {
            PowerMonitor.Flow.FULL -> "FULL"
            PowerMonitor.Flow.BALANCED -> "${r.level}%"
            else -> PowerMonitor.Calc.format(r.seconds)
        }
        big.color = Color.WHITE
        big.textSize = rad * 0.52f
        val w = big.measureText(time); if (w > rad * 1.6f) big.textSize *= rad * 1.6f / w
        c.drawText(time, cx, cy + big.textSize * 0.25f, big)
        small.color = colour
        small.textSize = rad * 0.34f
        c.drawText(arrow + signed(r.netMa), cx, cy + big.textSize * 0.25f + small.textSize * 1.25f, small)
        small.textSize = rad * 0.30f
        c.drawText("${r.level}%", cx, cy - big.textSize * 0.75f, small)
    }

    companion object {
        private val GREEN = Color.parseColor("#33D17A")
        private val AMBER = Color.parseColor("#E8A33D")
        private val RED = Color.parseColor("#E5484D")
        private val CYAN = Color.parseColor("#3DD6E8")

        fun colourOf(r: PowerMonitor.Reading): Int = when {
            r.flow == PowerMonitor.Flow.CHARGING || r.flow == PowerMonitor.Flow.FULL -> CYAN
            r.level < 20 || (r.seconds != null && r.seconds < 20 * 60) -> RED
            r.level < 50 -> AMBER
            else -> GREEN
        }

        /** +1.2A, −850mA: amperes from one ampere up, as a V-mount shows them. */
        fun signed(ma: Int): String {
            val s = if (ma > 0) "+" else if (ma < 0) "−" else "±"
            val a = kotlin.math.abs(ma)
            return if (a >= 1000) String.format(java.util.Locale.ROOT, "%s%.1fA", s, a / 1000.0) else "$s${a}mA"
        }

        fun describe(r: PowerMonitor.Reading?): String {
            if (r == null) return "Power monitor"
            val what = when (r.flow) {
                PowerMonitor.Flow.CHARGING -> "charging, full in ${PowerMonitor.Calc.format(r.seconds)}"
                PowerMonitor.Flow.DISCHARGING -> "discharging, empty in ${PowerMonitor.Calc.format(r.seconds)}"
                PowerMonitor.Flow.BALANCED -> "balanced"
                PowerMonitor.Flow.FULL -> "full"
            }
            return "Battery ${r.level} %, $what, ${signed(r.netMa)}, ${String.format(java.util.Locale.ROOT, "%.1f", r.watts)} W"
        }
    }
}
