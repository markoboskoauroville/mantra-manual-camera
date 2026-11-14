package com.mantraproductions.ndi

/**
 * SOUND LINK's jitter buffer for the Mac's sound (Sound Link (Mantra), repo SOUND_LINK). Pure Kotlin, nothing from
 * Android, so Test 1 drives it on a desk (LinkJitterTest).
 *
 * The Mac sends 48 kHz stereo 16-bit PCM in blocks (240 frames on the Wi-Fi, 160 over Tailscale), each stamped with the
 * Mac's own frame count. They land in a three-second ring at that frame count, so a late block fills its own hole.
 *
 * **v210, a decent buffer (Marko, 14.11.2026: "many dropouts when this is sending sound and lost packages. Can we build
 * some decent buffer here").**
 * - Every packet also carries the block from two packets back, in μ-law ([writeRedundant]): one lost packet, or two in a
 *   row, is filled from the next ones ([recovered]); only longer bursts are left to concealment.
 * - A hole that is still empty is concealed, never a hard click: the block before it is repeated at half level, again
 *   and again ([concealed]). A redundant copy that arrives later overwrites the concealment.
 * - The target is [baseMs] (150 ms on the Wi-Fi, 400 ms over Tailscale, set by MacLink from the Mac's address) plus
 *   3 × the arrival jitter plus 40 ms for each recent dropout; it grows at once and shrinks by 1 ms every 5 s.
 * - A dropout (the ring ran dry) fades the last sample out instead of stopping dead, and the sound fades back in.
 *
 * **The phone's audio clock is the master.** Each consumer ([Tap]) reads at the pace of its own recorder (AudioRecord, or
 * WebRTC's), steering a ratio of at most ±0.4 % by how full the ring is, linear interpolation between frames, so the Mac's
 * clock never drifts against the phone's. Both taps aim at the same target, so the take and the stream hear the Mac at
 * the same delay (LESSONS: lip-sync).
 */
class LinkJitter(val rate: Int = 48_000, ringSeconds: Int = 3) {

    val ringFrames = rate * ringSeconds
    private val ring = ShortArray(ringFrames * 2)
    /** 1 where a frame holds real sound (sent by the Mac), 0 where it is concealed or empty. */
    private val valid = ByteArray(ringFrames)
    private val lock = Any()

    /** One past the newest frame written, in the Mac's count; -1 before the first packet. */
    @Volatile var head = -1L
        private set
    private var first = -1L

    @Volatile var packets = 0L; private set
    @Volatile var lost = 0L; private set
    @Volatile var late = 0L; private set
    /** Blocks that were missing and came back through the redundant copy. */
    @Volatile var recovered = 0L; private set
    /** Blocks concealed and never recovered (what can be heard as a soft repeat). */
    @Volatile var concealed = 0L; private set
    private var lastSeq = -1L

    /** The base of the target, ms: 150 on the Wi-Fi, 400 over Tailscale (MacLink sets it). */
    @Volatile var baseMs = 150.0

    @Volatile var jitterFrames = 0.0; private set
    private var lastArrivalNs = 0L
    private var lastStamp = -1L
    private var bumpMs = 0.0

    val taps = ArrayList<Tap>()

    fun tap(name: String): Tap = synchronized(lock) { Tap(name).also { taps.add(it) } }

    fun reset() = synchronized(lock) {
        java.util.Arrays.fill(ring, 0.toShort()); java.util.Arrays.fill(valid, 0.toByte())
        head = -1L; first = -1L; packets = 0; lost = 0; late = 0; recovered = 0; concealed = 0; lastSeq = -1
        jitterFrames = 0.0; lastArrivalNs = 0; lastStamp = -1; bumpMs = 0.0
        for (t in taps) t.clear()
    }

    fun targetFrames(): Int {
        val ms = (baseMs + 3.0 * jitterFrames * 1000.0 / rate + bumpMs).coerceIn(baseMs, 900.0)
        return (ms * rate / 1000.0).toInt()
    }

    fun targetMs(): Int = targetFrames() * 1000 / rate

    private fun minTapPos(): Long? = taps.filter { it.started }.minOfOrNull { it.pos.toLong() }

    /** The main block of a packet: 16-bit little-endian PCM. */
    fun write(stamp: Long, seq: Long, pcm: ByteArray, off: Int, frames: Int, channels: Int, arrivalNs: Long) = synchronized(lock) {
        packets++
        if (lastSeq >= 0) {
            val gap = seq - lastSeq - 1
            if (gap in 1..10_000) lost += gap
        }
        if (seq > lastSeq) lastSeq = seq
        if (lastArrivalNs != 0L && stamp > lastStamp) {
            val d = (arrivalNs - lastArrivalNs) / 1e9 * rate - (stamp - lastStamp)
            jitterFrames += (Math.abs(d) - jitterFrames) / 16.0
        }
        if (stamp > lastStamp) { lastArrivalNs = arrivalNs; lastStamp = stamp }

        val end = stamp + frames
        if (head < 0 || stamp > head + ringFrames || end < head - ringFrames) {
            java.util.Arrays.fill(ring, 0.toShort()); java.util.Arrays.fill(valid, 0.toByte())
            head = stamp; first = stamp
            for (t in taps) t.restartAt(stamp)
        }
        val behind = minTapPos()
        if (behind != null && end <= behind) { late++; return@synchronized }
        if (stamp > head) conceal(head, stamp, frames)
        if (valid[idx(stamp)].toInt() == 0 && stamp < head) recoveredHole()
        var i = off
        for (k in 0 until frames) {
            val f = idx(stamp + k)
            val l = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort()
            val r = if (channels == 2) ((pcm[i + 2].toInt() and 0xff) or (pcm[i + 3].toInt() shl 8)).toShort() else l
            ring[2 * f] = l; ring[2 * f + 1] = r; valid[f] = 1
            i += 2 * channels
        }
        if (end > head) head = end
    }

