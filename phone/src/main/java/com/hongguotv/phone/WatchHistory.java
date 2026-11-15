package com.hongguotv.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public final class WatchHistory {
    private static final String PREF = "watch_history";
    private static final String KEY = "items";
    private static final int MAX = 40;

    private WatchHistory() {}

    public static JSONArray list(Context ctx) {
        try {
            String raw = prefs(ctx).getString(KEY, "[]");
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 打开详情时入历史；若已有记录则保留 lastEpisode */
    public static void add(Context ctx, JSONObject item) {
        try {
            JSONArray old = list(ctx);
            String sid = item.optString("seriesId");
            JSONObject prev = find(old, sid);
            if (prev != null) {
                if (!item.has("lastEpisode") && prev.has("lastEpisode")) {
                    item.put("lastEpisode", prev.optInt("lastEpisode"));
                    item.put("lastIndex", prev.optInt("lastIndex", prev.optInt("lastEpisode") - 1));
                }
                if (!item.has("lastIndex") && prev.has("lastIndex")) {
                    item.put("lastIndex", prev.optInt("lastIndex"));
                }
            }
            item.put("ts", System.currentTimeMillis());
            JSONArray next = new JSONArray();
            next.put(item);
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (sid.equals(o.optString("seriesId"))) continue;
                next.put(o);
                if (next.length() >= MAX) break;
            }
            prefs(ctx).edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * 记录最后播放集（0-based index）。会置顶该剧。
     */
    public static void updateProgress(Context ctx, String seriesId, int index0,
                                      String title, String cover) {
        if (seriesId == null || seriesId.isEmpty() || index0 < 0) return;
        try {
            JSONArray old = list(ctx);
            JSONObject prev = find(old, seriesId);
            JSONObject item = prev != null ? prev : new JSONObject();
            item.put("seriesId", seriesId);
            if (title != null && !title.isEmpty()) item.put("title", title);
            else if (!item.has("title")) item.put("title", seriesId);
            if (cover != null && !cover.isEmpty()) item.put("cover", cover);
            item.put("lastIndex", index0);
            item.put("lastEpisode", index0 + 1); // 展示用 1-based
            item.put("ts", System.currentTimeMillis());
            JSONArray next = new JSONArray();
            next.put(item);
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (seriesId.equals(o.optString("seriesId"))) continue;
                next.put(o);
                if (next.length() >= MAX) break;
            }
            prefs(ctx).edit().putString(KEY, next.toString()).apply();
            AppLog.i("历史进度 series=" + seriesId + " 看到第" + (index0 + 1) + "集");
        } catch (Exception e) {
            AppLog.w("updateProgress: " + e.getMessage());
        }
    }

    public static int lastIndex(Context ctx, String seriesId) {
        if (seriesId == null) return -1;
        try {
            JSONObject o = find(list(ctx), seriesId);
            if (o == null) return -1;
            if (o.has("lastIndex")) return Math.max(0, o.optInt("lastIndex"));
            if (o.has("lastEpisode")) return Math.max(0, o.optInt("lastEpisode") - 1);
        } catch (Exception ignored) {}
        return -1;
    }

    public static void remove(Context ctx, String seriesId) {
        if (seriesId == null || seriesId.isEmpty()) return;
        try {
            JSONArray old = list(ctx);
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (seriesId.equals(o.optString("seriesId"))) continue;
                next.put(o);
            }
            prefs(ctx).edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().putString(KEY, "[]").apply();
    }

    private static JSONObject find(JSONArray arr, String seriesId) {
        if (arr == null || seriesId == null) return null;
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (seriesId.equals(o.optString("seriesId"))) return o;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
