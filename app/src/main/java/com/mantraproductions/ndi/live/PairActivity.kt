package com.mantraproductions.ndi.live

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.mantraproductions.ndi.MainActivity
import com.mantraproductions.ndi.Trace

/**
 * v201: the band site's admin page, opened on this same phone, offers the pairing as a link
 * (mantracam://pair?site=https://...&token=...). Android hands it here; the camera keeps the site and the token
 * (sealed, LiveLink.pair) and comes to the front. Nothing is shown but a short toast; the token is never logged.
 */
class PairActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent?.dataString?.let { LiveLink.parsePair(it) }
        val ok = p != null && LiveLink.pair(this, p.first, p.second)
        Trace.control("live", "pair", if (ok) "paired by a link from another app" else "link refused")
        Toast.makeText(this, if (ok) "Paired with ${LiveLink.siteName(this)}" else "Not a camera pairing link",
            Toast.LENGTH_LONG).show()
        // v208: from SANTOSHI ADMIN (Settings → STREAMING, a Custom Tab over it): back to STREAMING, the tab closed
        // above it; from anywhere else, the camera
        if (com.mantraproductions.ndi.SettingsActivity.open) {
            startActivity(Intent(this, com.mantraproductions.ndi.SettingsActivity::class.java)
                .putExtra(com.mantraproductions.ndi.SettingsActivity.EXTRA_STREAMING, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}