    /** The redundant copy (μ-law, one byte a sample) of an older block: written only where its frames are missing. */
    fun writeRedundant(stamp: Long, ulaw: ByteArray, off: Int, frames: Int, channels: Int) = synchronized(lock) {
        if (head < 0 || stamp < 0) return@synchronized
        val end = stamp + frames
        if (end > head || stamp < head - ringFrames + rate) return@synchronized      // not yet due, or long gone
        val behind = minTapPos()
        if (behind != null && end <= behind) return@synchronized                    // already played (concealed)
        if (valid[idx(stamp)].toInt() != 0) return@synchronized                     // the main block came
        recoveredHole()
        var i = off
        for (k in 0 until frames) {
            val f = idx(stamp + k)
            val l = ulawDecode(ulaw[i]).toShort()
            val r = if (channels == 2) ulawDecode(ulaw[i + 1]).toShort() else l
            ring[2 * f] = l; ring[2 * f + 1] = r; valid[f] = 1
            i += channels
        }
    }

    private fun recoveredHole() { recovered++; if (concealed > 0) concealed-- }

    private fun idx(f: Long): Int = (((f % ringFrames) + ringFrames) % ringFrames).toInt()

    /** Missing frames [from, to): the block before repeated at half level, block after block (no click, no silence). */
    private fun conceal(from: Long, to: Long, block: Int) {
        val n = minOf(to - from, ringFrames.toLong()).toInt()
        val b = block.coerceAtLeast(1)
        for (k in 0 until n) {
            val f = idx(from + k)
            val src = idx(from + k - b)
            ring[2 * f] = (ring[2 * src] / 2).toShort(); ring[2 * f + 1] = (ring[2 * src + 1] / 2).toShort(); valid[f] = 0
        }
        concealed += (n + b - 1) / b
    }

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
        private var lastL = 0.0
        private var lastR = 0.0
        private var fadeIn = 0

        val driftPpm: Int get() = (drift * 1e6).toInt()
        val fillMs: Int get() = (fill * 1000 / rate).toInt()

        internal fun clear() { started = false; waiting = true; pos = 0.0; ratio = 1.0; smooth = 0.0; fill = 0.0; lastL = 0.0; lastR = 0.0 }
        internal fun restartAt(stamp: Long) { started = false; waiting = true; pos = stamp.toDouble(); ratio = 1.0 }

        /** The last sample faded to nothing over the buffer: a dropout is a short fade, never a click. */
        private fun fadeOut(out: ShortArray, frames: Int, channels: Int) {
            var o = 0
            for (k in 0 until frames) {
                val g = maxOf(0.0, 1.0 - k / 96.0)
                if (channels == 2) { out[o++] = (lastL * g).toInt().toShort(); out[o++] = (lastR * g).toInt().toShort() }
                else out[o++] = ((lastL + lastR) / 2 * g).toInt().toShort()
            }
            lastL = 0.0; lastR = 0.0
        }

        fun read(out: ShortArray, frames: Int, channels: Int, outRate: Int = rate): Boolean = synchronized(lock) {
            if (head < 0) return@synchronized false
            val target = targetFrames()
            val step = rate.toDouble() / outRate
            bumpMs = maxOf(0.0, bumpMs - frames.toDouble() / outRate * 0.2)        // 1 ms less every 5 s
            if (!started && waiting && pos < first) pos = first.toDouble()
            var have = head - pos
            if (waiting) {
                if (have >= target) { waiting = false; started = true; smooth = have; fadeIn = 240 }
                else { fadeOut(out, frames, channels); fill = have; return@synchronized true }
            }
            if (have > target + rate / 2 || have > ringFrames - rate / 10) {        // far too full: back to the target
                pos = (head - target).toDouble(); skips++; have = head - pos; smooth = have; fadeIn = 240
            }
            smooth += (have - smooth) * 0.02
            val err = (smooth - target) / rate
            ratio = 1.0 + (err * 0.05).coerceIn(-0.004, 0.004)
            drift += ((ratio - 1.0) - drift) * 0.002
            val inc = step * ratio
            if (have < frames * inc + 2) {                                            // dry: fade out, buffer more
                underruns++
                bumpMs = minOf(400.0, bumpMs + 40.0)
                waiting = true
                fadeOut(out, frames, channels)
                fill = have
                return@synchronized true
            }
            var p = pos
            var o = 0
            for (k in 0 until frames) {
                val i0 = Math.floor(p).toLong()
                val fr = p - i0
                val a = idx(i0)
                val b = idx(i0 + 1)
                var l = ring[2 * a] * (1 - fr) + ring[2 * b] * fr
                var r = ring[2 * a + 1] * (1 - fr) + ring[2 * b + 1] * fr
                if (fadeIn > 0) { val g = 1.0 - fadeIn / 240.0; l *= g; r *= g; fadeIn-- }
                if (channels == 2) { out[o++] = Math.round(l).toInt().toShort(); out[o++] = Math.round(r).toInt().toShort() }
                else out[o++] = Math.round((l + r) / 2).toInt().toShort()
                lastL = l; lastR = r
                p += inc
            }
            pos = p
            fill = smooth
            true
        }

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

    companion object {
        /** G.711 μ-law, as the Mac encodes it (SOUND_LINK app/Link.swift ulaw). */
        fun ulawDecode(b: Byte): Int {
            val u = b.toInt().inv() and 0xff
            val exp = (u shr 4) and 7
            val x = ((((u and 0x0f) shl 3) + 0x84) shl exp) - 0x84
            return if (u and 0x80 != 0) -x else x
        }
    }
}
