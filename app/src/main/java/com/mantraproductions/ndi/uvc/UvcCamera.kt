package com.mantraproductions.ndi.uvc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import com.mantraproductions.ndi.Trace
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * v193: a USB video camera (the Sony ILCE-7M5 in USB Streaming) as one more lens.
 *
 * Camera2 on the Pixel 7 lists no EXTERNAL camera, so the camera is read here over UsbManager: the UVC probe and
 * commit as control transfers, the picture from the bulk endpoint, cut into frames by [UvcProtocol.Assembler].
 * Each frame is decoded (MJPEG by Android's own JPEG decoder; YUY2 and NV12 converted) and drawn into the surface
 * it is given, which is the GPU stage's input: from there the preview, the LUT, peaking, the take, NDI and the link
 * take it exactly as they take a phone lens.
 */
class UvcHub(private val context: Context) {

    private val usb = context.getSystemService(UsbManager::class.java)

    /** The first attached device with a video streaming interface, or null. */
    fun find(): UsbDevice? = runCatching {
        usb.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { i ->
                val f = d.getInterface(i)
                f.interfaceClass == UsbConstants.USB_CLASS_VIDEO && f.interfaceSubclass == UvcProtocol.SC_VIDEOSTREAMING
            }
        }
    }.getOrNull()

    fun hasPermission(d: UsbDevice): Boolean = runCatching { usb.hasPermission(d) }.getOrDefault(false)

    /** A short name for the lens key: the model as the camera gives it, ILCE-7M5. */
    fun nameOf(d: UsbDevice): String = (d.productName ?: "").trim().ifEmpty { "USB camera" }

    fun isSony(d: UsbDevice): Boolean = d.vendorId == 0x054C

    private var permissionReceiver: BroadcastReceiver? = null

    /** Android's own dialog, which only the operator answers. [done] hears his answer. */
    fun requestPermission(d: UsbDevice, done: (Boolean) -> Unit) {
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { context.unregisterReceiver(this) }
                permissionReceiver = null
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || hasPermission(d)
                Trace.state("usb camera: permission " + if (granted) "granted" else "refused")
                done(granted)
            }
        }
        permissionReceiver = r
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 7, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
        Trace.state("usb camera: asking for permission on ${nameOf(d)}")
        usb.requestPermission(d, pi)
    }

    private var plugReceiver: BroadcastReceiver? = null

    /** Hears a camera come and go; [onChange] runs on the main thread with the device and whether it arrived. */
    fun listen(onChange: (UsbDevice, Boolean) -> Unit) {
        if (plugReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)) ?: return
                val video = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
                if (!video) return
                onChange(d, i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED)
            }
        }
        plugReceiver = r
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // the system sends both; exported so the system's broadcast reaches it
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stopListening() {
        plugReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        plugReceiver = null
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
    }

    /** Opens the device and reads what it offers; null with the reason traced. */
    fun open(d: UsbDevice): UvcCamera? {
        if (!hasPermission(d)) { Trace.refused("usb camera", "no permission"); return null }
        val conn = runCatching { usb.openDevice(d) }.getOrNull() ?: run {
            Trace.refused("usb camera", "openDevice gave nothing (another app holds it?)"); return null
        }
        val raw = conn.rawDescriptors ?: run { conn.close(); Trace.refused("usb camera", "no descriptors"); return null }
        val desc = UvcProtocol.parse(raw) ?: run {
            conn.close(); Trace.refused("usb camera", "no video streaming interface with an IN endpoint"); return null
        }
        Trace.state("usb camera: ${nameOf(d)} UVC ${Integer.toHexString(desc.uvcVersion)}, VC if ${desc.controlInterface}, " +
            "VS if ${desc.streamingInterface}, endpoint 0x${Integer.toHexString(desc.endpointAddress)}; modes: " +
            desc.modes.joinToString { m -> m.label + "@" + m.intervals.joinToString("/") { String.format(java.util.Locale.ROOT, "%.0f", m.fpsOf(it)) } })
        return UvcCamera(d, conn, desc, nameOf(d))
    }

    companion object {
        const val ACTION_PERMISSION = "com.mantraproductions.manualcamera.USB_CAMERA_PERMISSION"
    }
}

