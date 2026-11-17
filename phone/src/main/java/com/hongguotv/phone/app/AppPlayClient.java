package com.hongguotv.phone.app;

import com.hongguotv.phone.AppLog;
import com.hongguotv.phone.DeviceSettings;
import com.hongguotv.phone.core.AppPlayback;
import com.hongguotv.phone.core.PlaintextCache;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * 多清晰度回退：解密失败或文件异常时换下一档，减少有声无画 / 试看回退。
 */
public final class AppPlayClient {
    private AppPlayClient() {}

    public static JSONObject fetchEpisode(String vid, String preferredQuality) throws Exception {
        return fetchEpisode(vid, preferredQuality, true);
    }

    public static JSONObject fetchEpisode(String vid, String preferredQuality, boolean decrypt) throws Exception {
                List<AppPlayback.Stream> ranked = AppPlayback.fetchRanked(vid, preferredQuality);
        if (ranked == null || ranked.isEmpty()) {
            throw new IllegalStateException("app play empty");
        }
        boolean allowFb = DeviceSettings.INSTANCE.allowQualityFallback;
        int wantH = heightWanted(preferredQuality);
        if (!allowFb) {
            // 只保留与用户所选分辨率匹配的档（不自动降级）
            java.util.ArrayList<AppPlayback.Stream> exact = new java.util.ArrayList<>();
            for (AppPlayback.Stream s : ranked) {
                if (matchesPreferred(s, preferredQuality, wantH)) exact.add(s);
            }
            AppLog.i("严格清晰度 pref=" + preferredQuality + " 候选=" + ranked.size()
                    + " 匹配=" + exact.size() + " fallback=off");
            if (exact.isEmpty()) {
                throw new IllegalStateException("无匹配清晰度 " + preferredQuality
                        + "（已关闭降级；可在设置开启「允许清晰度降级」）");
            }
            ranked = exact;
        }
        Exception last = null;
        int limit = allowFb ? Math.min(6, ranked.size()) : ranked.size();
        for (int i = 0; i < limit; i++) {
            AppPlayback.Stream stream = ranked.get(i);
            String codec = stream.getCodec() == null ? "" : stream.getCodec();
            String cq = (codec + " " + stream.getQuality()).toLowerCase();
            if (cq.contains("bytevc2") || cq.contains("bvc2")) {
                AppLog.w("跳过 ByteVC2（系统无法解画） q=" + stream.getQuality() + " codec=" + codec);
                continue;
            }
            // 无 codec 时：低清高度高度可疑，优先跳过若已有更高档可试
            if (codec.isEmpty() && stream.getHeight() > 0 && stream.getHeight() < 1080 && i + 1 < limit) {
                boolean has1080 = false;
                for (int j = i + 1; j < ranked.size(); j++) {
                    if (ranked.get(j).getHeight() >= 1080) { has1080 = true; break; }
                }
                if (has1080) {
                    AppLog.w("暂缓低清无codec档 q=" + stream.getQuality() + " h=" + stream.getHeight());
                    continue;
                }
            }
            AppLog.i("尝试清晰度#" + (i + 1) + " q=" + stream.getQuality()
                    + " h=" + stream.getHeight() + " enc=" + stream.getEncrypted()
                    + " codec=" + codec);
            try {
                String qLabel = stream.getQuality();
                if (qLabel == null || qLabel.isEmpty() || "app".equalsIgnoreCase(qLabel)) {
                    int h = stream.getHeight();
                    if (h >= 1080) qLabel = "1080p";
                    else if (h >= 720) qLabel = "720p";
                    else if (h >= 480) qLabel = "480p";
                    else if (h > 0) qLabel = h + "p";
                    else qLabel = "app";
                }
                JSONObject o = new JSONObject()
                        .put("url", stream.getUrl())
                        .put("quality", qLabel)
                        .put("vid", vid)
                        .put("source", "app")
                        .put("height", stream.getHeight())
                        .put("encrypted", stream.getEncrypted())
                        .put("codec", stream.getCodec() == null ? "" : stream.getCodec())
                        .put("spadeA", stream.getSpadeA() == null ? "" : stream.getSpadeA());
                if (decrypt) {
                    File plain = PlaintextCache.ensurePlainFile(
                            stream.getUrl(), stream.getSpadeA(), stream.getEncrypted());
                    if (plain.length() < 64 * 1024) {
                        throw new IllegalStateException("plain too small " + plain.length());
                    }
                    o.put("plainPath", plain.getAbsolutePath());
                    o.put("url", "file://" + plain.getAbsolutePath());
                    o.put("source", stream.getEncrypted() ? "app-decrypted" : "app-clear");
                    AppLog.i("明文就绪 q=" + stream.getQuality() + " " + plain.getName()
                            + " size=" + plain.length());
                }
                return o;
            } catch (Exception e) {
                last = e;
                AppLog.w("清晰度失败 q=" + stream.getQuality() + " : " + e.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("app play empty");
    }

    private static int heightWanted(String preferred) {
        if (preferred == null) return 1080;
        if (preferred.contains("360")) return 360;
        if (preferred.contains("480")) return 480;
        if (preferred.contains("540")) return 540;
        if (preferred.contains("720")) return 720;
        if (preferred.contains("1080")) return 1080;
        return 1080;
    }

    private static boolean matchesPreferred(AppPlayback.Stream s, String preferred, int wantH) {
        String q = (s.getQuality() == null ? "" : s.getQuality()).toLowerCase();
        String pref = preferred == null ? "" : preferred.toLowerCase();
        if (pref.contains("1080") && (q.contains("1080") || s.getHeight() >= 900)) return true;
        if (pref.contains("720") && (q.contains("720") || (s.getHeight() >= 600 && s.getHeight() < 900))) return true;
        if (pref.contains("540") && (q.contains("540") || (s.getHeight() >= 500 && s.getHeight() < 600))) return true;
        if (pref.contains("480") && (q.contains("480") || (s.getHeight() >= 400 && s.getHeight() < 500))) return true;
        if (pref.contains("360") && (q.contains("360") || (s.getHeight() > 0 && s.getHeight() < 400))) return true;
        int h = s.getHeight();
        if (h <= 0) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})").matcher(q);
            if (m.find()) {
                try { h = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
        }
        if (h <= 0) return false;
        if (wantH >= 1080) return h >= 900;
        if (wantH >= 720) return h >= 600 && h < 900;
        if (wantH >= 480) return h >= 400 && h < 600;
        return h < 400;
    }
}
