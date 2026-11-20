package com.hongguotv.phone;

import com.hongguotv.shared.CastProtocol;

import org.json.JSONObject;

/**
 * 绑定后由手机统一管理的全部设置（电视只跟随）。
 */
public final class DeviceSettings {
    public static final DeviceSettings INSTANCE = new DeviceSettings();

    /** direct | proxy */
    public volatile String playMode = CastProtocol.MODE_PROXY;
    public volatile boolean autoNext = true;
    public volatile float defaultSpeed = 1.5f;
    /** auto|360p|480p|720p|1080p */
    public volatile String preferredQuality = "1080p";
    /** false=只解析用户选定清晰度，不自动降到更低档/公共试看 */
    public volatile boolean allowQualityFallback = false;
    public volatile boolean showTvOverlay = true;
    public volatile boolean tvKeepScreenOn = true;
    public volatile boolean allowTvLocalControl = true;
    public volatile int overlayHideMs = 5000;
    public volatile long revision = 1;

    /** 预缓存：连播/推流提前解密的集数 */
    public volatile int prefetchEpisodes = 2;
    /** 明文缓存上限（MB），超出按最旧删除 */
    public volatile int cacheMaxMb = 512;
    /** 缓存最多保留文件数 */
    public volatile int cacheMaxFiles = 12;

    public volatile String boundTvIp = "";
    public volatile int boundTvPort = CastProtocol.TV_BIND_PORT;
    public volatile String boundSession = "";
    public volatile boolean bound = false;

    private DeviceSettings() {}

    public synchronized boolean isProxy() {
        return CastProtocol.MODE_PROXY.equals(playMode);
    }

    public synchronized void applyJson(JSONObject o) {
        if (o == null) return;
        if (o.has("playMode")) {
            String m = o.optString("playMode", playMode);
            if (CastProtocol.MODE_DIRECT.equals(m) || CastProtocol.MODE_PROXY.equals(m)) playMode = m;
        }
        if (o.has("autoNext")) autoNext = o.optBoolean("autoNext", autoNext);
        if (o.has("defaultSpeed")) {
            float s = (float) o.optDouble("defaultSpeed", defaultSpeed);
            if (s < 0.75f) s = 0.75f;
            if (s > 3.0f) s = 3.0f;
            defaultSpeed = s;
        }
        if (o.has("showTvOverlay")) showTvOverlay = o.optBoolean("showTvOverlay", showTvOverlay);
        if (o.has("tvKeepScreenOn")) tvKeepScreenOn = o.optBoolean("tvKeepScreenOn", tvKeepScreenOn);
        if (o.has("allowTvLocalControl")) allowTvLocalControl = o.optBoolean("allowTvLocalControl", allowTvLocalControl);
        if (o.has("overlayHideMs")) overlayHideMs = Math.max(1000, o.optInt("overlayHideMs", overlayHideMs));
        if (o.has("allowQualityFallback")) allowQualityFallback = o.optBoolean("allowQualityFallback", allowQualityFallback);
        if (o.has("preferredQuality")) {
            String q = o.optString("preferredQuality", preferredQuality);
            if (q != null && !q.isEmpty()) preferredQuality = q;
        }
        if (o.has("prefetchEpisodes")) {
            int n = o.optInt("prefetchEpisodes", prefetchEpisodes);
            prefetchEpisodes = Math.max(0, Math.min(5, n));
        }
        if (o.has("cacheMaxMb")) {
            int n = o.optInt("cacheMaxMb", cacheMaxMb);
            cacheMaxMb = Math.max(64, Math.min(4096, n));
        }
        if (o.has("cacheMaxFiles")) {
            int n = o.optInt("cacheMaxFiles", cacheMaxFiles);
            cacheMaxFiles = Math.max(2, Math.min(50, n));
        }
        revision++;
    }

    public synchronized void setBound(String tvIp, int port, String session) {
        boundTvIp = tvIp == null ? "" : tvIp;
        boundTvPort = port;
        boundSession = session == null ? "" : session;
        bound = tvIp != null && !tvIp.isEmpty();
        revision++;
    }

    public synchronized void clearBound() {
        bound = false;
        boundTvIp = "";
        boundSession = "";
        revision++;
    }

    public synchronized JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("playMode", playMode)
                .put("autoNext", autoNext)
                .put("defaultSpeed", defaultSpeed)
                .put("preferredQuality", preferredQuality)
                .put("allowQualityFallback", allowQualityFallback)
                .put("showTvOverlay", showTvOverlay)
                .put("tvKeepScreenOn", tvKeepScreenOn)
                .put("allowTvLocalControl", allowTvLocalControl)
                .put("overlayHideMs", overlayHideMs)
                .put("prefetchEpisodes", prefetchEpisodes)
                .put("cacheMaxMb", cacheMaxMb)
                .put("cacheMaxFiles", cacheMaxFiles)
                .put("revision", revision)
                .put("bound", bound)
                .put("boundTvIp", boundTvIp == null ? "" : boundTvIp)
                .put("boundTvPort", boundTvPort)
                .put("boundSession", boundSession == null ? "" : boundSession);
    }
}
