package com.mantraproductions.ndi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The camera, and the two rails of keys in the black beside it.
 *
 *     LENS 1..4   the physical lenses this phone actually has
 *     AF          the focus director: hold, notice, rack over a beat
 *     PEAK        edge detector on the monitor only
 *     LOG         which log curve the phone's tone mapper is applying
 *     ROT         a quarter turn of the preview, by hand, remembered per lens
 *     M / CTRL    manual exposure, and the zones on the picture
 *     NDI         off, HX, full, off — one stream, so one key
 *     FULL        the clean feed: the picture and nothing else on the glass
 *
 *     REC         the take, at the top of the other rail
 *     LGHT        the lamp
 *     SNAP        the sensor's own frame as a DNG, uncorrected
 *     1..11       the LUT slots, five at a time, then the gear
 *
 * The lamp and the stills key moved to the right rail in v80: two keys fewer
 * on the left is two keys' worth of height shared among the ten that are left,
 * and they belong beside record anyway — they are what a right thumb reaches
 * for without taking the left hand off the lens keys. Nothing is drawn round a
 * key any more either. A box costs an outline, a corner, an inset and a margin,
 * and every one of those is taken off the word inside it.
 *
 * The audio meter runs down the inside edge of the picture whenever the app
 * does, because a dead microphone found after the take is a take that happens
 * again.
 *
 * Grey is off and green is on, everywhere, with no exceptions — that is the
 * whole of the interface language and it is why the keys can be this small.
 * Dark is a key this phone cannot honour, left in place rather than hidden so
 * the rail does not reshuffle itself between a Pixel 7 and a Pixel 7 Pro.
 */
/** v139: the action of the adb control (MainActivity.adbControl, scripts/mcam). */
private const val ADB_ACTION = "com.mantraproductions.manualcamera.ADB"
/** v145: how wide the record button's picture of the last capture is kept, in pixels. */
private const val THUMB_PX = 192
/** v145: the free-space key, which opens the settings, in yellow. */
private val STORAGE_YELLOW = android.graphics.Color.parseColor("#F5D90A")
/** v155: the POWER MONITOR's readout, blue beside the drive's yellow. */
private val POWER_BLUE = android.graphics.Color.parseColor("#4DB8FF")
private val POWER_GREEN = android.graphics.Color.parseColor("#33D17A")
/** v155 (Marko, 26.10.2026: "make the color of the camera ... orange"): the photo key. */
private val SNAP_ORANGE = android.graphics.Color.parseColor("#FF9500")
/** v155: how long a finger stays on a key before its help opens. */
private const val HELP_MS = 2000L
/** v155: the power readout is written this often (the measuring goes on every second). */
private const val POWER_SHOW_MS = 5000L

class MainActivity : AppCompatActivity() {

    private lateinit var preview: TextureView
    private lateinit var focusSquare: FocusSquareView
    private lateinit var focusMeter: FocusMeterView
    private lateinit var exposureCircle: MarkView
    private lateinit var zones: ControlZones
    private lateinit var fullScreenCatcher: View
    private lateinit var status: TextView
    private lateinit var telemetry: TextView
    private lateinit var vu: VuMeterView
    private lateinit var iris: TextView
    private lateinit var stage: LinearLayout
    private lateinit var railLeft: LinearLayout
    private lateinit var railRight: LinearLayout

    private lateinit var pipeline: CameraPipeline
    private lateinit var slots: LutSlots
    private lateinit var settings: Settings
    private lateinit var focus: FocusDirector

    private var lenses: List<CameraCatalogue.Lens> = emptyList()
    private var activeLens = 0
    private var activeSlot = 0            // 0 means no LUT
    private var curveIndex = 0
    private var manualQuarterTurns = 0

    /** The camera's own turn, as last decoded from the texture matrix. */
    private var lastProducerDegrees = -1

    /**
     * Whether the monitor shows the picture mirrored (the camera's producer
     * transform on the direct path; never on the GPU stage, which draws the
     * raw buffer). Screen and sensor are mapped through this, not through
     * "front camera", which v97 made two different questions.
     */
    private var previewMirrored = false

    /** TRK (v99): the focus box is a tracking mark. */
    private var tracking = false
    private var lastFocusX = 0.5f
    private var lastFocusY = 0.5f
    private var peaking = false
    private var bufferSize = Size(1920, 1080)

    /** Manual exposure, and whether the zones on the picture are listening. */
    /**
     * A / M per parameter (v87). ISO and shutter each have their own switch;
     * focus is the focus director's mode. The M key's AUTO / HM / FM is read
     * off these three, never stored beside them. White balance is not one of
     * them since v90: it is always a held number, and its A is a measurement.
     */
    private var isoAuto = true
    private var shutterAuto = true

    /** The last half-manual set, for the M key to put back. */
    private var lastHalf: BooleanArray? = null

    /** The brightness the half-manual loop holds, and whether the camera does it itself. */
    private var loopTarget = 0.0
    private var loopNative = false
    private var loopSentIso = -1
    private var loopSentShutterNs = -1L
    private var zonesOn = false

    /**
     * The clean feed: the picture and nothing else at all.
     *
     * He broadcasts this phone by mirroring its screen rather than over NDI,
     * and everything this app draws goes down that wire with the picture — the
     * rails, the status line, the geometry, the audio meter. So `FULL` takes
     * all of it off, and the phone's own status and navigation bars with it,
     * and leaves a picture on black. A double tap anywhere brings the camera
     * back; so does the back key.
     *
     * Nothing about the camera changes: a take goes on being written and NDI
     * goes on being sent while this is on. It is a mode of the *screen*.
     */
    private var fullScreen = false
    private var iso = 400
    private var shutterNs = 1_000_000_000L / 60
    private var focusFraction = 0f

    /**
     * Where each zone's knob is, 0..1, as the thumb left it.
     *
     * **This is "the slider stops two thirds of the way along and is very
     * strange to move".** The knob used to be drawn from the value the camera
     * reported, while the drag moved a fixed six stops per swipe — two
     * different instruments wearing one hat. The camera will not expose for
     * longer than a frame, so at 30fps it clamps at 1/30 whatever the sensor
     * says it can do, and 1/30 is two thirds of the way along a range that
     * reaches 1/92030 at the other end. A third of the track could not be
     * reached and nothing on the screen said why.
     *
     * So the position is the instrument: the whole track is the whole of the
     * range the camera will honour, the knob goes exactly where the thumb puts
     * it, and the value is read off the position. Seeded from what the camera
     * is doing whenever it is taken over, so nothing jumps.
     */
    private var isoPosition = 0.5f
    private var shutterPosition = 0.5f

    /** Said once per lens, not once per drag. */
    private var focusComplaintFor = ""

    /** Colour temperature: tungsten at the left of the fader, daylight right. */
    private var wbAuto = true
    private var wbKelvin = 5600

    /**
     * The microphone, held for as long as the app is in front.
     *
     * One reader, not two. The old build gave the mic to a meter and took it
     * back for the encoder when a take started, and a handover is a thing that
     * can fail — when it failed, the take had no sound and the meter said it
     * did. Now the meter owns it and the take borrows its samples.
     */
    private var meter: AudioMeter? = null
    private var recordingSince = 0L
    private var microphoneAsked = false

    private val lensKeys = mutableListOf<RailButton>()
    private lateinit var recKey: RecordButtonView
    private lateinit var playKey: RailButton
    private lateinit var lastKey: RecordButtonView
    private lateinit var shootKey: RailButton
    private lateinit var lutKey: RailButton
    private lateinit var falseKey: RailButton
    private lateinit var zebraKey: RailButton
    private lateinit var storageKey: RailButton
    /** v155: the POWER MONITOR, beside the drive's time left (Marko, 26.10.2026). */
    private lateinit var powerMonitor: PowerMonitor
    private lateinit var powerKey: RailButton
    private var lastPower: PowerMonitor.Reading? = null
    private lateinit var timecode: TextView

    /** The two exposure tools beside peaking. Monitor only, like peaking. */
    private var falseColour = false
    private var zebra = false
    private lateinit var focusKey: RailButton
    private lateinit var lensChooser: RailButton
    private var lensesOpen = false
    private var markKeys: List<RailButton> = emptyList()
    private lateinit var peakKey: RailButton
    private lateinit var snapKey: RailButton
    private lateinit var logKey: RailButton
    private lateinit var manualKey: RailButton
    private lateinit var ctrlKey: RailButton
    private lateinit var screenKey: RailButton

    private val ui = Handler(Looper.getMainLooper())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Trace.open(this)
        Trace.markStart()
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        focusSquare = findViewById(R.id.focusSquare)
        exposureCircle = findViewById(R.id.exposureCircle)
        focusMeter = findViewById(R.id.focusMeter)
        zones = findViewById(R.id.zones)
        fullScreenCatcher = findViewById(R.id.fullScreenCatcher)
        status = findViewById(R.id.status)
        telemetry = findViewById(R.id.telemetry)
        vu = findViewById(R.id.vu)
        iris = findViewById(R.id.iris)
        stage = findViewById(R.id.stage)
        railLeft = findViewById(R.id.railLeft)
        railRight = findViewById(R.id.railRight)
        timecode = findViewById(R.id.timecode)
        showTimecode(false)

        pipeline = CameraPipeline(this)
        slots = LutSlots(this)
        settings = Settings(this)
        applyTestExtras(intent)
        manualQuarterTurns = settings.quarterTurns
        lenses = CameraCatalogue.lenses(this)
        // v148: the lens it was left on (it always opened on L1, the ultra wide with fixed focus; Marko: "lens two is the best")
        activeLens = (settings.lens - 1).coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
        Trace.state(
            "lenses: " + lenses.joinToString {
                it.label + " [" + it.id + (it.physicalId?.let { p -> ":$p" } ?: "") + "]"
            }
        )

        focus = FocusDirector(
            controls = { if (pipeline.isRunning) pipeline.engine else null },
            onState = { s -> ui.post {
                focusSquare.state = s
                when (s) {
                    FocusSquareView.State.LOCKED -> { focusLockedAt = focusLockText(); say("Focus locked") }
                    FocusSquareView.State.SEEKING -> focusLockedAt = null
                    else -> Unit
                }
            } }
        )

        buildRails()
        startLink()
        applyShootMode()
        layoutForOrientation(resources.configuration.orientation)

