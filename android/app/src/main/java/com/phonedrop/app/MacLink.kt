package com.phonedrop.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.phonedrop.app.core.Crypto
import com.phonedrop.app.core.MacClient
import com.phonedrop.app.core.PairInfo
import com.phonedrop.app.core.UnauthorizedException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The paired Mac. The key never leaves app-private storage. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("phonedrop", Context.MODE_PRIVATE)

    var host: String?
        get() = sp.getString("host", null)
        set(v) = sp.edit().putString("host", v).apply()
    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) = sp.edit().putInt("port", v).apply()
    var macName: String?
        get() = sp.getString("macName", null)
        set(v) = sp.edit().putString("macName", v).apply()
    /** Subfolder of the Mac's download folder that uploads go to ("" = the folder itself). */
    var folder: String
        get() = sp.getString("folder", "") ?: ""
        set(v) = sp.edit().putString("folder", v).apply()
    val key: ByteArray?
        get() = sp.getString("key", null)?.let { Crypto.unb64url(it) }

    val paired get() = key != null
    val kid get() = key?.let { Crypto.kid(it) }

    fun pair(p: PairInfo) {
        sp.edit().putString("key", Crypto.b64url(p.key)).putString("host", p.host)
            .putInt("port", p.port).putString("macName", p.name).apply()
    }

    fun unpair() = sp.edit().clear().apply()
}

/**
 * Finds and talks to the paired Mac. Tries the last known address first, then looks for
 * the Mac on the network with Bonjour (its IP may have changed).
 */
class MacLink(ctx: Context) {
    private val app = ctx.applicationContext
    val prefs = Prefs(app)
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val nsd = app.getSystemService(NsdManager::class.java)

    @Volatile private var verified: MacClient? = null
    private val direct = DirectLink(app, prefs)
    private var directHost: Pair<String, Int>? = null
    private var lastDirectAttempt = 0L
    /** Why the last direct attempt failed, so the user keeps seeing it between attempts. */
    private var lastDirectError: Int? = null

    /** If Wi-Fi has no internet, Android may route traffic over mobile data; pin it to Wi-Fi. */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun opener(): (URL) -> HttpURLConnection {
        val net = wifiNetwork()
        return { url -> (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection }
    }

    private fun clientFor(host: String, port: Int): MacClient? {
        val key = prefs.key ?: return null
        return MacClient("http://$host:$port", key, opener())
    }

    private fun deviceName() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    /**
     * Returns a client for a Mac that answered; throws IOException if none can be reached.
     * With [allowDirect], falls back to direct mode (Bluetooth + hotspot) when the phone isn't on
     * the Mac's network; [progress] then receives status string resources.
     */
    @Synchronized
    fun client(allowDirect: Boolean = false, progress: (Int) -> Unit = {}): MacClient {
        verified?.let { return it }
        val key = prefs.key ?: throw IOException("not paired")
        // Already connected directly: the hotspot route doesn't go through a Wi-Fi client network
        directHost?.let { (h, p) -> if (handshake(MacClient("http://$h:$p", key))) return verified!! }
        prefs.host?.let { h -> clientFor(h, prefs.port)?.let { c -> if (handshake(c)) return c } }
        discover(8000)?.let { (host, port) ->
            val c = clientFor(host, port) ?: throw IOException("not paired")
            if (handshake(c)) {
                prefs.host = host
                prefs.port = port
                return c
            }
        }
        // Two routers can hand out the same subnet, so "same subnet" doesn't prove "same network".
        // Let Bluetooth decide: direct mode only goes ahead if the paired Mac is actually nearby.
        if (allowDirect && DirectLink.hasPermissions(app)) {
            if (System.currentTimeMillis() - lastDirectAttempt > 30_000) {
                lastDirectAttempt = System.currentTimeMillis()
                val t = try {
                    direct.connect(progress) { h, p -> clientFor(h, p)?.let { handshake(it) } ?: false }
                } catch (e: DirectLink.DirectException) {
                    lastDirectError = e.reason
                    throw e
                }
                lastDirectError = null
                if (!t.hotspot) {
                    // Same network after all: remember the Mac's current address
                    prefs.host = t.host
                    prefs.port = t.port
                    return verified!!
                }
                if (handshake(MacClient("http://${t.host}:${t.port}", key))) {
                    directHost = t.host to t.port
                    return verified!!
                }
                endDirect()
            }
            lastDirectError?.let { throw DirectLink.DirectException(it) }
        }
        throw IOException("Mac not found")
    }

    /** Leaves direct mode: tells the Mac to go back to its Wi-Fi and stops the hotspot. */
    @Synchronized
    fun endDirect() {
        if (!direct.active && directHost == null) return
        directHost?.let { runCatching { verified?.post("/api/direct/end", timeoutMs = 3000) } }
        direct.close()
        directHost = null
        verified = null
    }

    private fun handshake(c: MacClient): Boolean = try {
        val r = c.hello(deviceName())
        r.optString("name").takeIf { it.isNotEmpty() }?.let { prefs.macName = it }
        verified = c
        true
    } catch (e: UnauthorizedException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Explains why the Mac can't be reached, as a string resource id: no Wi-Fi, a different
     * network than the one the Mac was paired on, or (same subnet) the app isn't running.
     */
    fun diagnose(): Int {
        val net = wifiNetwork() ?: return R.string.status_no_wifi
        val host = prefs.host ?: return R.string.status_searching
        val link = cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            ?: return R.string.status_searching
        val mac = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address
            ?: return R.string.status_searching
        return if (sameSubnet(link.address.address, mac.address, link.prefixLength)) R.string.status_searching
        else R.string.status_other_network
    }

    private fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        for (i in 0 until 4) {
            val bits = (prefix - i * 8).coerceIn(0, 8)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
        }
        return true
    }

    fun invalidate(c: MacClient) {
        if (verified === c) verified = null
    }

    /** Looks for `_phonedrop._tcp` with our `kid` in the TXT record. */
    @Suppress("DEPRECATION")
    private fun discover(timeoutMs: Long): Pair<String, Int>? {
        val kid = prefs.kid ?: return null
        val latch = CountDownLatch(1)
        var result: Pair<String, Int>? = null
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            val next = synchronized(queue) {
                if (resolving || queue.isEmpty()) return
                resolving = true
                queue.removeFirst()
            }
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, err: Int) {
                    synchronized(queue) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    synchronized(queue) { resolving = false }
                    val host = info.host
                    if (host is Inet4Address && info.attributes["kid"]?.decodeToString() == kid) {
                        result = host.hostAddress!! to info.port
                        latch.countDown()
                    } else resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) = latch.countDown()
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(queue) { queue.addLast(info) }
                resolveNext()
            }
        }
        try {
            nsd.discoverServices("_phonedrop._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return result
    }
}
