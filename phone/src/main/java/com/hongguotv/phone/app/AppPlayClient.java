package com.hongguotv.phone.app;

import com.hongguotv.phone.core.AppPlayback;

import org.json.JSONObject;

/**
 * Java 桥：完整取流走 {@link AppPlayback}（匿名设备注册 + Argus 签名，无需登录）。
 */
public final class AppPlayClient {
    private AppPlayClient() {}

    public static JSONObject fetchEpisode(String vid, String preferredQuality) throws Exception {
        AppPlayback.Stream stream = AppPlayback.fetchBest(vid, preferredQuality);
        if (stream == null || stream.getUrl() == null || stream.getUrl().isEmpty()) {
            throw new IllegalStateException("app play empty");
        }
        return new JSONObject()
                .put("url", stream.getUrl())
                .put("quality", stream.getQuality())
                .put("vid", vid)
                .put("source", "app")
                .put("height", stream.getHeight());
    }
}
