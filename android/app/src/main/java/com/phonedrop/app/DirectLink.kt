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
import android.util.Log
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
 *
 * TR: Doğrudan mod (docs/PROTOCOL.md): ortak ağ yokken eşleşmiş Mac'i Bluetooth LE ile bulur, yerel bir
 * hotspot açar ve Mac'ten ona katılmasını ister. Dosyalar yine Wi-Fi üzerinden gider.
 */
class DirectLink(ctx: Context, private val prefs: Prefs) {
    companion object {
        private const val TAG = "PhoneDrop"
        private const val SUFFIX = "-9D4B-4F2A-8E61-50484F4E4544"
        val SERVICE: UUID = UUID.fromString("C0DE0001$SUFFIX")
        val INFO: UUID = UUID.fromString("C0DE0002$SUFFIX")
        val JOIN: UUID = UUID.fromString("C0DE0003$SUFFIX")
        val STATUS: UUID = UUID.fromString("C0DE0004$SUFFIX")
        /**
         * The Mac advertises this instead of [SERVICE] while it wants the phone to fetch its outbox.
         *
         * TR: Mac, telefonun giden kutusunu indirmesini istediğinde [SERVICE] yerine bunu yayınlar.
         */
        val WAKE: UUID = UUID.fromString("C0DE0005$SUFFIX")

        /**
         * Runtime permissions direct mode needs on this Android version.
         *
         * TR: Doğrudan modun bu Android sürümünde ihtiyaç duyduğu çalışma zamanı izinleri.
         */
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

    /**
     * Where to reach the Mac: over the phone's own hotspot, or on the network both are already on.
     *
     * TR: Mac'e nereden ulaşılacağı: telefonun kendi hotspot'u üzerinden ya da ikisinin zaten bulunduğu
     * ağdan.
     */
    class Target(val host: String, val port: Int, val hotspot: Boolean)

    /**
     * Failure with a user-facing string resource.
     *
     * TR: Kullanıcıya gösterilecek metin kaynağıyla birlikte hata.
     */
    class DirectException(val reason: Int) : IOException("direct mode failed")

    private val app = ctx.applicationContext
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val active get() = reservation != null

    /**
     * Blocking. Returns where the Mac can be reached. [progress] receives string resources to show while
     * it works; [tryLan] checks an address on the normal network first.
     *
     * TR: Bloklayan çağrı. Mac'e nereden ulaşılacağını döndürür. [progress], çalışırken gösterilecek metin
     * kaynaklarını alır; [tryLan] önce normal ağdaki bir adresi dener.
     */
    @SuppressLint("MissingPermission")
    fun connect(progress: (Int) -> Unit, tryLan: (String, Int) -> Boolean = { _, _ -> false }): Target {
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
                Log.i(TAG, "direct: ${device.address} kid=$kid (ours=${prefs.kid})")
                if (kid != prefs.kid) continue

                // The Mac says where it is on its network. If we can reach it there, the stored address
                // was just stale and no hotspot is needed.
                runCatching { JSONObject(crypto.open(gatt.read(STATUS), "pd2 ble status").decodeToString()) }
                    .getOrNull()?.let { st ->
                        val ip = st.optString("lan")
                        val port = st.optInt("lp")
                        if (ip.isNotEmpty() && port > 0) {
                            val ok = tryLan(ip, port)
                            Log.i(TAG, "direct: Mac is at $ip:$port on its network, reachable=$ok")
                            if (ok) return Target(ip, port, hotspot = false)
                        }
                    }

                progress(R.string.direct_starting)
                val (ssid, pass) = reservation?.let { credentials(it) } ?: startHotspot()
                val join = JSONObject().put("s", ssid).put("p", pass).put("t", System.currentTimeMillis() / 1000)
                gatt.write(JOIN, crypto.seal(join.toString().toByteArray(), "pd2 ble join"))

                progress(R.string.direct_joining)
                val deadline = System.currentTimeMillis() + 45_000
                var idleReads = 0
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(1000)
                    val st = JSONObject(crypto.open(gatt.read(STATUS), "pd2 ble status").decodeToString())
                    Log.i(TAG, "direct: status ${st.optString("st")} ${st.optString("e")}")
                    when (st.optString("st")) {
                        "ready" -> return Target(st.getString("ip"), st.getInt("p"), hotspot = true)
                        // The Mac went back to idle without reaching "ready": the join was abandoned
                        "idle" -> if (++idleReads >= 3) { close(); throw DirectException(R.string.direct_failed) }
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
                Log.w(TAG, "direct: ${device.address} failed: $e")
            } finally {
                gatt.close()
            }
        }
        throw DirectException(R.string.status_searching)
    }

