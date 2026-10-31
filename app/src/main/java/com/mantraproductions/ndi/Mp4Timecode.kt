package com.mantraproductions.ndi

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * THE TIME CODE IN THE FILE (v187, Marko, 31.10.2026: "in the files there is written real time code as a free run time
 * code. So every of our files have saved in timecode").
 *
 * MediaMuxer cannot write a time code track, so one is added once the muxer has closed the take: a QuickTime `tmcd`
 * track (the shape ffmpeg's `-timecode` writes, which Resolve, Premiere and ffprobe read), its one sample the frame
 * number since midnight of the take's first frame, and a `tref/tmcd` from the picture to it. Free run: the time of day
 * from the TC generator (the phone's clock plus the offset TC JAM took from a Sony's screen), so the phone's takes
 * line up with the Sonys'.
 *
 * Nothing already in the file moves: the sample and the new `moov` are appended, then the old `moov` is renamed `free`.
 * Every chunk offset of the picture and the sound still points where it did. Pure, so the Mac's tests run it on real
 * MP4s and ffprobe reads the result (Mp4TimecodeTest).
 */
object Mp4Timecode {

    /** Random access to the file the muxer wrote: the descriptor on the phone, a RandomAccessFile in the tests. */
    interface Io {
        fun size(): Long
        fun read(at: Long, n: Int): ByteArray
        fun write(at: Long, bytes: ByteArray)
    }

    /** Frame number since midnight for a time of day in milliseconds, at a whole frame rate. */
    fun frameOfDay(timeOfDayMs: Long, fps: Int): Long {
        val day = ((timeOfDayMs % 86_400_000L) + 86_400_000L) % 86_400_000L
        return day / 1000 * fps + (day % 1000) * fps / 1000
    }

    fun label(frame: Long, fps: Int): String {
        val s = frame / fps
        return "%02d:%02d:%02d:%02d".format(s / 3600 % 24, s / 60 % 60, s % 60, frame % fps)
    }

    private class Box(val type: String, val at: Long, val size: Long, val header: Int)

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = ByteBuffer.wrap(b, p + 8, 8).long; header = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) break
            out += Box(type, p.toLong(), size, header)
            p += size.toInt()
        }
        return out
    }

    private fun u32(b: ByteArray, at: Int): Long = ByteBuffer.wrap(b, at, 4).int.toLong() and 0xFFFFFFFFL

    private fun child(b: ByteArray, parent: Box, type: String): Box? =
        boxes(b, (parent.at + parent.header).toInt(), (parent.at + parent.size).toInt()).firstOrNull { it.type == type }

    private fun bytes(b: ByteArray, box: Box) = b.copyOfRange(box.at.toInt(), (box.at + box.size).toInt())

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    }

    private fun ints(vararg v: Int) = ByteBuffer.allocate(4 * v.size).apply { v.forEach { putInt(it) } }.array()

    /**
     * Adds the time code track to the MP4 behind [io]; [firstFrameOfDay] is the take's first frame, counted from
     * midnight at [fps]. @return the time code written, or the reason it was not.
     */
    fun write(io: Io, firstFrameOfDay: Long, fps: Int): Result<String> = runCatching {
        require(fps in 1..255) { "frame rate $fps" }
        val end = io.size()
        // the top level: ftyp, free, mdat, moov in some order; only their headers are read
        var p = 0L
        var moovAt = -1L; var moovSize = 0L
        while (p + 8 <= end) {
            val h = io.read(p, 16.coerceAtMost((end - p).toInt()))
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) size = ByteBuffer.wrap(h, 8, 8).long
            if (size == 0L) size = end - p
            if (size < 8) error("broken box at $p")
            if (type == "moov") { moovAt = p; moovSize = size }
            p += size
        }
        require(moovAt >= 0) { "no moov" }
        require(moovSize < 64L shl 20) { "moov of $moovSize bytes" }
        val m = io.read(moovAt, moovSize.toInt())
        val moov = Box("moov", 0, moovSize, 8)
        val kids = boxes(m, 8, m.size)
        require(kids.none { t -> t.type == "trak" && handler(m, t) == "tmcd" }) { "already has a time code track" }
        val mvhd = kids.first { it.type == "mvhd" }
        val video = kids.firstOrNull { it.type == "trak" && handler(m, it) == "vide" } ?: error("no picture track")

        // mvhd: next_track_ID is its last four bytes; the time code track takes it
        val mvhdBytes = bytes(m, mvhd)
        val tcId = ByteBuffer.wrap(mvhdBytes, mvhdBytes.size - 4, 4).int
        ByteBuffer.wrap(mvhdBytes).putInt(mvhdBytes.size - 4, tcId + 1)

        // the picture's length: in the movie's time scale (tkhd), and in frames (mdhd)
        val tkhd = child(m, video, "tkhd")!!
        val tv = m[tkhd.at.toInt() + 8].toInt()
        val movieDuration = if (tv == 1) ByteBuffer.wrap(m, tkhd.at.toInt() + 8 + 4 + 8 + 8 + 4 + 4, 8).long
            else u32(m, tkhd.at.toInt() + 8 + 4 + 4 + 4 + 4 + 4)
        val mdhd = child(m, child(m, video, "mdia")!!, "mdhd")!!
        val mv = m[mdhd.at.toInt() + 8].toInt()
        val (vScale, vDur) = if (mv == 1) u32(m, mdhd.at.toInt() + 8 + 4 + 16) to ByteBuffer.wrap(m, mdhd.at.toInt() + 8 + 4 + 20, 8).long
            else u32(m, mdhd.at.toInt() + 8 + 4 + 8) to u32(m, mdhd.at.toInt() + 8 + 4 + 12)
        val frames = maxOf(1L, Math.round(vDur.toDouble() * fps / maxOf(1L, vScale)))

        // the picture's trak, with a tref to the time code after its tkhd (an old tref is replaced)
        val vKids = boxes(m, (video.at + video.header).toInt(), (video.at + video.size).toInt())
        val tref = box("tref", box("tmcd", ints(tcId)))
        val newVideo = box("trak", *vKids.flatMap { k ->
            when (k.type) { "tref" -> emptyList(); "tkhd" -> listOf(bytes(m, k), tref); else -> listOf(bytes(m, k)) }
        }.toTypedArray())

        // the sample, at the end of the file in its own mdat: then the new moov after it
        val sampleAt = end + 8
        val scale = fps * 1000
        val identity = ints(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
        val tcTkhd = box("tkhd", ints(0x00000002, 0, 0, tcId, 0, movieDuration.toInt()), ByteArray(8),
            ByteArray(8), identity, ints(0, 0))
        val tcEntry = box("tmcd", ByteArray(6), byteArrayOf(0, 1), ints(0, 0, scale, 1000), byteArrayOf(fps.toByte(), 0))
        val stbl = box("stbl",
            box("stsd", ints(0, 1), tcEntry),
            box("stts", ints(0, 1, 1, (frames * 1000).toInt())),
            box("stsc", ints(0, 1, 1, 1, 1)),
            box("stsz", ints(0, 4, 1)),
            box("stco", ints(0, 1, sampleAt.toInt())))
        require(sampleAt < 0xFFFFFFFFL) { "a take over 4 GB needs co64" }
        val minf = box("minf", box("nmhd", ints(0)),
            box("dinf", box("dref", ints(0, 1), box("url ", ints(1)))), stbl)
        val mdia = box("mdia",
            box("mdhd", ints(0, 0, 0, scale, (frames * 1000).toInt()), byteArrayOf(0x55, 0xC4.toByte(), 0, 0)),
            box("hdlr", ints(0, 0), "tmcd".toByteArray(), ints(0, 0, 0), "TimeCodeHandler".toByteArray(), byteArrayOf(0)),
            minf)
        val tcTrak = box("trak", tcTkhd, mdia)

        // the new moov: mvhd with the next id, the picture with its tref, every other box as it was, the time code
        // track after the last trak
        val lastTrak = kids.last { it.type == "trak" }
        val newMoov = box("moov", *kids.flatMap { k ->
            val one = when { k === mvhd -> mvhdBytes; k === video -> newVideo; else -> bytes(m, k) }
            if (k === lastTrak) listOf(one, tcTrak) else listOf(one)
        }.toTypedArray())

        io.write(end, box("mdat", ints(firstFrameOfDay.toInt())) + newMoov)
        io.write(moovAt + 4, "free".toByteArray())
        label(firstFrameOfDay, fps)
    }

    private fun handler(b: ByteArray, trak: Box): String? {
        val mdia = child(b, trak, "mdia") ?: return null
        val hdlr = child(b, mdia, "hdlr") ?: return null
        return String(b, hdlr.at.toInt() + hdlr.header + 8, 4, Charsets.ISO_8859_1)
    }
}
