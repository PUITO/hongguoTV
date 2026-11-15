package com.hongguotv.phone;

import com.hongguotv.phone.app.AppPlayClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 片源解析对齐 hongguo-drama 官网通道：
 * - 搜索：/api/search/result?aid=8662&query=&page=
 * - 详情：/player/{seriesId} 提取完整 vid_list
 * - 播放：/video/public/{vid}/{quality}.mp4（公共源目前稳定 360p）
 * App 签名高清源需设备注册与 SM3，后续可按 preferredQuality 接入。
 */
public final class HongguoClient {
    public static final String ORIGIN = "https://hongguoduanju.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    public static JSONArray homeFeed() throws Exception {
        String html = get(ORIGIN + "/");
        return parseSeriesFromHtml(html);
    }

    public static JSONArray search(String keyword) throws Exception {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return homeFeed();
        // 官网搜索 API（hongguo-search.js 同源）
        try {
            String q = URLEncoder.encode(kw, "UTF-8");
            String raw = get(ORIGIN + "/api/search/result?aid=8662&query=" + q + "&page=1");
            if (raw != null && raw.contains("searchList")) {
                JSONObject payload = new JSONObject(raw);
                JSONArray list = payload.optJSONArray("searchList");
                JSONArray out = new JSONArray();
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.optJSONObject(i);
                        if (item == null) continue;
                        JSONObject video = item.optJSONObject("video_data");
                        if (video == null || video.length() == 0) video = item;
                        String sid = video.optString("series_id", item.optString("keyword", ""));
                        String title = firstNonEmpty(
                                video.optString("series_title"),
                                video.optString("series_name"),
                                item.optString("name"),
                                video.optString("title"));
                        if (!sid.matches("\\d{6,}") || title.isEmpty()) continue;
                        String cover = unescape(firstNonEmpty(
                                video.optString("series_cover"),
                                video.optString("cover")));
                        int ep = video.optInt("episode_cnt", video.optInt("episode_count", 0));
                        String intro = video.optString("series_intro", "");
                        if (intro.length() > 80) intro = intro.substring(0, 80);
                        JSONArray vids = video.optJSONArray("vid_list");
                        out.put(new JSONObject()
                                .put("seriesId", sid)
                                .put("title", title)
                                .put("cover", cover == null ? "" : cover)
                                .put("intro", intro)
                                .put("episodeCnt", ep)
                                .put("vidList", vids == null ? new JSONArray() : vids)
                                .put("source", "api"));
                    }
                }
                if (out.length() > 0) return out;
            }
        } catch (Exception ignored) {}
        // fallback: homepage filter
        JSONArray home = homeFeed();
        JSONArray out = new JSONArray();
        for (int i = 0; i < home.length(); i++) {
            JSONObject o = home.getJSONObject(i);
            if (o.optString("title").contains(kw) || o.optString("intro").contains(kw)) out.put(o);
        }
        return out;
    }

    public static JSONObject detail(String seriesId) throws Exception {
        String html = get(ORIGIN + "/player/" + seriesId);
        if (html.isEmpty()) html = get(ORIGIN + "/detail?series_id=" + seriesId);
        html = unescape(html);
        String title = match(html, "\"series_name\"\\s*:\\s*\"([^\"]+)\"");
        if (title == null) title = match(html, "\"series_title\"\\s*:\\s*\"([^\"]+)\"");
        String cover = match(html, "\"series_cover\"\\s*:\\s*\"([^\"]+)\"");
        if (cover != null) cover = unescape(cover);
        int episodeCnt = parseInt(match(html, "\"episode_cnt\"\\s*:\\s*(\\d+)"), 1);
        int accessible = parseInt(match(html, "\"accessible_episode_cnt\"\\s*:\\s*(\\d+)"), Math.min(5, episodeCnt));

        List<String> vids = extractVidList(html, seriesId);
        String quality = DeviceSettings.INSTANCE.preferredQuality;
        if (quality == null || quality.isEmpty() || "auto".equals(quality)) quality = "360p";
        // 公共源实测稳定档：360p；高清档占位，失败时回退
        String[] qualityTry = qualityOrder(quality);

        JSONArray episodes = new JSONArray();
        int n = Math.max(vids.size(), episodeCnt);
        if (n < 1) n = 1;
        for (int i = 0; i < n; i++) {
            String vid = i < vids.size() ? vids.get(i) : "";
            boolean free = i < accessible && !vid.isEmpty();
            String url = "";
            String usedQ = "360p";
            if (free) {
                for (String q : qualityTry) {
                    String candidate = ORIGIN + "/video/public/" + vid + "/" + q + ".mp4";
                    // 不在主线程 HEAD；先按首选拼 URL，播放失败再降级由播放器侧处理
                    url = candidate;
                    usedQ = q;
                    break;
                }
            }
            episodes.put(new JSONObject()
                    .put("episode", i + 1)
                    .put("vid", vid)
                    .put("title", "第" + (i + 1) + "集")
                    .put("url", url)
                    .put("quality", usedQ)
                    .put("locked", !free)
                    .put("seriesId", seriesId));
        }
        // App 通道：尝试为锁定集补全播放地址（设备注册 + 签名取流）
        int appOk = 0;
        try {
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject ep = episodes.getJSONObject(i);
                if (!ep.optBoolean("locked") && !ep.optString("url").isEmpty()) continue;
                String vid = ep.optString("vid");
                if (vid.isEmpty()) continue;
                try {
                    JSONObject app = AppPlayClient.fetchEpisode(vid, DeviceSettings.INSTANCE.preferredQuality);
                    String appUrl = app.optString("url");
                    if (!appUrl.isEmpty()) {
                        ep.put("url", appUrl);
                        ep.put("locked", false);
                        ep.put("quality", app.optString("quality", "app"));
                        ep.put("source", "app");
                        appOk++;
                    }
                } catch (Exception ignoreOne) {
                    // 单集失败继续
                }
                if (i >= 12 && appOk == 0) break; // 签名若整体失败，避免长时间阻塞
            }
        } catch (Exception ignored) {}
        String channel = appOk > 0 ? "app+web" : "web-public";
        String note;
        if (appOk > 0) {
            note = "App 源已补全 " + appOk + " 集；其余可用公共源或稍后重试";
        } else if (accessible < episodeCnt) {
            note = "公共源可播前 " + accessible + " 集；App 签名取流未成功时可稍后重试";
        } else {
            note = "公共源";
        }
        return new JSONObject()
                .put("seriesId", seriesId)
                .put("title", title == null ? ("剧集 " + seriesId) : title)
                .put("cover", cover == null ? "" : cover)
                .put("episodeCnt", Math.max(episodeCnt, episodes.length()))
                .put("accessible", accessible + appOk)
                .put("sourceChannel", channel)
                .put("note", note)
                .put("episodes", episodes);
    }

    public static JSONObject resolvePlay(String seriesId, String vidOrEp) throws Exception {
        JSONObject d = detail(seriesId);
        JSONArray eps = d.optJSONArray("episodes");
        if (eps == null || eps.length() == 0) throw new IllegalStateException("无可用集数");
        int ep = 1;
        try { ep = Integer.parseInt(vidOrEp); } catch (Exception ignored) {}
        int idx = Math.max(0, Math.min(ep - 1, eps.length() - 1));
        JSONObject item = eps.getJSONObject(idx);
        if (item.optBoolean("locked") || item.optString("url").isEmpty()) {
            // try first free
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.getJSONObject(i);
                if (!e.optBoolean("locked") && !e.optString("url").isEmpty()) {
                    item = e;
                    break;
                }
            }
        }
        if (item.optString("url").isEmpty()) {
            throw new IllegalStateException("本集无公共播放地址（可能需 App 源）");
        }
        return new JSONObject()
                .put("url", item.optString("url"))
                .put("title", d.optString("title") + " " + item.optString("title"))
                .put("seriesId", seriesId)
                .put("vid", item.optString("vid"))
                .put("quality", item.optString("quality"))
                .put("cover", d.optString("cover"))
                .put("source", "web-public");
    }

    private static String[] qualityOrder(String preferred) {
        if ("1080p".equals(preferred)) return new String[]{"1080p", "720p", "360p"};
        if ("720p".equals(preferred)) return new String[]{"720p", "360p"};
        if ("360p".equals(preferred)) return new String[]{"360p"};
        return new String[]{"720p", "360p"}; // auto
    }

    private static List<String> extractVidList(String html, String seriesId) {
        List<String> vids = new ArrayList<>();
        Matcher m = Pattern.compile("\"vid_list\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(html);
        if (m.find()) {
            Matcher vm = Pattern.compile("\"(\\d+)\"").matcher(m.group(1));
            while (vm.find()) {
                String v = vm.group(1);
                if (!v.equals(seriesId) && !vids.contains(v)) vids.add(v);
            }
        }
        if (vids.isEmpty()) {
            m = Pattern.compile("video/public/(\\d+)/").matcher(html);
            while (m.find()) {
                String v = m.group(1);
                if (!v.equals(seriesId) && !vids.contains(v)) vids.add(v);
            }
        }
        return vids;
    }

    private static JSONArray parseSeriesFromHtml(String html) throws Exception {
        html = unescape(html);
        Map<String, JSONObject> map = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"series_id\"\\s*:\\s*\"(\\d+)\"").matcher(html);
        while (m.find()) {
            String sid = m.group(1);
            String window = html.substring(Math.max(0, m.start() - 200), Math.min(html.length(), m.start() + 900));
            String title = match(window, "\"series_name\"\\s*:\\s*\"([^\"]+)\"");
            if (title == null) title = match(window, "\"series_title\"\\s*:\\s*\"([^\"]+)\"");
            String cover = match(window, "\"series_cover\"\\s*:\\s*\"([^\"]+)\"");
            String intro = match(window, "\"series_intro\"\\s*:\\s*\"([^\"]*)\"");
            JSONObject o = map.get(sid);
            if (o == null) o = new JSONObject().put("seriesId", sid);
            if (title != null) o.put("title", title);
            if (cover != null) o.put("cover", unescape(cover));
            if (intro != null) o.put("intro", intro.length() > 80 ? intro.substring(0, 80) : intro);
            if (!o.has("title")) o.put("title", "剧集 " + sid);
            if (!o.has("cover")) o.put("cover", "");
            map.put(sid, o);
        }
        JSONArray arr = new JSONArray();
        for (JSONObject o : map.values()) arr.put(o);
        return arr;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (Exception e) { return def; }
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
        c.setRequestProperty("Accept", "application/json,text/html,*/*");
        c.setRequestProperty("Referer", ORIGIN + "/");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code >= 400) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 2_500_000) break;
            }
        }
        return sb.toString();
    }
}
