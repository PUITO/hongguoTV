package com.hongguotv.phone;

import org.json.JSONArray;
import org.json.JSONObject;

/** 当前片源 + 遥控状态（电视轮询）。 */
public final class NowPlaying {
    public volatile String url = "";
    public volatile String title = "";
    public volatile long updatedAt;
    /** 播放列表（自动连播） */
    public final JSONArray playlist = new JSONArray();
    public volatile int index = 0;

    public volatile boolean playing = true;
    public volatile float speed = 1.0f;
    public volatile boolean autoNext = true;
    public volatile long seekMs = -1; // -1 = 无 seek 指令
    public volatile long commandId = 0;

    public synchronized void set(String url, String title) {
        this.url = url == null ? "" : url.trim();
        this.title = title == null ? "" : title.trim();
        this.updatedAt = System.currentTimeMillis();
        this.playing = true;
        this.seekMs = -1;
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
        url = item.optString("url", "");
        title = item.optString("title", "");
        updatedAt = System.currentTimeMillis();
        playing = true;
        seekMs = 0;
        bump();
    }

    public synchronized boolean next() {
        if (!autoNext || playlist.length() == 0) return false;
        if (index + 1 >= playlist.length()) return false;
        index++;
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
        } else if ("autonext".equals(action)) {
            autoNext = body.optBoolean("value", true);
        } else if ("next".equals(action)) {
            next();
            return;
        } else if ("prev".equals(action)) {
            if (index > 0) { index--; applyIndex(); }
            return;
        }
        bump();
    }

    private void bump() { commandId++; }

    public synchronized JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("ok", true)
                .put("url", url == null ? "" : url)
                .put("title", title == null ? "" : title)
                .put("updatedAt", updatedAt)
                .put("playing", playing)
                .put("speed", speed)
                .put("autoNext", autoNext)
                .put("seekMs", seekMs)
                .put("commandId", commandId)
                .put("index", index)
                .put("playlistSize", playlist.length());
    }
}
