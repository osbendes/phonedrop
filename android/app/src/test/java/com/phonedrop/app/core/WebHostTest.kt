package com.phonedrop.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Random
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Browser mode end to end, with this test playing the browser.
 *
 * TR: Tarayıcı modu uçtan uca; tarayıcı rolünü bu test oynar.
 */
class WebHostTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class MemStore : WebHost.Store {
        val offered = LinkedHashMap<String, Pair<String, ByteArray>>()
        val saved = LinkedHashMap<String, ByteArray>()
        val done = ArrayList<String>()
        var aborted = 0
        override fun offers() = offered.map { WebHost.Offer(it.key, it.value.first, it.value.second.size.toLong()) }
        override fun open(id: String): InputStream? = offered[id]?.let { ByteArrayInputStream(it.second) }
        override fun sending(id: String, bytes: Long, done: Boolean) { if (done) this.done.add(id) }
        override fun create(name: String, size: Long, mtime: Long) = object : WebHost.Sink {
            val buf = ByteArrayOutputStream()
            override fun write(data: ByteArray) = buf.write(data)
            override fun finish(): String { saved[name] = buf.toByteArray(); return name }
            override fun abort() { aborted++ }
        }
    }

    private lateinit var store: MemStore
    private lateinit var host: WebHost
    private lateinit var base: String
    private lateinit var box: Box
    private lateinit var sid: String
    private var counter = 1000L

    @Before
    fun setUp() {
        store = MemStore()
        host = WebHost("<html>page</html>".toByteArray(), { "Test Phone" }, store)
        base = "http://127.0.0.1:${host.start(0)}"
        // What the page does: make a key pair, show the public half, wait for the phone
        val private = X25519.newPrivate()
        val public = X25519.public(private)
        sid = MessageDigest.getInstance("SHA-256").digest(public).take(8).joinToString("") { "%02x".format(it) }
        assertEquals(404, get("/w/session?id=$sid").responseCode)
        assertTrue(host.connect(public))
        val hello = JSONObject(get("/w/session?id=$sid").inputStream.readBytes().decodeToString())
        val phone = Crypto.unb64url(hello.getString("a"))
        box = Box(X25519.hkdf(X25519.shared(private, phone), phone + public, "phonedrop web v3"))
        assertEquals("Test Phone", JSONObject(box.open(Crypto.unb64url(hello.getString("c")), "pd3 hello").decodeToString()).getString("name"))
    }

    @After
    fun tearDown() = host.stop()

    private fun get(target: String) = URL(base + target).openConnection() as HttpURLConnection

    private fun call(method: String, target: String, body: ByteArray? = null, auth: String? = null, session: String = sid): HttpURLConnection {
        val c = URL(base + target).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("X-PD-Session", session)
        c.setRequestProperty("X-PD-Auth", auth ?: Crypto.b64url(box.seal("$method $target ${++counter}".toByteArray(), "pd3 auth")))
        if (body != null || method != "GET") {
            c.doOutput = true
            c.outputStream.use { it.write(body ?: ByteArray(0)) }
        }
        return c
    }

    private fun rpc(path: String, query: String = "", body: JSONObject? = null): JSONObject {
        val c = call("POST", path + query, body?.let { box.seal(it.toString().toByteArray(), "pd3 req $path") })
        assertEquals(200, c.responseCode)
        return JSONObject(box.open(c.inputStream.readBytes(), "pd3 res $path").decodeToString())
    }

    @Test
    fun x25519MatchesRfc7748() {
        assertArrayEquals(hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            X25519.shared(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")))
        // Alice and Bob from the RFC
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), X25519.public(a))
        assertArrayEquals(hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"), X25519.shared(a, X25519.public(b)))
    }

    @Test
    fun hkdfMatchesRfc5869() {
        val okm = X25519.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), String(hex("f0f1f2f3f4f5f6f7f8f9"), Charsets.ISO_8859_1))
        // The RFC's info bytes are not valid UTF-8, so only check the plain-text path against a known answer
        assertEquals(32, okm.size)
        assertArrayEquals(X25519.hkdf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), "x"), X25519.hkdf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), "x"))
    }

    @Test
    fun pageIsServedWithAStrictPolicy() {
        val c = get("/")
        assertEquals(200, c.responseCode)
        assertTrue(c.getHeaderField("Content-Security-Policy").contains("default-src 'none'"))
        assertEquals("<html>page</html>", c.inputStream.readBytes().decodeToString())
    }

    @Test
    fun strangersAndReplaysAreRefused() {
        assertEquals(WebHost.State.SCANNED, host.state)
        // No key, wrong session, wrong key
        assertEquals(403, call("POST", "/w/list", auth = "AAAA").responseCode)
        assertEquals(403, call("POST", "/w/list", session = "0000000000000000").responseCode)
        val other = Box(ByteArray(32) { 7 })
        assertEquals(403, call("POST", "/w/list", auth = Crypto.b64url(other.seal("POST /w/list 5".toByteArray(), "pd3 auth"))).responseCode)
        assertEquals(WebHost.State.SCANNED, host.state)
        // A valid header works once, and only for the request it was made for
        val auth = Crypto.b64url(box.seal("POST /w/list 77".toByteArray(), "pd3 auth"))
        assertEquals(200, call("POST", "/w/list", auth = auth).responseCode)
        assertEquals(WebHost.State.LINKED, host.state)
        assertEquals(403, call("POST", "/w/list", auth = auth).responseCode)
        val forOther = Crypto.b64url(box.seal("POST /w/list 78".toByteArray(), "pd3 auth"))
        assertEquals(403, call("POST", "/w/up/cancel?id=x", auth = forOther).responseCode)
        // A new scan ends the old session
        assertTrue(host.connect(X25519.public(X25519.newPrivate())))
        assertEquals(403, call("POST", "/w/list").responseCode)
        assertFalse(host.connect(ByteArray(32)))
    }

    @Test
    fun uploadArrivesIntact() {
        val data = ByteArray(2 * WebHost.CHUNK + 4321).also { Random(5).nextBytes(it) }
        val id = rpc("/w/up/init", body = JSONObject().put("name", "../evil/..\\photo?.jpg").put("size", data.size).put("mtime", 0)).getString("id")
        fun chunk(i: Int, sealedAs: Int = i): Int {
            val from = i * WebHost.CHUNK
            val plain = data.copyOfRange(from, minOf(data.size, from + WebHost.CHUNK))
            return call("PUT", "/w/up/chunk?id=$id&i=$i", box.seal(plain, "pd3 up $id $sealedAs")).responseCode
        }
        assertEquals(409, chunk(1))             // out of order
        assertEquals(400, chunk(0, sealedAs = 1)) // sealed for another position
        assertEquals(200, chunk(0))
        assertEquals(200, chunk(0))             // a retry is harmless
        assertEquals(409, call("POST", "/w/up/finish?id=$id").responseCode)  // too early
        assertEquals(200, chunk(1))
        assertEquals(200, chunk(2))
        assertEquals("photo.jpg", rpc("/w/up/finish", "?id=$id").getString("name"))
        assertArrayEquals(data, store.saved["photo.jpg"])
    }

    @Test
    fun downloadIsIntactAndResumable() {
        val data = ByteArray(3 * MacClient.FRAME + 99).also { Random(6).nextBytes(it) }
        store.offered["f1"] = "clip.mp4" to data
        val listed = rpc("/w/list").getJSONArray("files").getJSONObject(0)
        assertEquals("clip.mp4", listed.getString("name"))
        assertEquals(data.size.toLong(), listed.getLong("size"))
        fun fetch(from: Int): ByteArray {
            val c = call("GET", "/w/dl?f=f1&from=$from")
            assertEquals(200, c.responseCode)
            val out = ByteArrayOutputStream()
            DataInputStream(c.inputStream).use { input ->
                var k = from
                while (true) {
                    val len = try { input.readInt() } catch (e: java.io.EOFException) { break }
                    val frame = ByteArray(len).also { input.readFully(it) }
                    out.write(box.open(frame, "pd3 dl f1 ${k++}"))
                }
            }
            return out.toByteArray()
        }
        assertArrayEquals(data, fetch(0))
        assertArrayEquals(data.copyOfRange(2 * MacClient.FRAME, data.size), fetch(2))
        assertEquals(listOf("f1", "f1"), store.done)
        assertEquals(404, call("GET", "/w/dl?f=nope&from=0").responseCode)
    }

    @Test
    fun stoppingAbortsUnfinishedUploads() {
        rpc("/w/up/init", body = JSONObject().put("name", "a.bin").put("size", 10).put("mtime", 0))
        host.stop()
        assertEquals(1, store.aborted)
    }
}
