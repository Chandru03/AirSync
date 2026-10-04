package com.example.airsync.data.bluetooth

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Bluetooth Core Spec primitives used to recognise and read our own AirPods' BLE adverts.
 *
 * AirPods advertise from a Resolvable Private Address that changes every ~15 min. With the IRK
 * they hand us over AAP we can verify an RPA (`hash == ah(IRK, prand)`), and with the encryption
 * key we can read the battery block the newer firmware encrypts (AES-128, one block).
 */
object BleCrypto {

    /** `address` as "AA:BB:CC:DD:EE:FF". True if it is an RPA generated from [irk]. */
    fun verifyRpa(address: String, irk: ByteArray): Boolean {
        if (irk.size != 16) return false
        val parts = address.split(":")
        if (parts.size != 6) return false
        // Little-endian byte order, as the spec defines the address.
        val rpa = runCatching { ByteArray(6) { parts[5 - it].toInt(16).toByte() } }.getOrNull() ?: return false
        // Two most-significant bits of an RPA are 01.
        if ((rpa[5].toInt() and 0xC0) != 0x40) return false
        val prand = rpa.copyOfRange(3, 6)
        val hash = rpa.copyOfRange(0, 3)
        return ah(irk, prand).contentEquals(hash)
    }

    /** Spec function `ah`: first 3 bytes of e(k, padding || r). */
    fun ah(k: ByteArray, r: ByteArray): ByteArray {
        val rPadded = ByteArray(16)
        r.copyInto(rPadded, 0, 0, 3)
        return e(k, rPadded).copyOfRange(0, 3)
    }

    /** Spec function `e` (AES-128 with the spec's little-endian byte convention). */
    fun e(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.reversedArray(), "AES"))
        return cipher.doFinal(data.reversedArray()).reversedArray()
    }

    /** Decrypts the trailing 16-byte block of an advert payload (AES-128-ECB, no byte swapping). */
    fun decryptLastBlock(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size < 16 || key.size != 16) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(data.copyOfRange(data.size - 16, data.size))
        }.getOrNull()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
