package com.phonedrop.app.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browser mode (docs/PROTOCOL.md, "Web sessions"): the phone is a small web server, and a computer with
 * nothing installed opens its page. The page shows a QR code with a one-time public key; scanning it gives
 * both sides a session key, and every file then travels in ChaCha20-Poly1305 boxes.
 * Pure JVM, so it is tested without a phone.
 *
 * TR: Tarayıcı modu (docs/PROTOCOL.md, "Web oturumları"): telefon küçük bir web sunucusu olur, hiçbir şey
 * kurulmamış bir bilgisayar onun sayfasını açar. Sayfa tek kullanımlık açık anahtar içeren bir karekod
 * gösterir; kod okutulunca iki taraf bir oturum anahtarı edinir ve bütün dosyalar ChaCha20-Poly1305
 * kutuları içinde gider. Saf JVM kodudur; telefon olmadan test edilir.
 */
class WebHost(private val page: ByteArray, private val deviceName: () -> String, private val store: Store) {

    class Offer(val id: String, val name: String, val size: Long)

    /**
     * Where the files live; the Android side implements it.
     *
     * TR: Dosyaların durduğu yer; Android tarafı bunu uygular.
     */
    interface Store {
        /** Files the phone offers to the browser. TR: Telefonun tarayıcıya sunduğu dosyalar. */
        fun offers(): List<Offer>
        fun open(id: String): InputStream?
        /** The browser has [bytes] of an offered file; [done] when it has all of it. */
        fun sending(id: String, bytes: Long, done: Boolean)
        fun create(name: String, size: Long, mtime: Long): Sink
    }

    interface Sink {
        fun write(data: ByteArray)
        /** Returns the name the file was saved under. TR: Dosyanın kaydedildiği adı döndürür. */
        fun finish(): String
        fun abort()
    }

    enum class State { WAITING, SCANNED, LINKED }

    private class Session(val sid: String, val box: Box, val hello: JSONObject) {
        val seen = HashSet<Long>()
        var max = 0L
    }

    private class Upload(val sink: Sink, val size: Long) {
        var next = 0
        var received = 0L
    }

