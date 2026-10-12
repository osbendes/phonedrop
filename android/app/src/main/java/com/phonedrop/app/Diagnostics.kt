package com.phonedrop.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.phonedrop.app.core.MacClient
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Test hooks driven over adb, e.g.
 * `adb shell am start -n com.osbendes.phonedrop/com.phonedrop.app.MainActivity -a com.osbendes.phonedrop.DIAG_SEND`.
 * They only run on a paired phone that has USB debugging turned on.
 *
 * TR: adb üzerinden tetiklenen test kancaları (örnek komut yukarıda). Yalnızca USB hata ayıklaması açık,
 * eşleşmiş bir telefonda çalışır.
 */
object Diagnostics {
    private const val TAG = "PhoneDrop"
    private const val PREFIX = "com.osbendes.phonedrop.DIAG_"

    fun run(activity: MainActivity, intent: Intent?, prefs: Prefs) {
        val action = intent?.action?.takeIf { it.startsWith(PREFIX) } ?: return
        val adbOn = Settings.Global.getInt(activity.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        if (!adbOn || !prefs.paired) return
        when (action.removePrefix(PREFIX)) {
            // Opens the QR scanner, to check it starts
            "SCAN" -> activity.scan()
            // Finds the Mac over Bluetooth and reads its id; touches no network
            "BLE" -> thread { report(activity, DirectLink(activity, prefs).probe().trim()) }
            // Uploads a random file through the normal path (extras: name, mb)
            "SEND" -> thread {
                val file = File(activity.cacheDir, intent.getStringExtra("name") ?: "pd-diag.bin")
                val buf = ByteArray(1 shl 20)
                val random = SecureRandom()
                file.outputStream().use { out -> repeat(intent.getIntExtra("mb", 20)) { random.nextBytes(buf); out.write(buf) } }
                Log.i(TAG, "diag: sending ${file.name} (${file.length()} bytes)")
                TransferService.upload(activity, listOf(Uri.fromFile(file)))
            }
            // Full direct-mode handshake; if the Mac is on Wi-Fi and refuses, nothing switches
            "DIRECT" -> thread {
                val direct = DirectLink(activity, prefs)
                val result = try {
                    val t = direct.connect({ Log.i(TAG, "diag: " + activity.getString(it)) })
                    val client = MacClient("http://${t.host}:${t.port}", prefs.key!!)
                    val name = client.hello("diag").optString("name")
                    runCatching { client.post("/api/direct/end", timeoutMs = 3000) }
                    "ready: ${t.host}:${t.port}, hello from $name"
                } catch (e: DirectLink.DirectException) {
                    "failed: " + activity.getString(e.reason)
                } finally {
                    direct.close()
                }
                report(activity, result)
            }
        }
    }

    private fun report(activity: MainActivity, text: String) {
        Log.i(TAG, "diag: $text")
        activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    }
}
