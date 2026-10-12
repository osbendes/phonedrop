package com.phonedrop.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log

/**
 * "Send" on the Mac: the Mac flags its Bluetooth advertisement, and a background scan that Android
 * keeps running for us (even when the app isn't) wakes [WakeReceiver], which downloads the outbox.
 *
 * TR: Mac'te "Gönder": Mac Bluetooth yayınına işaret koyar; Android'in bizim için (uygulama çalışmasa
 * bile) sürdürdüğü arka plan taraması [WakeReceiver]'ı uyandırır, o da giden kutusunu indirir.
 */
object Wake {
    private const val TAG = "PhoneDrop"
    const val ACTION = "com.osbendes.phonedrop.WAKE"

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, WakeReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /**
     * Idempotent; call whenever the app runs. The scan stops if Bluetooth is turned off.
     *
     * TR: Tekrar çağrılması zararsızdır; uygulama her çalıştığında çağır. Bluetooth kapatılırsa tarama
     * durur.
     */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        val app = ctx.applicationContext
        if (!Prefs(app).paired || !DirectLink.hasPermissions(app)) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(DirectLink.WAKE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
            // Start from a clean slate: Android suspends scans while any app is connecting over
            // Bluetooth, and a scan that has been through that can stop delivering results.
            val scanner = adapter.bluetoothLeScanner ?: return
            runCatching { scanner.stopScan(pendingIntent(app)) }
            val rc = scanner.startScan(listOf(filter), settings, pendingIntent(app))
            Log.i(TAG, "wake: background scan registered ($rc)")
        } catch (e: Exception) {
            Log.w(TAG, "wake: can't register scan: $e")
        }
    }
}

/**
 * Receives the background-scan result, and the boot and app-update events after which the scan has to be
 * registered again.
 *
 * TR: Arka plan taramasının sonucunu ve taramanın yeniden kaydedilmesi gereken açılış ve uygulama
 * güncelleme olaylarını alır.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Wake.register(ctx)
            Wake.ACTION -> {
                if (!Prefs(ctx).paired) return
                // The Mac keeps advertising until we've fetched everything; act once per burst
                val sp = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (now - sp.getLong("lastWake", 0) < 20_000 || Transfers.active().isNotEmpty()) return
                sp.edit().putLong("lastWake", now).apply()
                Log.i("PhoneDrop", "wake: the Mac has files for us")
                try {
                    TransferService.fetch(ctx)
                } catch (e: Exception) {
                    // Android refused a background start: ask the user to open the app instead
                    Log.w("PhoneDrop", "wake: can't start the service: $e")
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(NotificationChannel("done", ctx.getString(R.string.notif_channel_done),
                        NotificationManager.IMPORTANCE_DEFAULT))
                    nm.notify(3, Notification.Builder(ctx, "done")
                        .setSmallIcon(R.drawable.ic_stat_upload)
                        .setContentTitle(ctx.getString(R.string.notif_mac_has_files))
                        .setContentText(ctx.getString(R.string.notif_tap_to_download))
                        .setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                        .build())
                }
            }
        }
    }
}
