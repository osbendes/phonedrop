package com.phonedrop.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.phonedrop.app.core.Crypto
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Direct mode (docs/PROTOCOL.md): when there's no shared network, find the paired Mac over
 * Bluetooth LE, start a local-only hotspot and ask the Mac to join it. Files still go over Wi-Fi.
 */
class DirectLink(ctx: Context, private val prefs: Prefs) {
    companion object {
        private const val SUFFIX = "-9D4B-4F2A-8E61-50484F4E4544"
        val SERVICE: UUID = UUID.fromString("C0DE0001$SUFFIX")
        val INFO: UUID = UUID.fromString("C0DE0002$SUFFIX")
        val JOIN: UUID = UUID.fromString("C0DE0003$SUFFIX")
        val STATUS: UUID = UUID.fromString("C0DE0004$SUFFIX")

        /** Runtime permissions direct mode needs on this Android version. */
        fun permissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            else {
                // Android 12 ignores a fine-location request unless coarse is requested too
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()

        fun hasPermissions(ctx: Context) = permissions().all {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Failure with a user-facing string resource. */
    class DirectException(val reason: Int) : IOException("direct mode failed")

    private val app = ctx.applicationContext
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val active get() = reservation != null

    /**
     * Blocking. Returns the Mac's address on the phone's hotspot. [progress] receives string
     * resources to show while it works.
     */
    @SuppressLint("MissingPermission")
    fun connect(progress: (Int) -> Unit): Pair<String, Int> {
        val key = prefs.key ?: throw DirectException(R.string.status_searching)
        if (!hasPermissions(app)) throw DirectException(R.string.direct_no_permission)
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw DirectException(R.string.direct_unsupported)
        if (!adapter.isEnabled) throw DirectException(R.string.direct_bt_off)
        // Before Android 13 the hotspot (and before 12 the BLE scan) need location turned on
        if (Build.VERSION.SDK_INT < 33 &&
            app.getSystemService(LocationManager::class.java)?.isLocationEnabled == false) {
            throw DirectException(R.string.direct_location_off)
        }

        progress(R.string.direct_searching)
        val candidates = scan(adapter.bluetoothLeScanner ?: throw DirectException(R.string.direct_unsupported), 10_000)
        val crypto = Crypto(key)
        for (device in candidates) {
            val gatt = Gatt(device)
            try {
                gatt.open()
                val kid = gatt.read(INFO).decodeToString()
                if (kid != prefs.kid) continue

                progress(R.string.direct_starting)
                val (ssid, pass) = reservation?.let { credentials(it) } ?: startHotspot()
                val join = JSONObject().put("s", ssid).put("p", pass).put("t", System.currentTimeMillis() / 1000)
                gatt.write(JOIN, crypto.seal(join.toString().toByteArray(), "pd2 ble join"))

                progress(R.string.direct_joining)
                val deadline = System.currentTimeMillis() + 45_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(1000)
                    val st = JSONObject(crypto.open(gatt.read(STATUS), "pd2 ble status").decodeToString())
                    when (st.optString("st")) {
                        "ready" -> return st.getString("ip") to st.getInt("p")
                        "error" -> {
                            close()
                            throw DirectException(
                                if (st.optString("e") == "on-wifi") R.string.direct_mac_on_wifi else R.string.direct_failed)
                        }
                    }
                }
                close()
                throw DirectException(R.string.direct_failed)
            } catch (e: DirectException) {
                throw e
            } catch (e: Exception) {
                // Try the next advertiser
            } finally {
                gatt.close()
            }
        }
        throw DirectException(R.string.status_searching)
    }

    /** Stops the hotspot. The Mac notices and returns to its usual Wi-Fi. */
    fun close() {
        runCatching { reservation?.close() }
        reservation = null
    }

    // ---------------- hotspot ----------------

    @SuppressLint("MissingPermission")
    private fun startHotspot(): Pair<String, String> {
        val wm = app.getSystemService(WifiManager::class.java)
        val latch = CountDownLatch(1)
        var started: WifiManager.LocalOnlyHotspotReservation? = null
        wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) { started = r; latch.countDown() }
            override fun onFailed(reason: Int) = latch.countDown()
            override fun onStopped() { reservation = null }
        }, Handler(Looper.getMainLooper()))
        latch.await(15, TimeUnit.SECONDS)
        val r = started ?: throw DirectException(R.string.direct_hotspot_failed)
        reservation = r
        return credentials(r)
    }

    @Suppress("DEPRECATION")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        val (ssid, pass) = if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val name = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString() else c.ssid
            name to c.passphrase
        } else {
            r.wifiConfiguration?.let { it.SSID to it.preSharedKey } ?: (null to null)
        }
        if (ssid.isNullOrEmpty() || pass.isNullOrEmpty()) throw DirectException(R.string.direct_hotspot_failed)
        return ssid.removeSurrounding("\"") to pass.removeSurrounding("\"")
    }

    // ---------------- Bluetooth LE ----------------

    @SuppressLint("MissingPermission")
    private fun scan(scanner: android.bluetooth.le.BluetoothLeScanner, timeoutMs: Long): List<BluetoothDevice> {
        val found = LinkedHashMap<String, BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                synchronized(found) { found[result.device.address] = result.device }
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        // Stop early once something shows up, leaving a moment for other Macs nearby
        val end = System.currentTimeMillis() + timeoutMs
        var firstSeen = 0L
        while (System.currentTimeMillis() < end) {
            Thread.sleep(250)
            val n = synchronized(found) { found.size }
            if (n > 0 && firstSeen == 0L) firstSeen = System.currentTimeMillis()
            if (firstSeen > 0 && System.currentTimeMillis() - firstSeen > 1500) break
        }
        runCatching { scanner.stopScan(cb) }
        return synchronized(found) { found.values.toList() }
    }

    /** Minimal blocking wrapper around BluetoothGatt's callbacks. */
    @SuppressLint("MissingPermission")
    private inner class Gatt(private val device: BluetoothDevice) : BluetoothGattCallback() {
        private val events = LinkedBlockingQueue<Any>()
        private var gatt: BluetoothGatt? = null

        private inner class Read(val uuid: UUID, val value: ByteArray?, val status: Int)
        private inner class Written(val status: Int)

        fun open() {
            gatt = device.connectGatt(app, false, this, BluetoothDevice.TRANSPORT_LE)
            await { it == "connected" }
            gatt!!.requestMtu(247)
            await(3000, optional = true) { it == "mtu" }
            gatt!!.discoverServices()
            await { it == "services" }
            if (gatt!!.getService(SERVICE) == null) throw IOException("no service")
        }

        private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
            gatt!!.getService(SERVICE)?.getCharacteristic(uuid) ?: throw IOException("no characteristic")

        fun read(uuid: UUID): ByteArray {
            if (!gatt!!.readCharacteristic(characteristic(uuid))) throw IOException("read failed")
            val r = await { it is Read && it.uuid == uuid } as Read
            if (r.status != BluetoothGatt.GATT_SUCCESS || r.value == null) throw IOException("read failed")
            return r.value
        }

        @Suppress("DEPRECATION")
        fun write(uuid: UUID, value: ByteArray) {
            val c = characteristic(uuid)
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                gatt!!.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = value
                gatt!!.writeCharacteristic(c)
            }
            if (!ok) throw IOException("write failed")
            val w = await { it is Written } as Written
            if (w.status != BluetoothGatt.GATT_SUCCESS) throw IOException("write failed")
        }

        fun close() {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }

        private fun await(timeoutMs: Long = 10_000, optional: Boolean = false, match: (Any) -> Boolean): Any? {
            val end = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = end - System.currentTimeMillis()
                val e = if (left > 0) events.poll(left, TimeUnit.MILLISECONDS) else null
                if (e == null) { if (optional) return null else throw IOException("timeout") }
                if (e == "disconnected") throw IOException("disconnected")
                if (match(e)) return e
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            events.add(if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { events.add("mtu") }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.add("services") }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            events.add(Read(c.uuid, value, status))
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) events.add(Read(c.uuid, c.value, status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            events.add(Written(status))
        }
    }
}
