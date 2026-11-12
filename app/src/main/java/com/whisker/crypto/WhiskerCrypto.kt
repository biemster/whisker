package com.whisker.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * Whisker End-to-End Encryption (E2EE) Engine for Android.
 *
 * Implements:
 * 1. RFC 7748 X25519 ECDH key agreement and keypair generation.
 * 2. RFC 5869 HKDF-SHA256 symmetric key derivation.
 * 3. AES-256-GCM authenticated encryption and on-demand decryption.
 */
object WhiskerCrypto {
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT: ByteArray = ByteArray(32).apply { this[0] = 9 }

    private fun clamp(k: ByteArray): ByteArray {
        val copy = k.copyOf(32)
        copy[0] = (copy[0].toInt() and 248).toByte()
        copy[31] = (copy[31].toInt() and 127 or 64).toByte()
        return copy
    }

    private fun bytesToBigInt(bytes: ByteArray): BigInteger {
        val rev = ByteArray(bytes.size + 1)
        for (i in bytes.indices) {
            rev[bytes.size - i] = bytes[i]
        }
        return BigInteger(rev)
    }

    private fun bigIntToBytes(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        val out = ByteArray(32)
        val len = minOf(32, raw.size)
        for (i in 0 until len) {
            out[i] = raw[raw.size - 1 - i]
        }
        return out
    }

    fun x25519(scalarBytes: ByteArray, uBytes: ByteArray): ByteArray {
        val k = bytesToBigInt(clamp(scalarBytes))
        val u = bytesToBigInt(uBytes).mod(P)
        val x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).mod(P).modPow(BigInteger.valueOf(2), P)
            z3 = x1.multiply(da.subtract(cb).mod(P).modPow(BigInteger.valueOf(2), P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }

        if (swap != 0) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        val res = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
        return bigIntToBytes(res)
    }

    fun generateKeyPair(): Pair<ByteArray, String> {
        val priv = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val pub = x25519(priv, BASE_POINT)
        val pubB64 = Base64.encodeToString(pub, Base64.NO_WRAP)
        return Pair(priv, pubB64)
    }

    fun getPublicKeyB64(privKey: ByteArray): String {
        val pub = x25519(privKey, BASE_POINT)
        return Base64.encodeToString(pub, Base64.NO_WRAP)
    }

    fun deriveSharedSecret(privKey: ByteArray, peerPubB64: String): ByteArray {
        val peerBytes = Base64.decode(peerPubB64.trim(), Base64.DEFAULT)
        if (peerBytes.size != 32) {
            throw IllegalArgumentException("Invalid X25519 public key length: ${peerBytes.size}")
        }
        return x25519(privKey, peerBytes)
    }

    fun deriveAesKey(sharedSecret: ByteArray): ByteArray {
        val salt = "whisker-e2ee-v1".toByteArray(Charsets.UTF_8)
        val info = "whisker-msg-aes256-gcm".toByteArray(Charsets.UTF_8)
        val macExtract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }
        val prk = macExtract.doFinal(sharedSecret)
        val macExpand = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(prk, "HmacSHA256"))
        }
        macExpand.update(info)
        macExpand.update(1.toByte())
        return macExpand.doFinal().copyOf(32)
    }

    fun encryptPayload(sharedSecret: ByteArray, plaintext: String): String {
        val key = deriveAesKey(sharedSecret)
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val cipherWithTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val envelope = ByteBuffer.allocate(12 + cipherWithTag.size)
            .put(iv)
            .put(cipherWithTag)
            .array()
        return Base64.encodeToString(envelope, Base64.NO_WRAP)
    }

    fun decryptPayload(sharedSecret: ByteArray, envelopeB64: String): String {
        val key = deriveAesKey(sharedSecret)
        val raw = Base64.decode(envelopeB64.trim(), Base64.DEFAULT)
        if (raw.size < 28) throw IllegalArgumentException("Corrupted E2EE envelope: too short")
        val iv = raw.copyOfRange(0, 12)
        val cipherWithTag = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(cipherWithTag)
        return String(plainBytes, Charsets.UTF_8)
    }

    fun encryptPayload(privKey: ByteArray, peerPubB64: String, plaintext: String): String {
        val sharedSecret = deriveSharedSecret(privKey, peerPubB64)
        return encryptPayload(sharedSecret, plaintext)
    }

    fun decryptPayload(privKey: ByteArray, peerPubB64: String, envelopeB64: String): String {
        val sharedSecret = deriveSharedSecret(privKey, peerPubB64)
        return decryptPayload(sharedSecret, envelopeB64)
    }
}
