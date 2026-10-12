package com.phonedrop.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM boxes: nonce(12) || ciphertext || tag(16). See docs/PROTOCOL.md.
 *
 * TR: AES-256-GCM kutuları: nonce(12) || şifreli metin || etiket(16). Bkz. docs/PROTOCOL.md.
 */
class Crypto(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    init {
        require(key.size == 32) { "key must be 32 bytes" }
    }

    fun seal(plain: ByteArray, aad: String, off: Int = 0, len: Int = plain.size): ByteArray {
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce))
        c.updateAAD(aad.toByteArray())
        val out = ByteArray(NONCE + len + TAG)
        System.arraycopy(nonce, 0, out, 0, NONCE)
        c.doFinal(plain, off, len, out, NONCE)
        return out
    }

    /**
     * Throws [javax.crypto.AEADBadTagException] if the box was tampered with.
     *
     * TR: Kutu kurcalanmışsa [javax.crypto.AEADBadTagException] fırlatır.
     */
    fun open(box: ByteArray, aad: String, off: Int = 0, len: Int = box.size): ByteArray {
        require(len >= NONCE + TAG) { "box too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, box, off, NONCE))
        c.updateAAD(aad.toByteArray())
        return c.doFinal(box, off + NONCE, len - NONCE)
    }

    companion object {
        const val NONCE = 12
        const val TAG = 16
        const val OVERHEAD = NONCE + TAG

        fun kid(key: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest("phonedrop-kid".toByteArray() + key)
                .joinToString("") { "%02x".format(it) }.take(16)

        fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun unb64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    }
}