class UvcCamera internal constructor(
    val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    val descriptors: UvcProtocol.Descriptors,
    override val name: String
) : com.mantraproductions.ndi.FrameSource {
    /** The mode chosen by [prepare], and its frame interval. */
    var mode: UvcProtocol.Mode? = null; private set
    var interval: Int = 0; private set
    override val width: Int get() = mode?.width ?: 1920
    override val height: Int get() = mode?.height ?: 1080
    val fps: Double get() = mode?.fpsOf(interval) ?: 0.0

    @Volatile var framesIn = 0L; private set
    @Volatile var framesDrawn = 0L; private set
    @Volatile var lastFrameAt = 0L; private set
    @Volatile var decodeMs = 0.0; private set
    /** v195: System.nanoTime when the frame now being posted arrived whole from the camera (the GPU stage's clock). */
    @Volatile override var postedFrameNs = 0L; private set
    /** v195: the spacing of arrivals, for the trace: mean and the worst gap in ms over the last report. */
    @Volatile var arrivalJitter = ""; private set
    /** What failed last, for the trace and adb state. */
    @Volatile var problem: String? = null; private set

    /** Said once when the picture stops coming (USB Streaming off on the camera, cable out). */
    var onStall: ((String) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private var reader: Thread? = null
    private var drawer: Thread? = null
    private var vcIf: UsbInterface? = null
    private var vsIf: UsbInterface? = null
    private var endpoint: UsbEndpoint? = null

    /** Picks a mode the app can draw, near [maxWidth] and [fps]. False when the camera offers none. */
    fun prepare(maxWidth: Int, fps: Int): Boolean {
        val pick = UvcProtocol.choose(descriptors.modes, maxWidth, fps) ?: run {
            problem = "no MJPEG, NV12 or YUY2 mode"
            Trace.refused("usb camera", problem!!); return false
        }
        mode = pick.first
        interval = pick.second
        return true
    }

    private fun interfaceNumbered(n: Int): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == n && it.alternateSetting == 0 }
            ?: (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == n }

    /** Probe, commit, and the picture into [surface] until [stop]. Null when it runs, else why not. */
    override fun start(surface: Surface): String? {
        val m = mode ?: return "no mode chosen"
        if (running.get()) return null
        val vs = interfaceNumbered(descriptors.streamingInterface) ?: return "no streaming interface"
        val vc = interfaceNumbered(descriptors.controlInterface)
        // force: the kernel's own uvcvideo, where there is one, lets go
        vc?.let { if (!conn.claimInterface(it, true)) Trace.refused("usb camera", "control interface not claimed") }
        if (!conn.claimInterface(vs, true)) return "the streaming interface is held by another app"
        vcIf = vc; vsIf = vs
        val ep = (0 until vs.endpointCount).map { vs.getEndpoint(it) }.firstOrNull { it.address == descriptors.endpointAddress }
            ?: return "no picture endpoint"
        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) return "the camera streams isochronous, this version reads bulk only"
        endpoint = ep

        // the probe: what the camera wants back, then our wish, then what it agreed to
        val len0 = UvcProtocol.probeLength(descriptors.uvcVersion)
        val current = ByteArray(48)
        val got = conn.controlTransfer(0xA1, UvcProtocol.GET_CUR, UvcProtocol.VS_PROBE shl 8, vs.id, current, current.size, 1000)
        val len = if (got >= 26) got else len0
        var wish = UvcProtocol.probe(len, m, interval, if (got >= 26) current.copyOf(got) else null)
        var sent = conn.controlTransfer(0x21, UvcProtocol.SET_CUR, UvcProtocol.VS_PROBE shl 8, vs.id, wish, len, 1000)
        if (sent < 0) {
            // some cameras want exactly the block length of their own version
            for (other in listOf(34, 26, 48).filter { it != len }) {
                wish = UvcProtocol.probe(other, m, interval)
                sent = conn.controlTransfer(0x21, UvcProtocol.SET_CUR, UvcProtocol.VS_PROBE shl 8, vs.id, wish, other, 1000)
                if (sent >= 0) break
            }
        }
        if (sent < 0) return "the camera refused the probe"
        val agreed = ByteArray(wish.size)
        val back = conn.controlTransfer(0xA1, UvcProtocol.GET_CUR, UvcProtocol.VS_PROBE shl 8, vs.id, agreed, agreed.size, 1000)
        val n = if (back >= 26) UvcProtocol.negotiated(agreed.copyOf(back)) else UvcProtocol.negotiated(wish)
        val commit = if (back >= 26) agreed.copyOf(back) else wish
        if (conn.controlTransfer(0x21, UvcProtocol.SET_CUR, UvcProtocol.VS_COMMIT shl 8, vs.id, commit, commit.size, 1000) < 0)
            return "the camera refused the commit"
        val payload = n.maxPayload.takeIf { it in 512..(16 shl 20) } ?: (512 * 1024)
        Trace.state("usb camera: committed ${m.label} @${String.format(java.util.Locale.ROOT, "%.2f", m.fpsOf(n.interval))} fps " +
            "(format ${n.formatIndex} frame ${n.frameIndex}), frame ≤ ${n.maxFrame} B, payload $payload B")

        running.set(true)
        framesIn = 0; framesDrawn = 0; lastFrameAt = SystemClock.elapsedRealtime(); problem = null
        val slot = Slot()
        reader = thread(name = "uvc-read") { readLoop(ep, payload, n.maxFrame, slot) }
        drawer = thread(name = "uvc-draw") { drawLoop(surface, m, slot) }
        return null
    }

    /** The latest whole frame, handed from the reader to the drawer; older ones are dropped, never queued. */
    private class Slot {
        private val lock = java.util.concurrent.locks.ReentrantLock()
        private val ready = lock.newCondition()
        private var data = ByteArray(0)
        private var size = 0
        private var fresh = false
        /** The size of the frame [take] gave last. */
        var lastSize = 0
            private set

        private var arrived = 0L
        /** When the frame [take] gave last arrived (System.nanoTime). */
        var lastArrived = 0L
            private set
        /** Frames that came in while the one before was still waiting: dropped, never queued. */
        var overwritten = 0L
            private set

        fun put(b: ByteArray, n: Int, at: Long) {
            lock.lock()
            try {
                if (data.size < n) data = ByteArray(n + n / 4)
                if (fresh) overwritten++
                System.arraycopy(b, 0, data, 0, n); size = n; fresh = true; arrived = at
                ready.signalAll()
            } finally { lock.unlock() }
        }

        /** Copies the newest frame into [into] (a bigger array when it does not fit), waiting up to [ms]; null if none. */
        fun take(into: ByteArray, ms: Long): ByteArray? {
            lock.lock()
            try {
                if (!fresh) ready.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!fresh) return null
                val out = if (into.size >= size) into else ByteArray(size + size / 4)
                System.arraycopy(data, 0, out, 0, size); fresh = false
                lastSize = size
                lastArrived = arrived
                return out
            } finally { lock.unlock() }
        }
    }

    private fun readLoop(ep: UsbEndpoint, payload: Int, maxFrame: Int, slot: Slot) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        val buf = ByteArray(payload)
        val asm = UvcProtocol.Assembler(if (maxFrame > 0) maxFrame else 4 shl 20)
        var failures = 0
        var stallSaid = false
        while (running.get()) {
            val n = runCatching { conn.bulkTransfer(ep, buf, buf.size, 500) }.getOrDefault(-1)
            if (n < 0) {
                failures++
                val quiet = SystemClock.elapsedRealtime() - lastFrameAt
                if (quiet > 3000 && !stallSaid) {
                    stallSaid = true
                    problem = "no picture for ${quiet / 1000} s"
                    Trace.refused("usb camera", "$problem ($failures empty reads)")
                    onStall?.invoke("No picture from the $name. Is USB Streaming on?")
                }
                continue
            }
            failures = 0
            asm.push(buf, n) { frame, size ->
                framesIn++
                lastFrameAt = SystemClock.elapsedRealtime()
                stallSaid = false
                slot.put(frame, size, System.nanoTime())
            }
        }
    }

    private fun drawLoop(surface: Surface, m: UvcProtocol.Mode, slot: Slot) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var bitmap: Bitmap? = null
        var pixels: IntArray? = null
        var scratch = ByteArray(0)
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var reportAt = SystemClock.elapsedRealtime()
        var reportFrames = 0L
        var reportIn = 0L
        var decodeSum = 0.0
        var decodeCount = 0
        var badSaid = false
        var prevArrived = 0L
        val gaps = Gaps()
        while (running.get()) {
            val got = runCatching { slot.take(scratch, 500) }.getOrNull() ?: continue
            scratch = got
            val size = slot.lastSize
            val t0 = System.nanoTime()
            val pic: Bitmap? = runCatching {
                when (m.kind) {
                    UvcProtocol.Kind.MJPEG -> {
                        val jpeg = UvcProtocol.withHuffman(got, size)
                        opts.inBitmap = bitmap
                        try {
                            if (jpeg != null) BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                            else BitmapFactory.decodeByteArray(got, 0, size, opts)
                        } catch (t: IllegalArgumentException) {
                            // the reused bitmap did not fit this frame: decode into a new one
                            opts.inBitmap = null
                            if (jpeg != null) BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                            else BitmapFactory.decodeByteArray(got, 0, size, opts)
                        }
                    }
                    UvcProtocol.Kind.YUY2, UvcProtocol.Kind.NV12 -> {
                        val px = pixels?.takeIf { it.size == m.width * m.height } ?: IntArray(m.width * m.height).also { pixels = it }
                        if (m.kind == UvcProtocol.Kind.YUY2) UvcProtocol.yuy2ToArgb(got, m.width, m.height, px)
                        else UvcProtocol.nv12ToArgb(got, m.width, m.height, px)
                        val b = bitmap?.takeIf { it.width == m.width && it.height == m.height && it.isMutable }
                            ?: Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
                        b.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
                        b
                    }
                    else -> null
                }
            }.getOrNull()
            if (pic == null) {
                if (!badSaid) { badSaid = true; Trace.refused("usb camera", "a ${m.kind} frame of $size bytes did not decode") }
                continue
            }
            bitmap = pic
            decodeSum += (System.nanoTime() - t0) / 1e6; decodeCount++
            val drawn = runCatching {
                postedFrameNs = slot.lastArrived
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawBitmap(pic, null, Rect(0, 0, canvas.width, canvas.height), paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            }
            if (drawn.isFailure) {
                problem = "the picture surface went away"
                Trace.refused("usb camera", problem + ": " + drawn.exceptionOrNull()?.javaClass?.simpleName)
                break
            }
            framesDrawn++
            if (prevArrived > 0L) gaps.add((slot.lastArrived - prevArrived) / 1e6)
            prevArrived = slot.lastArrived
            val now = SystemClock.elapsedRealtime()
            if (now - reportAt >= 5000) {
                val s = (now - reportAt) / 1000.0
                decodeMs = if (decodeCount > 0) decodeSum / decodeCount else 0.0
                arrivalJitter = gaps.summary()
                Trace.state("usb camera: " + String.format(java.util.Locale.ROOT,
                    "%.1f fps in, %.1f fps drawn, decode %.1f ms, %d overwritten, arrivals %s, %s %dx%d",
                    (framesIn - reportIn) / s, (framesDrawn - reportFrames) / s, decodeMs, slot.overwritten,
                    arrivalJitter, m.kind, pic.width, pic.height))
                gaps.clear()
                reportAt = now; reportFrames = framesDrawn; reportIn = framesIn
                decodeSum = 0.0; decodeCount = 0
            }
        }
    }

    /** v195: the gaps between drawn frames' arrivals, mean and worst, to see pacing in the trace. */
    private class Gaps {
        private var sum = 0.0; private var n = 0; private var worst = 0.0
        fun add(ms: Double) { sum += ms; n++; if (ms > worst) worst = ms }
        fun clear() { sum = 0.0; n = 0; worst = 0.0 }
        fun summary(): String = if (n == 0) "-" else String.format(java.util.Locale.ROOT, "every %.1f ms (worst %.1f)", sum / n, worst)
    }

    /** The picture stops; the device stays open for the next start. */
    override fun stop() {
        if (!running.getAndSet(false)) return
        reader?.join(1500)
        drawer?.join(1500)
        reader = null; drawer = null
        // alternate setting 0 tells a camera the stream is over (the bulk cameras that care)
        vsIf?.let { runCatching { conn.setInterface(it) } }
        vsIf?.let { runCatching { conn.releaseInterface(it) } }
        vcIf?.let { runCatching { conn.releaseInterface(it) } }
        vsIf = null; vcIf = null
        Trace.state("usb camera: stream stopped after $framesIn frames in, $framesDrawn drawn")
    }

    fun close() {
        stop()
        runCatching { conn.close() }
    }
}
