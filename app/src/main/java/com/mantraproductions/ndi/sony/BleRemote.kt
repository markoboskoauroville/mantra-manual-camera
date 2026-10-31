package com.mantraproductions.ndi.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * The phone as a Sony Bluetooth remote commander for several cameras at once (Marko, 29.10.2026: "Build in a Bluetooth
 * start recording on both cameras at the same time"). The camera's remote service 8000FF00: two bytes on FF01 are a
 * button (01 0F = movie REC pressed, 01 0E = released), FF02 notifies the camera's state (byte 1 0xD5 with bit 0x20 of
 * byte 2 = recording). The link must be bonded and the camera's Network → Bluetooth → Bluetooth Rmt Ctrl must be On
 * (sarnau/sony-camera-protocol §1.10, hardware-verified on an A7R V; read, not copied).
 */
@SuppressLint("MissingPermission")   // the activity asks for BLUETOOTH_SCAN and BLUETOOTH_CONNECT before anything here
class BleRemote(private val context: Context, private val report: (name: String, state: String, recording: Boolean?) -> Unit) {
    /** FOLLOW (Marko, 29.10.2026: "If I press on one camera record, they can all start to record at the same time"). */
    var follow = false
    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val links = LinkedHashMap<String, Link>()

    inner class Link(val device: BluetoothDevice) : BluetoothGattCallback() {
        val name: String = device.name ?: device.address
        var gatt: BluetoothGatt? = null
        var command: BluetoothGattCharacteristic? = null
        var recording = false
        var pressedAt = 0L   // REC is a toggle: a camera pressed in the last 3 s is not pressed again by FOLLOW

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) { report(name, "BT CONNECTED", null); g.discoverServices() }
            else { command = null; report(name, "BT LOST, reconnecting", null); main.postDelayed({ g.connect() }, 2000) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            command = svc?.getCharacteristic(FF01)
            if (command == null) { report(name, "NO REMOTE SERVICE", null); return }
            svc.getCharacteristic(FF02)?.let { n ->
                g.setCharacteristicNotification(n, true)
                n.getDescriptor(CCCD)?.let { g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            }
            if (device.bondState != BluetoothDevice.BOND_BONDED) { report(name, "PAIRING: accept on the phone", null); device.createBond() }
            else report(name, "BT READY", false)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
            if (v.size >= 3 && (v[1].toInt() and 0xFF) == 0xD5) {
                val was = recording
                recording = (v[2].toInt() and 0x20) != 0
                report(name, if (recording) "● RECORDING" else "BT READY", recording)
                // a camera started or stopped by its own button: the others do the same
                if (follow && was != recording && System.currentTimeMillis() - pressedAt > 3000) main.post { followed(this, recording) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) report(name, "REFUSED 0x%02x: turn Bluetooth Rmt Ctrl On".format(status), null)
        }

        fun press(down: Byte, up: Byte) {
            val g = gatt ?: return; val c = command ?: return
            pressedAt = System.currentTimeMillis()
            g.writeCharacteristic(c, byteArrayOf(0x01, down), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            main.postDelayed({ g.writeCharacteristic(c, byteArrayOf(0x01, up), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }, 120)
        }
    }

    /** Sony cameras already bonded with the phone, then any Sony camera advertising (company 0x012D), up to two. */
    fun start() {
        adapter.bondedDevices.filter { (it.name ?: "").startsWith("ILCE") }.forEach(::connect)
        runCatching { adapter.bluetoothLeScanner?.stopScan(scan) }
        adapter.bluetoothLeScanner?.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        main.removeCallbacksAndMessages(SCANNING)
        main.postAtTime({ runCatching { adapter.bluetoothLeScanner?.stopScan(scan) } }, SCANNING, android.os.SystemClock.uptimeMillis() + 120_000)
    }

    private val scan = object : ScanCallback() {
        // any Sony device (company 0x012D) or one that calls itself a camera body
        override fun onScanResult(type: Int, r: ScanResult) {
            val sony = r.scanRecord?.getManufacturerSpecificData(SONY_COMPANY) != null
            val name = r.scanRecord?.deviceName ?: r.device.name ?: ""
            if (sony || name.startsWith("ILCE") || name.startsWith("ZV-")) connect(r.device)
        }
    }

    private fun connect(d: BluetoothDevice) {
        if (links.containsKey(d.address)) return
        val l = Link(d); links[d.address] = l
        report(l.name, "BT FOUND", null)
        l.gatt = d.connectGatt(context, true, l, BluetoothDevice.TRANSPORT_LE)
    }

    val ready get() = links.values.count { it.command != null }
    val anyRecording get() = links.values.any { it.recording }

    private fun followed(leader: Link, rec: Boolean) {
        val others = links.values.filter { it !== leader && it.command != null && it.recording != rec &&
            System.currentTimeMillis() - it.pressedAt > 3000 }
        others.forEach { it.press(0x0F, 0x0E) }
        if (others.isNotEmpty()) report(leader.name, if (rec) "● RECORDING · led ${others.size} more" else "BT READY · stopped ${others.size} more", rec)
    }

    /** Every camera's state again, for a screen that starts listening late (v187: SONY REMOTE opened from the camera). */
    fun replay() = links.values.forEach {
        report(it.name, if (it.command == null) "BT …" else if (it.recording) "● RECORDING" else "BT READY", null)
    }

    /** Cameras reporting that they record (Mantra Manual Camera's SONY status, v187). */
    val recordingCount get() = links.values.count { it.command != null && it.recording }

    /**
     * START SONY / STOP SONY (Mantra Manual Camera v187): REC pressed only on the cameras not already doing it, since
     * REC is a toggle. @return how many were pressed.
     */
    fun recording(on: Boolean): Int {
        val todo = links.values.filter { it.command != null && it.recording != on }
        todo.forEach { it.press(0x0F, 0x0E) }
        return todo.size
    }

    /** One press of the movie REC button on every camera, back to back (a few milliseconds apart). */
    fun record() = links.values.forEach { it.press(0x0F, 0x0E) }

    fun stop() {
        runCatching { adapter.bluetoothLeScanner?.stopScan(scan) }
        links.values.forEach { it.gatt?.close() }; links.clear()
    }

    companion object {
        const val SONY_COMPANY = 0x012D
        private val SCANNING = Any()
        val SERVICE: UUID = UUID.fromString("8000ff00-ff00-ffff-ffff-ffffffffffff")
        val FF01: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val FF02: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
