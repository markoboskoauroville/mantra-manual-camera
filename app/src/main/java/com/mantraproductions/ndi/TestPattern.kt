package com.mantraproductions.ndi

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v197, THE TEST LENS: a moving pattern with a clock, drawn at [fps] into the GPU stage's input, for a machine with no
 * camera (the cloud Pixel 7 has none) so GO LIVE, the take and the outputs can be tested there. Colour bars, a white
 * bar sweeping across once a second, a frame counter and the wall clock to the millisecond: two viewer screenshots
 * show that the picture moves, and the clock against the viewer's own clock gives the delay.
 */
class TestPattern(override val width: Int = 1280, override val height: Int = 720, private val fps: Int = 30) : FrameSource {
    override val name = "TEST"
    @Volatile override var postedFrameNs = 0L; private set
    @Volatile var frames = 0L; private set
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(surface: Surface): String? {
        if (!surface.isValid) return "no surface to draw into"
        running = true
        thread = Thread({ loop(surface) }, "test-pattern").also { it.start() }
        return null
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop(surface: Surface) {
        val bars = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK)
        val fill = Paint()
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.MONOSPACE; textSize = height / 7f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.MONOSPACE; textSize = height / 18f }
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
        val step = 1_000_000_000L / fps
        var next = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            if (now < next) { Thread.sleep((next - now) / 1_000_000, ((next - now) % 1_000_000).toInt()); continue }
            next += step
            if (next < System.nanoTime() - step) next = System.nanoTime() + step
            val c = runCatching { surface.lockHardwareCanvas() }.getOrElse { runCatching { surface.lockCanvas(null) }.getOrNull() } ?: break
            try {
                val w = width.toFloat(); val h = height.toFloat()
                val bw = w / bars.size
                for (i in bars.indices) { fill.color = bars[i]; c.drawRect(i * bw, 0f, (i + 1) * bw, h * 0.55f, fill) }
                fill.color = Color.rgb(20, 20, 24); c.drawRect(0f, h * 0.55f, w, h, fill)
                val phase = (System.currentTimeMillis() % 1000L) / 1000f
                fill.color = Color.WHITE; c.drawRect(phase * w - 12f, h * 0.55f, phase * w + 12f, h * 0.62f, fill)
                c.drawText(clock.format(Date()), w * 0.05f, h * 0.80f, big)
                c.drawText("MANTRA TEST  frame $frames  ${width}x$height@$fps", w * 0.05f, h * 0.93f, small)
            } finally {
                runCatching { surface.unlockCanvasAndPost(c) }
            }
            postedFrameNs = System.nanoTime()
            frames++
        }
    }
}
