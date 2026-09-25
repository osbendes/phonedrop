package com.phonedrop.app

import android.content.Context
import android.text.format.Formatter

object Fmt {
    fun bytes(ctx: Context, n: Long): String = Formatter.formatShortFileSize(ctx, n)

    fun eta(ctx: Context, sec: Double): String {
        if (!sec.isFinite() || sec <= 0) return ""
        val s = sec.toLong()
        return when {
            s < 60 -> ctx.getString(R.string.eta_s, s)
            s < 3600 -> ctx.getString(R.string.eta_ms, s / 60, s % 60)
            else -> ctx.getString(R.string.eta_hm, s / 3600, s % 3600 / 60)
        }
    }
}
