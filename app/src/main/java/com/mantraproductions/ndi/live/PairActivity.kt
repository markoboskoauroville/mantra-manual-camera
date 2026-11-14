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
        Toast.makeText(this, if (ok) "Camera paired with ${LiveLink.siteName(this)}" else "Not a camera pairing link",
            Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}
