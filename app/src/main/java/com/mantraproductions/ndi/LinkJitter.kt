package com.mantraproductions.ndi

/**
 * v206, SOUND LINK: the jitter buffer for the Mac's sound (Sound Link (Mantra), repo SOUND_LINK). Pure Kotlin, nothing
 * from Android, so Test 1 drives it on a desk (LinkJitterTest).
 *
 * The Mac sends 48 kHz stereo 16-bit PCM in packets of 240 frames, each stamped with the Mac's own frame count
 * ([write]). They land in a two-second ring at that frame count, so a late packet fills its own hole and a lost one
 * leaves silence of exactly its length; nothing after it moves.
 *
 * **The phone's audio clock is the master.** Each consumer ([Tap]) reads at its own pace, the pace of the AudioRecord
 * or WebRTC recorder that calls it, which runs on the phone's audio clock. The Mac's crystal and the phone's differ by
 * tens of ppm, enough to run a fixed buffer dry or full within minutes. So each tap watches how full the ring is ahead
 * of it and reads a hair faster or slower ([Tap.ratio], at most ±0.4 %, inaudible), linear interpolation between
 * frames. At rest the ratio is the drift between the two clocks ([Tap.driftPpm]).
 *
 * **Adaptive target.** The fill a tap aims for is 40 ms + 3 × the measured arrival jitter (RFC 3550 style), plus 10 ms
 * for each recent dropout (decaying), at most 160 ms. A dropout (the ring ran dry) gives silence and waits until the
 * target is there again; a fill far above the target (a burst after Wi-Fi power save) jumps back to the target.
 *
 * Both taps aim for the same target, so the take and the stream hear the Mac at the same delay (LESSONS: lip-sync).
 */
class LinkJitter(val rate: Int = 48_000, ringSeconds: Int = 2) {

    val ringFrames = rate * ringSeconds
    private val ring = ShortArray(ringFrames * 2)
    private val lock = Any()

    /** One past the newest frame written, in the Mac's count; -1 before the first packet. */
    @Volatile var head = -1L
        private set
    private var first = -1L

    @Volatile var packets = 0L; private set
    @Volatile var lost = 0L; private set
    @Volatile var late = 0L; private set
    private var lastSeq = -1L

    /** Arrival jitter in frames (smoothed, 1/16 as RFC 3550). */
    @Volatile var jitterFrames = 0.0; private set
    private var lastArrivalNs = 0L
    private var lastStamp = -1L
    private var bumpMs = 0.0

    val taps = ArrayList<Tap>()

    fun tap(name: String): Tap = synchronized(lock) { Tap(name).also { taps.add(it) } }

    fun reset() = synchronized(lock) {
        java.util.Arrays.fill(ring, 0.toShort())
        head = -1L; first = -1L; packets = 0; lost = 0; late = 0; lastSeq = -1
        jitterFrames = 0.0; lastArrivalNs = 0; lastStamp = -1; bumpMs = 0.0
        for (t in taps) t.clear()
    }

    /** The fill the taps aim for, in frames. */
    fun targetFrames(): Int {
        val ms = (40.0 + 3.0 * jitterFrames * 1000.0 / rate + bumpMs).coerceIn(40.0, 160.0)
        return (ms * rate / 1000.0).toInt()
    }

    fun targetMs(): Int = targetFrames() * 1000 / rate

    /**
     * One packet: [frames] frames of 16-bit little-endian PCM at [off] in [pcm], [channels] 1 or 2, stamped [stamp]
     * (the Mac's frame count), sequence [seq], arrived at [arrivalNs] (any monotonic clock).
     */
    fun write(stamp: Long, seq: Long, pcm: ByteArray, off: Int, frames: Int, channels: Int, arrivalNs: Long) = synchronized(lock) {
        packets++
        if (lastSeq >= 0) {
            val gap = seq - lastSeq - 1
            if (gap in 1..10_000) lost += gap
            else if (seq <= lastSeq && lost > 0) lost--      // it came after all, only late
        }
        if (seq > lastSeq) lastSeq = seq
        if (lastArrivalNs != 0L && stamp > lastStamp) {
            val d = (arrivalNs - lastArrivalNs) / 1e9 * rate - (stamp - lastStamp)
            jitterFrames += (Math.abs(d) - jitterFrames) / 16.0
        }
        if (stamp > lastStamp) { lastArrivalNs = arrivalNs; lastStamp = stamp }

        val end = stamp + frames
        if (head < 0 || stamp > head + ringFrames || end < head - ringFrames) {
            // the first packet, or the Mac started counting again: start over
            java.util.Arrays.fill(ring, 0.toShort())
            head = stamp; first = stamp
            for (t in taps) t.restartAt(stamp)
        }
        if (taps.any { it.started } && end <= taps.filter { it.started }.minOf { it.pos.toLong() }) { late++; return@synchronized }
        if (stamp > head) zero(head, stamp)
        var i = off
        for (k in 0 until frames) {
            val at = (((stamp + k) % ringFrames) * 2).toInt()
            val l = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort()
            val r = if (channels == 2) ((pcm[i + 2].toInt() and 0xff) or (pcm[i + 3].toInt() shl 8)).toShort() else l
            ring[at] = l; ring[at + 1] = r
            i += 2 * channels
        }
        if (end > head) head = end
    }

