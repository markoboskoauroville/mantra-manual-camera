package com.mantraproductions.ndi.sony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THE SONY REMOTE INSIDE THE CAMERA (v187, Marko, 31.10.2026: "We need to build in something we already built, just as
 * a separate app. And that's the Sony Remote Control ... when user enables Sony Remote Control status for Sony, which
 * is just, is it recording or not? and how many cameras are recording").
 *
 * One Bluetooth remote ([BleRemote], from SONY_REMOTE_CONTROLLER) for the whole app: the camera's status line and REC
 * hold use it, and so does SONY REMOTE (TcJamActivity), so a Sony stays paired when one screen gives way to the other.
 * Switched on in Settings → SONY; while it is off nothing scans or connects.
 */
object SonyLink {
    private const val PREFS = "tcjam"
    private const val ENABLED = "cameraLink"

    /** (camera name, state words, recording or null when unknown); called on Bluetooth's thread. */
    private val listeners = CopyOnWriteArrayList<(String, String, Boolean?) -> Unit>()
    private var remote: BleRemote? = null

    fun enabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        if (!on) stop()
    }

    val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    fun permitted(c: Context) = PERMISSIONS.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** The remote, started on the first call; null without Bluetooth permission (the caller asks for it). */
    @Synchronized
    fun start(c: Context): BleRemote? {
        if (!permitted(c)) return null
        remote?.let { return it }
        return runCatching {
            BleRemote(c.applicationContext) { name, state, rec -> listeners.forEach { it(name, state, rec) } }.also {
                it.follow = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("follow", true)
                it.start()
            }
        }.getOrNull().also { remote = it }
    }

    @Synchronized
    fun stop() { remote?.stop(); remote = null }

    fun listen(l: (String, String, Boolean?) -> Unit) { listeners += l }
    fun unlisten(l: (String, String, Boolean?) -> Unit) { listeners -= l }

    /** Cameras ready on Bluetooth, and how many of them record. */
    val ready get() = remote?.ready ?: 0
    val recording get() = remote?.recordingCount ?: 0
    val running get() = remote != null

    /** START SONY / STOP SONY: REC on every camera not already in that state. @return how many were pressed. */
    fun setRecording(on: Boolean) = remote?.recording(on) ?: 0
}

/**
 * The TC generator TC JAM keeps: the phone's clock plus an offset read from a Sony's screen (0 = the phone's own clock).
 * The camera's takes are stamped from it (Mp4Timecode), so they run with the Sonys jammed from the same clock.
 */
object TcGenerator {
    fun offsetMs(c: Context): Long = c.getSharedPreferences("tcjam", Context.MODE_PRIVATE).getLong("offset", 0)
}
