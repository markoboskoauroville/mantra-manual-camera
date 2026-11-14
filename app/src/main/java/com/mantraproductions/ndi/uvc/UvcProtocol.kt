package com.mantraproductions.ndi.uvc

/**
 * v193, THE SONY AS A LENS (Marko, 14.11.2026: "the Sony camera Alpha 7 is connected and it streaming to this phone
 * Pixel 7 and I want to be able to look at what is streaming right inside my manual camera").
 *
 * The USB Video Class, the parts a camera in "USB Streaming" needs, with no Android in it so the desk tests can run
 * it. The Sony ILCE-7M5 measured on the Pixel 7 (dumpsys usb, 14.11.2026): video control on interface 2, video
 * streaming on interface 3 with ONE BULK endpoint (0x83, 1024 bytes), no alternate settings. A bulk camera needs no
 * isochronous transfers, so Android's own UsbDeviceConnection does all of it and no native libusb is needed.
 *
 * What is here: the class-specific descriptors (formats and frame sizes), the probe/commit block, and the payload
 * header that cuts the byte stream into frames.
 */
object UvcProtocol {

    enum class Kind { MJPEG, YUY2, NV12, H264, OTHER }

    /** One frame size of one format, as the camera publishes it. */
    data class Mode(
        val formatIndex: Int,
        val frameIndex: Int,
        val kind: Kind,
        val width: Int,
        val height: Int,
        /** Frame intervals in 100 ns units (333333 = 30 fps). */
        val intervals: List<Int>,
        val defaultInterval: Int
    ) {
        fun fpsOf(interval: Int): Double = if (interval > 0) 10_000_000.0 / interval else 0.0
        val label: String get() = "$kind ${width}x$height"
    }

    data class Descriptors(
        val uvcVersion: Int,              // bcdUVC, 0x0110 = 1.1
        val controlInterface: Int,
        val streamingInterface: Int,
        val endpointAddress: Int,
        val modes: List<Mode>
    )

