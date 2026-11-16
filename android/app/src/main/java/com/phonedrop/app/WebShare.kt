package com.phonedrop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.phonedrop.app.core.WebHost
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Browser mode on the phone: what the screen needs to know about the running web host.
 *
 * TR: Telefonda tarayıcı modu: ekranın, çalışan web sunucusu hakkında bilmesi gerekenler.
 */
object WebShare {
    @Volatile var host: WebHost? = null
        internal set

    val running get() = host != null
    /** A browser has proved it holds the session key. TR: Bir tarayıcı oturum anahtarına sahip olduğunu kanıtladı. */
    val linked get() = host?.state == WebHost.State.LINKED

    fun start(ctx: Context) {
        ctx.startForegroundService(Intent(ctx, WebService::class.java).setAction(WebService.START))
    }

    fun stop(ctx: Context) {
        if (running) ctx.startService(Intent(ctx, WebService::class.java).setAction(WebService.STOP))
    }

    /**
     * Offers files to the browser; read permission travels to the service through ClipData.
     *
     * TR: Dosyaları tarayıcıya sunar; okuma izni servise ClipData ile aktarılır.
     */
    fun offer(ctx: Context, uris: List<Uri>) {
        if (uris.isEmpty() || !running) return
        val clip = ClipData.newRawUri("files", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val i = Intent(ctx, WebService::class.java).setAction(WebService.OFFER)
            .putParcelableArrayListExtra("uris", ArrayList(uris))
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startService(i)
        } catch (e: SecurityException) {
            // Some sources don't let the grant be passed on; the files can still be read from this process
            ctx.startService(Intent(ctx, WebService::class.java).setAction(WebService.OFFER)
                .putParcelableArrayListExtra("uris", ArrayList(uris)))
        }
    }

    /**
     * What to type into the computer's browser: this phone's addresses on Wi-Fi or its own hotspot.
     *
     * TR: Bilgisayarın tarayıcısına yazılacak adres: telefonun Wi-Fi'daki ya da kendi hotspot'undaki adresleri.
     */
    fun addresses(): List<String> {
        val port = host?.port ?: return emptyList()
        val found = ArrayList<Pair<Int, String>>()
        for (nif in runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            // Mobile data and VPN interfaces can't be reached from the computer
            if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")) continue
            val rank = if (name.startsWith("wlan")) 0 else 1
            for (a in nif.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) found.add(rank to "${a.hostAddress}:$port")
        }
        return found.sortedBy { it.first }.map { it.second }
    }
}

/**
 * Keeps the web host alive while the app is in the background, and connects it to the phone's storage.
 *
 * TR: Uygulama arka plandayken web sunucusunu ayakta tutar ve onu telefonun depolamasına bağlar.
 */
class WebService : Service(), WebHost.Store {

    companion object {
        const val START = "start"
        const val STOP = "stop"
        const val OFFER = "offer"
        private const val NOTIF_ID = 4
        private const val IDLE_MS = 15 * 60_000L
    }

    private class Offered(val uri: Uri, val item: Transfers.Item)

