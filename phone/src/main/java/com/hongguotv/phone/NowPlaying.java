package com.hongguotv.phone;

import com.hongguotv.shared.CastProtocol;
import com.hongguotv.phone.AppLog;
import com.hongguotv.phone.app.AppPlayClient;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NowPlaying {
    public volatile String sourceUrl = "";
    public volatile String title = "";
    public volatile long updatedAt;
    public final JSONArray playlist = new JSONArray();
    public volatile int index = 0;
    public volatile boolean playing = true;
    public volatile float speed = 1.5f;
    public volatile long seekMs = -1;
    public volatile long commandId = 0;
    public volatile String seriesId = "";
    public volatile String seriesTitle = "";
    public volatile String seriesCover = "";
    private android.content.Context appCtx;

    public void attachContext(android.content.Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    /** 推送/联播切集后写回手机历史，避免只推 TV 导致历史丢集数 */
    private void syncHistory() {
        if (appCtx == null) return;
        if (seriesId == null || seriesId.isEmpty()) return;
        if (index < 0) return;
        try {
            WatchHistory.updateProgress(appCtx, seriesId, index, seriesTitle, seriesCover);
            LastCastStore.save(appCtx, this);
            AppLog.i("历史同步 series=" + seriesId + " ep=" + (index + 1));
        } catch (Exception e) {
            AppLog.w("历史同步失败: " + e.getMessage());
        }
    }

    public String playUrlForTv(String phoneIp, int port) {
        if (sourceUrl == null || sourceUrl.isEmpty()) return "";
        // 明文文件 / 任意源：TV 一律走手机代理，TV 不直连红果 CDN
        // 必须带 index/updatedAt，否则联播时 URL 不变，TV 不会重新加载下一集
        String tag = "?i=" + index + "&t=" + updatedAt;
        if (sourceUrl.startsWith("file://") || sourceUrl.startsWith("/")
                || DeviceSettings.INSTANCE.isProxy()
                || sourceUrl.contains("qznovelvod")
                || sourceUrl.contains("byteimg")
                || sourceUrl.contains("tos-cn")) {
            return "http://" + phoneIp + ":" + port + CastProtocol.PATH_STREAM_CURRENT + tag;
        }
        // 直链模式也加 cache-buster，避免同路径不重载
        if (sourceUrl.contains("?")) return sourceUrl + "&_hg=" + index + "_" + updatedAt;
        return sourceUrl + "?_hg=" + index + "_" + updatedAt;
    }

    public synchronized void set(String url, String title) {
        this.sourceUrl = url == null ? "" : url.trim();
        this.title = title == null ? "" : title.trim();
        this.updatedAt = System.currentTimeMillis();
        this.playing = true;
        this.seekMs = -1;
        this.speed = DeviceSettings.INSTANCE.defaultSpeed;
        bump();
        syncHistory();
    }

    public synchronized void setPlaylist(JSONArray list, int startIndex) {
        while (playlist.length() > 0) playlist.remove(0);
        if (list != null) {
            for (int i = 0; i < list.length(); i++) playlist.put(list.opt(i));
        }
        index = Math.max(0, Math.min(startIndex, Math.max(0, playlist.length() - 1)));
        if (playlist.length() > 0) {
            JSONObject first = playlist.optJSONObject(Math.max(0, index));
            if (first != null) {
                if (seriesId == null || seriesId.isEmpty()) {
                    seriesId = first.optString("seriesId", "");
                }
                String st = first.optString("seriesTitle", "");
                if (!st.isEmpty()) seriesTitle = st;
                String cv = first.optString("cover", "");
                if (!cv.isEmpty()) seriesCover = cv;
            }
        }
        applyIndex();
    }

    public synchronized void applyIndex() {
        if (playlist.length() == 0) return;
        JSONObject item = playlist.optJSONObject(index);
        if (item == null) return;
        sourceUrl = item.optString("url", "");
        title = item.optString("title", "");
        seriesId = item.optString("seriesId", seriesId);
        if (seriesTitle == null || seriesTitle.isEmpty()) {
            seriesTitle = item.optString("seriesTitle", "");
        }
        if (seriesCover == null || seriesCover.isEmpty()) {
            seriesCover = item.optString("cover", "");
        }
        updatedAt = System.currentTimeMillis();
        playing = true;
        seekMs = 0;
        // 切集保持当前倍速
        bump();
        syncHistory();
        // 当前集已有 url 时，后台预取下一集（无 url 则等 resolve 后再预取）
        if (sourceUrl != null && !sourceUrl.isEmpty()) {
            try { EpisodePrefetcher.prefetchPlaylist(playlist, index); } catch (Exception ignored) {}
        }
    }

    public synchronized boolean next() {
        if (playlist.length() == 0) return false;
        if (index + 1 >= playlist.length()) return false;
        index++;
        applyIndex();
        resolvePreferredQualityLocked();
        // 切到新集后立刻预取再下一集
        try { EpisodePrefetcher.prefetchPlaylist(playlist, index); } catch (Exception ignored) {}
        return true;
    }

    public synchronized boolean prev() {
        if (index <= 0) return false;
        index--;
        applyIndex();
        resolvePreferredQualityLocked();
        try { EpisodePrefetcher.prefetchPlaylist(playlist, index); } catch (Exception ignored) {}
        return true;
    }

    /**
     * 联播/切集后按设置的 preferredQuality 重新取流，避免列表里残留的公共 360p。
     * 调用方须已持有 this 锁。
     */
    /** 供遥控恢复推流在持锁外触发重解析 */
    public synchronized void resolvePreferredQualityPublic() {
        resolvePreferredQualityLocked();
    }

    private void resolvePreferredQualityLocked() {
        try {
            JSONObject item = playlist.optJSONObject(index);
            if (item == null) return;
            String vid = item.optString("vid", "");
            if (vid.isEmpty()) return;
            String pref = DeviceSettings.INSTANCE.preferredQuality;
            if (pref == null || pref.isEmpty()) pref = "1080p";
            String curQ = item.optString("quality", "");
            String src = item.optString("source", "");
            String url = item.optString("url", sourceUrl);
            boolean weak = url == null || url.isEmpty()
                    || "web-public".equals(src)
                    || curQ.contains("360")
                    || url.contains("/360p.")
                    || url.contains("hongguoduanju.com/video/public");
            boolean mismatch = !"360p".equals(pref) && !curQ.toLowerCase().contains(
                    pref.toLowerCase().replace("p", ""));
            // 已是目标清晰度的本地解密文件则跳过
            if (!weak && !mismatch && url.startsWith("file://")) return;
            if (!weak && !mismatch && "app-decrypted".equals(src)) return;
            AppLog.i("切集重解析 vid=" + vid + " pref=" + pref + " wasQ=" + curQ + " src=" + src);
            JSONObject r = AppPlayClient.fetchEpisode(vid, pref);
            String nu = r.optString("url", "");
            if (nu.isEmpty()) return;
            sourceUrl = nu;
            String nq = r.optString("quality", pref);
            item.put("url", nu);
            item.put("quality", nq);
            item.put("source", r.optString("source", "app"));
            item.put("height", r.optInt("height", 0));
            updatedAt = System.currentTimeMillis();
            if (CastServerService.CACHE != null
                    && !nu.startsWith("file://") && !nu.startsWith("/")) {
                try { CastServerService.CACHE.setCurrent(nu); } catch (Exception ignored) {}
            }
            bump();
            AppLog.i("切集重解析成功 q=" + nq + " url=" + AppLog.clip(nu, 80));
            try { EpisodePrefetcher.prefetchPlaylist(playlist, index); } catch (Exception ignored) {}
        } catch (Exception e) {
            AppLog.w("切集重解析失败: " + e.getMessage());
        }
    }

    public synchronized void control(String action, JSONObject body) {
        if ("play".equals(action)) playing = true;
        else if ("pause".equals(action)) playing = false;
        else if ("toggle".equals(action)) playing = !playing;
        else if ("speed".equals(action)) {
            float s = (float) body.optDouble("value", 1.0);
            if (s < 0.75f) s = 0.75f;
            if (s > 3.0f) s = 3.0f;
            speed = s;
        } else if ("seek".equals(action)) {
            seekMs = body.optLong("positionMs", 0);
        } else if ("next".equals(action)) {
            // 播完触发的 next：受 autoNext 开关控制；遥控手动 next 始终切集
            if (body != null && body.optBoolean("fromComplete", false)
                    && !DeviceSettings.INSTANCE.autoNext) {
                playing = false;
                bump();
                return;
            }
            next();
            return;
        } else if ("prev".equals(action)) {
            prev();
            return;
        } else if ("playMode".equals(action)) {
            try {
                DeviceSettings.INSTANCE.applyJson(new JSONObject().put("playMode", body.optString("value", CastProtocol.MODE_PROXY)));
            } catch (Exception ignored) {}
        }
        bump();
    }

    public void bump() { commandId++; }

    public synchronized JSONObject toJson(String phoneIp, int port) throws Exception {
        DeviceSettings s = DeviceSettings.INSTANCE;
        return new JSONObject()
                .put("ok", true)
                .put("url", playUrlForTv(phoneIp, port))
                .put("sourceUrl", sourceUrl == null ? "" : sourceUrl)
                .put("title", title == null ? "" : title)
                .put("updatedAt", updatedAt)
                .put("playing", playing)
                .put("speed", speed)
                .put("seekMs", seekMs)
                .put("commandId", commandId)
                .put("index", index)
                .put("playlistSize", playlist.length())
                .put("seriesId", seriesId == null ? "" : seriesId)
                .put("playlist", playlist)
                .put("playMode", s.playMode)
                .put("useProxy", s.isProxy())
                .put("autoNext", s.autoNext)
                .put("settings", s.toJson());
    }
}
