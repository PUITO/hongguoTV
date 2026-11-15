// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.phone.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 完整 App 取流：匿名设备注册（无需登录）→ PlayerSigner 签名 → video_model → 解析/解密 URL。
 * 流程对齐 N3urda/hongguoTV ContentRepository.playerLadder 兼容路径。
 */
object AppPlayback {
    data class Identity(val deviceId: String, val installId: String, val cdid: String)
    data class Stream(val url: String, val quality: String, val height: Int)

    private val cached = AtomicReference<Identity?>(null)

    @JvmStatic
    fun clearIdentity() { cached.set(null) }

    @JvmStatic
    fun obtainIdentity(): Identity {
        cached.get()?.let { return it }
        synchronized(this) {
            cached.get()?.let { return it }
            val id = registerDevice()
            cached.set(id)
            return id
        }
    }

    /** 取单集可播放地址列表（按清晰度）。 */
    @JvmStatic
    fun fetchStreams(vid: String): List<Stream> {
        val id = obtainIdentity()
        val streams = linkedMapOf<String, Stream>()
        // 1) shortplay video_model (aid=1967)
        runCatching { fetchShortplay(id, vid) }.getOrNull()?.forEach { streams[it.url] = it }
        // 2) phoenix multi_video_model (aid=8662)
        runCatching { fetchPhoenix(id, vid) }.getOrNull()?.forEach { streams[it.url] = it }
        return streams.values.toList()
    }

    @JvmStatic
    fun fetchBest(vid: String, preferred: String?): Stream? {
        val list = fetchStreams(vid)
        if (list.isEmpty()) return null
        val want = when (preferred) {
            "360p" -> 360
            "1080p" -> 1080
            "720p" -> 720
            else -> 720
        }
        return list.minByOrNull { kotlin.math.abs(it.height.let { h -> if (h > 0) h else parseH(it.quality) } - want) }
            ?: list.first()
    }

