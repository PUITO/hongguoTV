// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.phone.core

import android.content.Context
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
 * 对齐 hongguo-drama：匿名注册 + VIDEO_TAB / multi_video_model。
 * 针对 empty body：换设备重试、Connection:close、非 gzip POST。
 */
object AppPlayback {
    data class Identity(val deviceId: String, val installId: String, val cdid: String)
    data class Stream(
        val url: String,
        val quality: String,
        val height: Int,
        val spadeA: String? = null,
        val encrypted: Boolean = false,
        val codec: String = "",
    )

    private val VIDEO_TAB =
        "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/"
    private val VIDEO_API =
        "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"
    private val VIDEO_UA =
        "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

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
    @Volatile private var appContext: Context? = null

    @JvmStatic fun lastError(): String = lastError

    @JvmStatic
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        // 尝试加载上次成功身份，减少频繁注册
        if (cached.get() == null) {
            loadPersisted()?.let { cached.set(it) }
        }
    }

    @JvmStatic fun clearIdentity() {
        cached.set(null)
        appContext?.getSharedPreferences("app_playback", Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        AppLog.i("已清除播放设备身份")
    }

    @JvmStatic
    fun obtainIdentity(forceNew: Boolean = false): Identity {
        if (!forceNew) {
            cached.get()?.let { return it }
            loadPersisted()?.let {
                cached.set(it)
                return it
            }
        }
        synchronized(this) {
            if (!forceNew) {
                cached.get()?.let { return it }
            }
            val id = registerDevice()
            cached.set(id)
            persist(id)
            return id
        }
    }

    @JvmStatic
    fun fetchStreams(vid: String): List<Stream> {
        lastError = ""
        AppLog.i("取流开始 vid=$vid")
        // 最多：当前设备 2 次路径 + 换设备再 2 次
        val errors = mutableListOf<String>()
        for (round in 0 until 2) {
            val forceNew = round > 0
            val id = try {
                obtainIdentity(forceNew)
            } catch (e: Exception) {
                lastError = "注册失败: ${e.message}"
                AppLog.e("设备注册失败", e)
                throw e
            }
            AppLog.i("设备#${round + 1} did=${id.deviceId} iid=${id.installId} forceNew=$forceNew")

            // 1 TAB GET
            try {
                val list = fetchTabGet(id, vid, closeConn = round > 0)
                if (list.isNotEmpty()) {
                    AppLog.i("TAB成功 streams=${list.size} q=${list.first().quality}")
                    return list
                }
                errors += "TAB空模型"
            } catch (e: Exception) {
                errors += "TAB:${e.message}"
                AppLog.w("TAB 失败: ${e.message}")
            }

            // 2 POST gzip
            try {
                val list = fetchApiPost(id, vid, gzip = true, closeConn = true)
                if (list.isNotEmpty()) {
                    AppLog.i("POST-gzip成功 streams=${list.size} q=${list.first().quality}")
                    return list
                }
                errors += "POST-gzip空模型"
            } catch (e: Exception) {
                errors += "POST-gzip:${e.message}"
                AppLog.w("POST-gzip 失败: ${e.message}")
            }

            // 3 POST 明文 JSON（部分节点对 gzip 返回空）
            try {
                val list = fetchApiPost(id, vid, gzip = false, closeConn = true)
                if (list.isNotEmpty()) {
                    AppLog.i("POST-raw成功 streams=${list.size} q=${list.first().quality}")
                    return list
                }
                errors += "POST-raw空模型"
            } catch (e: Exception) {
                errors += "POST-raw:${e.message}"
                AppLog.w("POST-raw 失败: ${e.message}")
            }

            // 本轮全空 → 清身份换设备
            AppLog.w("第${round + 1}轮无流，准备换设备")
            clearIdentity()
        }
        lastError = errors.joinToString(" | ")
        AppLog.e("取流全部失败: $lastError")
        return emptyList()
    }

    @JvmStatic
    fun fetchBest(vid: String, preferred: String?): Stream? {
        val ranked = rankStreams(fetchStreams(vid), preferred)
        if (ranked.isEmpty()) return null
        val best = ranked.first()
        AppLog.i("选定流 q=${best.quality} h=${best.height} enc=${best.encrypted} codec=${best.codec} url=${AppLog.clip(best.url, 80)}")
        return best
    }

    /**
     * 选流排序（对齐 hongguo-drama / 社区实测）：
     * - 1080p 多为 bytevc1=HEVC，系统可解
     * - 720及以下多为 bytevc2，系统 MediaPlayer 只能出声无画
     * drama 桌面端对 bvc2 用内置 FFmpeg 转 H.264；手机端无 bvc2 解码时必须优先 1080p HEVC
     */
    @JvmStatic
    fun rankStreams(list: List<Stream>, preferred: String?): List<Stream> {
        if (list.isEmpty()) return emptyList()
        for (s in list) {
            AppLog.i("候选流 q=${s.quality} h=${s.height} enc=${s.encrypted} codec=${s.codec} url=${AppLog.clip(s.url, 60)}")
        }
        val want = when (preferred) {
            "360p" -> 360
            "480p" -> 480
            "540p" -> 540
            "720p" -> 720
            "1080p" -> 1080
            else -> 1080
        }
        fun heightOf(s: Stream): Int {
            if (s.height > 0) return s.height
            val fromQ = Regex("(\\d{3,4})").find(s.quality)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return fromQ ?: 0
        }
        fun codecPenalty(s: Stream): Int {
            val c = (s.codec + " " + s.quality).lowercase()
            return when {
                "h264" in c || "avc1" in c || "avc" in c -> 0
                "bytevc1" in c || "hvc1" in c || "hevc" in c || "h265" in c -> 5
                "bytevc2" in c || "bvc2" in c -> 1000
                heightOf(s) >= 1080 || "1080" in s.quality -> 8
                heightOf(s) in 1..720 || Regex("360|480|540|720").containsMatchIn(s.quality) -> 200
                else -> 50
            }
        }
        // 不再优先未加密流（多为公共试看 360p）
        return list.sortedWith(
            compareBy<Stream> { codecPenalty(it) }
                .thenBy { s ->
                    val h = heightOf(s)
                    if (h <= 0) 10000
                    else {
                        val dist = kotlin.math.abs(h - want)
                        if (h >= want) dist else dist + 50
                    }
                }
                .thenBy { if (it.encrypted) 0 else 1 }
                .thenByDescending { heightOf(it) },
        )
    }

    @JvmStatic
    fun fetchRanked(vid: String, preferred: String?): List<Stream> =
        rankStreams(fetchStreams(vid), preferred)

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
        val conn = open(url, "POST", headers, closeConn = false)
        // 注册不要强制 identity，避免解压问题；也不要手动 Accept-Encoding
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = readBody(conn)
        AppLog.i("注册 HTTP $code hdrs=${headerSummary(conn)} body=${AppLog.clip(text, 180)}")
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

    private fun fetchTabGet(id: Identity, vid: String, closeConn: Boolean): List<Stream> {
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
        AppLog.i("TAB 签名 argusLen=${signed["x-argus"]?.length ?: 0}")
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
        )
        if (closeConn) headers["Connection"] = "close"
        headers.putAll(signed)
        val conn = open(url, "GET", headers, closeConn)
        val code = conn.responseCode
        val text = readBody(conn)
        AppLog.i("TAB HTTP $code hdrs=${headerSummary(conn)} len=${text.length} body=${AppLog.clip(text, 220)}")
        if (code >= 400) throw IllegalStateException("HTTP $code")
        if (text.isBlank()) throw IllegalStateException("empty body")
        val root = JSONObject(text)
        val biz = root.optInt("code", root.optInt("Code", 0))
        if (biz != 0) throw IllegalStateException("code $biz")
        return parseStreams(root)
    }

    private fun fetchApiPost(id: Identity, vid: String, gzip: Boolean, closeConn: Boolean): List<Stream> {
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
        val raw = payload.toByteArray(Charsets.UTF_8)
        val body = if (gzip) gzip(raw) else raw
        val ticket = System.currentTimeMillis()
        val khronos = ticket / 1000
        val params = baseDevice(id)
        params["ts"] = khronos.toString()
        params["_rticket"] = ticket.toString()
        val query = encodeQuery(params)
        val url = VIDEO_API + "?" + query
        val signed = PlayerSigner.headers(query, body, khronos)
        AppLog.i("POST 签名 gzip=$gzip argusLen=${signed["x-argus"]?.length ?: 0} body=${body.size}")
        val headers = linkedMapOf(
            "User-Agent" to VIDEO_UA,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "Content-Type" to "application/json; charset=UTF-8",
            "sdk-version" to "2",
            "x-xs-from-web" to "0",
            "passport-sdk-version" to "5051452",
            "x-ss-dp" to "8662",
            "x-tt-dt" to "",
            "x-tt-store-region" to "cn",
            "x-tt-store-region-src" to "did",
        )
        if (gzip) headers["Content-Encoding"] = "gzip"
        if (closeConn) headers["Connection"] = "close"
        headers.putAll(signed)
        val conn = open(url, "POST", headers, closeConn)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = readBody(conn)
        AppLog.i("POST HTTP $code gzip=$gzip hdrs=${headerSummary(conn)} len=${text.length} body=${AppLog.clip(text, 220)}")
        if (code >= 400) throw IllegalStateException("HTTP $code")
        if (text.isBlank()) throw IllegalStateException("empty body")
        val root = JSONObject(text)
        val biz = root.optInt("code", root.optInt("Code", 0))
        if (biz != 0) throw IllegalStateException("code $biz")
        return parseStreams(root)
    }

    private fun parseStreams(root: JSONObject): List<Stream> {
        val out = ArrayList<Stream>()
        val data = root.optJSONObject("data") ?: root
        val keys = data.keys()
        var entryCount = 0
        while (keys.hasNext()) {
            val k = keys.next()
            val v = data.opt(k)
            entryCount++
            when (v) {
                is JSONObject -> {
                    collectModel(v.opt("video_model"), out)
                    collectModel(v, out)
                }
                is String -> if (v.startsWith("{")) collectModel(v, out)
            }
        }
        collectModel(data.opt("video_model"), out)
        data.optJSONObject("video_model_data")?.let { vmd ->
            val ks = vmd.keys()
            while (ks.hasNext()) collectModel(vmd.opt(ks.next()), out)
        }
        data.optJSONArray("video_model_list")?.let { arr ->
            for (i in 0 until arr.length()) collectModel(arr.opt(i), out)
        }
        AppLog.i("parseStreams entries=$entryCount streams=${out.size}")
        if (out.isEmpty() && entryCount > 0) {
            AppLog.w("解析无流 sample=${AppLog.clip(data.toString(), 320)}")
        }
        return out.distinctBy { it.url }
    }

    private fun collectModel(raw: Any?, out: MutableList<Stream>) {
        if (raw == null) return
        try {
            val model = when (raw) {
                is JSONObject -> raw
                is String -> if (raw.trimStart().startsWith("{")) JSONObject(raw) else return
                else -> return
            }
            val nested = model.opt("video_model")
            if (nested is String && nested.trimStart().startsWith("{")) collectModel(nested, out)
            else if (nested is JSONObject) collectModel(nested, out)

            val keySeed = model.optString("key_seed").takeIf { it.isNotBlank() }?.let {
                runCatching { decode64(it) }.getOrNull()
            }
            val videoList = model.optJSONObject("video_list")
            if (videoList == null) {
                model.optJSONArray("video_list")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { extractItem(it, keySeed, out) }
                    }
                }
                return
            }
            val vkeys = videoList.keys()
            var n = 0
            var ok = 0
            while (vkeys.hasNext()) {
                val item = videoList.optJSONObject(vkeys.next()) ?: continue
                n++
                if (extractItem(item, keySeed, out)) ok++
            }
            if (n > 0) AppLog.i("video_list items=$n decoded=$ok keySeed=${keySeed != null}")
        } catch (e: Exception) {
            AppLog.w("collectModel: ${e.message}")
        }
    }

    private fun extractItem(item: JSONObject, keySeed: ByteArray?, out: MutableList<Stream>): Boolean {
        val candidates = buildList {
            add(item.optString("main_url"))
            add(item.optString("backup_url"))
            add(item.optString("main_url_no_redirect"))
            item.optJSONArray("backup_url_list")?.let { arr ->
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
            item.optJSONObject("play_addr")?.optJSONArray("url_list")?.let { arr ->
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
        }
        var url = ""
        var lastErr = ""
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            try {
                url = decodePlayUrl(c, keySeed)
                if (url.startsWith("http")) break
                lastErr = "not-http:${AppLog.clip(url, 40)}"
            } catch (e: Exception) {
                lastErr = e.message ?: "decode"
            }
        }
        if (!url.startsWith("http")) {
            if (candidates.any { !it.isNullOrBlank() }) {
                AppLog.w("url解码失败 ${AppLog.clip(candidates.first { !it.isNullOrBlank() }, 50)} err=$lastErr")
            }
            return false
        }
        val meta = item.optJSONObject("video_meta")
        val h = item.optInt(
            "vheight",
            item.optInt(
                "height",
                item.optInt("video_height", meta?.optInt("height", meta?.optInt("vheight", 0) ?: 0) ?: 0),
            ),
        )
        var label = item.optString(
            "definition",
            item.optString(
                "quality_desc",
                item.optString("quality", item.optString("quality_type", meta?.optString("definition", "") ?: "")),
            ),
        )
        if (label.isBlank() && h > 0) label = "${h}p"
        val ctype = item.optString("logo_type", item.optString("media_type", ""))
        if (ctype.contains("audio", true)) {
            AppLog.w("跳过音频流 $label")
            return false
        }
        val enc = item.optJSONObject("encrypt_info")
        val encrypted = enc?.optBoolean("encrypt", false) == true
        val spade = enc?.optString("spade_a")?.takeIf { it.isNotBlank() }
        val codec = item.optString(
            "codec_type",
            meta?.optString("codec_type", item.optString("codec", "")) ?: "",
        )
        if (encrypted) {
            AppLog.i("轨道加密 q=$label h=$h codec=$codec spade=${AppLog.clip(spade ?: "", 24)}")
        }
        out += Stream(url, label.ifBlank { "app" }, h, spade, encrypted, codec)
        return true
    }

    private fun decodePlayUrl(value: String, keySeed: ByteArray?): String {
        if (value.startsWith("http")) return value
        runCatching {
            val plain = String(decode64(value), Charsets.UTF_8).trim()
            if (plain.startsWith("http")) return plain
        }
        if (keySeed != null) {
            runCatching { return MediaCrypto.decryptUrl(value, keySeed) }
                .onFailure { AppLog.w("MediaCrypto.decryptUrl: ${it.message}") }
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

    private fun open(url: String, method: String, headers: Map<String, String>, closeConn: Boolean): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.requestMethod = method
        conn.instanceFollowRedirects = false
        conn.doInput = true
        if (method == "POST") conn.doOutput = true
        // 不手动设 Accept-Encoding，让系统处理 gzip 解压
        headers.forEach { (k, v) ->
            if (!k.equals("Accept-Encoding", true)) conn.setRequestProperty(k, v)
        }
        if (closeConn) conn.setRequestProperty("Connection", "close")
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        } catch (_: Exception) {
            null
        }
        return stream?.readBytes()?.toString(StandardCharsets.UTF_8) ?: ""
    }

    private fun headerSummary(conn: HttpURLConnection): String {
        return try {
            val cl = conn.getHeaderField("Content-Length")
            val ce = conn.getHeaderField("Content-Encoding")
            val ct = conn.getHeaderField("Content-Type")
            "cl=$cl ce=$ce ct=$ct"
        } catch (_: Exception) {
            ""
        }
    }

    private fun persist(id: Identity) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences("app_playback", Context.MODE_PRIVATE).edit()
            .putString("device_id", id.deviceId)
            .putString("install_id", id.installId)
            .putString("cdid", id.cdid)
            .apply()
    }

    private fun loadPersisted(): Identity? {
        val ctx = appContext ?: return null
        val sp = ctx.getSharedPreferences("app_playback", Context.MODE_PRIVATE)
        val d = sp.getString("device_id", null) ?: return null
        val i = sp.getString("install_id", null) ?: return null
        val c = sp.getString("cdid", null) ?: return null
        if (d.isBlank() || i.isBlank() || d == "0") return null
        AppLog.i("加载已保存设备 did=$d")
        return Identity(d, i, c)
    }
}
