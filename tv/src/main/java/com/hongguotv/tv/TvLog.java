package com.hongguotv.tv;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** TV 端简易日志，便于确认倍速是否真正生效 */
public final class TvLog {
    private static final int MAX = 200;
    private static final CopyOnWriteArrayList<String> LINES = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TvLog() {}

    public static void i(String msg) {
        add("I", msg);
    }

    public static void w(String msg) {
        add("W", msg);
    }

    public static void e(String msg) {
        add("E", msg);
    }

    private static void add(String level, String msg) {
        String line = FMT.format(new Date()) + " " + level + " " + (msg == null ? "" : msg);
        LINES.add(line);
        while (LINES.size() > MAX) {
            try { LINES.remove(0); } catch (Exception ignored) { break; }
        }
        android.util.Log.println(
                "E".equals(level) ? android.util.Log.ERROR
                        : "W".equals(level) ? android.util.Log.WARN
                        : android.util.Log.INFO,
                "hongguoTV", msg == null ? "" : msg);
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : new ArrayList<>(LINES)) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        LINES.clear();
    }
}
