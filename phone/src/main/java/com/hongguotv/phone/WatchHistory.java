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

    public static void add(Context ctx, JSONObject item) {
        try {
            JSONArray old = list(ctx);
            JSONArray next = new JSONArray();
            String sid = item.optString("seriesId");
            next.put(item.put("ts", System.currentTimeMillis()));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (sid.equals(o.optString("seriesId"))) continue;
                next.put(o);
                if (next.length() >= MAX) break;
            }
            prefs(ctx).edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
