package com.hongguotv.phone.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * App 通道取流：设备注册 + multi_video_model。
 * 签名完整实现依赖 Argus（N3urda/hongguoTV）；本客户端在签名不可用时仍会尝试注册态请求，
 * 成功则解析 video_list 的 main_url；失败由调用方回退 web-public。
 */
public final class AppPlayClient {
    private AppPlayClient() {}

    public static JSONObject fetchEpisode(String vid, String preferredQuality) throws Exception {
        DeviceRegister.Identity id = DeviceRegister.obtain();
        String query = "iid=" + id.installId
                + "&device_id=" + id.deviceId
                + "&aid=8662&app_name=novelread&version_code=71332&version_name=7.1.3.32"
                + "&device_platform=android&os=android&device_type=MI12&device_brand=Xiaomi"
                + "&cdid=" + id.cdid
                + "&need_all_video_definition=1&need_mp4_align=0&use_os_player=0&video_platform=1024";
        String url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?" + query;
        JSONObject body = new JSONObject()
                .put("biz_param", new JSONObject()
                        .put("device_level", 3)
                        .put("need_all_video_definition", true)
                        .put("need_mp4_align", false)
                        .put("use_os_player", false)
                        .put("video_platform", 1024))
                .put("mixed_video_id_map", new JSONObject().put("1004", new JSONArray().put(vid)));
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "com.phoenix.read/71332 (Linux; U; Android 13; zh_CN)");
        c.setRequestProperty("Accept", "application/json");
        // Argus headers (best-effort)
        try {
            long ts = System.currentTimeMillis() / 1000;
            c.setRequestProperty("x-khronos", String.valueOf(ts));
            c.setRequestProperty("x-ss-req-ticket", String.valueOf(ts * 1000));
            String stub = ArgusSigner.md5Hex(payload).toUpperCase();
            c.setRequestProperty("x-ss-stub", stub);
            c.setRequestProperty("x-argus", ArgusSigner.sign(query, stub, ts, 8662L, id.deviceId));
            c.setRequestProperty("x-ladon", ArgusSigner.ladon(ts, 8662L));
        } catch (Throwable ignored) {}
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        String resp = bos.toString("UTF-8");
        if (code >= 400 || resp.isEmpty()) {
            throw new IllegalStateException("app play HTTP " + code);
        }
        JSONObject root = new JSONObject(resp);
        List<JSONObject> qualities = extractQualities(root);
        if (qualities.isEmpty()) throw new IllegalStateException("app play empty model");
        JSONObject best = pickQuality(qualities, preferredQuality);
        return new JSONObject()
                .put("url", best.optString("url"))
                .put("quality", best.optString("label", "app"))
                .put("vid", vid)
                .put("source", "app")
                .put("deviceId", id.deviceId);
    }

    private static List<JSONObject> extractQualities(JSONObject root) {
        List<JSONObject> list = new ArrayList<>();
        try {
            // walk common shapes
            JSONObject data = root.optJSONObject("data");
            if (data == null) data = root;
            collectFromModel(data.opt("video_model"), list);
            JSONObject vmd = data.optJSONObject("video_model_data");
            if (vmd != null) {
                Iterator<String> keys = vmd.keys();
                while (keys.hasNext()) {
                    collectFromModel(vmd.opt(keys.next()), list);
                }
            }
            JSONArray arr = data.optJSONArray("video_model_list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) collectFromModel(arr.opt(i), list);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static void collectFromModel(Object raw, List<JSONObject> out) {
        if (raw == null) return;
        try {
            JSONObject model = raw instanceof JSONObject ? (JSONObject) raw : new JSONObject(String.valueOf(raw));
            JSONObject videoList = model.optJSONObject("video_list");
            if (videoList == null) return;
            Iterator<String> keys = videoList.keys();
            while (keys.hasNext()) {
                JSONObject item = videoList.optJSONObject(keys.next());
                if (item == null) continue;
                String url = item.optString("main_url");
                if (url.isEmpty()) url = item.optString("backup_url");
                if (url.isEmpty()) continue;
                // base64 url
                if (!url.startsWith("http")) {
                    try {
                        url = new String(android.util.Base64.decode(url, android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                }
                if (!url.startsWith("http")) continue;
                String label = item.optString("definition", item.optString("quality_desc", ""));
                int h = item.optInt("vheight", item.optInt("height", 0));
                if (label.isEmpty() && h > 0) label = h + "p";
                try {
                    out.add(new JSONObject().put("url", url).put("label", label).put("height", h));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject pickQuality(List<JSONObject> list, String preferred) throws Exception {
        if (list.isEmpty()) throw new IllegalStateException("no url");
        int want = 720;
        if ("1080p".equals(preferred)) want = 1080;
        else if ("360p".equals(preferred)) want = 360;
        else if ("auto".equals(preferred)) want = 1080;
        JSONObject best = list.get(0);
        int bestDiff = Integer.MAX_VALUE;
        for (JSONObject o : list) {
            int h = o.optInt("height", 0);
            String label = o.optString("label");
            if (h == 0 && label.matches(".*\\d+.*")) {
                try { h = Integer.parseInt(label.replaceAll("\\D+", "")); } catch (Exception ignored) {}
            }
            int diff = Math.abs(h - want);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = o;
            }
        }
        return best;
    }
}
