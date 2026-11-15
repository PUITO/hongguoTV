package com.hongguotv.phone;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存环形日志 + 导出，便于排查 App 取流。 */
public final class AppLog {
    private static final String TAG = "HongguoTV";
    private static final int MAX = 800;
    private static final CopyOnWriteArrayList<String> LINES = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    private AppLog() {}

    public static void i(String msg) { add("I", msg); }
    public static void w(String msg) { add("W", msg); }
    public static void e(String msg) { add("E", msg); }

    public static void e(String msg, Throwable t) {
        add("E", msg + " · " + (t == null ? "" : t.getClass().getSimpleName() + ": " + t.getMessage()));
        if (t != null) Log.e(TAG, msg, t);
    }

    /** 截断敏感/过长内容 */
    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ');
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…(" + s.length() + ")";
    }

    private static void add(String level, String msg) {
        String line = FMT.format(new Date()) + " " + level + " " + msg;
        LINES.add(line);
        while (LINES.size() > MAX) {
            try { LINES.remove(0); } catch (Exception ignored) { break; }
        }
        if ("E".equals(level)) Log.e(TAG, msg);
        else if ("W".equals(level)) Log.w(TAG, msg);
        else Log.i(TAG, msg);
    }

    public static List<String> snapshot() {
        return new ArrayList<>(LINES);
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("hongguoTV logs ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append('\n');
        sb.append("device=").append(Build.MANUFACTURER).append('/').append(Build.MODEL)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("----\n");
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    public static void clear() {
        LINES.clear();
        i("日志已清空");
    }

    /** 写入缓存并返回 File，供分享 */
    public static File writeExportFile(Context ctx) throws Exception {
        File dir = new File(ctx.getCacheDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "hongguotv-log-" + System.currentTimeMillis() + ".txt");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(dump().getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }
}
