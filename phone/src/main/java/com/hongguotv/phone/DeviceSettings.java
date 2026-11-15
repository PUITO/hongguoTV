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
    /** auto|360p|720p|1080p — web 公共源目前仅 360p，App 源后续按此选档 */
    public volatile String preferredQuality = "720p";
    public volatile boolean showTvOverlay = true;
    public volatile boolean tvKeepScreenOn = true;
    public volatile boolean allowTvLocalControl = true; // 电视本机按键是否可用
    public volatile int overlayHideMs = 5000;
    public volatile long revision = 1;

    /** 已绑定的电视 */
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
            if (s < 0.5f) s = 0.5f;
            if (s > 2.0f) s = 2.0f;
            defaultSpeed = s;
        }
        if (o.has("showTvOverlay")) showTvOverlay = o.optBoolean("showTvOverlay", showTvOverlay);
        if (o.has("tvKeepScreenOn")) tvKeepScreenOn = o.optBoolean("tvKeepScreenOn", tvKeepScreenOn);
        if (o.has("allowTvLocalControl")) allowTvLocalControl = o.optBoolean("allowTvLocalControl", allowTvLocalControl);
        if (o.has("overlayHideMs")) overlayHideMs = Math.max(1000, o.optInt("overlayHideMs", overlayHideMs));
        if (o.has("preferredQuality")) {
            String q = o.optString("preferredQuality", preferredQuality);
            if (q != null && !q.isEmpty()) preferredQuality = q;
        }
        revision++;
    }

    public synchronized void setBound(String tvIp, int port, String session) {
        boundTvIp = tvIp == null ? "" : tvIp;
        boundTvPort = port;
        boundSession = session == null ? "" : session;
        bound = boundTvIp.length() > 0;
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
                .put("showTvOverlay", showTvOverlay)
                .put("tvKeepScreenOn", tvKeepScreenOn)
                .put("allowTvLocalControl", allowTvLocalControl)
                .put("overlayHideMs", overlayHideMs)
                .put("preferredQuality", preferredQuality)
                .put("revision", revision)
                .put("bound", bound)
                .put("boundTvIp", boundTvIp)
                .put("boundTvPort", boundTvPort);
    }
}
