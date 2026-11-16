package com.phonedrop.app.core

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * X25519 key agreement (RFC 7748) on BigInteger. Android only ships it from API 31, and the keys here are
 * used for one session, so a small portable version is enough.
 *
 * TR: BigInteger üzerinde X25519 anahtar anlaşması (RFC 7748). Android bunu ancak API 31'den itibaren
 * sunuyor; buradaki anahtarlar tek oturumluk olduğundan küçük ve taşınabilir bir sürüm yeterli.
 */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun newPrivate(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun public(private: ByteArray): ByteArray = shared(private, BASE)

    /**
     * The shared secret of our private key and their public key. All zeros means a bad public key.
     *
     * TR: Bizim özel anahtarımız ile karşı tarafın açık anahtarının ortak sırrı. Tamamı sıfırsa açık
     * anahtar geçersizdir.
     */
    fun shared(private: ByteArray, public: ByteArray): ByteArray {
        require(private.size == 32 && public.size == 32) { "keys must be 32 bytes" }
        val s = private.copyOf()
        s[0] = (s[0].toInt() and 248).toByte()
        s[31] = ((s[31].toInt() and 127) or 64).toByte()
        val k = BigInteger(1, s.reversedArray())
        val u = public.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = BigInteger(1, u.reversedArray()).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = k.testBit(t)
            if (swap != bit) { val x = x2; x2 = x3; x3 = x; val z = z2; z2 = z3; z3 = z }
            swap = bit
            val a = (x2 + z2).mod(P); val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P); val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P); val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P); val cb = (c * b).mod(P)
            x3 = (da + cb).mod(P).let { (it * it).mod(P) }
            z3 = (da - cb).mod(P).let { (x1 * ((it * it).mod(P))).mod(P) }
            x2 = (aa * bb).mod(P)
            z2 = (e * ((aa + A24 * e).mod(P))).mod(P)
        }
        if (swap) { x2 = x3; z2 = z3 }
        val r = (x2 * z2.modPow(P - BigInteger.valueOf(2), P)).mod(P).toByteArray().reversedArray()
        return ByteArray(32).also { System.arraycopy(r, 0, it, 0, minOf(32, r.size)) }
    }

    /**
     * HKDF-SHA256 (RFC 5869), one 32-byte block.
     *
     * TR: HKDF-SHA256 (RFC 5869), 32 baytlık tek blok.
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray) =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        return hmac(hmac(salt, ikm), info.toByteArray() + 1)
    }
}

/**
 * ChaCha20-Poly1305 boxes (RFC 8439): nonce(12) || ciphertext || tag(16). Used with browsers, which have no
 * built-in AES on plain-HTTP pages; the page carries its own ChaCha20.
 *
 * TR: ChaCha20-Poly1305 kutuları (RFC 8439): nonce(12) || şifreli metin || etiket(16). Tarayıcılarla
 * kullanılır; düz HTTP sayfalarında yerleşik AES bulunmadığından sayfa kendi ChaCha20'sini taşır.
 */
class Box(key: ByteArray) {
    private val spec = SecretKeySpec(key, "ChaCha20")
    private val random = SecureRandom()

    init {
        require(key.size == 32) { "key must be 32 bytes" }
    }

    fun seal(plain: ByteArray, aad: String, off: Int = 0, len: Int = plain.size): ByteArray {
        val nonce = ByteArray(Crypto.NONCE).also { random.nextBytes(it) }
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(nonce))
        c.updateAAD(aad.toByteArray())
        val out = ByteArray(Crypto.OVERHEAD + len)
        System.arraycopy(nonce, 0, out, 0, Crypto.NONCE)
        c.doFinal(plain, off, len, out, Crypto.NONCE)
        return out
    }

    /**
     * Throws [javax.crypto.AEADBadTagException] if the box was tampered with.
     *
     * TR: Kutu kurcalanmışsa [javax.crypto.AEADBadTagException] fırlatır.
     */
    fun open(box: ByteArray, aad: String): ByteArray {
        require(box.size >= Crypto.OVERHEAD) { "box too short" }
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(box, 0, Crypto.NONCE))
        c.updateAAD(aad.toByteArray())
        return c.doFinal(box, Crypto.NONCE, box.size - Crypto.NONCE)
    }
}
