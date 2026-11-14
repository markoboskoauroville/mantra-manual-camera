package com.mantraproductions.ndi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * v206, SOUND LINK (Marko, 14.11.2026: "I'm choosing the source, and I press Send. When I press Send, my camera app
 * automatically switches the sound and puts the status text that it's receiving sound from the Mac").
 *
 * While the app is open it is findable on the Wi-Fi as `_mantrasound._udp` (Android NSD), so Sound Link (Mantra) on the
 * Mac finds it without an IP. The Mac's packets (the wire is written at the top of SOUND_LINK app/Link.swift) go into
 * [jitter]; the microphone's reader ([AudioMeter], so the meter, the take and NDI) and the GO LIVE recorder
 * ([live.VdoPublisher]) each read it through their own [LinkJitter.Tap] in place of their own input, timed by their own
 * audio clock. The phone answers each hello of the Mac with its numbers, so the Mac shows "camera receiving".
 *
 * The link is on from the first packet and off 1.5 s after the last one (or at once on the Mac's bye); [listener] hears
 * both on the main thread, which switches the status line and back.
 */
object MacLink {
    const val SERVICE_TYPE = "_mantrasound._udp"
    /** v209: the fixed UDP port on every interface, so the Mac finds the phone over Tailscale by probing it. */
    const val PORT = 47474
    private const val QUIET_MS = 1500L

    val jitter = LinkJitter()
    /** The microphone's reader: the meter, the take, NDI. */
    val meterTap = jitter.tap("meter")
    /** The GO LIVE (VDO.Ninja) recorder. */
    val liveTap = jitter.tap("live")

    /** The Mac's sound is arriving: everything that reads the microphone reads the Mac instead. */
    @Volatile var active = false
        private set
    @Volatile var macName = ""
        private set

    /** (on, the Mac's name, why) on the main thread. */
    @Volatile var listener: ((Boolean, String, String) -> Unit)? = null

    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var nsd: NsdManager? = null
    private var registered: NsdManager.RegistrationListener? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var mac: SocketAddress? = null
    private var lastPacketAt = 0L
    private var lastTraceAt = 0L
    private var startedAt = 0L

    /** From onCreate: open the socket and say "here I am" on the Wi-Fi. */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        val s = runCatching {
            // v209: the fixed port on all interfaces (Wi-Fi and Tailscale); any free port when it is taken
            runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(java.net.InetSocketAddress(PORT)) } }
                .getOrElse { DatagramSocket(0) }.apply { soTimeout = 250; receiveBufferSize = 1 shl 18 }
        }.getOrElse {
            Trace.fault("sound link", it); return
        }
        socket = s
        running = true
        worker = thread(name = "sound-link", isDaemon = true) { loop(s) }
        register(s.localPort)
    }

    /** From onDestroy. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { registered?.let { nsd?.unregisterService(it) } }
        registered = null
        runCatching { socket?.close() }
        socket = null
        worker?.join(500)
        worker = null
        if (active) end("the app closed")
    }

    private fun register(port: Int) {
        val ctx = appContext ?: return
        val m = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        val info = NsdServiceInfo().apply {
            serviceName = "Manual Camera (${Build.MODEL})"
            serviceType = SERVICE_TYPE
            this.port = port
            runCatching { setAttribute("id", deviceId) }      // v210: the Mac knows it is the same phone over Tailscale
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Trace.state("sound link: findable as \"${i.serviceName}\" on UDP $port")
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = Trace.refused("sound link", "NSD registration failed ($code)")
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        registered = l
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { Trace.fault("sound link", it) }
    }

    private fun loop(s: DatagramSocket) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                p.setLength(buf.size)
                s.receive(p)
                handle(s, p)
            } catch (_: SocketTimeoutException) {
            } catch (t: Throwable) {
                if (running) Trace.fault("sound link", t)
            }
            val now = SystemClock.elapsedRealtime()
            if (active && now - lastPacketAt > QUIET_MS) end("the Mac stopped sending")
            if (active && now - lastTraceAt >= 10_000) { lastTraceAt = now; Trace.state("sound link: " + numbers()) }
        }
    }

    private fun handle(s: DatagramSocket, p: DatagramPacket) {
        val n = p.length
        if (n < 5) return
        val b = p.data
        if (b[0] != 'M'.code.toByte() || b[1] != 'S'.code.toByte() || b[2] != 'L'.code.toByte()) return
        val from = p.socketAddress
        // one Mac at a time; a hello from another (a Tailscale probe) is still answered, so the phone stays listed
        if (active && from != mac && b[3].toInt().toChar() != 'H') return
        val bb = ByteBuffer.wrap(b, 0, n)                    // big-endian, the header's order
        when (b[3].toInt().toChar()) {
            'A' -> {
                if (n < 20) return
                val ch = b[5].toInt().coerceIn(1, 2)
                val frames = bb.getShort(6).toInt() and 0xffff
                val seq = bb.getInt(8).toLong() and 0xffffffffL
                val stamp = bb.getLong(12)
                val main = frames * ch * 2
                if (20 + main > n) return
                lastPacketAt = SystemClock.elapsedRealtime()
                if (!active) begin(from)
                jitter.write(stamp, seq, b, 20, frames, ch, System.nanoTime())
                // v210: version 2 carries the block from two packets back in μ-law after the main block
                if (b[4].toInt() >= 2 && 20 + main + 8 + frames * ch <= n) {
                    val red = bb.getLong(20 + main)
                    jitter.writeRedundant(red, b, 28 + main, frames, ch)
                }
            }
            'H' -> {
                if (n < 18) return
                val ping = bb.getInt(5)
                val macNs = bb.getLong(9)
                val len = b[17].toInt() and 0xff
                if (active && from != mac) { report(s, from, ping, macNs); return }
                if (18 + len <= n) macName = String(b, 18, len, Charsets.UTF_8)
                // v214: Mac v6 adds its measured round trip and its own delay (input buffer + packet) after the name
                if (18 + len + 8 <= n) { macRttUs = bb.getInt(18 + len).toLong() and 0xffffffffL; macSideUs = bb.getInt(22 + len).toLong() and 0xffffffffL }
                if (!active) { mac = from }
                report(s, from, ping, macNs)
            }
            'B' -> if (active) end("STOP on the Mac")
        }
    }

    /** v214: from the Mac's hello (v6): the round trip it measured and its own delay; defaults for an older Mac. */
    @Volatile private var macRttUs = 20_000L
    @Volatile private var macSideUs = 9_000L

    /**
     * v214, LIP-SYNC: how late the Mac's sound is against the moment it was played, in µs: the Mac's own input buffer and
     * packet, half the round trip, and the buffer the tap reads behind ([LinkJitter.Tap.fill], smoothed).
     * The take's sound is stamped this much earlier (AacEncoder), and so is the stream's (MacAudioRecord.getTimestamp).
     */
    fun delayUs(tap: LinkJitter.Tap = meterTap): Long = if (!active) 0L else
        macSideUs + macRttUs / 2 + (tap.fill * 1_000_000L / jitter.rate).toLong()

    /** v210: over Tailscale (100.64.0.0/10) the buffer starts at 400 ms, on the Wi-Fi at 150 ms. */
    @Volatile var overTailscale = false
        private set

    private fun begin(from: SocketAddress) {
        mac = from
        jitter.reset()
        val ip = (from as? java.net.InetSocketAddress)?.address?.address
        overTailscale = ip != null && ip.size == 4 && (ip[0].toInt() and 0xff) == 100 && (ip[1].toInt() and 0xc0) == 64
        jitter.baseMs = if (overTailscale) 400.0 else 150.0
        active = true
        startedAt = SystemClock.elapsedRealtime()
        lastTraceAt = startedAt
        holdWifi(true)
        val name = macName.ifEmpty { "Mac" }
        Trace.state("sound link: ON from $name ($from)")
        main.post { listener?.invoke(true, name, "") }
    }

    private fun end(why: String) {
        if (!active) return
        active = false
        monitor = false            // v210: MONITOR goes with the link
        holdWifi(false)
        Trace.state("sound link: OFF ($why) after ${(SystemClock.elapsedRealtime() - startedAt) / 1000} s · " + numbers())
        val name = macName.ifEmpty { "Mac" }
        main.post { listener?.invoke(false, name, why) }
        mac = null
    }

    fun numbers(): String {
        val t = meterTap
        return (if (overTailscale) "[Tailscale] " else "[Wi-Fi] ") +
            "fill ${t.fillMs} ms (target ${jitter.targetMs()}), underruns ${t.underruns}, lost ${jitter.lost}, " +
            "recovered ${jitter.recovered}, concealed ${jitter.concealed}, " +
            "late ${jitter.late}, skips ${t.skips}, drift ${t.driftPpm} ppm, packets ${jitter.packets}, " +
            "live tap fill ${liveTap.fillMs} ms underruns ${liveTap.underruns}"
    }

    /** v210: this phone's own id (ANDROID_ID), the same over Wi-Fi and Tailscale. */
    private val deviceId: String by lazy {
        runCatching { android.provider.Settings.Secure.getString(appContext?.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }
            .getOrNull().orEmpty().take(32)
    }

    /** The answer to a hello: the ping echoed and the numbers, so the Mac shows "camera receiving" and the latency. */
    private fun report(s: DatagramSocket, to: SocketAddress, ping: Int, macNs: Long) {
        val name = "Manual Camera · ${Build.MODEL}".toByteArray(Charsets.UTF_8).let { if (it.size > 60) it.copyOf(60) else it }
        val t = meterTap
        val id = deviceId.toByteArray(Charsets.US_ASCII)
        val bb = ByteBuffer.allocate(56 + name.size + id.size).order(ByteOrder.BIG_ENDIAN)
        bb.put("MSLR".toByteArray(Charsets.US_ASCII)).put(1.toByte())
        bb.put((if (!active) 0 else if (t.started && t.fill > 0) 2 else 1).toByte())
        bb.putShort(t.fillMs.coerceIn(0, 65535).toShort()).putShort(jitter.targetMs().toShort())
        bb.putInt(ping).putLong(macNs)
        bb.putInt(t.underruns.toInt()).putInt(jitter.lost.toInt()).putInt(jitter.late.toInt())
        bb.putInt(t.driftPpm).putInt(jitter.packets.toInt()).putInt(t.skips.toInt())
        bb.put(name.size.toByte()).put(name)
        bb.put(id.size.toByte()).put(id)                     // v210: after the name (older Macs read up to the name)
        bb.putInt(jitter.recovered.toInt()).putInt(jitter.concealed.toInt())
        runCatching { s.send(DatagramPacket(bb.array(), bb.position(), to)) }
    }

    /** Wi-Fi power save holds packets back for 100 ms and more; the low-latency lock keeps the radio awake. */
    private fun holdWifi(on: Boolean) {
        runCatching {
            if (on) {
                if (wifiLock == null) {
                    val wm = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiLock = wm.createWifiLock(mode, "sound-link").apply { setReferenceCounted(false) }
                }
                wifiLock?.acquire()
            } else wifiLock?.release()
        }.onFailure { Trace.fault("sound link wifi lock", it) }
    }

    // ---------------------------------------------------------------- for the readers

    /** From the microphone's reader thread: the Mac's sound in place of the buffer's own. */
    fun fillMeter(buffer: ByteArray, bytes: Int, channels: Int): Boolean =
        active && meterTap.readBytes(buffer, bytes, channels)

    /** From the WebRTC recorder (GO LIVE): its direct buffer, 16-bit, [channels] at [rate]. */
    fun fillLive(buffer: ByteBuffer, channels: Int, rate: Int): Boolean {
        if (!active) return false
        val bytes = buffer.capacity()
        val frames = bytes / (2 * channels)
        if (liveScratch.size < frames * channels) liveScratch = ShortArray(frames * channels)
        if (!liveTap.read(liveScratch, frames, channels, rate)) return false
        val sb = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        sb.clear()
        sb.asShortBuffer().put(liveScratch, 0, frames * channels)
        return true
    }

    private var liveScratch = ShortArray(0)

    // ---------------------------------------------------------------- v210: MONITOR (Marko, 14.11.2026: "please make
    // the sound pass through so I can listen to it on my Pixel phone ... it will be kind of feedback, but music can play")

    /** Off at every start of the app and at every end of the link; only the Mac's sound is ever played (no feedback). */
    @Volatile var monitor = false
    /** Its own volume, 0..1; the take and the stream are not touched by it. */
    @Volatile var monitorVolume = 1f
    private var track: android.media.AudioTrack? = null
    private var trackChannels = 0

    /**
     * From the microphone's reader, right after [fillMeter]: the very buffer the take, NDI and the meter carry is also
     * played (headphones when plugged in, else the speaker, as Android routes media). Never blocks the reader.
     */
    fun monitorOut(buffer: ByteArray, bytes: Int, channels: Int, filled: Boolean) {
        if (!(monitor && filled && active)) { if (track != null) closeTrack(); return }
        if (track == null || trackChannels != channels) {
            closeTrack()
            track = runCatching {
                val mask = if (channels == 2) android.media.AudioFormat.CHANNEL_OUT_STEREO else android.media.AudioFormat.CHANNEL_OUT_MONO
                val min = android.media.AudioTrack.getMinBufferSize(48_000, mask, android.media.AudioFormat.ENCODING_PCM_16BIT)
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
                    .setBufferSizeInBytes(min * 4)
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build().also { it.play() }
            }.onFailure { Trace.fault("monitor", it) }.getOrNull()
            trackChannels = channels
            Trace.state("monitor: playing the Mac's sound on the phone (${channels}ch)")
        }
        track?.let { t ->
            runCatching { t.setVolume(monitorVolume); t.write(buffer, 0, bytes, android.media.AudioTrack.WRITE_NON_BLOCKING) }
        }
    }

    private fun closeTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush(); t.release() }
        Trace.state("monitor: off")
    }
}
