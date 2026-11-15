// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.phone.core

import com.hongguotv.phone.AppLog

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/**
 * 对齐 hongguo-drama `app-source.js` + `playback-device.js`：
 * 1. 匿名 device_register（无需登录）
 * 2. VIDEO_TAB GET（带 video_id）优先；失败再 multi_video_model POST gzip
 * 3. PlayerSigner（x-argus / x-ladon）签最终 query + body
 */
object AppPlayback {
    data class Identity(val deviceId: String, val installId: String, val cdid: String)
    data class Stream(val url: String, val quality: String, val height: Int)

    private val VIDEO_TAB =
        "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/"
    private val VIDEO_API =
        "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"
    private val VIDEO_UA =
        "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    /** 与 app-source.js DEVICE / PLAYBACK_DEVICE 对齐 */
    private fun baseDevice(id: Identity): LinkedHashMap<String, String> = linkedMapOf(
        "ac" to "wifi",
        "channel" to "update_64",
        "aid" to "8662",
        "app_name" to "novelread",
        "version_code" to "71332",
        "version_name" to "7.1.3.32",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "25053RT47C",
        "device_brand" to "Redmi",
        "language" to "zh",
        "os_api" to "36",
        "os_version" to "16",
        "manifest_version_code" to "71332",
        "resolution" to "1280*2772",
        "dpi" to "520",
        "update_version_code" to "71332",
        "host_abi" to "arm64-v8a",
        "dragon_device_type" to "phone",
        "pv_player" to "71332",
        "compliance_status" to "0",
        "need_personal_recommend" to "1",
        "player_so_load" to "1",
        "is_android_pad_screen" to "0",
        "device_id" to id.deviceId,
        "iid" to id.installId,
        "cdid" to id.cdid,
    )

    private val cached = AtomicReference<Identity?>(null)
    private var lastError: String = ""

    @JvmStatic fun lastError(): String = lastError

    @JvmStatic fun clearIdentity() { cached.set(null) }

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