    private fun parseH(q: String): Int =
        Regex("(\\d{3,4})").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun registerDevice(): Identity {
        val cdid = UUID.randomUUID().toString()
        val openudid = List(16) { "0123456789abcdef".random() }.joinToString("")
        val device = mapOf(
            "cdid" to cdid,
            "openudid" to openudid,
            "device_model" to "MI 12",
            "os_version" to "13",
            "os_api" to "33",
            "resolution" to "1080x2400",
            "dpi" to "440",
            "aid" to "8662",
            "channel" to "update_64",
            "package" to "com.phoenix.read",
            "app_version" to "7.3.9.32",
            "version_code" to "73932",
        )
        val body = DeviceRegister.payload(device)
        val url = DeviceRegister.url()
        val headers = DeviceRegister.headers(body, device)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "POST"
            doOutput = true
            // 注册不要加签名头
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 13; zh_CN; MI 12)")
            // 避免 br 压缩
            setRequestProperty("Accept-Encoding", "identity")
        }
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val bytes = (if (code >= 400) conn.errorStream else conn.inputStream)?.readBytes() ?: ByteArray(0)
        val text = String(bytes, StandardCharsets.UTF_8)
        if (code >= 400) throw IllegalStateException("register HTTP $code $text")
        val registered = DeviceRegister.parse(text)
            ?: throw IllegalStateException("register parse fail: ${text.take(200)}")
        if (registered.deviceId.isBlank() || registered.installId.isBlank()) {
            throw IllegalStateException("register empty ids: ${text.take(200)}")
        }
        return Identity(registered.deviceId, registered.installId, cdid)
    }

    private fun fetchShortplay(id: Identity, vid: String): List<Stream> {
        val query = "iid=${id.installId}&device_id=${id.deviceId}&aid=1967&app_name=novelapp" +
            "&version_code=72132&version_name=7.2.1.32&device_platform=android&device_brand=Xiaomi" +
            "&os_version=13&cdid=${id.cdid}"
        val url = "https://api3-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/?$query"
        val body = JSONObject()
            .put("biz_param", JSONObject()
                .put("device_level", 3)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("video_platform", 1024))
            .put("video_id", vid)
            .toString().toByteArray(Charsets.UTF_8)
        return postModel(url, query, body)
    }

    private fun fetchPhoenix(id: Identity, vid: String): List<Stream> {
        val query = "iid=${id.installId}&device_id=${id.deviceId}&aid=8662&app_name=novelread" +
            "&version_code=71332&version_name=7.1.3.32&device_platform=android&os=android" +
            "&device_type=MI12&device_brand=Xiaomi&cdid=${id.cdid}" +
            "&need_all_video_definition=1&need_mp4_align=0&use_os_player=0&video_platform=1024"
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?$query"
        val body = JSONObject()
            .put("biz_param", JSONObject()
                .put("device_level", 3)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
            .toString().toByteArray(Charsets.UTF_8)
        return postModel(url, query, body)
    }

    private fun postModel(url: String, query: String, body: ByteArray): List<Stream> {
        val signed = PlayerSigner.headers(query, body)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "com.phoenix.read/71332 (Linux; U; Android 13; zh_CN)")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("sdk-version", "2")
            signed.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val bytes = (if (code >= 400) conn.errorStream else conn.inputStream)?.readBytes() ?: ByteArray(0)
        val text = String(bytes, Charsets.UTF_8)
        if (code >= 400 || text.isBlank()) throw IllegalStateException("play HTTP $code")
        val root = JSONObject(text)
        val biz = root.optInt("code", root.optInt("Code", 0))
        if (biz != 0) throw IllegalStateException("play code $biz")
        return parseStreams(root)
    }

    private fun parseStreams(root: JSONObject): List<Stream> {
        val out = ArrayList<Stream>()
        val data = root.optJSONObject("data") ?: root
        collectModel(data.opt("video_model"), out)
        val vmd = data.optJSONObject("video_model_data")
        if (vmd != null) {
            val keys = vmd.keys()
            while (keys.hasNext()) collectModel(vmd.opt(keys.next()), out)
        }
        val list = data.optJSONArray("video_model_list")
        if (list != null) for (i in 0 until list.length()) collectModel(list.opt(i), out)
        // some payloads embed video_model as string JSON
        return out.distinctBy { it.url }
    }

    private fun collectModel(raw: Any?, out: MutableList<Stream>) {
        if (raw == null) return
        try {
            val model = when (raw) {
                is JSONObject -> raw
                is String -> JSONObject(raw)
                else -> return
            }
            val keySeed = model.optString("key_seed").takeIf { it.isNotBlank() }?.let {
                runCatching { decode64(it) }.getOrNull()
            }
            val videoList = model.optJSONObject("video_list") ?: return
            val keys = videoList.keys()
            while (keys.hasNext()) {
                val item = videoList.optJSONObject(keys.next()) ?: continue
                val candidates = listOf(
                    item.optString("main_url"),
                    item.optString("backup_url"),
                ) + (item.optJSONArray("backup_url_list")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList())
                var url = ""
                for (c in candidates) {
                    if (c.isBlank()) continue
                    url = decodePlayUrl(c, keySeed)
                    if (url.startsWith("http")) break
                }
                if (!url.startsWith("http")) continue
                val h = item.optInt("vheight", item.optInt("height", 0))
                var label = item.optString("definition", item.optString("quality_desc", ""))
                if (label.isBlank() && h > 0) label = "${h}p"
                out += Stream(url, label.ifBlank { "app" }, h)
            }
        } catch (_: Exception) {}
    }

    private fun decodePlayUrl(value: String, keySeed: ByteArray?): String {
        if (value.startsWith("http")) return value
        // try plain base64
        runCatching {
            val plain = String(decode64(value), Charsets.UTF_8)
            if (plain.startsWith("http")) return plain
        }
        // spade / key_seed decrypt
        if (keySeed != null) {
            runCatching { return MediaCrypto.decryptUrl(value, keySeed) }
        }
        return value
    }
}
