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
        val s = runCatching { DatagramSocket(0).apply { soTimeout = 250; receiveBufferSize = 1 shl 18 } }.getOrElse {
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
        if (active && from != mac) return                    // one Mac at a time
        val bb = ByteBuffer.wrap(b, 0, n)                    // big-endian, the header's order
        when (b[3].toInt().toChar()) {
            'A' -> {
                if (n < 20) return
                val ch = b[5].toInt().coerceIn(1, 2)
                val frames = bb.getShort(6).toInt() and 0xffff
                val seq = bb.getInt(8).toLong() and 0xffffffffL
                val stamp = bb.getLong(12)
                if (20 + frames * ch * 2 > n) return
                lastPacketAt = SystemClock.elapsedRealtime()
                if (!active) begin(from)
                jitter.write(stamp, seq, b, 20, frames, ch, System.nanoTime())
            }
            'H' -> {
                if (n < 18) return
                val ping = bb.getInt(5)
                val macNs = bb.getLong(9)
                val len = b[17].toInt() and 0xff
                if (18 + len <= n) macName = String(b, 18, len, Charsets.UTF_8)
                if (!active) { mac = from }
                report(s, from, ping, macNs)
            }
            'B' -> if (active) end("STOP on the Mac")
        }
    }

    private fun begin(from: SocketAddress) {
        mac = from
        jitter.reset()
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
        holdWifi(false)
        Trace.state("sound link: OFF ($why) after ${(SystemClock.elapsedRealtime() - startedAt) / 1000} s · " + numbers())
        val name = macName.ifEmpty { "Mac" }
        main.post { listener?.invoke(false, name, why) }
        mac = null
    }

    fun numbers(): String {
        val t = meterTap
        return "fill ${t.fillMs} ms (target ${jitter.targetMs()}), underruns ${t.underruns}, lost ${jitter.lost}, " +
            "late ${jitter.late}, skips ${t.skips}, drift ${t.driftPpm} ppm, packets ${jitter.packets}, " +
            "live tap fill ${liveTap.fillMs} ms underruns ${liveTap.underruns}"
    }

    /** The answer to a hello: the ping echoed and the numbers, so the Mac shows "camera receiving" and the latency. */
    private fun report(s: DatagramSocket, to: SocketAddress, ping: Int, macNs: Long) {
        val name = "Manual Camera · ${Build.MODEL}".toByteArray(Charsets.UTF_8).let { if (it.size > 60) it.copyOf(60) else it }
        val t = meterTap
        val bb = ByteBuffer.allocate(47 + name.size).order(ByteOrder.BIG_ENDIAN)
        bb.put("MSLR".toByteArray(Charsets.US_ASCII)).put(1.toByte())
        bb.put((if (!active) 0 else if (t.started && t.fill > 0) 2 else 1).toByte())
        bb.putShort(t.fillMs.coerceIn(0, 65535).toShort()).putShort(jitter.targetMs().toShort())
        bb.putInt(ping).putLong(macNs)
        bb.putInt(t.underruns.toInt()).putInt(jitter.lost.toInt()).putInt(jitter.late.toInt())
        bb.putInt(t.driftPpm).putInt(jitter.packets.toInt()).putInt(t.skips.toInt())
        bb.put(name.size.toByte()).put(name)
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
}
