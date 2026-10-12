package com.phonedrop.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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

/**
 * The only screen: pairing, picking files, the destination folder, the transfer list and the files the Mac
 * offers.
 *
 * TR: Tek ekran: eşleştirme, dosya seçme, hedef klasör, aktarım listesi ve Mac'in sunduğu dosyalar.
 */
class MainActivity : Activity() {

    companion object {
        /**
         * Download page with the Mac app; short enough to type on the Mac.
         *
         * TR: Mac uygulamasının indirme sayfası; Mac'te elle yazılabilecek kadar kısa.
         */
        const val MAC_DOWNLOAD_URL = "https://osbendes.github.io/phonedrop/"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var link: MacLink
    private val rows = HashMap<Int, Row>()
    @Volatile private var macOnline: Boolean? = null
    @Volatile private var offlineReason = R.string.mac_offline
    private val finishedDownloads = HashSet<Int>()

    /**
     * Screenshot mode, set by [Diagnostics]: null = normal, false = looks unpaired, true = paired with a
     * sample Mac. The real Mac isn't contacted while it is set.
     *
     * TR: Ekran görüntüsü modu, [Diagnostics] ayarlar: null = normal, false = eşleşmemiş görünür, true =
     * örnek bir Mac ile eşleşmiş. Ayarlıyken gerçek Mac'e bağlanılmaz.
     */
    internal var demo: Boolean? = null
    private val paired get() = demo ?: link.prefs.paired

    private class Row(val root: View, val name: TextView, val bar: ProgressBar, val status: TextView, val speed: TextView, val plate: TextView)

    private val tick = object : Runnable {
        override fun run() {
            renderTransfers()
            handler.postDelayed(this, 500)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        applyOverrideConfiguration(Look.config(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        link = MacLink(this)

        findViewById<Button>(R.id.pairBtn).setOnClickListener { if (paired) showMacMenu() else showPairDialog() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.pickGallery).setOnClickListener { pickGallery() }
        findViewById<Button>(R.id.pickFiles).setOnClickListener { pickFiles() }
        findViewById<View>(R.id.outboxRefresh).setOnClickListener { refreshMac() }
        findViewById<View>(R.id.destBtn).setOnClickListener { showFolderDialog() }
        findViewById<View>(R.id.getMacApp).setOnClickListener { showMacAppDialog() }
        findViewById<View>(R.id.batteryBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<View>(R.id.transfersAction).setOnClickListener { TransferService.stopAll(this) }
        // Removes finished, failed and stopped rows; anything still running stays
        findViewById<View>(R.id.transfersClear).setOnClickListener { Transfers.clearFinished(); renderTransfers() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Not after a restart of the screen (rotation, new theme): the code was handled the first time
        if (savedInstanceState == null) {
            intent?.data?.let { pairWith(it.toString()) }
            Diagnostics.run(this, intent, link.prefs)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { pairWith(it.toString()) }
        Diagnostics.run(this, intent, link.prefs)
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        findViewById<View>(R.id.batteryCard).visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        renderMac()
        renderDest()
        refreshMac()
        handler.post(tick)
        askDirectPermissions()
        Wake.register(this)
    }

    /**
     * Asks once, after pairing, for what direct mode needs. Denying only disables that fallback.
     *
     * TR: Eşleştirmeden sonra, doğrudan modun ihtiyaç duyduğu izinleri bir kez sorar. Reddetmek yalnızca o
     * yedek yolu kapatır.
     */
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

    // ---------------- settings ----------------

    /**
     * Language and theme. A change restarts the screen so everything is drawn again.
     *
     * TR: Dil ve tema. Değişiklik, her şey yeniden çizilsin diye ekranı baştan başlatır.
     */
    internal fun showSettings() {
        val sp = getSharedPreferences(Look.PREFS, MODE_PRIVATE)
        val system = getString(R.string.opt_system)
        val langs = listOf("" to system, "en" to "English", "tr" to "Türkçe")
        val themes = listOf("" to system, "light" to getString(R.string.opt_light), "dark" to getString(R.string.opt_dark))
        val titles = listOf(getString(R.string.set_language), getString(R.string.set_theme))
        fun current(key: String, list: List<Pair<String, String>>) = list.indexOfFirst { it.first == sp.getString(key, "") }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(arrayOf("${titles[0]}: ${langs[current(Look.LANG, langs)].second}",
                "${titles[1]}: ${themes[current(Look.THEME, themes)].second}")) { _, which ->
                val key = if (which == 0) Look.LANG else Look.THEME
                val list = if (which == 0) langs else themes
                AlertDialog.Builder(this)
                    .setTitle(titles[which])
                    .setSingleChoiceItems(list.map { it.second }.toTypedArray(), current(key, list)) { d, i ->
                        sp.edit().putString(key, list[i].first).apply()
                        d.dismiss()
                        recreate()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- pairing ----------------

    internal fun showPairDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_dialog_title)
            .setMessage(R.string.pair_dialog_msg)
            .setPositiveButton(R.string.pair_scan) { _, _ -> scan() }
            .setNeutralButton(R.string.pair_get_mac) { _, _ -> showMacAppDialog() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * The Mac app's download page as a QR code, with share and copy.
     *
     * TR: Mac uygulamasının indirme sayfası karekod olarak; paylaşma ve kopyalama seçenekleriyle.
     */
    internal fun showMacAppDialog() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), 0)
        }
        box.addView(TextView(this).apply { setText(R.string.mac_app_msg); textSize = 15f })
        qrBitmap(MAC_DOWNLOAD_URL, (220 * d).toInt())?.let { bmp ->
            box.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams((220 * d).toInt(), (220 * d).toInt()).apply { topMargin = (16 * d).toInt() }
            })
        }
        box.addView(TextView(this).apply {
            text = MAC_DOWNLOAD_URL.removePrefix("https://").trimEnd('/')
            textSize = 17f; paint.isFakeBoldText = true; setTextIsSelectable(true)
            setPadding(0, (12 * d).toInt(), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_get_mac)
            .setView(box)
            .setPositiveButton(R.string.share_link) { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, MAC_DOWNLOAD_URL), null))
            }
            .setNeutralButton(R.string.copy_link) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("PhoneDrop", MAC_DOWNLOAD_URL))
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun qrBitmap(text: String, size: Int): android.graphics.Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val scale = maxOf(1, size / m.width)
        val px = IntArray(m.width * scale * m.height * scale)
        val w = m.width * scale
        for (y in 0 until m.height * scale) for (x in 0 until w) {
            px[y * w + x] = if (m[x / scale, y / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        android.graphics.Bitmap.createBitmap(px, w, m.height * scale, android.graphics.Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }

    // ---------------- destination folder ----------------

    private fun renderDest() {
        val folder = if (demo == null) link.prefs.folder else ""
        findViewById<TextView>(R.id.destFolder).text = folder.ifEmpty { getString(R.string.dest_default) }
    }

    /**
     * Lets the user type a subfolder or pick one that already exists on the Mac.
     *
     * TR: Kullanıcının bir alt klasör yazmasını ya da Mac'te zaten var olan birini seçmesini sağlar.
     */
    private fun showFolderDialog() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        box.addView(TextView(this).apply { setText(R.string.dest_dialog_msg); textSize = 14f })
        val input = android.widget.EditText(this).apply {
            setText(link.prefs.folder); setHint(R.string.dest_dialog_hint); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(input)
        val existing = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(android.widget.ScrollView(this).apply {
            addView(existing)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dest_dialog_title)
            .setView(box)
            .setPositiveButton(R.string.dest_save) { _, _ ->
                link.prefs.folder = input.text.toString().trim().trim('/')
                renderDest()
            }
            .setNeutralButton(R.string.dest_none) { _, _ -> link.prefs.folder = ""; renderDest() }
            .setNegativeButton(R.string.cancel, null)
            .show()
        if (!link.prefs.paired) return
        thread {
            val names = runCatching {
                val arr = link.client().folders().getJSONArray("folders")
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (!dialog.isShowing || names.isEmpty()) return@runOnUiThread
                existing.addView(TextView(this).apply {
                    setText(R.string.dest_existing); setTextColor(getColor(R.color.mute)); textSize = 13f
                    setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
                })
                for (n in names.take(12)) existing.addView(TextView(this).apply {
                    text = n; textSize = 16f; setTextColor(getColor(R.color.link))
                    setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                    setOnClickListener { input.setText(n); input.setSelection(n.length) }
                })
            }
        }
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

    /**
     * Google's code scanner: runs in Play services, needs no camera permission.
     *
     * TR: Google'ın kod tarayıcısı: Play hizmetlerinde çalışır, kamera izni gerektirmez.
     */
    fun scan() {
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

    internal fun renderMac() {
        val name = findViewById<TextView>(R.id.macName)
        val status = findViewById<TextView>(R.id.macStatus)
        val btn = findViewById<Button>(R.id.pairBtn)
        if (demo != null) macOnline = true
        // Unpaired, the card needs the room for its two lines of text
        findViewById<View>(R.id.macIcon).visibility = if (paired) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pickGallery).isEnabled = paired
        findViewById<View>(R.id.pickFiles).isEnabled = paired
        findViewById<View>(R.id.pickGallery).alpha = if (paired) 1f else 0.5f
        findViewById<View>(R.id.pickFiles).alpha = if (paired) 1f else 0.5f
        if (!paired) {
            name.setText(R.string.mac_not_paired)
            status.setText(R.string.mac_not_paired_hint)
            status.setTextColor(getColor(R.color.mute))
            status.background = null
            status.setPadding(0, 0, 0, 0)
            btn.setText(R.string.btn_pair)
            return
        }
        name.text = if (demo == true) "MacBook" else link.prefs.macName ?: "Mac"
        btn.setText(R.string.btn_change)
        status.setText(when (macOnline) {
            true -> R.string.mac_connected
            false -> offlineReason
            null -> R.string.mac_searching
        })
        status.setTextColor(getColor(when (macOnline) { true -> R.color.on_ok; false -> R.color.warn; null -> R.color.mute }))
        // "Connected" sits in a mint pill; the other states are plain text
        val d = resources.displayMetrics.density
        if (macOnline == true) {
            status.setBackgroundResource(R.drawable.bg_chip)
            status.setPadding((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt())
        } else {
            status.background = null
            status.setPadding(0, 0, 0, 0)
        }
    }

    /**
     * Checks the Mac is reachable and lists its "Send to Phone" files.
     *
     * TR: Mac'e ulaşılabildiğini kontrol eder ve "Telefona Gönder" dosyalarını listeler.
     */
    private fun refreshMac() {
        if (demo != null) return
        if (!link.prefs.paired) { renderOutbox(null, null); return }
        thread {
            val result = runCatching { link.client().outbox() }
            if (result.isFailure) offlineReason = link.diagnose()
            runOnUiThread {
                if (demo != null) return@runOnUiThread
                macOnline = result.isSuccess
                renderMac()
                renderOutbox(result.isSuccess, result.getOrNull())
                // "Send" was pressed on the Mac but the Bluetooth wake-up didn't reach us: fetch now
                val json = result.getOrNull()
                if (json != null && json.optBoolean("send") && json.getJSONArray("files").length() > 0 &&
                    Transfers.active().isEmpty()) {
                    TransferService.fetch(this)
                }
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
        val running = Transfers.active().size
        findViewById<View>(R.id.transfersAction).visibility = if (running > 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.transfersClear).visibility = if (items.size > running) View.VISIBLE else View.GONE
        val ls = findViewById<TextView>(R.id.linkStatus)
        val warn = Transfers.linkStatus.takeIf { Transfers.active().isNotEmpty() }
        ls.visibility = if (warn == null) View.GONE else View.VISIBLE
        ls.text = warn

        // A finished download drops off the Mac's list; refresh ours to match
        val newlyDone = items.filter { it.direction == Transfers.Direction.DOWN && it.state == Transfers.State.DONE }
            .map { it.key }.filter { finishedDownloads.add(it) }
        if (newlyDone.isNotEmpty()) refreshMac()

        val keys = items.map { it.key }.toSet()
        rows.keys.filter { it !in keys }.forEach { box.removeView(rows.remove(it)!!.root) }
        for (it in items) {
            val row = rows.getOrPut(it.key) { newRow().also { r -> box.addView(r.root, 0) } }
            row.name.text = (if (it.direction == Transfers.Direction.UP) "↑ " else "↓ ") + it.name
            setType(row.plate, it.name)
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
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
        }
        val plate = typePlate()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val name = TextView(this).apply {
            setTextColor(getColor(R.color.fg)); textSize = 15f; setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE
            paint.isFakeBoldText = true
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = getDrawable(R.drawable.progress)
            layoutParams = LinearLayout.LayoutParams(-1, (6 * d).toInt()).apply { topMargin = (8 * d).toInt(); bottomMargin = (6 * d).toInt() }
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val status = TextView(this).apply {
            setTextColor(getColor(R.color.mute)); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val speed = TextView(this).apply { setTextColor(getColor(R.color.mute)); textSize = 13f }
        line.addView(status); line.addView(speed)
        column.addView(name); column.addView(bar); column.addView(line)
        root.addView(plate); root.addView(column)
        return Row(root, name, bar, status, speed, plate)
    }

    /**
     * The small pastel square that shows a file's type (its extension, coloured by kind).
     *
     * TR: Dosyanın türünü gösteren küçük pastel kare (uzantısı, türüne göre renklenir).
     */
    private fun typePlate(): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 11f; paint.isFakeBoldText = true; setTextColor(0xFF2B2A28.toInt())
            setBackgroundResource(R.drawable.bg_plate)
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (12 * d).toInt() }
        }
    }

    private fun setType(plate: TextView, fileName: String) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        plate.text = ext.take(4).uppercase()
        plate.backgroundTintList = getColorStateList(when (ext) {
            "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "dng", "raw", "bmp", "svg" -> R.color.type_image
            "mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v" -> R.color.type_video
            "mp3", "wav", "m4a", "flac", "ogg", "aac", "opus" -> R.color.type_audio
            "zip", "rar", "7z", "tar", "gz", "apk", "dmg", "iso" -> R.color.type_archive
            else -> R.color.type_doc
        })
    }

    // ---------------- from the Mac ----------------

    internal fun renderOutbox(reachable: Boolean?, json: JSONObject?) {
        val box = findViewById<LinearLayout>(R.id.outbox)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        fun note(res: Int) = box.addView(TextView(this).apply { setText(res); setTextColor(getColor(R.color.mute)); textSize = 14f })
        if (!paired) { note(R.string.outbox_unpaired); return }
        if (reachable != true || json == null) { note(R.string.outbox_offline); return }
        val files = json.getJSONArray("files")
        if (files.length() == 0) { note(R.string.outbox_empty); return }
        if (files.length() > 1) {
            box.addView(Button(this).apply {
                setText(getString(R.string.outbox_download_all, files.length()))
                setBackgroundResource(R.drawable.bg_btn_primary)
                setTextColor(getColor(R.color.on_acc))
                isAllCaps = false
                stateListAnimator = null
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
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
                setOnClickListener {
                    TransferService.download(this@MainActivity, id, name, size)
                    Toast.makeText(this@MainActivity, getString(R.string.toast_downloading, name), Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(typePlate().also { setType(it, name) })
            row.addView(TextView(this).apply {
                text = name; setTextColor(getColor(R.color.fg)); textSize = 15f; setSingleLine()
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(this).apply { text = "${Fmt.bytes(this@MainActivity, size)}  ↓"; setTextColor(getColor(R.color.link)); textSize = 14f; paint.isFakeBoldText = true })
            box.addView(row)
        }
    }
}
