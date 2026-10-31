package com.mantraproductions.ndi.sony

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * JAM THE PHONE FROM A CAMERA'S SCREEN (Marko, 29.10.2026: "You need to jam the phone to my screen of my 6600, and then
 * when it's jammed, I can walk to another camera and just jam it.").
 *
 * The back camera films the camera's screen; ML Kit's on-device text recognition reads "HH:MM:SS:FF" in each frame; each
 * reading is paired with the moment the frame was exposed (the sensor's timestamp, on the phone's elapsed-realtime clock,
 * turned into wall time). [onReading] gets (time code in ms of the day, wall time in ms) for every good reading.
 */
@SuppressLint("MissingPermission")   // the activity asks for CAMERA first
class ScreenReader(private val context: Context, private val view: TextureView, private val fps: Int,
                   private val onReading: (tcMs: Long, wallMs: Long, text: String) -> Unit) {
    private val thread = HandlerThread("screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null
    @Volatile private var busy = false
    /** Where to read, as a share of the picture's width and height (the box Marko drags; 0.5, 0.5 = the middle). */
    /** Two fingers zoom the camera itself (Marko, 29.10.2026: "Can I also use two fingers to zoom in ... and then I can
     *  also make more image available for this square?"): the sensor's own pixels, so the digits get sharper. */
    var zoom = 1f
        set(v) { field = v.coerceIn(1f, maxZoom); handler.post { repeat() } }
    var maxZoom = 1f
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    /** Pan (Marko, 29.10.2026: "tap and drag to pan if I'm outside of this square"): the crop's centre on the sensor,
     *  as a share of its width and height; zoom and pan are one crop of the sensor's own pixels. */
    private var cx = 0.5; private var cy = 0.5
    private var active = android.graphics.Rect(0, 0, 4000, 3000)
    private var sensorDeg = 90
    fun pan(dxView: Float, dyView: Float, viewW: Int, viewH: Int) {
        // the finger's move on the screen, turned onto the sensor's axes, as a share of what is shown
        val fx = dxView.toDouble() / viewW / zoom; val fy = dyView.toDouble() / viewH / zoom
        val turn = (sensorDeg - deviceDegrees() + 360) % 360
        val (sx, sy) = when (turn) { 90 -> fy to -fx; 180 -> -fx to -fy; 270 -> -fy to fx; else -> fx to fy }
        cx = (cx - sx).coerceIn(0.5 / zoom, 1 - 0.5 / zoom); cy = (cy - sy).coerceIn(0.5 / zoom, 1 - 0.5 / zoom)
        handler.post { repeat() }
    }
    private fun repeat() {
        val r = request ?: return
        val w = (active.width() / zoom).toInt(); val h = (active.height() / zoom).toInt()
        cx = cx.coerceIn(0.5 / zoom, 1 - 0.5 / zoom); cy = cy.coerceIn(0.5 / zoom, 1 - 0.5 / zoom)
        val l = active.left + (active.width() * cx).toInt() - w / 2; val t = active.top + (active.height() * cy).toInt() - h / 2
        r.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(l, t, l + w, t + h))
        runCatching { session?.setRepeatingRequest(r.build(), null, handler) }
    }
    @Volatile var targetX = 0.5
    @Volatile var targetY = 0.5
    @Volatile private var running = true
    private val tc = Regex("""(\d{2})\s*[:;.,]\s*(\d{2})\s*[:;.,]\s*(\d{2})\s*[:;.,]\s*(\d{2})""")

    fun start() {
        if (view.isAvailable) open() else view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = open()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun open() {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val sensor = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val ch = cm.getCameraCharacteristics(id)
        maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: active
        sensorDeg = sensor
        val realtime = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val r = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3).also { reader = it }
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (busy || !running) { img.close(); return@setOnImageAvailableListener }
            busy = true
            // the moment this frame was exposed, as wall time
            val ts = img.timestamp
            val wall = if (realtime) System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - ts) / 1_000_000
                       else System.currentTimeMillis()
            // the frame turned as the phone is held, so the digits stand upright for the recognizer
            val turn = (sensor - deviceDegrees() + 360) % 360
            val (w, h) = if (turn % 180 == 0) img.width to img.height else img.height to img.width
            recognizer.process(InputImage.fromMediaImage(img, turn))
                .addOnSuccessListener { t ->
                    // two cameras in the shot (Marko, 29.10.2026: "How does it know which one to sync?"): the time code
                    // nearest the centre of the picture, where the reticle is
                    val m = t.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val found = tc.find(line.text.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1'))
                        val b = line.boundingBox
                        if (found == null || b == null) null
                        else found to Math.hypot(b.exactCenterX() - w * targetX, b.exactCenterY() - h * targetY)
                    }.minByOrNull { it.second }?.first
                    if (m != null) {
                        val (h, mi, s, f) = m.destructured.toList().map { it.toInt() }
                        if (h < 24 && mi < 60 && s < 60 && f < fps)
                            onReading(((h * 60L + mi) * 60 + s) * 1000 + f * 1000L / fps, wall, m.value)
                    }
                }
                .addOnCompleteListener { img.close(); busy = false }
        }, handler)
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                val preview = Surface(view.surfaceTexture!!.apply { setDefaultBufferSize(1280, 720) })
                d.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview); addTarget(r.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        session = s; request = req
                        repeat()
                        view.post { fit() }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, handler)
            }
            override fun onDisconnected(d: CameraDevice) = d.close()
            override fun onError(d: CameraDevice, e: Int) = d.close()
        }, handler)
    }

    private fun deviceDegrees() = when (view.display?.rotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }

    /** Sideways the picture must be turned back by hand (TextureView does not); upright it is already right. */
    fun fit() {
        val r = view.display?.rotation ?: return
        val vw = view.width.toFloat(); val vh = view.height.toFloat()
        val m = android.graphics.Matrix()
        if (r == Surface.ROTATION_90 || r == Surface.ROTATION_270) {
            val viewRect = android.graphics.RectF(0f, 0f, vw, vh)
            val bufRect = android.graphics.RectF(0f, 0f, 720f, 1280f)
            bufRect.offset(viewRect.centerX() - bufRect.centerX(), viewRect.centerY() - bufRect.centerY())
            m.setRectToRect(viewRect, bufRect, android.graphics.Matrix.ScaleToFit.FILL)
            val scale = maxOf(vh / 720f, vw / 1280f)
            m.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
            m.postRotate(90f * (r - 2), viewRect.centerX(), viewRect.centerY())
        } else if (r == Surface.ROTATION_180) m.postRotate(180f, vw / 2, vh / 2)
        view.setTransform(m)
    }

    fun stop() {
        running = false
        runCatching { device?.close() }; runCatching { reader?.close() }
        recognizer.close(); thread.quitSafely()
    }
}
