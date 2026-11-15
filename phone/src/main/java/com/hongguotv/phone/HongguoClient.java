package com.hongguotv.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红果官网解析：首页推荐 + 关键词过滤 + 播放页公开 mp4。
 */
public final class HongguoClient {
    public static final String ORIGIN = "https://hongguoduanju.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36";

    public static JSONArray homeFeed() throws Exception {
        String html = get(ORIGIN + "/");
        return parseSeriesList(html);
    }

    public static JSONArray search(String keyword) throws Exception {
        String kw = keyword == null ? "" : keyword.trim();
        JSONArray home = homeFeed();
        if (kw.isEmpty()) return home;
        JSONArray out = new JSONArray();
        for (int i = 0; i < home.length(); i++) {
            JSONObject o = home.getJSONObject(i);
            String title = o.optString("title", "");
            String intro = o.optString("intro", "");
            if (title.contains(kw) || intro.contains(kw)) out.put(o);
        }
        // 再拉播放页搜索词不走加密 API 时，至少保证首页命中；
        // 额外尝试 search 页 HTML（部分环境可能内嵌）
        try {
            String q = java.net.URLEncoder.encode(kw, "UTF-8");
            String html = get(ORIGIN + "/search/" + q);
            JSONArray extra = parseSeriesList(html);
            Map<String, JSONObject> map = new LinkedHashMap<>();
            for (int i = 0; i < out.length(); i++) {
                JSONObject o = out.getJSONObject(i);
                map.put(o.optString("seriesId"), o);
            }
            for (int i = 0; i < extra.length(); i++) {
                JSONObject o = extra.getJSONObject(i);
                map.put(o.optString("seriesId"), o);
            }
            out = new JSONArray();
            for (JSONObject o : map.values()) out.put(o);
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject detail(String seriesId) throws Exception {
        String html = get(ORIGIN + "/player/" + seriesId);
        html = unescape(html);
        String title = match(html, "\"series_name\"\\s*:\\s*\"([^\"]+)\"");
        String cover = match(html, "\"series_cover\"\\s*:\\s*\"([^\"]+)\"");
        if (cover != null) cover = unescape(cover);
        int episodeCnt = 1;
        try {
            String c = match(html, "\"episode_cnt\"\\s*:\\s*(\\d+)");
            if (c != null) episodeCnt = Integer.parseInt(c);
        } catch (Exception ignored) {}
        int accessible = episodeCnt;
        try {
            String c = match(html, "\"accessible_episode_cnt\"\\s*:\\s*(\\d+)");
            if (c != null) accessible = Integer.parseInt(c);
        } catch (Exception ignored) {}

        List<String> vids = extractVids(html, seriesId);
        JSONArray episodes = new JSONArray();
        int n = Math.max(vids.size(), Math.min(accessible, 30));
        if (n < 1) n = 1;
        for (int i = 0; i < n; i++) {
            String vid = i < vids.size() ? vids.get(i) : (vids.isEmpty() ? "" : vids.get(0));
            String url = vid.isEmpty() ? "" : (ORIGIN + "/video/public/" + vid + "/360p.mp4");
            episodes.put(new JSONObject()
                    .put("episode", i + 1)
                    .put("vid", vid)
                    .put("title", "第" + (i + 1) + "集")
                    .put("url", url)
                    .put("seriesId", seriesId));
        }
        return new JSONObject()
                .put("seriesId", seriesId)
                .put("title", title == null ? ("剧集 " + seriesId) : title)
                .put("cover", cover == null ? "" : cover)
                .put("episodeCnt", episodeCnt)
                .put("accessible", accessible)
                .put("episodes", episodes);
    }

    public static JSONObject resolvePlay(String seriesId, String vidOrEp) throws Exception {
        JSONObject d = detail(seriesId);
        JSONArray eps = d.optJSONArray("episodes");
        if (eps == null || eps.length() == 0) throw new IllegalStateException("无可用集数");
        int ep = 1;
        try { ep = Integer.parseInt(vidOrEp); } catch (Exception ignored) {}
        JSONObject item = eps.optJSONObject(Math.max(0, Math.min(ep - 1, eps.length() - 1)));
        if (item == null || item.optString("url").isEmpty()) {
            // fallback first
            item = eps.getJSONObject(0);
        }
        return new JSONObject()
                .put("url", item.optString("url"))
                .put("title", d.optString("title") + " " + item.optString("title"))
                .put("seriesId", seriesId)
                .put("vid", item.optString("vid"))
                .put("cover", d.optString("cover"))
                .put("source", "web-public");
    }

    private static List<String> extractVids(String html, String seriesId) {
        List<String> vids = new ArrayList<>();
        // public path first
        Matcher m = Pattern.compile("video/public/(\\d+)/").matcher(html);
        while (m.find()) {
            String v = m.group(1);
            if (!v.equals(seriesId) && !vids.contains(v)) vids.add(v);
        }
        m = Pattern.compile("\"vid\"\\s*:\\s*\"(\\d+)\"").matcher(html);
        while (m.find()) {
            String v = m.group(1);
            if (!v.equals(seriesId) && !vids.contains(v)) vids.add(v);
        }
        return vids;
    }

    private static JSONArray parseSeriesList(String html) throws Exception {
        html = unescape(html);
        Map<String, JSONObject> map = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"series_id\"\\s*:\\s*\"(\\d+)\"").matcher(html);
        while (m.find()) {
            String sid = m.group(1);
            String window = html.substring(Math.max(0, m.start() - 200), Math.min(html.length(), m.start() + 900));
            String title = match(window, "\"series_name\"\\s*:\\s*\"([^\"]+)\"");
            String cover = match(window, "\"series_cover\"\\s*:\\s*\"([^\"]+)\"");
            String intro = match(window, "\"series_intro\"\\s*:\\s*\"([^\"]*)\"");
            JSONObject o = map.get(sid);
            if (o == null) o = new JSONObject().put("seriesId", sid);
            if (title != null && !title.isEmpty()) o.put("title", title);
            if (cover != null && !cover.isEmpty()) o.put("cover", unescape(cover));
            if (intro != null) o.put("intro", intro.length() > 80 ? intro.substring(0, 80) : intro);
            if (!o.has("title")) o.put("title", "剧集 " + sid);
            if (!o.has("cover")) o.put("cover", "");
            map.put(sid, o);
        }
        JSONArray arr = new JSONArray();
        for (JSONObject o : map.values()) arr.put(o);
        return arr;
    }

    private static String match(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s.replace("\\u002F", "/").replace("\\/", "/");
    }

    private static String get(String urlSpec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlSpec).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html,application/json,*/*");
        c.setRequestProperty("Referer", ORIGIN + "/");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code >= 400) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 2_000_000) break;
            }
        }
        return sb.toString();
    }
}
