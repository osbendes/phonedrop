package com.phonedrop.app.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Talks to a running PhoneDrop Mac app. Skipped unless /tmp/phonedrop-test.json exists:
 * {"base": "http://127.0.0.1:8765", "saveDir": "...", "outboxId": "...", "outboxPath": "..."}
 * The key is read from ~/Library/Application Support/PhoneDrop/key.
 */
class MacIntegrationTest {
    private lateinit var cfg: JSONObject
    private lateinit var key: ByteArray
    private lateinit var client: MacClient

    @Before
    fun setUp() {
        val f = File("/tmp/phonedrop-test.json")
        assumeTrue("no test config", f.exists())
        cfg = JSONObject(f.readText())
        key = File(System.getProperty("user.home"), "Library/Application Support/PhoneDrop/key").readBytes()
        client = MacClient(cfg.getString("base"), key)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    @Test
    fun helloReturnsMacName() {
        val r = client.hello("JUnit")
        assertEquals(2, r.getInt("v"))
        assertTrue(r.getString("name").isNotEmpty())
    }

    @Test
    fun wrongKeyIsRejected() {
        val other = MacClient(cfg.getString("base"), ByteArray(32) { 7 })
        try {
            other.hello("intruder")
            fail("expected 403")
        } catch (e: UnauthorizedException) {
        }
    }

    @Test
    fun uploadResumesAndArrivesIntact() {
        val chunk = 1 shl 20
        val data = ByteArray(5 * chunk + 12345).also { Random(42).nextBytes(it) }
        val name = "pd-test-${System.nanoTime()}.bin"
        val mtime = 1_700_000_000_000L

        // First session: send chunks 0..2, then "lose the connection"
        val first = client.init(name, data.size.toLong(), mtime, chunk)
        val id = first.getString("id")
        assertEquals(0, first.getJSONArray("done").length())
        for (i in 0..2) client.putChunk(id, i, data.copyOfRange(i * chunk, (i + 1) * chunk), chunk)

        // Finishing early must fail
        try { client.finish(id); fail("expected 409") } catch (e: HttpStatusException) { assertEquals(409, e.code) }

        // Second session: the Mac remembers what it already has
        val second = client.init(name, data.size.toLong(), mtime, chunk)
        assertEquals(id, second.getString("id"))
        val done = (0 until second.getJSONArray("done").length()).map { second.getJSONArray("done").getInt(it) }.toSet()
        assertEquals(setOf(0, 1, 2), done)
        val n = (data.size + chunk - 1) / chunk
        for (i in 3 until n) {
            val len = minOf(chunk, data.size - i * chunk)
            client.putChunk(id, i, data.copyOfRange(i * chunk, i * chunk + len), len)
        }
        val saved = client.finish(id).getString("name")
        val file = File(cfg.getString("saveDir"), saved)
        assertArrayEquals(sha(data), sha(file.readBytes()))
        assertEquals(mtime, file.lastModified())
        file.delete()
    }

    @Test
    fun chunkSealedForAnotherIndexIsRejected() {
        val chunk = 1 shl 20
        val data = ByteArray(2 * chunk).also { Random(1).nextBytes(it) }
        val id = client.init("pd-tamper-${System.nanoTime()}.bin", data.size.toLong(), 0, chunk).getString("id")
        // Box made for chunk 0 but sent as chunk 1: the AAD no longer matches
        val box = Crypto(key).seal(data, "pd2 chunk $id 0", 0, chunk)
        val c = java.net.URL(cfg.getString("base") + "/api/chunk?id=$id&i=1").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        val now = System.currentTimeMillis() / 1000
        c.setRequestProperty("X-PD-Auth", Crypto.b64url(Crypto(key).seal("PUT /api/chunk?id=$id&i=1 $now".toByteArray(), "pd2 auth")))
        c.setFixedLengthStreamingMode(box.size)
        c.outputStream.use { it.write(box) }
        assertEquals(400, c.responseCode)
        client.cancel(id)
    }

    @Test
    fun downloadFromOutboxIsIntactAndResumable() {
        val fid = cfg.getString("outboxId")
        val expected = File(cfg.getString("outboxPath")).readBytes()
        val listed = client.outbox().getJSONArray("files")
        assertTrue((0 until listed.length()).any { listed.getJSONObject(it).getString("id") == fid })

        val full = ByteArrayOutputStream()
        client.download(fid, 0) { _, b -> full.write(b) }
        assertArrayEquals(sha(expected), sha(full.toByteArray()))

        // Resume from frame 2
        val tail = ByteArrayOutputStream()
        client.download(fid, 2) { _, b -> tail.write(b) }
        assertArrayEquals(sha(expected.copyOfRange(2 * MacClient.FRAME, expected.size)), sha(tail.toByteArray()))
    }

    @Test
    fun pairingUrlRoundTrips() {
        val url = "phonedrop://pair?v=2&k=${Crypto.b64url(key)}&h=192.168.1.6&p=8765&n=OSB%E2%80%99s%20Mac"
        val p = PairInfo.parse(url)!!
        assertArrayEquals(key, p.key)
        assertEquals("OSB’s Mac", p.name)
        assertEquals(8765, p.port)
    }
}
