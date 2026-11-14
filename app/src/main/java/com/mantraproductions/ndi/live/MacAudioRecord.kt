package com.mantraproductions.ndi.live

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import com.mantraproductions.ndi.MacLink
import com.mantraproductions.ndi.Trace
import java.nio.ByteBuffer

/**
 * v208, SOUND LINK IN THE STREAM. stream-webrtc-android 1.3.10 has no hook on the recorded buffer (its
 * setAudioRecordDataCallback is never called), so the WebRTC recorder's own AudioRecord is wrapped: this object stands
 * in its field and hands every call to the [real] one, which keeps the microphone running and so keeps WebRTC's pace.
 * After each read, while the Mac sends, the buffer is overwritten with the Mac's sound ([MacLink.fillLive]); when the
 * link stops, the microphone's own samples go through again. The stream never stops: the swap happens under the
 * running recorder (the field is read again at every read).
 *
 * The superclass is made with the smallest buffer and released at once: only [real] is ever used.
 */
@SuppressLint("MissingPermission")
class MacAudioRecord(wrapped: AudioRecord) : AudioRecord(
    MediaRecorder.AudioSource.MIC, 48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
) {
    /**
     * Null while AudioRecord's own constructor runs: it calls overridden getters (getFormat, …) before this class's
     * fields exist (v209 crashed there with a NullPointerException). Every override falls back to the superclass then.
     */
    @Volatile private var inner: AudioRecord? = null
    val real: AudioRecord get() = inner!!

    init { inner = wrapped; runCatching { super.release() } }

    @Volatile private var mac = false
    private var failed = 0L
    private var nextNs = 0L

    /**
     * v213: after the real read. When it gives nothing (seen 15.11.2026: "AudioRecord.read failed: 0" in a loop after the
     * swap) while the Mac sends, the wrapper paces itself on the clock, one buffer per its own duration, and still hands
     * WebRTC the Mac's sound; the tap's drift steering absorbs the clock. Without the Mac, the real result goes back.
     */
    private fun after(buffer: ByteBuffer, size: Int, read: Int): Int {
        val r = inner ?: return read
        if (read <= 0) {
            failed++
            if (failed == 1L || failed % 1000 == 0L)
                Trace.refused("live audio", "the microphone read gave $read ($failed times), state ${r.recordingState}, " +
                    "${r.sampleRate} Hz ${r.channelCount}ch" + if (MacLink.active) "; paced by the clock with the Mac's sound" else "")
            if (!MacLink.active) return read
            val ch = r.channelCount.coerceIn(1, 2)
            val durNs = size.toLong() / (2 * ch) * 1_000_000_000L / r.sampleRate.coerceAtLeast(8000)
            val now = System.nanoTime()
            if (nextNs == 0L || now - nextNs > 200_000_000L) nextNs = now
            nextNs += durNs
            val wait = nextNs - now
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            val on = MacLink.fillLive(buffer, ch, r.sampleRate)
            if (on != mac) { mac = on; Trace.state(if (on) "live audio: MAC LINK (paced)" else "live audio: microphone") }
            return if (on) size else read
        }
        nextNs = 0L
        val on = MacLink.fillLive(buffer, r.channelCount, r.sampleRate)
        if (on != mac) { mac = on; Trace.state(if (on) "live audio: MAC LINK" else "live audio: microphone") }
        return read
    }

    override fun read(audioBuffer: ByteBuffer, sizeInBytes: Int): Int = inner?.let { after(audioBuffer, sizeInBytes, it.read(audioBuffer, sizeInBytes)) } ?: super.read(audioBuffer, sizeInBytes)
    override fun read(audioBuffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int =
        inner?.let { after(audioBuffer, sizeInBytes, it.read(audioBuffer, sizeInBytes, readMode)) } ?: super.read(audioBuffer, sizeInBytes, readMode)
    override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = inner?.read(audioData, offsetInBytes, sizeInBytes) ?: super.read(audioData, offsetInBytes, sizeInBytes)
    override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int = inner?.read(audioData, offsetInShorts, sizeInShorts) ?: super.read(audioData, offsetInShorts, sizeInShorts)

    override fun getRecordingState(): Int = inner?.recordingState ?: super.getRecordingState()
    override fun getState(): Int = inner?.state ?: super.getState()
    override fun getChannelCount(): Int = inner?.channelCount ?: super.getChannelCount()
    override fun getSampleRate(): Int = inner?.sampleRate ?: super.getSampleRate()
    override fun getAudioFormat(): Int = inner?.audioFormat ?: super.getAudioFormat()
    override fun getAudioSessionId(): Int = inner?.audioSessionId ?: super.getAudioSessionId()
    override fun getAudioSource(): Int = inner?.audioSource ?: super.getAudioSource()
    override fun getBufferSizeInFrames(): Int = inner?.bufferSizeInFrames ?: super.getBufferSizeInFrames()
    override fun getFormat(): AudioFormat = inner?.format ?: super.getFormat()
    override fun getRoutedDevice(): AudioDeviceInfo? = inner?.routedDevice ?: super.getRoutedDevice()
    override fun setPreferredDevice(deviceInfo: AudioDeviceInfo?): Boolean = inner?.setPreferredDevice(deviceInfo) ?: super.setPreferredDevice(deviceInfo)
    override fun getTimestamp(outTimestamp: AudioTimestamp, timebase: Int): Int = inner?.getTimestamp(outTimestamp, timebase) ?: super.getTimestamp(outTimestamp, timebase)
    override fun startRecording() { inner?.startRecording() ?: super.startRecording() }
    override fun stop() { inner?.stop() ?: super.stop() }
    override fun release() { inner?.release() ?: super.release() }

    companion object {
        /**
         * Puts the wrapper into the running WebRTC recorder of [adm] (a JavaAudioDeviceModule) when it holds a plain
         * AudioRecord. Called every second while GO LIVE runs, so a recorder made again (a new viewer) is wrapped too.
         * @return true when the wrapper is in place.
         */
        fun install(adm: Any?): Boolean = runCatching {
            if (adm == null) return false
            val input = adm.javaClass.getField("audioInput").get(adm) ?: return false
            val f = input.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }
            when (val r = f.get(input)) {
                null -> false
                is MacAudioRecord -> true
                is AudioRecord -> {
                    if (r.recordingState != RECORDSTATE_RECORDING) return false
                    f.set(input, MacAudioRecord(r))
                    Trace.state("live audio: the WebRTC recorder is wrapped (Sound Link can replace the microphone), " +
                        "${r.sampleRate} Hz ${r.channelCount}ch, source ${r.audioSource}, state ${r.recordingState}")
                    true
                }
                else -> false
            }
        }.getOrElse { Trace.fault("live audio wrap", it); false }
    }
}
