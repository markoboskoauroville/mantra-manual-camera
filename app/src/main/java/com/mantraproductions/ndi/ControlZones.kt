package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The zones: five bands across the picture, one parameter each.
 *
 * They were four invisible *columns* — a third of the frame wide, dragged up
 * and down — and that was wrong twice over: the values sat at the foot of the
 * picture where the geometry readout already lives, and a phone held across is
 * wide and short, so a vertical drag has the *short* side of the screen to
 * travel in while the long side sits empty. So the zones went horizontal, one
 * band per parameter, right for more of everything.
 *
 * **What v79 still had wrong was the room.** The name and the value were two
 * fixed columns a hundred and eighty density pixels wide, and the track began
 * after them — a third of the travel spent on two words. *"We want to maximise
 * the slider length so I can do it precisely in the zone."* Quite right: the
 * words are small and sit **above** the track now, and the track runs the
 * whole width of the picture, edge to edge.
 *
 * **A zone is relative.** Touching one never moves anything: the value goes
 * where the thumb pushes it from where it already was, so a fader can be
 * nudged a hair without first being found exactly. An absolute fader on a
 * picture is a parameter that jumps the moment it is touched, which on a live
 * camera is a ruined take.
 *
 * **v87: mixer faders with an A / M switch each.** A double tap and a hold
 * used to hand a zone back to the camera, and nobody could remember which did
 * what. Now a switch at the head of each fader says A or M and is pressed to
 * change it; a drag moves the fader (and takes it to M); a single tap anywhere
 * else focuses there; a double tap means nothing.
 *
 * **v183: the words turn with the phone, the faders do not.** Marko, 29.10.2026: *"When I'm in portrait mode, only
 * in the manual controls, the labels should rotate the same as the other labels ... the faders stay the same. The
 * base 100 and base 1600 can stay as they are, but all the small labels should turn around."* [turn] is the rail
 * keys' turn. A quarter turn (the phone held upright) moves the name and the number to the far end of each fader,
 * two upright lines, the number above and the name under it, *"like a label for that fader"*; the A / M letter and
 * every preset word short enough to stand in its key turn in place. Half a turn turns each word where it is.
 *
 * Still exclusive with the focus box, and that is still the reason the rail has
 * a key to put it away: a tap that could mean "focus here" and could mean
 * "start changing ISO" is a tap that means neither.
 */
