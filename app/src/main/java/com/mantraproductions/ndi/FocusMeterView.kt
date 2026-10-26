package com.mantraproductions.ndi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * THE FOCUS PULLER'S METER (v149, Marko, 26.10.2026): *"a remote control focus-pulling user interface, where the focus
 * puller can mark on the meter, which will be on the screen, different distances, any number, and then with the play
 * button go from one to the other ... Any point he marks is key points or key frames. There will be small circles, like
 * our ISO circle here, which will be the size of the index finger. He can tap on any of these points, and the focus will
 * just slide to that point ... Key frames on the meter will look like an empty triangle looking down, and the current
 * focus will be a full white triangle"*; *"it must use the longest edge of the phone's UI, not the short one."*
 *
 * A strip along the picture's long edge: down the right side upright, along the bottom sideways. The scale is in
 * dioptres, the lens's own steps ([Mechanism.meterPosition]): the near distances, where a pull is fine, get the room.
 * The lens is a full white triangle where the capture result says it is, so a rack is seen easing in and out, with its
 * own white finger circle (v151): pressed and dragged, it pulls focus by hand; held, it asks ADD KEYFRAME (an empty
 * triangle and an amber circle where the lens is) or DELETE KEYFRAME. A tap on a key point's circle racks to it; held, it
 * asks DELETE KEYFRAME; ▶ racks to the next key point in the order they were marked. Every move goes through [onRack] or
 * [onPull] and every question through [onLongPress]; the view itself never touches the camera.
 */
class FocusMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** The closest the lens goes, in dioptres; 0 hides the meter (a fixed lens). */
    var closest = 0f
        set(value) { field = value; invalidate() }
    /** Where the lens is now (the capture result), dioptres. */
    var lens: Float? = null
        set(value) { if (value != field) { field = value; invalidate() } }
    /** The key points, dioptres, in the order they were marked. */
    var keys: List<Float> = emptyList()
        set(value) { field = value; invalidate() }

    var onRack: ((Float) -> Unit)? = null
    var onPull: ((Float) -> Unit)? = null
    var onKeysChanged: ((List<Float>) -> Unit)? = null
    /**
     * v151: a long press on a circle. [lens] true: the white one (the screen offers ADD KEYFRAME, and DELETE KEYFRAME
     * when [keyIndex] ≥ 0, a key point standing where the lens is); false: a key point's (DELETE KEYFRAME).
     */
    var onLongPress: ((lens: Boolean, keyIndex: Int) -> Unit)? = null

    /** v151: a key point where the lens is now, the one at the lens's own place (the screen's ADD KEYFRAME). */
    fun addKeyAtLens() {
        val l = lens ?: return
        keys = keys + l.coerceIn(0f, closest)
        onKeysChanged?.invoke(keys)
    }

    fun deleteKey(index: Int) {
        if (index !in keys.indices) return
        keys = keys.filterIndexed { i, _ -> i != index }
        onKeysChanged?.invoke(keys)
    }

    /** The key point standing where the lens is (within its circle), or -1. */
    fun keyAtLens(): Int {
        val l = lens ?: return -1
        val at = along(l.coerceIn(0f, closest))
        return keys.indexOfFirst { kotlin.math.abs(along(it) - at) <= circleR }
    }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val vertical get() = height >= width
    /** The strip's thickness, from the long edge in. */
    private val strip get() = 96f * density
    private val margin get() = 28f * density
    private val circleR get() = 20f * density          // an index finger
    private val playR get() = 22f * density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 242, 242, 242); strokeWidth = 1.5f * density }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 242, 242, 242); strokeWidth = 1f * density }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 242, 242, 242); textSize = 9f * scaled; typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }
    private val keyLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = RailButton.AMBER; strokeWidth = 1.6f * density; setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }
    private val lensFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = RailButton.WHITE; setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val lensRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = RailButton.WHITE; strokeWidth = 2f * density; setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = RailButton.GREEN }
    private val path = Path()

    // --- where things are -----------------------------------------------------------------------------------------

    /** The scale's two ends along the long edge, pixels: the near end first. */
    private fun ends(): Pair<Float, Float> =
        if (vertical) (height - margin - playR * 2.6f) to margin        // near at the bottom, ∞ at the top, ▶ below
        else (margin + playR * 2.6f) to (width - margin)               // near at the left, ∞ at the right, ▶ before

    /** The scale line's place across the strip. */
    private fun lineAt(): Float = if (vertical) width - strip * 0.32f else height - strip * 0.32f

    private fun along(dpt: Float): Float {
        val (near, far) = ends()
        return near + (far - near) * Mechanism.meterPosition(dpt, closest)
    }

    private fun dptAt(x: Float, y: Float): Float {
        val (near, far) = ends()
        val p = ((if (vertical) y else x) - near) / (far - near)
        return Mechanism.meterDioptres(p, closest)
    }

    /** A key's circle: on the picture's side of its triangle. */
    private fun circleCentre(dpt: Float): Pair<Float, Float> {
        val a = along(dpt)
        val c = lineAt() - 14f * density - circleR - 6f * density
        return if (vertical) c to a else a to c
    }

    private fun playCentre(): Pair<Float, Float> {
        val (near, _) = ends()
        val p = if (vertical) near + playR * 1.6f else near - playR * 1.6f
        return if (vertical) lineAt() to p else p to lineAt()
    }

    // --- drawing --------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (closest <= 0f) return
        val (near, far) = ends()
        val l = lineAt()
        if (vertical) canvas.drawLine(l, near, l, far, line) else canvas.drawLine(near, l, far, l, line)

        // the distances worth a word, from the closest the lens goes to infinity
        val marks = listOf(1000f / 100f to "10", 100f / 15f to "15", 100f / 20f to "20", 100f / 30f to "30",
            100f / 50f to "50cm", 1f to "1m", 0.5f to "2m", 0f to "∞")
        for ((dpt, word) in marks) {
            if (dpt > closest + 0.01f) continue
            val a = along(dpt)
            val t = 6f * density
            if (vertical) {
                canvas.drawLine(l, a, l + t, a, tick)
                canvas.drawText(word, l + t + 3f * density, a + words.textSize * 0.35f, words)
            } else {
                canvas.drawLine(a, l, a, l + t, tick)
                canvas.drawText(word, a - words.measureText(word) / 2f, l + t + words.textSize + 2f * density, words)
            }
        }

        // the key points: an empty triangle on the line, a fingertip circle beside it
        for (k in keys) {
            triangle(canvas, along(k), keyLine)
            val (cx, cy) = circleCentre(k)
            canvas.drawCircle(cx, cy, circleR, keyLine)
        }
        // the lens, a full white triangle, and (v151) its own white finger circle: press it and drag to pull focus
        lens?.let {
            val d = it.coerceIn(0f, closest)
            triangle(canvas, along(d), lensFill)
            val (cx, cy) = circleCentre(d)
            canvas.drawCircle(cx, cy, circleR, lensRing)
        }

        // ▶, the next key point
        val (px, py) = playCentre()
        playPaint.color = if (keys.isEmpty()) RailButton.DEAD else RailButton.GREEN
        path.reset()
        val r = playR * 0.55f
        path.moveTo(px - r * 0.6f, py - r); path.lineTo(px + r, py); path.lineTo(px - r * 0.6f, py + r); path.close()
        canvas.drawPath(path, playPaint)
    }

    /** A triangle whose point touches the line from the picture's side ("looking down" at the scale). */
    private fun triangle(canvas: Canvas, at: Float, paint: Paint) {
        val l = lineAt()
        val h = 12f * density
        val w = 7f * density
        path.reset()
        if (vertical) { path.moveTo(l, at); path.lineTo(l - h, at - w); path.lineTo(l - h, at + w) }
        else { path.moveTo(at, l); path.lineTo(at - w, l - h); path.lineTo(at + w, l - h) }
        path.close()
        canvas.drawPath(path, paint)
    }

    // --- the hand -------------------------------------------------------------------------------------------------

    // v151 (Marko, 26.10.2026): the lens's own white circle — pressed and dragged up and down, it pulls focus; held, it
    // asks ADD KEYFRAME / DELETE KEYFRAME. A key point's circle: a tap racks there, held it asks DELETE KEYFRAME. Key
    // points are no longer added by a tap on the scale (the long press on the white circle is the way).
    private enum class Grab { NONE, KEY, LENS, PLAY }
    private var grab = Grab.NONE
    private var grabbedKey = -1
    private var longFired = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    /** Where along the scale the finger held the lens's circle, against the circle's own place, so it does not jump. */
    private var grabOffset = 0f
    private val longPress = Runnable {
        when (grab) {
            Grab.LENS -> { longFired = true; onLongPress?.invoke(true, keyAtLens()) }
            Grab.KEY -> if (grabbedKey in keys.indices) { longFired = true; onLongPress?.invoke(false, grabbedKey) }
            else -> return@Runnable
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }
    private var next = 0

    private fun inStrip(x: Float, y: Float) = if (vertical) x >= width - strip else y >= height - strip

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (closest <= 0f) return false
        val pos = if (vertical) e.y else e.x
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inStrip(e.x, e.y)) return false           // not ours: on to the marks and the square underneath
                downX = e.x; downY = e.y; longFired = false; dragging = false
                val reach = circleR * 1.2f
                val (px, py) = playCentre()
                val onLens = lens?.let { l -> circleCentre(l.coerceIn(0f, closest)).let { (cx, cy) -> dist(e.x, e.y, cx, cy) <= reach } } == true
                grabbedKey = keys.indexOfFirst { k -> circleCentre(k).let { (cx, cy) -> dist(e.x, e.y, cx, cy) <= reach } }
                grab = when {
                    dist(e.x, e.y, px, py) <= playR * 1.3f -> Grab.PLAY
                    onLens -> Grab.LENS                        // the white circle wins where a key point lies under it
                    grabbedKey >= 0 -> Grab.KEY
                    else -> return false                       // the empty strip: the picture's
                }
                if (grab == Grab.LENS) grabOffset = along(lens!!.coerceIn(0f, closest)) - pos
                // Marko: "long press on the focusing pulling interface should last two seconds"
                if (grab == Grab.LENS || grab == Grab.KEY) postDelayed(longPress, LONG_PRESS_MS)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = dist(e.x, e.y, downX, downY) > 10f * density
                if (moved && !dragging) { dragging = true; removeCallbacks(longPress) }
                if (grab == Grab.LENS && dragging && !longFired) {
                    val at = pos + grabOffset
                    onPull?.invoke(if (vertical) dptAt(0f, at) else dptAt(at, 0f))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when (grab) {
                    Grab.KEY -> if (!longFired && !dragging) keys.getOrNull(grabbedKey)?.let { onRack?.invoke(it) }
                    Grab.PLAY -> if (keys.isNotEmpty()) {
                        // the next key point in marking order, past any the lens already stands on
                        for (i in keys.indices) {
                            next %= keys.size
                            val k = keys[next++]
                            if (lens == null || kotlin.math.abs(k - lens!!) > 0.05f) { onRack?.invoke(k); break }
                        }
                    }
                    else -> Unit
                }
                grab = Grab.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); grab = Grab.NONE; return true }
        }
        return grab != Grab.NONE
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = kotlin.math.hypot(ax - bx, ay - by)

    private companion object { const val LONG_PRESS_MS = 2000L }
}
