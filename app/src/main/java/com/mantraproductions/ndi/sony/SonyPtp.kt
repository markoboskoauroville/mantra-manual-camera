package com.mantraproductions.ndi.sony

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

/**
 * v202, TC JAM FROM THE CAMERA SCREEN (Marko, 14.11.2026: "When I plug the camera in with USB, actually, this camera app
 * is resetting the time code on my camera, and it's synced with the real-time clock").
 *
 * The same Sony PTP exchange as SONY REMOTE's TC JAM (TcJamActivity.Ptp, measured on the α7 V 29.10.2026), as a class
 * of its own so the camera screen can jam a Sony the moment it is plugged in: OpenSession, the Sony handshake 0x9201
 * 1/2, 0x9202 protocol (PTP 3 = 0x012C for the α7 V / α7 IV), 0x9201 3, then 0x9205 Time Code Run = Free Run (0xD0D6
 * = 2), Make = Preset (0xD0D7 = 1) and Preset (0xD0D3 = h<<24|m<<16|s<<8|f) at the turn of a second, read back by
 * 0x9209 (stale for about half a second after a set, so read after 0.8 s).
 *
 * It needs the PTP interface (still image, class 6): the camera's USB mode Remote Shoot. In USB Streaming the camera
 * offers video and audio only, and then this says so; it never pretends to have jammed.
 */
class SonyPtp private constructor(
    private val d: UsbDevice,
    private val c: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) : AutoCloseable {

    val name: String = d.productName ?: "Sony"
    private var tid = 0

    /** What a jam did, in words for the screen, and the camera's time code read back (null when unread). */
    class Result(val ok: Boolean, val words: String, val readBack: String?)

    override fun close() { runCatching { c.close() } }

    /** Jams the camera to [nowMs] (the phone's TC generator: clock + offset); runs on a worker thread (USB waits). */
    fun jam(nowMs: () -> Long, log: (String) -> Unit): Result {
        tx(0x1002, 1)                                   // OpenSession (already open answers 0x201E, fine)
        val ptp3 = name.contains("7M5") || name.contains("7M4")
        val want = if (ptp3) 0x012C else 0x00C8
        tx(0x9201, 1, 0, 0); tx(0x9201, 2, 0, 0)
        var version = 0
        repeat(30) { if (version != want) { val r = tx(0x9202, want); if (r.data.size >= 2) version = r.u16(0) } }
        tx(0x9201, 3, 0, 0)
        log("$name: Sony handshake, protocol " + when (version) { 0x012C -> "PTP 3"; 0x00C8 -> "PTP 2"; else -> "0x%04x".format(version) })
        val props = scanProps(tx(0x9209).data)
        if (0xD0D3 !in props) return Result(false, "$name does not let the phone set its time code over USB", null)
        set(0xD0D6, byteArrayOf(0x02))                  // Time Code Run: Free Run
        set(0xD0D7, byteArrayOf(0x01))                  // Time Code Make: Preset
        val next = (nowMs() / 1000 + 1) * 1000          // the generator's next whole second: frame 00 exact
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        val h = cal.get(Calendar.HOUR_OF_DAY); val m = cal.get(Calendar.MINUTE); val s = cal.get(Calendar.SECOND)
        val v = (h shl 24) or (m shl 16) or (s shl 8)
        while (nowMs() < next - 3) Thread.sleep(1)
        val sentLate = nowMs() - next
        val r = set(0xD0D3, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        val shown = "%02d:%02d:%02d:00".format(h, m, s)
        log("$name: preset $shown sent at %+d ms, answer 0x%04x".format(sentLate, r.code))
        if (r.code != 0x2001) return Result(false, "$name refused the time code (0x%04x)".format(r.code), null)
        Thread.sleep(800)
        val back = currentU32(tx(0x9209).data, 0xD0D3)?.let {
            "%02d:%02d:%02d:%02d".format(it ushr 24, (it ushr 16) and 0xFF, (it ushr 8) and 0xFF, it and 0xFF)
        }
        return Result(true, "TC JAMMED $shown on the $name" + (back?.let { ", it reads $it" } ?: ""), back)
    }

    /** The camera's time code now (0x9209), or null. */
    fun readTc(): String? = runCatching {
        tx(0x1002, 1)
        currentU32(tx(0x9209).data, 0xD0D3)?.let {
            "%02d:%02d:%02d:%02d".format(it ushr 24, (it ushr 16) and 0xFF, (it ushr 8) and 0xFF, it and 0xFF)
        }
    }.getOrNull()

    private fun set(code: Int, value: ByteArray) = tx(0x9205, code, data = value)

    private class Reply(val code: Int, val data: ByteArray) {
        fun u16(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun tx(op: Int, vararg params: Int, data: ByteArray? = null): Reply {
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

    private fun scanProps(b: ByteArray): Set<Int> {
        val s = HashSet<Int>()
        for (i in 0 until b.size - 4) {
            val code = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            val type = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
            if (code == 0xD0D3 && type == 6 || code == 0xD0D6 && type == 2 || code == 0xD0D7 && type == 2) s += code
        }
        return s
    }

    private fun currentU32(b: ByteArray, code: Int): Int? {
        for (i in 0 until b.size - 14) {
            val c0 = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            val t = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
            if (c0 == code && t == 6) return ByteBuffer.wrap(b, i + 10, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        return null
    }

    companion object {
        /** The device has Sony's PTP (still image) interface: Remote Shoot / PC Remote, not USB Streaming. */
        fun hasPtp(d: UsbDevice): Boolean = d.vendorId == 0x054C &&
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

        /** Opens the PTP interface; null with [why] told when it cannot. */
        fun open(usb: UsbManager, d: UsbDevice, why: (String) -> Unit): SonyPtp? {
            val itf = (0 until d.interfaceCount).map { d.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: run { why("no PTP interface: set the camera's USB mode to Remote Shoot to jam"); return null }
            val eps = (0 until itf.endpointCount).map { itf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val bin = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val bout = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (bin == null || bout == null) { why("the PTP interface has no bulk endpoints"); return null }
            if (!usb.hasPermission(d)) { why("no USB permission"); return null }
            val c = runCatching { usb.openDevice(d) }.getOrNull() ?: run { why("the camera could not be opened"); return null }
            if (!c.claimInterface(itf, true)) { c.close(); why("the PTP interface is held by another app"); return null }
            return SonyPtp(d, c, bin, bout)
        }
    }
}
