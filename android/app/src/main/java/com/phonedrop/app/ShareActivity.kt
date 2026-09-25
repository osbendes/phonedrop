package com.phonedrop.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** Share-sheet target: hands the files to the background service and closes right away. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        when {
            uris.isEmpty() -> Toast.makeText(this, R.string.toast_no_files, Toast.LENGTH_SHORT).show()
            !Prefs(this).paired -> {
                Toast.makeText(this, R.string.toast_pair_first, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java))
            }
            else -> {
                TransferService.upload(this, uris)
                val msg = if (uris.size == 1) getString(R.string.toast_sending_one)
                else getString(R.string.toast_sending_many, uris.size)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun extractUris(i: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        }
        i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { out.add(it) } }
        return out.toList()
    }
}
