package com.mantraproductions.ndi

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout

/**
 * v190: THE LOCK SCREEN, for long takes on a tripod (Marko, 9.11.2026: Santoshi concerts).
 *
 * Black, over everything, swallowing every touch. A red outline breathes slowly (a 5 s cycle, ease in and out) so the
 * eye knows the phone is not frozen; the take's time runs large in the middle, dim; a padlock under it. One tap opens
 * the padlock for [OPEN_MS]; a second tap inside that time unlocks, otherwise it closes again.
 *
 * Light on the phone: the outline is a separate view drawn once and pulsed by its alpha on a hardware layer, so the GPU
 * does the breathing and nothing is redrawn; the text and padlock are redrawn once a second.
 */
class LockScreen(context: Context, private val elapsed: () -> Long?, private val onUnlock: () -> Unit) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private var open = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val close = Runnable { open = false; face.invalidate() }
    private var pulse: ObjectAnimator? = null
    private var lastRecording = false

    /** The red outline: Sony's recording frame. Grey when nothing is recording. */
    private val frame = object : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        override fun onDraw(c: Canvas) {
            val w = 5f * dp
            p.strokeWidth = w
            p.color = if (lastRecording) Color.rgb(255, 59, 48) else Color.rgb(110, 110, 110)
            c.drawRect(w / 2, w / 2, width - w / 2, height - w / 2, p)
        }
    }

    /** The time and the padlock. */
    private val face = object : View(context) {
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val lock = minOf(w, h) * 0.16f
            var size = minOf(w * 0.17f, h * 0.17f)
            val ms = elapsed()
            val text = if (ms == null) "--:--:--" else clock(ms)
            t.textSize = size
            while (t.measureText(text) > w * 0.8f && size > 10f) { size *= 0.95f; t.textSize = size }
            t.color = if (ms == null) Color.rgb(90, 90, 90) else Color.rgb(150, 150, 150)
            // the group (time, gap, padlock) centred as one
            val padlockH = lock * 1.45f
            val gap = size * 0.55f
            val group = size * 0.75f + gap + padlockH
            val top = h / 2f - group / 2f
            c.drawText(text, w / 2f, top + size * 0.75f, t)
            Padlock.draw(
                c, w / 2f, top + size * 0.75f + gap + padlockH / 2f, lock, open,
                if (open) Color.rgb(235, 235, 235) else Color.rgb(120, 120, 120), Color.BLACK
            )
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = false
        // margins on all sides: the outline sits inside a gutter, the system bars are already padded away by the root
        val gutter = (6 * dp).toInt()
        addView(frame, LayoutParams(-1, -1).apply { setMargins(gutter, gutter, gutter, gutter) })
        addView(face, LayoutParams(-1, -1).apply { setMargins((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt()) })
        frame.setLayerType(LAYER_TYPE_HARDWARE, null)
        visibility = GONE
    }

    private val tick = object : Runnable {
        override fun run() {
            val rec = elapsed() != null
            if (rec != lastRecording) { lastRecording = rec; frame.invalidate() }
            face.invalidate()
            ui.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000))
        }
    }

    fun show() {
        open = false
        lastRecording = elapsed() != null
        visibility = VISIBLE
        frame.invalidate()
        pulse?.cancel()
        pulse = ObjectAnimator.ofFloat(frame, View.ALPHA, 0.18f, 1f).apply {
            duration = 2500; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator(); start()
        }
        ui.removeCallbacks(tick); ui.post(tick)
    }

    fun hide() {
        pulse?.cancel(); pulse = null
        ui.removeCallbacks(tick); ui.removeCallbacks(close)
        open = false
        visibility = GONE
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            if (open) { onUnlock() }
            else { open = true; face.invalidate(); ui.removeCallbacks(close); ui.postDelayed(close, OPEN_MS) }
        }
        return true
    }

    override fun onDetachedFromWindow() { pulse?.cancel(); ui.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }

    companion object {
        const val OPEN_MS = 2000L
        fun clock(ms: Long): String {
            val s = ms / 1000
            return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        }
    }
}