    private fun zero(from: Long, to: Long) {
        var f = from
        val stop = minOf(to, from + ringFrames)
        while (f < stop) { val at = ((f % ringFrames) * 2).toInt(); ring[at] = 0; ring[at + 1] = 0; f++ }
    }

    /** One reader of the ring with its own position and its own drift steering. */
    inner class Tap(val name: String) {
        @Volatile var started = false; internal set
        var pos = 0.0; internal set
        @Volatile var ratio = 1.0; private set
        private var smooth = 0.0
        private var drift = 0.0
        @Volatile var underruns = 0L; private set
        @Volatile var skips = 0L; private set
        /** Frames waiting ahead of this tap, smoothed over about a second (what it steers on). */
        @Volatile var fill = 0.0; private set
        private var waiting = true
        private var scratch = ShortArray(0)

        val driftPpm: Int get() = (drift * 1e6).toInt()
        val fillMs: Int get() = (fill * 1000 / rate).toInt()

        internal fun clear() { started = false; waiting = true; pos = 0.0; ratio = 1.0; smooth = 0.0; fill = 0.0 }
        internal fun restartAt(stamp: Long) { started = false; waiting = true; pos = stamp.toDouble(); ratio = 1.0 }

        /**
         * Fills [out] with [frames] frames of [channels] (1 = L+R mixed, 2 = stereo) at [outRate], timed by the caller.
         * @return false when no Mac sound has arrived yet (then [out] is left as it was).
         */
        fun read(out: ShortArray, frames: Int, channels: Int, outRate: Int = rate): Boolean = synchronized(lock) {
            if (head < 0) return@synchronized false
            val target = targetFrames()
            val step = rate.toDouble() / outRate
            bumpMs = maxOf(0.0, bumpMs - frames.toDouble() / outRate * 0.1)     // 10 ms of extra buffer fade in 100 s
            if (!started && waiting && pos < first) pos = first.toDouble()
            var have = head - pos
            if (waiting) {
                if (have >= target) { waiting = false; started = true; smooth = have }
                else { java.util.Arrays.fill(out, 0, frames * channels, 0); fill = have; return@synchronized true }
            }
            if (have > target + rate / 4 || have > ringFrames - rate / 10) {      // a burst: back to the target
                pos = (head - target).toDouble(); skips++; have = head - pos; smooth = have
            }
            smooth += (have - smooth) * 0.02
            val err = (smooth - target) / rate                                        // seconds too full (+) or too empty (-)
            ratio = 1.0 + (err * 0.05).coerceIn(-0.004, 0.004)
            drift += ((ratio - 1.0) - drift) * 0.002
            val inc = step * ratio
            if (have < frames * inc + 2) {                                            // dry: silence, buffer again
                underruns++
                bumpMs = minOf(80.0, bumpMs + 10.0)
                waiting = true
                java.util.Arrays.fill(out, 0, frames * channels, 0)
                fill = have
                return@synchronized true
            }
            var p = pos
            var o = 0
            for (k in 0 until frames) {
                val i0 = Math.floor(p).toLong()
                val fr = p - i0
                val a = ((i0 % ringFrames) * 2).toInt()
                val b = (((i0 + 1) % ringFrames) * 2).toInt()
                val l = ring[a] * (1 - fr) + ring[b] * fr
                val r = ring[a + 1] * (1 - fr) + ring[b + 1] * fr
                if (channels == 2) { out[o++] = Math.round(l).toInt().toShort(); out[o++] = Math.round(r).toInt().toShort() }
                else out[o++] = Math.round((l + r) / 2).toInt().toShort()
                p += inc
            }
            pos = p
            fill = smooth            // the level it steers on (before each read), not the dip right after one
            true
        }

        /** The same into 16-bit little-endian bytes, as AudioRecord buffers are. */
        fun readBytes(out: ByteArray, bytes: Int, channels: Int, outRate: Int = rate): Boolean {
            val frames = bytes / (2 * channels)
            if (scratch.size < frames * channels) scratch = ShortArray(frames * channels)
            if (!read(scratch, frames, channels, outRate)) return false
            var i = 0
            for (k in 0 until frames * channels) {
                val s = scratch[k].toInt()
                out[i++] = (s and 0xff).toByte(); out[i++] = (s shr 8).toByte()
            }
            return true
        }
    }
}
