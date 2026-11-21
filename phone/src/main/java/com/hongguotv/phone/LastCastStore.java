package com.hongguotv.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 持久化最近一次推流会话，供遥控「继续推流」使用（不依赖可能不同步的历史列表）。
 */
public final class LastCastStore {
    private static final String PREF = "last_cast_v1";
    private static final String KEY = "session";

    private LastCastStore() {}

    public static void save(Context ctx, NowPlaying now) {
        if (ctx == null || now == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("seriesId", now.seriesId == null ? "" : now.seriesId);
            o.put("seriesTitle", now.seriesTitle == null ? "" : now.seriesTitle);
            o.put("seriesCover", now.seriesCover == null ? "" : now.seriesCover);
            o.put("index", now.index);
            o.put("title", now.title == null ? "" : now.title);
            o.put("sourceUrl", now.sourceUrl == null ? "" : now.sourceUrl);
            o.put("ts", System.currentTimeMillis());
            // 只存 vid/title 列表，体积可控
            JSONArray slim = new JSONArray();
            for (int i = 0; i < now.playlist.length(); i++) {
                JSONObject it = now.playlist.optJSONObject(i);
                if (it == null) continue;
                slim.put(new JSONObject()
                        .put("vid", it.optString("vid"))
                        .put("title", it.optString("title"))
                        .put("seriesId", it.optString("seriesId", now.seriesId))
                        .put("seriesTitle", it.optString("seriesTitle", now.seriesTitle))
                        .put("cover", it.optString("cover", now.seriesCover))
                        .put("episode", it.optInt("episode", i + 1)));
            }
            o.put("playlist", slim);
            prefs(ctx).edit().putString(KEY, o.toString()).apply();
            AppLog.i("LastCast 保存 series=" + now.seriesId + " ep=" + (now.index + 1)
                    + " n=" + slim.length());
        } catch (Exception e) {
            AppLog.w("LastCast 保存失败: " + e.getMessage());
        }
    }

    public static JSONObject load(Context ctx) {
        if (ctx == null) return null;
        try {
            String raw = prefs(ctx).getString(KEY, "");
            if (raw == null || raw.isEmpty()) return null;
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean hasSession(Context ctx) {
        JSONObject o = load(ctx);
        return o != null && o.optJSONArray("playlist") != null
                && o.optJSONArray("playlist").length() > 0
                && !o.optString("seriesId").isEmpty();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
