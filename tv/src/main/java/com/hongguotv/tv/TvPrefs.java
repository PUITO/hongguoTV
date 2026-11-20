package com.hongguotv.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** TV 本机偏好（默认倍速等），与手机无关 */
public final class TvPrefs {
    private static final String PREF = "tv_prefs_v1";

    private TvPrefs() {}

    public static float defaultSpeed(Context ctx) {
        float s = prefs(ctx).getFloat("default_speed", 1.0f);
        if (s < 0.75f) s = 0.75f;
        if (s > 3.0f) s = 3.0f;
        return s;
    }

    public static void setDefaultSpeed(Context ctx, float speed) {
        if (speed < 0.75f) speed = 0.75f;
        if (speed > 3.0f) speed = 3.0f;
        prefs(ctx).edit().putFloat("default_speed", speed).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
