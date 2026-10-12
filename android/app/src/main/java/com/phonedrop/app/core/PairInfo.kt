package com.phonedrop.app.core

import java.net.URI
import java.net.URLDecoder

/**
 * Contents of the Mac's pairing QR code: phonedrop://pair?v=2&k=..&h=..&p=..&n=..
 *
 * TR: Mac'in eşleştirme karekodunun içeriği: phonedrop://pair?v=2&k=..&h=..&p=..&n=..
 */
data class PairInfo(val key: ByteArray, val host: String, val port: Int, val name: String) {
    val kid get() = Crypto.kid(key)

    companion object {
        fun parse(text: String): PairInfo? = try {
            val u = URI(text.trim())
            if (u.scheme != "phonedrop" || u.host != "pair") null
            else {
                val q = (u.rawQuery ?: "").split("&").filter { "=" in it }.associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to URLDecoder.decode(v, "UTF-8")
                }
                val key = Crypto.unb64url(q.getValue("k"))
                if (q["v"] != "2" || key.size != 32) null
                else PairInfo(key, q.getValue("h"), q["p"]?.toIntOrNull() ?: 8765, q["n"] ?: "Mac")
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun equals(other: Any?) = other is PairInfo && key.contentEquals(other.key) && host == other.host && port == other.port
    override fun hashCode() = key.contentHashCode()
}
