package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** The time code track (v187): the frame arithmetic, then a real MP4 that ffprobe reads back. */
class Mp4TimecodeTest {

    @Test fun frameOfDay() {
        assertEquals(0L, Mp4Timecode.frameOfDay(0, 25))
        // 10:20:30 and 480 ms at 25 fps is frame 12 of that second
        val ms = ((10 * 3600 + 20 * 60 + 30) * 1000L) + 480
        assertEquals("10:20:30:12", Mp4Timecode.label(Mp4Timecode.frameOfDay(ms, 25), 25))
        assertEquals("23:59:59:29", Mp4Timecode.label(Mp4Timecode.frameOfDay(86_399_999, 30), 30))
        assertEquals("00:00:00:00", Mp4Timecode.label(Mp4Timecode.frameOfDay(86_400_000, 30), 30))
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(); return out
    }

    private fun tool(name: String) = listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name").firstOrNull { File(it).canExecute() }

    @Test fun ffprobeReadsIt() {
        val ffmpeg = tool("ffmpeg"); val ffprobe = tool("ffprobe")
        assumeTrue("ffmpeg on this machine", ffmpeg != null && ffprobe != null)
        for (faststart in listOf(false, true)) {
            val f = File.createTempFile("take", ".mp4")
            // a picture and a sound, as the camera writes them, without a time code
            run(ffmpeg!!, "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=30", "-f", "lavfi",
                "-i", "sine=frequency=1000", "-t", "2", "-c:v", "libx264", "-c:a", "aac",
                *(if (faststart) arrayOf("-movflags", "+faststart") else emptyArray()), f.path)
            fun counted() = run(ffprobe!!, "-v", "error", "-count_frames", "-show_entries", "stream=codec_type,nb_read_frames", "-of", "csv=p=0", f.path)
                .lines().filter { it.startsWith("video") || it.startsWith("audio") }.distinct()   // ffprobe 8 repeats a stream under STREAM_GROUP
            val before = counted()
            val frame = Mp4Timecode.frameOfDay(((14 * 3600 + 5 * 60 + 9) * 1000L) + 500, 30)
            RandomAccessFile(f, "rw").use { raf ->
                val io = object : Mp4Timecode.Io {
                    override fun size() = raf.length()
                    override fun read(at: Long, n: Int) = ByteArray(n).also { raf.seek(at); raf.readFully(it) }
                    override fun write(at: Long, bytes: ByteArray) { raf.seek(at); raf.write(bytes) }
                }
                assertEquals("14:05:09:15", Mp4Timecode.write(io, frame, 30).getOrThrow())
                assertTrue(Mp4Timecode.write(io, frame, 30).isFailure)   // never twice
            }
            val tc = run(ffprobe!!, "-v", "error", "-show_entries", "stream_tags=timecode", "-of", "csv=p=0", f.path)
            assertTrue("faststart=$faststart: $tc", tc.lines().any { it.trim() == "14:05:09:15" })
            // the picture and the sound decode as before, every frame
            assertEquals(2, before.size)
            assertEquals(before, counted())
            f.delete()
        }
    }
}
