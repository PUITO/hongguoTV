package com.hongguotv.phone;

import com.hongguotv.phone.app.AppPlayClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连播预缓存：当前集播放时提前解析/解密下一集（及后续 N-1 集）。
 */
public final class EpisodePrefetcher {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ep-prefetch");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final AtomicInteger GEN = new AtomicInteger();

    private EpisodePrefetcher() {}

    /** 从详情页 episodes 预取（首次推送时） */
    public static void prefetchAfter(JSONObject detail, int currentIndex) {
        if (detail == null) return;
        try {
            JSONArray eps = detail.getJSONArray("episodes");
            JSONArray pl = new JSONArray();
            String sid = detail.optString("seriesId");
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.getJSONObject(i);
                pl.put(new JSONObject()
                        .put("vid", e.optString("vid", e.optString("episodeId")))
                        .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                        .put("seriesId", sid)
                        .put("url", e.optString("url")));
            }
            prefetchPlaylist(pl, currentIndex);
        } catch (Exception e) {
            AppLog.w("prefetchAfter: " + e.getMessage());
        }
    }

    /** 从 NOW.playlist 预取：优先保证下一集立即可播 */
    public static void prefetchPlaylist(JSONArray playlist, int currentIndex) {
        if (playlist == null || playlist.length() == 0) return;
        int n = DeviceSettings.INSTANCE.prefetchEpisodes;
        if (n <= 0) n = 1; // 连播至少预取 1 集
        final int gen = GEN.incrementAndGet();
        final int start = currentIndex;
        final int count = n;
        final String quality = DeviceSettings.INSTANCE.preferredQuality;
        // 快照
        final JSONArray snap = new JSONArray();
        for (int i = 0; i < playlist.length(); i++) snap.put(playlist.opt(i));

        POOL.execute(() -> {
            for (int i = 1; i <= count; i++) {
                if (GEN.get() != gen) return;
                int idx = start + i;
                if (idx < 0 || idx >= snap.length()) break;
                try {
                    JSONObject ep = snap.optJSONObject(idx);
                    if (ep == null) continue;
                    String url = ep.optString("url");
                    if (url.startsWith("file://") || url.startsWith("/")) {
                        AppLog.i("预缓存跳过已明文 ep=" + (idx + 1));
                        // 仍可让 MediaCache 预热（file 无需）
                        continue;
                    }
                    String vid = ep.optString("vid", ep.optString("episodeId", ep.optString("id")));
                    if (vid.isEmpty()) {
                        AppLog.w("预缓存无 vid ep=" + (idx + 1));
                        continue;
                    }
                    AppLog.i("预缓存开始 ep=" + (idx + 1) + " vid=" + vid + " q=" + quality);
                    JSONObject resolved = AppPlayClient.fetchEpisode(vid, quality, true);
                    String plain = resolved.optString("url");
                    if (plain.isEmpty()) {
                        AppLog.w("预缓存空 url ep=" + (idx + 1));
                        continue;
                    }
                    // 写回 NOW.playlist（若仍是同一会话）
                    synchronized (CastServerService.NOW) {
                        if (GEN.get() != gen) return;
                        if (idx < CastServerService.NOW.playlist.length()) {
                            JSONObject live = CastServerService.NOW.playlist.optJSONObject(idx);
                            if (live != null) {
                                live.put("url", plain);
                                live.put("quality", resolved.optString("quality", quality));
                                live.put("source", resolved.optString("source", "app"));
                                live.put("height", resolved.optInt("height", 0));
                            }
                        }
                    }
                    // HTTP 源写入磁盘缓存，连播时 MediaCache 可命中
                    if (CastServerService.CACHE != null
                            && !plain.startsWith("file://") && !plain.startsWith("/")) {
                        try {
                            CastServerService.CACHE.prefetch(plain);
                        } catch (Exception ignored) {}
                    }
                    AppLog.i("预缓存完成 ep=" + (idx + 1) + " " + AppLog.clip(plain, 60));
                } catch (Exception e) {
                    AppLog.w("预缓存失败 ep=" + (idx + 1) + ": " + e.getMessage());
                }
            }
        });
    }

    /** 当前集已确定后：立刻预取下一集 */
    public static void prefetchNextFromNow() {
        NowPlaying now = CastServerService.NOW;
        if (now == null) return;
        synchronized (now) {
            if (now.playlist.length() == 0) return;
            prefetchPlaylist(now.playlist, now.index);
        }
    }
}
