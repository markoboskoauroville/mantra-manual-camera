package com.mantraproductions.ndi.live

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v197, GO LIVE: what the password changes on the VDO.Ninja wire (docs/VDO_NINJA_SIGNALLING.md section 3), written
 * from that description, not copied from any client. Pure functions, so the worked example in the doc is a unit test.
 *
 * - the password is trimmed and percent-encoded like encodeURIComponent;
 * - key = SHA-256(pw + salt), 32 bytes; the stream id on the wire is id + the first 3 bytes of it in hex;
 * - description and candidates travel as AES-256-CBC (PKCS#7) of their compact JSON, hex, with a fresh 16-byte IV
 *   in "vector".
 */
object VdoCrypto {
    const val SALT = "vdo.ninja"

    private const val UNRESERVED = "-_.!~*'()"

    fun normalizePassword(raw: String): String {
        val s = raw.trim()
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in UNRESERVED)) out.append(ch)
            else out.append('%').append(HEX_UPPER[c shr 4]).append(HEX_UPPER[c and 15])
        }
        return out.toString()
    }

    fun key(password: String, salt: String = SALT): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((normalizePassword(password) + salt).toByteArray(Charsets.UTF_8))

    fun hash6(password: String, salt: String = SALT): String = hex(key(password, salt).copyOfRange(0, 3))

    fun wireId(id: String, password: String, salt: String = SALT): String = id + hash6(password, salt)

    /** @return the hex cipher text and the hex IV ("vector"). [iv] is fixed only in tests. */
    fun encrypt(plainJson: String, password: String, salt: String = SALT, iv: ByteArray? = null): Pair<String, String> {
        val v = iv ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(password, salt), "AES"), IvParameterSpec(v))
        return hex(c.doFinal(plainJson.toByteArray(Charsets.UTF_8))) to hex(v)
    }

    fun decrypt(cipherHex: String, ivHex: String, password: String, salt: String = SALT): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(password, salt), "AES"), IvParameterSpec(unhex(ivHex)))
        return String(c.doFinal(unhex(cipherHex)), Charsets.UTF_8)
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

    fun hex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        b.forEachIndexed { i, x -> out[2 * i] = HEX[(x.toInt() shr 4) and 15]; out[2 * i + 1] = HEX[x.toInt() and 15] }
        return String(out)
    }

    fun unhex(s: String): ByteArray {
        val t = s.trim()
        require(t.length % 2 == 0) { "odd hex" }
        return ByteArray(t.length / 2) { i -> ((Character.digit(t[2 * i], 16) shl 4) or Character.digit(t[2 * i + 1], 16)).toByte() }
    }

    /** A made-up test stream: "mmcnight" + 6 random hex, and a 12-hex password. Never the band's own. */
    fun randomHex(n: Int): String = hex(ByteArray((n + 1) / 2).also { SecureRandom().nextBytes(it) }).take(n)
}
