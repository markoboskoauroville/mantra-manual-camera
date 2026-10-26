package com.mantraproductions.ndi.monitor

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * v150: THE MONITOR'S ADB IDENTITY. Wireless debugging knows a host by its RSA key and the self-signed certificate made
 * from it; pairing (the six digits) is what teaches the camera phone to trust this one. Made once and kept in the app's
 * own files, so a pairing lasts until the camera phone forgets it (Developer options → Revoke authorisations).
 */
object AdbKeys {
    private const val KEY = "adb-key.pk8"
    private const val CERT = "adb-cert.der"

    private var cached: Pair<PrivateKey, Certificate>? = null

    /**
     * v153 (the review): synchronised and cached. Pairing and connecting can start together on a first run, and two
     * threads making their own key at once could leave one's key beside the other's certificate.
     */
    @Synchronized
    fun load(context: Context): Pair<PrivateKey, Certificate> {
        cached?.let { return it }
        val dir = context.filesDir
        val k = File(dir, KEY); val c = File(dir, CERT)
        if (k.exists() && c.exists()) runCatching {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(k.readBytes()))
            val cert = c.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            cached = key to cert
            return key to cert
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        @Suppress("DEPRECATION")
        val gen = org.bouncycastle.x509.X509V3CertificateGenerator().apply {
            val name = javax.security.auth.x500.X500Principal("CN=Mantra Monitor")
            setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            setIssuerDN(name); setSubjectDN(name)
            setNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
            setNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 86_400_000L))
            setPublicKey(pair.public)
            setSignatureAlgorithm("SHA256withRSA")
        }
        @Suppress("DEPRECATION")
        val cert: java.security.cert.X509Certificate = gen.generate(pair.private)   // signed by the phone's own SHA256withRSA
        k.writeBytes(pair.private.encoded)
        c.writeBytes(cert.encoded)
        cached = pair.private to cert
        return pair.private to cert
    }
}

/** The library's connection manager, with this monitor's key and its name as the camera phone will list it. */
class AdbManager(context: Context) : AbsAdbConnectionManager() {
    private val keys = AdbKeys.load(context)
    init {
        setApi(Build.VERSION.SDK_INT)
        // the app's own Conscrypt first, so the pairing's TLS socket can export its keying material
        if (java.security.Security.getProvider("Conscrypt")?.javaClass?.name?.startsWith("org.conscrypt") != true) {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        }
    }
    override fun getPrivateKey(): PrivateKey = keys.first
    override fun getCertificate(): Certificate = keys.second
    override fun getDeviceName(): String = "Mantra Monitor (" + Build.MODEL + ")"
}
