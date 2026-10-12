package com.phonedrop.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.phonedrop.app.core.MacClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
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
            // Screenshots: sets language/theme (extras: lang, theme; "" = system) and restarts the screen
            "LOOK" -> {
                val sp = activity.getSharedPreferences(Look.PREFS, 0)
                val lang = intent.getStringExtra("lang") ?: ""
                val theme = intent.getStringExtra("theme") ?: ""
                if (lang != sp.getString(Look.LANG, "") || theme != sp.getString(Look.THEME, "")) {
                    sp.edit().putString(Look.LANG, lang).putString(Look.THEME, theme).apply()
                    activity.recreate()
                }
            }
            // Screenshots: sample content instead of the real Mac (extras: scene, dialog, bottom); scene "off" ends it
            "DEMO" -> {
                demo(activity, intent.getStringExtra("scene") ?: "off", intent.getStringExtra("dialog"))
                // `--ez bottom true` shows the lower half of the screen
                val scroll = activity.findViewById<android.widget.ScrollView>(R.id.scroll)
                val down = intent.getBooleanExtra("bottom", false)
                scroll.postDelayed({ scroll.fullScroll(if (down) android.view.View.FOCUS_DOWN else android.view.View.FOCUS_UP) }, 600)
            }
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

    private fun demo(activity: MainActivity, scene: String, dialog: String?) {
        fun item(dir: Transfers.Direction, name: String, size: Long, sent: Long, state: Transfers.State, speed: Double = 0.0) =
            Transfers.Item(dir, name, size).also { it.state = state; it.sent.set(sent); it.speed = speed }
        val up = Transfers.Direction.UP
        val wasOn = activity.demo != null
        Transfers.items.clear()
        activity.demo = when (scene) { "off" -> null; "unpaired" -> false; else -> true }
        val files = JSONArray()
        when (scene) {
            "sending" -> Transfers.items += listOf(
                // The list shows the last one on top
                item(up, "IMG_20261011_184467.jpg", 5_100_000, 0, Transfers.State.WAITING),
                item(up, "IMG_20261011_184512.jpg", 4_800_000, 4_800_000, Transfers.State.DONE),
                item(up, "VID_20261012_143501.mp4", 3_800_000_000, 1_420_000_000, Transfers.State.ACTIVE, 21_400_000.0))
            "receiving" -> {
                Transfers.items += item(Transfers.Direction.DOWN, "Trailer-4K.mov", 2_100_000_000, 840_000_000,
                    Transfers.State.ACTIVE, 18_200_000.0)
                for ((n, s) in listOf("Mix-Final.wav" to 86_000_000L, "Contract.pdf" to 1_200_000L)) {
                    files.put(JSONObject().put("id", n).put("name", n).put("size", s))
                }
            }
        }
        activity.renderMac()
        activity.renderOutbox(activity.demo, JSONObject().put("files", files))
        when (dialog) {
            "pair" -> activity.showPairDialog()
            "settings" -> activity.showSettings()
            "macapp" -> activity.showMacAppDialog()
        }
        if (scene == "off" && wasOn) activity.recreate()
    }

    private fun report(activity: MainActivity, text: String) {
        Log.i(TAG, "diag: $text")
        activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    }
}
