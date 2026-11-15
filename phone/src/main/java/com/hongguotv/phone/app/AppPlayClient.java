package com.hongguotv.phone.app;

import com.hongguotv.phone.AppLog;
import com.hongguotv.phone.core.AppPlayback;
import com.hongguotv.phone.core.PlaintextCache;

import org.json.JSONObject;

import java.io.File;

/**
 * 取流 + 可选解密为本地明文。
 */
public final class AppPlayClient {
    private AppPlayClient() {}

    public static JSONObject fetchEpisode(String vid, String preferredQuality) throws Exception {
        return fetchEpisode(vid, preferredQuality, true);
    }

    public static JSONObject fetchEpisode(String vid, String preferredQuality, boolean decrypt) throws Exception {
        AppPlayback.Stream stream = AppPlayback.fetchBest(vid, preferredQuality);
        if (stream == null || stream.getUrl() == null || stream.getUrl().isEmpty()) {
            throw new IllegalStateException("app play empty");
        }
        JSONObject o = new JSONObject()
                .put("url", stream.getUrl())
                .put("quality", stream.getQuality())
                .put("vid", vid)
                .put("source", "app")
                .put("height", stream.getHeight())
                .put("encrypted", stream.getEncrypted())
                .put("spadeA", stream.getSpadeA() == null ? "" : stream.getSpadeA());
        if (decrypt) {
            try {
                File plain = PlaintextCache.ensurePlainFile(
                        stream.getUrl(), stream.getSpadeA(), stream.getEncrypted());
                o.put("plainPath", plain.getAbsolutePath());
                o.put("url", "file://" + plain.getAbsolutePath());
                o.put("source", stream.getEncrypted() ? "app-decrypted" : "app-clear");
                AppLog.i("明文就绪 " + plain.getName() + " " + plain.length());
            } catch (Exception e) {
                AppLog.e("解密/缓存失败，回退 CDN URL", e);
                // 保留 CDN url 供上层尝试
            }
        }
        return o;
    }
}