    private class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray) {
        val path = target.substringBefore('?')
        val query: Map<String, String> = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private class Reply(val status: Int, val body: ByteArray = ByteArray(0), val type: String = "application/octet-stream")
    private class Refused(val status: Int) : Exception()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var session: Session? = null
    private val uploads = ConcurrentHashMap<String, Upload>()
    private val pool = ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, SynchronousQueue())

    @Volatile var state = State.WAITING
        private set
    /** When the browser last made a valid request. TR: Tarayıcının son geçerli isteğinin zamanı. */
    @Volatile var lastActive = System.currentTimeMillis()
        private set
    /** Called (on a server thread) whenever [state] changes. TR: [state] değişince çağrılır. */
    var onChange: () -> Unit = {}

    val port get() = server?.localPort ?: 0

    fun start(preferred: Int = PORT): Int {
        val s = try { ServerSocket(preferred) } catch (e: IOException) { ServerSocket(0) }
        server = s
        thread(name = "web-accept", isDaemon = true) {
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (e: IOException) { break }
                try { pool.execute { serve(socket) } } catch (e: Exception) { runCatching { socket.close() } }
            }
        }
        return s.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        session = null
        state = State.WAITING
        uploads.values.forEach { runCatching { it.sink.abort() } }
        uploads.clear()
        pool.shutdownNow()
    }

    /**
     * The phone scanned the page's code: agree on a key with that browser. Replaces any earlier session.
     * Returns false if the key in the code is not usable.
     *
     * TR: Telefon sayfadaki kodu okuttu: o tarayıcıyla bir anahtar üzerinde anlaş. Önceki oturumun yerini
     * alır. Koddaki anahtar kullanılamıyorsa false döndürür.
     */
    fun connect(browserPublic: ByteArray): Boolean {
        if (browserPublic.size != 32) return false
        val private = X25519.newPrivate()
        val shared = X25519.shared(private, browserPublic)
        if (shared.all { it == 0.toByte() }) return false
        val public = X25519.public(private)
        val box = Box(X25519.hkdf(shared, public + browserPublic, "phonedrop web v3"))
        val sid = MessageDigest.getInstance("SHA-256").digest(browserPublic).take(8).joinToString("") { "%02x".format(it) }
        val confirm = box.seal(JSONObject().put("name", deviceName()).toString().toByteArray(), "pd3 hello")
        uploads.values.forEach { runCatching { it.sink.abort() } }
        uploads.clear()
        session = Session(sid, box, JSONObject().put("a", Crypto.b64url(public)).put("c", Crypto.b64url(confirm)))
        setState(State.SCANNED)
        return true
    }

    private fun setState(s: State) {
        if (state != s) { state = s; onChange() }
    }

    // ---------------- HTTP ----------------

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream(), 1 shl 16)
            val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)
            while (true) {
                val req = read(input) ?: break
                if (req.method == "GET" && req.path == "/w/dl") {
                    download(req, out)
                    break
                }
                val reply = try { route(req) } catch (e: Refused) { Reply(e.status) }
                write(out, reply, req.path == "/")
                if (req.headers["connection"].equals("close", true)) break
            }
        } catch (e: Exception) {
            // A dropped connection: the browser retries
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun read(input: InputStream): Request? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.length > 8192) throw IOException("header too long")
        }
        val lines = head.trim().split("\r\n")
        val first = lines[0].split(' ')
        if (first.size != 3) throw IOException("bad request")
        val headers = lines.drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw IOException("body too large")
        val body = ByteArray(length)
        if (MacClient.readFully(input, body, length) < length) return null
        return Request(first[0], first[1], headers, body)
    }

    private fun write(out: OutputStream, r: Reply, isPage: Boolean) {
        val reason = when (r.status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error" }
        val head = StringBuilder("HTTP/1.1 ${r.status} $reason\r\n")
            .append("Content-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\n")
            .append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n")
        // The page may only run its own inline code and talk to this server
        if (isPage) head.append("Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "connect-src 'self'; img-src data: blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n")
        out.write(head.append("\r\n").toString().toByteArray())
        out.write(r.body)
        out.flush()
    }

    private fun route(req: Request): Reply {
        if (req.method == "GET") return when (req.path) {
            "/", "/index.html" -> Reply(200, page, "text/html; charset=utf-8")
            // Unauthenticated on purpose: it only answers the browser whose code was scanned, and what it
            // returns is useless without that browser's private key
            "/w/session" -> session?.takeIf { it.sid == req.query["id"] }
                ?.let { Reply(200, it.hello.toString().toByteArray(), "application/json") } ?: Reply(404)
            else -> Reply(404)
        }
        val s = authorize(req)
        fun sealed(json: JSONObject) = Reply(200, s.box.seal(json.toString().toByteArray(), "pd3 res ${req.path}"))
        return when (req.method + " " + req.path) {
            "POST /w/list" -> sealed(JSONObject().put("files", JSONArray(store.offers().map {
                JSONObject().put("id", it.id).put("name", it.name).put("size", it.size)
            })))
            "POST /w/up/init" -> {
                val j = try {
                    JSONObject(s.box.open(req.body, "pd3 req ${req.path}").decodeToString())
                } catch (e: Exception) { throw Refused(400) }
                val size = j.optLong("size", -1)
                if (size < 0 || uploads.size >= 8) throw Refused(400)
                val id = UUID.randomUUID().toString()
                uploads[id] = Upload(store.create(safeName(j.optString("name")), size, j.optLong("mtime")), size)
                sealed(JSONObject().put("id", id))
            }
            "PUT /w/up/chunk" -> {
                val id = req.query["id"] ?: throw Refused(400)
                val i = req.query["i"]?.toIntOrNull() ?: throw Refused(400)
                val up = uploads[id] ?: throw Refused(404)
                val plain = try { s.box.open(req.body, "pd3 up $id $i") } catch (e: Exception) { throw Refused(400) }
                synchronized(up) {
                    // A repeated chunk is a retry whose answer got lost
                    if (i != up.next - 1) {
                        if (i != up.next || up.received + plain.size > up.size) throw Refused(409)
                        up.sink.write(plain)
                        up.received += plain.size
                        up.next++
                    }
                }
                sealed(JSONObject().put("ok", true))
            }
            "POST /w/up/finish" -> {
                val id = req.query["id"] ?: throw Refused(400)
                val up = uploads[id] ?: throw Refused(404)
                synchronized(up) {
                    if (up.received != up.size) throw Refused(409)
                    uploads.remove(id)
                    sealed(JSONObject().put("name", up.sink.finish()))
                }
            }
            "POST /w/up/cancel" -> {
                uploads.remove(req.query["id"])?.let { runCatching { it.sink.abort() } }
                sealed(JSONObject().put("ok", true))
            }
            else -> throw Refused(404)
        }
    }

    /**
     * Every request carries the sealed text "<METHOD> <target> <n>"; n may be used once.
     *
     * TR: Her istek, şifreli "<METHOD> <hedef> <n>" metnini taşır; n yalnızca bir kez kullanılabilir.
     */
    private fun authorize(req: Request): Session {
        val s = session?.takeIf { it.sid == req.headers["x-pd-session"] } ?: throw Refused(403)
        val text = try {
            s.box.open(Crypto.unb64url(req.headers["x-pd-auth"] ?: ""), "pd3 auth").decodeToString()
        } catch (e: Exception) { throw Refused(403) }
        val n = text.substringAfterLast(' ').toLongOrNull() ?: throw Refused(403)
        if (text.substringBeforeLast(' ') != "${req.method} ${req.target}") throw Refused(403)
        synchronized(s) {
            if (n <= s.max - WINDOW || !s.seen.add(n)) throw Refused(403)
            if (n > s.max) s.max = n
            if (s.seen.size > 2 * WINDOW) s.seen.removeAll { it <= s.max - WINDOW }
        }
        lastActive = System.currentTimeMillis()
        setState(State.LINKED)
        return s
    }

    /**
     * Streams an offered file as sealed 1 MiB frames, each prefixed with its length.
     *
     * TR: Sunulan bir dosyayı, her biri uzunluğuyla başlayan 1 MiB'lik şifreli çerçeveler halinde gönderir.
     */
    private fun download(req: Request, out: OutputStream) {
        val s = try { authorize(req) } catch (e: Refused) { return write(out, Reply(e.status), false) }
        val id = req.query["f"] ?: return write(out, Reply(400), false)
        val from = req.query["from"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return write(out, Reply(400), false)
        val input = store.open(id) ?: return write(out, Reply(404), false)
        input.use {
            var skip = from.toLong() * MacClient.FRAME
            while (skip > 0) { val n = input.skip(skip); if (n <= 0) break; skip -= n }
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray())
            val data = DataOutputStream(out)
            val buf = ByteArray(MacClient.FRAME)
            var k = from
            var sent = from.toLong() * MacClient.FRAME
            while (true) {
                val n = MacClient.readFully(input, buf, buf.size)
                if (n <= 0) break
                val box = s.box.seal(buf, "pd3 dl $id $k", 0, n)
                data.writeInt(box.size)
                data.write(box)
                data.flush()
                k++
                sent += n
                lastActive = System.currentTimeMillis()
                store.sending(id, sent, false)
                if (n < buf.size) break
            }
            store.sending(id, sent, true)
        }
    }

    companion object {
        const val PORT = 8765
        /** Upload chunks are 2 MiB of file data. TR: Yükleme parçaları 2 MiB dosya verisidir. */
        const val CHUNK = 2 shl 20
        private const val MAX_BODY = CHUNK + 1024
        private const val WINDOW = 4096

        /**
         * A file name with nothing that could leave the download folder.
         *
         * TR: Kayıt klasörünün dışına çıkabilecek hiçbir şey içermeyen dosya adı.
         */
        fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it !in "<>:\"|?*" }
                .trim().trimStart('.').take(200).ifEmpty { "file" }
    }
}
