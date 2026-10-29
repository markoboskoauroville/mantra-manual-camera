package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerMonitorTest {
    private val C = PowerMonitor.Calc

    @Test fun unitsBySize() {
        assertEquals(-850, C.toMilliamps(-850_000))     // µA (Pixel)
        assertEquals(-850, C.toMilliamps(-850))         // mA (some phones)
        assertEquals(2100, C.toMilliamps(2_100_000))
    }

    @Test fun slopeOfTheCounter() {
        // 10 minutes, 1 000 mA out of the cell: 166.7 mAh less
        val s = (0..600 step 10).map { t -> t * 1000L to (3_000_000L - t * 1_000_000L / 3600) }
        assertEquals(-1000.0, C.slopeMa(s, 120_000, 2_000)!!, 5.0)
        assertNull(C.slopeMa(s.take(5), 120_000, 2_000))   // 40 s: not yet
    }

    @Test fun stillCounterIsBalanced() {
        val s = (0..300 step 10).map { t -> t * 1000L to 3_000_000L }
        assertEquals(0.0, C.slopeMa(s, 120_000, 2_000)!!, 0.01)
    }

    @Test fun timeToEmpty() {
        assertEquals(3 * 3600L, C.secondsToEmpty(3000.0, 1000.0))  // 3000 mAh at 1 A
        assertNull(C.secondsToEmpty(3000.0, 0.0))
    }

    @Test fun timeToFullSlowsAbove80() {
        // 4000 mAh cell at 50 %: 1200 mAh to 80 % at 2 A (36 min), 800 mAh above at 1 A (48 min)
        assertEquals((0.6 * 3600 + 0.8 * 3600).toLong(), C.secondsToFull(50, 2000.0, 2000.0))
    }

    @Test fun implausibleCounter() {
        assertEquals(false, C.plausible(10_000, 64))        // the emulator: 10 mAh at 64 %
        assertEquals(true, C.plausible(2_700_000, 64))      // a Pixel 7: about 4 200 mAh full
    }

    @Test fun format() {
        assertEquals("3:42", C.format(3 * 3600L + 42 * 60))
        assertEquals("0:07", C.format(7 * 60L))
        assertEquals("--:--", C.format(null))
    }
}
