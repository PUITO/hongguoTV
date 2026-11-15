package com.hongguotv.phone;

import com.hongguotv.shared.CastProtocol;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NowPlaying {
    public volatile String sourceUrl = "";
    public volatile String title = "";
    public volatile long updatedAt;
    public final JSONArray playlist = new JSONArray();
    public volatile int index = 0;
    public volatile boolean playing = true;
    public volatile float speed = 1.0f;
    public volatile long seekMs = -1;
    public volatile long commandId = 0;
    public volatile String seriesId = "";

    public String playUrlForTv(String phoneIp, int port) {
        DeviceSettings s = DeviceSettings.INSTANCE;
        if (s.isProxy() && sourceUrl != null && !sourceUrl.isEmpty()) {
            return "http://" + phoneIp + ":" + port + CastProtocol.PATH_STREAM_CURRENT;
        }
        return sourceUrl == null ? "" : sourceUrl;
    }

    public synchronized void set(String url, String title) {
        this.sourceUrl = url == null ? "" : url.trim();
        this.title = title == null ? "" : title.trim();
        this.updatedAt = System.currentTimeMillis();
        this.playing = true;
        this.seekMs = -1;
        this.speed = DeviceSettings.INSTANCE.defaultSpeed;
        bump();
    }

    public synchronized void setPlaylist(JSONArray list, int startIndex) {
        while (playlist.length() > 0) playlist.remove(0);
        if (list != null) {
            for (int i = 0; i < list.length(); i++) playlist.put(list.opt(i));
        }
        index = Math.max(0, Math.min(startIndex, Math.max(0, playlist.length() - 1)));
        applyIndex();
    }

    public synchronized void applyIndex() {
        if (playlist.length() == 0) return;
        JSONObject item = playlist.optJSONObject(index);
        if (item == null) return;
        sourceUrl = item.optString("url", "");
        title = item.optString("title", "");
        seriesId = item.optString("seriesId", seriesId);
        updatedAt = System.currentTimeMillis();
        playing = true;
        seekMs = 0;
        speed = DeviceSettings.INSTANCE.defaultSpeed;
        bump();
    }

    public synchronized boolean next() {
        if (playlist.length() == 0) return false;
        if (index + 1 >= playlist.length()) return false;
        index++;
        applyIndex();
        return true;
    }

    public synchronized boolean prev() {
        if (index <= 0) return false;
        index--;
        applyIndex();
        return true;
    }

    public synchronized void control(String action, JSONObject body) {
        if ("play".equals(action)) playing = true;
        else if ("pause".equals(action)) playing = false;
        else if ("toggle".equals(action)) playing = !playing;
        else if ("speed".equals(action)) {
            float s = (float) body.optDouble("value", 1.0);
            if (s < 0.5f) s = 0.5f;
            if (s > 2.0f) s = 2.0f;
            speed = s;
        } else if ("seek".equals(action)) {
            seekMs = body.optLong("positionMs", 0);
        } else if ("next".equals(action)) {
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

    private void bump() { commandId++; }

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
