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
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.phonedrop.app.core.HttpStatusException
import com.phonedrop.app.core.MacClient
import com.phonedrop.app.core.UnauthorizedException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Transfer list shared by the service and the UI.
 *
 * TR: Servis ile arayüzün paylaştığı aktarım listesi.
 */
object Transfers {
    enum class State { WAITING, ACTIVE, DONE, FAILED, CANCELLED }
    enum class Direction { UP, DOWN }

    class Item(
        val direction: Direction,
        val name: String,
        val size: Long,
        val mtime: Long = 0,
        val source: Uri? = null,       // UP: file to read
        val tempFile: File? = null,    // UP: copy made for non-seekable sources
        val fileId: String? = null,    // DOWN: id in the Mac's outbox
        val folder: String = "",       // UP: subfolder on the Mac
    ) {
        val key = nextKey.incrementAndGet()
        @Volatile var state = State.WAITING
        val sent = AtomicLong()
        @Volatile var speed = 0.0
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        @Volatile var resumed = false
        @Volatile var notified = false
        @Volatile var output: Uri? = null  // DOWN: MediaStore entry
        var lastBytes = 0L
        var lastT = 0L
    }

    private val nextKey = AtomicInteger()
    val items = CopyOnWriteArrayList<Item>()

    /**
     * Connection problem shown to the user while something is pending (null = fine).
     *
     * TR: Bekleyen bir iş varken kullanıcıya gösterilen bağlantı sorunu (null = sorun yok).
     */
    @Volatile var linkStatus: String? = null

    fun active() = items.filter { it.state == State.WAITING || it.state == State.ACTIVE }
    fun clearFinished() = items.removeAll { it.state != State.WAITING && it.state != State.ACTIVE }
}

private class FatalException(msg: String) : Exception(msg)
private class CancelledException : Exception()

/**
 * Foreground service that runs the queue: uploads to the Mac in parallel encrypted chunks and downloads
 * from it, with retries, progress notifications and wake locks so it keeps going with the screen off.
 *
 * TR: Kuyruğu çalıştıran ön plan servisi: Mac'e paralel şifreli parçalarla yükler ve Mac'ten indirir;
 * yeniden denemeler, ilerleme bildirimleri ve ekran kapalıyken de sürmesi için uyanık tutma kilitleri
 * içerir.
 */
class TransferService : Service() {

