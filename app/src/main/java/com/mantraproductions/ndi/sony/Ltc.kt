package com.mantraproductions.ndi.sony

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

/**
 * AUDIO TIME CODE (Marko, 29.10.2026: "Please build in an audio timecode generator as well, so later we can find a way
 * to send the same audio to multiple cameras, maybe through a mixer or something. Through audio, it can be synchronized.")
 *
 * SMPTE linear time code (LTC) at 25 frames: 80 bits a frame, 2000 bits a second, biphase mark (the level turns at the
 * start of every bit, and once more in its middle for a 1), the sync word 0011 1111 1111 1101 at the end. Written at
 * 48 kHz (24 samples a bit) to the phone's output: the speaker, headphones or a USB-C audio adapter into a camera's
 * microphone input or a mixer. [now] gives the generator's time in ms; each frame carries the time it will be heard,
 * the output's latency taken from the track's own timestamps.
 */
class Ltc(private val now: () -> Long) {
    private val rate = 48000
    private val fps = 25
    private val perBit = rate / (80 * fps)              // 24
    @Volatile private var running = false
    private var level = 0.6f

    fun start() {
        if (running) return
        running = true
        thread(name = "ltc", priority = Thread.MAX_PRIORITY) {
            val frameSamples = perBit * 80               // 1920 = 40 ms
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(frameSamples * 4 * 4).build()
            track.play()
            val buf = FloatArray(frameSamples)
            var written = 0L                             // samples written so far
            val t0 = now()                               // the generator's time when the first sample was written
            // the first frame: the next whole frame of the generator
            var frameMs = (t0 / 40 + 1) * 40
            val ts = android.media.AudioTimestamp()
            var latencyMs = 0L
            while (running) {
                // how far behind the written samples the speaker is, from the track's own clock
                if (track.getTimestamp(ts)) latencyMs = (written - ts.framePosition) * 1000 / rate -
                    (System.nanoTime() - ts.nanoTime) / 1_000_000
                encode(frameMs + latencyMs.coerceIn(0, 400), buf)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                written += buf.size; frameMs += 40
            }
            track.stop(); track.release()
        }
    }

    fun stop() { running = false }

    /** One 80-bit frame for the time of day [ms] (local time), biphase mark into [out]. */
    private fun encode(ms: Long, out: FloatArray) {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY); val m = c.get(java.util.Calendar.MINUTE)
        val s = c.get(java.util.Calendar.SECOND); val f = (c.get(java.util.Calendar.MILLISECOND) * fps / 1000)
        val bits = IntArray(80)
        fun put(at: Int, n: Int, v: Int) { for (i in 0 until n) bits[at + i] = (v shr i) and 1 }
        put(0, 4, f % 10); put(8, 2, f / 10)
        put(16, 4, s % 10); put(24, 3, s / 10)
        put(32, 4, m % 10); put(40, 3, m / 10)
        put(48, 4, h % 10); put(56, 2, h / 10)
        val sync = intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1)
        for (i in 0 until 16) bits[64 + i] = sync[i]
        // 25 frames: bit 59 is the polarity bit, set so every frame holds an even number of zeros
        if (bits.count { it == 0 } % 2 == 1) bits[59] = 1
        var k = 0
        for (b in bits) {
            level = -level                               // a turn at the start of every bit
            for (i in 0 until perBit) {
                if (b == 1 && i == perBit / 2) level = -level   // and one in the middle for a 1
                out[k++] = level
            }
        }
    }
}
