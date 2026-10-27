package com.mantraproductions.ndi

import android.os.Handler
import android.os.Looper

/**
 * How focus behaves, as opposed to how it is set.
 *
 * Continuous autofocus hunts: it re-evaluates constantly and snaps whenever it
 * changes its mind. That is right for a photograph and ruinous in a shot,
 * because the snap is visible and the hunting is visible and neither is
 * something a camera operator would ever do.
 *
 * A focus puller holds, notices, then moves over a beat. So:
 *
 *     hold for the hold time, measuring sharpness but touching nothing
 *     if it has genuinely drifted, rack to the new mark over the rack time
 *     hold again
 *
 * The lens is driven here rather than handed to the camera's own routine,
 * because the camera's routine cannot be asked to take two seconds about it.
 * Sharpness comes from the preview, the same frames the scope and the waveform
 * read, so it costs nothing extra.
 *
 * Both times are settings. Two seconds is a considered move, one is brisk, and
 * zero is a snap for anybody who wants the phone to behave like a phone.
 */
class FocusDirector(
    private val controls: () -> CaptureEngine?,
    private val onState: (FocusSquareView.State) -> Unit
) {
    enum class Mode { MANUAL, AUTO }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Focus starts on A (v88). It started on M, so the M key read HM on a
     * camera where he had touched nothing.
     */
    var mode: Mode = Mode.AUTO
        private set

    /** Where the box is, as fractions of the frame. */
    var target: Pair<Float, Float> = 0.5f to 0.5f

    /** The box, for measuring sharpness only inside it. */
    var bounds: FloatArray = floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f)

    /** Set by the screen each time it samples the preview (v159: MainActivity.sharpnessTick; it never was before). */
    @Volatile var currentSharpness: Double = 0.0

    var holdMs: Long = 2000
    var rampMs: Long = 2000
    /** v147: millimetres per second for a tap, a rack or a new mark; 0 is the camera's own glide (v125). */
    var speedMmS: Int = 0
    /** v147: the same for a tracked subject. */
    var trackSpeedMmS: Int = 0

    /** v158: the square's TRACK: off, a found focus is held and never looked at again; on, the 2-second watch. */
    var watching = true
        set(value) { field = value; if (value) watchAgain() }

    /** v158: how far sharpness may fall before focus moves (the delta), a fraction of the best it has been. */
    var holdFraction = 0.75

    private var watchAlive = false

    /**
     * v159 (27.10.2026, measured on his tennis ball: moved 30 cm closer, focus never followed): a tap, a glide or a
     * walk that found focus never started the watch; only switching AF on did. Every focus found arms it now.
     */
    private fun armWatch() {
        if (!watching || mode != Mode.AUTO) return
        handler.postDelayed({ if (!watchAlive && !racking) watch() }, holdMs.coerceAtLeast(200))
    }

    /** v158: the watch started again (TRACK turned on with focus held), once. */
    private fun watchAgain() {
        if (!watchAlive && mode == Mode.AUTO && !racking) handler.postDelayed({ watch() }, holdMs.coerceAtLeast(200))
    }

    private var reference = 0.0
    private var lensPosition = 0f
    private var racking = false
    private var searching = false

    /** v153 (the review): a tap's search-and-walk ends here: a hand pull, a rack or a mode change takes over. */
    fun cancelWalk() { walkTicket++; racking = false }

    fun setMode(next: Mode) {
        if (mode == next) return
        walkTicket++
        mode = next
        handler.removeCallbacksAndMessages(null)
        racking = false
        when (next) {
            Mode.MANUAL -> {
                controls()?.lockFocusHere()
                onState(FocusSquareView.State.LOCKED)
            }
            Mode.AUTO -> {
                focusNowThenHold()
            }
        }
    }

    /** True while the lens is gliding to a mark (v125): a new tap only moves the mark it travels to. */
    private var gliding = false

    /**
     * The box was tapped. In either mode that means focus here, then hold.
     *
     * v125, THE RAMP: with a rack time the lens GLIDES there (the camera's video focus, eased by its own
     * drive) and is locked where it comes to rest; a rack time of zero is the photo search, the snap.
     */
    fun focusHereAndHold(speed: Int = speedMmS) {
        val c = controls() ?: return
        reference = 0.0                 // v159: a new place: what was best at the old one says nothing here
        if (searching && !gliding) return
        // v147: at a speed, the camera finds the mark, the lens goes back and walks there at that speed
        if (speed > 0) { searchThenWalk(c, speed) { settled -> lensPosition = settled; reference = 0.0; onState(FocusSquareView.State.LOCKED); armWatch() }; return }
        if (rampMs > 0L) {
            val started = c.glideFocusAtNormalisedPoint(target.first, target.second) { focused ->
                searching = false
                gliding = false
                // locked either way: a lens left in a continuous mode keeps hunting, on the recording
                c.lockFocusHere()
                lensPosition = c.lastFocusDistance ?: lensPosition
                reference = 0.0   // v159: the next watch reads it fresh (sampled twice a second, the old value may be another place)
                onState(if (focused) FocusSquareView.State.LOCKED else FocusSquareView.State.FAILED)
                if (focused) armWatch()
            }
            if (started) {
                searching = true
                gliding = true
                onState(FocusSquareView.State.SEEKING)
                return
            }
        }
        if (searching) return
        searching = true
        onState(FocusSquareView.State.SEEKING)
        c.focusAtNormalisedPoint(target.first, target.second) { focused ->
            searching = false
            if (focused) {
                c.lockFocusHere()
                lensPosition = c.lastFocusDistance ?: lensPosition
                reference = 0.0   // v159: the next watch reads it fresh (sampled twice a second, the old value may be another place)
                onState(FocusSquareView.State.LOCKED)
                armWatch()
            } else {
                onState(FocusSquareView.State.FAILED)
            }
        }
    }

    /** A new session is open: automatic focus finds its subject again. */
    fun resume() {
        if (mode != Mode.AUTO) return
        handler.removeCallbacksAndMessages(null)
        racking = false
        focusNowThenHold()
    }

    fun stop() {
        walkTicket++
        handler.removeCallbacksAndMessages(null)
        racking = false
        searching = false
        gliding = false
    }

    private fun focusNowThenHold() {
        focusHereAndHold()
        handler.postDelayed({ if (mode == Mode.AUTO) watch() }, holdMs.coerceAtLeast(200))
    }

    /**
     * The held part. Nothing is touched unless sharpness has actually fallen,
     * so a static shot never moves, which is the whole complaint about
     * continuous autofocus.
     */
    private fun watch() {
        if (mode != Mode.AUTO || racking || !watching) { watchAlive = false; return }
        watchAlive = true
        // v159: never while focus is being found (a tap's search or glide): the square may have just moved
        if (searching || gliding) { handler.postDelayed({ watch() }, holdMs.coerceAtLeast(200)); return }

        Trace.state(String.format(java.util.Locale.ROOT, "square watch: sharpness %.1f, best %.1f (%.0f %%)",
            currentSharpness, reference, if (reference > 0) 100 * currentSharpness / reference else 0.0))
        if (Mechanism.focusHasDrifted(reference, currentSharpness, holdFraction)) {
            Trace.state(String.format(java.util.Locale.ROOT, "square track: sharpness %.1f of best %.1f (below %.0f %%), focus moves",
                currentSharpness, reference, holdFraction * 100))
            watchAlive = false
            onState(FocusSquareView.State.SEEKING)
            rackToNewMark()
        } else {
            // Sharpness creeps up as light changes; keep the reference honest
            // so the next comparison is against the best it has actually been.
            if (currentSharpness > reference) reference = currentSharpness
            handler.postDelayed({ watch() }, holdMs.coerceAtLeast(200))
        }
    }

    /**
     * Finds the new mark, then travels to it over the rack time.
     *
     * The camera is asked where focus should be, which is one quick search,
     * and then the lens is put back and walked there instead of left where the
     * search dropped it. At a rack time of zero that walk is a single step,
     * which is the snap somebody asked for.
     */
    /**
     * v147: one quick search for where the mark is, then back to where the lens was and a walk there at [speed] mm/s,
     * eased in and out ([Mechanism.focusTravelMs], [Mechanism.rackPosition]). A new tap takes over the walk.
     */
    private var walkTicket = 0

    private fun searchThenWalk(c: CaptureEngine, speed: Int, arrived: (Float) -> Unit) {
        val from = c.lastFocusDistance ?: lensPosition
        searching = true
        gliding = false
        val mine = ++walkTicket
        onState(FocusSquareView.State.SEEKING)
        c.focusAtNormalisedPoint(target.first, target.second) { focused ->
            searching = false
            if (mine != walkTicket) return@focusAtNormalisedPoint
            val to = c.lastFocusDistance ?: from
            if (!focused) { c.setFocusDistance(from); onState(FocusSquareView.State.FAILED); return@focusAtNormalisedPoint }
            val ms = Mechanism.focusTravelMs(from, to, speed)
            c.setFocusDistance(from)
            racking = true
            val started = android.os.SystemClock.uptimeMillis()
            handler.post(object : Runnable {
                override fun run() {
                    if (mine != walkTicket) { racking = false; return }
                    val p = ((android.os.SystemClock.uptimeMillis() - started).toFloat() / ms).coerceIn(0f, 1f)
                    c.setFocusDistance(Mechanism.rackPosition(from, to, p))
                    if (p >= 1f) { racking = false; arrived(to) } else handler.postDelayed(this, 33)
                }
            })
        }
    }

    private fun rackToNewMark() {
        val c = controls() ?: return
        if (searching) return
        // v147: at a speed, the walk is timed by the speed
        if (speedMmS > 0) { searchThenWalk(c, speedMmS) { settle(it) }; return }
        // v125: the glide, so nothing jumps there and back before the walk
        if (rampMs > 0L && c.glideFocusAtNormalisedPoint(target.first, target.second) { _ ->
                searching = false
                gliding = false
                c.lockFocusHere()
                settle(c.lastFocusDistance ?: lensPosition)
            }) {
            searching = true
            gliding = true
            return
        }
        searching = true
        val from = c.lastFocusDistance ?: lensPosition

        c.focusAtNormalisedPoint(target.first, target.second) { focused ->
            searching = false
            val to = c.lastFocusDistance ?: from
            if (!focused) {
                onState(FocusSquareView.State.IDLE)
                handler.postDelayed({ watch() }, holdMs.coerceAtLeast(200))
                return@focusAtNormalisedPoint
            }

            if (rampMs <= 0L || from == to) {
                c.setFocusDistance(to)
                settle(to)
                return@focusAtNormalisedPoint
            }

            // Back to where it was, then walk.
            c.setFocusDistance(from)
            racking = true
            val started = System.currentTimeMillis()
            val step = object : Runnable {
                override fun run() {
                    if (mode != Mode.AUTO) { racking = false; return }
                    val progress = (System.currentTimeMillis() - started).toFloat() / rampMs
                    c.setFocusDistance(Mechanism.rackPosition(from, to, progress))
                    if (progress >= 1f) {
                        racking = false
                        settle(to)
                    } else {
                        handler.postDelayed(this, 33)
                    }
                }
            }
            handler.post(step)
        }
    }

    private fun settle(distance: Float) {
        lensPosition = distance
        reference = 0.0   // v159: the next watch reads it fresh (sampled twice a second, the old value may be another place)
        onState(FocusSquareView.State.LOCKED)
        handler.postDelayed({ watch() }, holdMs.coerceAtLeast(200))
    }
}