        // Android draws its camera-in-use indicator over the top of the screen
        // whenever this app is doing its job, and on the first run it sat on
        // top of the HX key. The rails are inset out of the system's way.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // v149: the focus puller's meter
        focusMeter.keys = Mechanism.decodeKeys(settings.focusKeys)
        focusMeter.onRack = { dpt -> rackTo(dpt) }
        focusMeter.onPull = { dpt -> pullTo(dpt) }
        // v151: the long press on a circle asks (Marko: "it's a long press, and then the dialog opens: Add Keyframe or
        // Delete Keyframe")
        focusMeter.onLongPress = { onLens, index ->
            val at = (if (onLens) pipeline.engine.lastFocusDistance else focusMeter.keys.getOrNull(index))
                ?.let { Mechanism.formatFocusDistance(it) } ?: ""
            // v151 (Marko: "Add Keyframe and Remove Keyframe, both options. It's the keyframe column, and then two yellow
            // buttons: Add or Remove"): ADD where the lens is, REMOVE the keyframe pressed or the one the lens stands on
            val keyAt = focusMeter.keys.getOrNull(index)      // v153: removed by its value, not a list place that may move
            val d = android.app.AlertDialog.Builder(this)
                .setTitle("KEYFRAME:  $at")
                .setNeutralButton("ADD") { _, _ -> focusMeter.addKeyAtLens() }
                .setPositiveButton("REMOVE") { _, _ -> keyAt?.let { v -> focusMeter.deleteKey(focusMeter.keys.indexOfFirst { it == v }) } }
                .show()
            val yellow = RailButton.AMBER
            d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.apply { setTextColor(yellow); textSize = 16f }
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                textSize = 16f
                isEnabled = index >= 0
                setTextColor(if (index >= 0) yellow else RailButton.GREY)
            }
        }
        focusMeter.onKeysChanged = { keys ->
            settings.focusKeys = Mechanism.encodeKeys(keys)
            Trace.control("focus puller", "key points", keys.joinToString { Mechanism.formatFocusDistance(it) })
        }
        focusSquare.size = settings.focusBoxSize
        focusSquare.onMoved = { x, y ->
            focus.target = x to y
            focus.bounds = focusSquare.normalisedBounds()
            updateFocusRegion()
            // A tap or a drag in TRK is a new subject: the box becomes the new pattern.
            if (tracking) startTrack()
        }
        focusSquare.onResized = { size ->
            settings.focusBoxSize = size
            if (tracking) startTrack()
        }
        pipeline.trackListener = GpuStage.TrackListener { x, y, score -> ui.post { onTracked(x, y, score) } }
        focusSquare.onTapped = { rackTicket++; focus.focusHereAndHold(); alsoUnder(focusSquare.tapX, focusSquare.tapY, 0) }
        // v103: the circle. A tap reads there and locks; where it is dragged is kept. v143: the one circle does
        // ISO or white balance (its mode, written under it); a long press chooses, and sets the shutter.
        exposureCircle.kind = MarkView.Kind.CIRCLE
        exposureCircle.size = settings.circleSize
        exposureCircle.label = circleLabel()
        exposureCircle.post { exposureCircle.moveTo(settings.circleX, settings.circleY) }
        exposureCircle.onMoved = { x, y -> settings.circleX = x; settings.circleY = y; exposureCircle.state = MarkView.State.IDLE }
        exposureCircle.onTapped = { fireCircle(); alsoUnder(exposureCircle.tapX, exposureCircle.tapY, 1) }
        exposureCircle.onLongPressed = { openCircleMenu() }
        focusSquare.onStateChanged = { refreshKeys() }
        exposureCircle.onStateChanged = { refreshKeys() }
        focusSquare.onTapFor = { x, y -> tapAt(x, y) }
        refreshMarks()

        onBackPressedDispatcher.addCallback(this, leaveFullScreen)
        // The clean feed's only control: two taps anywhere on the glass. One
        // tap is what a phone gets by accident while it is being carried.
        val leaving = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    setFullScreen(false)
                    return true
                }
                // One tap focuses where it lands, box or no box. Confirmed
                // single, so the first half of the double tap never racks.
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    val picture = findViewById<View>(R.id.picture)
                    val at = IntArray(2)
                    picture.getLocationOnScreen(at)
                    val x = (e.rawX - at[0]) / picture.width.coerceAtLeast(1)
                    val y = (e.rawY - at[1]) / picture.height.coerceAtLeast(1)
                    // The black round the picture is not a place to focus on.
                    // v105: a tap serves the armed shape, in the clean feed as everywhere
                    if (x in 0f..1f && y in 0f..1f) tapAt(x, y)
                    return true
                }
                override fun onDown(e: android.view.MotionEvent) = true
            }
        )
        fullScreenCatcher.setOnTouchListener { view, event ->
            view.performClick()
            leaving.onTouchEvent(event)
        }

        zones.onDrag = { index, delta -> nudge(index, delta) }
        zones.onGrab = { refreshZones() }
        zones.onToggle = { index -> toggleParam(index) }
        zones.onPreset = { index, preset -> applyPreset(index, preset) }
        zones.onSingleTap = { x, y -> tapAt(x, y) }

        // The trace is the only instrument that reaches a phone with no cable,
        // so it is always one long press away rather than behind a menu.
        status.setOnLongClickListener { exportTrace(); true }

        // v104: any change that stopped the picture was taken back by the engine; say so, in his words
        pipeline.engine.onRequestFroze = {
            ui.post {
                say("That setting is not available on this phone — put back as it was")
                refreshZones(); refreshKeys()
            }
        }
        pipeline.engine.onCurveRefused = { refused, back ->
            ui.post {
                curveIndex = back.ordinal
                refusedCurves.add(lensKey() + "/" + refused.name)
                settings.refusedCurves = refusedCurves
                risk = null
                say("${curveLabel(refused)} is not supported on this lens — back to ${curveLabel(back)}")
                refreshKeys()
            }
        }

        pipeline.listener = object : CameraPipeline.Listener {
            override fun onReady(tenBit: Boolean, codec: String, size: Size) {
                ui.post {
                    bufferSize = size
                    holdBufferSize()
                    applyPreviewTransform()
                    say("${size.width}x${size.height} ${if (tenBit) "10-bit" else "8-bit"} $codec" +
                        // v148: said on the screen, not only in the trace (it fell back unseen on every open until v148)
                        if (settings.gpuStage && !pipeline.usesGpuStage) " · GPU STAGE could not start: direct path, no tracking" else "")
                    showMeter()                     // v149: a lens that focuses has the meter, a fixed one none
                    reopenTries = -1                // v116: up again, the recovery is over
                    // v104: the light, switched in settings, is put on whenever the camera opens
                    pipeline.engine.setTorchLevel(settings.torchLevel)
                    if (settings.torch) pipeline.engine.setTorch(true)
                    restoreMode = CameraPipeline.Mode.OFF
                    // v136: the stream stands the way the phone is held
                    updateStreamTurns()
                    // v130: NDI runs whenever it is armed, from the moment the camera is up (item 47)
                    followNdiSwitch()
                    // A new session starts from the template, which is all
                    // automatic: his A / M switches are put back on it.
                    if (!isoAuto || !shutterAuto) applyExposure()
                    focus.resume()
                    // A new session is a new GPU stage: the mark is set again where it is.
                    if (tracking) ui.postDelayed({ if (tracking) startTrack() }, 400)
                    loadWbCurve()
                    if (!wbMeasuredOnce) probeWhiteBalance()
                    refreshZones()
                    refreshKeys()
                }
            }
            override fun onError(message: String) {
                ui.post {
                    say(message); Trace.refused("pipeline", message)
                    // v115: a dead camera is opened again, and a risky step that killed it is taken back
                    if (message.startsWith("Camera error")) onCameraDied(message)
                    else retryOpen(message)
                }
            }
            override fun onRate(fps: Double, megabitsPerSecond: Double, connections: Int) {
                ui.post { showRate(fps, megabitsPerSecond, connections); refreshZones(); watchEncoder(fps) }
            }
        }

        // THE STRETCH.
        //
        // `setTransform` is in the view's own coordinates and it does not
        // follow the view when the view changes size. The transform was being
        // computed on surface-size changes only — and a TextureView inside a
        // weighted LinearLayout is measured more than once before it settles,
        // so the matrix could be worked out against one width and left applied
        // to another. Scaled about the centre, that is exactly a squeeze, and
        // it explains the thing that made no sense: the NDI output was normal
        // while the preview was not, from the same buffer, with the readout
        // reporting squeeze 1.000 — because by the time it printed, the numbers
        // were right and the matrix was old.
        //
        // The transform is now recomputed whenever the view is laid out, which
        // is the only moment that can ever invalidate it.
        preview.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) {
                holdBufferSize()
                applyPreviewTransform()
            }
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                // v136: the stream ran on in the background; the camera is open, only the window comes back.
                if (pipeline.isRunning && pipeline.usesGpuStage) {
                    holdBufferSize()
                    pipeline.attachPreview(Surface(t))
                    applyPreviewTransform()
                    updateStreamTurns()
                    Trace.state("preview back on the running pipeline")
                } else openCamera()
            }
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {
                // The view has just taken the buffer size for itself.
                holdBufferSize()
                applyPreviewTransform()
            }
            /**
             * The producer's transform is not known until frames are flowing.
             *
             * It is empty before the first frame and it can change when the
             * camera reconfigures, so the one moment it can be trusted is on a
             * frame. Decoded here and acted on only when it actually changes,
             * which is a handful of times in a session rather than thirty times
             * a second.
             */
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {
                val m = FloatArray(16)
                runCatching { t.getTransformMatrix(m) }
                if (Mechanism.producerRotation(m) != lastProducerDegrees) applyPreviewTransform()
            }

            /**
             * The session's surface dies with the TextureView. The pipeline is
             * torn down here rather than left pointing at a surface that is
             * gone, which is what made the picture come back frozen.
             */
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                // v136: streaming on in the background: only the window goes.
                if (keptRunning && pipeline.isRunning) pipeline.detachPreview() else pipeline.stop()
                return true
            }
        }
    }

    // --- the rails -----------------------------------------------------------

    private fun buildRails() {
        // As many lens keys as this phone has lenses, and no more. Four were
        // always drawn with the missing ones dark; a key for a lens that does
        // not exist is not a control.
        // v103: ONE key, L (Marko, 2.10.2026: "This lenses chooser is taking too much space ... L, only one
        // icon. When user press L, they uncollapse. User choose one lens and then it collapse again"). Its
        // whisper is the lens in use; the lens keys appear beside it only while it is open.
        lensChooser = newKey("L") { openDrawer(!lensesOpen) }.also { railLeft.addView(it) }
        // v130, THE LENS DRAWER (Marko, 3.10.2026: "when I press lens, it should actually take all available space
        // ... all other icons are gone ... millimeters ... next to the letter ... aperture opening for each lens and
        // resolution"). Each lens key reads "L1 24mm" over "f/1.85 · 12MP · 4K".
        for (i in 1..lenses.size) {
            val lens = lenses[i - 1]
            val mm = lens.equivalentMm.takeIf { it > 0 }?.let { " ${it}mm" } ?: ""
            val video = runCatching { pipeline.widthsOffered(lens.id, lens.physicalId).maxOrNull() }.getOrNull()
            val res = when { video == null -> null; video >= 3840 -> "4K"; video >= 2560 -> "1440p"; video >= 1920 -> "1080p"; else -> "720p" }
            val sub = listOfNotNull(
                lens.aperture.takeIf { it > 0f }?.let { "f/" + String.format(java.util.Locale.ROOT, "%.2f", it).trimEnd('0').trimEnd('.') },
                lens.megapixels.takeIf { it > 0 }?.let { "${it}MP" },
                res
            ).joinToString(" · ")
            val key = newKey("L$i$mm") { openDrawer(false); chooseLens(i - 1) }
            key.sub = sub.ifEmpty { null }
            key.scale = 1.35f
            key.divider = true
            lensKeys.add(key)
            railLeft.addView(key)
        }
        // v103: the marks' keys, drawn as their marks. Grey = not on the picture, white = on it,
        // orange = the one a pinch resizes (only one). A tap steps grey → white → orange → grey.
        // v143: the square and the circle; the triangle went into the circle's WB mode.
        markKeys = listOf(RailButton.Glyph.SQUARE, RailButton.Glyph.CIRCLE).mapIndexed { i, g ->
            newKey("") { stepMark(i) }.also { it.glyph = g; railLeft.addView(it) }
        }
        focusKey = newKey("AF") { toggleAutoFocus() }.also { railLeft.addView(it) }
        logKey = newKey("LOG") { nextCurve() }.also { railLeft.addView(it) }
        manualKey = newKey("M") { nextCameraMode() }.also { railLeft.addView(it) }
        ctrlKey = newKey("CTRL") { toggleZones() }.also { railLeft.addView(it) }
        // The clean feed, for a phone that is being broadcast by its screen.
        // v131: FULL pressed by the monitor's finger (Mantra Link) makes the MONITOR clean, not this screen
        // (Marko, 3.10.2026: "full screen button to make it full screen on the phone, which I'm not there. Doesn't
        // make any sense"): the monitor then shows the picture, the record key and its counter.
        screenKey = newKey("FULL") {
            if (LinkServer.fromMonitorJustNow()) LinkServer.setClean(!LinkServer.clean) else setFullScreen(!fullScreen)
        }
            .also { railLeft.addView(it) }

        // THE RIGHT RAIL, v85: the take, the look, the measuring tools, and
        // what is left on the drive.
        //
        // *"Right side is record button, preview file button, portrait or
        // landscape shooting, and the LUT switcher ... and the measuring tools
        // ... and peaking, so everything is in one place ... and at the bottom
        // the free space and how much time we can still record."* The eleven
        // LUT slots went to settings; one key here switches between the ones
        // loaded there.
        // v151 (Marko, 26.10.2026: "the record button, should still be the record button as before. But this triangle,
        // which is the playing button, should become this round button with the last picture of the last clip ...
        // Everything is circular. Please make the recording in the place of the lot, in the middle of the screen"):
        // LUT where REC was, PLAY as a round key with the last capture, REC in the middle where the LUT was.
        recKey = RecordButtonView(this).also { it.setOnClickListener { toggleRecording() }; withHelp(it) { "REC" } }
        lutKey = newKey("LUT") { nextLut() }.also {
            it.setOnLongClickListener { openSettings(); true }
            railRight.addView(it)
        }
        // the PLAY key the link and adb press by name ("play"); on the rail it is the round lastKey
        playKey = newKey("") { playLastCapture() }.also { it.glyph = RailButton.Glyph.PLAY }
        lastKey = RecordButtonView(this).also {
            it.playMode = true
            it.setOnClickListener { playLastCapture() }
            withHelp(it) { "PLAY" }
            railRight.addView(it)
        }
        // v145: the last capture, kept from the run before
        stillWriter.execute {
            val kept = runCatching { android.graphics.BitmapFactory.decodeFile(lastCaptureFile().path) }.getOrNull()
            if (kept != null) ui.post { if (lastKey.thumbnail == null) lastKey.thumbnail = kept }
        }
        // SNAP is the app's own icon (v88): the still is this camera's picture.
        snapKey = newKey("") { takeSnap() }.also {
            it.glyph = RailButton.Glyph.CAMERA
            it.tint = SNAP_ORANGE
            railRight.addView(it)
        }
        shootKey = newKey("POR") { nextShoot() }.also { it.glyph = RailButton.Glyph.ROTATE; railRight.addView(it) }
        railRight.addView(recKey)
        peakKey = newKey("PEA") { togglePeaking() }.also { it.glyph = RailButton.Glyph.PEAKING; railRight.addView(it) }
        falseKey = newKey("FC") { falseColour = !falseColour; applyLook(); refreshKeys() }
            .also { it.glyph = RailButton.Glyph.FALSECOLOUR; railRight.addView(it) }
        zebraKey = newKey("ZEB") { zebra = !zebra; applyLook(); refreshKeys() }
            .also { it.glyph = RailButton.Glyph.ZEBRA; railRight.addView(it) }
        // v145: no gear (Marko, 26.10.2026: "remove this settings icon, and clicking on the free space will open the
        // settings. That's why you need to color this free space thing in yellow"). The readout is the way in.
        storageKey = newKey("—") { openSettings() }.also { it.tint = STORAGE_YELLOW; railRight.addView(it) }
        // v155 (Marko, 26.10.2026: "two points of reference that tell me how long I can keep working"): the drive's
        // time left, and under it the battery's, on the ring of the POWER MONITOR; a tap opens all of its numbers
        powerMonitor = PowerMonitor(this)
        powerKey = newKey("—") { showPower() }.also { it.tint = POWER_BLUE; railRight.addView(it) }
    }

    /** v130: the lens drawer opens over the whole left rail (wider in landscape, taller upright) and closes again. */
    private fun openDrawer(open: Boolean) {
        lensesOpen = open
        refreshKeys()
        layoutForOrientation(resources.configuration.orientation)
    }

    /** v131, MANTRA LINK (LinkServer, Link.kt): the monitor sees this window and plays its touches into it. */
    private fun startLink() {
        LinkServer.start(application)
        startAdbControl()
        LinkServer.onKey = { name -> pressKey(name) }
        LinkServer.onModeChanged = { refreshKeys() }
        LinkServer.stateProvider = {
            mapOf(
                "rec" to if (rolling) "1" else "0",
                "since" to if (rolling) (android.os.SystemClock.elapsedRealtime() - recordingSince).toString() else "0",
                "name" to settings.sourceName,
                // v132: what the camera last said, so a clean monitor can show why a press did nothing
                "msg" to lastSaid.replace("=", "-"), "msgAt" to lastSaidAt.toString()
            )
        }
        // the clean picture: the preview as it is drawn (TextureView.getBitmap, on the UI thread)
        LinkServer.cleanSource = { w, h ->
            var b: android.graphics.Bitmap? = null
            val done = java.util.concurrent.CountDownLatch(1)
            ui.post {
                b = runCatching {
                    if (!preview.isAvailable || preview.width <= 0) null else {
                        val k = minOf(w.toFloat() / preview.width, h.toFloat() / preview.height)
                        preview.getBitmap((preview.width * k).toInt().coerceAtLeast(2), (preview.height * k).toInt().coerceAtLeast(2))
                    }
                }.getOrNull()
                done.countDown()
            }
            done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            b
        }
    }

    private fun openSettings() {
        // The lens in use, so ROT in settings turns this lens and no other.
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_LENS, lensKey())
                .putExtra(SettingsActivity.EXTRA_LENS_NAME, lenses.getOrNull(activeLens)?.label)
        )
    }

    private fun newKey(label: String, onTap: () -> Unit): RailButton =
        RailButton(this).apply {
            this.label = label
            setOnClickListener { onTap() }
            withHelp(this) { keyName(this) }
        }

    /**
     * v155 (Marko, 26.10.2026: "long press of any of the action buttons after 2 seconds. Open the help menu explaining
     * what the button does"): a finger held 2 s on a key opens its help, and the key is not pressed. A key with its own
     * long press (LUT: the settings) keeps it.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun withHelp(v: View, name: () -> String) {
        var shown = false
        val open = Runnable { if (!v.isLongClickable) { shown = true; showHelp(name()) } }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { shown = false; v.postDelayed(open, HELP_MS) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(open); if (shown) { v.isPressed = false; return@setOnTouchListener true } }
            }
            false
        }
    }

    private fun showHelp(name: String) {
        if (name == "FC") { showFalseColourLegend(); return }
        val (title, text) = Help.forKey(name, lastPower)
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    /**
     * Across in landscape, down in portrait. Both, like any app on a phone.
     *
     * The keys keep their order and their meaning; only the direction of the
     * rail changes, so a hand that has learned this camera keeps it when the
     * phone turns. Every key is re-sized here because a rail that runs down the
     * side wants square-ish keys and one that runs across the top wants wide
     * ones.
     *
     * This was locked to landscape for two versions, to get the rotation
     * argument down to one case while it was being solved. It is solved — the
     * camera's own quarter turn is read and taken off — so the lock has done
     * its job and the phone can be held either way again.
     */
    private fun layoutForOrientation(orientation: Int) {
        val landscape = orientation != Configuration.ORIENTATION_PORTRAIT
        stage.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        railLeft.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        railRight.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val density = resources.displayMetrics.density
        val margin = (2 * density).toInt()
        for (rail in listOf(railLeft, railRight)) {
            // v130: the open lens drawer takes room for its words, across in landscape and down when upright
            val thickness = ((if (rail === railLeft && lensesOpen) (if (landscape) 150 else 72) else 44) * density).toInt()
            val lp = rail.layoutParams as LinearLayout.LayoutParams
            if (landscape) {
                lp.width = thickness
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = thickness
            }
            rail.layoutParams = lp

            for (i in 0 until rail.childCount) {
                rail.getChildAt(i).layoutParams = LinearLayout.LayoutParams(
                    if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                    if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    weight = 1f
                    setMargins(margin, margin, margin, margin)
                }
            }
        }

        val stageLp = (findViewById<View>(R.id.picture)).layoutParams as LinearLayout.LayoutParams
        if (landscape) {
            stageLp.width = 0
            stageLp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            stageLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            stageLp.height = 0
        }
        stageLp.weight = 1f
        findViewById<View>(R.id.picture).layoutParams = stageLp

        applyPreviewTransform()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Trace.state("rotated: orientation=${newConfig.orientation}")
        layoutForOrientation(newConfig.orientation)
        if (fullScreen) hideSystemBars(true)
    }

    /**
     * **The half turn: landscape, the other way up.**
     *
     * *"If I turn my phone upside down in landscape mode, the phone doesn't
     * follow."* It cannot, and nothing in this app was ever going to hear
     * about it. Turning a phone end for end takes the display from 90° to 270°
     * and changes **nothing else**: the configuration's orientation is
     * landscape either way, the window is the same size, so no configuration
     * change arrives and no layout change arrives — and the preview transform
     * is only ever recomputed when one of those two does. The picture is left
     * standing on its head with no event anywhere to say so.
     *
     * A display listener is the one thing that does hear it. It fires for any
     * change to the display including a rotation that changes nothing else,
     * which is exactly and only the case that was missing.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (preview.isAvailable) applyPreviewTransform()
            updateStreamTurns()
        }
    }

    private val displays: DisplayManager?
        get() = getSystemService(DisplayManager::class.java)

    // --- the camera ----------------------------------------------------------

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Both at once. Asking for the microphone later, at the moment the
            // record key is pressed, is a dialog in the middle of a take.
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                1
            )
            return
        }
        if (lenses.isEmpty()) { say("This phone reports no usable camera"); return }

        val texture = preview.surfaceTexture ?: return
        val lens = lenses.getOrNull(activeLens) ?: lenses.first()

        // The resolution and the frame rate first: they decide the session,
        // and a session can be neither resized nor re-timed once it is built.
        pipeline.maxWidth = settings.captureWidth
        pipeline.fps = settings.fps
        // Each lens carries its own correction. A front camera is a separate
        // sensor in a separate hole and is not always mounted the same way
        // round as the rear ones, so one global quarter turn meant fixing the
        // selfie lens broke the other three.
        // v104: "remove interface orientation from the settings. It can be always at 0" (Marko, 2.10.2026)
        manualQuarterTurns = 0
        focusComplaintFor = ""
        // The buffer size has to be right before the session is built.
        bufferSize = pipeline.previewSizeFor(lens.id, lens.physicalId)
        holdBufferSize()
        applyPreviewTransform()

        val curve = LogCurves.Curve.entries[curveIndex]
        focus.holdMs = settings.focusHoldMs
        focus.rampMs = settings.focusRackMs
        focus.speedMmS = settings.focusSpeedMmS
        focus.trackSpeedMmS = settings.trackSpeedMmS
        pipeline.streamBitRate = settings.streamMbps * 1_000_000
        pipeline.start(
            cameraId = lens.id,
            physicalId = lens.physicalId,
            previewSurface = Surface(texture),
            sourceName = Mechanism.sanitizeSourceName(settings.sourceName),
            curve = curve,
            wantTenBit = settings.wantTenBit,
            bitRate = settings.bitRateMbps * 1_000_000,
            useGpuStage = settings.gpuStage
        )
        applyLook()
        refreshKeys()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            // The microphone alone. The camera is already running; only the
            // meter and the sound on a take were waiting for this.
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMeter()
            else say("No microphone: takes will be silent and the meter is dark")
            return
        }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCamera()
            startMeter()
        } else {
            say("Camera permission refused, there is nothing to show")
        }
    }

    override fun onResume() {
        super.onResume()
        if (keptRunning) resumeKept()
        ui.removeCallbacks(logExposureTick)
        ui.post(logExposureTick)
        ui.removeCallbacks(remoteTick)
        ui.post(remoteTick)
        ui.removeCallbacks(focusLabelTick)
        ui.post(focusLabelTick)
        displays?.registerDisplayListener(displayListener, ui)
        if (fullScreen) hideSystemBars(true)
        // The camera session is lost while backgrounded even with a foreground
        // service, so it is rebuilt rather than tested for.
        if (preview.isAvailable && !pipeline.isRunning) openCamera()
        // v106: the outputs line, shown or hidden in settings
        telemetry.visibility = if (!fullScreen && settings.showOutputs) View.VISIBLE else View.GONE
        placeStatus()
        // v104: back from settings — the marks take their sizes from there, and the light follows its switch
        focusSquare.size = settings.focusBoxSize
        exposureCircle.size = settings.circleSize
        showMeter()                                 // v149: back from the settings, the FOCUS PULLER switch
        focus.speedMmS = settings.focusSpeedMmS
        focus.trackSpeedMmS = settings.trackSpeedMmS
        focus.holdMs = settings.focusHoldMs
        focus.rampMs = settings.focusRackMs
        if (pipeline.isRunning && pipeline.engine.hasFlash()) {
            pipeline.engine.setTorchLevel(settings.torchLevel)
            pipeline.engine.setTorch(settings.torch)
        }
        // Settings may have removed the LUT that was on.
        if (activeSlot > 0 && !slots.slot(activeSlot).loaded) { activeSlot = 0; applyLook() }
        refreshStorage()
        refreshKeys()
        startMeter()
        ui.removeCallbacks(rateTick)
        ui.post(rateTick)
        updateStreamTurns()
    }

    override fun onPause() {
        super.onPause()
        runCatching { displays?.unregisterDisplayListener(displayListener) }
        ui.removeCallbacks(rateTick)
        ui.removeCallbacks(focusWatch)
        ui.removeCallbacks(logExposureTick)
        ui.removeCallbacks(remoteTick)
        ui.removeCallbacks(focusLabelTick)
        focus.stop()
        // v136: NDI GOES ON IN THE BACKGROUND. While it streams on the GPU path, leaving the app (home, another app,
        // the screen off, settings) keeps the camera, the microphone and the sender running under the foreground
        // service; only the preview window is let go. The ticks that watch the stream and the wire keep running.
        if (pipeline.isRunning && pipeline.mode != CameraPipeline.Mode.OFF && pipeline.usesGpuStage) {
            keptRunning = true
            keptWith = snapshot()
            if (!StreamService.running) StreamService.start(this, "Streaming NDI on " +
                Routing.wireLabel(settings.ndiTransport, pipeline.boundAdapter))
            ui.removeCallbacks(rateTick); ui.post(rateTick)
            ui.removeCallbacks(remoteTick); ui.post(remoteTick)
            Trace.state("background: NDI ${pipeline.mode} goes on, the preview is let go")
            return
        }
        ui.removeCallbacks(rateTick)
        ui.removeCallbacks(remoteTick)
        // The file is closed before the camera goes, not after: a take whose
        // encoder disappeared underneath it has no moov atom and opens nowhere.
        if (rolling) stopAll()
        pipeline.stop()
        StreamService.stop(this)
        meter?.stop()
        meter = null
        vu.dead = true
        vu.reset()
        refreshKeys()
    }

    /** v136: the pipeline was kept running behind other apps (NDI in the background). */
    private var keptRunning = false
    private var keptWith: List<Any?> = emptyList()

    /** v136: what, changed in settings while the stream ran on, needs the camera (or the meter) made again. */
    private fun snapshot(): List<Any?> = listOf(
        settings.captureWidth, settings.fps, settings.gpuStage, settings.wantTenBit, settings.bitRateMbps,
        settings.streamMbps, settings.sourceName, settings.ndiKind, settings.ndiTransport, settings.armNdi,
        settings.audioSource, settings.audioStereo, settings.ndiAudio
    )

    /**
     * v136: back in front after the stream ran on. The preview comes back through the surface callback; here, what
     * settings changed meanwhile is applied: the camera's own decisions need it made again, the wire and the kind
     * need the sender made again, the sound needs the meter made again.
     */
    private fun resumeKept() {
        keptRunning = false
        applyChanged(keptWith)
    }

    /** v144 (out of resumeKept): what changed in the settings since [was] is applied to the running camera. */
    private fun applyChanged(was: List<Any?>) {
        val now = snapshot()
        if (was.isEmpty() || now == was) return
        val camera = (0..6).any { now[it] != was[it] }
        val sound = (10..12).any { now[it] != was[it] }
        Trace.state("back in front: settings changed (camera=$camera, sound=$sound)")
        if (camera) {
            pipeline.stop()
            if (preview.isAvailable) openCamera()
        } else {
            NdiAudio.wanted = settings.ndiAudio
            if ((7..9).any { now[it] != was[it] }) {
                pipeline.setMode(CameraPipeline.Mode.OFF)
                followNdiSwitch()
            }
        }
        if (sound) { meter?.stop(); meter = null; startMeter() }
    }

    /**
     * The meter runs whenever the app is in front, take or no take.
     *
     * That is the whole point of having one. A microphone that is dead, muted
     * at the socket, or pointed at nothing is something to find out about
     * before the take, and a meter that only appears once recording starts
     * reports it afterwards, when it is a reshoot rather than a fix.
     */
    private fun startMeter() {
        if (meter?.isRunning == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            vu.dead = true
            Trace.refused("audio meter", "no microphone permission")
            // Asked here rather than only beside the camera.
            //
            // On a phone that already had this app installed, the camera was
            // granted long ago, so the pair of permissions beside `openCamera`
            // is never reached and the microphone is never asked for at all —
            // which is why his first take came out silent with "no microphone
            // permission" in the trace and no dialog in sight. Asked once.
            if (!microphoneAsked) {
                microphoneAsked = true
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), 2
                )
            }
            refreshKeys()
            return
        }
        // v135: the microphone settings name (AUTO, the phone's, or a USB-C / wired device), mono or stereo.
        val m = AudioMeter(this, settings.audioSource, settings.audioStereo,
            onLevel = { rms -> ui.post { vu.setLevel(rms) } },
            onRoute = { label, asChosen ->
                audioLabel = label
                if (!asChosen) say("${Routing.choiceLabel(settings.audioSource)} is not plugged in — sound from $label")
                else say("Sound: $label")
                refreshTelemetry()
            })
        if (m.start()) {
            meter = m
            vu.dead = false
        } else {
            vu.dead = true
        }
        refreshKeys()
    }

    private var storageTicks = 0

    private val rateTick = object : Runnable {
        override fun run() {
            if (pipeline.isRunning) pipeline.sampleRate()
            if (++storageTicks % 5 == 0) refreshStorage()
            runCatching { powerMonitor.read() }.getOrNull()?.let { refreshPower(it) }
            if (storageTicks % 2 == 0) watchWire()
            // v136: the stream watchdog, and the service that keeps the stream alive behind other apps
            pipeline.watchStream()?.let { Trace.refused("stream watch", it) }
            syncService()
            ui.postDelayed(this, 1000)
        }
    }

    // --- the take -------------------------------------------------------------

    /**
     * Record, independent of both NDI keys.
     *
     * A camera that can only record while it is streaming is not a camera. The
     * encoder is in the session whether or not anything is being sent, so the
     * file costs a write and the same frames go to the wire and to the card.
     */
    /** Which way the screen is turned, in degrees (Surface.ROTATION_*). */
    private fun displayDegrees(): Int = when (
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
    ) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun toggleRecording() {
        if (rolling) stopAll() else startAll()
    }

    /** The master trigger is engaged: every armed destination is running (v91). */
    private var rolling = false

    /**
     * THE RECORD KEY (v130): the take, and only the take.
     *
     * v91 started every armed destination from this key. Marko, 3.10.2026: *"NDI Stream should start actually as
     * soon as it's enabled. It's not any more connected with the record button."* So NDI follows its switch in
     * settings ([followNdiSwitch]) and the record key records the file; the record-run timecode counts from here.
     */
    private fun startAll() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        if (!settings.armFile) {
            say("FILE is not armed — arm it in settings (the gear)")
            return
        }
        rolling = true
        recordingSince = android.os.SystemClock.elapsedRealtime()
        if (!startRecording()) { rolling = false; refreshKeys(); return }
        Trace.control("record", "start", "FILE")
        showTimecode(true)
        refreshTelemetry()
        refreshKeys()
    }

    /** The take stops; NDI goes on as long as it is armed. */
    private fun stopAll() {
        rolling = false
        if (pipeline.isRecording) stopRecording()
        Trace.control("record", "stop", "FILE")
        showTimecode(false)
        refreshTelemetry()
        refreshKeys()
    }

    /**
     * v130: the NDI stream follows its switch, not the record key. Armed: it runs as soon as the camera is open, the
     * kind chosen in settings (HX or FULL). Not armed: it stops. Called whenever the camera comes up.
     */
    private fun followNdiSwitch() {
        if (!pipeline.isRunning) return
        pipeline.transport = settings.ndiTransport
        NdiAudio.wanted = settings.ndiAudio
        if (!settings.armNdi) {
            if (pipeline.mode != CameraPipeline.Mode.OFF) pipeline.setMode(CameraPipeline.Mode.OFF)
            return
        }
        var want = if (settings.ndiKind == 2) CameraPipeline.Mode.FULL else CameraPipeline.Mode.HX
        // v152 (the stress pass, 26.10.2026): FULL refused by the lens left NOTHING streaming (a settings change stops the
        // stream before asking again). A kind the lens cannot take falls back to the other one, said, rather than to none.
        // The wire watch asks again every 2 s, so it is said once, when the stream actually changes (it was said every 2 s).
        if (want == CameraPipeline.Mode.FULL && !pipeline.fullAvailable && pipeline.hxAvailable) {
            want = CameraPipeline.Mode.HX
            if (pipeline.mode != want) {
                say("Full NDI is not possible on this lens (10-bit takes its output) — NDI HX instead")
                Trace.refused("ndi", "FULL not available on this lens: HX instead")
            }
        }
        if (pipeline.mode == want) return
        val possible = if (want == CameraPipeline.Mode.FULL) pipeline.fullAvailable else pipeline.hxAvailable
        if (!possible) {
            say("${if (want == CameraPipeline.Mode.FULL) "Full NDI" else "NDI HX"} is not possible on this lens")
            return
        }
        // v135: no adapter for the chosen wire, no stream; the watch below starts it when the wire comes up.
        if (pipeline.mode == CameraPipeline.Mode.OFF && pipeline.currentAdapter() == null) {
            say("NDI waiting — " + Routing.wireLabel(settings.ndiTransport, null))
            refreshTelemetry()
            return
        }
        if (pipeline.setMode(want)) {
            pipeline.boundAdapter?.let { say("NDI ${if (want == CameraPipeline.Mode.FULL) "FULL" else "HX"} on " + Routing.wireLabel(settings.ndiTransport, it)) }
        }
        Trace.control("ndi", want.name, "armed in settings")
        refreshTelemetry()
    }

    /**
     * v135: THE TEST HOOK. scripts/field_test.py starts the app with `am start -S ... --es ndi_transport USB` and so
     * on, so every combination is set without tapping through settings (a tap by coordinates breaks with the next
     * layout). Only in a debuggable build, which is what the releases are. Keys: ndi_arm, ndi_kind (1 HX, 2 FULL),
     * ndi_transport (WIFI, USB), ndi_audio, audio_source (auto, phone, or a device key), audio_stereo, file_arm.
     */
    private fun applyTestExtras(i: android.content.Intent?) {
        val x = i?.extras ?: return
        if (!BuildConfig.DEBUG) return
        fun bool(k: String) = x.getString(k)?.let { it == "1" || it.equals("true", true) || it.equals("on", true) }
        bool("ndi_arm")?.let { settings.armNdi = it }
        x.getString("ndi_kind")?.toIntOrNull()?.let { settings.ndiKind = it }
        x.getString("ndi_transport")?.let { v -> runCatching { settings.ndiTransport = Routing.Transport.valueOf(v.uppercase()) } }
        bool("ndi_audio")?.let { settings.ndiAudio = it }
        x.getString("audio_source")?.let { settings.audioSource = it }
        bool("audio_stereo")?.let { settings.audioStereo = it }
        bool("file_arm")?.let { settings.armFile = it }
        Trace.state("TEST settings: ndi=${settings.armNdi} kind=${settings.ndiKind} wire=${settings.ndiTransport} " +
            "ndiSound=${settings.ndiAudio} source=${settings.audioSource} stereo=${settings.audioStereo} file=${settings.armFile}")
    }

    /**
     * v135: THE WIRE WATCH, every two seconds. Marko asked for a ROBUST switch: the tether is plugged in after the
     * app starts, pulled out mid-show, plugged back on a new subnet; Wi-Fi drops and returns. The sender is pinned to
     * one address, so when the address the switch names changes, the sender is made again on the new one; when it
     * goes, the stream stops and waits; when it comes, the stream starts. Lens changes keep the source as before.
     */
    private var wireTicks = 0

    private fun watchWire() {
        if (!pipeline.isRunning || !settings.armNdi) return
        // Every ten seconds while streaming, one line the field test reads: the wire, and the sound that went out.
        if (++wireTicks % 5 == 0 && pipeline.mode != CameraPipeline.Mode.OFF) {
            Trace.state("NDI LIVE ${pipeline.mode} on ${pipeline.boundAdapter?.let { "${it.name} ${it.ip}" }} " +
                "· video queued ${NdiVideo.queued} sent ${NdiVideo.sent} dropped ${NdiVideo.dropped} flushes ${NdiVideo.flushes} slowest ${NdiVideo.takeSlowest()} ms " +
                "· turned ${pipeline.streamTurns * 90}° " +
                "· sound ${if (NdiAudio.wanted) "on" else "off"} sent ${NdiAudio.sent} dropped ${NdiAudio.dropped}" +
                " · from ${meter?.routed ?: "no meter"} ${meter?.channels ?: 0}ch · watching $lastWatching")
        }
        val now = pipeline.currentAdapter()
        val bound = pipeline.boundAdapter
        val streaming = pipeline.mode != CameraPipeline.Mode.OFF
        when {
            streaming && now?.ip != bound?.ip -> {
                Trace.state("wire changed: ${bound?.let { "${it.name} ${it.ip}" } ?: "none"} → ${now?.let { "${it.name} ${it.ip}" } ?: "none"}")
                pipeline.setMode(CameraPipeline.Mode.OFF)
                if (now == null) say("NDI stopped — " + Routing.wireLabel(settings.ndiTransport, null) + ", waiting")
                else followNdiSwitch()
                refreshTelemetry()
            }
            !streaming && now != null -> followNdiSwitch()
        }
    }

    private fun startRecording(): Boolean {
        if (!pipeline.isRunning) { say("The camera is not open"); return false }
        if (meter == null) say("No microphone: this take will have no sound")
        val where = pipeline.startRecording(
            this, meter, settings.recordFolder,
            Mechanism.recordingRotation(
                pipeline.engine.sensorOrientation, displayDegrees(),
                pipeline.engine.isFrontFacing, manualQuarterTurns
            )
        )
            ?: run { refreshKeys(); return false }
        pipeline.lastTakeUri?.let { settings.lastTake = it.toString() }
        // v145: the take's first picture, for the record button once the take is saved
        takeThumb = runCatching { if (preview.isAvailable) preview.getBitmap(THUMB_PX, THUMB_PX * preview.height / maxOf(1, preview.width)) else null }.getOrNull()
        say("Recording → $where")
        return true
    }

    private fun stopRecording() {
        val frames = pipeline.recordedFrames
        val drops = pipeline.recordedDrops
        val where = pipeline.stopRecording()
        takeThumb?.let { t -> if (where != null) stillWriter.execute { keepLastCapture(t) } }
        if (where != null) settings.lastIsStill = false      // v151: PLAY plays the take now
        takeThumb = null
        refreshStorage()
        say(
            when {
                where == null -> "Nothing was written; the file was removed"
                drops > 0 -> "Saved → $where ($frames frames, $drops refused)"
                else -> "Saved → $where ($frames frames)"
            }
        )
        refreshKeys()
    }

    /**
     * The picture: turned the right way up, and never stretched.
     *
     * Both halves of this have been wrong before, and they fail differently.
     * The wrong angle is obvious and everybody reports it. The wrong scale is
     * not — a stretched picture looks fine on a test pattern and only shows on
     * a face — and it has shipped repeatedly because the arithmetic lived here,
     * inside a call that can only be checked by holding a phone up to something
     * rectangular.
     *
     * So the arithmetic is not here any more. `Mechanism.previewRotation` and
     * `Mechanism.previewFit` are pure, and the test suite asserts the one thing
     * that matters: whatever the view, whatever the buffer and whichever way it
     * is turned, what reaches the screen has the buffer's own shape.
     *
     * The angle is the sensor's mounting against the phone's rotation, which is
     * right on nearly every device — and `ROT` adds quarter turns by hand for
     * the ones it is not, because a sideways picture is not something to debug
     * around on a shoot.
     */
    private fun applyPreviewTransform() {
        val vw = preview.width
        val vh = preview.height
        if (vw <= 0 || vh <= 0) return

        val displayDegrees = displayDegrees()
        val sensor = if (pipeline.isRunning) pipeline.engine.sensorOrientation else 90
        val front = pipeline.isRunning && pipeline.engine.isFrontFacing

        // What the camera already did, before we were handed the frame.
        //
        // A TextureView applies its producer's transform matrix before any of
        // this runs, and this phone's camera puts a quarter turn in it. Seven
        // attempts at the angle argued about sensor against display and every
        // one of them was adding a correct rotation on top of one that was
        // already there. It is read rather than assumed, so a phone whose
        // camera turns nothing is left exactly as it was.
        val producer = FloatArray(16)
        runCatching { preview.surfaceTexture?.getTransformMatrix(producer) }
        val producerDegrees = Mechanism.producerRotation(producer)
        val producerMirrored = Mechanism.producerMirrored(producer)
        lastProducerDegrees = producerDegrees
        previewMirrored = producerMirrored

        val auto = Mechanism.previewRotation(sensor, displayDegrees, front, producerDegrees)
        val applied = ((auto + manualQuarterTurns * 90) % 360 + 360) % 360

        // THE SQUASH.
        //
        // A producer transform moves texture coordinates, not the view: the
        // quad is still drawn at the view's own size, so a camera that
        // transposes the frame hands over content whose width and height have
        // swapped while the quad has not. v76 took the turn off the angle and
        // the picture came up the right way round — which is why it looked so
        // nearly right — but the fit was still computed against 1920x1080 when
        // 1080x1920 had arrived, and left the picture in a strip down the
        // middle instead of filling the frame edge to edge.
        val effective = Mechanism.effectiveBuffer(
            bufferSize.width, bufferSize.height, producerDegrees
        )
        val scale = Mechanism.previewFit(vw, vh, effective[0], effective[1], applied)
        val matrix = Matrix()
        // THE SELFIE CAMERA, UPSIDE DOWN.
        //
        // A front camera hands over a *mirrored* frame, and a mirror is not a
        // rotation: the old decoder saw the negative entry in the matrix and
        // answered "half a turn", so half a turn that was never there was
        // taken off the angle and lens four came up upside down while the three
        // rear lenses were right. The mirror is read separately now and put
        // back here, before the rotation — this is a broadcast camera and the
        // monitor has to show what the wire is carrying. The encoder is fed the
        // camera buffer directly and never sees this matrix at all.
        if (producerMirrored) matrix.postScale(-1f, 1f, vw / 2f, vh / 2f)
        matrix.postRotate(applied.toFloat(), vw / 2f, vh / 2f)
        matrix.postScale(scale[0], scale[1], vw / 2f, vh / 2f)
        preview.setTransform(matrix)

        // THE PORTRAIT STRIP.
        //
        // The box was given the buffer's own shape, 16:9 always. In landscape
        // that is right, because the camera's quarter turn and ours cancel. Held
        // upright they do not: the total is a quarter, what arrives is 9:16, and
        // a 9:16 picture fitted inside a 16:9 box is a strip with black on all
        // four sides. The box is told the total now.
        findViewById<AspectFrame>(R.id.picture).aspect =
            Mechanism.shownAspect(bufferSize.width, bufferSize.height, producerDegrees, applied)

        // Measured on what actually reaches the screen, turned and fitted,
        // against the shape the picture is really meant to be.
        val shown = Mechanism.displayedAspect(vw, vh, effective[0], effective[1], applied)
        val wanted = Mechanism.shownAspect(
            bufferSize.width, bufferSize.height, producerDegrees, applied
        )
        val squeeze = if (shown > 0.0 && wanted > 0.0) shown / wanted else 1.0

        val line = "sensor $sensor · disp $displayDegrees · cam $producerDegrees" +
            (if (producerMirrored) " mirrored" else "") +
            (if (front) " front" else "") + " · rot $applied" +
            (if (manualQuarterTurns != 0) " (auto $auto +${manualQuarterTurns * 90})" else "") +
            " · buf ${bufferSize.width}x${bufferSize.height} · view ${vw}x$vh" +
            " · squeeze " + String.format("%.3f", squeeze) +
            (if (kotlin.math.abs(squeeze - 1.0) > 0.01) "  STRETCHED" else "")
        Trace.control("preview geometry", line, applied)
        updateFocusRegion()
    }

    /**
     * The focus box, handed to the camera in the sensor's own coordinates.
     *
     * Recomputed whenever the box moves or is pinched and whenever the picture
     * is turned, because those are the three things that change which part of
     * the sensor is under the box.
     */
    private fun updateFocusRegion() {
        if (!pipeline.isRunning) return
        val engine = pipeline.engine
        val displayDegrees = displayDegrees()
        val front = engine.isFrontFacing
        val degrees = Mechanism.sensorToViewDegrees(
            engine.sensorOrientation, displayDegrees, front, manualQuarterTurns
        )
        val b = focusSquare.normalisedBounds()
        val onStream = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], degrees, previewMirrored)
        val array = engine.activeArray()
        engine.focusRegion = if (array == null) onStream else Mechanism.streamRegionToArray(
            onStream, bufferSize.width, bufferSize.height, array.width(), array.height()
        )
    }

    /**
     * Focus on a point of the picture, whether or not the box is showing.
     *
     * The box goes there too, invisibly, so that when it comes back it is on
     * what was focused on, and its size is the size of the region used.
     */
    private fun focusAt(x: Float, y: Float) {
        if (!pipeline.isRunning) return
        focusSquare.placeAt(x, y)
        Trace.control("focus tap", String.format("%.2f,%.2f", x, y),
            if (fullScreen) "clean feed" else if (zonesOn) "zones up" else "box")
        focus.focusHereAndHold()
    }

    /**
     * Puts the buffer size back, because the TextureView keeps taking it away.
     *
     * This is the stretch, and it is an interaction rather than a formula —
     * which is why six attempts at the arithmetic never fixed it. A TextureView
     * sets its SurfaceTexture's default buffer size to *the view's own pixel
     * size* whenever the view is laid out or resized. Set it once when the
     * camera opens and the next layout silently replaces 1920x1080 with
     * whatever shape the view happens to be, and the camera then scales its
     * output into that, which is a stretched picture arriving from below with
     * nothing in any log to say so.
     *
     * So it is re-asserted on every size change, and the numbers go on screen.
     */
    private fun holdBufferSize() {
        preview.surfaceTexture?.setDefaultBufferSize(bufferSize.width, bufferSize.height)
        // The box's shape is set by applyPreviewTransform, which is the only
        // place that knows the total turn. Setting it here from the buffer
        // alone is what left the picture in a strip when the phone was upright.
    }

    // --- the keys ------------------------------------------------------------

    /**
     * A new lens, without losing the stream.
     *
     * Changing lens means a new capture session, and a new session used to mean
     * the NDI source closed and the mode went back to off — so every lens
     * change was a dropped source at the far end and a key he had to press
     * again. The mode is remembered across the rebuild and put back the moment
     * the camera is ready, and the NDI source itself is kept open, so a
     * receiver sees a short freeze rather than a source that vanished.
     */
    private fun chooseLens(index: Int) {
        if (index >= lenses.size) return
        if (index == activeLens && pipeline.isRunning) return
        activeLens = index
        settings.lens = index + 1           // v148: the camera opens on it next time
        restoreMode = pipeline.mode
        Trace.control("lens", index + 1, lenses[index].label + ", keeping " + restoreMode)
        pipeline.stop(keepSource = restoreMode != CameraPipeline.Mode.OFF)
        openCamera()
    }

    /** The mode to put back once the new lens is live. */
    private var restoreMode: CameraPipeline.Mode = CameraPipeline.Mode.OFF

    /**
     * The focus key: AF → TRK → MF → AF (v99). TRK is automatic focus whose
     * box follows a subject; it needs the GPU stage, and without it the key
     * steps straight from AF to MF and says why.
     */
    private fun toggleAutoFocus() {
        when {
            focus.mode == FocusDirector.Mode.AUTO && !tracking -> {
                if (pipeline.usesGpuStage) {
                    startTrack()
                    refreshKeys()
                    return
                }
                say("Tracking needs the GPU stage — Settings, Picture path")
                focus.setMode(FocusDirector.Mode.MANUAL)
            }
            tracking -> {
                stopTrack()
                focus.setMode(FocusDirector.Mode.MANUAL)
            }
            else -> focus.setMode(FocusDirector.Mode.AUTO)
        }
        Trace.control("focus mode", focus.mode.name, focus.mode.name)
        refreshKeys()
    }

    // --- tracking focus (v99) ---------------------------------------------------------

    /** The stream's degrees from the screen, the same sum [updateFocusRegion] uses. */
    private fun viewDegrees(): Int {
        val engine = pipeline.engine
        return Mechanism.sensorToViewDegrees(
            engine.sensorOrientation, displayDegrees(), engine.isFrontFacing, manualQuarterTurns
        )
    }

    /** The box, as it is now, becomes the pattern; the GPU stage starts looking for it. */
    private fun startTrack() {
        if (!pipeline.isRunning) return
        if (focus.mode != FocusDirector.Mode.AUTO) focus.setMode(FocusDirector.Mode.AUTO)
        val b = focusSquare.normalisedBounds()
        val s = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], viewDegrees(), previewMirrored)
        val cx = (s[0] + s[2]) / 2f
        val cy = (s[1] + s[3]) / 2f
        val search = settings.trackSearch / 10f
        if (!pipeline.startTracking(
                cx, cy, kotlin.math.abs(s[2] - s[0]), kotlin.math.abs(s[3] - s[1]),
                settings.trackEvery, search, settings.trackConfidence / 100f
            )
        ) {
            say("Tracking needs the GPU stage — Settings, Picture path")
            return
        }
        tracking = true
        focusSquare.searchFactor = search
        focusSquare.lost = false
        focusSquare.tracking = true
        lastFocusX = focusSquare.centreX
        lastFocusY = focusSquare.centreY
        focus.focusHereAndHold()
        say("Tracking")
    }

    private fun stopTrack() {
        if (!tracking) return
        tracking = false
        pipeline.stopTracking()
        focusSquare.tracking = false
        focusSquare.lost = false
    }

    /**
     * Where the GPU found the pattern. Below the confidence the mark says
     * LOST and stays; above it the mark follows by the set fraction, focus is
     * told where the subject is, and asked again only past the tolerance.
     */
    private fun onTracked(x: Float, y: Float, score: Float) {
        if (!tracking) return
        val lost = score < settings.trackConfidence / 100f
        focusSquare.lost = lost
        if (lost) return
        val v = Mechanism.streamPointToView(x, y, viewDegrees(), previewMirrored)
        val speed = settings.trackFollow / 100f
        val nx = Mechanism.follow(focusSquare.centreX, v[0], speed)
        val ny = Mechanism.follow(focusSquare.centreY, v[1], speed)
        focusSquare.moveTo(nx, ny)
        focus.target = nx to ny
        focus.bounds = focusSquare.normalisedBounds()
        updateFocusRegion()
        if (Mechanism.trackRefocus(lastFocusX, lastFocusY, nx, ny, settings.trackTolerance / 100f)) {
            lastFocusX = nx
            lastFocusY = ny
            focus.focusHereAndHold(settings.trackSpeedMmS)     // v147: the tracking speed
        }
    }

    private fun togglePeaking() {
        peaking = !peaking
        applyLook()
        refreshKeys()
    }

    /** The stills taken but not yet on the drive; each 4K picture holds about 33 MB until it is written. */
    private val stillWriter = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "still-png") }
    private val stillsWaiting = java.util.concurrent.atomic.AtomicInteger()
    private val MAX_STILLS_WAITING = 4
    private val keptStill = java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap?>()

    /**
     * SNAP: the picture as a PNG, beside the takes.
     *
     * Taken from the preview at the camera's own resolution rather than the
     * screen's, the right way up, without the rails, the zones, the peaking
     * or the monitor LUT (those are on the View, not in the texture). The
     * compression is off the main thread: a 4K PNG takes most of a second.
     */
    private fun takeSnap() {
        if (!pipeline.isRunning || !preview.isAvailable) { say("The camera is not open"); return }
        val vw = preview.width
        val vh = preview.height
        if (vw <= 0 || vh <= 0) return
        val long = maxOf(bufferSize.width, bufferSize.height)
        val (w, h) = if (vw >= vh) long to (long.toLong() * vh / vw).toInt()
                     else (long.toLong() * vw / vh).toInt() to long
        // Marko, 16.10.2026: "snaps in the RAM quickly ... and then slowly saves on SSD so I get the image
        // momentarily." The tap answers on the frame it lands on: the picture blinks and the key lights. The
        // picture is read from the preview straight after that frame is drawn (reading first would hold the blink
        // back behind the read), into a kept buffer rather than a new 33 MB one, and the PNG is written behind it,
        // one at a time, so the next snap can be taken straight away.
        if (stillsWaiting.get() >= MAX_STILLS_WAITING) { say("Still writing the last stills — a moment"); return }
        val at = java.util.Date()
        stillsWaiting.incrementAndGet()
        snapKey.state = RailButton.State.ARMED
        preview.animate().cancel()
        preview.alpha = 0.2f
        preview.animate().alpha(1f).setDuration(180).start()
        ui.postDelayed({ refreshKeys() }, 150)
        android.view.Choreographer.getInstance().postFrameCallback { ui.post { grabStill(w, h, at) } }
    }

    private fun grabStill(w: Int, h: Int, at: java.util.Date) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val kept = keptStill.getAndSet(null)?.takeIf { !it.isRecycled && it.width == w && it.height == h }
        val bitmap = runCatching { if (kept != null) preview.getBitmap(kept) else preview.getBitmap(w, h) }.getOrNull()
        if (bitmap == null) {
            stillsWaiting.decrementAndGet()
            say("The picture could not be read")
            return
        }
        Trace.control("still", "${w}x$h", "PNG", "read in ${android.os.SystemClock.uptimeMillis() - t0} ms")
        stillWriter.execute {
            val where = Recordings.savePng(this, bitmap, at = at, folder = settings.recordFolder)
            if (where != null) {
                keepLastCapture(bitmap)
                // v151: PLAY opens this still now
                Recordings.lastStillUri?.let { settings.lastStill = it.toString() }
                settings.lastIsStill = true
            }
            // One buffer is kept for the next snap; the others go back, so idle memory stays at one picture.
            if (!keptStill.compareAndSet(null, bitmap)) bitmap.recycle()
            stillsWaiting.decrementAndGet()
            ui.post { say(if (where != null) "Still → $where" else "The still was not written, see the trace") }
        }
    }

    /** Curves a lens froze on, "lens/CURVE": stepped over from then on, and since v115 kept across starts. */
    private val refusedCurves by lazy { settings.refusedCurves.toMutableSet() }

    /**
     * v115: THE LAST RISKY STEP, so a fatal camera error that follows it can be blamed on it. Marko, 2.10.2026: "say
     * this feature is not available and go back to the old settings." MEASURED on the Nothing Phone 2a: a log curve
     * made its camera stop (no frame), then the driver closed the camera ("Camera error 4"), and the app went on
     * sending to a closed camera — the freeze he saw.
     */
    private var risk: Triple<String, Long, () -> Unit>? = null
    private val reopenTimes = ArrayDeque<Long>()

    private fun onCameraDied(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        val r = risk
        risk = null
        if (r != null && now - r.second < 10_000) {
            r.third()                                    // forget the step, put the old value back
            say("${r.first} is not available on this phone — back as it was")
            Trace.refused("camera died", "$message after ${r.first}: taken back, the camera opened again")
        } else {
            say("The camera stopped ($message) — opened again")
        }
        // never a loop: at most three reopenings a minute
        while (reopenTimes.isNotEmpty() && now - reopenTimes.first() > 60_000) reopenTimes.removeFirst()
        if (reopenTimes.size >= 3) { say("The camera keeps stopping — not opened again; reopen the app"); return }
        reopenTimes.addLast(now)
        restoreMode = pipeline.mode
        pipeline.stop(keepSource = restoreMode != CameraPipeline.Mode.OFF)
        reopenTries = 0
        ui.postDelayed({ if (preview.isAvailable) openCamera() }, 600)
    }

    /**
     * v116: after a driver crash the phone's camera service restarts, and an open in the first seconds answers "Could
     * not read lens 0" (MEASURED on the Nothing Phone 2a, v115). So the reopening tries again, later each time.
     */
    private var reopenTries = -1

    private fun retryOpen(message: String): Boolean {
        if (reopenTries < 0 || reopenTries >= 4) return false
        if (!message.startsWith("Could not read") && !message.startsWith("Could not open")) return false
        reopenTries++
        val wait = 1500L * reopenTries
        say("The camera is restarting — trying again in ${wait / 1000.0} s")
        ui.postDelayed({ if (preview.isAvailable && !pipeline.isRunning) openCamera() }, wait)
        return true
    }

    private var previousCurve = LogCurves.Curve.REC709

    private fun nextCurve() {
        previousCurve = LogCurves.Curve.entries[curveIndex]
        val count = LogCurves.Curve.entries.size
        for (step in 1..count) {
            curveIndex = (curveIndex + 1) % count
            val name = LogCurves.Curve.entries[curveIndex].name
            if (lensKey() + "/" + name !in refusedCurves) break
        }
        val curve = LogCurves.Curve.entries[curveIndex]
        val before = previousCurve
        risk = Triple(curveLabel(curve), android.os.SystemClock.uptimeMillis()) {
            refusedCurves.add(lensKey() + "/" + curve.name)
            settings.refusedCurves = refusedCurves
            curveIndex = before.ordinal
            pipeline.engine.forgetCurve(before)
            refreshKeys()
        }
        previousCurve = curve
        val ok = pipeline.setLogCurve(curve)
        say(if (ok) (if (curve == LogCurves.Curve.REC709) curveLabel(curve) + ", the camera's own curve"
                     else "${curve.vendor} ${curve.displayName}") else "${curveLabel(curve)} refused")
        refreshKeys()
    }

    /** The four switches: ISO, shutter, focus, white balance; true = A. */
    private fun switches(): BooleanArray = booleanArrayOf(
        isoAuto, shutterAuto, focus.mode == FocusDirector.Mode.AUTO
    )

    /**
     * M: AUTO → HM → FM → AUTO.
     *
     * *"Now we have modes: full manual, half manual."* HM puts back the last
     * half-manual set he made with the switches (or manual exposure with
     * automatic focus, the first time). Into AUTO, white balance is measured
     * once, the way pressing its A does.
     */
    private fun nextCameraMode() {
        val now = switches()
        if (Mechanism.cameraMode(now) == "HM") lastHalf = now.copyOf()
        val next = Mechanism.nextCameraMode(now, lastHalf)
        for (i in next.indices) if (next[i] != now[i]) setParamAuto(i, next[i], quiet = true)
        if (Mechanism.cameraMode(next) == "AUTO" && !wbAuto) probeWhiteBalance()
        say("Mode " + Mechanism.cameraMode(switches()))
        refreshZones()
        refreshKeys()
    }

    /** The A / M switch at the head of a fader. */
    private fun toggleParam(index: Int) {
        // WB's switch is a button (v90): A measures once and comes back to M.
        if (index == 3) { if (!wbAuto) probeWhiteBalance(); return }
        val now = switches()
        setParamAuto(index, !now[index], quiet = false)
        if (Mechanism.cameraMode(switches()) == "HM") lastHalf = switches()
        refreshZones()
        refreshKeys()
    }

    /**
     * One parameter to automatic or manual.
     *
     * Leaving auto starts from the value auto had settled on, so the picture
     * does not jump; white balance from A to M reads the camera's own answer
     * once and keeps it (the probe), which is what the old double tap did.
     */
    private fun setParamAuto(index: Int, auto: Boolean, quiet: Boolean) {
        val engine = pipeline.engine
        when (index) {
            0, 1 -> {
                if (!auto) {
                    iso = engine.lastIso ?: iso
                    shutterNs = engine.lastExposureNs ?: shutterNs
                    seedZonePositions()
                }
                if (index == 0) isoAuto = auto else shutterAuto = auto
                applyExposure()
                if (!quiet) say((if (index == 0) "ISO " else "Shutter ") + if (auto) "automatic" else "manual")
            }
            2 -> when {
                !engine.supportsManualFocus() -> if (!quiet) sayFixedFocus()
                (focus.mode == FocusDirector.Mode.AUTO) != auto || (!auto && tracking) -> {
                    // Straight to A or M: the rail key's cycle passes through TRK.
                    if (!auto) stopTrack()
                    focus.setMode(if (auto) FocusDirector.Mode.AUTO else FocusDirector.Mode.MANUAL)
                    Trace.control("focus mode", focus.mode.name, focus.mode.name)
                    if (!quiet) say("Focus " + if (auto) "automatic" else "manual")
                }
            }
            3 -> if (auto && !wbAuto) probeWhiteBalance()
        }
    }

    /**
     * Exposure from the two switches.
     *
     * Both A is the camera's auto exposure; both M is the operator's. One of
     * each is half manual: the phone's own ISO or shutter priority where the
     * lens offers it (Android 16), otherwise this app's loop, which moves the
     * automatic half to hold the brightness auto exposure had reached.
     */
    private fun applyExposure() {
        val engine = pipeline.engine
        releaseCircle()
        ui.removeCallbacks(priorityLoop)
        loopNative = false
        when (Mechanism.exposureMode(isoAuto, shutterAuto)) {
            Mechanism.Exposure.AUTO -> engine.setAutoExposure()
            Mechanism.Exposure.MANUAL -> engine.setManualExposure(iso, shutterNs)
            Mechanism.Exposure.ISO_PRIORITY, Mechanism.Exposure.SHUTTER_PRIORITY -> {
                val mode = if (!isoAuto) 1 else 2
                if (engine.hasNativePriority(mode) && engine.setPriorityExposure(mode, iso, shutterNs)) {
                    loopNative = true
                } else {
                    engine.setManualExposure(iso, shutterNs)
                    loopSentIso = iso
                    loopSentShutterNs = shutterNs
                    loopTarget = sampleBrightness() ?: 0.0
                    Trace.control(
                        "exposure", if (mode == 1) "ISO priority" else "shutter priority",
                        "the app's loop, holding brightness " + String.format("%.3f", loopTarget)
                    )
                    ui.postDelayed(priorityLoop, 300)
                }
            }
        }
    }

    /**
     * The half-manual loop, four times a second: read the picture's middle,
     * move the automatic half a fraction of a stop towards the target.
     */
    private val priorityLoop = object : Runnable {
        override fun run() {
            val mode = Mechanism.exposureMode(isoAuto, shutterAuto)
            if (loopNative || !pipeline.isRunning ||
                (mode != Mechanism.Exposure.ISO_PRIORITY && mode != Mechanism.Exposure.SHUTTER_PRIORITY)
            ) return
            val measured = sampleBrightness()
            if (measured != null && loopTarget <= 0.0) loopTarget = measured
            if (measured != null && loopTarget > 0.0) {
                val engine = pipeline.engine
                // v129, THE RAMP: the loop chooses where the automatic half should go; the spring walks it
                // there every frame (v128 stepped it four times a second: ISO 247 → 259 → 280 → 322 → 374)
                val current = kotlin.math.exp(springX.takeIf { springLive } ?: kotlin.math.ln(autoHalf(mode)))
                if (mode == Mechanism.Exposure.ISO_PRIORITY) {
                    shutterBounds()?.let { (low, high) ->
                        loopGoal = Mechanism.priorityStep(current, measured, loopTarget, low.toDouble(), high.toDouble())
                    }
                } else {
                    engine.isoRange()?.let {
                        loopGoal = Mechanism.priorityStep(current, measured, loopTarget, it.lower.toDouble(), it.upper.toDouble())
                    }
                }
                if (!springLive) { springX = kotlin.math.ln(autoHalf(mode)); springV = 0.0; springLive = true; ui.post(springTick) }
            }
            ui.postDelayed(this, 250)
        }
    }

    /** v129: where the loop wants the automatic half (ISO or shutter), and the spring that walks it there. */
    private var loopGoal = 0.0
    private var springX = 0.0
    private var springV = 0.0
    private var springLive = false
    /** True while a preset key's ramp carries the automatic half itself (exposure held constant). */
    private var presetCarries = false

    private fun autoHalf(mode: Mechanism.Exposure): Double =
        if (mode == Mechanism.Exposure.ISO_PRIORITY) shutterNs.toDouble() else iso.toDouble()

    /** Every frame: the automatic half follows the loop's goal on a critically damped spring, eased in and out. */
    private val springTick = object : Runnable {
        override fun run() {
            val mode = Mechanism.exposureMode(isoAuto, shutterAuto)
            if (loopNative || !pipeline.isRunning ||
                (mode != Mechanism.Exposure.ISO_PRIORITY && mode != Mechanism.Exposure.SHUTTER_PRIORITY)
            ) { springLive = false; return }
            if (!presetCarries && loopGoal > 0.0) {
                val (x, v) = Mechanism.springStep(springX, springV, kotlin.math.ln(loopGoal), SPRING_FRAME_S, SPRING_OMEGA)
                springX = x; springV = v
                if (mode == Mechanism.Exposure.ISO_PRIORITY) shutterNs = kotlin.math.exp(x).toLong()
                else iso = Math.round(kotlin.math.exp(x)).toInt()
                if (Mechanism.exposureChanged(iso, shutterNs, loopSentIso, loopSentShutterNs)) {
                    pipeline.engine.setManualExposure(iso, shutterNs, trace = false)
                    loopSentIso = iso
                    loopSentShutterNs = shutterNs
                    refreshZones()
                }
            }
            ui.postDelayed(this, (SPRING_FRAME_S * 1000).toLong())
        }
    }

    /**
     * The picture's brightness in the middle, 0..1, from a 24x14 read of the
     * preview: the scaling is done by the GPU, a few dozen pixels are averaged
     * here.
     */
    /**
     * The grey card: the mean signal inside the focus box (the middle fifth of
     * the picture when the box is put away), 0..1 as the curve encoded it.
     * Read off the monitor texture — the picture the camera made, before the
     * monitor LUT and the tools, which live on the View.
     */
    private fun sampleGreyCard(): Double? {
        if (!preview.isAvailable) return null
        val w = 48
        val h = 27
        val bmp = runCatching { preview.getBitmap(w, h) }.getOrNull() ?: return null
        // v103: the exposure circle is where exposure is read, when it is on the picture
        val b = if (exposureCircle.visibility == View.VISIBLE) exposureCircle.normalisedBounds()
            else if (focusSquare.visibility == View.VISIBLE) focusSquare.normalisedBounds()
            else floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f)
        val x0 = (b[0] * w).toInt().coerceIn(0, w - 1)
        val x1 = (b[2] * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (b[1] * h).toInt().coerceIn(0, h - 1)
        val y1 = (b[3] * h).toInt().coerceIn(y0 + 1, h)
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = bmp.getPixel(x, y)
            // Rec.709 luma weights, on the encoded signal, as a waveform reads it.
            sum += (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) +
                0.0722 * android.graphics.Color.blue(c)) / 255.0
            n++
        }
        bmp.recycle()
        return if (n == 0) null else sum / n
    }

    /**
     * v124: THE ENCODER WATCHDOG. While NDI goes out, an encoder that gives no frame for two reports running is
     * restarted with the whole pipeline (at most three times a minute). MEASURED 2.10.2026: after a live bit-rate drop
     * the Pixel's HEVC encoder went silent with no error, and the monitor saw nothing until the camera was restarted.
     */
    private var quietReports = 0

    private fun watchEncoder(fps: Double) {
        if (!pipeline.isRunning || pipeline.mode == CameraPipeline.Mode.OFF) { quietReports = 0; return }
        quietReports = if (fps < 0.5) quietReports + 1 else 0
        if (quietReports < 2) return
        quietReports = 0
        val now = android.os.SystemClock.uptimeMillis()
        while (reopenTimes.isNotEmpty() && now - reopenTimes.first() > 60_000) reopenTimes.removeFirst()
        if (reopenTimes.size >= 3) { say("The encoder keeps stopping — not restarted again"); return }
        reopenTimes.addLast(now)
        Trace.refused("encoder", "no frame out of the encoder while streaming: the pipeline is restarted")
        say("The encoder stopped — restarted")
        restoreMode = pipeline.mode
        pipeline.stop(keepSource = true)
        ui.postDelayed({ if (preview.isAvailable) openCamera() }, 400)
    }

    // --- REMOTE CONTROL (v117) -------------------------------------------------
    //
    // Marko, 2.10.2026: "this camera is just giving all the same interface to remote user, but nothing is recorded.
    // Everything is remote controlling"; "remote control is a priority". Mantra Monitor sends KEYS by name and TAPS on
    // the picture up the NDI stream as metadata; the camera does exactly what its own key or tap does. Twice a second,
    // and at once after a command, it sends back what the monitor needs to draw the same interface: the keys with
    // their labels and colours, the status line, the marks, and how the stream is turned.

    private var remoteSent = 0L
    private var remoteDirty = false

    private val remoteTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 100)
            if (!pipeline.isRunning || pipeline.mode == CameraPipeline.Mode.OFF) return
            var n = 0
            while (n++ < 20) {
                val xml = NdiSender.pollCommand() ?: break
                if (xml.contains(CameraState.ROOT)) continue          // our own state, never a command
                val c = CameraCommand.parse(xml) ?: continue
                Trace.control("remote", xml.take(120), "from the monitor")
                c.key?.let { pressKey(it) }
                if (c.tapX != null && c.tapY != null) streamToView(c.tapX, c.tapY)?.let { (x, y) -> tapAt(x, y) }
                c.streamMbps?.let { m ->
                    val v = m.coerceIn(2, 100)
                    settings.streamMbps = v
                    pipeline.setStreamBitRateLive(v * 1_000_000)
                    say("Streaming $v Mbit/s (set from the monitor)")
                }
                remoteDirty = true
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (remoteDirty || now - remoteSent > 500) {
                remoteDirty = false
                remoteSent = now
                NdiSender.sendState(remoteState().toXml())
            }
        }
    }

    /** The quarter turns from the stream to this screen, and back. */
    private fun streamDegrees(): Int {
        val e = pipeline.engine
        return Mechanism.sensorToViewDegrees(e.sensorOrientation, displayDegrees(), e.isFrontFacing, manualQuarterTurns)
    }

    /**
     * v136: what is still left for a receiver to turn. The stream now carries the picture upright (the pipeline
     * turns it by [streamDegrees]); a monitor that turned it again by the same angle would put it on its side.
     * 0 whenever the stream is turned; the old angle on the direct path, which cannot turn.
     */
    private fun remoteDegrees(): Int = ((streamDegrees() - pipeline.streamTurns * 90) % 360 + 360) % 360

    /** v136: the stream's orientation follows the phone (called on start, on every display turn, on resume). */
    private fun updateStreamTurns() {
        if (!::pipeline.isInitialized || !pipeline.isRunning) return
        val turns = Mechanism.streamTurns(streamDegrees())
        if (turns != pipeline.streamTurns) pipeline.setStreamTurns(turns)
    }

    private fun streamToView(u: Float, v: Float): Pair<Float, Float>? {
        if (!pipeline.isRunning) return null
        val p = Mechanism.viewRegionToSensor(u, v, u, v, (360 - remoteDegrees()) % 360, false)
        val x = if (previewMirrored) 1f - p[0] else p[0]
        return x to p[1]
    }

    /** A key pressed from the monitor: the very key on this screen, clicked. */
    private fun pressKey(name: String) {
        when {
            name == "REC" -> recKey.performClick()
            name.startsWith("MARK") -> name.removePrefix("MARK").toIntOrNull()?.let { markKeys.getOrNull(it)?.performClick() }
            else -> {
                val all = (0 until railLeft.childCount).map { railLeft.getChildAt(it) } +
                    (0 until railRight.childCount).map { railRight.getChildAt(it) }
                // v155: the keys are three letters now; their old names still reach them (mcam key FALSE …)
                val n = mapOf("PORTRAIT" to "POR", "LANDSCAPE" to "LAN", "PEAK" to "PEA", "FALSE" to "FC", "FAL" to "FC", "ZEBRA" to "ZEB")[name] ?: name
                all.filterIsInstance<RailButton>().firstOrNull { keyName(it) == n || (n == "POR" && keyName(it) == "LAN") || (n == "LAN" && keyName(it) == "POR") }?.performClick()
                    ?: Trace.refused("remote", "no key called $name")
            }
        }
    }

    // ---------------------------------------------------------------- ADB CONTROL (v139)

    /**
     * The camera driven from the Mac through adb (Marko, 22.10.2026: "make it also available to fully control through
     * ADB. So you can, for example, if its picture is too dark, you can turn on the light on my camera. If picture is in
     * wrong exposure, you can adjust exposure parameters in the camera. If there is wrong white balance, you can adjust
     * that.")
     *
     *     adb shell am broadcast -a com.mantraproductions.manualcamera.ADB -p com.mantraproductions.manualcamera --es cmd torch --es v on
     *
     * Every command goes through the same paths as the rail and the zones, so the screen shows what was set and says
     * it on the status line. The answer, which `am broadcast` prints as its data, is what was done on the first line and
     * the camera's state after it, one key=value a line. Only the shell may send: the sender must hold DUMP, which adb's
     * shell has and no app is given. The Mac's side is scripts/mcam.
     */
    private var adbRegistered = false
    /** The exposure compensation set over adb, kept by the log-exposure tick while no curve aims at a grey (v140). */
    private var adbEvSteps = 0
    private val adbControl = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd")?.lowercase() ?: "state"
            // v144: the switch in the settings (MORE → ADB CONTROL); shut, nothing is done and nothing is told
            if (!settings.adbControl) {
                Trace.refused("adb", "$cmd: ADB CONTROL is off in the settings")
                resultData = "ADB control is off (Settings → MORE → ADB CONTROL)"
                return
            }
            // v144: a setting's value keeps its case (a source name, a folder)
            val raw = intent.getStringExtra("v")?.trim().orEmpty()
            val v = if (cmd == "set") raw else raw.lowercase()
            val said = runCatching { adbCommand(cmd, v) }.getOrElse { Trace.fault("adb", it); "error: ${it.javaClass.simpleName}" }
            Trace.control("adb", "$cmd $v", said)
            resultData = said + "\n" + adbState()
        }
    }

    private fun startAdbControl() {
        if (adbRegistered) return
        ContextCompat.registerReceiver(
            this, adbControl, android.content.IntentFilter(ADB_ACTION), "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED
        )
        adbRegistered = true
    }

    /** One command; returns what was done, or why not. */
    private fun adbCommand(cmd: String, v: String): String {
        val engine = pipeline.engine
        if (cmd == "state") return "state"
        // v144 (Marko, 26.10.2026: "remotely adjust any parameters of this camera, any settings"): every setting of
        // the settings screen by its name. What the camera decides when it is built (size, rate, depth, path, the
        // outputs, the sound) makes it again, the stream kept, as coming back from the settings screen does.
        if (cmd == "settings") return "settings\n" + settings.all().entries.joinToString("\n") { "${it.key}=${it.value}" }
        if (cmd == "set") {
            val name = v.substringBefore(' ').trim()
            if (name.isEmpty()) return "set takes a name and a value: set fps 25 (mcam settings lists them)"
            val was = snapshot()
            val said = settings.set(name, v.substringAfter(' ', "").trim())
            if (!said.contains('=')) return said
            restoreMode = pipeline.mode
            applyChanged(was)
            focusSquare.size = settings.focusBoxSize
            exposureCircle.size = settings.circleSize
            showMeter()
            focusMeter.keys = Mechanism.decodeKeys(settings.focusKeys)
            focus.speedMmS = settings.focusSpeedMmS
            focus.trackSpeedMmS = settings.trackSpeedMmS
            focus.holdMs = settings.focusHoldMs
            focus.rampMs = settings.focusRackMs
            if (pipeline.isRunning && pipeline.engine.hasFlash()) {
                pipeline.engine.setTorchLevel(settings.torchLevel)
                pipeline.engine.setTorch(settings.torch)
            }
            telemetry.visibility = if (!fullScreen && settings.showOutputs) View.VISIBLE else View.GONE
            say("ADB: $said")
            refreshZones()
            refreshKeys()
            return said
        }
        if (cmd == "restart") {
            restoreMode = pipeline.mode
            pipeline.stop(keepSource = restoreMode != CameraPipeline.Mode.OFF)
            if (preview.isAvailable) openCamera()
            return "Camera made again"
        }
        // v144: what the UPDATE key in the settings does — the newest release, downloaded by Android
        if (cmd == "update") {
            Updater.check(this) { said -> say(said) }
            return "Asking GitHub for the newest release"
        }
        // v141 (Marko: "Lens two is the best"): a lens by its number in the drawer, L1, L2 …, the stream kept
        if (cmd == "lens") {
            val n = v.removePrefix("l").toIntOrNull() ?: return "lens takes a number, 1 to ${lenses.size}"
            if (n !in 1..lenses.size) return "there are ${lenses.size} lenses"
            chooseLens(n - 1)
            say("ADB: Lens $n")
            return "Lens $n " + lenses[n - 1].label
        }
        if (cmd != "key" && !pipeline.isRunning) return "the camera is not running"
        val said: String = when (cmd) {
            "torch", "light" -> {
                if (!engine.hasFlash()) return "this lens has no lamp"
                // v145: a brightness in percent turns it on at that level (torch 30%)
                v.removeSuffix("%").toIntOrNull()?.takeIf { v.endsWith("%") }?.let { pct ->
                    settings.torchLevel = pct
                    settings.torch = true
                    engine.setTorchLevel(settings.torchLevel)
                    engine.setTorch(true)
                    return "Light ${settings.torchLevel}% (level of ${engine.torchStrengthMax()})"
                }
                val on = when (v) { "on", "1" -> true; "off", "0" -> false; else -> !settings.torch }
                settings.torch = on
                engine.setTorch(on)
                "Light " + if (on) "on" else "off"
            }
            "exposure" -> {
                if (v != "auto") return "exposure takes auto (iso and shutter set it by hand)"
                setParamAuto(0, true, quiet = true); setParamAuto(1, true, quiet = true)
                "Exposure automatic"
            }
            "iso" -> if (v == "auto") { setParamAuto(0, true, quiet = true); "ISO automatic" } else {
                val n = v.toIntOrNull() ?: return "iso takes a number or auto"
                if (isoAuto) setParamAuto(0, false, quiet = true)
                val r = engine.isoRange()
                iso = if (r != null) n.coerceIn(r.lower, r.upper) else n
                applyExposure()
                "ISO $iso"
            }
            "shutter" -> if (v == "auto") { setParamAuto(1, true, quiet = true); "Shutter automatic" } else {
                val ns = adbShutter(v) ?: return "shutter takes 1/50, 0.02 (seconds) or auto"
                if (shutterAuto) setParamAuto(1, false, quiet = true)
                val r = engine.exposureRange()
                shutterNs = if (r != null) ns.coerceIn(r.lower, r.upper) else ns
                applyExposure()
                "Shutter " + Mechanism.formatShutter(shutterNs)
            }
            "ev" -> {
                if (!(isoAuto && shutterAuto)) return "ev moves automatic exposure: exposure auto first"
                val r = engine.aeCompensationRange() ?: return "this lens has no exposure compensation"
                val stops = v.removePrefix("+").toDoubleOrNull() ?: return "ev takes stops, like +1 or -0.7"
                val step = engine.aeCompensationStep()
                val steps = kotlin.math.round(stops / step).toInt().coerceIn(r.lower, r.upper)
                adbEvSteps = steps
                engine.setExposureCompensation(steps)
                String.format(java.util.Locale.ROOT, "EV %+.1f", steps * step)
            }
            "wb" -> if (v == "auto") {
                // the A on WB: the camera measures once and the number is kept (v90)
                if (!wbAuto) probeWhiteBalance()
                "White balance measuring"
            } else {
                val k = v.removeSuffix("k").toIntOrNull() ?: return "wb takes kelvin (2000 to 10000) or auto"
                if (wbAuto) { probeTicket++; wbAuto = false }
                wbKelvin = k.coerceIn(WhiteBalance.COOLEST_KELVIN, WhiteBalance.WARMEST_KELVIN)
                engine.setWhiteBalanceKelvin(wbKelvin)
                "White balance " + WhiteBalance.format(wbKelvin)
            }
            "focus" -> if (v == "auto") { rackTicket++; setParamAuto(2, true, quiet = true); "Focus automatic" } else {
                if (!engine.supportsManualFocus()) return "this lens has fixed focus"
                val f = v.toFloatOrNull() ?: return "focus takes 0 (infinity) to 1 (closest) or auto"
                focusFraction = f.coerceIn(0f, 1f)
                rackTicket++                                  // v153: a hand on the focus ends a rack (the review)
                stopTrack()
                if (focus.mode == FocusDirector.Mode.AUTO) focus.setMode(FocusDirector.Mode.MANUAL)
                engine.setManualFocus(focusFraction)
                String.format(java.util.Locale.ROOT, "Focus %.2f", focusFraction)
            }
            // v145: the lens at an exact distance, in dioptres (4.0), centimetres (25cm), millimetres (250mm) or metres (0.25m)
            "focusd" -> {
                if (!engine.supportsManualFocus()) return "this lens has fixed focus"
                val dpt = adbDioptres(v) ?: return "focusd takes dioptres (4.0), 250mm, 25cm or 0.25m"
                rackTicket++
                stopTrack()
                if (focus.mode == FocusDirector.Mode.AUTO) focus.setMode(FocusDirector.Mode.MANUAL)
                val closest = engine.minimumFocusDistance()
                focusFraction = (dpt / closest).coerceIn(0f, 1f)
                engine.setFocusDistance(dpt.coerceIn(0f, closest))
                String.format(java.util.Locale.ROOT, "Focus %.4f dpt", dpt.coerceIn(0f, closest))
            }
            // v145: a rack to a distance, eased in and out over a time (rack 40cm 3000), as a focus puller does it
            "rack" -> {
                if (!engine.supportsManualFocus()) return "this lens has fixed focus"
                val parts = v.split(' ').filter { it.isNotBlank() }
                val to = parts.getOrNull(0)?.let { adbDioptres(it) } ?: return "rack takes a distance and ms: rack 40cm 3000"
                rackTo(to, parts.getOrNull(1)?.toLongOrNull()) ?: return "the camera is not running"
            }
            // v149: the focus puller's meter: meter on|off, meter add 40cm, meter clear, meter next
            "meter" -> {
                val parts = v.split(' ').filter { it.isNotBlank() }
                when (parts.getOrNull(0)) {
                    "on", "off" -> { settings.focusPuller = parts[0] == "on"; showMeter(); "Focus puller ${parts[0]}" }
                    "add" -> {
                        val d = parts.getOrNull(1)?.let { adbDioptres(it) } ?: return "meter add takes a distance: meter add 40cm"
                        val keys = Mechanism.decodeKeys(settings.focusKeys) + d.coerceIn(0f, engine.minimumFocusDistance())
                        settings.focusKeys = Mechanism.encodeKeys(keys); focusMeter.keys = keys
                        "Key point " + Mechanism.formatFocusDistance(d) + " (${keys.size})"
                    }
                    "clear" -> { settings.focusKeys = ""; focusMeter.keys = emptyList(); "Key points cleared" }
                    "keys" -> return "keys " + Mechanism.decodeKeys(settings.focusKeys).joinToString(" ") { Mechanism.formatFocusDistance(it) }
                    else -> return "meter takes on, off, add 40cm, clear, keys"
                }
            }
            // any key of the rails, by the name the link uses (REC, MARK0, a key's label)
            "key" -> { pressKey(v.uppercase()); return "key ${v.uppercase()}" }
            // v143: the circle's mode, a tap on it, the shutter a stop either way
            "circle" -> when (v) {
                "iso" -> { setCircleMode(CIRCLE_ISO); return "Circle ISO" }
                "wb" -> { setCircleMode(CIRCLE_WB); return "Circle WB" }
                "", "tap" -> { fireCircle(); return "Circle " + circleLabel() + " tapped" }
                "+", "-" -> { stepShutter(if (v == "+") 1 else -1); return lastSaid }
                else -> return "circle takes iso, wb, tap, + or -"
            }
            else -> return "unknown command $cmd: state, settings, set, restart, update, lens, torch, exposure, iso, shutter, ev, wb, focus, focusd, rack, meter, circle, key"
        }
        say("ADB: $said")
        refreshZones()
        refreshKeys()
        return said
    }

    /** v145: a rack running from adb; a new one, or any other focus command, takes over. */
    private var rackTicket = 0

    /**
     * v145/v149: the lens racked to [dpt], eased in and out (smootherstep), over [ms] or, without it, at FOCUS SPEED
     * (or the ramp, at CAMERA). The adb `rack` and the focus puller's meter both come here. Null when there is no lens
     * to move; otherwise what was done.
     */
    private fun rackTo(dpt: Float, ms: Long? = null): String? {
        if (!pipeline.isRunning) return null
        val engine = pipeline.engine
        if (!engine.supportsManualFocus()) return "this lens has fixed focus"
        stopTrack()
        focus.cancelWalk()
        if (focus.mode == FocusDirector.Mode.AUTO) focus.setMode(FocusDirector.Mode.MANUAL)
        val closest = engine.minimumFocusDistance()
        val from = engine.lastFocusDistance ?: 0f
        val target = dpt.coerceIn(0f, closest)
        val time = ms ?: Mechanism.focusTravelMs(from, target, settings.focusSpeedMmS).takeIf { it > 0 } ?: settings.focusRackMs
        val started = android.os.SystemClock.uptimeMillis()
        val mine = ++rackTicket
        ui.post(object : Runnable {
            override fun run() {
                if (rackTicket != mine || !pipeline.isRunning) return
                val p = ((android.os.SystemClock.uptimeMillis() - started).toFloat() / time.coerceAtLeast(1)).coerceIn(0f, 1f)
                val at = Mechanism.rackPosition(from, target, p)
                focusFraction = (at / closest).coerceIn(0f, 1f)
                pipeline.engine.setFocusDistance(at)
                if (p < 1f) ui.postDelayed(this, 33) else say("Rack arrived: " + Mechanism.formatFocusDistance(target))
            }
        })
        Trace.control("rack", Mechanism.formatFocusDistance(from) + " → " + Mechanism.formatFocusDistance(target), "$time ms")
        return "Rack " + Mechanism.formatFocusDistance(from) + " → " + Mechanism.formatFocusDistance(target) + " in $time ms"
    }

    /** v149: the lens straight to [dpt], by the hand on the meter's white triangle (no ease: the hand is the ease). */
    private fun pullTo(dpt: Float) {
        if (!pipeline.isRunning || !pipeline.engine.supportsManualFocus()) return
        rackTicket++
        focus.cancelWalk()                              // v153: a tap's walk must not fight the hand
        stopTrack()
        if (focus.mode == FocusDirector.Mode.AUTO) focus.setMode(FocusDirector.Mode.MANUAL)
        val closest = pipeline.engine.minimumFocusDistance()
        val at = dpt.coerceIn(0f, closest)
        focusFraction = (at / closest).coerceIn(0f, 1f)
        pipeline.engine.setFocusDistance(at)
    }

    /** v149: the meter shown when the settings say so and the lens focuses; its lens triangle follows the capture result. */
    /**
     * v155 (Marko, 26.10.2026: "make it the same as free space, the same design, maybe in the blue color"): the
     * battery above (level and ▲ charging, ▼ discharging, = balanced), the time under it (to empty, or to full);
     * red under 20 % or 20 minutes, as the drive's readout goes red under ten.
     */
    // v155 (Marko, 26.10.2026: "the numbers of discharging and charging are changing too rapidly. You need to average
    // the value ... make it less fast"): the time shown follows the readings over about a minute, the readout is
    // written every POWER_SHOW_MS, at once only when the flow turns (plugged in, unplugged, full)
    private var shownSeconds: Double? = null
    private var shownFlow: PowerMonitor.Flow? = null
    private var shownAt = 0L

    private fun refreshPower(raw: PowerMonitor.Reading) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (raw.flow != shownFlow) { shownSeconds = null; shownAt = 0L }
        raw.seconds?.let { s -> shownSeconds = shownSeconds?.let { it + (s - it) * 0.06 } ?: s.toDouble() }
        if (raw.flow == shownFlow && now - shownAt < POWER_SHOW_MS) return
        shownFlow = raw.flow; shownAt = now
        val r = raw.copy(seconds = shownSeconds?.toLong() ?: raw.seconds)
        lastPower = r
        val arrow = when (r.flow) {
            PowerMonitor.Flow.CHARGING -> "▲"; PowerMonitor.Flow.DISCHARGING -> "▼"
            PowerMonitor.Flow.BALANCED -> "="; PowerMonitor.Flow.FULL -> ""
        }
        powerKey.label = "${r.level}%$arrow"
        powerKey.sub = when {
            r.flow == PowerMonitor.Flow.FULL -> "FULL"
            r.seconds == null -> if (r.flow == PowerMonitor.Flow.BALANCED) "BAL" else "--:--"
            else -> PowerMonitor.Calc.format(r.seconds) + " h"
        }
        // v155 (Marko, 26.10.2026): ▲ charging green, ▼ discharging yellow, = balanced white; red only when it is
        // critical (discharging under 20 % or under 20 minutes)
        val low = r.flow == PowerMonitor.Flow.DISCHARGING && (r.level < 20 || (r.seconds ?: Long.MAX_VALUE) < 20 * 60)
        powerKey.state = if (low) RailButton.State.ARMED else RailButton.State.SHOWN
        powerKey.tint = when {
            low -> null
            r.flow == PowerMonitor.Flow.CHARGING || r.flow == PowerMonitor.Flow.FULL -> POWER_GREEN
            r.flow == PowerMonitor.Flow.DISCHARGING -> STORAGE_YELLOW
            else -> null                                               // SHOWN without a tint: white
        }
        powerKey.contentDescription = PowerMonitor.Calc.describe(r)
    }

    /**
     * v155 (Marko, 26.10.2026: "On the long press on the False colors, please make legend what each color means in
     * exposure values"): the bands of PreviewEffects' false colour, as the shader draws them (the picture's level as
     * recorded, before the LUT), with their place in stops from middle grey.
     */
    private fun showFalseColourLegend() {
        val rows = listOf(
            Triple(0xFF8000CC.toInt(), "PURPLE   under 2.5 %", "crushed black, no detail (≈ −6 EV and below)"),
            Triple(0xFF1A4DFF.toInt(), "BLUE     2.5 – 10 %", "near black, shadow detail (≈ −4 EV)"),
            Triple(0xFF26D940.toInt(), "GREEN    38 – 42 %", "middle grey, 18 % grey card (0 EV)"),
            Triple(0xFFFF73B3.toInt(), "PINK     52 – 56 %", "one stop over: light skin (+1 EV)"),
            Triple(0xFFFFE61A.toInt(), "YELLOW   97 – 99 %", "nearly clipped, last highlight detail (≈ +4 EV)"),
            Triple(0xFFFF1A1A.toInt(), "RED      over 99 %", "clipped: white, no detail"),
            Triple(0xFF8A8A8A.toInt(), "GREY     elsewhere", "the picture's own brightness, so the bands stand out"),
        )
        val sb = android.text.SpannableStringBuilder()
        for ((colour, band, meaning) in rows) {
            val at = sb.length
            sb.append("■ ")
            sb.setSpan(android.text.style.ForegroundColorSpan(colour), at, at + 1, 0)
            sb.setSpan(android.text.style.RelativeSizeSpan(1.4f), at, at + 1, 0)
            val b = sb.length
            sb.append(band)
            sb.setSpan(android.text.style.TypefaceSpan("monospace"), b, sb.length, 0)
            sb.append("\n    $meaning\n")
        }
        sb.append("\nLevels are the picture as recorded, before a LUT. The stops are for HLG and REC.709; on a log curve middle grey sits lower, so set exposure by the green band. Monitor only: the recording is untouched.")
        android.app.AlertDialog.Builder(this).setTitle("FC · FALSE COLOUR").setMessage(sb).setPositiveButton("OK", null).show()
    }

    /** v155: the POWER MONITOR's numbers, in words, as a V-mount's menu shows them. */
    private fun showPower() {
        val r = lastPower ?: return
        val time = when (r.flow) {
            PowerMonitor.Flow.CHARGING -> "Full in ${PowerMonitor.Calc.format(r.seconds)} h"
            PowerMonitor.Flow.DISCHARGING -> "Empty in ${PowerMonitor.Calc.format(r.seconds)} h at this draw"
            PowerMonitor.Flow.BALANCED -> "In balance: the charger gives what the camera takes"
            PowerMonitor.Flow.FULL -> "Full"
        }
        val lines = listOf(
            time,
            "${r.level} %" + (r.chargeMah?.let { "  ·  $it mAh in the cell" } ?: ""),
            "${PowerMonitor.Calc.signed(r.netMa)}  ·  " + String.format(java.util.Locale.ROOT, "%.2f V  ·  %.1f W", r.volts, r.watts),
            if (r.plugged.isEmpty()) "On battery" else "Plugged in: ${r.plugged}",
            String.format(java.util.Locale.ROOT, "Battery %.1f °C", r.tempC),
            if (r.measured) "Measured: the cell's own charge counter over the last minutes"
            else "First estimate from the current now; measured after two minutes"
        )
        android.app.AlertDialog.Builder(this).setTitle("POWER MONITOR").setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null).show()
    }

    private fun showMeter() {
        val closest = if (pipeline.isRunning) pipeline.engine.minimumFocusDistance() else 0f
        val on = settings.focusPuller && closest > 0f && !fullScreen
        focusMeter.visibility = if (on) View.VISIBLE else View.GONE
        focusMeter.closest = if (on) closest else 0f
    }

    /** v145: 4.0 (dioptres), 250mm, 25cm, 0.25m or inf as dioptres. */
    private fun adbDioptres(v: String): Float? = when {
        v == "inf" || v == "∞" -> 0f
        v.endsWith("mm") -> v.removeSuffix("mm").toFloatOrNull()?.let { 1000f / it }
        v.endsWith("cm") -> v.removeSuffix("cm").toFloatOrNull()?.let { 100f / it }
        v.endsWith("m") -> v.removeSuffix("m").toFloatOrNull()?.let { 1f / it }
        else -> v.removeSuffix("dpt").toFloatOrNull()
    }

    /** 1/50, 0.02 or 20ms as nanoseconds. */
    private fun adbShutter(v: String): Long? = when {
        v.startsWith("1/") -> v.removePrefix("1/").toDoubleOrNull()?.takeIf { it > 0 }?.let { (1e9 / it).toLong() }
        v.endsWith("ms") -> v.removeSuffix("ms").toDoubleOrNull()?.let { (it * 1e6).toLong() }
        else -> v.removeSuffix("s").toDoubleOrNull()?.let { (it * 1e9).toLong() }
    }

    /** The camera as the Mac needs it to judge the picture: what is set, what the sensor took, how bright it is. */
    private fun adbState(): String {
        val e = pipeline.engine
        val running = pipeline.isRunning
        val m = linkedMapOf<String, Any?>(
            "version" to BuildConfig.VERSION_NAME,
            "running" to running,
            "lens" to (activeLens + 1),
            "lenses" to lenses.mapIndexed { i, l -> "L${i + 1}:" + l.label.replace(' ', '_') + (l.equivalentMm.takeIf { it > 0 }?.let { "_${it}mm" } ?: "") }.joinToString(","),
            "torch" to (if (e.hasFlash()) settings.torch else "none"),
            "torch_level" to (if (e.hasFlash()) "${settings.torchLevel}% of ${e.torchStrengthMax()} levels" else "-"),
            "exposure" to Mechanism.exposureMode(isoAuto, shutterAuto).name.lowercase(),
            "iso" to (if (isoAuto) "auto" else iso),
            "shutter" to (if (shutterAuto) "auto" else Mechanism.formatShutter(shutterNs)),
            "sensor_iso" to e.lastIso,
            "sensor_shutter" to e.lastExposureNs?.let { Mechanism.formatShutter(it) },
            "ev_steps" to e.aeCompensation,
            "iso_range" to e.isoRange()?.let { "${it.lower}-${it.upper}" },
            "wb" to (if (wbAuto) "auto" else wbKelvin),
            "circle" to circleLabel().lowercase(),
            "wb_measured" to (if (running) e.measuredKelvin() else null),
            "focus" to (if (focus.mode == FocusDirector.Mode.AUTO) "auto" else String.format(java.util.Locale.ROOT, "%.2f", focusFraction)),
            // v145: where the lens really is (the capture result), how far it goes, and how far its numbers are true
            "focus_dpt" to e.lastFocusDistance?.let { String.format(java.util.Locale.ROOT, "%.5f", it) },
            "focus_at" to e.lastFocusDistance?.let { Mechanism.formatFocusDistance(it) },
            "focus_closest_dpt" to (if (running) String.format(java.util.Locale.ROOT, "%.4f", e.minimumFocusDistance()) else null),
            "focus_calibration" to (if (running) e.focusCalibration() else null),
            "hyperfocal_dpt" to e.hyperfocalDistance()?.let { String.format(java.util.Locale.ROOT, "%.4f", it) },
            "focus_label" to focusLabel()?.replace('\n', ' '),
            "brightness" to sampleBrightness()?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
            "rec" to rolling,
            // v155: the POWER MONITOR, for the Mac and the remote
            "power" to lastPower?.let { PowerMonitor.Calc.describe(it) },
            "power_level" to lastPower?.level,
            "power_flow" to lastPower?.flow?.name?.lowercase(),
            "power_ma" to lastPower?.netMa,
            "power_w" to lastPower?.let { String.format(java.util.Locale.ROOT, "%.2f", it.watts) },
            "power_time" to lastPower?.let { PowerMonitor.Calc.format(it.seconds) },
            "power_measured" to lastPower?.measured,
            "status" to lastSaid.replace('\n', ' ')
        )
        return m.entries.joinToString("\n") { "${it.key}=${it.value ?: "-"}" }
    }

    private fun keyName(k: RailButton): String = when (k.glyph) {
        RailButton.Glyph.SQUARE -> "MARK0"
        RailButton.Glyph.CIRCLE -> "MARK1"
        RailButton.Glyph.TRIANGLE -> "MARK2"
        RailButton.Glyph.NONE, RailButton.Glyph.ROTATE, RailButton.Glyph.ZEBRA, RailButton.Glyph.PEAKING, RailButton.Glyph.FALSECOLOUR -> k.label
        else -> k.glyph.name.lowercase()
    }

    private fun remoteState(): CameraState {
        fun keyLine(k: RailButton) = listOf(keyName(k), k.label, k.sub ?: "", k.state.name, k.tint?.toString() ?: "").joinToString("~")
        val left = (0 until railLeft.childCount).map { railLeft.getChildAt(it) }
            .filterIsInstance<RailButton>().filter { it.visibility == View.VISIBLE }.map { keyLine(it) }
        val right = (0 until railRight.childCount).map { railRight.getChildAt(it) }
            .filterIsInstance<RailButton>().filter { it.visibility == View.VISIBLE }.map { keyLine(it) }
        val keys = (left + listOf("--") + right + listOf("REC~REC~~${if (rolling) "ARMED" else "OFF"}~")).joinToString("|")
        val d = remoteDegrees()
        fun mark(kind: Int, b: FloatArray, colour: Int, shown: Boolean): String {
            val s = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], d, previewMirrored)
            return String.format(java.util.Locale.ROOT, "%d,%.4f,%.4f,%.4f,%.4f,%d,%d", kind, s[0], s[1], s[2], s[3], colour, if (shown) 1 else 0)
        }
        val marks = listOf(
            mark(0, focusSquare.normalisedBounds(), focusSquare.colour(), settings.marksShown and 1 != 0),
            mark(1, exposureCircle.normalisedBounds(), exposureCircle.colour(), settings.marksShown and 2 != 0)
        ).joinToString("|")
        return CameraState(
            recording = rolling, cameraName = settings.sourceName, status = status.text.toString(),
            keys = keys, marks = marks, armed = settings.pinchTarget, turns = (d / 90) % 4,
            streamMbps = pipeline.actualStreamBitRate / 1_000_000
        )
    }

    // --- the marks (v103; v143 the square and one circle) ---------------------------

    /** Grey → white → orange → grey, for the square (0) and the circle (1). */
    private fun stepMark(i: Int) {
        val bit = 1 shl i
        val shown = settings.marksShown and bit != 0
        when {
            !shown -> settings.marksShown = settings.marksShown or bit
            settings.pinchTarget != i -> settings.pinchTarget = i
            else -> {
                settings.marksShown = settings.marksShown and bit.inv()
                // the orange goes to the next mark still on the picture, or stays (a pinch with none is nothing)
                (0..1).firstOrNull { settings.marksShown and (1 shl it) != 0 }?.let { settings.pinchTarget = it }
                if (i == 1) { releaseCircle(); exposureCircle.state = MarkView.State.IDLE }
            }
        }
        Trace.control("marks", "shown ${settings.marksShown}, pinch ${settings.pinchTarget}", "key ${i}")
        say(lastSaid)
        refreshMarks()
        refreshKeys()
    }

    /** What is drawn and what takes a pinch, from the keys' state, the controls and the clean feed. */
    private fun refreshMarks() {
        val canShow = !zonesOn && !fullScreen
        val shown = settings.marksShown
        // the square always takes the tap that focuses; grey only stops it being drawn
        focusSquare.visibility = if (canShow) View.VISIBLE else View.GONE
        focusSquare.drawn = shown and 1 != 0
        exposureCircle.visibility = if (canShow && shown and 2 != 0) View.VISIBLE else View.GONE
        focusSquare.pinchResizesMe = settings.pinchTarget == 0
        focusSquare.tapMovesMe = settings.pinchTarget == 0
    }

    /**
     * v105: a tap on the picture serves the ARMED shape (the orange key): it moves there, corrects, locks.
     * Square: focus there. Circle: ISO or white balance read there (its mode, v143).
     */
    private fun tapAt(x: Float, y: Float) {
        // v125: a tap on one or more marks sets every one of them where they are (the monitor's taps too)
        // The armed mark still goes where the finger is, unless it is already there.
        val under = marksUnder(x, y)
        if (settings.pinchTarget.coerceIn(0, 1) in under) {
            Trace.control("tap", String.format("%.2f,%.2f", x, y), "on marks " + under.joinToString())
            under.forEach { fireMark(it) }
            return
        }
        if (under.isNotEmpty()) {
            Trace.control("tap", String.format("%.2f,%.2f", x, y), "also on marks " + under.joinToString())
            under.forEach { fireMark(it) }
        }
        when (settings.pinchTarget) {
            1 -> {
                exposureCircle.moveTo(x, y)
                settings.circleX = exposureCircle.centreX; settings.circleY = exposureCircle.centreY
                Trace.control("tap", String.format("%.2f,%.2f", x, y), "circle " + circleLabel())
                fireCircle()
            }
            else -> focusAt(x, y)
        }
    }

    /**
     * v125, OVERLAP: *"If they're overlapping and the user clicks basically on all three at the same time,
     * all three are doing their thing."* Which marks (0 square, 1 circle) are under the point,
     * on them or within a finger's reach. A grey (hidden) mark is not under anything.
     */
    private fun marksUnder(x: Float, y: Float): List<Int> = buildList {
        if (focusSquare.hits(x, y)) add(0)
        if (exposureCircle.hits(x, y)) add(1)
    }

    private fun fireMark(i: Int) = when (i) {
        0 -> focus.focusHereAndHold()
        else -> fireCircle()
    }

    /** The mark that took the touch has fired; the others under the same finger fire with it. */
    private fun alsoUnder(x: Float, y: Float, took: Int) {
        val others = marksUnder(x, y) - took
        if (others.isEmpty()) return
        Trace.control("tap", String.format("%.2f,%.2f", x, y), "overlap: mark $took and " + others.joinToString())
        others.forEach { fireMark(it) }
    }

    /** A pinch anywhere resizes the orange mark, when that is the circle. */
    private val markPinch by lazy {
        android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                when (settings.pinchTarget) {
                    1 -> exposureCircle.size *= d.scaleFactor
                }
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                when (settings.pinchTarget) {
                    1 -> settings.circleSize = exposureCircle.size
                }
                Trace.control("mark size", settings.pinchTarget, "pinched")
            }
        }).apply { isQuickScaleEnabled = false }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (settings.pinchTarget != 0 && !zonesOn && !fullScreen) markPinch.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** The circle, as the camera's own region: the same turn from screen to sensor as the focus box. */
    private fun sensorRegionOf(b: FloatArray): FloatArray? {
        if (!pipeline.isRunning) return null
        val engine = pipeline.engine
        val degrees = Mechanism.sensorToViewDegrees(engine.sensorOrientation, displayDegrees(), engine.isFrontFacing, manualQuarterTurns)
        val onStream = Mechanism.viewRegionToSensor(b[0], b[1], b[2], b[3], degrees, previewMirrored)
        val array = engine.activeArray() ?: return onStream
        return Mechanism.streamRegionToArray(onStream, bufferSize.width, bufferSize.height, array.width(), array.height())
    }

    /** v125: the circle's ramp. */
    private val exposureRamp by lazy { Ramp(ui) }
    private var circleTicket = 0
    /**
     * v126: how many stops the picture moves for a stop of exposure, per curve, learnt round to round. The
     * phone's own tone curve answered 0.58 (Pixel 7, 2.10.2026 10:02), so v125 needed a second ramp after a
     * pause; with this the next tap arrives in one move.
     */
    private val exposureResponse = HashMap<Int, Double>()
    private var circleLastLinear = 0.0
    private var circleLastStops = 0.0

    // --- the circle's mode (v143) ---------------------------------------------------

    /*
     * Marko, 24.10.2026: *"we're going to have a square and one circle because this is the trick. So circle, a long
     * press on the circle, change its mode ... One mode is to tap to adjust exposure with ISO only ... It's a white
     * balance. And the third option, which is not the mode, which is just to set in the shutter. So idea in manual
     * camera is that what is fixed in the video mode is always shutter speed and aperture ... with this round thing,
     * what I expect to change manually with plus and minus buttons — shutter speed ... Under the circle, you need to
     * write in small letters ISO or WB."*
     */

    private fun circleLabel(): String = if (settings.circleMode == CIRCLE_WB) "WB" else "ISO"

    /** A tap on the circle, from the finger, the picture, the monitor or adb: what its mode does. */
    private fun fireCircle() {
        if (settings.circleMode == CIRCLE_WB) spotWhiteBalance() else meterIso()
    }

    private fun setCircleMode(mode: Int) {
        releaseCircle()
        spotTicket++
        settings.circleMode = mode
        exposureCircle.label = circleLabel()
        exposureCircle.state = MarkView.State.IDLE
        Trace.control("circle mode", circleLabel(), "long press")
        say(if (mode == CIRCLE_WB) "Circle: WB — a tap balances white on what is inside"
            else "Circle: ISO — a tap sets ISO on what is inside, the shutter stays")
        refreshKeys()
    }

    /**
     * The long press on the circle: its two modes, ISO and WB, and the shutter with − and +, which is not a mode
     * but the one number the circle never moves.
     */
    private fun openCircleMenu() {
        val dp = resources.displayMetrics.density
        val green = android.graphics.Color.parseColor("#33D17A")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6101010.toInt()); cornerRadius = 10 * dp; setStroke((1 * dp).toInt(), 0x55FFFFFF)
            }
        }
        val popup = android.widget.PopupWindow(box, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { isOutsideTouchable = true; elevation = 8 * dp }
        fun chip(text: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (on) green else android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            minWidth = (52 * dp).toInt()
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { click() }
        }
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setGravity(android.view.Gravity.CENTER) }
        modes.addView(chip("ISO", settings.circleMode == CIRCLE_ISO) { setCircleMode(CIRCLE_ISO); popup.dismiss() })
        modes.addView(chip("WB", settings.circleMode == CIRCLE_WB) { setCircleMode(CIRCLE_WB); popup.dismiss() })
        box.addView(modes)
        val shutter = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            minWidth = (64 * dp).toInt()
        }
        fun showShutter() {
            val ns = if (shutterAuto) pipeline.engine.lastExposureNs ?: shutterNs else shutterNs
            shutter.text = "SHUTTER\n" + Mechanism.formatShutter(ns) + if (shutterAuto) " A" else ""
        }
        showShutter()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setGravity(android.view.Gravity.CENTER) }
        row.addView(chip("−", false) { stepShutter(-1); showShutter() })
        row.addView(shutter)
        row.addView(chip("+", false) { stepShutter(+1); showShutter() })
        box.addView(row)
        // above the circle, or below it when there is no room above; inside the screen either way
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2)
        exposureCircle.getLocationOnScreen(at)
        val b = exposureCircle.normalisedBounds()
        val cx = at[0] + exposureCircle.width * exposureCircle.centreX
        val top = at[1] + exposureCircle.height * b[1]
        val bottom = at[1] + exposureCircle.height * b[3]
        val screen = resources.displayMetrics
        val x = (cx - box.measuredWidth / 2f).toInt().coerceIn(0, (screen.widthPixels - box.measuredWidth).coerceAtLeast(0))
        val above = (top - box.measuredHeight - 8 * dp).toInt()
        val y = (if (above >= 0) above else (bottom + 22 * dp).toInt())
            .coerceIn(0, (screen.heightPixels - box.measuredHeight).coerceAtLeast(0))
        popup.showAtLocation(exposureCircle, android.view.Gravity.NO_GRAVITY, x, y)
    }

    /**
     * The shutter one video stop faster (+1) or slower (−1), by hand from then on: the circle never moves it.
     * On automatic it starts from where the camera had it.
     */
    private fun stepShutter(direction: Int) {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        val (low, high) = shutterBounds() ?: run { say("This lens has no manual shutter"); return }
        val from = if (shutterAuto) pipeline.engine.lastExposureNs ?: shutterNs else shutterNs
        val next = Mechanism.shutterStep(from, direction, low, high)
        if (next == null) {
            say("Shutter " + Mechanism.formatShutter(from) +
                if (direction > 0) ": the fastest there is" else ": the slowest this frame rate holds")
            return
        }
        if (shutterAuto) setParamAuto(1, false, quiet = true)
        shutterNs = next
        shutterPosition = Mechanism.positionOfValue(shutterNs.toDouble(), low.toDouble(), high.toDouble())
        applyExposure()
        Trace.control("shutter", Mechanism.formatShutter(shutterNs), "circle " + if (direction > 0) "+" else "−")
        say("Shutter " + Mechanism.formatShutter(shutterNs) + if (isoAuto) " · ISO automatic" else " · ISO $iso")
        refreshZones()
        refreshKeys()
    }

    /**
     * v143, THE CIRCLE ON ISO: a tap reads the circle and walks ISO alone until it lands on 18% grey; the shutter
     * stays where it is. From the tap on both are the operator's numbers, the ones the faders show: a shutter on
     * automatic is held where the camera had it.
     */
    private fun meterIso() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        val engine = pipeline.engine
        val iso0 = if (isoAuto) engine.lastIso else iso
        val shutter0 = if (shutterAuto) engine.lastExposureNs else shutterNs
        if (engine.isoRange() == null || iso0 == null || shutter0 == null) {
            exposureCircle.state = MarkView.State.FAILED
            say("ISO: this lens does not report its exposure")
            return
        }
        iso = iso0
        shutterNs = shutter0
        isoAuto = false
        shutterAuto = false
        applyExposure()
        exposureCircle.state = MarkView.State.MEASURING
        exposureLockedAt = null
        say("ISO: reading the circle…")
        refreshKeys()
        isoRound(1, ++circleTicket)
    }

    /** The circle's mean scene light, linear 0..1 (luma of the decoded channels), or null with no picture. */
    private fun sampleCircleLinear(): Double? {
        if (!preview.isAvailable) return null
        val w = (preview.width / 4).coerceAtLeast(48)
        val h = (preview.height / 4).coerceAtLeast(48)
        val bmp = runCatching { preview.getBitmap(w, h) }.getOrNull() ?: return null
        val bd = exposureCircle.normalisedBounds()
        val curve = LogCurves.Curve.entries[curveIndex]
        var sum = 0.0
        var n = 0
        for (y in (bd[1] * h).toInt().coerceIn(0, h - 1) until (bd[3] * h).toInt().coerceIn(1, h))
            for (x in (bd[0] * w).toInt().coerceIn(0, w - 1) until (bd[2] * w).toInt().coerceIn(1, w)) {
                if (!exposureCircle.contains((x + 0.5f) / w, (y + 0.5f) / h)) continue
                val c = bmp.getPixel(x, y)
                sum += 0.2126 * LogCurves.decode(curve, android.graphics.Color.red(c) / 255.0) +
                    0.7152 * LogCurves.decode(curve, android.graphics.Color.green(c) / 255.0) +
                    0.0722 * LogCurves.decode(curve, android.graphics.Color.blue(c) / 255.0)
                n++
            }
        bmp.recycle()
        return if (n < 4) null else sum / n
    }

    /**
     * One round of the circle on ISO: read it, and if it is more than a fifth of a stop off 18% grey, walk ISO
     * there over the ramp time, eased in and out; read again. The shutter is not touched.
     */
    private fun isoRound(round: Int, ticket: Int) {
        if (circleTicket != ticket || !pipeline.isRunning) return
        val engine = pipeline.engine
        val linear = sampleCircleLinear()
        val range = engine.isoRange()
        if (linear == null || range == null) {
            exposureCircle.state = MarkView.State.FAILED
            say("ISO: the circle could not be read")
            return
        }
        val error = Mechanism.circleExposureError(linear)
        if (round > 1 && kotlin.math.abs(circleLastStops) > 0.15 && circleLastLinear > 1e-4) {
            val answered = kotlin.math.ln(linear / circleLastLinear) / kotlin.math.ln(2.0)
            exposureResponse[curveIndex] = (answered / circleLastStops).coerceIn(0.3, 1.5)
        }
        val response = exposureResponse[curveIndex] ?: 1.0
        val iso0 = iso
        // the shutter's bounds are the shutter itself: every stop goes to ISO
        val (iso1, _) = Mechanism.splitExposure((error / response).coerceIn(-4.0, 4.0),
            iso0, shutterNs, range.lower, range.upper, shutterNs, shutterNs)
        circleLastLinear = linear
        circleLastStops = kotlin.math.ln(iso1.toDouble() / iso0.coerceAtLeast(1)) / kotlin.math.ln(2.0)
        Trace.state(String.format(java.util.Locale.ROOT,
            "circle ISO: round %d, light %.3f, %+.2f stops (response %.2f), ISO %d → %d, shutter %s held",
            round, linear, error, response, iso0, iso1, Mechanism.formatShutter(shutterNs)))
        val there = kotlin.math.abs(error) < CIRCLE_TOLERANCE_STOPS
        if (there || round > CIRCLE_ROUNDS || iso1 == iso0) {
            exposureCircle.state = if (there) MarkView.State.NEUTRAL else MarkView.State.FAILED
            exposureLockedAt = "locked ISO $iso · " + Mechanism.formatShutter(shutterNs)
            isoPosition = Mechanism.positionOfValue(iso.toDouble(), range.lower.toDouble(), range.upper.toDouble())
            say(when {
                there -> "ISO $iso on the circle · shutter " + Mechanism.formatShutter(shutterNs) + " held"
                error > 0 && iso >= range.upper -> "ISO at its top ($iso): a slower shutter (long press the circle) or more light"
                error < 0 && iso <= range.lower -> "ISO at its floor ($iso): a faster shutter (long press the circle) or less light"
                else -> String.format(java.util.Locale.ROOT, "ISO %d: as close as it goes (%+.1f stops)", iso, error)
            })
            refreshZones()
            refreshKeys()
            return
        }
        val ms = if (round == 1) settings.focusRackMs else (settings.focusRackMs / 2).coerceAtLeast(300L)
        exposureRamp.run(if (settings.focusRackMs > 0) ms else 0L,
            step = { p ->
                circleTicket == ticket && run {
                    iso = Math.round(Mechanism.rampLog(iso0.toDouble(), iso1.toDouble(), p)).toInt()
                    engine.setManualExposure(iso, shutterNs, trace = p >= 1f)
                }
            },
            done = { ui.postDelayed({ isoRound(round + 1, ticket) }, CIRCLE_SETTLE_MS) },
            failed = {
                if (circleTicket == ticket) {
                    exposureCircle.state = MarkView.State.FAILED
                    say("ISO: the camera refused the ramp")
                }
            })
    }

    /** The circle is put away, its mode changed, or exposure set another way: its ramp stops. */
    private fun releaseCircle() {
        circleTicket++
        exposureRamp.cancel()
    }

    /**
     * THE SPOT (v101): the white balance mark's lit pixels, in LINEAR light. v143: the circle on WB, was the triangle.
     *
     * Round 1 chooses the pixels — lit (not the black around a key) and not clipped — and every later round
     * reads exactly those, because v100 let the population change from round to round (a red key crossing
     * the clipping line dropped out) and the answer swung r/g 0.16 → 3.19 → 0.18 (trace 2.10.2026 03:41).
     * Returns r, g, b means, or null when too few pixels qualify.
     */
    private var spotMask: BooleanArray? = null

    private fun sampleSpotRgb(choose: Boolean): DoubleArray? {
        if (!preview.isAvailable) return null
        // v102: at half the view's OWN size and shape. v101 read a 192x108 landscape thumbnail of a portrait
        // view, so each sample row averaged ~17 screen rows and a keyboard's thin lit legends blended into the
        // black around them: "nothing lit in the triangle" over a lit keyboard (trace 2.10.2026 03:47).
        val w = (preview.width / 2).coerceAtLeast(64)
        val h = (preview.height / 2).coerceAtLeast(64)
        val bmp = runCatching { preview.getBitmap(w, h) }.getOrNull() ?: return null
        val bd = exposureCircle.normalisedBounds()
        val x0 = (bd[0] * w).toInt().coerceIn(0, w - 1)
        val x1 = (bd[2] * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (bd[1] * h).toInt().coerceIn(0, h - 1)
        val y1 = (bd[3] * h).toInt().coerceIn(y0 + 1, h)
        val curve = LogCurves.Curve.entries[curveIndex]
        val count = (x1 - x0) * (y1 - y0)
        if (choose || spotMask?.size != count) spotMask = BooleanArray(count)
        val mask = spotMask!!
        val sum = DoubleArray(3)
        var n = 0
        var i = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = bmp.getPixel(x, y)
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val bl = android.graphics.Color.blue(c)
            if (choose) mask[i] = maxOf(r, g, bl) >= SPOT_DARK && maxOf(r, g, bl) <= SPOT_CLIPPED &&
                exposureCircle.contains((x + 0.5f) / w, (y + 0.5f) / h)
            if (mask[i]) {
                sum[0] += LogCurves.decode(curve, r / 255.0)
                sum[1] += LogCurves.decode(curve, g / 255.0)
                sum[2] += LogCurves.decode(curve, bl / 255.0)
                n++
            }
            i++
        }
        bmp.recycle()
        return if (n < SPOT_MIN_PIXELS || sum[1] <= 0) null else doubleArrayOf(sum[0] / n, sum[1] / n, sum[2] / n)
    }

    private var spotTicket = 0
    /** v126: how strongly the picture answered the gains, per curve, learnt by the last tap. */
    private val spotResponse = HashMap<Int, DoubleArray>()
    private val wbRamp by lazy { Ramp(ui) }
    private var spotLearn: WhiteBalance.SpotLearn? = null

    /** A tap on the circle on WB. */
    private fun spotWhiteBalance() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        val ticket = ++spotTicket
        probeTicket++                       // a running A gives way
        // v126: from what the last tap on this curve learnt, or cautious (a first step that falls short)
        spotLearn = WhiteBalance.SpotLearn(spotResponse[curveIndex]
            ?: doubleArrayOf(WhiteBalance.CAUTIOUS_RESPONSE, WhiteBalance.CAUTIOUS_RESPONSE))
        exposureCircle.state = MarkView.State.MEASURING
        wbLockedAt = null
        say("White balance: reading the circle…")
        spotRound(1, ticket)
    }

    /**
     * One round: read the rectangle; stop when it is neutral or the rounds are spent; otherwise step red and
     * blue gain by what the last rounds taught about how strongly the picture answers (WhiteBalance.SpotLearn),
     * and come back when the picture has taken the new gains.
     */
    private fun spotRound(round: Int, ticket: Int) {
        if (spotTicket != ticket || !pipeline.isRunning) return
        val engine = pipeline.engine
        val rgb = sampleSpotRgb(choose = round == 1)
        val gains = engine.currentGains()
        val learn = spotLearn ?: return
        if (rgb == null || gains == null) {
            exposureCircle.state = MarkView.State.FAILED
            say(if (round == 1) "Nothing lit in the circle (all dark or clipped) — point it at something white or grey"
                else "White balance: the circle went dark or clipped at round $round")
            Trace.refused("white balance spot", "round $round: nothing in the circle between dark and clipped")
            return
        }
        Trace.state(String.format(java.util.Locale.ROOT,
            "white balance spot: round %d, circle linear r/g %.3f b/g %.3f", round, rgb[0] / rgb[1], rgb[2] / rgb[1]))
        val neutral = WhiteBalance.spotNeutral(rgb, SPOT_TOLERANCE)
        if (neutral || round > WhiteBalance.SPOT_ROUNDS) {
            if (round > 2) spotResponse[curveIndex] = learn.p.copyOf()
            val k = engine.adoptSpotAsAnchor()
            if (k != null) wbKelvin = k
            wbAuto = false
            exposureCircle.state = if (neutral) MarkView.State.NEUTRAL else MarkView.State.FAILED
            wbLockedAt = wbLockText()
            say(if (neutral) "White balance locked: neutral after ${round - 1} step${if (round == 2) "" else "s"}"
                else String.format(java.util.Locale.ROOT, "White balance: closest after %d steps (r/g %.2f, b/g %.2f)",
                    round - 1, rgb[0] / rgb[1], rgb[2] / rgb[1]))
            refreshZones(); refreshKeys()
            return
        }
        val next = learn.next(gains, rgb)
        // v125, THE RAMP: the gains walk to the next step, eased in and out, in ratios (a stop of red looks
        // the same at either end); the round reads the picture once it has arrived and settled
        val ms = if (round == 1) settings.focusRackMs else (settings.focusRackMs / 2).coerceAtLeast(300L)
        wbRamp.run(if (settings.focusRackMs > 0) ms else 0L,
            step = { p ->
                spotTicket == ticket && engine.setWhiteBalanceGains(
                    FloatArray(4) { Mechanism.rampLog(gains[it].toDouble(), next[it].toDouble(), p).toFloat() },
                    round, trace = p >= 1f)
            },
            done = { ui.postDelayed({ spotRound(round + 1, ticket) }, SPOT_SETTLE_MS) },
            failed = {
                if (spotTicket == ticket) {
                    exposureCircle.state = MarkView.State.FAILED
                    say("White balance: the camera refused the gains")
                }
            })
    }

    /** The last compensation move and the error before it, and the step it proved to be. */
    private var lastMove: Pair<Int, Double>? = null
    private var learntStep = 0.0

    /** The grey readout for the status line, or null on the phone's own curve. */
    private var greyReadout: String? = null

    /**
     * LOG-AWARE AUTO EXPOSURE (v98, Phase 5). *"Make automatic exposure expose
     * for the chosen curve, exactly as the manufacturer specifies."* Twice a
     * second: the grey card is read, its distance from the curve's own grey is
     * worked out in stops, and while exposure is automatic the camera's
     * exposure compensation is moved half of it. On the phone's own curve the
     * compensation goes back to zero. With exposure manual it only reads.
     */
    private val logExposureTick = object : Runnable {
        override fun run() {
            if (pipeline.isRunning) runCatching { stepLogExposure() }
                .onFailure { Trace.fault("log exposure", it) }
            ui.postDelayed(this, 500)
        }
    }

    private fun stepLogExposure() {
        val engine = pipeline.engine
        val curve = LogCurves.Curve.entries[curveIndex]
        val auto = isoAuto && shutterAuto
        val target = Mechanism.greyTarget(curve)
        if (target == null) {
            greyReadout = null
            // v140: the compensation adb asked for is the one kept (it was put back to 0 here twice a second)
            if (auto && engine.aeCompensation != adbEvSteps) engine.setExposureCompensation(adbEvSteps)
            return
        }
        val measured = sampleGreyCard() ?: return
        greyReadout = String.format(
            java.util.Locale.ROOT, "grey %.0f%% · %s %.0f%%", measured * 100, curve.displayName, target * 100
        )
        if (!auto) return
        val range = engine.aeCompensationRange() ?: return
        val error = Mechanism.logExposureError(curve, measured) ?: return
        // What one step really moved, learnt from the last move: a camera
        // whose step is coarser than it says would otherwise hunt between two.
        lastMove?.let { (steps, before) ->
            if (steps != 0) learntStep = maxOf(learntStep, kotlin.math.abs(error - before) / kotlin.math.abs(steps))
        }
        lastMove = null
        val step = maxOf(engine.aeCompensationStep(), learntStep)
        val next = Mechanism.nextCompensation(engine.aeCompensation, error, step, range.lower, range.upper)
        val moved = next - engine.aeCompensation
        if (next != engine.aeCompensation && engine.setExposureCompensation(next)) {
            lastMove = moved to error
            Trace.control(
                "log exposure",
                String.format(java.util.Locale.ROOT, "%s grey %.3f, target %.3f, %+.2f stops", curve.displayName, measured, target, error),
                "compensation $next steps"
            )
        }
    }

    private fun sampleBrightness(): Double? {
        if (!preview.isAvailable) return null
        val bmp = runCatching { preview.getBitmap(24, 14) }.getOrNull() ?: return null
        var sum = 0.0
        var n = 0
        for (y in 4 until 10) for (x in 8 until 16) {
            val c = bmp.getPixel(x, y)
            sum += (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) +
                0.114 * android.graphics.Color.blue(c)) / 255.0
            n++
        }
        bmp.recycle()
        return if (n == 0) null else sum / n
    }

    /**
     * The zones on, and the focus box off.
     *
     * Exclusive on purpose: a tap on the picture that could mean "focus here"
     * and could mean "start changing ISO" is a tap that means neither.
     */
    private fun toggleZones() {
        zonesOn = !zonesOn
        zones.visibility = if (zonesOn) View.VISIBLE else View.GONE
        iris.visibility = if (zonesOn) View.VISIBLE else View.GONE
        refreshMarks()
        Trace.control("controls", if (zonesOn) "on" else "off",
            if (zonesOn) "focus box put away" else "focus box back")
        refreshZones()
        refreshKeys()
    }

    /**
     * One zone dragged. Right is more of everything.
     *
     * The drag moves the **knob**, and the value is read off where the knob
     * ends up. Every zone's whole track is the whole of the range the camera
     * will actually honour — so a full sweep is the full range, half way along
     * is half way along, and there is no part of the travel that cannot be
     * reached. Geometric, because ISO and shutter are stops and a stop is what
     * an operator thinks in.
     *
     * Dragging a zone takes that parameter over, rather than doing nothing
     * until a key somewhere else has been pressed first. A zone that has to be
     * armed is a zone that looks broken.
     */
    private fun nudge(index: Int, delta: Float) {
        val engine = pipeline.engine
        // v127: a hand on the fader takes over from a preset's ramp, where it is
        if (index == 0 || index == 1) glider?.cancel()
        when (index) {
            0 -> {
                if (isoAuto) setParamAuto(0, false, quiet = true)
                val range = engine.isoRange() ?: return
                isoPosition = (isoPosition + delta).coerceIn(0f, 1f)
                iso = Mechanism.valueAtPosition(
                    isoPosition, range.lower.toDouble(), range.upper.toDouble()
                ).toInt().coerceIn(range.lower, range.upper)
                applyDragExposure()
            }
            1 -> {
                if (shutterAuto) setParamAuto(1, false, quiet = true)
                val (low, high) = shutterBounds() ?: return
                shutterPosition = (shutterPosition + delta).coerceIn(0f, 1f)
                shutterNs = Mechanism.valueAtPosition(
                    shutterPosition, low.toDouble(), high.toDouble()
                ).toLong().coerceIn(low, high)
                applyDragExposure()
            }
            2 -> {
                // A lens with no focus motor is said outright rather than
                // letting the number move while the picture does not. An ultra
                // wide is fixed on nearly every phone, and the selfie lens on
                // this one, and for six versions the drag was refused in
                // silence — which is exactly what "focus doesn't change focus"
                // looks like from the outside.
                if (!engine.supportsManualFocus()) { sayFixedFocus(); return }
                focusFraction = (focusFraction + delta).coerceIn(0f, 1f)
                stopTrack()
                if (focus.mode == FocusDirector.Mode.AUTO) {
                    focus.setMode(FocusDirector.Mode.MANUAL)
                    refreshKeys()
                }
                engine.setManualFocus(focusFraction)
                // And then look, a moment later, at whether the glass moved.
                ui.removeCallbacks(focusWatch)
                ui.postDelayed(focusWatch, 600)
            }
            3 -> {
                // Left is tungsten, right is daylight — the way the numbers on
                // a colour meter run, and the way every white balance dial ever
                // made is laid out. Taking it over starts from what the camera
                // had settled on, so the colour does not jump.
                if (wbAuto) {
                    // A drag during a measurement ends it where the camera had got to.
                    probeTicket++
                    wbKelvin = engine.measuredKelvin() ?: wbKelvin
                    wbAuto = false
                }
                val at = (WhiteBalance.travel(wbKelvin) + delta).coerceIn(0f, 1f)
                wbKelvin = WhiteBalance.kelvinAt(at)
                engine.setWhiteBalanceKelvin(wbKelvin)
            }
        }
        refreshZones()
    }

    /**
     * **Push-auto white balance.** Ask the camera once, keep the number.
     *
     * *"I don't want it to go to auto mode. I want it to read the right white
     * balance from auto and move the slider to that position, so the white
     * balance looks the same — and I can see exactly how many Kelvin that is."*
     *
     * This is the AWB button on a real camera, and it is not the same thing as
     * switching auto on: auto is the camera deciding again every frame, which
     * is what an operator lighting a scene is trying to stop. A probe is the
     * camera deciding **once**, at a moment he chooses, with the answer left on
     * the fader as a number that will then sit still.
     *
     * The picture does not jump at the end of it, because the temperature that
     * comes back is applied through the anchor, and the anchor is the very
     * measurement just taken.
     */
    /** v127: which lenses have had their curve measured this run, and whether one is being measured now. */
    private val curveTried = HashSet<String>()
    private var calibrating = false

    /** The open lens's measured white balance curve, from settings, onto the engine (or none). */
    private fun loadWbCurve() {
        pipeline.engine.presetCurve = WhiteBalance.usableCurve(WhiteBalance.decodeCurve(settings.wbCurve("m." + lensKey())))
    }

    private fun probeWhiteBalance() {
        if (!pipeline.isRunning) { say("The camera is not open"); return }
        if (calibrating) return
        // v127: a lens with no curve yet measures it first, once: its presets, a few frames each
        val engine = pipeline.engine
        val lens = lensKey()
        if (engine.presetCurve == null && curveTried.add(lens)) {
            say("Measuring this lens's white balance curve…")
            calibrating = engine.calibratePresets { found ->
                ui.post {
                    calibrating = false
                    val usable = WhiteBalance.usableCurve(found)
                    if (usable != null && lensKey() == lens) {
                        engine.presetCurve = usable
                        settings.setWbCurve("m." + lens, WhiteBalance.encodeCurve(usable))
                        Trace.control("white balance curve", WhiteBalance.encodeCurve(usable), "kept for lens $lens")
                    } else {
                        Trace.refused("white balance curve", "the presets gave no usable curve (" +
                            WhiteBalance.encodeCurve(found) + "): the physics of the light instead")
                    }
                    probeWhiteBalance()
                }
            }
            if (calibrating) return
        }
        say("Measuring white balance…")
        // v90: A on WB is a measurement, not a mode. *"When I press auto, it
        // will just stay auto for a few seconds until it finds the white
        // balance from the camera, and then it will switch back to manual
        // automatically."* A shows while the camera looks; nothing tracks
        // the light afterwards and no earlier manual value comes back.
        wbAuto = true
        wbMeasuredOnce = true
        refreshZones()
        val ticket = ++probeTicket
        // A deadline, because an answer that never comes (the lens changed,
        // the session died) would leave the camera tracking for ever.
        ui.postDelayed({
            if (probeTicket == ticket) {
                probeTicket++
                wbKelvin = pipeline.engine.measuredKelvin() ?: wbKelvin
                wbAuto = false
                if (pipeline.isRunning) pipeline.engine.setWhiteBalanceKelvin(wbKelvin)
                say("White balance held at ${WhiteBalance.format(wbKelvin)} (the camera did not settle)")
                Trace.refused("white balance probe", "no answer within 6 s")
                refreshZones()
                refreshKeys()
            }
        }, 6000)
        val started = pipeline.engine.probeWhiteBalance { kelvin ->
            ui.post {
                // Late, after the deadline or a newer probe: not this one's answer.
                if (probeTicket != ticket) return@post
                probeTicket++
                if (kelvin == null) {
                    // No calibration to turn gains into a temperature. The
                    // camera's own answer is still on the picture, so it is
                    // left there rather than replaced by a number invented
                    // here — a fader that lies is worse than one that stops.
                    wbAuto = true
                    say("This sensor publishes no calibration — left on the camera's own")
                } else {
                    wbKelvin = kelvin
                    wbAuto = false
                    pipeline.engine.setWhiteBalanceKelvin(kelvin)
                    say("White balance measured: ${WhiteBalance.format(kelvin)}, now manual")
                }
                refreshZones()
                refreshKeys()
            }
        }
        if (!started) {
            probeTicket++
            say("The camera would not take a white balance reading")
        }
    }

    /** The ISO presets of the open lens: BASE and, where it says, HIGH. */
    private fun isoPresets(): List<Pair<String, Int>> {
        val engine = pipeline.engine
        if (!pipeline.isRunning) return emptyList()
        val range = engine.isoRange() ?: return emptyList()
        return Mechanism.isoPresets(range.lower, range.upper, engine.maxAnalogIso())
    }

    /** The five shutter presets at this frame rate, as exposure times. */
    private fun shutterPresets(): List<Long> {
        val range = pipeline.engine.exposureRange() ?: return emptyList()
        return Mechanism.SHUTTER_ANGLES.map {
            Mechanism.shutterForAngle(it, pipeline.fps, range.lower, range.upper)
        }
    }

    /**
     * A preset under a fader (v90). *"These presets are nudging the slider of
     * shutter speed to the right shutter speed."* The parameter goes to M and
     * the fader glides there over a quarter of a second, the picture following,
     * then lands on the exact value.
     */
    private fun applyPreset(index: Int, preset: Int) {
        if (!pipeline.isRunning) return
        when (index) {
            0 -> {
                val (name, target) = isoPresets().getOrNull(preset) ?: return
                val range = pipeline.engine.isoRange() ?: return
                if (isoAuto) setParamAuto(0, false, quiet = true)
                // v129: with the shutter on A, it moves against ISO every frame so the exposure holds
                val carry = carryFrom()
                glide(isoPosition, Mechanism.positionOfValue(
                    target.toDouble(), range.lower.toDouble(), range.upper.toDouble()
                ), { p ->
                    isoPosition = p
                    iso = Mechanism.valueAtPosition(p, range.lower.toDouble(), range.upper.toDouble())
                        .toInt().coerceIn(range.lower, range.upper)
                    carry?.let { (iso0, sh0) -> carryShutter(iso0, sh0) }
                }) { iso = target; carry?.let { (iso0, sh0) -> carryShutter(iso0, sh0) }; landCarry() }
                say("ISO $target ($name)")
            }
            1 -> {
                val target = shutterPresets().getOrNull(preset) ?: return
                val (low, high) = shutterBounds() ?: return
                if (shutterAuto) setParamAuto(1, false, quiet = true)
                // v129: with ISO on A, it moves against the shutter every frame so the exposure holds
                val carry = carryFrom()
                glide(shutterPosition, Mechanism.positionOfValue(
                    target.toDouble(), low.toDouble(), high.toDouble()
                ), { p ->
                    shutterPosition = p
                    shutterNs = Mechanism.valueAtPosition(p, low.toDouble(), high.toDouble()).toLong()
                    carry?.let { (iso0, sh0) -> carryIso(iso0, sh0) }
                }) { shutterNs = target; carry?.let { (iso0, sh0) -> carryIso(iso0, sh0) }; landCarry() }
                say("${Mechanism.SHUTTER_ANGLES[preset]}° = ${Mechanism.formatShutter(target)}")
            }
        }
        // The M key reads the switches: a preset just put one on M.
        if (Mechanism.cameraMode(switches()) == "HM") lastHalf = switches()
        refreshKeys()
    }

    private var glider: android.animation.ValueAnimator? = null

    /**
     * v129: a preset key moves one half; when the other half is on A (the app's loop), that half moves against
     * it every frame so ISO × shutter, the exposure, stays where it was — what a camera on auto ISO does when
     * the shutter is turned. Returns the starting ISO and shutter, or null when nothing is to be carried.
     */
    private fun carryFrom(): Pair<Int, Long>? {
        val mode = Mechanism.exposureMode(isoAuto, shutterAuto)
        if (loopNative || (mode != Mechanism.Exposure.ISO_PRIORITY && mode != Mechanism.Exposure.SHUTTER_PRIORITY)) return null
        presetCarries = true
        return iso to shutterNs
    }

    private fun carryIso(iso0: Int, sh0: Long) {
        val r = pipeline.engine.isoRange() ?: return
        iso = Math.round(iso0.toDouble() * sh0 / shutterNs.coerceAtLeast(1L)).toInt().coerceIn(r.lower, r.upper)
    }

    private fun carryShutter(iso0: Int, sh0: Long) {
        val (low, high) = shutterBounds() ?: return
        shutterNs = (sh0.toDouble() * iso0 / iso.coerceAtLeast(1)).toLong().coerceIn(low, high)
    }

    /** The carried half is where the spring starts from, at rest: the loop goes on from there, smoothly. */
    private fun landCarry() {
        val mode = Mechanism.exposureMode(isoAuto, shutterAuto)
        presetCarries = false
        springX = kotlin.math.ln(autoHalf(mode))
        springV = 0.0
        loopGoal = autoHalf(mode)
        loopSentIso = iso
        loopSentShutterNs = shutterNs
    }

    /**
     * Moves a fader from [from] to [to], applying exposure on the way, then [land]s exactly.
     *
     * v127, THE RAMP: *"please do the ramping when I change my shutter speed in degrees from button to
     * button"* (Marko, 2.10.2026). Over the ramp time, eased in and out (smootherstep), the fader's own
     * travel being even in stops; v126 took a quarter of a second, too quick to be a hand. Zero snaps.
     */
    private fun glide(from: Float, to: Float, step: (Float) -> Unit, land: () -> Unit) {
        glider?.cancel()
        glider = android.animation.ValueAnimator.ofFloat(from, to.coerceIn(0f, 1f)).apply {
            duration = settings.focusRackMs.coerceAtLeast(0L)
            interpolator = android.animation.TimeInterpolator { Mechanism.rampEase(it) }
            addUpdateListener {
                step(it.animatedValue as Float)
                applyDragExposure()
                refreshZones()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    land()
                    applyDragExposure()
                    refreshZones()
                }
            })
            start()
        }
    }

    /** Which probe is waiting; anything else arriving is stale. */
    private var probeTicket = 0

    /** The first camera session measures white once, so WB starts as a number. */
    private var wbMeasuredOnce = false

    /**
     * A drag on ISO or shutter: in full manual both go to the camera; in a
     * native priority mode only the held half is sent and the camera keeps
     * the other; in the app's loop the loop goes on from the new value.
     */
    private fun applyDragExposure() {
        val engine = pipeline.engine
        if (loopNative) engine.setPriorityExposure(if (!isoAuto) 1 else 2, iso, shutterNs)
        else engine.setManualExposure(iso, shutterNs)
    }

    /** Said once per lens: a fixed lens is a fact, not an error to repeat. */
    private fun sayFixedFocus() {
        val lens = lenses.getOrNull(activeLens)?.label ?: "This lens"
        val key = lensKey()
        if (focusComplaintFor != key) {
            focusComplaintFor = key
            Trace.refused("focus", "$lens has no focus travel; it is fixed")
        }
        say("$lens is fixed focus — there is nothing to pull")
    }

    /**
     * Did the glass actually move?
     *
     * A lens that refuses in silence and a lens doing its job look identical
     * from in here, and this camera has shipped both. The request and what the
     * capture result says the lens reached are compared a beat later, and a
     * lens that is not following is said on the status line rather than left
     * for the operator to work out from a picture that will not sharpen.
     */
    private val focusWatch = Runnable {
        if (pipeline.isRunning && !pipeline.engine.focusIsResponding()) {
            val lens = lenses.getOrNull(activeLens)?.label ?: "This lens"
            say("$lens is not following the focus zone — see the trace")
            Trace.refused(
                "focus",
                "$lens did not reach what was asked: wanted " +
                    pipeline.engine.focusCommanded + ", reached " +
                    pipeline.engine.lastFocusDistance
            )
        }
    }

    /**
     * What the four zones currently say.
     *
     * The numbers are the camera's own answers wherever the camera has one —
     * the ISO and the shutter it settled on, the distance the lens actually
     * reached — rather than the value that was asked for. A zone that echoes
     * the request agrees with itself whatever the lens is doing, which is how
     * a focus control that never moved anything looked correct for six
     * versions.
     *
     * The knob, though, is where the thumb left it. Those are two different
     * questions and answering both with one number is what made the shutter
     * zone stop two thirds of the way along its own track.
     */
    private fun refreshZones() {
        val engine = pipeline.engine
        val running = pipeline.isRunning
        val apertures = engine.apertures()
        val liveIso = engine.lastIso ?: iso
        val liveShutter = engine.lastExposureNs ?: shutterNs

        val canFocus = running && engine.supportsManualFocus()
        val reached = engine.lastFocusDistance
        val autoFocus = focus.mode == FocusDirector.Mode.AUTO
        val focusText = when {
            !running -> "—"
            // No focus motor on this lens. An ultra wide on most phones, and
            // the selfie lens on this one.
            !canFocus -> "FIXED"
            autoFocus -> "AUTO"
            reached != null && reached > 0.01f -> String.format("%.2fm", 1f / reached)
            else -> "∞"
        }
        val closest = if (running) engine.minimumFocusDistance() else 0f
        val focusPosition = when {
            !canFocus -> 0f
            autoFocus && reached != null && closest > 0f -> (reached / closest).coerceIn(0f, 1f)
            else -> focusFraction
        }

        val isoPresetList = isoPresets()
        val shutterPresetList = if (running) shutterPresets() else emptyList()
        val isoPositionShown = engine.isoRange()?.let {
            if (!isoAuto) isoPosition
            else Mechanism.positionOfValue(
                liveIso.toDouble(), it.lower.toDouble(), it.upper.toDouble()
            )
        } ?: isoPosition
        val shutterPositionShown = shutterBounds()?.let { (low, high) ->
            if (!shutterAuto) shutterPosition
            else Mechanism.positionOfValue(
                liveShutter.toDouble(), low.toDouble(), high.toDouble()
            )
        } ?: shutterPosition

        zones.zones = listOf(
            // While a fader is his, the number is what he set, so it moves
            // with his thumb rather than a frame behind the camera.
            ControlZones.Zone(
                "ISO", (if (isoAuto) liveIso else iso).toString(), true, isoPositionShown, isoAuto,
                presets = isoPresetList.map { (name, value) -> "$name $value" },
                presetLit = if (isoAuto) -1
                    else Mechanism.presetLit(iso.toDouble(), isoPresetList.map { it.second.toDouble() })
            ),
            ControlZones.Zone(
                "SHTR", Mechanism.formatShutter(if (shutterAuto) liveShutter else shutterNs), true,
                shutterPositionShown, shutterAuto,
                presets = if (shutterPresetList.isEmpty()) emptyList()
                    else Mechanism.SHUTTER_ANGLES.map { "$it°" },
                presetLit = if (shutterAuto) -1
                    else Mechanism.presetLit(shutterNs.toDouble(), shutterPresetList.map { it.toDouble() })
            ),
            ControlZones.Zone("FOCUS", focusText, canFocus, focusPosition, autoFocus),
            // Tungsten on the left, daylight on the right. Dragging it takes
            // it off auto; two taps hand it back.
            ControlZones.Zone(
                "WB",
                if (wbAuto) "MEASURING" else WhiteBalance.format(wbKelvin),
                running,
                WhiteBalance.travel(wbKelvin),
                wbAuto
            )
        )

        iris.text = apertures.firstOrNull()?.let { String.format("IRIS  f/%.2f", it) } ?: ""
        // Never in the clean feed. This line runs once a second, and it used
        // to put the F-stop back on a screen that was meant to be empty.
        iris.visibility =
            if (zonesOn && !fullScreen && apertures.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * The shutter speeds this camera will actually honour, at this frame rate.
     *
     * The ceiling is one frame interval — a shutter longer than a frame cannot
     * be held at the frame rate, and the camera settles the argument by
     * dropping the rate, which on a live stream is worse than a dark picture.
     * The floor is 1/8000, because the sensor's own eleven microseconds reads
     * 1/92030 and nobody has ever chosen it.
     */
    private fun shutterBounds(): Pair<Long, Long>? {
        val range = pipeline.engine.exposureRange() ?: return null
        return Mechanism.shutterFaderRange(pipeline.fps, range.lower, range.upper)
    }

    /**
     * Put the knobs where the camera already is.
     *
     * Called whenever this app takes a parameter over or the camera is rebuilt
     * under it. Without it, the first drag after opening a lens jumps the
     * picture to wherever the knob happened to be left.
     */
    private fun seedZonePositions() {
        val engine = pipeline.engine
        engine.isoRange()?.let {
            isoPosition = Mechanism.positionOfValue(
                (engine.lastIso ?: iso).toDouble(), it.lower.toDouble(), it.upper.toDouble()
            )
        }
        shutterBounds()?.let { (low, high) ->
            shutterPosition = Mechanism.positionOfValue(
                (engine.lastExposureNs ?: shutterNs).toDouble(), low.toDouble(), high.toDouble()
            )
        }
    }

    /**
     * **The clean feed.** Everything off the glass but the picture.
     *
     * *"I'm using screen copy to broadcast my camera, not our NDI, so I need
     * the pure full screen mode without anything on the screen, not even the
     * VU meter. Nothing, not even parts of the interface of the phone."*
     *
     * Every pixel this app draws is on the wire when the wire is the phone's
     * own screen, so the question is not which controls to shrink but which to
     * take away — and the answer is all of them, including Android's own
     * status and navigation bars. The picture keeps its shape: it is centred
     * on black at the largest size that does not stretch it, because a
     * stretched picture is the one fault this app has shipped most often and a
     * receiver can crop black but cannot undo a squeeze.
     *
     * The camera is untouched. A take goes on being written and NDI goes on
     * being sent; this is a mode of the screen and of nothing else.
     */
    private fun setFullScreen(on: Boolean) {
        fullScreen = on
        val hidden = if (on) View.GONE else View.VISIBLE
        railLeft.visibility = hidden
        railRight.visibility = hidden
        status.visibility = hidden
        telemetry.visibility = if (!on && settings.showOutputs) View.VISIBLE else View.GONE
        placeStatus()
        vu.visibility = hidden
        timecode.visibility = hidden
        // v154 (seen 26.10.2026 in the page's screenshots): the focus-puller scale with its play key stayed on a
        // clean feed; FULL is nothing but the picture
        showMeter()
        // The zones and the focus box come back to whichever of them was up.
        zones.visibility = if (!on && zonesOn) View.VISIBLE else View.GONE
        refreshMarks()
        iris.visibility = if (!on && zonesOn) View.VISIBLE else View.GONE
        fullScreenCatcher.visibility = if (on) View.VISIBLE else View.GONE
        leaveFullScreen.isEnabled = on

        // No toast: he knows the double tap, and a toast is on the wire.
        hideSystemBars(on)
        Trace.control("clean feed", if (on) "on" else "off", if (on) "screen cleared" else "back")
        refreshKeys()
        // The picture has the whole window to itself now, or has given it back.
        preview.post { holdBufferSize(); applyPreviewTransform() }
    }

    /**
     * Android's own status and navigation bars, off and on.
     *
     * Re-applied rather than set once: the bars come back of their own accord
     * on a rotation and after the app has been away, and a clean feed with a
     * clock and three buttons across it is not a clean feed.
     *
     * Transient-by-swipe rather than immovable, so a phone left in this mode
     * is never trapped in it.
     */
    private fun hideSystemBars(hide: Boolean) {
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        if (hide) {
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.root).setPadding(0, 0, 0, 0)
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The back key leaves the clean feed before it leaves the app. */
    private val leaveFullScreen = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullScreen(false)
    }

    /** What this lens is called in the preferences: its id, and its sub-lens. */
    private fun lensKey(): String {
        val lens = lenses.getOrNull(activeLens) ?: return "0"
        return lens.id + (lens.physicalId?.let { ":$it" } ?: "")
    }


    // --- the LUT switcher, and the other right-rail keys (v85) ----------------

    /**
     * LUT: off, then each LUT loaded in settings, then off again.
     *
     * The eleven slots moved to settings, where LUTs are added and removed;
     * this key only switches between them. A long press opens settings.
     */
    private fun nextLut() {
        val loaded = (1..LutSlots.COUNT).filter { slots.slot(it).loaded }
        // An empty library is not a dead key: it is the way in to filling it.
        if (loaded.isEmpty()) { say("No LUTs yet — add one in settings"); openSettings(); return }
        activeSlot = Mechanism.nextLut(activeSlot, loaded)
        applyLook()
        refreshKeys()
        say(if (activeSlot == 0) "LUT off" else "LUT: " + (slots.slot(activeSlot).label ?: "$activeSlot"))
        Trace.control("LUT", activeSlot, if (activeSlot == 0) "off" else slots.slot(activeSlot).label)
    }

    // --- v145: THE LAST CAPTURE, inside the record button ----------------------------------------------------------

    private var takeThumb: android.graphics.Bitmap? = null

    private fun lastCaptureFile() = java.io.File(filesDir, "last_capture.jpg")

    /** Off the main thread: [picture] made small, shown in the record button, and kept for the next run. */
    private fun keepLastCapture(picture: android.graphics.Bitmap) {
        runCatching {
            val h = (THUMB_PX * picture.height / maxOf(1, picture.width)).coerceAtLeast(1)
            val small = android.graphics.Bitmap.createScaledBitmap(picture, THUMB_PX, h, true)
            lastCaptureFile().outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            ui.post { lastKey.thumbnail = small }
        }.onFailure { Trace.fault("last capture", it) }
    }

    /** The last take: this session's, or the one remembered from before. */
    private fun lastTakeUri(): Uri? =
        pipeline.lastTakeUri ?: settings.lastTake?.let { Uri.parse(it) }

    /** PLAY: the last take in the phone's own player. */
    private fun playLastTake() {
        if (pipeline.isRecording) { say("Recording — stop the take first"); return }
        val uri = lastTakeUri() ?: run { say("No take yet"); return }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (runCatching { startActivity(view); true }.getOrDefault(false)) {
            Trace.control("play", uri.toString(), "opened")
        } else {
            say("No player on this phone opens the take")
        }
    }

    /** v151: the round PLAY key: whatever was captured last — a still in the phone's viewer, else the last take. */
    private fun playLastCapture() {
        val still = settings.lastStill?.let { Uri.parse(it) }
        if (settings.lastIsStill && still != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(still, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (runCatching { startActivity(view); true }.getOrDefault(false)) { Trace.control("play", still.toString(), "still opened"); return }
            say("No viewer on this phone opens the still")
            return
        }
        playLastTake()
    }

    /**
     * The orientation key: LANDSCAPE or PORTRAIT.
     *
     * *"A button to change the orientation of the screen and its labels ...
     * when I rotate the phone I need to read all the labels clearly."* The
     * whole interface goes with it, so every word stays upright. v88: *"it
     * just says LANDSCAPE or PORTRAIT and toggles between the two"* — no AUTO.
     */
    private fun nextShoot() {
        settings.shootMode = Mechanism.toggleShoot(settings.shootMode, screenIsLandscape())
        applyShootMode()
        refreshKeys()
        say(if (settings.shootMode == 1) "Landscape" else "Portrait")
    }

    private fun screenIsLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun applyShootMode() {
        // An older version's "follow the phone" becomes the way the screen is now.
        settings.shootMode = Mechanism.shootResolved(settings.shootMode, screenIsLandscape())
        requestedOrientation = if (settings.shootMode == 1)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }

    /**
     * What the curve key calls the camera's own picture, truthfully.
     *
     * "Rec.709" was a lie in a ten bit session: with no log curve the picture
     * is the phone's own HLG, the broadcast HDR curve with the soft highlight
     * shoulder. HLG when ten bit, STD (the phone's standard SDR curve) when not.
     */
    private fun curveLabel(curve: LogCurves.Curve): String =
        if (curve != LogCurves.Curve.REC709) curve.displayName
        else if (pipeline.isRunning && pipeline.isTenBit) "HLG" else "STD"

    /**
     * The storage key: free space on the drive the takes go to, and how long
     * can still be recorded there at the current bit rate.
     */
    private fun refreshStorage() {
        if (!::storageKey.isInitialized) return
        val free = Recordings.freeBytes(this, settings.recordFolder)
        if (free == null) {
            storageKey.label = "NO DRIVE"
            storageKey.sub = null
            storageKey.state = RailButton.State.ARMED
            storageKey.tint = null
            return
        }
        val left = Mechanism.secondsLeft(free, pipeline.videoBitRate.toLong(), 192_000L)
        storageKey.label = Mechanism.formatFree(free)
        storageKey.sub = Mechanism.formatTimeLeft(left)
        // v145: yellow, the settings' key; red under ten minutes, as before
        storageKey.state = if (left < 10 * 60) RailButton.State.ARMED else RailButton.State.SHOWN
        storageKey.tint = if (left < 10 * 60) null else STORAGE_YELLOW
    }

    /** Record-run timecode, ticking ten times a second while a take runs. */
    private val timecodeTick = object : Runnable {
        override fun run() {
            if (rolling) {
                setTimecode("REC", true, android.os.SystemClock.elapsedRealtime() - recordingSince)
                ui.postDelayed(this, 100)
            }
        }
    }

    /**
     * Bottom middle: the status word, then the timecode (v88).
     *
     * *"The timecode is always white. Next to the timecode we have a status,
     * REC, which becomes red when it is active, or PLAY."* The word carries
     * the state; the numbers never change colour.
     */
    private fun setTimecode(word: String, active: Boolean, elapsedMs: Long) {
        // Called from onCreate too, before the pipeline exists (v88 crashed on
        // launch there): standing by, zero is zero at any rate.
        val fps = if (::pipeline.isInitialized) pipeline.fps else 25
        val tc = Mechanism.timecode(elapsedMs, fps)
        val text = android.text.SpannableString("$word  $tc")
        val wordColour = if (active) android.graphics.Color.rgb(255, 59, 48) else android.graphics.Color.WHITE
        text.setSpan(
            android.text.style.ForegroundColorSpan(wordColour), 0, word.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        timecode.text = text
    }

    private fun showTimecode(recording: Boolean) {
        if (recording) { ui.removeCallbacks(timecodeTick); ui.post(timecodeTick) }
        else { ui.removeCallbacks(timecodeTick); setTimecode("REC", false, 0) }
    }

    /**
     * The monitor LUT and the peaking, in one shader pass.
     *
     * Both are on the View, which means both are on the monitor and neither is
     * on the wire. That is deliberate and it is what a broadcast camera does:
     * the stream carries the log picture the phone's tone mapper produced, and
     * the LUT is how the operator judges it. Baking a display LUT into the
     * stream would throw away the range the log curve was chosen to keep.
     */
    private fun applyLook() {
        // The temperature belongs to the operator, not to the session. A new
        // lens rebuilds the request from the template, so a chosen white
        // balance has to be put back or it silently reverts to auto.
        if (!wbAuto && pipeline.isRunning) pipeline.engine.setWhiteBalanceKelvin(wbKelvin)
        val cube = if (activeSlot > 0) slots.cube(activeSlot) else null
        // The monitor gets the cube exactly; the wire gets as much of it as a
        // tone curve can carry, which is its tone and its colour balance.
        if (pipeline.isRunning) pipeline.engine.setWireLut(cube)
        val ok = PreviewEffects.apply(
            view = preview,
            lut = cube != null,
            peak = peaking,
            peakColour = settings.peakColour,
            sensitivity = settings.peakSensitivity,
            uploaded = cube,
            falseColour = falseColour,
            zebra = zebra,
            zebraLevel = settings.zebraLevel
        )
        if (!ok) {
            say("This phone will not run the preview shader")
            Trace.refused("preview shader", "rejected by the graphics layer")
            peaking = false
            falseColour = false
            zebra = false
            activeSlot = 0
        }
    }

    // --- what the keys say ----------------------------------------------------

    private fun refreshKeys() {
        lenses.getOrNull(activeLens)?.equivalentMm?.takeIf { it > 0 }?.let { lensChooser.sub = "${it}mm" }
        lensChooser.state = if (lensesOpen) RailButton.State.ARMED else RailButton.State.ON
        lensKeys.forEach { it.visibility = if (lensesOpen) View.VISIBLE else View.GONE }
        // v130: while the drawer is open it is the whole rail; every other key steps aside
        for (i in 0 until railLeft.childCount) {
            val k = railLeft.getChildAt(i)
            if (k !== lensChooser && k !in lensKeys) k.visibility = if (lensesOpen) View.GONE else View.VISIBLE
        }
        // v107: each mark key wears its shape's colour of the moment (white, orange searching, green locked,
        // red failed), grey when the shape is off; a bar under it says which one is armed.
        markKeys.forEachIndexed { i, key ->
            val on = settings.marksShown and (1 shl i) != 0
            key.state = if (on) RailButton.State.SHOWN else RailButton.State.OFF
            key.tint = if (!on) null else if (i == 0) focusSquare.colour() else exposureCircle.colour()
            key.marked = on && settings.pinchTarget == i
            key.invalidate()
        }
        lensKeys.forEachIndexed { i, key ->
            val lens = lenses.getOrNull(i)
            key.state = when {
                lens == null -> RailButton.State.DEAD
                i == activeLens && pipeline.isRunning -> RailButton.State.ON
                else -> RailButton.State.OFF
            }
        }

        focusKey.label = when {
            tracking -> "TRK"
            focus.mode == FocusDirector.Mode.AUTO -> "AF"
            else -> "MF"
        }
        focusKey.state =
            if (focus.mode == FocusDirector.Mode.AUTO) RailButton.State.ON
            else RailButton.State.OFF
        peakKey.state = if (peaking) RailButton.State.ON else RailButton.State.OFF
        snapKey.state =
            if (pipeline.isRunning) RailButton.State.SHOWN else RailButton.State.DEAD   // SHOWN: its orange (v155)
        logKey.sub = curveLabel(LogCurves.Curve.entries[curveIndex]).take(5)
        logKey.state =
            if (LogCurves.Curve.entries[curveIndex] == LogCurves.Curve.REC709)
                RailButton.State.OFF
            else RailButton.State.ON
        val mode = Mechanism.cameraMode(switches())
        manualKey.state = if (mode == "AUTO") RailButton.State.OFF else RailButton.State.ON
        manualKey.sub = mode
        ctrlKey.state = if (zonesOn) RailButton.State.ON else RailButton.State.OFF
        screenKey.state = if (fullScreen || LinkServer.clean) RailButton.State.ON else RailButton.State.OFF

        // The LUT switcher says which LUT is on, or OFF.
        lutKey.sub = if (activeSlot > 0) slots.slot(activeSlot).label?.take(5) ?: "$activeSlot" else "OFF"
        lutKey.state = if (activeSlot > 0) RailButton.State.ON else RailButton.State.OFF
        if (activeSlot == 0 && slots.loadedCount() == 0) lutKey.sub = "ADD"
        falseKey.state = if (falseColour) RailButton.State.ON else RailButton.State.OFF
        zebraKey.state = if (zebra) RailButton.State.ON else RailButton.State.OFF
        zebraKey.sub = if (zebra) "${settings.zebraLevel}%" else null
        shootKey.label = if (settings.shootMode == 1) "LAN" else "POR"   // v155: three letters (Marko, 26.10.2026), the name
        shootKey.across = settings.shootMode == 1                     // v155: and the icon lies across in landscape
        shootKey.state = RailButton.State.OFF
        playKey.state = if (lastTakeUri() != null) RailButton.State.OFF else RailButton.State.DEAD

        recKey.dead = !pipeline.isRunning
        recKey.recording = rolling
        refreshTelemetry()
    }

    // --- the status line ------------------------------------------------------

    private var lastSaid = ""
    private var lastSaidAt = 0L

    private fun say(text: String) {
        lastSaid = text
        lastSaidAt = android.os.SystemClock.elapsedRealtime()
        status.text = markStatus() + text
        Trace.state(text)
    }

    // v106: the armed shape's name on the status line, and where it locked, in numbers (Marko, 2.10.2026:
    // "at the top status line, you need to write exposure circle, white balance triangle, focusing square, and
    // then when it reads the value and locks ... write exact number where is it locked").
    private var focusLockedAt: String? = null
    private var exposureLockedAt: String? = null
    private var wbLockedAt: String? = null

    /**
     * v111: the status line sits right under the outputs line when that is shown, and at the very top when it is
     * hidden — no gap (Marko, 2.10.2026: "when my outputs are gone, the status line has a gap").
     */
    private fun placeStatus() {
        val lp = status.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        val dp = resources.displayMetrics.density
        lp.topMargin = ((if (telemetry.visibility == View.VISIBLE) 22 else 2) * dp).toInt()
        status.layoutParams = lp
    }

    private fun markStatus(): String = when (settings.pinchTarget) {
        1 -> if (settings.circleMode == CIRCLE_WB) "WB CIRCLE" + (wbLockedAt?.let { " $it" } ?: "") + "  ·  "
            else "ISO CIRCLE" + (exposureLockedAt?.let { " $it" } ?: "") + "  ·  "
        else -> "FOCUSING SQUARE" + (focusLockedAt?.let { " $it" } ?: "") + "  ·  "
    }

    /**
     * v145, under the square (Marko, 26.10.2026: "In the same style as we have ISO under the circle ... put the meters,
     * which are centimeters or meters, where the focus is actually adjusted, so we always know the distance"): FOC: and
     * where the lens really is, from the capture result, running while it searches.
     */
    private val focusLabelTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 150)
            focusSquare.label = focusLabel()
            if (focusMeter.visibility == View.VISIBLE) focusMeter.lens = if (pipeline.isRunning) pipeline.engine.lastFocusDistance else null
        }
    }

    private fun focusLabel(): String? {
        if (!pipeline.isRunning) return null
        val e = pipeline.engine
        // v145, his second word (26.10.2026): "It can be FOC. Then column, then centimeters. The set thing. I don't need
        // to have it ... while it's searching, instead of auto, it goes to display the number" — one line, the number runs
        if (e.minimumFocusDistance() <= 0f) return "FOC: FIXED"
        return "FOC: " + (e.lastFocusDistance?.let { Mechanism.formatFocusDistance(it) } ?: "--")
    }

    private fun focusLockText(): String? {
        val d = pipeline.engine.lastFocusDistance ?: return null
        return if (d <= 0.001f) "locked at ∞" else String.format(java.util.Locale.ROOT, "locked at %.2f m (%.2f dpt)", 1f / d, d)
    }

    private fun wbLockText(): String {
        val g = pipeline.engine.currentGains()
        return "locked ${WhiteBalance.format(wbKelvin)}" +
            (g?.let { String.format(java.util.Locale.ROOT, " · gains R %.2f B %.2f", it[0], it[3]) } ?: "")
    }

    private var lastMbps = 0.0
    private var lastWatching = 0

    /** v135: where the sound comes from now, as Android routed it. */
    private var audioLabel = ""

    private fun showRate(fps: Double, mbps: Double, connections: Int) {
        lastMbps = mbps
        lastWatching = connections
        val depth = if (pipeline.isTenBit) "10-bit" else "8-bit"
        val grey = greyReadout?.let { " · $it" } ?: ""
        status.text = if (fps > 0.5) String.format(java.util.Locale.ROOT, "%s%s · %.1f fps%s · %s", markStatus(), depth, fps, grey, lastSaid)
            else "${markStatus()}$depth$grey · $lastSaid"
        refreshTelemetry()
    }

    /**
     * The telemetry line (v91): FILE, USB, NDI, YT, in place. Dim is not
     * armed, white is armed and waiting for the record key, a red dot and
     * its numbers is sending.
     */
    private fun refreshTelemetry() {
        // LESSONS 7: never read the pipeline before onCreate has made it.
        if (!::telemetry.isInitialized || !::pipeline.isInitialized) return
        val parts = Mechanism.telemetry(
            settings.armFile, pipeline.isRecording, pipeline.videoBitRate / 1_000_000.0,
            // v152: the kind actually streaming (FULL refused by a lens streams HX; the line said FULL over HX's 16 Mb/s)
            settings.armNdi, when (pipeline.mode) { CameraPipeline.Mode.FULL -> 2; CameraPipeline.Mode.HX -> 1; else -> settings.ndiKind },
            pipeline.mode != CameraPipeline.Mode.OFF,
            lastMbps, lastWatching,
            wire = Routing.wireLabel(settings.ndiTransport, pipeline.boundAdapter ?: pipeline.currentAdapter()),
            sound = if (meter != null && settings.ndiAudio) audioLabel.ifEmpty { "MIC" } else ""
        )
        val text = android.text.SpannableStringBuilder()
        parts.forEachIndexed { i, p ->
            if (i > 0) text.append("   ")
            val start = text.length
            if (p.state == Mechanism.Output.LIVE) {
                text.append("● ")
                text.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.rgb(255, 59, 48)),
                    start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val wordStart = text.length
            text.append(p.name)
            if (p.detail.isNotEmpty()) text.append(" ").append(p.detail)
            val colour = when (p.state) {
                Mechanism.Output.OFF -> android.graphics.Color.argb(120, 200, 204, 208)
                Mechanism.Output.READY -> android.graphics.Color.WHITE
                Mechanism.Output.LIVE -> android.graphics.Color.WHITE
            }
            text.setSpan(android.text.style.ForegroundColorSpan(colour), wordStart, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        telemetry.text = text
    }

    private fun exportTrace() {
        val file = Trace.file()
        val text = file?.readText() ?: Trace.lines().joinToString("\n")
        val name = file?.name ?: "trace.txt"
        val where = Downloads.writeText(this, name, text)
        Toast.makeText(
            this,
            where?.let { "Trace → $it" } ?: "The trace could not be written",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (adbRegistered) runCatching { unregisterReceiver(adbControl) }
        keptRunning = false
        pipeline.stop()
        meter?.stop()
        meter = null
        StreamService.stop(this)
    }

    /**
     * v136: the foreground service runs exactly while NDI streams. Started only while the app is in front (Android
     * refuses a camera service started from behind); stopped as soon as the stream is off.
     */
    private fun syncService() {
        val streaming = pipeline.isRunning && pipeline.mode != CameraPipeline.Mode.OFF
        if (streaming && !StreamService.running && !keptRunning) {
            StreamService.start(this, "Streaming NDI on " + Routing.wireLabel(settings.ndiTransport, pipeline.boundAdapter))
        } else if (!streaming && StreamService.running) {
            StreamService.stop(this)
        }
    }
}

// The spot (v100, v101). Out of 255 on the monitor texture: below DARK is the black around a lit thing, above
// CLIPPED a channel has hit the top and no longer says how bright it is. MIN_PIXELS: enough lit legend to
// average (read at half the view's size since v102). SETTLE: gains sent now reach the texture some frames later (8 at 30 fps ~ 270 ms).
private const val SPOT_DARK = 25
private const val SPOT_CLIPPED = 245
private const val SPOT_MIN_PIXELS = 12
private const val SPOT_SETTLE_MS = 450L
/** v129: the auto half's spring: a frame, and how quick (critically damped; settles in about a second). */
private const val SPRING_FRAME_S = 0.033
private const val SPRING_OMEGA = 5.0
/** v125: the exposure circle is there inside a fifth of a stop; three rounds at most; a read after each ramp. */
private const val CIRCLE_TOLERANCE_STOPS = 0.2
private const val CIRCLE_ROUNDS = 3
private const val CIRCLE_SETTLE_MS = 300L
/** v143: the circle's modes (Settings.circleMode). */
private const val CIRCLE_ISO = 0
private const val CIRCLE_WB = 1
private const val SPOT_TOLERANCE = 0.03
