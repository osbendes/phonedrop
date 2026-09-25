package com.phonedrop.app.core

import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 1 GiB upload in 8 MiB chunks over 4 parallel connections, like the app does. */
class MacLoadTest {
    @Test
    fun parallelUploadOfOneGigabyte() {
        val f = File("/tmp/phonedrop-test.json")
        assumeTrue(f.exists() && System.getProperty("pd.load") != null || File("/tmp/phonedrop-load").exists())
        val cfg = JSONObject(f.readText())
        val key = File(System.getProperty("user.home"), "Library/Application Support/PhoneDrop/key").readBytes()
        val client = MacClient(cfg.getString("base"), key)

        val chunk = MacClient.CHUNK
        val block = ByteArray(chunk).also { Random(7).nextBytes(it) }
        val n = 128
        val size = n.toLong() * chunk
        val id = client.init("pd-load-${System.nanoTime()}.bin", size, 0, chunk).getString("id")
        val todo = ConcurrentLinkedQueue((0 until n).toList())
        val t0 = System.nanoTime()
        (1..4).map {
            thread { while (true) { val i = todo.poll() ?: break; client.putChunk(id, i, block, chunk) } }
        }.forEach { it.join() }
        val secs = (System.nanoTime() - t0) / 1e9
        val saved = File(cfg.getString("saveDir"), client.finish(id).getString("name"))
        println("LOAD: %d MiB in %.2f s = %.0f MiB/s".format(size shr 20, secs, (size shr 20) / secs))

        val md = MessageDigest.getInstance("SHA-256")
        val expected = MessageDigest.getInstance("SHA-256").apply { repeat(n) { update(block) } }.digest()
        saved.inputStream().use { s -> val b = ByteArray(1 shl 20); while (true) { val r = s.read(b); if (r < 0) break; md.update(b, 0, r) } }
        assertArrayEquals(expected, md.digest())
        saved.delete()
    }
}
