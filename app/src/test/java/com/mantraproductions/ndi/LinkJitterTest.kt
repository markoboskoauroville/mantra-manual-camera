package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** v206 SOUND LINK: the jitter buffer keeps two clocks apart without ever running dry or slowly filling up. */
class LinkJitterTest {

    private fun packet(frames: Int, value: Short): ByteArray {
        val b = ByteArray(frames * 4)
        for (f in 0 until frames) {
            b[4 * f] = value.toByte(); b[4 * f + 1] = (value.toInt() shr 8).toByte()
            b[4 * f + 2] = value.toByte(); b[4 * f + 3] = (value.toInt() shr 8).toByte()
        }
        return b
    }

    /**
     * Two simulated minutes: the Mac's clock 150 ppm fast, packets of 240 frames arriving with up to 15 ms of jitter,
     * the phone reading 960 frames (20 ms) at a time on its own clock. After the start, no dropout, the fill stays near
     * the target, and the tap's ratio has found the drift.
     */
    @Test fun twoMinutesOfDriftAndJitter() {
        val j = LinkJitter()
        val tap = j.tap("meter")
        val rnd = Random(7)
        val macRate = 48_000 * (1 + 150e-6)
        val pcm = packet(240, 1000)
        val out = ShortArray(960 * 2)
        var stamp = 0L
        var seq = 0L
        var nextSendNs = 0.0
        var nextReadNs = 0.0
        val pending = java.util.PriorityQueue<Pair<Double, Long>>(compareBy { it.first })
        var nowNs = 0.0
        val endNs = 120e9
        var underrunsAfterStart = -1L
        var maxFillMs = 0
        while (nowNs < endNs) {
            // the next event: a send, an arrival or a read
            val nextArrival = pending.peek()?.first ?: Double.MAX_VALUE
            nowNs = minOf(nextSendNs, nextArrival, nextReadNs)
            if (nowNs == nextSendNs) {
                pending.add(Pair(nowNs + 2e6 + rnd.nextDouble() * 15e6, stamp))
                stamp += 240; nextSendNs += 240 / macRate * 1e9
            } else if (nowNs == nextArrival) {
                val (_, st) = pending.poll()!!
                j.write(st, st / 240, pcm, 0, 240, 2, nowNs.toLong())
                seq++
            } else {
                tap.read(out, 960, 2)
                if (nowNs > 5e9) {
                    if (underrunsAfterStart < 0) underrunsAfterStart = tap.underruns
                    maxFillMs = maxOf(maxFillMs, tap.fillMs)
                }
                nextReadNs += 960 / 48_000.0 * 1e9
            }
        }
        assertEquals("no dropout after the first seconds", underrunsAfterStart, tap.underruns)
        assertTrue("fill ${tap.fillMs} ms near target ${j.targetMs()}", Math.abs(tap.fillMs - j.targetMs()) < 25)
        assertTrue("never far above the target: $maxFillMs", maxFillMs < j.targetMs() + 60)
        assertTrue("drift found: ${tap.driftPpm} ppm", tap.driftPpm in 80..220)
        assertEquals(1000.toShort(), out[0])
    }

    /** v210: a lost packet is concealed (the block before at half level), never a click; nothing after it moves. */
    @Test fun lostPacketIsCountedAndConcealed() {
        val j = LinkJitter()
        j.baseMs = 40.0
        val tap = j.tap("meter")
        val pcm = packet(240, 500)
        for (k in 0 until 40) if (k != 20) j.write(k * 240L, k.toLong(), pcm, 0, 240, 2, k * 5_000_000L)
        assertEquals(1L, j.lost)
        val out = ShortArray(240 * 40 * 2)
        assertTrue(tap.read(out, 240 * 30, 2))
        // the tap starts target frames behind the head, so the hole lands inside what it read
        val half = (0 until 240 * 30).count { out[2 * it] == 250.toShort() }
        assertTrue("about one packet concealed at half level, not $half", half in 200..260)
        assertEquals(1L, j.concealed)
    }