    private val offered = LinkedHashMap<String, Offered>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var watcher: Thread? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Look.wrap(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("transfers", getString(R.string.notif_channel_transfers), NotificationManager.IMPORTANCE_LOW))
        when (intent?.action) {
            STOP -> { shutdown(); return START_NOT_STICKY }
            OFFER -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>("uris").orEmpty()
                thread(name = "web-offer") { uris.forEach { offer(it) } }
            }
        }
        if (WebShare.host == null) open()
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun open() {
        val name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val host = WebHost(assets.open("index.html").use { it.readBytes() }, { name }, this)
        host.start()
        WebShare.host = host
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneDrop:web")
            .apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneDrop:web")
            .apply { setReferenceCounted(false); acquire() }
        // Close by itself when nobody has used it for a while
        watcher = thread(name = "web-idle", isDaemon = true) {
            try {
                while (WebShare.host === host) {
                    Thread.sleep(30_000)
                    val busy = Transfers.items.any { it.web && it.state == Transfers.State.ACTIVE }
                    if (!busy && System.currentTimeMillis() - host.lastActive > IDLE_MS) { shutdown(); break }
                }
            } catch (e: InterruptedException) {
            }
        }
    }

    private fun shutdown() {
        val host = WebShare.host
        WebShare.host = null
        watcher?.interrupt()
        thread { runCatching { host?.stop() } }
        synchronized(offered) {
            offered.values.forEach { if (it.item.state != Transfers.State.DONE) it.item.state = Transfers.State.CANCELLED }
            offered.clear()
        }
        runCatching { wakeLock?.release() }; wakeLock = null
        runCatching { wifiLock?.release() }; wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Android 15+: daily limit for dataSync foreground services
    override fun onTimeout(startId: Int, fgsType: Int) = shutdown()

    override fun onDestroy() {
        if (WebShare.host != null) shutdown()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, WebService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "transfers")
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle(getString(R.string.web_notif_title))
            .setContentText(WebShare.addresses().firstOrNull() ?: getString(R.string.web_no_network))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.web_stop), stop).build())
            .build()
    }

    private fun offer(uri: Uri) {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }?.let { name = it }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        if (size < 0) size = runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1
        val item = Transfers.Item(Transfers.Direction.UP, name, maxOf(size, 0), source = uri, web = true)
        if (size < 0) { item.state = Transfers.State.FAILED; item.error = getString(R.string.err_read) }
        else synchronized(offered) { offered[UUID.randomUUID().toString()] = Offered(uri, item) }
        Transfers.items.add(item)
    }

    // ---------------- WebHost.Store ----------------

    override fun offers(): List<WebHost.Offer> = synchronized(offered) {
        offered.filter { it.value.item.state != Transfers.State.DONE }.map { WebHost.Offer(it.key, it.value.item.name, it.value.item.size) }
    }

    override fun open(id: String): InputStream? {
        val o = synchronized(offered) { offered[id] } ?: return null
        o.item.state = Transfers.State.ACTIVE
        o.item.lastT = System.nanoTime()
        return runCatching { contentResolver.openInputStream(o.uri) }.getOrNull()
    }

    override fun sending(id: String, bytes: Long, done: Boolean) {
        val item = synchronized(offered) { offered[id] }?.item ?: return
        item.sent.set(bytes)
        measure(item)
        if (done) { item.state = Transfers.State.DONE; item.speed = 0.0 }
    }

    override fun create(name: String, size: Long, mtime: Long): WebHost.Sink {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneDrop")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("can't create the file")
        val out: OutputStream = contentResolver.openOutputStream(uri) ?: throw IOException("can't open the file")
        val item = Transfers.Item(Transfers.Direction.DOWN, name, size, web = true)
        item.state = Transfers.State.ACTIVE
        item.lastT = System.nanoTime()
        Transfers.items.add(item)
        return object : WebHost.Sink {
            override fun write(data: ByteArray) {
                if (item.cancelled) throw IOException("stopped")
                out.write(data)
                item.sent.addAndGet(data.size.toLong())
                measure(item)
            }

            override fun finish(): String {
                out.close()
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                item.state = Transfers.State.DONE
                item.speed = 0.0
                return name
            }

            override fun abort() {
                runCatching { out.close() }
                runCatching { contentResolver.delete(uri, null, null) }
                if (item.state == Transfers.State.ACTIVE) item.state = Transfers.State.CANCELLED
            }
        }
    }

    private fun measure(item: Transfers.Item) {
        val now = System.nanoTime()
        val dt = (now - item.lastT) / 1e9
        if (dt < 0.5) return
        val bytes = item.sent.get()
        val inst = maxOf(0.0, (bytes - item.lastBytes) / dt)
        item.speed = if (item.speed == 0.0) inst else item.speed * 0.6 + inst * 0.4
        item.lastBytes = bytes
        item.lastT = now
    }
}
