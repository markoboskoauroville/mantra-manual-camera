package com.mantraproductions.ndi.sony

import android.app.Activity
import com.mantraproductions.ndi.R
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * TC JAM (Marko, 29.10.2026: "can you build a timecode generator inside the phone? Then I connect with USB first, 6600,
 * and then my Alpha 75. Then when they recognize it's connected, they jam the time code automatically.").
 *
 * The phone's clock is the generator, shown as a running time code at 25 frames. A Sony camera plugged into USB (camera
 * set to USB Connection: PC Remote) is opened with Sony's handshake (SDIO_Connect 1, 2, version, 3), asked whether it
 * offers Time Code Preset (0xD0D3), and if it does, given Time Code Run = Free Run, Time Code Make = Preset and the
 * phone's time at the turn of a second. Every step is written on the screen and to logcat (tag TcJam).
 */
class TcJamActivity : Activity() {
    private lateinit var clock: TextView
    private lateinit var cards: LinearLayout
    private lateinit var log: TextView
    private val main = Handler(Looper.getMainLooper())
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val busy = HashSet<String>()
    private val cardOf = HashMap<String, TextView>()

    /**
     * The screen of a Betacam deck's time code display (Marko, 29.10.2026: "The option and time code are running off the
     * screen. It's in two lines. That's ugly. Please design a nice screen for time code, like they have it in Betacam
     * scopes."): one line always, sized to the width, amber digits over their unlit "88:88:88:88", the deck's flags
     * above (TCG, FREE RUN, 25P NDF, the source), one card per camera, and the JAM key that asks again.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val amber = Color.rgb(0xFF, 0xB0, 0x20)
        fun digits(color: Int) = TextView(this).apply {
            setTextColor(color); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(12, 400, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        clock = digits(amber)
        fun flag(t: String, on: Boolean) = TextView(this).apply {
            text = t; textSize = 13f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setPadding(14, 4, 14, 4)
            setTextColor(if (on) Color.BLACK else amber)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 6f; if (on) setColor(amber) else setStroke(2, amber)
            }
        }
        // upright, the camera displays stack under the flag (v187: side by side they were squeezed to nothing)
        val upright = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val flags = LinearLayout(this).apply {
            gravity = Gravity.CENTER; isBaselineAligned = false
            if (upright) orientation = LinearLayout.VERTICAL
            srcFlag = flag(if (offsetMs == 0L) "PHONE TC GENERATOR · 25P · from the phone clock" else "PHONE TC GENERATOR · 25P · from a camera screen", true)
            addView(srcFlag, LinearLayout.LayoutParams(-2, -2).apply { setMargins(6, 0, 6, 0) }); srcFlag.gravity = Gravity.CENTER
            statusRow = this
        }
        val units = TextView(this).apply {
            text = "HR      MIN      SEC      FRM"; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(0x8A, 0x60, 0x10)); typeface = Typeface.MONOSPACE; letterSpacing = 0.2f
        }
        val window = android.widget.FrameLayout(this).apply {
            setPadding(24, 8, 24, 8)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(0x0A, 0x07, 0x02)); cornerRadius = 18f; setStroke(3, Color.rgb(0x3A, 0x2A, 0x10))
            }
            addView(clock, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(TextView(this@TcJamActivity).apply {
                text = "⛶"; textSize = 26f; setTextColor(amber); setPadding(18, 0, 18, 0); gravity = Gravity.CENTER
                setOnClickListener { fullScreenTc() }
            }, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL))
            setOnClickListener { fullScreenTc() }
        }
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        jamKey = TextView(this).apply {
            text = "PHONE TC GENERATOR > CAMERA (USB)"; maxLines = 1   // one line, ">" not an arrow, larger (Marko, 29.10.2026)
            setAutoSizeTextTypeUniformWithConfiguration(10, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP); gravity = Gravity.CENTER; setPadding(0, 26, 0, 26)
            setTextColor(Color.BLACK); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(amber); cornerRadius = 14f }
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)   // tighter spaces between the words
            setOnClickListener { if (busy.isEmpty()) { held(this, true, "SENDING TC TO CAMERA…"); handle(null); if (busy.isEmpty()) main.postDelayed({ held(this, false) }, 400) } }
        }
        recKey = TextView(this).apply {
            text = recLabel(false); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 30, 0, 30)
            setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setPadding(0, 26, 0, 26)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(0x22, 0x22, 0x26)); cornerRadius = 14f; setStroke(3, Color.rgb(0xFF, 0x3B, 0x3B)) }
            setOnClickListener {
                val n = ble?.ready ?: 0
                if (n == 0) { say("Bluetooth: no camera ready yet (Network → Bluetooth → Bluetooth Rmt Ctrl: On, then pair)"); ble?.start() }
                else {
                    val starting = ble?.anyRecording != true
                    ble?.record(); say("REC pressed on $n camera(s) over Bluetooth")
                    if (starting && clapOn) main.postDelayed({ clap() }, 2000)
                }
            }
        }
        log = TextView(this).apply { setTextColor(getColor(R.color.quiet)); textSize = 12f; typeface = Typeface.MONOSPACE }
        fun key(t: CharSequence, click: () -> Unit) = TextView(this).apply {
            text = red(t); textSize = 13f; gravity = Gravity.CENTER; setPadding(8, 18, 8, 18)
            setTextColor(amber); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply { setStroke(3, amber); cornerRadius = 14f }
            setOnClickListener { click() }
            dip(this)
        }
        readKey = key("CAMERA SCREEN TC → PHONE TC GENERATOR (phone camera reads it)") { if (screen == null) startReading() }
        val keys2 = LinearLayout(this).apply {
            isBaselineAligned = false
            addView(readKey, LinearLayout.LayoutParams(0, -1, 2f).apply { setMargins(0, 0, 10, 0) })
            addView(key(clockLabel()) { setOffset(0); say("generator back to the phone's clock") }, LinearLayout.LayoutParams(0, -1, 1.2f).apply { setMargins(0, 0, 10, 0) })
            addView(key(clapLabel(clapOn)) {}.also { k -> k.setOnClickListener { clapOn = !clapOn; k.text = clapLabel(clapOn) } },
                LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(0, 0, 10, 0) })
            addView(key(if (prefs.getBoolean("follow", true)) "📷🔗📷 SYNC CAMERA REC BUTTONS: ON" else "📷🔗📷 SYNC CAMERA REC BUTTONS: OFF") {}.also { k -> k.setOnClickListener {
                val on = !prefs.getBoolean("follow", true); prefs.edit().putBoolean("follow", on).apply()
                ble?.follow = on; k.text = red(if (on) "📷🔗📷 SYNC CAMERA REC BUTTONS: ON" else "📷🔗📷 SYNC CAMERA REC BUTTONS: OFF")
                say(if (on) "FOLLOW: REC on one camera starts them all" else "FOLLOW off")
            } }, LinearLayout.LayoutParams(0, -1, 1.1f).apply { setMargins(0, 0, 10, 0) })
            addView(key(if (ltc != null) "🔊 AUDIO TC (LTC) OUT: ON" else "🔊 AUDIO TC (LTC) OUT: OFF") {}.also { k -> k.setOnClickListener {
                if (ltc == null) { ltc = Ltc(::gen).also { it.start() }; say("LTC: 25 fps audio time code on the phone's output") }
                else { ltc?.stop(); ltc = null; say("LTC off") }
                k.text = if (ltc != null) "🔊 AUDIO TC (LTC) OUT: ON" else "🔊 AUDIO TC (LTC) OUT: OFF"
            } }, LinearLayout.LayoutParams(0, -1, 1.1f).apply { setMargins(0, 0, 10, 0) })
            addView(key("HELP") { help() }, LinearLayout.LayoutParams(0, -1, 0.8f))
        }
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val keyRows = if (!portrait) listOf(keys2) else {
            val all = (0 until keys2.childCount).map { keys2.getChildAt(it) }
            keys2.removeAllViews()
            all.chunked(3).map { three -> LinearLayout(this).apply {
                isBaselineAligned = false
                three.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(0, 0, if (i < 2) 10 else 0, 0) }) }
            } }
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.BLACK); addView(LinearLayout(this@TcJamActivity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(32, 48, 32, 32)
            addView(flags)
            addView(window, LinearLayout.LayoutParams(-1, if (portrait) 260 else 200).apply { setMargins(0, 16, 0, 6) })
            addView(units)
            addView(cards, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 16, 0, 0) })
            cards.visibility = android.view.View.GONE   // the cameras' state lives in the displays at the top now
            addView(LinearLayout(this@TcJamActivity).apply {
                isBaselineAligned = false
                addView(recKey, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(0, 0, 10, 0) })
                addView(jamKey, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(0, 0, 10, 0) })
                addView(TextView(this@TcJamActivity).apply {
                    clapKey = this
                    text = "CLAP NOW"; textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 26, 0, 26)
                    setTextColor(Color.BLACK); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 14f }
                    setOnClickListener { if (!clapping) clap() }
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
            keyRows.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 14, 0, 0) }) }
            addView(log, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 16, 0, 0) })
        }) })
        // clear of the selfie-camera hole and the navigation buttons on all four sides, both orientations, every layer
        // (MANTRA_MANIFEST android-app.md §3a; Marko, 29.10.2026: "It should have margins to the screen")
        findViewById<android.view.ViewGroup>(android.R.id.content).setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val m = (16 * resources.displayMetrics.density).toInt()
            safe = android.graphics.Rect(i.left + m, i.top + m, i.right + m, i.bottom + m)
            (v as android.view.ViewGroup).let { g -> for (k in 0 until g.childCount) g.getChildAt(k).setPadding(safe.left, safe.top, safe.right, safe.bottom) }
            insets
        }
        startBluetooth()
        listOf(recKey, jamKey).forEach(::dip); clapKey?.let(::dip)
        tick()
        registerReceiver(permission, IntentFilter(ACTION_PERMISSION), RECEIVER_NOT_EXPORTED)
        handle(intent)
    }

    /** One card per camera: its name, and WAITING / JAMMED 12:58:33:00 / NO TIME CODE OVER USB in its colour. */
    private fun card(name: String, state: String, color: Int) = main.post { feed(name, state); }.also { main.post {
        val v = cardOf.getOrPut(name) {
            TextView(this).apply {
                textSize = 16f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setPadding(24, 18, 24, 18)
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(0x14, 0x1A, 0x21)); cornerRadius = 12f }
                cards.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) })
            }
        }
        v.text = "●  ${name.padEnd(10)} $state"; v.setTextColor(color)
    } }

    // ---- the status displays at the top (Marko, 29.10.2026: "this Betacam top. Let's use it not for decoration, but
    // for a real thing: status ... small displays of status for each camera ... Use commercial names, and put a camera
    // icon before them." / "At the top of the screen, I want to see the status of the cameras: are they in sync? Are
    // they recording?") ----
    private class Cam(val model: String) { var bt = ""; var rec = false; var usb = ""; var tc = "" }
    private val cams = LinkedHashMap<String, Cam>()
    private lateinit var statusRow: LinearLayout

    /** Sony's model codes as the shops call them. */
    private fun commercial(model: String) = when (model.removeSuffix(" BT").trim()) {
        "ILCE-7M5" -> "α7 V"; "ILCE-7M4" -> "α7 IV"; "ILCE-7M3" -> "α7 III"; "ILCE-7SM3" -> "α7S III"
        "ILCE-7RM5" -> "α7R V"; "ILCE-7RM4" -> "α7R IV"; "ILCE-7CM2" -> "α7C II"; "ILCE-7CR" -> "α7CR"; "ILCE-7C" -> "α7C"
        "ILCE-6600" -> "α6600"; "ILCE-6700" -> "α6700"; "ILCE-6400" -> "α6400"; "ILCE-6100" -> "α6100"
        "ILCE-1" -> "α1"; "ILCE-1M2" -> "α1 II"; "ILCE-9M3" -> "α9 III"; "ILME-FX3" -> "FX3"; "ILME-FX30" -> "FX30"
        else -> model.removeSuffix(" BT").trim()
    }

    private fun feed(name: String, state: String) {
        if (name == "GENERATOR") { drawStatus(); return }
        val c = cams.getOrPut(name.removeSuffix(" BT")) { Cam(name.removeSuffix(" BT")) }
        if (name.endsWith(" BT")) {
            c.rec = state.contains("RECORDING")
            c.bt = when { state.contains("RECORDING") || state.contains("READY") -> "BT"; state.contains("PAIRING") -> "BT pairing"; else -> "BT …" }
        } else {
            c.usb = "USB"
            c.tc = when {
                state.startsWith("JAMMED") -> "TC SET " + state.removePrefix("JAMMED").trim().take(8)
                state.contains("NO TIME CODE") -> "TC by hand / screen"
                else -> c.tc
            }
        }
        drawStatus()
    }

    /** One small display per camera: icon and name, REC or STBY, how its time code was set, its links. */
    private fun drawStatus() {
        if (!::statusRow.isInitialized) return
        while (statusRow.childCount > 1) statusRow.removeViewAt(1)
        val red = Color.rgb(0xEF, 0x44, 0x44); val green = Color.rgb(0x22, 0xC5, 0x5E); val amber = Color.rgb(0xFF, 0xB0, 0x20)
        cams.values.forEach { c ->
            val synced = c.tc.startsWith("TC SET")
            val edge = if (c.rec) red else if (synced) green else amber
            val links = listOf(c.bt, c.usb).filter { it.isNotEmpty() }.joinToString(" · ")
            statusRow.addView(TextView(this).apply {
                text = red("📷 ${commercial(c.model)}  ${if (c.rec) "● REC" else "STBY"}\n${c.tc.ifEmpty { "TC not set" }}  ·  $links")
                textSize = 12f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(edge)
                setPadding(14, 6, 14, 6)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6f; setStroke(3, edge); setColor(if (c.rec) Color.rgb(0x3A, 0x0B, 0x0B) else Color.BLACK)
                }
            }, (if (statusRow.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(-2, -2)).apply { setMargins(6, 6, 6, 0) })
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private var ble: BleRemote? = null
    private lateinit var recKey: TextView

    private val heard: (String, String, Boolean?) -> Unit = { name, state, recording ->
        if (recording != null) say("$name: ${if (recording) "camera confirms RECORDING" else "camera confirms STOPPED"}")
        card("$name BT", state, if (recording == true) Color.rgb(0xEF, 0x44, 0x44) else Color.rgb(0x5B, 0xA8, 0xF5))
        main.post { recKey.text = recLabel(ble?.anyRecording == true) }
    }

    private fun startBluetooth() {
        val need = arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        if (need.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) { requestPermissions(need, 7); return }
        // the camera's one remote (SonyLink, v187): the camera's status and REC hold share it with this screen
        SonyLink.unlisten(heard); SonyLink.listen(heard)
        ble = SonyLink.start(this)
        ble?.start()   // scan again for cameras switched on since
        ble?.replay()
        say("Bluetooth: looking for Sony cameras (Bluetooth Rmt Ctrl On)")
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (code == 8) { if (r.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startReading() else say("camera permission refused"); return }
        if (r.isNotEmpty() && r.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startBluetooth()
        else say("Bluetooth permission refused: REC over Bluetooth is off")
    }

    // ---- the generator: the phone's clock plus an offset taken from a camera's screen (kept until changed) ----
    private val prefs by lazy { getSharedPreferences("tcjam", MODE_PRIVATE) }
    private var offsetMs: Long = 0
        get() = prefs.getLong("offset", 0)
    private fun gen() = System.currentTimeMillis() + offsetMs
    private lateinit var srcFlag: TextView
    private fun setOffset(ms: Long) {
        prefs.edit().putLong("offset", ms).apply()
        main.post { srcFlag.text = if (ms == 0L) "PHONE TC GENERATOR · 25P · from the phone clock" else "PHONE TC GENERATOR · 25P · from a camera screen"; drawStatus() }
    }

    // ---- the digital clapboard (Marko, 29.10.2026: "when I press record, after two seconds it's going to start three
    // beeps for synchronization over the phone speaker"): three beeps one second apart, the third higher, with the
    // whole screen white and the generator's time code on it at the third ----
    private var clapOn: Boolean
        get() = prefs.getBoolean("clap", true)
        set(v) { prefs.edit().putBoolean("clap", v).apply() }

    /** 🎬 and the word CLAP in red (Marko, 29.10.2026: "an icon of a clapboard next to clap after recording ... Put it
     *  in red"). */
    /** The dot white while the cameras wait, red while they record (Marko, 29.10.2026). */
    private fun recLabel(rec: Boolean): CharSequence = android.text.SpannableString(red(if (rec) "●  REC · tap to STOP ALL" else "●  REC ALL")).apply {
        if (rec) setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0xFF, 0x3B, 0x3B)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    private fun clapLabel(on: Boolean): CharSequence = red("🎬 CLAP AFTER REC: ${if (on) "ON" else "OFF"}")

    /** The word REC is red wherever it is written (Marko, 29.10.2026: "What is red is literally the word REC.
     *  Everywhere in the application, REC should be red."). */
    private fun red(t: CharSequence): CharSequence {
        val sp = android.text.SpannableString(t)
        Regex("""\bREC\b""").findAll(t).forEach {
            sp.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0xFF, 0x3B, 0x3B)), it.range.first, it.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }
    /** An analog clock → a digital one (Marko, 29.10.2026: "one clock, then an arrow, then the other clock ... the phone
     *  clock can be a normal clock, an analog clock, and the generator is a digital clock"). */
    private fun clockLabel(): CharSequence = android.text.SpannableString("🕒 → 88:88\nPHONE CLOCK → PHONE TC GENERATOR").apply {
        val d = indexOf("88:88")
        setSpan(android.text.style.BackgroundColorSpan(Color.rgb(0xFF, 0xB0, 0x20)), d, d + 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.ForegroundColorSpan(Color.BLACK), d, d + 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.TypefaceSpan("monospace"), d, d + 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.RelativeSizeSpan(1.3f), 0, d + 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    private var clapping = false
    private fun clap() {
        clapping = true
        clapKey?.let { held(it, true, "CLAPPING…") }
        main.postDelayed({ clapping = false; clapKey?.let { held(it, false) } }, 2800)
        val rate = 48000
        val pcm = ShortArray(rate * 3)
        // Marko, 29.10.2026: "the first one K sound, then the second K sound, then the third K sound. Its sound is
        // lasting for 300 milliseconds, and the gap in between is 500 milliseconds. Then again, all three sounds."
        // 1 kHz, 2 kHz, 3 kHz, 300 ms each, 500 ms apart; then the three together: the clap
        fun beep(at: Double, hz: List<Double>, len: Double) {
            val start = (at * rate).toInt(); val n = (len * rate).toInt()
            for (i in 0 until n) {
                val fade = minOf(1.0, i / 96.0, (n - i) / 96.0)   // 2 ms edges: a clean click-free start
                val v = hz.sumOf { Math.sin(2 * Math.PI * it * i / rate) } / hz.size
                pcm[start + i] = (v * 0.8 * fade * Short.MAX_VALUE).toInt().toShort()
            }
        }
        beep(0.0, listOf(1000.0), 0.3); beep(0.8, listOf(2000.0), 0.3); beep(1.6, listOf(3000.0), 0.3)
        beep(2.4, listOf(1000.0, 2000.0, 3000.0), 0.3)
        val track = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        track.write(pcm, 0, pcm.size)
        track.play()
        // the LED with each sound (Marko, 29.10.2026: "make a flash on the camera for each sound ... LED flash" / "the
        // flash should be one frame long only ... We can synchronize in editing by the flash"): the torch on for one
        // frame (40 ms at 25p) at the start of each sound
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
        val torch = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (torch != null) listOf(0L, 800L, 1600L, 2400L).forEach { at ->
            main.postDelayed({ runCatching { cm.setTorchMode(torch, true) } }, at)
            main.postDelayed({ runCatching { cm.setTorchMode(torch, false) } }, at + 1000L / FPS)   // one frame (Marko: "Flash is one frame")
        }
        say("CLAP: 1, 2, 3 kHz, then the three together")
        main.postDelayed({ flash() }, 2400)
        main.postDelayed({ track.release() }, 3500)
    }

    /** The third beep: the screen white, the time code black on it, for half a second. */
    private fun flash() {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val slate = TextView(this).apply {
            setBackgroundColor(Color.WHITE); setTextColor(Color.BLACK); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 56f
            text = "CLAP\n" + timecode(gen())
            setPadding(safe.left, safe.top, safe.right, safe.bottom)
        }
        root.addView(slate, android.view.ViewGroup.LayoutParams(-1, -1))
        say("CLAP at ${timecode(gen())}")
        main.postDelayed({ root.removeView(slate) }, 500)
    }

    private var ltc: Ltc? = null

    /** The generator's time code over the whole screen, white on black, for the cameras to film; a tap closes it. */
    private fun fullScreenTc() {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val big = TextView(this).apply {
            setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(20, 600, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val layer = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); setPadding(safe.left, safe.top, safe.right, safe.bottom)
            addView(big, android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { root.removeView(this) }
        }
        root.addView(layer, android.view.ViewGroup.LayoutParams(-1, -1))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fun run() { if (layer.parent != null) { big.text = timecode(gen()); main.postDelayed(::run, 10) } }
        run()
    }

    // ---- reading a camera's screen ----
    private var screen: ScreenReader? = null
    private lateinit var readKey: TextView
    private val readings = ArrayList<Pair<Long, String>>()   // offset candidate (tc − phone time of day), the text read

    /**
     * The reader, full screen (Marko, 29.10.2026: "I will open the camera from this app in full screen, and under the
     * camera will be the running time code from this app. As soon as the application recognizes the on-camera time
     * code, it's going to synchronize, and I can see both numbers running in synchronization ... some kind of change of
     * color of the phone time code so I know they are synced."): the picture above, then CAMERA (what was read) and
     * PHONE (the generator), amber while reading, green once locked and while every new reading agrees within a frame,
     * red when they drift apart. It keeps checking until CLOSE.
     */
    private var safe = android.graphics.Rect(48, 96, 48, 96)
    private var overlay: LinearLayout? = null
    private lateinit var phoneTc: TextView
    private lateinit var cameraTc: TextView
    private lateinit var verdict: TextView
    private var locked = false

    private fun startReading() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 8); return
        }
        readings.clear(); locked = false; lastWall = 0
        val lens = TextureView(this)
        fun big(label: String, color: Int) = TextView(this).apply {
            text = label; setTextColor(color); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(12, 200, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        cameraTc = big("CAMERA  --:--:--:--", Color.WHITE)
        phoneTc = big("PHONE   " + timecode(gen()), Color.rgb(0xFF, 0xB0, 0x20))
        verdict = TextView(this).apply {
            text = "drag the box onto ONE camera's time code"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(0xF2, 0xDD, 0xB4)); typeface = Typeface.MONOSPACE
        }
        val close = TextView(this).apply {
            text = "CLOSE"; textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 24, 0, 24); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(0xFF, 0xB0, 0x20)); cornerRadius = 14f }
            setOnClickListener { stopReading("reader closed") }
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(safe.left, safe.top, safe.right, safe.bottom)
            // the picture at its own shape (Marko: "It's totally stretched"), the reticle in its middle
            val portraitNow = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            val frame = AspectFrame(this@TcJamActivity).apply {
                aspect = if (portraitNow) 9.0 / 16.0 else 16.0 / 9.0
                addView(lens, android.widget.FrameLayout.LayoutParams(-1, -1))
                addView(android.view.View(this@TcJamActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { setStroke(5, Color.rgb(0xFF, 0xB0, 0x20)); cornerRadius = 10f }
                }, android.widget.FrameLayout.LayoutParams(-1, -1).apply { })
            }
            val holder = android.widget.FrameLayout(this@TcJamActivity).apply {
                addView(frame, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
            frame.post {
                // the box: 45 % by 22 % of the picture, in the middle at first; a finger moves it (Marko, 29.10.2026:
                // "make this square for reading time code movable by finger, so I can point to the area of the screen
                // where to read, not to move the phone"); the reader takes the time code nearest its centre
                val box = frame.getChildAt(1)
                (box.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                    width = (frame.width * 0.45).toInt(); height = (frame.height * 0.22).toInt(); gravity = Gravity.CENTER
                }.also { box.layoutParams = it }
                val pinch = android.view.ScaleGestureDetector(this@TcJamActivity, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                        screen?.let { it.zoom = it.zoom * d.scaleFactor; verdict.text = "zoom %.1f×".format(it.zoom) }
                        return true
                    }
                })
                var panning = false; var lastX = 0f; var lastY = 0f
                frame.setOnTouchListener { _, e ->
                    pinch.onTouchEvent(e)
                    if (e.pointerCount > 1 || pinch.isInProgress) return@setOnTouchListener true   // two fingers: zoom only
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        val r = android.graphics.Rect(); box.getHitRect(r)
                        panning = !r.contains(e.x.toInt(), e.y.toInt()); lastX = e.x; lastY = e.y
                    }
                    if (panning) {
                        if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE) screen?.pan(e.x - lastX, e.y - lastY, frame.width, frame.height)
                        lastX = e.x; lastY = e.y
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) readings.clear()
                        return@setOnTouchListener true
                    }
                    // the box's centre follows the finger, kept inside the picture
                    val cx = e.x.coerceIn(box.width / 2f, frame.width - box.width / 2f)
                    val cy = e.y.coerceIn(box.height / 2f, frame.height - box.height / 2f)
                    box.translationX = cx - frame.width / 2f; box.translationY = cy - frame.height / 2f
                    screen?.targetX = (cx / frame.width).toDouble(); screen?.targetY = (cy / frame.height).toDouble()
                    if (e.actionMasked == android.view.MotionEvent.ACTION_UP) { readings.clear(); verdict.text = "box moved: reading here" }
                    true
                }
            }
            addView(cameraTc, LinearLayout.LayoutParams(-1, 150).apply { setMargins(0, 16, 0, 0) })
            addView(phoneTc, LinearLayout.LayoutParams(-1, 150))
            addView(verdict, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 12) })
            addView(close)
        }
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))
        say("reading: point the phone at the camera's screen, time code showing")
        screen = ScreenReader(this, lens, FPS) { tcMs, wall, text ->
            val c = Calendar.getInstance().apply { timeInMillis = wall }
            val tod = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60 + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND)
            var cand = tcMs - tod
            if (cand > 43_200_000) cand -= 86_400_000 else if (cand < -43_200_000) cand += 86_400_000
            main.post { consider(cand, text) }
        }.also { it.start() }
        readTick()
    }

    private fun readTick() {
        if (overlay == null) return
        phoneTc.text = "PHONE   " + timecode(gen())
        if (lastWall > 0) cameraTc.text = "CAMERA  " + timecode(System.currentTimeMillis() + lastCand)
        main.postDelayed(::readTick, 20)
    }

    /**
     * Each reading says: at this moment the camera's time code was at least what it shows, and less than one frame
     * more. So the offset lies in [cand, cand + one frame]; the readings that agree with the median narrow it to
     * where all those windows overlap. Twelve agreeing readings with at least three different frames lock it; after
     * that every reading is checked against the generator.
     */
    private var lastCand = 0L; private var lastWall = 0L
    private fun consider(cand: Long, text: String) {
        if (overlay == null) return
        lastCand = cand + 500 / FPS; lastWall = System.currentTimeMillis()   // the middle of the frame it showed
        val frame = 1000L / FPS
        val green = Color.rgb(0x22, 0xC5, 0x5E); val red = Color.rgb(0xEF, 0x44, 0x44)
        if (locked) {
            // the generator against this reading, in frames (0 = the same frame, as close as a screen can show)
            val off = (cand + frame / 2 - offsetMs).toDouble() / frame
            val inSync = kotlin.math.abs(off) <= 1.0
            phoneTc.setTextColor(if (inSync) green else red)
            verdict.text = if (inSync) "IN SYNC  ·  %+.1f frame".format(off) else "DRIFTED  %+.1f frames  ·  READ again".format(off)
            verdict.setTextColor(if (inSync) green else red)
            return
        }
        readings += cand to text
        val sorted = readings.map { it.first }.sorted()
        val median = sorted[sorted.size / 2]
        val good = readings.filter { kotlin.math.abs(it.first - median) < 120 }
        val lo = good.maxOf { it.first }; val hi = good.minOf { it.first } + frame
        verdict.text = "reading…  ${good.size} of ${readings.size} agree"
        if (good.size >= 12 && good.map { it.second }.distinct().size >= 3) {
            val off = if (lo <= hi) (lo + hi) / 2 else median + frame / 2
            setOffset(off); locked = true
            phoneTc.setTextColor(green)
            verdict.text = "LOCKED  ·  the phone runs with the camera"; verdict.setTextColor(green)
            say("JAMMED FROM SCREEN: offset $off ms, window ${if (lo <= hi) hi - lo else -1} ms, ${good.size} readings")
            card("GENERATOR", "JAMMED FROM SCREEN", green)
        } else if (readings.size >= 80) {
            verdict.text = "could not lock: the readings disagree. Hold steady, closer."; verdict.setTextColor(red)
            readings.clear()
        }
    }

    private fun stopReading(why: String) {
        screen?.stop(); screen = null
        overlay?.let { findViewById<android.view.ViewGroup>(android.R.id.content).removeView(it) }; overlay = null
        say(why)
    }

    /** HELP: the two ways to jam, and which Sony models take a time code over USB. */
    private fun help() {
        android.app.AlertDialog.Builder(this).setTitle("TC Jam · help").setMessage(HELP).setPositiveButton("OK", null).show()
    }

    override fun onDestroy() { ltc?.stop(); screen?.stop(); SonyLink.unlisten(heard); if (!SonyLink.enabled(this)) SonyLink.stop(); unregisterReceiver(permission); main.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun tick() {
        clock.text = timecode(gen())
        main.postDelayed(::tick, 20)
    }

    /** A camera that arrived with the attach intent already has permission; one found on the bus asks for it. */
    private var preset: String? = null
    private var bcd = false

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra("read", false) == true) { main.post { if (screen == null) startReading() }; return }   // test door
        intent?.getStringExtra("tc")?.let { preset = it; bcd = intent.getBooleanExtra("bcd", false); say("preset $it, ${if (bcd) "BCD" else "binary"}") }
        val attached = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        val cameras = (listOfNotNull(attached) + usb.deviceList.values).distinctBy { it.deviceName }.filter { it.vendorId == SONY }
        if (cameras.isEmpty()) say("No Sony camera on USB. Plug one in.")
        // opening the app from its icon only lists the cameras: a plug-in, the JAM key or a chosen time code jams
        // (29.10.2026: reopening the app overwrote the 10:00:00:00 Marko had asked for)
        val jamNow = intent == null || intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent.hasExtra("tc")
        cameras.forEach { d ->
            card(d.productName ?: "camera", if (!jamNow) "ON USB · press JAM to set it" else if (usb.hasPermission(d)) "WAITING" else "ALLOW USB ON THE PHONE", Color.rgb(0xF5, 0x9E, 0x0B))
            if (!jamNow) return@forEach
            if (usb.hasPermission(d)) jam(d)
            else usb.requestPermission(d, PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        }
    }

    private val permission = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) jam(d) else say("${d.productName}: USB permission refused")
        }
    }

    private fun jam(d: UsbDevice) {
        if (!busy.add(d.deviceName)) return
        main.post { if (::jamKey.isInitialized) held(jamKey, true, "SENDING TC TO CAMERA…") }
        thread(name = "jam") {
            try { Ptp(d).use { it.jam() } } catch (e: Exception) { say("${d.productName}: ${e.message}"); card(d.productName ?: "camera", "NOT ANSWERING", Color.rgb(0xEF, 0x44, 0x44)) }
            finally { main.post { busy.remove(d.deviceName); if (busy.isEmpty() && ::jamKey.isInitialized) held(jamKey, false) } }
        }
    }

    private lateinit var jamKey: TextView
    private var clapKey: TextView? = null
    private val restLabel = HashMap<TextView, String>()

    /** A key held down while its action runs: darker, sunk, its label saying what it is doing; back up when done. */
    private fun held(v: TextView, on: Boolean, doing: String = "") {
        if (on) { restLabel.getOrPut(v) { v.text.toString() }; v.text = doing; v.alpha = 0.55f; v.scaleX = 0.96f; v.scaleY = 0.96f }
        else { restLabel.remove(v)?.let { v.text = it }; v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f }
    }

    /** Every key dips under the finger, so a press is seen at once. */
    private fun dip(v: android.view.View) = v.setOnTouchListener { x, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.7f).setDuration(60).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                if (restLabel[x as? TextView] == null) x.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
        }
        false
    }

    private fun say(s: String) {
        Log.i("TcJam", s)
        main.post { log.append(timecode(gen()) + "  " + s + "\n") }
    }

    /** One camera over USB: Sony's PTP (Camera Control PTP 2 for the a6600, PTP 3 for the α7 V). */
    private inner class Ptp(val d: UsbDevice) : AutoCloseable {
        val name = d.productName ?: "camera"
        val c: UsbDeviceConnection
        val bulkIn: UsbEndpoint
        val bulkOut: UsbEndpoint
        var tid = 0

        init {
            val itf = (0 until d.interfaceCount).map { d.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: error("no PTP interface (${d.interfaceCount} interfaces, class ${d.getInterface(0).interfaceClass}): set the camera's USB Connection to PC Remote")
            val eps = (0 until itf.endpointCount).map { itf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            bulkIn = eps.first { it.direction == UsbConstants.USB_DIR_IN }
            bulkOut = eps.first { it.direction == UsbConstants.USB_DIR_OUT }
            c = usb.openDevice(d) ?: error("cannot open")
            check(c.claimInterface(itf, true)) { "cannot claim the PTP interface" }
        }

        override fun close() = c.close()

        fun jam() {
            say("$name: connected (USB ${"%04x".format(d.productId)})"); card(name, "CONNECTED", Color.rgb(0xF2, 0xDD, 0xB4))
            tx(0x1002, 1)                                   // OpenSession (already open answers 0x201E, fine)
            val ptp3 = name.contains("7M5") || name.contains("7M4")
            val want = if (ptp3) 0x012C else 0x00C8
            tx(0x9201, 1, 0, 0); tx(0x9201, 2, 0, 0)
            var version = 0
            repeat(30) { if (version != want) { val r = tx(0x9202, want); if (r.data.size >= 2) version = r.u16(0) } }
            tx(0x9201, 3, 0, 0)
            say("$name: Sony handshake done, protocol ${if (version == 0x012C) "PTP 3" else if (version == 0x00C8) "PTP 2" else "0x%04x".format(version)}")
            val info = tx(0x9209)
            val props = scanProps(info.data)
            say("$name: ${props.size} settings offered; time code preset ${if (0xD0D3 in props) "YES" else "NO"}, run ${if (0xD0D6 in props) "yes" else "no"}, make ${if (0xD0D7 in props) "yes" else "no"}")
            if (0xD0D3 !in props) { say("$name: this camera does not let the phone set its time code over USB"); card(name, "NO TIME CODE OVER USB", Color.rgb(0xEF, 0x44, 0x44)); return }
            set(0xD0D6, byteArrayOf(0x02))                  // Time Code Run: Free Run
            set(0xD0D7, byteArrayOf(0x01))                  // Time Code Make: Preset
            // at the turn of the next second, so frame 00 is exact to the USB's few milliseconds
            val next = (gen() / 1000 + 1) * 1000          // the generator's next whole second
            val cal = Calendar.getInstance().apply { timeInMillis = next }
            val parts = preset?.split(":")?.map { it.toInt() }
                ?: listOf(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND), 0)
            fun f(n: Int) = if (bcd) (n / 10) * 16 + n % 10 else n
            val v = (f(parts[0]) shl 24) or (f(parts[1]) shl 16) or (f(parts[2]) shl 8) or f(parts[3])
            if (preset == null) while (gen() < next - 3) Thread.sleep(1)
            val sent = System.currentTimeMillis()
            val r = set(0xD0D3, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            val shown = preset ?: timecode(next)
            say("$name: JAMMED $shown (sent at +${sent - next} ms, answer 0x%04x)".format(r.code))
            card(name, if (r.code == 0x2001) "JAMMED  $shown" else "REFUSED 0x%04x".format(r.code), if (r.code == 0x2001) Color.rgb(0x22, 0xC5, 0x5E) else Color.rgb(0xEF, 0x44, 0x44))
            Thread.sleep(800)   // the camera reports the old value for a moment after a set (measured 29.10.2026)
            val back = currentU32(tx(0x9209).data, 0xD0D3)
            say("$name: sent 0x%08x, read back 0x%08x".format(v, back ?: -1))
            if (back != null) say("$name: the camera now reads %02d:%02d:%02d:%02d".format(back ushr 24, (back ushr 16) and 0xFF, (back ushr 8) and 0xFF, back and 0xFF))
        }

        fun set(code: Int, value: ByteArray) = tx(0x9205, code, data = value)

        inner class Reply(val code: Int, val data: ByteArray) {
            fun u16(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        }

        /** One PTP transaction: command, optional data out, then data in (if any) and the response. */
        fun tx(op: Int, vararg params: Int, data: ByteArray? = null): Reply {
            tid++
            val cmd = ByteBuffer.allocate(12 + 4 * params.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(12 + 4 * params.size).putShort(1).putShort(op.toShort()).putInt(tid)
            params.forEach { cmd.putInt(it) }
            out(cmd.array())
            if (data != null) {
                out(ByteBuffer.allocate(12 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(12 + data.size).putShort(2).putShort(op.toShort()).putInt(tid).put(data).array())
            }
            var got = ByteArray(0)
            while (true) {
                val p = read()
                val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val len = bb.getInt(0); val type = bb.getShort(4).toInt()
                var whole = p
                while (whole.size < len) whole += read()
                if (type == 2) got = whole.copyOfRange(12, len)
                else if (type == 3) {
                    val code = ByteBuffer.wrap(whole).order(ByteOrder.LITTLE_ENDIAN).getShort(6).toInt() and 0xFFFF
                    Log.i("TcJam", "op 0x%04x -> 0x%04x, %d bytes".format(op, code, got.size))
                    return Reply(code, got)
                }
            }
        }

        private val buf = ByteArray(1 shl 20)
        private fun read(): ByteArray {
            val n = c.bulkTransfer(bulkIn, buf, buf.size, 5000)
            if (n <= 0) error("the camera did not answer")
            return buf.copyOf(n)
        }
        private fun out(b: ByteArray) { check(c.bulkTransfer(bulkOut, b, b.size, 5000) == b.size) { "USB write failed" } }

        /** Property codes in GetAllExtDevicePropInfo: each dataset begins with its code and a datatype from 1 to 0x4002. */
        fun scanProps(b: ByteArray): Set<Int> {
            val s = HashSet<Int>()
            for (i in 0 until b.size - 4) {
                val code = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
                val type = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
                if (code == 0xD0D3 && type == 6 || code == 0xD0D6 && type == 2 || code == 0xD0D7 && type == 2) s += code
                else if (code in 0x5000..0xFFFF && type in 1..10) s += code
            }
            return s
        }

        /** Code, datatype, get/set, enabled, default (4), current (4) for a UINT32 property. */
        fun currentU32(b: ByteArray, code: Int): Int? {
            for (i in 0 until b.size - 14) {
                val c0 = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
                val t = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
                if (c0 == code && t == 6) return ByteBuffer.wrap(b, i + 10, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
            return null
        }
    }

    companion object {
        const val SONY = 0x054C
        const val FPS = 25
        /** Sony's Camera Remote Command 2.02.00 README (models by protocol), and what was measured here. */
        val HELP = """
            TWO WAYS TO JAM

            1. A camera's screen → the phone. Press READ A CAMERA'S SCREEN and point the phone at the camera's screen
            with its time code showing (TC/UB Disp.: TC). The phone reads it many times a second and locks its own
            generator to it (SRC: JAMMED FROM SCREEN). Works with any camera that shows its time code. The generator
            keeps running from the phone's clock; PHONE CLOCK puts it back.

            2. The phone → a camera over USB. Plug the camera into the phone (on the α7 V: USB PORT 1, the fast one;
            MENU → Setup → USB → USB Cnct Mode: Remote Shoot/Trn., on others: PC Remote). The phone sets Time Code Run:
            Free Run, Make: Preset and the generator's time at the turn of a second. This app opens by itself when a
            Sony camera is plugged in.

            So: read the camera you cannot jam (a6600), walk to the one you can (α7 V), plug it in.

            USB TIME CODE: SONY MODELS
            Measured: ILCE-7M5 (α7 V) YES. ILCE-6600 NO (its USB protocol has no time code).

            Camera Control PTP 3 (time code over USB, on bodies that have time code):
            ILCE-1M2, ILCE-1, ILCE-9M3, ILCE-9M2, ILCE-7RM6, ILCE-7RM5, ILCE-7RM4A, ILCE-7RM4, ILCE-7M5,
            ILCE-7M4, ILCE-7SM3, ILCE-7CM2, ILCE-7CR, ILCE-7C, ILCE-6700, ILX-LR1, MPC-2610, ILME-FR7, ILME-FX6,
            ILME-FX3(A), ILME-FX2, ILME-FX30, ZV-E1, ZV-E10M2, ZV-E10, ZV-1M2, ZV-1F, ZV-1(A), DSC-RX1RM3,
            DSC-RX0M2, DSC-RX100M7(A), PXW-Z300 (Z380), PXW-Z200, BRC-AM7.
            The app asks each camera and shows "NO TIME CODE OVER USB" when one does not offer it.

            Camera Control PTP 2 only (no time code over USB; use way 1):
            ILCE-7RM3A, ILCE-7M3, ILCE-7M2, ILCE-6600, ILCE-6400(A), ILCE-6100(A), DSC-RX100M5A, DSC-RX10M4, DSC-HX99.

            REC BOTH CAMERAS: each camera MENU → Network → Bluetooth → Bluetooth Rmt Ctrl: On, then pair with the phone.
        """.trimIndent()
        const val ACTION_PERMISSION = "com.mantraproductions.ndi.SONY_USB_PERMISSION"
        fun timecode(ms: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = ms }
            return "%02d:%02d:%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), (ms % 1000 * FPS / 1000).toInt())
        }
    }
}
