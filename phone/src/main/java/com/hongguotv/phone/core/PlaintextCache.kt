package com.hongguotv.phone.core

import com.hongguotv.phone.AppLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载 CDN 密文 → spade_a 派生密钥 → MediaCrypto CENC 解密 → 本地明文 mp4。
 * 供本机播放与推流给 TV（TV 只收明文流）。
 */
object PlaintextCache {
    private val locks = ConcurrentHashMap<String, Any>()
    private var dir: File? = null

    @JvmStatic
    fun init(cacheDir: File) {
        val d = File(cacheDir, "plain")
        d.mkdirs()
        dir = d
    }

    @JvmStatic
    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }

    /**
     * 返回本地明文文件；未加密则直接缓存原文件。
     */
    @JvmStatic
    fun ensurePlainFile(url: String, spadeA: String?, encrypted: Boolean): File {
        val base = dir ?: throw IllegalStateException("PlaintextCache 未 init")
        val key = md5(url + "|" + (spadeA ?: ""))
        val out = File(base, "$key.mp4")
        if (out.exists() && out.length() > 64 * 1024) {
            AppLog.i("明文缓存命中 ${out.name} size=${out.length()}")
            return out
        }
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            if (out.exists() && out.length() > 64 * 1024) return out
            val encFile = File(base, "$key.enc")
            AppLog.i("开始下载 CDN → ${AppLog.clip(url, 70)} enc=$encrypted")
            download(url, encFile)
            AppLog.i("下载完成 size=${encFile.length()}")
            if (!encrypted || spadeA.isNullOrBlank()) {
                encFile.renameTo(out)
                AppLog.i("无加密，直接作明文")
                return out
            }
            decryptTo(encFile, out, spadeA)
            encFile.delete()
            AppLog.i("解密完成 plain=${out.length()}")
            return out
        }
    }

    private fun download(url: String, dest: File) {
        val uas = arrayOf(
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)",
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)",
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        )
        var last: Exception? = null
        for (ua in uas) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 120000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                AppLog.i("CDN 下载 HTTP $code ua=${AppLog.clip(ua, 36)}")
                if (code != 200 && code != 206) {
                    c.disconnect()
                    continue
                }
                c.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        var total = 0L
                        while (input.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            total += n
                        }
                        AppLog.i("CDN 写入 bytes=$total")
                    }
                }
                if (dest.length() > 1024) return
            } catch (e: Exception) {
                last = e
                AppLog.w("CDN 下载失败: ${e.message}")
            }
        }
        throw last ?: IllegalStateException("CDN 下载失败(403?)")
    }

    private fun decryptTo(enc: File, out: File, spadeA: String) {
        val key = MediaCrypto.deriveKey(spadeA)
        AppLog.i("spade 密钥 len=${key.size}")
        val data = enc.readBytes()
        if (data.size < 32) throw IllegalStateException("文件过小")
        // 取头部做 prepare
        val probe = minOf(data.size, 2 * 1024 * 1024)
        val head = data.copyOf(probe)
        val headerEnd = try {
            MediaCrypto.headerEnd(head, data.size.toLong())
        } catch (e: Exception) {
            // 可能整文件需读更多
            AppLog.w("headerEnd: ${e.message}, 用整文件")
            data.size
        }
        val headerBytes = if (headerEnd <= data.size) data.copyOf(headerEnd) else data
        val prepared = MediaCrypto.prepare(headerBytes, key)
        AppLog.i("prepare samples=${prepared.samples.size} header=${prepared.header.size}")
        // 全文件解密
        val working = data.copyOf()
        // 写入修补后的 header
        val hLen = minOf(prepared.header.size, working.size)
        System.arraycopy(prepared.header, 0, working, 0, hLen)
        val clear = MediaCrypto.decrypt(working, 0, working.size.toLong(), key, prepared)
        FileOutputStream(out).use { it.write(clear) }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
