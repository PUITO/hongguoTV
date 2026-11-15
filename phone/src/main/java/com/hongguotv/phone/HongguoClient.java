package com.hongguotv.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红果公开站解析（网页兜底）。不依赖 WebView BigInt 签名。
 * 站点与选择器可能随上游变更，失败时允许手动填 URL。
 */
public final class HongguoClient {
    public static final String ORIGIN = "https://www.hongguoduanju.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36";

    public static JSONArray search(String keyword) throws Exception {
        String q = URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8");
        String html = get(ORIGIN + "/search?q=" + q);
        if (html.isEmpty()) html = get(ORIGIN + "/search/" + q);
        JSONArray out = new JSONArray();
        // series links: /detail/123 or /book/123 or data-id
        Pattern p = Pattern.compile("href=\"([^\"]*(?:detail|book|series|drama)/(\\d+)[^\"]*)\"[^>]*>([^<]{0,80})", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        int guard = 0;
        while (m.find() && guard++ < 40) {
            String id = m.group(2);
            String title = m.group(3).replaceAll("\\s+", " ").trim();
            if (title.length() < 1) title = "剧集 " + id;
            boolean dup = false;
            for (int i = 0; i < out.length(); i++) {
                if (id.equals(out.getJSONObject(i).optString("seriesId"))) { dup = true; break; }
            }
            if (dup) continue;
            out.put(new JSONObject()
                    .put("seriesId", id)
                    .put("title", title)
                    .put("path", m.group(1)));
        }
        // fallback: any /player/sid/vid
        if (out.length() == 0) {
            Pattern p2 = Pattern.compile("/player/(\\d+)/(\\d+)");
            Matcher m2 = p2.matcher(html);
            while (m2.find() && out.length() < 20) {
                String sid = m2.group(1);
                boolean dup = false;
                for (int i = 0; i < out.length(); i++) {
                    if (sid.equals(out.getJSONObject(i).optString("seriesId"))) { dup = true; break; }
                }
                if (dup) continue;
                out.put(new JSONObject().put("seriesId", sid).put("title", "剧集 " + sid).put("path", "/detail/" + sid));
            }
        }
        return out;
    }

    public static JSONObject detail(String seriesId) throws Exception {
        String html = get(ORIGIN + "/detail/" + seriesId);
        if (html.isEmpty()) html = get(ORIGIN + "/book/" + seriesId);
        if (html.isEmpty()) html = get(ORIGIN + "/player/" + seriesId + "/1");
        String title = extractTitle(html);
        JSONArray episodes = new JSONArray();
        Pattern ep = Pattern.compile("/player/" + Pattern.quote(seriesId) + "/(\\d+)");
        Matcher m = ep.matcher(html);
        List<String> seen = new ArrayList<>();
        while (m.find()) {
            String vid = m.group(1);
            if (seen.contains(vid)) continue;
            seen.add(vid);
            episodes.put(new JSONObject()
                    .put("episode", Integer.parseInt(vid))
                    .put("vid", vid)
                    .put("title", "第" + vid + "集")
                    .put("seriesId", seriesId));
        }
        if (episodes.length() == 0) {
            // invent first 30 slots for manual resolve
            for (int i = 1; i <= 20; i++) {
                episodes.put(new JSONObject()
                        .put("episode", i)
                        .put("vid", String.valueOf(i))
                        .put("title", "第" + i + "集")
                        .put("seriesId", seriesId));
            }
        }
        return new JSONObject()
                .put("seriesId", seriesId)
                .put("title", title)
                .put("episodes", episodes);
    }

    /** 解析单集播放地址（mp4/m3u8）。 */
    public static JSONObject resolvePlay(String seriesId, String vid) throws Exception {
        String html = get(ORIGIN + "/player/" + seriesId + "/" + vid);
        String url = findMediaUrl(html);
        if (url == null || url.isEmpty()) {
            Matcher mj = Pattern.compile("https?://[^\"\s]+\.(?:mp4|m3u8)[^\"\s]*").matcher(html);
            if (mj.find()) url = mj.group().replace("\/", "/");
        }
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException("未解析到播放地址，可手动粘贴 URL");
        }
        return new JSONObject()
                .put("url", url)
                .put("title", "红果 " + seriesId + " 第" + vid + "集")
                .put("seriesId", seriesId)
                .put("vid", vid)
                .put("source", "web");
    }

    private static String findMediaUrl(String html) {
        Pattern[] ps = new Pattern[] {
                Pattern.compile("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)"),
                Pattern.compile("(https?://[^\"'\\s]+\\.mp4[^\"'\\s]*)"),
                Pattern.compile("src\\s*=\\s*\"(https?://[^\"]+)\"")
        };
        for (Pattern p : ps) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                String u = m.group(1).replace("\\/", "/");
                if (u.contains(".m3u8") || u.contains(".mp4") || u.contains("video")) return u;
            }
        }
        return null;
    }

    private static String extractTitle(String html) {
        Matcher m = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1).replaceAll("\\s*[-|_].*$", "").trim();
        return "红果剧集";
    }

    private static String get(String urlSpec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlSpec).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html,application/json,*/*");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code >= 400) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 1_500_000) break;
            }
        }
        return sb.toString();
    }
}
