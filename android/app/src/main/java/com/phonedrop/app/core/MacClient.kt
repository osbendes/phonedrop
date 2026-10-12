package com.phonedrop.app.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.AEADBadTagException
import org.json.JSONObject

class HttpStatusException(val code: Int, msg: String) : IOException(msg)

/** Thrown when the Mac rejects our key (e.g. pairing was reset). */
class UnauthorizedException : IOException("unauthorized")

/**
 * Protocol v2 client (docs/PROTOCOL.md). Pure JVM so it can be tested against the real Mac app.
 * [opener] lets Android route the connection over Wi-Fi.
 */
class MacClient(
    val base: String,
    key: ByteArray,
    private val opener: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val crypto = Crypto(key)

    private fun connect(method: String, target: String, readTimeoutMs: Int = 60_000): HttpURLConnection {
        val c = opener(URL(base + target))
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = readTimeoutMs
        val now = System.currentTimeMillis() / 1000
        val auth = crypto.seal("$method $target $now".toByteArray(), "pd2 auth")
        c.setRequestProperty("X-PD-Auth", Crypto.b64url(auth))
        return c
    }

    private fun check(c: HttpURLConnection): Int {
        val code = c.responseCode
        if (code == 403) { c.errorStream?.close(); throw UnauthorizedException() }
        if (code !in 200..299) {
            val text = c.errorStream?.use { it.readBytes().decodeToString() } ?: ""
            val msg = runCatching { JSONObject(text).optString("error") }.getOrNull() ?: ""
            throw HttpStatusException(code, msg.ifEmpty { "HTTP $code" })
        }
        return code
    }

    private fun readSealed(c: HttpURLConnection, path: String): JSONObject {
        check(c)
        val box = c.inputStream.use { it.readBytes() }
        val plain = try {
            crypto.open(box, "pd2 res $path")
        } catch (e: AEADBadTagException) {
            throw IOException("bad response")
        }
        return JSONObject(plain.decodeToString())
    }

    /** POST an encrypted JSON body (or none) and return the decrypted JSON response. */
    fun post(path: String, query: String = "", body: JSONObject? = null, timeoutMs: Int = 30_000): JSONObject {
        val c = connect("POST", path + query, timeoutMs)
        val bytes = body?.let { crypto.seal(it.toString().toByteArray(), "pd2 req $path") } ?: ByteArray(0)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        return readSealed(c, path)
    }

    fun hello(device: String, timeoutMs: Int = 4_000): JSONObject =
        post("/api/hello", body = JSONObject().put("device", device), timeoutMs = timeoutMs)

    /** [dir] is an optional subfolder of the Mac's download folder, e.g. "Wedding 2026". */
    fun init(name: String, size: Long, mtime: Long, chunk: Int, dir: String = ""): JSONObject =
        post("/api/init", body = JSONObject().put("name", name).put("size", size).put("mtime", mtime)
            .put("chunk", chunk).put("dir", dir))

    /** Subfolders that already exist in the Mac's download folder. */
    fun folders(): JSONObject = post("/api/folders")

    fun finish(id: String): JSONObject = post("/api/finish", "?id=$id")

    fun cancel(id: String): JSONObject = post("/api/cancel", "?id=$id")

    fun outbox(): JSONObject = post("/api/outbox")

    /**
     * Encrypts and uploads one chunk. [progress] receives bytes written so far (of the sealed body);
     * the caller maps that to plaintext progress.
     */
    fun putChunk(id: String, index: Int, plain: ByteArray, len: Int, progress: (Long) -> Unit = {}) {
        val box = crypto.seal(plain, "pd2 chunk $id $index", 0, len)
        val c = connect("PUT", "/api/chunk?id=$id&i=$index")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(box.size)
        c.outputStream.use { out -> writeWithProgress(out, box, progress) }
        readSealed(c, "/api/chunk")
    }

    private fun writeWithProgress(out: OutputStream, data: ByteArray, progress: (Long) -> Unit) {
        var off = 0
        while (off < data.size) {
            val n = minOf(256 * 1024, data.size - off)
            out.write(data, off, n)
            off += n
            progress(off.toLong())
        }
    }

    /**
     * Streams a file from the Mac's outbox starting at [fromFrame]; each decrypted frame
     * (1 MiB, the last one shorter) is handed to [onFrame] in order.
     */
    fun download(fileId: String, fromFrame: Int, onFrame: (index: Int, data: ByteArray) -> Unit) {
        val c = connect("GET", "/api/dl?f=$fileId&from=$fromFrame", readTimeoutMs = 60_000)
        check(c)
        DataInputStream(c.inputStream.buffered(1 shl 16)).use { input ->
            var k = fromFrame
            while (true) {
                val len = try { input.readInt() } catch (e: EOFException) { break }
                if (len < Crypto.OVERHEAD || len > FRAME + Crypto.OVERHEAD) throw IOException("bad frame")
                val box = ByteArray(len)
                input.readFully(box)
                val plain = try {
                    crypto.open(box, "pd2 dl $fileId $k")
                } catch (e: AEADBadTagException) {
                    throw IOException("bad frame")
                }
                onFrame(k, plain)
                k++
            }
        }
    }

    companion object {
        const val CHUNK = 8 * 1024 * 1024
        const val FRAME = 1 shl 20

        /** Reads exactly [len] bytes unless the stream ends first; returns the count read. */
        fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
            var n = 0
            while (n < len) {
                val r = input.read(buf, n, len - n)
                if (r < 0) break
                n += r
            }
            return n
        }
    }
}
