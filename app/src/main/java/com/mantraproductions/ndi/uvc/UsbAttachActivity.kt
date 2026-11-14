package com.mantraproductions.ndi.uvc

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import com.mantraproductions.ndi.Trace

/** v201: where Android's "Open with Manual Camera" hands a Sony on USB to the running camera screen. */
object UsbAttach {
    /** The camera screen's handler, weakly (the screen holds it). */
    @Volatile var hook: java.lang.ref.WeakReference<(UsbDevice) -> Unit>? = null
    /** The camera screen is started (visible). */
    @Volatile var inFront = false
}

/**
 * v201, THE SONY ON USB (Marko, 14.11.2026: "When I plug it in, automatically select the first lens before L1").
 *
 * Android opens this when a Sony video device (vendor 0x054C, a video class interface: USB Streaming, not PTP) is
 * plugged in, after its own dialog "Open Manual Camera to handle ILCE-7M5?". Ticked "Always", the permission stays
 * with the app for every plug from then on, so the Sony becomes the lens without a tap. Nothing is drawn here: the
 * device goes to the camera screen (started, or brought to the front, as the launcher would), then this closes.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        val hook = UsbAttach.hook?.get()
        Trace.state("usb camera: Android handed over ${d?.productName ?: "a device"} " +
            String.format(java.util.Locale.ROOT, "%04X:%04X", d?.vendorId ?: 0, d?.productId ?: 0) +
            (if (hook != null) " to the open camera" else ", the camera starts"))
        if (d != null && hook != null) runCatching { hook(d) }
        if (hook == null || !UsbAttach.inFront) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                runCatching { startActivity(it) }
            }
        }
        finish()
    }
}
