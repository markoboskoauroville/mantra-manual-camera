package com.mantraproductions.ndi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * THE UPDATE KEY (v144, Marko, 26.10.2026: "inside the settings, you need to add an update button to update the
 * app. Clicking on "Version" is a bit difficult").
 *
 * GitHub is asked for the newest release (the repository is public, so no token is carried). A newer version: its
 * Mantra Manual Camera APK — not Mantra Monitor's, which sits in the same release — is handed to Android, which
 * downloads it and offers to install it with its own dialogue, as Mantra Stopwatch does. The app holds no
 * REQUEST_INSTALL_PACKAGES: the phone stays in charge of what is installed on it. The same key, so it updates over
 * this one in place.
 */
object Updater {

    private const val API = "https://api.github.com/repos/markoboskoauroville/mantra-manual-camera/releases/latest"

    /** [onSaid] is called on the main thread with what happened, for a toast or the status line. */
    fun check(context: Context, onSaid: (String) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun say(text: String) = main.post { onSaid(text) }
        say("Update: asking GitHub")
        thread(name = "update-check", isDaemon = true) {
            val said = runCatching {
                val c = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                try {
                    if (c.responseCode != 200) return@runCatching "Update: GitHub answered ${c.responseCode}"
                    val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val newest = json.optString("tag_name").removePrefix("v").toIntOrNull()
                        ?: return@runCatching "Update: the newest release has no version number"
                    val current = BuildConfig.VERSION_CODE
                    if (newest <= current) return@runCatching "Update: v$current is the newest"
                    val assets = json.optJSONArray("assets")
                    val url = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                        .firstOrNull { it.optString("name").let { n -> n.endsWith(".apk") && "manual-camera" in n } }
                        ?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
                        ?: return@runCatching "Update: v$newest has no camera APK"
                    main.post {
                        runCatching {
                            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { onSaid("Update: no browser to download v$newest") }
                    }
                    Trace.control("update", "v$current → v$newest", url)
                    "Update: v$newest is downloading — open it to install"
                } finally {
                    c.disconnect()
                }
            }.getOrElse { "Update: no network" }
            say(said)
        }
    }

    /**
     * v221, ASKED AT THE START (MANTRA_MANIFEST modules/update-at-start.md, Marko 14.11.2026: "When the application starts,
     * it checks for it, downloads it, and asks you, 'Do you want to update?'"). Nothing newer, no network: silent. Never
     * while SKELETON mode runs (the Mac drives the camera then). Yes = the same download as the update key.
     */
    fun askAtStart(activity: android.app.Activity) {
        if (SkeletonMode.active) return
        val main = Handler(Looper.getMainLooper())
        thread(name = "update-at-start", isDaemon = true) {
            val newest = runCatching {
                val c = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000; readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                try {
                    if (c.responseCode != 200) null
                    else JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("tag_name").removePrefix("v").toIntOrNull()
                } finally { c.disconnect() }
            }.getOrNull() ?: return@thread
            if (newest <= BuildConfig.VERSION_CODE) return@thread
            main.post {
                if (activity.isFinishing || activity.isDestroyed || SkeletonMode.active) return@post
                runCatching {
                    android.app.AlertDialog.Builder(activity)
                        .setTitle("Mantra Manual Camera v$newest")
                        .setMessage("A new version is available (this is v${BuildConfig.VERSION_CODE}). Do you want to update?")
                        .setPositiveButton("UPDATE") { _, _ -> check(activity) { said -> android.widget.Toast.makeText(activity, said, android.widget.Toast.LENGTH_LONG).show() } }
                        .setNegativeButton("LATER", null)
                        .show()
                }
                Trace.control("update", "asked at the start", "v${BuildConfig.VERSION_CODE} → v$newest")
            }
        }
    }
}
