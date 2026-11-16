package com.mantraproductions.ndi

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * v221, SKELETON MODE (Marko, 16.11.2026: "if I connect via USB my Pixel phone, this app needs to understand how to adjust
 * the phone and the camera to get the best possible picture available on this USB connection ... my application is to
 * understand ADB and start the camera itself, adjust the settings of this phone and being able to use it for posing").
 *
 * Skeleton (Mantra) on the Mac starts the camera over adb with intent extras:
 *
 *   am start -n com.mantraproductions.manualcamera/com.mantraproductions.ndi.MainActivity --es mode skeleton
 *            [--es lens back_main|back_wide|front|L2] [--ei width 1920|3840|1280] [--ei fps 30] [--ei mbps 40]
 *            [--es codec hevc|h264] [--ei gop 1] [--es lock ae,awb|awb|none] [--es flicker 50|60|auto]
 *            [--es stab off|on] [--ez dim true]
 *   am start ... --es mode off          the operator's own settings again
 *
 * Nothing of this is written into the settings: the run is the Mac's, the camera's own settings stay Marko's. While it is
 * on: the GPU stage, 8-bit SDR, the stream encoder at the asked size, rate, bit rate and keyframe interval, low latency;
 * stabilisation (video and optical) off, since it shifts the picture under a calibration; anti-banding at the mains
 * (50 Hz here); continuous video focus; exposure and white balance locked 3 s after the lens opens (they settle first);
 * the screen dimmed and kept awake. The picture goes to the Mac through Mantra Link (MODE 2, port 48100, `adb forward`
 * on the cable). The status is JSON at http://<phone>:48102/status (also forwarded): size, rate, measured fps and
 * bit rate, lens, the lens's own calibration (LENS_INTRINSIC_CALIBRATION / LENS_DISTORTION) and the intrinsics turned
 * into the stream's own pixels, the phone clock for the Mac's latency measurement, the USB link speed, battery.
 */
object SkeletonMode {
    const val STATUS_PORT = 48102

    @Volatile var active = false
        private set
    var lens = "back_main"; private set
    var width = 1920; private set
    var fps = 30; private set
    var mbps = 40; private set
    var hevc = true; private set
    var gopSec = 1; private set
    var lockAe = true; private set
    var lockAwb = true; private set
    /** CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_*: 1 = 50 Hz, 2 = 60 Hz, 3 = auto, 0 = off. */
    var antibanding = 1; private set
    var stabilisation = false; private set
    var dim = true; private set
    var since = 0L; private set

    /** What the running camera is (filled by MainActivity / CameraPipeline). */
    @Volatile var lensLabel = ""
    @Volatile var cameraId = ""
    @Volatile var physicalId: String? = null
    @Volatile var streamW = 0
    @Volatile var streamH = 0
    @Volatile var streamTurns = 0
    @Volatile var codecName = ""
    @Volatile var locked = ""
    @Volatile var iso: Int? = null
    @Volatile var exposureNs: Long? = null
    @Volatile var focusDiopters: Float? = null
    @Volatile var linkClient = false
    @Volatile var appVersion = 0

    // measured on the stream's frames that go to the Mac
    private val meterLock = Any()
    private var winStart = 0L
    private var winFrames = 0
    private var winBytes = 0L
    @Volatile var fpsMeasured = 0.0; private set
    @Volatile var mbpsMeasured = 0.0; private set
    @Volatile var lastPtsUs = 0L; private set
    @Volatile var dropped = 0; private set

    /** The intent's extras: true when it asked for skeleton mode (or for its end: [active] then false). */
    fun fromIntent(i: Intent?): Boolean {
        val m = i?.getStringExtra("mode")?.lowercase() ?: return false
        if (m != "skeleton" && m != "off" && m != "normal") return false
        if (m != "skeleton") {
            val was = active
            active = false
            Trace.control("skeleton", "off", "the camera's own settings again")
            return was
        }
        lens = i.getStringExtra("lens")?.lowercase() ?: "back_main"
        width = i.getIntExtra("width", 1920).let { if (it in 640..4096) it else 1920 }
        fps = i.getIntExtra("fps", 30).coerceIn(10, 60)
        mbps = i.getIntExtra("mbps", 40).coerceIn(4, 150)
        hevc = (i.getStringExtra("codec")?.lowercase() ?: "hevc") != "h264"
        gopSec = i.getIntExtra("gop", 1).coerceIn(1, 10)
        val lock = (i.getStringExtra("lock") ?: "ae,awb").lowercase()
        lockAe = "ae" in lock.split(',', ' ').map { it.trim() }
        lockAwb = "awb" in lock
        antibanding = when (i.getStringExtra("flicker")?.lowercase() ?: "50") {
            "60" -> 2; "auto" -> 3; "off", "0" -> 0; else -> 1
        }
        stabilisation = (i.getStringExtra("stab")?.lowercase() ?: "off") == "on"
        dim = i.getBooleanExtra("dim", true)
        active = true
        since = SystemClock.elapsedRealtime()
        synchronized(meterLock) { winStart = 0; winFrames = 0; winBytes = 0 }
        fpsMeasured = 0.0; mbpsMeasured = 0.0; dropped = 0
        Trace.control("skeleton", "on", "lens $lens ${width}w ${fps}fps ${mbps}Mbit/s ${if (hevc) "HEVC" else "H.264"} gop ${gopSec}s lock $lock")
        return true
    }

    /** The lens asked for, as an index into [CameraCatalogue.lenses] (sorted: back first, by focal length). */
    fun lensIndex(lenses: List<CameraCatalogue.Lens>): Int {
        if (lenses.isEmpty()) return 0
        val l = lens
        Regex("^l(\\d+)$").find(l)?.let { return (it.groupValues[1].toInt() - 1).coerceIn(0, lenses.size - 1) }
        val back = lenses.withIndex().filter { it.value.facing == "Back" }
        val pick = when (l) {
            "back_wide", "back_ultrawide", "ultrawide", "wide" -> back.minByOrNull { it.value.equivalentMm }
            "front", "selfie" -> lenses.withIndex().firstOrNull { it.value.facing == "Front" }
            "back_tele", "tele" -> back.maxByOrNull { it.value.equivalentMm }
            // the main 1x: the back lens nearest 25 mm equivalent
            else -> back.minByOrNull { kotlin.math.abs(it.value.equivalentMm - 25) }
        }
        return pick?.index ?: 0
    }

    /** One encoded frame went to the Mac. */
    fun onFrame(bytes: Int, ptsUs: Long) {
        val now = SystemClock.elapsedRealtime()
        lastPtsUs = ptsUs
        synchronized(meterLock) {
            if (winStart == 0L) winStart = now
            winFrames++; winBytes += bytes
            val dt = now - winStart
            if (dt >= 1000) {
                fpsMeasured = winFrames * 1000.0 / dt
                mbpsMeasured = winBytes * 8.0 / dt / 1000.0
                winStart = now; winFrames = 0; winBytes = 0
            }
        }
    }

    fun onDrop() { dropped++ }

    // ------------------------------------------------------------------------------------------------ the status

    fun status(context: Context): JSONObject {
        val o = JSONObject()
        o.put("app", "Mantra Manual Camera")
        o.put("version", appVersion)
        o.put("mode", if (active) "skeleton" else "normal")
        o.put("since_s", if (active) (SystemClock.elapsedRealtime() - since) / 1000 else 0)
        // the stream's pts are the camera's sensor timestamps, on this clock (SENSOR_INFO_TIMESTAMP_SOURCE REALTIME)
        o.put("clock_us", SystemClock.elapsedRealtimeNanos() / 1000)
        o.put("last_pts_us", lastPtsUs)
        o.put("asked", JSONObject().apply {
            put("lens", lens); put("width", width); put("fps", fps); put("mbps", mbps)
            put("codec", if (hevc) "hevc" else "h264"); put("gop_s", gopSec)
            put("lock", listOfNotNull("ae".takeIf { lockAe }, "awb".takeIf { lockAwb }).joinToString(",").ifEmpty { "none" })
            put("flicker", when (antibanding) { 1 -> "50"; 2 -> "60"; 3 -> "auto"; else -> "off" })
            put("stab", if (stabilisation) "on" else "off")
        })
        o.put("stream", JSONObject().apply {
            put("width", streamW); put("height", streamH); put("turns", streamTurns); put("codec", codecName)
            put("fps", round(fpsMeasured)); put("mbps", round(mbpsMeasured)); put("dropped", dropped)
            put("to_mac", linkClient)
        })
        o.put("lens", JSONObject().apply {
            put("label", lensLabel); put("camera_id", cameraId); put("physical_id", physicalId ?: JSONObject.NULL)
            put("locked", locked)
            put("iso", iso ?: JSONObject.NULL); put("exposure_ms", exposureNs?.let { round(it / 1e6) } ?: JSONObject.NULL)
            put("focus_diopters", focusDiopters?.let { round(it.toDouble()) } ?: JSONObject.NULL)
        })
        runCatching { calibration(context)?.let { o.put("calibration", it) } }
            .onFailure { o.put("calibration_error", it.javaClass.simpleName) }
        o.put("usb", usbSpeed())
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val b = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            o.put("battery", JSONObject().apply {
                put("percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                put("temp_c", (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
                put("plugged", b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
            })
        }
        return o
    }

    private fun round(v: Double) = Math.round(v * 100) / 100.0

    /** The USB device controller's negotiated speed (e.g. "super-speed-plus"), or what the phone allows us to read. */
    private fun usbSpeed(): String = runCatching {
        java.io.File("/sys/class/udc").listFiles()?.firstNotNullOfOrNull { d ->
            runCatching { java.io.File(d, "current_speed").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        } ?: "unknown"
    }.getOrDefault("unknown")

    /**
     * The lens's own calibration and the same intrinsics in the stream's pixels. Camera2 crops the active array to the
     * output's shape about its centre and scales it, so K moves with that crop and scale; the stream's quarter turns turn
     * it too. The ISP's distortion correction is on (HIGH_QUALITY), so the stream is already undistorted: the distortion
     * coefficients are reported for reference and the stream's model has k = 0. The board calibration stays the truth;
     * this is the starting guess.
     */
    private fun calibration(context: Context): JSONObject? {
        if (cameraId.isEmpty()) return null
        val m = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val c = m.getCameraCharacteristics(physicalId ?: cameraId)
        val o = JSONObject()
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val dist = c.get(CameraCharacteristics.LENS_DISTORTION)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        active?.let { o.put("active_array", JSONArray(listOf(it.left, it.top, it.width(), it.height()))) }
        pre?.let { o.put("pre_correction_array", JSONArray(listOf(it.left, it.top, it.width(), it.height()))) }
        k?.let { o.put("intrinsic_calibration", JSONArray(it.map { v -> v.toDouble() })) }   // fx, fy, cx, cy, s
        dist?.let { o.put("distortion", JSONArray(it.map { v -> v.toDouble() })) }          // k1, k2, k3, p1, p2
        focal?.let { o.put("focal_mm", it.toDouble()) }
        phys?.let { o.put("sensor_mm", JSONArray(listOf(it.width.toDouble(), it.height.toDouble()))) }
        c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.let { o.put("pose_translation", JSONArray(it.map { v -> v.toDouble() })) }
        c.get(CameraCharacteristics.LENS_POSE_ROTATION)?.let { o.put("pose_rotation", JSONArray(it.map { v -> v.toDouble() })) }
        o.put("distortion_correction", "high_quality (the stream is undistorted by the ISP)")

        // the intrinsics in the stream's own pixels
        val arr = active ?: return o
        val w0 = if (streamTurns % 2 == 1) streamH else streamW    // the stream before its turns
        val h0 = if (streamTurns % 2 == 1) streamW else streamH
        if (w0 <= 0 || h0 <= 0) return o
        val aw = arr.width().toDouble(); val ah = arr.height().toDouble()
        val outAspect = w0.toDouble() / h0
        // the crop of the active array the output is made from (centred, same shape as the output)
        val (cw, ch) = if (aw / ah > outAspect) Pair(ah * outAspect, ah) else Pair(aw, aw / outAspect)
        val ox = (aw - cw) / 2; val oy = (ah - ch) / 2
        val s = w0 / cw
        var fx: Double; var fy: Double; var cx: Double; var cy: Double
        var source: String
        if (k != null && k.size >= 4 && k[0] > 0f) {
            // LENS_INTRINSIC_CALIBRATION is in pre-correction array pixels: into the active array, then the crop
            val dx = ((pre?.left ?: arr.left) - arr.left).toDouble(); val dy = ((pre?.top ?: arr.top) - arr.top).toDouble()
            fx = k[0] * s; fy = k[1] * s
            cx = (k[2] + dx - ox) * s; cy = (k[3] + dy - oy) * s
            source = "LENS_INTRINSIC_CALIBRATION"
        } else if (focal != null && phys != null && pix != null) {
            val pxPerMm = pix.width / phys.width.toDouble() * (aw / pix.width)
            fx = focal * pxPerMm * s; fy = fx; cx = w0 / 2.0; cy = h0 / 2.0
            source = "focal length and sensor size (the lens publishes no calibration)"
        } else return o
        // the stream's quarter turns clockwise
        repeat(streamTurns % 4) {
            val nfx = fy; val nfy = fx
            val ncx = (if (it % 2 == 0) h0 else w0) - cy; val ncy = cx
            fx = nfx; fy = nfy; cx = ncx; cy = ncy
        }
        o.put("stream_intrinsics", JSONObject().apply {
            put("fx", round(fx)); put("fy", round(fy)); put("cx", round(cx)); put("cy", round(cy))
            put("k", JSONArray(listOf(0, 0, 0, 0))); put("width", streamW); put("height", streamH); put("source", source)
        })
        return o
    }

    // ------------------------------------------------------------------------------------------------ the server
    private var serverStarted = false

    /** GET /status (any path): the JSON above. One small thread; started once with the app. */
    fun startServer(context: Context) {
        if (serverStarted) return
        serverStarted = true
        val app = context.applicationContext
        thread(name = "skeleton-status", isDaemon = true) {
            val server = try { ServerSocket(STATUS_PORT) } catch (e: Exception) { Trace.fault("skeleton status", e); return@thread }
            Trace.step("skeleton: status on $STATUS_PORT")
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { continue }
                thread(isDaemon = true) {
                    runCatching {
                        s.soTimeout = 3000
                        val r = BufferedReader(InputStreamReader(s.getInputStream()))
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        val body = status(app).toString(1).toByteArray()
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                        s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                    }
                    runCatching { s.close() }
                }
            }
        }
    }
}