    @JvmStatic
    fun fetchStreams(vid: String): List<Stream> {
        lastError = ""
        AppLog.i("取流开始 vid=$vid")
        val id = try {
            obtainIdentity()
        } catch (e: Exception) {
            lastError = "注册失败: ${e.message}"
            AppLog.e("设备注册失败", e)
            throw e
        }
        AppLog.i("设备 did=${id.deviceId} iid=${id.installId} cdid=${id.cdid.take(8)}…")
        val errors = mutableListOf<String>()
        try {
            val list = fetchTabGet(id, vid)
            if (list.isNotEmpty()) {
                AppLog.i("TAB成功 streams=${list.size} best=${list.first().quality}/${list.first().height}p")
                return list
            }
            errors += "TAB空响应"
            AppLog.w("TAB 无可用清晰度")
        } catch (e: Exception) {
            errors += "TAB:${e.message}"
            AppLog.w("TAB 失败: ${e.message}")
        }
        try {
            val list = fetchApiPost(id, vid)
            if (list.isNotEmpty()) {
                AppLog.i("POST成功 streams=${list.size} best=${list.first().quality}")
                return list
            }
            errors += "POST空模型"
            AppLog.w("POST 无可用清晰度")
        } catch (e: Exception) {
            errors += "POST:${e.message}"
            AppLog.w("POST 失败: ${e.message}")
        }
        lastError = errors.joinToString(" | ")
        AppLog.e("取流全部失败: $lastError")
        return emptyList()
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
        return list.minByOrNull {
            val h = if (it.height > 0) it.height else Regex("(\\d{3,4})").find(it.quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            kotlin.math.abs(h - want)
        } ?: list.first()
    }

    private fun registerDevice(): Identity {
        val cdid = UUID.randomUUID().toString()
        val openudid = List(16) { "0123456789abcdef".random() }.joinToString("")
        val device = mapOf(
            "cdid" to cdid,
            "openudid" to openudid,
            "device_model" to "25053RT47C",
            "os_version" to "16",
            "os_api" to "36",
            "resolution" to "1280*2772",
            "dpi" to "520",
            "aid" to "8662",
            "channel" to "update_64",
            "package" to "com.phoenix.read",
            "app_version" to "7.3.9.32",
            "version_code" to "73932",
            "user_agent" to VIDEO_UA,
        )
        val body = DeviceRegister.payload(device)
        val url = DeviceRegister.url()
        val headers = DeviceRegister.headers(body, device)
        val conn = open(url, "POST", headers)
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = readBody(conn)
        AppLog.i("注册 HTTP $code body=${AppLog.clip(text, 180)}")
        if (code >= 400) throw IllegalStateException("register HTTP $code ${text.take(120)}")
        val registered = DeviceRegister.parse(text)
            ?: throw IllegalStateException("register parse: ${text.take(160)}")
        if (registered.deviceId.isBlank() || registered.installId.isBlank()
            || registered.deviceId == "0" || registered.installId == "0"
        ) {
            throw IllegalStateException("register empty id")
        }
        AppLog.i("注册成功 did=${registered.deviceId} iid=${registered.installId} new=${registered.newUser}")
        return Identity(registered.deviceId, registered.installId, cdid)
    }

    private fun fetchTabGet(id: Identity, vid: String): List<Stream> {
        val ticket = System.currentTimeMillis()
        val khronos = ticket / 1000
        val params = baseDevice(id)
        params["video_id"] = vid
        params["video_id_type"] = "0"
        params["device_level"] = "1"
        params["need_all_video_definition"] = "1"
        params["need_mp4_align"] = "0"
        params["use_os_player"] = "0"
        params["video_platform"] = "1024"
        params["source"] = "0"
        params["from_video_id"] = ""
        params["ts"] = khronos.toString()
        params["_rticket"] = ticket.toString()
        val query = encodeQuery(params)
        val url = VIDEO_TAB + "?" + query
        val signed = PlayerSigner.headers(query, null, khronos)
        AppLog.i("TAB 签名 argusLen=${signed["x-argus"]?.length ?: 0} ladon=${AppLog.clip(signed["x-ladon"] ?: "", 24)}")
        val headers = linkedMapOf(
            "User-Agent" to VIDEO_UA,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "sdk-version" to "2",
            "x-xs-from-web" to "0",
            "passport-sdk-version" to "50561",
            "x-ss-dp" to "8662",
            "x-tt-dt" to "",
            "x-tt-store-region" to "cn",
            "x-tt-store-region-src" to "did",
            "x-tt-request-tag" to "t=0;n=0",
            "Accept-Encoding" to "identity",
        )
        headers.putAll(signed)
        val conn = open(url, "GET", headers)
        val code = conn.responseCode
        val text = readBody(conn)
        AppLog.i("TAB HTTP $code len=${text.length} body=${AppLog.clip(text, 220)}")
        if (code >= 400) throw IllegalStateException("HTTP $code")
        if (text.isBlank()) throw IllegalStateException("empty body")
        val root = JSONObject(text)
        val biz = root.optInt("code", root.optInt("Code", 0))
        if (biz != 0) throw IllegalStateException("code $biz")
        val streams = parseStreams(root)
        AppLog.i("TAB 解析 streams=${streams.size}")
        return streams
    }

    private fun fetchApiPost(id: Identity, vid: String): List<Stream> {
        val payload = JSONObject()
            .put(
                "biz_param",
                JSONObject()
                    .put("detail_page_version", 0)
                    .put("device_level", 3)
                    .put("disable_digg_stat", false)
                    .put("need_all_video_definition", true)
                    .put("need_mp4_align", false)
                    .put("use_os_player", false)
                    .put("use_server_dns", false)
                    .put("video_platform", 1024),
            )
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
            .toString()
        val body = gzip(payload.toByteArray(Charsets.UTF_8))
        val ticket = System.currentTimeMillis()
        val khronos = ticket / 1000
        val params = baseDevice(id)
        params["ts"] = khronos.toString()
        params["_rticket"] = ticket.toString()
        val query = encodeQuery(params)
        val url = VIDEO_API + "?" + query
        val signed = PlayerSigner.headers(query, body, khronos)
        AppLog.i("POST 签名 argusLen=${signed["x-argus"]?.length ?: 0} bodyGzip=${body.size}")
        val headers = linkedMapOf(
            "User-Agent" to VIDEO_UA,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "Content-Type" to "application/json; charset=UTF-8",
            "Content-Encoding" to "gzip",
            "sdk-version" to "2",
            "x-xs-from-web" to "0",
            "passport-sdk-version" to "5051452",
            "x-ss-dp" to "8662",
            "x-tt-dt" to "",
            "x-tt-store-region" to "cn",
            "x-tt-store-region-src" to "did",
            "Accept-Encoding" to "identity",
        )
        headers.putAll(signed)
        val conn = open(url, "POST", headers)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = readBody(conn)
        AppLog.i("POST HTTP $code len=${text.length} body=${AppLog.clip(text, 220)}")
        if (code >= 400) throw IllegalStateException("HTTP $code")
        if (text.isBlank()) throw IllegalStateException("empty body")
        val root = JSONObject(text)
        val biz = root.optInt("code", root.optInt("Code", 0))
        if (biz != 0) throw IllegalStateException("code $biz")
        val streams = parseStreams(root)
        AppLog.i("POST 解析 streams=${streams.size}")
        return streams
    }

    private fun parseStreams(root: JSONObject): List<Stream> {
        val out = ArrayList<Stream>()
        val data = root.optJSONObject("data") ?: root
        collectModel(data.opt("video_model"), out)
        data.optJSONObject("video_model_data")?.let { vmd ->
            val keys = vmd.keys()
            while (keys.hasNext()) collectModel(vmd.opt(keys.next()), out)
        }
        data.optJSONArray("video_model_list")?.let { arr ->
            for (i in 0 until arr.length()) collectModel(arr.opt(i), out)
        }
        // TAB 接口常见结构
        data.optJSONObject("video_data")?.let { collectModel(it.opt("video_model"), out) }
        return out.distinctBy { it.url }
    }

    private fun collectModel(raw: Any?, out: MutableList<Stream>) {
        if (raw == null) return
        try {
            val model = when (raw) {
                is JSONObject -> raw
                is String -> if (raw.startsWith("{")) JSONObject(raw) else return
                else -> return
            }
            val keySeed = model.optString("key_seed").takeIf { it.isNotBlank() }?.let {
                runCatching { decode64(it) }.getOrNull()
            }
            val videoList = model.optJSONObject("video_list") ?: return
            val keys = videoList.keys()
            while (keys.hasNext()) {
                val item = videoList.optJSONObject(keys.next()) ?: continue
                val candidates = buildList {
                    add(item.optString("main_url"))
                    add(item.optString("backup_url"))
                    item.optJSONArray("backup_url_list")?.let { arr ->
                        for (i in 0 until arr.length()) add(arr.optString(i))
                    }
                }
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
        } catch (_: Exception) {
        }
    }

    private fun decodePlayUrl(value: String, keySeed: ByteArray?): String {
        if (value.startsWith("http")) return value
        runCatching {
            val plain = String(decode64(value), Charsets.UTF_8)
            if (plain.startsWith("http")) return plain
        }
        if (keySeed != null) {
            runCatching { return MediaCrypto.decryptUrl(value, keySeed) }
        }
        return value
    }

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.requestMethod = method
        conn.instanceFollowRedirects = false
        conn.doInput = true
        if (method == "POST") conn.doOutput = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        return stream?.readBytes()?.toString(StandardCharsets.UTF_8) ?: ""
    }
}
