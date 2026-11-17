// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.phone.core

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

fun Byte.u() = toInt() and 255

fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.hex() = joinToString("") { "%02x".format(it.u()) }

fun decode64(s: String): ByteArray {
    val normalized = s.replace('-', '+').replace('_', '/')
    val pad = (4 - normalized.length % 4) % 4
    return Base64.decode(normalized + "=".repeat(pad), Base64.DEFAULT)
}

fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

fun le32(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
fun be32(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
fun le64(n: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()

fun ByteArray.i32(at: Int, little: Boolean = false) =
    ByteBuffer.wrap(this, at, 4).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int

fun ByteArray.u32(at: Int) = i32(at).toLong() and 0xffffffffL

fun ByteArray.i64(at: Int, little: Boolean = false) =
    ByteBuffer.wrap(this, at, 8).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).long

fun digest(algorithm: String, value: ByteArray): ByteArray =
    MessageDigest.getInstance(algorithm).digest(value)

fun sm3(value: ByteArray): ByteArray = Sm3Native.hash(value)

fun aes(mode: String, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
    Cipher.getInstance("AES/$mode/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(data)
    }

fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].u() xor b[it % b.size].u()).toByte() }


/** Pure SM3 for Argus (same as hongguo-drama sm3.js). */
object Sm3Native {
    fun hash(source: ByteArray): ByteArray {
        val len = ((source.size + 9 + 63) / 64) * 64
        val data = ByteArray(len)
        System.arraycopy(source, 0, data, 0, source.size)
        data[source.size] = 0x80.toByte()
        val bitLen = source.size.toLong() * 8
        for (i in 0 until 8) data[data.size - 8 + i] = ((bitLen ushr ((7 - i) * 8)) and 0xff).toByte()
        val state = intArrayOf(
            0x7380166f.toInt(), 0x4914b2b9.toInt(), 0x172442d7.toInt(), 0xda8a0600.toInt(),
            0xa96f30bc.toInt(), 0x163138aa.toInt(), 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt()
        )
        val words = IntArray(68)
        fun rot(x: Int, n: Int): Int { val k = n and 31; return (x shl k) or (x ushr (32 - k)) }
        var offset = 0
        while (offset < data.size) {
            for (i in 0 until 16) {
                val o = offset + i * 4
                words[i] = ((data[o].toInt() and 0xff) shl 24) or ((data[o + 1].toInt() and 0xff) shl 16) or
                    ((data[o + 2].toInt() and 0xff) shl 8) or (data[o + 3].toInt() and 0xff)
            }
            for (i in 16 until 68) {
                val x = words[i - 16] xor words[i - 9] xor rot(words[i - 3], 15)
                words[i] = x xor rot(x, 15) xor rot(x, 23) xor rot(words[i - 13], 7) xor words[i - 6]
            }
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
            for (i in 0 until 64) {
                val a12 = rot(a, 12)
                val ss1 = rot(a12 + e + rot(if (i < 16) 0x79cc4519.toInt() else 0x7a879d8a.toInt(), i), 7)
                val ss2 = ss1 xor a12
                val ff = if (i < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (i < 16) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + (words[i] xor words[i + 4])
                val tt2 = gg + h + ss1 + words[i]
                d = c; c = rot(b, 9); b = a; a = tt1
                h = g; g = rot(f, 19); f = e
                e = tt2 xor rot(tt2, 9) xor rot(tt2, 17)
            }
            state[0] = state[0] xor a; state[1] = state[1] xor b; state[2] = state[2] xor c; state[3] = state[3] xor d
            state[4] = state[4] xor e; state[5] = state[5] xor f; state[6] = state[6] xor g; state[7] = state[7] xor h
            offset += 64
        }
        val digest = ByteArray(32)
        for (i in 0 until 8) {
            digest[i * 4] = (state[i] ushr 24).toByte()
            digest[i * 4 + 1] = (state[i] ushr 16).toByte()
            digest[i * 4 + 2] = (state[i] ushr 8).toByte()
            digest[i * 4 + 3] = state[i].toByte()
        }
        return digest
    }
}

fun rol(v: Int, n: Int) = Integer.rotateLeft(v, n)

fun ror(v: Int, n: Int) = Integer.rotateRight(v, n)
