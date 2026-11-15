package com.hongguotv.phone;

import com.hongguotv.phone.app.AppPlayClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连播/推流：后台预取并解密后续集，提升 TV 推流流畅度。
 */
public final class EpisodePrefetcher {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ep-prefetch");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final AtomicInteger GEN = new AtomicInteger();

    private EpisodePrefetcher() {}

    /**
     * 从当前 index 起预取后续 N 集（N=设置 prefetchEpisodes）。
     * episodes JSONArray 元素需含 vid 或可 resolve 的字段。
     */
    public static void prefetchAfter(JSONObject detail, int currentIndex) {
        if (detail == null) return;
        final int n = DeviceSettings.INSTANCE.prefetchEpisodes;
        if (n <= 0) return;
        final int gen = GEN.incrementAndGet();
        final String seriesId = detail.optString("seriesId");
        final JSONArray eps;
        try {
            eps = detail.getJSONArray("episodes");
        } catch (Exception e) {
            return;
        }
        final String quality = DeviceSettings.INSTANCE.preferredQuality;
        POOL.execute(() -> {
            for (int i = 1; i <= n; i++) {
                if (GEN.get() != gen) return; // 已有新任务
                int idx = currentIndex + i;
                if (idx < 0 || idx >= eps.length()) break;
                try {
                    JSONObject ep = eps.getJSONObject(idx);
                    String url = ep.optString("url");
                    if (url.startsWith("file://") || url.startsWith("/")) {
                        AppLog.i("预缓存跳过已明文 ep=" + (idx + 1));
                        continue;
                    }
                    String vid = ep.optString("vid", ep.optString("episodeId", ep.optString("id")));
                    if (vid.isEmpty()) {
                        AppLog.w("预缓存无 vid ep=" + (idx + 1));
                        continue;
                    }
                    AppLog.i("预缓存开始 ep=" + (idx + 1) + " vid=" + vid);
                    JSONObject resolved = AppPlayClient.fetchEpisode(vid, quality, true);
                    String plain = resolved.optString("url");
                    if (!plain.isEmpty()) {
                        ep.put("url", plain);
                        ep.put("locked", false);
                        ep.put("source", resolved.optString("source"));
                        ep.put("quality", resolved.optString("quality"));
                        AppLog.i("预缓存完成 ep=" + (idx + 1) + " " + AppLog.clip(plain, 60));
                    }
                } catch (Exception e) {
                    AppLog.w("预缓存失败 ep=" + (idx + 1) + ": " + e.getMessage());
                }
            }
        });
    }
}
