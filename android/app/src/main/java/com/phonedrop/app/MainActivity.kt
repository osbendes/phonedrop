package com.phonedrop.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.phonedrop.app.core.PairInfo
import kotlin.concurrent.thread
import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        const val MAC_DOWNLOAD_URL = "https://github.com/osbendes/phonedrop/releases/latest"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var link: MacLink
    private val rows = HashMap<Int, Row>()
    @Volatile private var macOnline: Boolean? = null
    @Volatile private var offlineReason = R.string.mac_offline

    private class Row(val root: View, val name: TextView, val bar: ProgressBar, val status: TextView, val speed: TextView)

    private val tick = object : Runnable {
        override fun run() {
            renderTransfers()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        link = MacLink(this)

        findViewById<Button>(R.id.pairBtn).setOnClickListener { if (link.prefs.paired) showMacMenu() else showPairDialog() }
        findViewById<Button>(R.id.pickGallery).setOnClickListener { pickGallery() }
        findViewById<Button>(R.id.pickFiles).setOnClickListener { pickFiles() }
        findViewById<View>(R.id.outboxRefresh).setOnClickListener { refreshMac() }
        findViewById<View>(R.id.batteryBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<TextView>(R.id.transfersAction).setOnClickListener {
            if (Transfers.active().isNotEmpty()) TransferService.stopAll(this)
            else { Transfers.clearFinished(); renderTransfers() }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        intent?.data?.let { pairWith(it.toString()) }
        runDiagnostics(intent)
    }

    /** `adb shell am start -n <pkg>/com.phonedrop.app.MainActivity -a com.osbendes.phonedrop.DIAG_BLE` */
    private fun runDiagnostics(i: Intent?) {
        if (!link.prefs.paired) return
        when (i?.action) {
            "com.osbendes.phonedrop.DIAG_BLE" -> thread {
                val report = DirectLink(this, link.prefs).probe()
                runOnUiThread { Toast.makeText(this, report.trim(), Toast.LENGTH_LONG).show() }
            }
            // Full handshake incl. hotspot; if the Mac is on Wi-Fi it answers "on-wifi" and nothing switches
            "com.osbendes.phonedrop.DIAG_DIRECT" -> thread {
                val d = DirectLink(this, link.prefs)
                val result = try {
                    val (h, p) = d.connect { android.util.Log.i("PhoneDrop", "diag: " + getString(it)) }
                    val c = com.phonedrop.app.core.MacClient("http://$h:$p", link.prefs.key!!)
                    val name = c.hello("diag").optString("name")
                    runCatching { c.post("/api/direct/end", timeoutMs = 3000) }
                    "ready: $h:$p, hello from $name"
                } catch (e: DirectLink.DirectException) {
                    "failed: " + getString(e.reason)
                } finally {
                    d.close()
                }
                android.util.Log.i("PhoneDrop", "diag: $result")
                runOnUiThread { Toast.makeText(this, result, Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { pairWith(it.toString()) }
        runDiagnostics(intent)
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        findViewById<View>(R.id.batteryCard).visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        renderMac()
        refreshMac()
        handler.post(tick)
        askDirectPermissions()
    }

    /** Asks once, after pairing, for what direct mode needs. Denying only disables that fallback. */
    private fun askDirectPermissions() {
        val sp = getSharedPreferences("ui", MODE_PRIVATE)
        if (!link.prefs.paired || DirectLink.hasPermissions(this) || sp.getBoolean("directAsked", false)) return
        sp.edit().putBoolean("directAsked", true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.direct_perm_title)
            .setMessage(R.string.direct_perm_msg)
            .setPositiveButton(R.string.direct_perm_allow) { _, _ -> requestPermissions(DirectLink.permissions(), 2) }
            .setNegativeButton(R.string.direct_perm_later, null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    // ---------------- pairing ----------------

    private fun showPairDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_dialog_title)
            .setMessage(R.string.pair_dialog_msg)
            .setPositiveButton(R.string.pair_scan) { _, _ -> scan() }
            .setNeutralButton(R.string.pair_get_mac) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MAC_DOWNLOAD_URL)))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMacMenu() {
        val name = link.prefs.macName ?: "Mac"
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf(getString(R.string.pair_scan), getString(R.string.pair_unpair))) { _, which ->
                if (which == 0) scan()
                else AlertDialog.Builder(this)
                    .setMessage(getString(R.string.unpair_confirm, name))
                    .setPositiveButton(R.string.pair_unpair) { _, _ ->
                        link.prefs.unpair()
                        link = MacLink(this)
                        macOnline = null
                        renderMac()
                        renderOutbox(null, null)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .show()
    }

    /** Google's code scanner: runs in Play services, needs no camera permission. */
    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { pairWith(it) } }
            .addOnFailureListener { Toast.makeText(this, R.string.toast_scanner_unavailable, Toast.LENGTH_LONG).show() }
    }

    private fun pairWith(text: String) {
        if (!text.startsWith("phonedrop:")) return
        val info = PairInfo.parse(text)
        if (info == null) {
            Toast.makeText(this, R.string.toast_bad_qr, Toast.LENGTH_LONG).show()
            return
        }
        link.prefs.pair(info)
        link = MacLink(this)
        macOnline = null
        renderMac()
        thread {
            val ok = runCatching { link.client() }.isSuccess
            runOnUiThread {
                macOnline = ok
                renderMac()
                val name = link.prefs.macName ?: info.name
                Toast.makeText(this, getString(if (ok) R.string.toast_paired else R.string.toast_paired_offline, name),
                    Toast.LENGTH_LONG).show()
                if (ok) refreshMac()
            }
        }
    }

    private fun renderMac() {
        val name = findViewById<TextView>(R.id.macName)
        val status = findViewById<TextView>(R.id.macStatus)
        val btn = findViewById<Button>(R.id.pairBtn)
        val paired = link.prefs.paired
        findViewById<View>(R.id.pickGallery).isEnabled = paired
        findViewById<View>(R.id.pickFiles).isEnabled = paired
        findViewById<View>(R.id.pickGallery).alpha = if (paired) 1f else 0.5f
        findViewById<View>(R.id.pickFiles).alpha = if (paired) 1f else 0.5f
        if (!paired) {
            name.setText(R.string.mac_not_paired)
            status.setText(R.string.mac_not_paired_hint)
            status.setTextColor(getColor(R.color.mute))
            btn.setText(R.string.btn_pair)
            return
        }
        name.text = link.prefs.macName ?: "Mac"
        btn.setText(R.string.btn_change)
        status.setText(when (macOnline) {
            true -> R.string.mac_connected
            false -> offlineReason
            null -> R.string.mac_searching
        })
        status.setTextColor(getColor(when (macOnline) { true -> R.color.ok; false -> R.color.warn; null -> R.color.mute }))
    }

    /** Checks the Mac is reachable and lists its "Send to Phone" files. */
    private fun refreshMac() {
        if (!link.prefs.paired) { renderOutbox(null, null); return }
        thread {
            val result = runCatching { link.client().outbox() }
            if (result.isFailure) offlineReason = link.diagnose()
            runOnUiThread {
                macOnline = result.isSuccess
                renderMac()
                renderOutbox(result.isSuccess, result.getOrNull())
            }
        }
    }

    // ---------------- picking files ----------------

    private fun pickGallery() {
        val photoPicker = Build.VERSION.SDK_INT >= 33 ||
            (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)
        val i = if (photoPicker) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 10)
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        @Suppress("DEPRECATION")
        startActivityForResult(i, 11)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        TransferService.upload(this, uris)
    }

    // ---------------- transfer list ----------------

    private fun renderTransfers() {
        val box = findViewById<LinearLayout>(R.id.transfers)
        val items = Transfers.items.toList()
        findViewById<View>(R.id.transfersHeader).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.transfersAction).setText(
            if (Transfers.active().isNotEmpty()) R.string.action_stop else R.string.action_clear)
        val ls = findViewById<TextView>(R.id.linkStatus)
        val warn = Transfers.linkStatus.takeIf { Transfers.active().isNotEmpty() }
        ls.visibility = if (warn == null) View.GONE else View.VISIBLE
        ls.text = warn

        val keys = items.map { it.key }.toSet()
        rows.keys.filter { it !in keys }.forEach { box.removeView(rows.remove(it)!!.root) }
        for (it in items) {
            val row = rows.getOrPut(it.key) { newRow().also { r -> box.addView(r.root, 0) } }
            row.name.text = (if (it.direction == Transfers.Direction.UP) "↑ " else "↓ ") + it.name
            val pct = if (it.size > 0) (it.sent.get() * 1000 / it.size).toInt() else 0
            row.bar.progress = if (it.state == Transfers.State.DONE) 1000 else pct
            row.bar.progressTintList = getColorStateList(when (it.state) {
                Transfers.State.DONE -> R.color.ok
                Transfers.State.FAILED -> R.color.err
                else -> R.color.acc
            })
            val size = Fmt.bytes(this, it.size)
            row.status.text = when (it.state) {
                Transfers.State.WAITING -> getString(R.string.st_waiting, size)
                Transfers.State.ACTIVE -> getString(R.string.st_active, Fmt.bytes(this, it.sent.get()), size) +
                    (if (it.resumed) getString(R.string.st_resumed) else "")
                Transfers.State.DONE -> getString(
                    if (it.direction == Transfers.Direction.UP) R.string.st_done_up else R.string.st_done_down, size)
                Transfers.State.FAILED -> getString(R.string.st_failed, it.error ?: "")
                Transfers.State.CANCELLED -> getString(R.string.st_cancelled)
            }
            row.speed.text = if (it.state == Transfers.State.ACTIVE && it.speed > 1) {
                val eta = Fmt.eta(this, (it.size - it.sent.get()) / it.speed)
                "${Fmt.bytes(this, it.speed.toLong())}/s" + (if (eta.isEmpty()) "" else " · $eta")
            } else ""
        }
    }

    private fun newRow(): Row {
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
        }
        val name = TextView(this).apply {
            setTextColor(getColor(R.color.fg)); textSize = 15f; setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE
            paint.isFakeBoldText = true
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(-1, (6 * d).toInt()).apply { topMargin = (8 * d).toInt(); bottomMargin = (6 * d).toInt() }
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val status = TextView(this).apply {
            setTextColor(getColor(R.color.mute)); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val speed = TextView(this).apply { setTextColor(getColor(R.color.mute)); textSize = 13f }
        line.addView(status); line.addView(speed)
        root.addView(name); root.addView(bar); root.addView(line)
        return Row(root, name, bar, status, speed)
    }

    // ---------------- from the Mac ----------------

    private fun renderOutbox(reachable: Boolean?, json: JSONObject?) {
        val box = findViewById<LinearLayout>(R.id.outbox)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        fun note(res: Int) = box.addView(TextView(this).apply { setText(res); setTextColor(getColor(R.color.mute)); textSize = 14f })
        if (!link.prefs.paired) { note(R.string.outbox_unpaired); return }
        if (reachable != true || json == null) { note(R.string.outbox_offline); return }
        val files = json.getJSONArray("files")
        if (files.length() == 0) { note(R.string.outbox_empty); return }
        if (files.length() > 1) {
            box.addView(Button(this).apply {
                setText(getString(R.string.outbox_download_all, files.length()))
                setBackgroundResource(R.drawable.bg_btn_primary)
                setTextColor(0xFFFFFFFF.toInt())
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(-1, (52 * d).toInt()).apply { bottomMargin = (8 * d).toInt() }
                setOnClickListener {
                    for (k in 0 until files.length()) {
                        val f = files.getJSONObject(k)
                        TransferService.download(this@MainActivity, f.getString("id"), f.getString("name"), f.getLong("size"))
                    }
                    Toast.makeText(this@MainActivity, getString(R.string.toast_downloading_all, files.length()), Toast.LENGTH_SHORT).show()
                }
            })
        }
        for (k in 0 until files.length()) {
            val f = files.getJSONObject(k)
            val id = f.getString("id")
            val name = f.getString("name")
            val size = f.getLong("size")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
                setOnClickListener {
                    TransferService.download(this@MainActivity, id, name, size)
                    Toast.makeText(this@MainActivity, getString(R.string.toast_downloading, name), Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(TextView(this).apply {
                text = name; setTextColor(getColor(R.color.fg)); textSize = 15f; setSingleLine()
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(this).apply { text = "${Fmt.bytes(this@MainActivity, size)}  ↓"; setTextColor(getColor(R.color.acc)); textSize = 14f })
            box.addView(row)
        }
    }
}
