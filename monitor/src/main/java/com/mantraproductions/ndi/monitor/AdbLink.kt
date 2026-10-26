package com.mantraproductions.ndi.monitor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.github.muntashirakon.adb.AdbStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * v150, THE REMOTE OVER ADB (Marko, 26.10.2026: "since we have wireless debugging, we can also upgrade my remote control
 * app to use this protocol ... Remote controller using ADB, and through ADB you transfer the picture using open-source
 * code from screen copy"; "There will be some kind of ADB status line that says ADB is enabled, and then it works
 * through ADB").
 *
 * The camera phone's whole screen, as MANTRA LINK shows it, but carried by scrcpy over wireless debugging: the monitor
 * connects with its own key (paired once, [AdbKeys]), pushes scrcpy's server (an asset, scrcpy 4.1, Apache 2.0) to
 * /data/local/tmp through ADB's file sync, starts it from the shell, and opens its two sockets: the H.264 picture, decoded
 * here straight onto [output], and the control channel, where every finger goes back as scrcpy's own touch event.
 *
 * The wire (scrcpy 4.1, tunnel_forward, measured): on the first socket one dummy byte, then — only once the control
 * socket is open too — 64 bytes of device name, 4 of codec id, then packets ([decode]).
 */
class AdbLink(
    private val context: Context,
    val host: String,
    val port: Int,
    private val output: Surface,
    private val onStatus: (String) -> Unit,
    private val onSize: (Int, Int) -> Unit,
    /** The camera phone asks to be paired with this monitor (its key is not known there yet). */
    private val onPairingNeeded: () -> Unit
) {
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    val frames = AtomicLong()
    val bytes = AtomicLong()
    @Volatile var device = ""; private set
    @Volatile private var running = false
    private var manager: AdbManager? = null
    private var video: AdbStream? = null
    private var control: AdbStream? = null
    private var shell: AdbStream? = null
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "adb-control") }

    fun start() {
        running = true
        thread(name = "adb-link") {
            try { run() } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                if (running) { onStatus("ADB: this monitor is not paired with $host yet"); onPairingNeeded() }
            } catch (e: Throwable) {
                if (running) onStatus("ADB: " + (e.message ?: e.javaClass.simpleName))
                Log.w(TAG, "link", e)
            } finally { close() }
        }
    }

    fun stop() { running = false; close() }

    private fun close() {
        runCatching { video?.close() }; runCatching { control?.close() }; runCatching { shell?.close() }
        runCatching { manager?.close() }
        video = null; control = null; shell = null; manager = null
    }

    private fun run() {
        onStatus("ADB: connecting to $host:$port")
        val m = AdbManager(context).apply { setTimeout(10, java.util.concurrent.TimeUnit.SECONDS) }
        manager = m
        if (!m.connect(host, port)) throw IllegalStateException("$host:$port did not answer")
        onStatus("ADB: connected, starting the picture")

        // scrcpy's server onto the camera phone
        val jar = context.assets.open(SERVER_ASSET).use { it.readBytes() }
        push(m, jar, SERVER_PATH)

        // the session's id: a 31-bit number in hex, as scrcpy reads it; its socket is scrcpy_<scid>
        val scid = Integer.toHexString((Math.random() * 0x7FFFFFFF).toInt())
        val args = listOf(
            "scid=$scid", "log_level=info", "tunnel_forward=true", "audio=false", "control=true",
            "video_codec=h264", "max_size=$MAX_SIZE", "video_bit_rate=$BIT_RATE", "max_fps=30", "cleanup=true",
            "stay_awake=true", "power_off_on_close=false"
        ).joinToString(" ")
        val sh = m.openStream("shell:CLASSPATH=$SERVER_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION $args")
        shell = sh
        thread(name = "adb-server-log") {
            runCatching {
                val r = sh.openInputStream().bufferedReader()
                while (running) { val line = r.readLine() ?: break; Log.i(TAG, "server: $line") }
            }
        }

        // its first socket is the picture, the second the control channel
        val v = openWhenReady(m, "localabstract:scrcpy_$scid")
        video = v
        val vin = v.openInputStream()
        readFully(vin, 1)                                   // the dummy byte of a forward tunnel
        // the server sends the device and the codec only once every socket is open (MEASURED: waiting for them
        // before opening the control socket hung at "starting the picture")
        control = openWhenReady(m, "localabstract:scrcpy_$scid")
        device = String(readFully(vin, 64)).trimEnd('\u0000')
        readFully(vin, 4)                                   // "h264"; the size comes in the first session packet
        onStatus("ADB ENABLED · $device · $host:$port")
        decode(vin)
    }

    /** ADB's file sync: SEND path,mode · DATA chunks · DONE mtime · OKAY. */
    private fun push(m: AdbManager, data: ByteArray, path: String) {
        val s = m.openStream("sync:")
        val out = s.openOutputStream(); val inp = s.openInputStream()
        fun head(id: String, n: Int) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply { put(id.toByteArray()); putInt(n) }.array()
        val spec = "$path,${0b110100100}".toByteArray()   // 0644
        out.write(head("SEND", spec.size)); out.write(spec)
        var at = 0
        while (at < data.size) {
            val n = minOf(64 * 1024, data.size - at)
            out.write(head("DATA", n)); out.write(data, at, n); at += n
        }
        out.write(head("DONE", (System.currentTimeMillis() / 1000).toInt())); out.flush()
        val answer = readFully(inp, 8)
        val id = String(answer, 0, 4)
        if (id != "OKAY") {
            val n = ByteBuffer.wrap(answer, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            throw IllegalStateException("push refused: " + String(readFully(inp, n.coerceIn(0, 1024))))
        }
        out.write(head("QUIT", 0)); out.flush()
        runCatching { s.close() }
    }

    private fun openWhenReady(m: AdbManager, where: String): AdbStream {
        val until = System.currentTimeMillis() + 5000
        while (true) {
            try { return m.openStream(where) } catch (e: Exception) {
                if (System.currentTimeMillis() > until || !running) throw IllegalStateException("scrcpy did not open its socket")
                Thread.sleep(100)
            }
        }
    }

    /**
     * MEASURED on scrcpy 4.1 (26.10.2026, the first bytes dumped from the Mac): every packet starts with 12 bytes. With
     * the top bit set it is a SESSION packet — the rest of its first four bytes are flags, then width and height (u32
     * each), no payload; it comes first and again whenever the picture's size changes (a turn of the phone). Otherwise it
     * is a frame: 8 bytes of pts with flags (bit 62 config, bit 61 key frame), 4 bytes of length, the payload.
     */
    private fun decode(vin: InputStream) {
        var dec: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val head = ByteBuffer.wrap(readFully(vin, 12))
                val ptsFlags = head.long
                if (ptsFlags and FLAG_SESSION != 0L) {
                    head.position(4)
                    val w = head.int; val h = head.int
                    // a new size: a new decoder for it (the old one would keep the old frame's shape)
                    if (dec == null || w != width || h != height) {
                        dec?.let { runCatching { it.stop() }; runCatching { it.release() } }
                        width = w; height = h
                        onSize(w, h)
                        dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                            configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h), output, null, 0)
                            start()
                        }
                    }
                    continue
                }
                val size = head.int
                val payload = readFully(vin, size)
                bytes.addAndGet(size + 12L)
                val d = dec ?: continue                     // no session yet: nothing to decode with
                var flags = 0
                if (ptsFlags and FLAG_CONFIG != 0L) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                if (ptsFlags and FLAG_KEY != 0L) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                val pts = if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) 0L else ptsFlags and PTS_MASK
                var i = d.dequeueInputBuffer(20_000)
                while (i < 0 && running) { drain(d, info); i = d.dequeueInputBuffer(20_000) }
                if (i >= 0) {
                    d.getInputBuffer(i)!!.apply { clear(); put(payload) }
                    d.queueInputBuffer(i, 0, size, pts, flags)
                }
                drain(d, info)
            }
        } finally { dec?.let { runCatching { it.stop() }; runCatching { it.release() } } }
    }

    private fun drain(dec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val o = dec.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> { dec.releaseOutputBuffer(o, true); frames.incrementAndGet() }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = dec.outputFormat
                    val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (w != width || h != height) { width = w; height = h; onSize(w, h) }
                }
                else -> return
            }
        }
    }

    // --- the hand, as scrcpy's control messages ---------------------------------------------------------------------

    /** A finger as fractions of the picture, like [com.mantraproductions.ndi.LinkClient.touch]. */
    fun touch(action: Int, index: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val msgs = mutableListOf<ByteArray>()
        fun one(a: Int, i: Int) = msgs.add(touchMessage(a, ids[i].toLong(), (xs[i] * w).toInt(), (ys[i] * h).toInt(), w, h, a != UP))
        when (action) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_POINTER_DOWN -> one(DOWN, index)
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_POINTER_UP -> one(UP, index)
            android.view.MotionEvent.ACTION_MOVE -> for (i in ids.indices) one(MOVE, i)
            android.view.MotionEvent.ACTION_CANCEL -> for (i in ids.indices) one(UP, i)
        }
        send(msgs)
    }

    /** BACK, as a key on the camera phone. */
    fun key(name: String) {
        val code = when (name) { "BACK" -> 4; "HOME" -> 3; else -> return }
        send(listOf(keyMessage(0, code), keyMessage(1, code)))
    }

    private fun send(msgs: List<ByteArray>) {
        val c = control ?: return
        sender.execute {
            runCatching { val o = c.openOutputStream(); for (m in msgs) o.write(m); o.flush() }
                .onFailure { Log.w(TAG, "control", it) }
        }
    }

    private fun touchMessage(action: Int, pointer: Long, x: Int, y: Int, w: Int, h: Int, pressed: Boolean): ByteArray =
        ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            put(TYPE_TOUCH.toByte()); put(action.toByte()); putLong(pointer)
            putInt(x); putInt(y); putShort(w.toShort()); putShort(h.toShort())
            putShort(if (pressed) 0xFFFF.toShort() else 0)
            putInt(0)                                      // action button
            putInt(0)                                      // buttons (a finger, not a mouse)
        }.array()

    private fun keyMessage(action: Int, code: Int): ByteArray =
        ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN).apply {
            put(TYPE_KEY.toByte()); put(action.toByte()); putInt(code); putInt(0); putInt(0)
        }.array()

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var at = 0
        while (at < n) { val r = inp.read(b, at, n - at); if (r < 0) throw java.io.EOFException("the camera phone closed the picture"); at += r }
        return b
    }

    companion object {
        private const val TAG = "AdbLink"

        /** Wireless debugging's "Pair with code": the six digits at the address and port the camera phone shows. */
        fun pair(context: Context, host: String, port: Int, code: String): Boolean =
            AdbManager(context).use { it.pair(host, port, code.trim()) }
        const val SERVER_ASSET = "scrcpy-server.jar"
        const val SERVER_PATH = "/data/local/tmp/mantra-scrcpy-server.jar"
        const val SERVER_VERSION = "4.1"
        const val MAX_SIZE = 1600
        const val BIT_RATE = 8_000_000
        private const val FLAG_SESSION = 1L shl 63
        private const val FLAG_CONFIG = 1L shl 62
        private const val FLAG_KEY = 1L shl 61
        private const val PTS_MASK = (1L shl 61) - 1
        private const val TYPE_KEY = 0
        private const val TYPE_TOUCH = 2
        private const val DOWN = 0
        private const val UP = 1
        private const val MOVE = 2
    }
}