    companion object {
        private const val ACTION_UPLOAD = "upload"
        private const val ACTION_DOWNLOAD = "download"
        private const val ACTION_STOP = "stop"
        private const val ACTION_FETCH = "fetch"
        private const val CHANNEL = "transfers"
        private const val CHANNEL_DONE = "done"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE_ID = 2
        private const val PARALLEL = 4
        private const val TAG = "PhoneDrop"

        /**
         * Queues files for upload; read permission is handed to the service through ClipData.
         *
         * TR: Dosyaları yükleme kuyruğuna ekler; okuma izni servise ClipData ile aktarılır.
         */
        fun upload(ctx: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val clip = ClipData.newRawUri("files", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            val i = Intent(ctx, TransferService::class.java)
                .setAction(ACTION_UPLOAD)
                .putParcelableArrayListExtra("uris", ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = clip
            try {
                ctx.startForegroundService(i)
            } catch (e: SecurityException) {
                // The sharing app gave us a read grant we can't pass on; try without passing it.
                // If we can't read the file either, enqueue() reports "couldn't read the file".
                i.clipData = null
                i.flags = i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
                ctx.startForegroundService(i)
            }
        }

        fun download(ctx: Context, fileId: String, name: String, size: Long) {
            ctx.startForegroundService(Intent(ctx, TransferService::class.java).setAction(ACTION_DOWNLOAD)
                .putExtra("id", fileId).putExtra("name", name).putExtra("size", size))
        }

        /**
         * Downloads everything the Mac currently offers (used when the Mac presses "Send").
         *
         * TR: Mac'in o an sunduğu her şeyi indirir (Mac'te "Gönder"e basıldığında kullanılır).
         */
        fun fetch(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TransferService::class.java).setAction(ACTION_FETCH))
        }

        fun stopAll(ctx: Context) {
            ctx.startService(Intent(ctx, TransferService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var link: MacLink
    private lateinit var nm: NotificationManager
    @Volatile private var worker: Thread? = null
    @Volatile private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        link = MacLink(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_transfers), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires a foreground notification within 5 seconds
        goForeground(buildProgressNotification())
        when (intent?.action) {
            ACTION_STOP -> {
                Transfers.active().forEach { it.cancelled = true; it.state = Transfers.State.CANCELLED }
                if (worker == null) shutdown()
            }
            ACTION_UPLOAD -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>("uris").orEmpty()
                thread(name = "enqueue") {
                    uris.forEach { enqueue(it) }
                    startWorker()
                }
            }
            ACTION_DOWNLOAD -> {
                Transfers.items.add(Transfers.Item(Transfers.Direction.DOWN, intent.getStringExtra("name") ?: "file",
                    intent.getLongExtra("size", 0), fileId = intent.getStringExtra("id")))
                startWorker()
            }
            ACTION_FETCH -> thread(name = "fetch") {
                val queued = runCatching {
                    val files = link.client(allowDirect = true).outbox().getJSONArray("files")
                    var n = 0
                    for (k in 0 until files.length()) {
                        val f = files.getJSONObject(k)
                        val id = f.getString("id")
                        // Skip what's already downloading or was just downloaded
                        if (Transfers.items.any { it.fileId == id && it.state != Transfers.State.FAILED &&
                                it.state != Transfers.State.CANCELLED }) continue
                        Transfers.items.add(Transfers.Item(Transfers.Direction.DOWN, f.getString("name"),
                            f.getLong("size"), fileId = id))
                        n++
                    }
                    n
                }.getOrDefault(0)
                Log.i(TAG, "wake: queued $queued download(s)")
                if (queued > 0) startWorker() else if (worker == null) shutdown()
            }
            else -> if (worker == null) shutdown()
        }
        return START_NOT_STICKY
    }

    // Android 15+: daily limit for dataSync foreground services
    override fun onTimeout(startId: Int, fgsType: Int) {
        Transfers.active().forEach { it.cancelled = true; it.state = Transfers.State.FAILED; it.error = getString(R.string.err_time_limit) }
        shutdown()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    // ---------------- queue ----------------

    private fun enqueue(uri: Uri) {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        var mtime = 0L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun col(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }
                    col(OpenableColumns.DISPLAY_NAME)?.let { c.getString(it) }?.let { name = it }
                    col(OpenableColumns.SIZE)?.let { size = c.getLong(it) }
                    mtime = col(MediaStore.MediaColumns.DATE_TAKEN)?.let { c.getLong(it) }
                        ?: col("last_modified")?.let { c.getLong(it) }
                        ?: col(MediaStore.MediaColumns.DATE_MODIFIED)?.let { c.getLong(it) * 1000 }
                        ?: 0L
                }
            }
        }
        // Some apps hand out streams we can't seek in; copy those to the cache first
        var src = uri
        var temp: File? = null
        if (!seekable(uri)) {
            try {
                val f = File(cacheDir, "tx_${System.nanoTime()}")
                temp = f
                contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it, 1 shl 20) } }
                src = Uri.fromFile(f)
                size = f.length()
            } catch (e: Exception) {
                temp?.delete()
                Transfers.items.add(Transfers.Item(Transfers.Direction.UP, name, 0).apply {
                    state = Transfers.State.FAILED; error = getString(R.string.err_read)
                })
                return
            }
        } else if (size < 0) {
            size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0
        }
        Transfers.items.add(Transfers.Item(Transfers.Direction.UP, name, size, mtime, src, temp, folder = link.prefs.folder))
    }

    private fun seekable(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize >= 0 } ?: false
    } catch (e: Exception) {
        false
    }

    @Synchronized
    private fun startWorker() {
        if (worker != null) return
        acquireLocks()
        val ticker = thread(name = "ticker", isDaemon = true) { tickLoop() }
        worker = thread(name = "transfers") {
            try {
                while (true) {
                    val item = Transfers.items.firstOrNull { it.state == Transfers.State.WAITING } ?: break
                    run(item)
                }
            } finally {
                ticker.interrupt()
                synchronized(this) { worker = null }
                // Something may have been queued meanwhile
                if (Transfers.items.any { it.state == Transfers.State.WAITING }) startWorker() else finishAll()
            }
        }
    }

    private fun run(item: Transfers.Item) {
        item.state = Transfers.State.ACTIVE
        try {
            if (item.direction == Transfers.Direction.UP) upload(item) else download(item)
            item.state = Transfers.State.DONE
        } catch (e: FatalException) {
            item.state = Transfers.State.FAILED
            item.error = e.message
        } catch (e: CancelledException) {
            if (item.state != Transfers.State.FAILED) item.state = Transfers.State.CANCELLED
            if (item.direction == Transfers.Direction.DOWN) item.output?.let { runCatching { contentResolver.delete(it, null, null) } }
        } finally {
            item.tempFile?.delete()
        }
    }

    // ---------------- upload ----------------

    private fun upload(item: Transfers.Item) {
        val init = retrying(item) { it.init(item.name, item.size, item.mtime, MacClient.CHUNK, item.folder) }
        val id = init.getString("id")
        val chunk = init.getInt("chunk")
        val n = ((item.size + chunk - 1) / chunk).toInt()
        val len = { i: Int -> minOf(chunk.toLong(), item.size - i.toLong() * chunk).toInt() }
        val doneArr = init.getJSONArray("done")
        val done = (0 until doneArr.length()).map { doneArr.getInt(it) }.toSet()
        item.sent.set(done.sumOf { len(it).toLong() })
        item.resumed = done.isNotEmpty()
        item.lastBytes = item.sent.get()
        item.lastT = System.nanoTime()

        val todo = ConcurrentLinkedQueue((0 until n).filter { it !in done })
        var fatal: Exception? = null
        val threads = (1..minOf(PARALLEL, todo.size)).map {
            thread(name = "chunk-$it") {
                val buf = ByteArray(chunk)
                try {
                    while (!item.cancelled) {
                        val i = todo.poll() ?: break
                        val size = len(i)
                        readChunk(item.source!!, i.toLong() * chunk, buf, size)
                        retrying(item) { c ->
                            var counted = 0L
                            try {
                                c.putChunk(id, i, buf, size) { written ->
                                    if (item.cancelled) throw IOException("cancelled")
                                    val plain = minOf(written, size.toLong())
                                    item.sent.addAndGet(plain - counted)
                                    counted = plain
                                }
                            } catch (e: Exception) {
                                item.sent.addAndGet(-counted)
                                throw e
                            }
                        }
                    }
                } catch (e: FatalException) {
                    fatal = e; item.cancelled = true
                } catch (e: CancelledException) {
                }
            }
        }
        threads.forEach { it.join() }
        fatal?.let { throw it }
        if (item.cancelled) {
            runCatching { link.client().cancel(id) }
            throw CancelledException()
        }
        retrying(item) { it.finish(id) }
    }

    private fun readChunk(uri: Uri, offset: Long, buf: ByteArray, len: Int) {
        val input: InputStream = try {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException()
            ParcelFileDescriptor.AutoCloseInputStream(pfd).also { it.channel.position(offset) }
        } catch (e: Exception) {
            throw FatalException(getString(R.string.err_read))
        }
        val n = try {
            input.use { MacClient.readFully(it, buf, len) }
        } catch (e: IOException) {
            throw FatalException(getString(R.string.err_read))
        }
        if (n < len) throw FatalException(getString(R.string.err_short))
    }

    // ---------------- download ----------------

    private fun download(item: Transfers.Item) {
        val out = item.output ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneDrop")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            Log.i(TAG, "download ${item.name}: creating the file")
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw FatalException(getString(R.string.err_disk))
        }.also { item.output = it }
        item.lastT = System.nanoTime()

        retrying(item) { c ->
            val from = (item.sent.get() / MacClient.FRAME).toInt()
            item.resumed = from > 0
            Log.i(TAG, "download ${item.name}: opening it, from frame $from")
            val pfd = contentResolver.openFileDescriptor(out, "rw") ?: throw FatalException(getString(R.string.err_disk))
            Log.i(TAG, "download ${item.name}: requesting")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { stream ->
                val ch = stream.channel
                ch.position(from.toLong() * MacClient.FRAME)
                c.download(item.fileId!!, from) { _, data ->
                    if (item.cancelled) throw CancelledException()
                    ch.write(ByteBuffer.wrap(data))
                    item.sent.addAndGet(data.size.toLong())
                }
                ch.truncate(item.sent.get())
            }
            if (item.sent.get() < item.size) throw IOException("incomplete download")
        }
        contentResolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        Log.i(TAG, "download ${item.name}: done")
    }

    // ---------------- retries ----------------

    /**
     * Retries network errors forever with backoff; rediscovers the Mac if its address changed.
     *
     * TR: Ağ hatalarında artan beklemeyle süresiz yeniden dener; adresi değiştiyse Mac'i yeniden bulur.
     */
    private fun <T> retrying(item: Transfers.Item, block: (MacClient) -> T): T {
        var delay = 1000L
        while (true) {
            if (item.cancelled) throw CancelledException()
            val client = try {
                link.client(allowDirect = true) { Transfers.linkStatus = getString(it) }
            } catch (e: UnauthorizedException) {
                throw FatalException(getString(R.string.err_unpaired))
            } catch (e: IOException) {
                Transfers.linkStatus = getString(if (e is DirectLink.DirectException) e.reason else link.diagnose())
                sleepOrCancel(item, delay); delay = minOf(delay * 2, 8000); continue
            }
            try {
                val r = block(client)
                Transfers.linkStatus = null
                return r
            } catch (e: UnauthorizedException) {
                throw FatalException(getString(R.string.err_unpaired))
            } catch (e: HttpStatusException) {
                if (e.code in 400..499 && e.code != 408) throw FatalException(e.message ?: "HTTP ${e.code}")
                Transfers.linkStatus = getString(R.string.status_mac_error)
            } catch (e: IOException) {
                if (item.cancelled) throw CancelledException()
                link.invalidate(client)
                Transfers.linkStatus = getString(R.string.status_retry)
            }
            sleepOrCancel(item, delay)
            delay = minOf(delay * 2, 8000)
        }
    }

    private fun sleepOrCancel(item: Transfers.Item, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (item.cancelled) throw CancelledException()
            Thread.sleep(100)
        }
    }

    // ---------------- notifications ----------------

    private fun tickLoop() {
        try {
            while (true) {
                val now = System.nanoTime()
                for (it in Transfers.items) {
                    if (it.state != Transfers.State.ACTIVE) { it.speed = 0.0; continue }
                    val s = it.sent.get()
                    val dt = (now - it.lastT) / 1e9
                    if (dt >= 0.5) {
                        val inst = maxOf(0.0, (s - it.lastBytes) / dt)
                        it.speed = if (it.speed == 0.0) inst else it.speed * 0.6 + inst * 0.4
                        it.lastBytes = s; it.lastT = now
                    }
                }
                if (!stopping) nm.notify(NOTIF_ID, buildProgressNotification())
                Thread.sleep(1000)
            }
        } catch (e: InterruptedException) {
        }
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildProgressNotification(): Notification {
        val all = Transfers.items.filter { it.state != Transfers.State.CANCELLED && it.state != Transfers.State.FAILED }
        val pending = Transfers.active()
        val doneCount = all.count { it.state == Transfers.State.DONE }
        val total = all.sumOf { it.size }.coerceAtLeast(1)
        val sent = all.sumOf { if (it.state == Transfers.State.DONE) it.size else it.sent.get() }
        val current = pending.firstOrNull { it.state == Transfers.State.ACTIVE }
        val speed = pending.sumOf { it.speed }
        val text = Transfers.linkStatus ?: buildString {
            append(current?.name ?: getString(R.string.notif_preparing))
            if (speed > 1) {
                append(" · ").append(Fmt.bytes(this@TransferService, speed.toLong())).append("/s")
                val eta = Fmt.eta(this@TransferService, (total - sent) / speed)
                if (eta.isNotEmpty()) append(" · ").append(eta).append(' ').append(getString(R.string.notif_left))
            }
        }
        val stop = PendingIntent.getService(this, 1,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle(getString(R.string.notif_title, minOf(doneCount + 1, all.size.coerceAtLeast(1)), all.size.coerceAtLeast(1)))
            .setContentText(text)
            .setProgress(1000, (sent * 1000 / total).toInt(), sent == 0L && speed == 0.0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .build()
    }

    private fun finishAll() {
        val done = Transfers.items.filter { it.state == Transfers.State.DONE && !it.notified }
        val failed = Transfers.items.filter { it.state == Transfers.State.FAILED && !it.notified }
        (done + failed).forEach { it.notified = true }
        if (done.isNotEmpty() || failed.isNotEmpty()) {
            val title = when {
                failed.isNotEmpty() && done.isEmpty() -> getString(R.string.notif_failed)
                failed.isNotEmpty() -> getString(R.string.notif_mixed, done.size, failed.size)
                done.size > 1 -> getString(R.string.notif_sent_many, done.size)
                done[0].direction == Transfers.Direction.UP -> getString(R.string.notif_sent_one, done[0].name)
                else -> getString(R.string.notif_saved_one, done[0].name)
            }
            val text = failed.firstOrNull()?.let { "${it.name}: ${it.error}" }
                ?: (Fmt.bytes(this, done.sumOf { it.size }) + (link.prefs.macName?.let { " · $it" } ?: ""))
            nm.notify(NOTIF_DONE_ID, Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build())
        }
        shutdown()
    }

    private fun shutdown() {
        // Leave direct mode off the main thread (it makes a network call)
        thread { runCatching { link.endDirect() } }
        stopping = true
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        stopping = false
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneDrop:transfer").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        if (wifiLock == null) wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneDrop:wifi").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }; wakeLock = null
        runCatching { wifiLock?.release() }; wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }
}
