package com.mantraproductions.ndi

import com.mantraproductions.ndi.live.LiveLink
import com.mantraproductions.ndi.live.VdoCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v197: the worked example of docs/VDO_NINJA_SIGNALLING.md section 3, byte for byte. */
class VdoCryptoTest {
    private val pw = "a1b2c3d4e5f6"
    private val iv = ByteArray(16) { it.toByte() }

    @Test fun keyAndHash() {
        assertEquals("5802a2d224aede16c297532a4d2cc20b5aed37a1b4a9b8b3b3a618df0f617153", VdoCrypto.hex(VdoCrypto.key(pw)))
        assertEquals("5802a2", VdoCrypto.hash6(pw))
        assertEquals("sb0123456789ab5802a2", VdoCrypto.wireId("sb0123456789ab", pw))
    }

    @Test fun description() {
        val plain = "{\"type\":\"offer\",\"sdp\":\"v=0\\r\\no=- 1 2 IN IP4 127.0.0.1\\r\\ns=-\\r\\n\"}"
        val (ct, v) = VdoCrypto.encrypt(plain, pw, iv = iv)
        assertEquals("000102030405060708090a0b0c0d0e0f", v)
        assertEquals("120a99858d745338e5caae40110bc82a753d2ff10c4f8ac09e47c35ddd09325c8efece93b9cbf357db1e42a5476c16f098652f0add5b73c4cc9df124e16c4998642b0bc4ed7b90e8d5793930e56b84db", ct)
        assertEquals(plain, VdoCrypto.decrypt(ct, v, pw))
    }

    @Test fun candidates() {
        val plain = "[{\"candidate\":\"candidate:1 1 udp 2113937151 192.168.1.5 54321 typ host\",\"sdpMid\":\"0\",\"sdpMLineIndex\":0}]"
        val (ct, _) = VdoCrypto.encrypt(plain, pw, iv = iv)
        assertEquals("13c33f5011a67f11131a43b151d3b8e400d2c313c306aca52012d2519f2c9660adf2050dab1c85228a02001b06f8245104d9c33d6be8bcbded5865585dba42921fcc0f9545d786c75d7972cf687be3e822f18a1059c3b5867e41a848f74b9013c6d3c496b3fe7e375aa6c81d4022c50e", ct)
    }

    @Test fun password() {
        assertEquals("a1b2", VdoCrypto.normalizePassword("  a1b2 "))
        assertEquals("a%20b%C3%A9", VdoCrypto.normalizePassword("a bé"))
    }

    @Test fun pairLink() {
        val p = LiveLink.parsePair("mantracam://pair?site=https://santoshi-band.pages.dev&token=abc123XYZ")!!
        assertEquals("https://santoshi-band.pages.dev", p.first)
        assertEquals("abc123XYZ", p.second)
        assertNull(LiveLink.parsePair("https://example.com/?token=x"))
        assertNull(LiveLink.parsePair("mantracam://pair?site=http://evil.example&token=x"))
    }

    @Test fun opusStereo() {
        val sdp = "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\n"
        val out = LiveLink.opusStereo(sdp)
        assertEquals(true, out.contains("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;maxaveragebitrate=128000\r\n"))
        assertEquals(out, LiveLink.opusStereo(out))
    }
}