    /**
     * Diagnostic: scans, connects and reads INFO from every PhoneDrop Mac nearby, without touching
     * Wi-Fi. Returns a human-readable report (also written to logcat).
     *
     * TR: Teşhis: yakındaki her PhoneDrop Mac'ini tarar, bağlanır ve INFO'yu okur; Wi-Fi'a dokunmaz.
     * Okunabilir bir rapor döndürür (logcat'e de yazılır).
     */
    @SuppressLint("MissingPermission")
    fun probe(): String {
        val out = StringBuilder()
        fun say(s: String) { Log.i(TAG, "probe: $s"); out.appendLine(s) }
        if (!hasPermissions(app)) return "no permission".also { say(it) }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return "no adapter".also { say(it) }
        if (!adapter.isEnabled) return "bluetooth off".also { say(it) }
        val found = scan(adapter.bluetoothLeScanner, 10_000)
        say("found ${found.size} advertiser(s)")
        // Unfiltered scan: shows whether the Mac advertises at all and what it puts in the packet
        val all = LinkedHashMap<String, ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { synchronized(all) { all[r.device.address] = r } }
        }
        adapter.bluetoothLeScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        Thread.sleep(6000)
        runCatching { adapter.bluetoothLeScanner.stopScan(cb) }
        synchronized(all) {
            say("unfiltered: ${all.size} advertiser(s)")
            for (r in all.values) {
                val rec = r.scanRecord ?: continue
                val apple = rec.getManufacturerSpecificData(0x004C)
                if (rec.deviceName != null || rec.serviceUuids != null || apple != null) {
                    say("  ${r.device.address} rssi=${r.rssi} name=${rec.deviceName} uuids=${rec.serviceUuids} apple=${apple?.joinToString("") { "%02x".format(it) }}")
                }
            }
        }
        for (d in found) {
            val g = Gatt(d)
            try {
                g.open()
                say("${d.address}: kid=${g.read(INFO).decodeToString()} ours=${prefs.kid}")
            } catch (e: Exception) {
                say("${d.address}: $e")
            } finally {
                g.close()
            }
        }
        return out.toString()
    }

    /**
     * Stops the hotspot. The Mac notices and returns to its usual Wi-Fi.
     *
     * TR: Hotspot'u kapatır. Mac bunu fark edip her zamanki Wi-Fi'ına döner.
     */
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
                synchronized(found) {
                    if (found.put(result.device.address, result.device) == null) {
                        Log.i(TAG, "direct: saw ${result.device.address} rssi=${result.rssi}")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) { Log.w(TAG, "direct: scan failed $errorCode") }
        }
        // The Mac advertises either UUID; its GATT service is the same in both cases
        val filters = listOf(SERVICE, WAKE).map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
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

    /**
     * Minimal blocking wrapper around BluetoothGatt's callbacks.
     *
     * TR: BluetoothGatt geri çağrıları için en küçük bloklayan sarmalayıcı.
     */
    @SuppressLint("MissingPermission")
    private inner class Gatt(private val device: BluetoothDevice) : BluetoothGattCallback() {
        private val events = LinkedBlockingQueue<Any>()
        private var gatt: BluetoothGatt? = null

        private inner class Read(val uuid: UUID, val value: ByteArray?, val status: Int)
        private inner class Written(val status: Int)

        /**
         * Connects with the usual Android workarounds: retry (the first attempt often fails with
         * status 133, especially on Xiaomi and Samsung), deliver callbacks on the main looper, and
         * pause briefly before MTU negotiation and service discovery.
         *
         * TR: Android'in bilinen sorunlarına karşı önlemlerle bağlanır: yeniden deneme (ilk deneme
         * özellikle Xiaomi ve Samsung'da sık sık 133 koduyla başarısız olur), geri çağrıların ana döngüde
         * teslimi, MTU pazarlığı ve servis keşfinden önce kısa beklemeler.
         */
        fun open() {
            var lastError: Exception? = null
            for (attempt in 1..3) {
                events.clear()
                try {
                    gatt = device.connectGatt(app, false, this, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper()))
                    await(12_000) { it == "connected" }
                    Log.i(TAG, "direct: ${device.address} connected (attempt $attempt)")
                    Thread.sleep(600)
                    gatt!!.requestMtu(247)
                    await(3000, optional = true) { it == "mtu" }
                    Thread.sleep(200)
                    if (!gatt!!.discoverServices()) throw IOException("discoverServices refused")
                    await(15_000) { it == "services" }
                    if (gatt!!.getService(SERVICE) == null) throw IOException("service missing")
                    return
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "direct: ${device.address} attempt $attempt: $e (last status $lastStatus)")
                    close()
                    Thread.sleep(800L * attempt)
                }
            }
            throw lastError ?: IOException("connect failed")
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

        @Volatile var lastStatus = 0

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            lastStatus = status
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
