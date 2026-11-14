package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Test

/** v194: the meter split for stereo reads each channel on its own, never summed. */
class StereoMeterTest {

    private fun stereo(left: Short, right: Short, frames: Int): ByteArray {
        val b = ByteArray(frames * 4)
        for (f in 0 until frames) {
            b[4 * f] = left.toByte(); b[4 * f + 1] = (left.toInt() shr 8).toByte()
            b[4 * f + 2] = right.toByte(); b[4 * f + 3] = (right.toInt() shr 8).toByte()
        }
        return b
    }

    @Test fun leftAndRightAreSeparate() {
        val pcm = stereo(16384, 0, 100)
        assertEquals(0.5f, Mechanism.rmsOfPcm16Channel(pcm, pcm.size, 2, 0), 0.001f)
        assertEquals(0f, Mechanism.rmsOfPcm16Channel(pcm, pcm.size, 2, 1), 0.001f)
    }

    @Test fun rightOnly() {
        val pcm = stereo(0, -8192, 50)
        assertEquals(0f, Mechanism.rmsOfPcm16Channel(pcm, pcm.size, 2, 0), 0.001f)
        assertEquals(0.25f, Mechanism.rmsOfPcm16Channel(pcm, pcm.size, 2, 1), 0.001f)
    }

    @Test fun monoChannelZeroIsEverySample() {
        val pcm = stereo(16384, 16384, 10)    // read as mono: every sample is 0.5
        assertEquals(0.5f, Mechanism.rmsOfPcm16Channel(pcm, pcm.size, 1, 0), 0.001f)
    }
}
