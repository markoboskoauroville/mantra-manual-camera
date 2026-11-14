package com.mantraproductions.ndi

import com.mantraproductions.ndi.uvc.UvcProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v193: the USB Video Class parts the Sony as a lens needs, on a desk. */
class UvcProtocolTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun le16(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF)
    private fun le32(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v shr 24) and 0xFF)

    /** Shaped like the Sony ILCE-7M5 measured on 14.11.2026: VC on 2, VS on 3 with a bulk endpoint 0x83. */
    private fun sonyLike(): ByteArray {
        val out = ArrayList<Int>()
        out += listOf(18, 1, 0, 2, 0xEF, 2, 1, 64, 0x4C, 0x05, 0x51, 0x0F, 0, 1, 1, 2, 3, 1)        // device
        out += listOf(9, 2, 0, 0, 4, 1, 0, 0x80, 112)                                               // configuration
        out += listOf(9, 4, 2, 0, 1, 0x0E, 1, 0, 0)                                                 // VC interface
        out += listOf(13, 0x24, 1) + le16(0x0110).toList() + le16(13).toList() + le32(48_000_000).toList() + listOf(1, 3)
        out += listOf(7, 5, 0x82, 3, 16, 0, 8)                                                      // interrupt
        out += listOf(9, 4, 3, 0, 1, 0x0E, 2, 0, 0)                                                 // VS interface
        out += listOf(11, 0x24, 6, 1, 1, 0, 1, 0, 0, 0, 0)                                          // MJPEG format 1
        out += listOf(34, 0x24, 7, 1, 0) + le16(1920).toList() + le16(1080).toList() +
            le32(1).toList() + le32(2).toList() + le32(4_147_200).toList() + le32(333_333).toList() + listOf(2) +
            le32(333_333).toList() + le32(166_666).toList()
        out += listOf(30, 0x24, 7, 2, 0) + le16(1280).toList() + le16(720).toList() +
            le32(1).toList() + le32(2).toList() + le32(1_843_200).toList() + le32(333_333).toList() + listOf(1) +
            le32(333_333).toList()
        out += listOf(7, 5, 0x83, 2, 0x00, 0x04, 0)                                                 // bulk IN, 1024
        return ByteArray(out.size) { out[it].toByte() }
    }

    @Test fun parsesTheSonyShape() {
        val d = UvcProtocol.parse(sonyLike())
        assertNotNull(d)
        d!!
        assertEquals(0x0110, d.uvcVersion)
        assertEquals(2, d.controlInterface)
        assertEquals(3, d.streamingInterface)
        assertEquals(0x83, d.endpointAddress)
        assertEquals(2, d.modes.size)
        val m = d.modes[0]
        assertEquals(UvcProtocol.Kind.MJPEG, m.kind)
        assertEquals(1920, m.width); assertEquals(1080, m.height)
        assertEquals(listOf(333_333, 166_666), m.intervals)
        assertEquals(333_333, m.defaultInterval)
    }

    @Test fun noVideoStreamingIsNull() {
        assertNull(UvcProtocol.parse(b(18, 1, 0, 2, 0, 0, 0, 64, 0, 0, 0, 0, 0, 1, 1, 2, 3, 1)))
    }

    @Test fun choosesTheBiggestMjpegNearTheFps() {
        val d = UvcProtocol.parse(sonyLike())!!
        val (m, interval) = UvcProtocol.choose(d.modes, 1920, 50)!!
        assertEquals(1920, m.width)
        assertEquals(166_666, interval)
        val (m2, i2) = UvcProtocol.choose(d.modes, 1280, 25)!!
        assertEquals(1280, m2.width)
        assertEquals(333_333, i2)
    }

    @Test fun probeBlockCarriesTheMode() {
        val d = UvcProtocol.parse(sonyLike())!!
        val p = UvcProtocol.probe(UvcProtocol.probeLength(d.uvcVersion), d.modes[0], 333_333)
        assertEquals(34, p.size)
        assertEquals(1, p[0].toInt()); assertEquals(1, p[2].toInt()); assertEquals(1, p[3].toInt())
        val n = UvcProtocol.negotiated(p)
        assertEquals(333_333, n.interval)
        assertEquals(26, UvcProtocol.probeLength(0x0100))
        assertEquals(48, UvcProtocol.probeLength(0x0150))
    }

    @Test fun assemblerCutsOnEofAndOnFid() {
        val a = UvcProtocol.Assembler(16)
        val frames = ArrayList<ByteArray>()
        val sink: (ByteArray, Int) -> Unit = { f, n -> frames += f.copyOf(n) }
        a.push(b(2, 0x80, 1, 2), 4, sink)
        a.push(b(2, 0x82, 3), 3, sink)            // EOF
        assertEquals(1, frames.size)
        assertArrayEquals(b(1, 2, 3), frames[0])
        a.push(b(2, 0x81, 7), 3, sink)            // FID 1, no EOF
        a.push(b(2, 0x80, 9), 3, sink)            // FID flips: the frame before is over
        assertEquals(2, frames.size)
        assertArrayEquals(b(7), frames[1])
        a.push(b(2, 0xC2, 5), 3, sink)            // ERR with the EOF of the FID-0 frame: dropped
        assertEquals(2, frames.size)
        assertEquals(1L, a.dropped)
    }

    @Test fun standardHuffmanTablesAreWhole() {
        assertEquals(420, UvcProtocol.standardDhtLength)
    }

    @Test fun huffmanGoesBeforeTheScanOnlyWhenMissing() {
        val bare = b(0xFF, 0xD8, 0xFF, 0xDB, 0, 4, 1, 2, 0xFF, 0xDA, 0, 2, 9, 0xFF, 0xD9)
        val fixed = UvcProtocol.withHuffman(bare, bare.size)!!
        assertEquals(bare.size + 420, fixed.size)
        assertEquals(0xC4, fixed[9].toInt() and 0xFF)
        assertEquals(0xDA, fixed[8 + 420 + 1].toInt() and 0xFF)
        val own = b(0xFF, 0xD8, 0xFF, 0xC4, 0, 2, 0xFF, 0xDA, 0, 2)
        assertNull(UvcProtocol.withHuffman(own, own.size))
    }

    @Test fun yuy2GreyIsGrey() {
        val out = IntArray(2)
        UvcProtocol.yuy2ToArgb(b(126, 128, 126, 128), 2, 1, out)
        val r = (out[0] shr 16) and 0xFF; val g = (out[0] shr 8) and 0xFF; val bl = out[0] and 0xFF
        assertTrue(r == g && g == bl && r in 120..135)
    }
}
