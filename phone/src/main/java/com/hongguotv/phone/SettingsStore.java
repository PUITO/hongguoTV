package com.hongguotv.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** DeviceSettings 磁盘持久化（含绑定电视） */
public final class SettingsStore {
    private static final String PREF = "device_settings_v1";
    private static final String KEY = "json";
    private static final String KEY_PAIR = "pair_code";

    private SettingsStore() {}

    public static void load(Context ctx) {
        try {
            SharedPreferences sp = prefs(ctx);
            String raw = sp.getString(KEY, "");
            if (raw != null && !raw.isEmpty()) {
                JSONObject o = new JSONObject(raw);
                DeviceSettings.INSTANCE.applyJson(o);
                // bound fields not in applyJson fully
                if (o.optBoolean("bound", false)) {
                    DeviceSettings.INSTANCE.setBound(
                            o.optString("boundTvIp", ""),
                            o.optInt("boundTvPort", 8766),
                            o.optString("boundSession", ""));
                }
            }
        } catch (Exception e) {
            AppLog.w("SettingsStore.load: " + e.getMessage());
        }
    }

    public static void save(Context ctx) {
        try {
            JSONObject o = DeviceSettings.INSTANCE.toJson();
            prefs(ctx).edit().putString(KEY, o.toString()).apply();
        } catch (Exception e) {
            AppLog.w("SettingsStore.save: " + e.getMessage());
        }
    }

    public static String loadPair(Context ctx, String fallback) {
        return prefs(ctx).getString(KEY_PAIR, fallback);
    }

    public static void savePair(Context ctx, String pair) {
        if (pair == null) return;
        prefs(ctx).edit().putString(KEY_PAIR, pair).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
