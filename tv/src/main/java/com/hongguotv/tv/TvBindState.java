package com.hongguotv.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** 电视绑定到的手机端信息（持久化）。 */
public final class TvBindState {
    public static final TvBindState INSTANCE = new TvBindState();
    public volatile String session = "";
    public volatile String phoneIp = "";
    public volatile int phonePort = 8765;
    public volatile String pair = "";
    public volatile boolean bound;

    private TvBindState() {}

    public void save(Context ctx) {
        ctx.getSharedPreferences("tv_bind", Context.MODE_PRIVATE).edit()
                .putString("phoneIp", phoneIp == null ? "" : phoneIp)
                .putInt("phonePort", phonePort)
                .putString("pair", pair == null ? "" : pair)
                .putBoolean("bound", bound)
                .apply();
    }

    public void load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("tv_bind", Context.MODE_PRIVATE);
        phoneIp = sp.getString("phoneIp", "");
        phonePort = sp.getInt("phonePort", 8765);
        pair = sp.getString("pair", "");
        bound = sp.getBoolean("bound", false) && phoneIp != null && !phoneIp.isEmpty();
    }

    public void clear(Context ctx) {
        bound = false;
        phoneIp = "";
        pair = "";
        ctx.getSharedPreferences("tv_bind", Context.MODE_PRIVATE).edit().clear().apply();
    }
}
