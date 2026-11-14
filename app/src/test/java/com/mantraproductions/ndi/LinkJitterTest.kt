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

    /** A lost packet is silence of exactly its length; nothing after it moves. */
    @Test fun lostPacketIsCountedAndSilent() {
        val j = LinkJitter()
        val tap = j.tap("meter")
        val pcm = packet(240, 500)
        for (k in 0 until 40) if (k != 20) j.write(k * 240L, k.toLong(), pcm, 0, 240, 2, k * 5_000_000L)
        assertEquals(1L, j.lost)
        val out = ShortArray(240 * 40 * 2)
        assertTrue(tap.read(out, 240 * 30, 2))
        // the tap starts target frames behind the head, so the hole lands inside what it read
        val silent = (0 until 240 * 30).count { out[2 * it] == 0.toShort() }
        assertTrue("about one packet of silence, not $silent", silent in 200..260)
    }

    /** Mono readers get left and right mixed. */
    @Test fun monoReaderGetsTheMix() {
        val j = LinkJitter()
        val tap = j.tap("meter")
        val b = ByteArray(240 * 4)
        for (f in 0 until 240) { b[4 * f] = 0xe8.toByte(); b[4 * f + 1] = 0x03; b[4 * f + 2] = 0; b[4 * f + 3] = 0 }   // L 1000, R 0
        for (k in 0 until 20) j.write(k * 240L, k.toLong(), b, 0, 240, 2, k * 5_000_000L)
        val out = ShortArray(480)
        assertTrue(tap.read(out, 480, 1))
        assertEquals(500.toShort(), out[100])
    }
}