class ControlZones @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /**
     * One zone.
     *
     * [live] false is a parameter this lens does not have — a phone's iris is
     * fixed, because the blades cost room a phone does not have, and an ultra
     * wide has no focus motor. It keeps its band and shows its value, dimmed
     * and with no track, rather than disappearing and shuffling the others
     * under his thumb.
     *
     * [position] is 0..1, where the value sits in its own travel. It is what
     * the knob is drawn at; without it a zone is a word with a line beside it.
     */
    data class Zone(
        val title: String,
        var value: String = "—",
        var live: Boolean = true,
        var position: Float = 0f,
        /** True while the camera is deciding this one, so the word can say so. */
        var auto: Boolean = false,
        /** Preset keys under the fader (v90): 0° … 360° under SHTR, BASE / HIGH under ISO. */
        var presets: List<String> = emptyList(),
        /** The preset the fader sits on, lit; -1 for none. */
        var presetLit: Int = -1,
        /**
         * v183: where the measurement would put this fader (0..1), drawn as one line across the groove while the
         * fader is manual; null draws nothing. The cap turns yellow when it stands on it.
         */
        var suggested: Float? = null
    )

    var zones: List<Zone> = emptyList()
        set(value) { field = value; invalidate() }

    /** A drag, as a fraction of the track's width. Right is positive. */
    var onDrag: ((index: Int, delta: Float) -> Unit)? = null

    /** Told when a zone is grabbed and let go, so the name can light up. */
    var onGrab: ((index: Int?) -> Unit)? = null

    /**
     * The A / M switch at the head of a fader: that parameter automatic or
     * manual. *"Next to each slider should be A and M ... avoid confusion with
     * what is double tap, what is single tap."* A double tap and a hold used to
     * carry these meanings; now a switch that says which it is carries them,
     * and a double tap means nothing at all.
     */
    var onToggle: ((index: Int) -> Unit)? = null

    /**
     * One tap anywhere that is not the switch: focus there. With nothing else
     * meaning a tap, it fires at once rather than a double-tap interval late.
     */
    var onSingleTap: ((x: Float, y: Float) -> Unit)? = null

    /** A preset key under a fader: which zone, which preset. */
    var onPreset: ((index: Int, preset: Int) -> Unit)? = null

    private var held: Int? = null
    private var onSwitch: Int? = null
    private var onPresetKey: Pair<Int, Int>? = null
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    /** v183: the turn of the words, in degrees (0, 90, 180, 270), the same as the rail keys' (RailButton.turn). */
    var turn = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    private val quarter get() = turn == 90 || turn == 270

    // v88: the names were grey and thin, and vanished over a bright picture.
    // Now the same weight as the numbers, white, over a dark band.
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = density(12f)
        letterSpacing = 0.08f
    }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 0, 0, 0)
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = density(15f)
    }
    private val switchText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = density(16f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val box = android.graphics.RectF()

    private fun density(dp: Float) = dp * resources.displayMetrics.density

    /** How far a finger may wander and still have meant a tap. */
    private val slop get() = density(8f)

    /** Below the telemetry and status lines, which are pinned to the top edge (v91: two lines). */
    private val topInset get() = density(44f)

    /**
     * Thick: a fader is grabbed without looking. As tall as the picture allows
     * for four of them in the top three quarters, and never thinner than a
     * thumb.
     */
    private val rowHeight: Float
        get() {
            val count = zones.size.coerceAtLeast(1)
            val strips = zones.count { it.presets.isNotEmpty() } * stripHeight
            val room = (height - topInset) * 0.78f - strips
            return (room / count).coerceIn(density(44f), density(68f))
        }

    /** The row of preset keys under a fader that has them. */
    private val stripHeight get() = density(34f)

    private fun heightOf(index: Int) =
        rowHeight + if (zones[index].presets.isNotEmpty()) stripHeight else 0f

    private val sideGap get() = density(8f)

    /** The A / M switch, a square at the head of each fader. */
    private val switchSize get() = (rowHeight * 0.62f).coerceAtMost(density(40f))

    private val trackLeft get() = sideGap + switchSize + density(12f)
    private val trackRight get() = width - sideGap - density(14f) - if (quarter) labelRoom + density(8f) else 0f

    /** v183: across the screen, what the two upright lines at a fader's far end take (the phone held upright). */
    private val labelRoom get() = value.fontSpacing + title.fontSpacing + density(10f)

    /** [text] centred on ([cx], [cy]), turned by [turn]; [maxLength] shrinks it to fit along its own line. */
    private fun drawTurned(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint, maxLength: Float = Float.MAX_VALUE) {
        val size = paint.textSize
        val align = paint.textAlign
        val w = paint.measureText(text)
        if (w > maxLength && w > 0f) paint.textSize = size * maxLength / w
        paint.textAlign = Paint.Align.CENTER
        val m = paint.fontMetrics
        canvas.save()
        canvas.rotate(turn.toFloat(), cx, cy)
        canvas.drawText(text, cx, cy - (m.ascent + m.descent) / 2f, paint)
        canvas.restore()
        paint.textSize = size
        paint.textAlign = align
    }

    private fun rowTop(index: Int): Float {
        var top = topInset
        for (i in 0 until index) top += heightOf(i)
        return top
    }

    /** Which band a finger at [y] landed on, or null. */
    private fun rowAt(y: Float): Int? {
        if (zones.isEmpty() || y < topInset) return null
        var top = topInset
        for (i in zones.indices) {
            val bottom = top + heightOf(i)
            if (y < bottom) return i
            top = bottom
        }
        return null
    }

    /** The preset key under a finger at ([x], [y]) in zone [index], or null. */
    private fun presetAt(index: Int, x: Float, y: Float): Int? {
        val zone = zones[index]
        if (zone.presets.isEmpty()) return null
        val stripTop = rowTop(index) + rowHeight
        if (y < stripTop || y > stripTop + stripHeight) return null
        val left = trackLeft
        val w = (trackRight - left) / zone.presets.size
        val k = ((x - left) / w).toInt()
        return if (x >= left && k in zone.presets.indices) k else null
    }

    private val presetText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun onSwitchAt(x: Float): Boolean = x <= sideGap + switchSize + density(6f)

    override fun onDraw(canvas: Canvas) {
        if (zones.isEmpty() || width == 0) return

        for (i in zones.indices) {
            val zone = zones[i]
            val top = rowTop(i)
            val mid = top + rowHeight * 0.62f

            // THE A / M SWITCH: amber A while the camera decides, white M
            // while the operator does. A lens that does not have the
            // parameter (a fixed focus, an iris) shows neither.
            val s = switchSize
            box.set(sideGap, mid - s / 2f, sideGap + s, mid + s / 2f)
            if (zone.live) {
                fill.color = if (zone.auto) Color.argb(200, 232, 163, 61) else Color.argb(215, 245, 247, 248)
                fill.setShadowLayer(density(3f), 0f, 0f, Color.BLACK)
                canvas.drawRoundRect(box, density(4f), density(4f), fill)
                fill.clearShadowLayer()
                switchText.color = Color.BLACK
                drawTurned(canvas, if (zone.auto) "A" else "M", box.centerX(), box.centerY(), switchText)
            }

            // The name and the number, above the track, the number big: it is
            // what the eye comes for, and it moves as the fader moves.
            val tint = when {
                !zone.live -> Color.argb(120, 122, 128, 135)
                held == i -> RailButton.GREEN
                zone.auto -> Color.argb(225, 232, 163, 61)
                else -> Color.argb(235, 245, 247, 248)
            }
            title.color = Color.argb(if (zone.live) 245 else 110, 245, 247, 248)
            value.color = tint
            title.setShadowLayer(density(2.5f), 0f, 0f, Color.BLACK)
            value.setShadowLayer(density(2.5f), 0f, 0f, Color.BLACK)
            val pad = density(5f)
            if (quarter) {
                // v183, THE PHONE UPRIGHT: at the far end of the fader, two lines that read upright, the number above
                // and the name under it. Held with the screen's left edge up (270) "above" is towards the left.
                val right = width - sideGap
                val left = right - labelRoom
                val cy = top + rowHeight / 2f
                val along = rowHeight - pad * 2f
                canvas.drawRoundRect(left, top + pad * 0.6f, right, top + rowHeight - pad * 0.6f, pad, pad, band)
                val inner = left + density(5f)
                val outer = right - density(5f)
                val valueFirst = turn == 270
                val valueX = if (valueFirst) inner + value.fontSpacing / 2f else outer - value.fontSpacing / 2f
                val titleX = if (valueFirst) outer - title.fontSpacing / 2f else inner + title.fontSpacing / 2f
                drawTurned(canvas, zone.value, valueX, cy, value, along)
                drawTurned(canvas, zone.title, titleX, cy, title, along)
            } else {
                val words = top + rowHeight * 0.30f
                val valueX = trackLeft + title.measureText(zone.title) + density(10f)
                canvas.drawRoundRect(
                    trackLeft - pad, words + value.ascent() - pad * 0.6f,
                    valueX + value.measureText(zone.value) + pad, words + value.descent() + pad * 0.6f,
                    pad, pad, band
                )
                if (turn == 180) {
                    // upside down: each word turns where it stands
                    val tm = title.fontMetrics
                    drawTurned(canvas, zone.title, trackLeft + title.measureText(zone.title) / 2f,
                        words + (tm.ascent + tm.descent) / 2f, title)
                    val vm = value.fontMetrics
                    drawTurned(canvas, zone.value, valueX + value.measureText(zone.value) / 2f,
                        words + (vm.ascent + vm.descent) / 2f, value)
                } else {
                    canvas.drawText(zone.title, trackLeft, words, title)
                    canvas.drawText(zone.value, valueX, words, value)
                }
            }

            if (!zone.live) continue
            val left = trackLeft
            val right = trackRight
            if (right <= left) continue

            // THE PRESETS (v90): a row of keys under the groove, equal widths
            // across the fader's own length. Dark keys with white words, the
            // one the fader sits on lit green; a key being pressed amber.
            if (zone.presets.isNotEmpty()) {
                val stripTop = top + rowHeight + density(3f)
                val w = (right - left) / zone.presets.size
                presetText.textSize = density(12f)
                for (k in zone.presets.indices) {
                    box.set(left + w * k + density(2f), stripTop, left + w * (k + 1) - density(2f),
                        stripTop + stripHeight - density(8f))
                    val pressed = onPresetKey == (i to k)
                    fill.color = when {
                        pressed -> Color.argb(230, 232, 163, 61)
                        k == zone.presetLit -> Color.argb(210, 51, 209, 122)
                        else -> Color.argb(150, 0, 0, 0)
                    }
                    canvas.drawRoundRect(box, density(4f), density(4f), fill)
                    presetText.color = if (pressed || k == zone.presetLit) Color.BLACK else Color.WHITE
                    val label = zone.presets[k]
                    // v183: a word short enough to stand up in its key turns with the phone (0°, 90° …);
                    // a long one (BASE 100, HIGH 1600) stays as it is
                    val standing = box.height() - density(4f)
                    val full = presetText.measureText(label)
                    if (turn != 0 && (!quarter || full * PRESET_MIN / 12f <= standing)) {
                        drawTurned(canvas, label, box.centerX(), box.centerY(), presetText,
                            if (quarter) standing else box.width() - density(4f))
                        continue
                    }
                    val room = box.width() - density(4f)
                    if (full > room) {
                        presetText.textSize = density(12f) * room / full
                    }
                    val m = presetText.fontMetrics
                    canvas.drawText(label, box.centerX(), box.centerY() - (m.ascent + m.descent) / 2f, presetText)
                    presetText.textSize = density(12f)
                }
            }

            // THE GROOVE: a thick dark slot with ticks every tenth, like the
            // slot a mixer's fader runs in.
            line.strokeWidth = density(7f)
            line.color = Color.argb(150, 0, 0, 0)
            canvas.drawLine(left, mid, right, mid, line)
            line.strokeWidth = density(1f)
            line.color = Color.argb(110, 255, 255, 255)
            for (t in 0..10) {
                val x = left + (right - left) * t / 10f
                val h = if (t % 5 == 0) density(9f) else density(5f)
                canvas.drawLine(x, mid + density(6f), x, mid + density(6f) + h, line)
            }

            // v183, THE MEASURED POINT (Marko, 29.10.2026: "mark the phone's recommended settings in manual mode
            // with one line"; "only the knob should change its color to yellow when it reaches" it)
            val suggested = zone.suggested?.takeIf { !zone.auto }?.coerceIn(0f, 1f)
            if (suggested != null) {
                val sx = left + (right - left) * suggested
                line.strokeWidth = density(2f)
                line.color = YELLOW
                line.setShadowLayer(density(2f), 0f, 0f, Color.BLACK)
                canvas.drawLine(sx, mid - density(14f), sx, mid + density(14f), line)
                line.clearShadowLayer()
            }
            val onMeasured = suggested != null && kotlin.math.abs(zone.position - suggested) <= ON_MEASURED

            // THE CAP: a flat block, wide across the groove and narrow along it, with
            // a line across its middle, the way an audio fader's cap is read at a glance.
            val at = left + (right - left) * zone.position.coerceIn(0f, 1f)
            val capW = density(18f)
            val capH = (rowHeight * 0.62f).coerceAtMost(density(42f))
            box.set(at - capW / 2f, mid - capH / 2f, at + capW / 2f, mid + capH / 2f)
            fill.color = when {
                onMeasured -> YELLOW
                held == i -> RailButton.GREEN
                zone.auto -> Color.argb(200, 200, 190, 170)
                else -> Color.argb(240, 230, 232, 235)
            }
            fill.setShadowLayer(density(4f), 0f, 0f, Color.BLACK)
            canvas.drawRoundRect(box, density(3f), density(3f), fill)
            fill.clearShadowLayer()
            // v183 (Marko, 29.10.2026: "turn the knobs on the faders so they are vertical, like on the audio mixer.
            // That is our role model"): the line runs ACROSS the cap, square to the travel, the level mark of a mixer's
            // fader cap, with a fine grip ridge either side of it. It ran along the travel before.
            line.strokeWidth = density(1f)
            line.color = Color.argb(110, 20, 20, 20)
            for (side in listOf(-1f, 1f)) {
                val x = at + side * capW * 0.30f
                canvas.drawLine(x, box.top + density(4f), x, box.bottom - density(4f), line)
            }
            line.strokeWidth = density(2f)
            line.color = Color.argb(220, 20, 20, 20)
            canvas.drawLine(at, box.top + density(2f), at, box.bottom - density(2f), line)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zones.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                moved = false
                val index = rowAt(event.y)
                onPresetKey = index?.takeIf { zones[it].live }
                    ?.let { i -> presetAt(i, event.x, event.y)?.let { k -> i to k } }
                onSwitch = if (index != null && onPresetKey == null && onSwitchAt(event.x) &&
                    event.y < rowTop(index) + rowHeight && zones[index].live) index else null
                held = if (index != null && onSwitch == null && onPresetKey == null &&
                    event.y < rowTop(index) + rowHeight && zones[index].live) index else null
                if (held != null) onGrab?.invoke(held)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved &&
                    (kotlin.math.abs(event.x - downX) > slop ||
                        kotlin.math.abs(event.y - downY) > slop)
                ) moved = true
                val index = held ?: return true
                if (!moved) return true
                // Relative to the last position: a fader that jumps to where
                // the finger landed is a picture that jumps when it is touched.
                val span = (trackRight - trackLeft).coerceAtLeast(1f)
                val delta = (event.x - lastX) / span
                lastX = event.x
                onDrag?.invoke(index, delta)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val sw = onSwitch
                val wasHeld = held
                val preset = onPresetKey
                held = null
                onSwitch = null
                onPresetKey = null
                if (wasHeld != null) onGrab?.invoke(null)
                invalidate()
                if (!moved) {
                    if (preset != null) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        onPreset?.invoke(preset.first, preset.second)
                    } else if (sw != null) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        onToggle?.invoke(sw)
                    } else {
                        onSingleTap?.invoke(event.x / width.coerceAtLeast(1), event.y / height.coerceAtLeast(1))
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                held = null
                onSwitch = null
                onPresetKey = null
                onGrab?.invoke(null)
                invalidate()
                return true
            }
        }
        return false
    }

    private companion object {
        /** v183: the smallest a preset word may shrink to (sp) and still turn upright in its key. */
        const val PRESET_MIN = 9f
        /** v183: how near the measured point (in travel) the cap counts as on it. */
        const val ON_MEASURED = 0.015f
        val YELLOW = Color.rgb(255, 214, 10)
    }
}
