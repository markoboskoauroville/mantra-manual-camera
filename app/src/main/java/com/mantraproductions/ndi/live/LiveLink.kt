package com.mantraproductions.ndi.live

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v197, GO LIVE: the camera's side of the band site's LIVE door (santoshi_band_site TAKEOVER 14.11.2026).
 *
 * - PAIR: the admin page shows mantracam://pair?site=https://...&token=...; the camera keeps the site and the token.
 *   The token is sealed with an AES key that lives in the Android Keystore and never leaves it, and is never logged.
 * - GO LIVE: POST {site}/api/live {action:"go"} with Authorization: Bearer token. The answer names the VDO.Ninja
 *   push id and password the site's viewer is waiting for; STOP sends {action:"stop"}.
 * - v211, SANTOSHI RELAY: GO with {via:"relay"} also returns whip + whipUser + whipKey: the camera sends ONE stream to the band's
 *   relay (MediaMTX in Frankfurt) and the viewers watch it there. The key is used in the WHIP header only and never logged.
 */
object LiveLink {

    /** Settings asked the camera screen to look for the pairing QR (it has the picture). */
    @Volatile var scanRequested = false
    /** v201: STREAMING, VDO NINJA pressed: the camera screen goes live, or stops. */
    @Volatile var toggleRequested = false
    /** What the camera screen last said: OFF, CONNECTING or "n viewers", for the STREAMING tab. */
    @Volatile var livePhase = "OFF"
    /** v211: which way the running live goes: "relay" (SANTOSHI RELAY) or "vdo" (VDO NINJA); "" while off. */
    @Volatile var liveVia = ""

    /** v211: the way GO LIVE takes: SANTOSHI RELAY by default, VDO NINJA when that was the last one pressed. */
    fun via(ctx: Context): String = ctx.getSharedPreferences(PREFS, 0).getString("via", "relay") ?: "relay"
    fun setVia(ctx: Context, v: String) { ctx.getSharedPreferences(PREFS, 0).edit().putString("via", if (v == "vdo") "vdo" else "relay").apply() }

    // --- pure, unit tested -------------------------------------------------------------------------------------------

    /** @return (site, token) from a pairing link, or null. Only https sites. */
    fun parsePair(link: String): Pair<String, String>? = runCatching {
        val u = URI(link.trim())
        if (u.scheme != "mantracam" || u.host != "pair") return null
        val q = (u.rawQuery ?: return null).split('&').mapNotNull {
            val k = it.substringBefore('=', ""); if (k.isEmpty()) null
            else k to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }.toMap()
        val site = q["site"]?.trimEnd('/') ?: return null
        val token = q["token"]?.takeIf { it.isNotBlank() } ?: return null
        val s = URI(site)
        if (s.scheme != "https" || s.host.isNullOrEmpty()) return null
        site to token
    }.getOrNull()

    /**
     * Opus in stereo both ways: the sender only encodes two channels when the answer it was given says stereo=1, so
     * the offer and the viewer's answer both get it (and a music bit rate). Idempotent.
     */
    fun opusStereo(sdp: String): String {
        val pt = Regex("a=rtpmap:(\\d+) opus/48000/2", RegexOption.IGNORE_CASE).find(sdp)?.groupValues?.get(1) ?: return sdp
        val lines = sdp.split("\r\n").toMutableList()
        val i = lines.indexOfFirst { it.startsWith("a=fmtp:$pt ") }
        if (i < 0) return sdp
        var l = lines[i]
        if (!l.contains("stereo=1")) l += ";stereo=1"
        if (!l.contains("sprop-stereo=1")) l += ";sprop-stereo=1"
        if (!l.contains("maxaveragebitrate")) l += ";maxaveragebitrate=128000"
        lines[i] = l
        return lines.joinToString("\r\n")
    }

    /** What the site said to a go. */
    data class Go(val live: Boolean, val push: String?, val password: String?, val view: String?, val error: String?, val code: Int,
                  /** v211, SANTOSHI RELAY: the relay's WHIP door and its publish key (only to a paired camera), the viewers counted by the site */
                  val whip: String? = null, val whipUser: String? = null, val whipKey: String? = null, val viewers: Int? = null)

    fun parseAnswer(code: Int, body: String): Go = runCatching {
        val j = JSONObject(body)
        Go(
            live = j.optBoolean("live", false),
            // the site sends id (the stream id) and push (the browser publisher's whole URL, ?push=<id>)
            push = j.optString("id").ifEmpty { Regex("[?&]push=([^&]+)").find(j.optString("push"))?.groupValues?.get(1).orEmpty() }
                .ifEmpty { null },
            password = j.optString("password").ifEmpty { null },
            view = j.optString("view").ifEmpty { null },
            error = j.optString("error").ifEmpty { null } ?: if (code >= 400) "HTTP $code" else null,
            code = code,
            whip = j.optString("whip").takeIf { it.startsWith("https://") },
            whipUser = j.optString("whipUser").ifEmpty { null },
            whipKey = j.optString("whipKey").ifEmpty { null },
            viewers = if (j.has("viewers") && !j.isNull("viewers")) j.optInt("viewers") else null
        )
    }.getOrElse { Go(false, null, null, null, if (code >= 400) "HTTP $code" else "unreadable answer", code) }

    // --- the site ----------------------------------------------------------------------------------------------------

    /** POST {site}/api/live {action}. Blocking: call from a worker thread. The token goes in the header only. */
    fun call(site: String, token: String, action: String, via: String? = null): Go {
        val c = (URL("$site/api/live").openConnection() as HttpURLConnection)
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $token")
            val req = JSONObject().put("action", action).also { if (via != null) it.put("via", via) }
            c.outputStream.use { it.write(req.toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { s ->
                s.readBytes().decodeToString().take(16_000)       // bounded read
            }.orEmpty()
            parseAnswer(code, body)
        } catch (e: Exception) {
            Go(false, null, null, null, e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: ""), 0)
        } finally { c.disconnect() }
    }

    // --- the paired site, kept on the phone ---------------------------------------------------------------------------

    private const val PREFS = "live"
    private const val KEY_ALIAS = "mantra-live-token"

    fun site(ctx: Context): String? = ctx.getSharedPreferences(PREFS, 0).getString("site", null)
    fun siteName(ctx: Context): String? = ctx.getSharedPreferences(PREFS, 0).getString("name", null)
    fun paired(ctx: Context): Boolean = site(ctx) != null && ctx.getSharedPreferences(PREFS, 0).contains("token")

    fun pair(ctx: Context, site: String, token: String): Boolean = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret())
        val sealed = c.doFinal(token.toByteArray())
        ctx.getSharedPreferences(PREFS, 0).edit()
            .putString("site", site)
            .putString("name", runCatching { URI(site).host }.getOrNull() ?: site)
            .putString("token", VdoCrypto.hex(c.iv) + ":" + VdoCrypto.hex(sealed))
            .apply()
        true
    }.getOrDefault(false)

    fun token(ctx: Context): String? = runCatching {
        val s = ctx.getSharedPreferences(PREFS, 0).getString("token", null) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, VdoCrypto.unhex(s.substringBefore(':'))))
        String(c.doFinal(VdoCrypto.unhex(s.substringAfter(':'))))
    }.getOrNull()

    fun unpair(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }
}
