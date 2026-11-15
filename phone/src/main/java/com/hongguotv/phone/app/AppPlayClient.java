package com.hongguotv.phone.app;

import com.hongguotv.phone.AppLog;
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
        Exception last = null;
        // 最多尝试 4 档
        int limit = Math.min(4, ranked.size());
        for (int i = 0; i < limit; i++) {
            AppPlayback.Stream stream = ranked.get(i);
            AppLog.i("尝试清晰度#" + (i + 1) + " q=" + stream.getQuality()
                    + " h=" + stream.getHeight() + " enc=" + stream.getEncrypted()
                    + " codec=" + stream.getCodec());
            try {
                JSONObject o = new JSONObject()
                        .put("url", stream.getUrl())
                        .put("quality", stream.getQuality())
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
}