    private fun ulaw(sample: Int): Byte {
        var x = sample; val sign = if (x < 0) { x = -x; 0x80 } else 0
        if (x > 32635) x = 32635
        x += 0x84
        var exp = 7; var mask = 0x4000
        while (exp > 0 && (x and mask) == 0) { exp--; mask = mask shr 1 }
        val mant = (x shr (exp + 3)) and 0x0f
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    /** v210: the redundant copy of a lost block fills its hole before it is played. */
    @Test fun redundantCopyRecoversALostBlock() {
        val j = LinkJitter()
        j.baseMs = 40.0
        val tap = j.tap("meter")
        val pcm = packet(240, 500)
        val red = ByteArray(480) { ulaw(500) }
        for (k in 0 until 40) {
            if (k != 20) j.write(k * 240L, k.toLong(), pcm, 0, 240, 2, k * 5_000_000L)
            if (k >= 2) j.writeRedundant((k - 2) * 240L, red, 0, 240, 2)
        }
        assertEquals(1L, j.lost)
        assertEquals(1L, j.recovered)
        assertEquals(0L, j.concealed)
        val out = ShortArray(240 * 40 * 2)
        assertTrue(tap.read(out, 240 * 30, 2))
        val off = (240 until 240 * 30).count { Math.abs(out[2 * it] - 500) > 20 }   // after the 5 ms fade-in
        assertTrue("the hole is filled, not concealed: $off off", off < 5)
        assertNear(500, LinkJitter.ulawDecode(ulaw(500)), 16.0)
    }

    private fun assertNear(expected: Int, actual: Int, delta: Double) = org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta)

    /** Mono readers get left and right mixed. */
    @Test fun monoReaderGetsTheMix() {
        val j = LinkJitter()
        j.baseMs = 40.0
        val tap = j.tap("meter")
        val b = ByteArray(240 * 4)
        for (f in 0 until 240) { b[4 * f] = 0xe8.toByte(); b[4 * f + 1] = 0x03; b[4 * f + 2] = 0; b[4 * f + 3] = 0 }   // L 1000, R 0
        for (k in 0 until 20) j.write(k * 240L, k.toLong(), b, 0, 240, 2, k * 5_000_000L)
        val out = ShortArray(480)
        assertTrue(tap.read(out, 480, 1))
        assertEquals(500.toShort(), out[400])      // after the 5 ms fade-in
    }

    /**
     * v216: the Wi-Fi stops for 600 ms and then delivers everything at once (15.11.2026, the 2.4 GHz network). The first
     * stop costs a hole; the target then covers the stop, so a second stop of the same size passes without one, and
     * after the first the fill is back near the target at once (no minute of 0.4 % fast playing).
     */
    @Test fun secondStallIsBridged() {
        val j = LinkJitter()
        val tap = j.tap("meter")
        val pcm = packet(240, 1000)
        val out = ShortArray(960 * 2)
        var stamp = 0L
        var nextSendNs = 0.0
        var nextReadNs = 0.0
        var nowNs = 0.0
        val held = ArrayList<Long>()
        val stalls = listOf(20e9 to 20.6e9, 60e9 to 60.6e9)
        var underrunsAt30 = -1L
        var fillAt25 = 0
        while (nowNs < 90e9) {
            nowNs = minOf(nextSendNs, nextReadNs)
            if (nowNs == nextSendNs) {
                val stalled = stalls.any { nowNs >= it.first && nowNs < it.second }
                if (stalled) held.add(stamp) else {
                    for (h in held) j.write(h, h / 240, pcm, 0, 240, 2, nowNs.toLong())
                    held.clear()
                    j.write(stamp, stamp / 240, pcm, 0, 240, 2, nowNs.toLong())
                }
                stamp += 240; nextSendNs += 240 / 48_000.0 * 1e9
            } else {
                tap.read(out, 960, 2)
                if (underrunsAt30 < 0 && nowNs > 30e9) underrunsAt30 = tap.underruns
                if (fillAt25 == 0 && nowNs > 25e9) fillAt25 = tap.fillMs
                nextReadNs += 960 / 48_000.0 * 1e9
            }
        }
        assertTrue("the first stop is a hole", underrunsAt30 >= 1)
        assertEquals("the second stop is bridged", underrunsAt30, tap.underruns)
        assertTrue("stall remembered: ${j.stallMs}", j.stallMs > 500)
        assertTrue("fill $fillAt25 ms near the target ${j.targetMs()} soon after the first stop", Math.abs(fillAt25 - j.targetMs()) < 60)
    }
}