    const val CS_INTERFACE = 0x24
    const val INTERFACE = 0x04
    const val ENDPOINT = 0x05
    const val CC_VIDEO = 0x0E
    const val SC_VIDEOCONTROL = 0x01
    const val SC_VIDEOSTREAMING = 0x02

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i) or (u16(b, i + 2) shl 16)

    /** The four bytes of a GUID that name an uncompressed format ("YUY2", "NV12" …). */
    private fun fourcc(b: ByteArray, i: Int): String = String(CharArray(4) { (b[i + it].toInt() and 0xFF).toChar() })

    /**
     * The configuration's raw descriptors (UsbDeviceConnection.getRawDescriptors, device descriptor first) read for
     * the video interfaces. Null when there is no video streaming interface.
     */
    fun parse(raw: ByteArray): Descriptors? {
        var i = 0
        var curClass = -1
        var curSub = -1
        var curIf = -1
        var uvc = 0x0100
        var vc = -1
        var vs = -1
        var ep = -1
        val modes = ArrayList<Mode>()
        var formatIndex = -1
        var kind = Kind.OTHER
        while (i + 2 <= raw.size) {
            val len = u8(raw, i)
            if (len < 2 || i + len > raw.size) break
            val type = u8(raw, i + 1)
            when (type) {
                INTERFACE -> if (len >= 9) {
                    curIf = u8(raw, i + 2)
                    curClass = u8(raw, i + 5)
                    curSub = u8(raw, i + 6)
                    if (curClass == CC_VIDEO && curSub == SC_VIDEOCONTROL && vc < 0) vc = curIf
                    if (curClass == CC_VIDEO && curSub == SC_VIDEOSTREAMING && vs < 0) vs = curIf
                }
                ENDPOINT -> if (len >= 7 && curClass == CC_VIDEO && curSub == SC_VIDEOSTREAMING && curIf == vs) {
                    val addr = u8(raw, i + 2)
                    val attr = u8(raw, i + 3) and 0x03
                    // the video data: an IN endpoint, bulk (2) or isochronous (1)
                    if (ep < 0 && (addr and 0x80) != 0 && (attr == 2 || attr == 1)) ep = addr
                }
                CS_INTERFACE -> if (len >= 3 && curClass == CC_VIDEO) {
                    val sub = u8(raw, i + 2)
                    if (curSub == SC_VIDEOCONTROL && sub == 0x01 && len >= 5) uvc = u16(raw, i + 3)
                    if (curSub == SC_VIDEOSTREAMING && curIf == vs) when (sub) {
                        0x04 -> if (len >= 27) {          // VS_FORMAT_UNCOMPRESSED
                            formatIndex = u8(raw, i + 3)
                            kind = when (fourcc(raw, i + 5)) { "YUY2" -> Kind.YUY2; "NV12" -> Kind.NV12; else -> Kind.OTHER }
                        }
                        0x06 -> if (len >= 11) { formatIndex = u8(raw, i + 3); kind = Kind.MJPEG }   // VS_FORMAT_MJPEG
                        0x10 -> if (len >= 28) {          // VS_FORMAT_FRAME_BASED
                            formatIndex = u8(raw, i + 3)
                            kind = if (fourcc(raw, i + 5) == "H264") Kind.H264 else Kind.OTHER
                        }
                        0x05, 0x07 -> if (len >= 26 && formatIndex > 0) {   // VS_FRAME_UNCOMPRESSED / _MJPEG
                            modes += frame(raw, i, len, formatIndex, kind, typeAt = 25, firstAt = 26, defaultAt = 21)
                        }
                        0x11 -> if (len >= 30 && formatIndex > 0) {         // VS_FRAME_FRAME_BASED
                            modes += frame(raw, i, len, formatIndex, kind, typeAt = 21, firstAt = 26, defaultAt = 17)
                        }
                    }
                }
            }
            i += len
        }
        if (vs < 0 || ep < 0) return null
        return Descriptors(uvc, vc, vs, ep, modes)
    }

    private fun frame(raw: ByteArray, i: Int, len: Int, format: Int, kind: Kind, typeAt: Int, firstAt: Int, defaultAt: Int): Mode {
        val count = u8(raw, i + typeAt)
        val first = i + firstAt
        val intervals = if (count == 0) {
            // continuous: min, max, step; offered as the min and the max
            listOfNotNull(
                if (first + 4 <= i + len) u32(raw, first) else null,
                if (first + 8 <= i + len) u32(raw, first + 4) else null
            )
        } else (0 until count).mapNotNull { k -> (first + 4 * k).takeIf { it + 4 <= i + len }?.let { u32(raw, it) } }
        return Mode(
            formatIndex = format, frameIndex = u8(raw, i + 3), kind = kind,
            width = u16(raw, i + 5), height = u16(raw, i + 7),
            intervals = intervals.filter { it > 0 }, defaultInterval = u32(raw, i + defaultAt)
        )
    }

    /**
     * The mode to ask for: a format the app can draw (MJPEG first: smallest on the wire, decoded by Android; then the
     * uncompressed ones), the size nearest 1920 wide without going over [maxWidth], and the interval nearest [fps].
     * Returns the mode and its chosen interval.
     */
    fun choose(modes: List<Mode>, maxWidth: Int, fps: Int): Pair<Mode, Int>? {
        val order = listOf(Kind.MJPEG, Kind.NV12, Kind.YUY2)
        for (k in order) {
            val ofKind = modes.filter { it.kind == k && it.intervals.isNotEmpty() }
            if (ofKind.isEmpty()) continue
            val fit = ofKind.filter { it.width <= maxWidth }.ifEmpty { ofKind }
            val best = fit.maxWith(compareBy<Mode>({ it.width.toLong() * it.height }, { -it.intervals.min() }))
            val wanted = 10_000_000.0 / fps.coerceAtLeast(1)
            val interval = best.intervals.minBy { kotlin.math.abs(it - wanted) }
            return best to interval
        }
        return null
    }

    // --- probe and commit (UVC 1.1 §4.3.1.1) -----------------------------------------------------------------------

    const val SET_CUR = 0x01
    const val GET_CUR = 0x81
    const val VS_PROBE = 0x01
    const val VS_COMMIT = 0x02

    /** The control block's length for a UVC version: 26 (1.0), 34 (1.1), 48 (1.5). */
    fun probeLength(uvcVersion: Int): Int = when {
        uvcVersion >= 0x0150 -> 48
        uvcVersion >= 0x0110 -> 34
        else -> 26
    }

    /** The block asked for: hint "keep the frame interval", the format, the frame, the interval. */
    fun probe(length: Int, mode: Mode, interval: Int, base: ByteArray? = null): ByteArray {
        val b = ByteArray(length)
        if (base != null) System.arraycopy(base, 0, b, 0, minOf(length, base.size))
        b[0] = 1; b[1] = 0                                     // bmHint: dwFrameInterval
        b[2] = mode.formatIndex.toByte()
        b[3] = mode.frameIndex.toByte()
        put32(b, 4, interval)
        return b
    }

    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte(); b[i + 2] = (v shr 16).toByte(); b[i + 3] = (v shr 24).toByte()
    }

    data class Negotiated(val formatIndex: Int, val frameIndex: Int, val interval: Int, val maxFrame: Int, val maxPayload: Int)

    fun negotiated(b: ByteArray): Negotiated = Negotiated(
        formatIndex = u8(b, 2), frameIndex = u8(b, 3), interval = u32(b, 4),
        maxFrame = if (b.size >= 22) u32(b, 18) else 0,
        maxPayload = if (b.size >= 26) u32(b, 22) else 0
    )

    // --- the payload header (UVC 1.1 §2.4.3.3) ---------------------------------------------------------------------

    /**
     * Cuts the payloads of one stream into frames. Each bulk transfer is one payload: a header (its length in the
     * first byte; FID, EOF and ERR bits in the second) and then picture bytes. A frame ends on EOF, or when FID
     * flips (cameras that never set EOF).
     */
    class Assembler(capacity: Int) {
        private var buf = ByteArray(capacity.coerceAtLeast(64 * 1024))
        private var size = 0
        private var fid = -1
        private var bad = false
        var frames = 0L; private set
        var dropped = 0L; private set

        /** [onFrame] gets each finished frame: the assembler's own buffer and its length, valid only during the call. */
        fun push(data: ByteArray, length: Int, onFrame: (ByteArray, Int) -> Unit) {
            if (length < 2) return
            val hle = data[0].toInt() and 0xFF
            if (hle < 2 || hle > length) return
            val bfh = data[1].toInt() and 0xFF
            val f = bfh and 0x01
            val eof = bfh and 0x02 != 0
            val err = bfh and 0x40 != 0
            if (fid >= 0 && f != fid && size > 0) finish(onFrame)     // FID flipped: the last frame is over
            fid = f
            if (err) bad = true
            val n = length - hle
            if (n > 0) {
                if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
                System.arraycopy(data, hle, buf, size, n)
                size += n
            }
            if (eof) { finish(onFrame); fid = -1 }
        }

        private fun finish(onFrame: (ByteArray, Int) -> Unit) {
            if (!bad && size > 0) { frames++; onFrame(buf, size) } else if (size > 0) dropped++
            size = 0
            bad = false
        }
    }

    /** A JPEG starts with FFD8; a payload stream cut in the wrong place does not. */
    fun looksLikeJpeg(b: ByteArray, n: Int): Boolean = n > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    // --- MJPEG without its Huffman tables -------------------------------------------------------------------------

    /**
     * Many UVC cameras leave the Huffman tables out of every MJPEG frame (the standard ones are implied) and
     * Android's decoder then refuses the frame. The standard tables (JPEG Annex K.3) as one DHT segment.
     */
    private val STANDARD_DHT: ByteArray = hex(
        "FFC401A20000010501010101010100000000000000000102030405060708090A" +
        "0B100002010303020403050504040000017D0102030004110512213141061351" +
        "6107227114328191A1082342B1C11552D1F02433627282090A161718191A2526" +
        "2728292A3435363738393A434445464748494A535455565758595A6364656667" +
        "68696A737475767778797A838485868788898A92939495969798999AA2A3A4A5" +
        "A6A7A8A9AAB2B3B4B5B6B7B8B9BAC2C3C4C5C6C7C8C9CAD2D3D4D5D6D7D8D9DA" +
        "E1E2E3E4E5E6E7E8E9EAF1F2F3F4F5F6F7F8F9FA010003010101010101010101" +
        "0000000000000102030405060708090A0B110002010204040304070504040001" +
        "0277000102031104052131061241510761711322328108144291A1B1C1092333" +
        "52F0156272D10A162434E125F11718191A262728292A35363738393A43444546" +
        "4748494A535455565758595A636465666768696A737475767778797A82838485" +
        "868788898A92939495969798999AA2A3A4A5A6A7A8A9AAB2B3B4B5B6B7B8B9BA" +
        "C2C3C4C5C6C7C8C9CAD2D3D4D5D6D7D8D9DAE2E3E4E5E6E7E8E9EAF2F3F4F5F6" +
        "F7F8F9FA"
    )

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The standard tables' own size check: 418 bytes after the marker, 12 + 162 + 12 + 162 values. */
    val standardDhtLength: Int get() = STANDARD_DHT.size

    /** [b] with the standard Huffman tables put in before the scan when it has none, or null when it has its own. */
    fun withHuffman(b: ByteArray, n: Int): ByteArray? {
        var i = 2
        while (i + 4 <= n) {
            if (b[i] != 0xFF.toByte()) return null           // not a marker where one should be: leave it to the decoder
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xC4) return null                         // it has its own
            if (m == 0xDA) {                                   // the scan: the tables go just before it
                val out = ByteArray(n + STANDARD_DHT.size)
                System.arraycopy(b, 0, out, 0, i)
                System.arraycopy(STANDARD_DHT, 0, out, i, STANDARD_DHT.size)
                System.arraycopy(b, i, out, i + STANDARD_DHT.size, n - i)
                return out
            }
            if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) { i += 2; continue }
            i += 2 + (((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF))
        }
        return null
    }

    // --- uncompressed pictures to ARGB ------------------------------------------------------------------------------

    private fun argb(y: Int, u: Int, v: Int): Int {
        // BT.601 limited range, what a UVC camera sends
        val c = (y - 16).coerceAtLeast(0) * 298
        val d = u - 128
        val e = v - 128
        val r = ((c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val bl = ((c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** YUY2 (Y0 U Y1 V) into [out], width x height. */
    fun yuy2ToArgb(src: ByteArray, width: Int, height: Int, out: IntArray) {
        var s = 0
        var o = 0
        val pairs = width * height / 2
        for (p in 0 until pairs) {
            if (s + 4 > src.size) return
            val y0 = src[s].toInt() and 0xFF
            val u = src[s + 1].toInt() and 0xFF
            val y1 = src[s + 2].toInt() and 0xFF
            val v = src[s + 3].toInt() and 0xFF
            out[o] = argb(y0, u, v); out[o + 1] = argb(y1, u, v)
            s += 4; o += 2
        }
    }

    /** NV12 (the Y plane, then U V interleaved at half size) into [out]. */
    fun nv12ToArgb(src: ByteArray, width: Int, height: Int, out: IntArray) {
        val frame = width * height
        if (src.size < frame * 3 / 2) return
        for (y in 0 until height) {
            val row = y * width
            val uvRow = frame + (y shr 1) * width
            for (x in 0 until width) {
                val uv = uvRow + (x and 1.inv())
                out[row + x] = argb(src[row + x].toInt() and 0xFF, src[uv].toInt() and 0xFF, src[uv + 1].toInt() and 0xFF)
            }
        }
    }
}
