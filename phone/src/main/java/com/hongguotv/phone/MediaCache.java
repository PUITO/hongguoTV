package com.hongguotv.phone;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 磁盘缓存 + 后台预取。支持边下边播（文件可被边写边读）。
 */
public final class MediaCache {
    private static final String TAG = "MediaCache";
    private final File dir;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private volatile String currentKey = "";

    public MediaCache(Context ctx) {
        dir = new File(ctx.getCacheDir(), "stream_cache");
        if (!dir.exists()) dir.mkdirs();
    }

    public static String keyOf(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }

    public File fileFor(String url) {
        return new File(dir, keyOf(url) + ".bin");
    }

    public synchronized void setCurrent(String url) {
        if (url == null || url.isEmpty()) return;
        currentKey = keyOf(url);
        ensureDownloading(url, true);
    }

    public void prefetch(String url) {
        if (url == null || url.isEmpty()) return;
        ensureDownloading(url, false);
    }

    private void ensureDownloading(String url, boolean highPriority) {
        String key = keyOf(url);
        Job existing = jobs.get(key);
        if (existing != null && (existing.done.get() || existing.running.get())) return;
        Job job = new Job(url, key, fileFor(url));
        jobs.put(key, job);
        job.future = pool.submit(job);
        Log.i(TAG, "cache start " + key + " prio=" + highPriority);
    }

    public Job jobForUrl(String url) {
        return jobs.get(keyOf(url));
    }

    public Job currentJob() {
        return jobs.get(currentKey);
    }

    public synchronized void clearOld(int keepMax) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keepMax) return;
        long total = 0;
        for (File f : files) total += f.length();
        long maxBytes = (long) DeviceSettings.INSTANCE.cacheMaxMb * 1024L * 1024L;
        if (total < maxBytes && files.length <= keepMax) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (currentKey != null && f.getName().startsWith(currentKey)) continue;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (dir.listFiles() != null && dir.listFiles().length <= keepMax) break;
        }
    }

    /** 用户手动清理推流缓存 */
    public synchronized void clearAll() {
        try {
            for (Job j : jobs.values()) {
                try {
                    if (j.future != null) j.future.cancel(true);
                } catch (Exception ignored) {}
            }
            jobs.clear();
            currentKey = "";
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            Log.i(TAG, "cache cleared all");
        } catch (Exception e) {
            Log.w(TAG, "clearAll: " + e.getMessage());
        }
    }

    /** [0]=file count, [1]=total bytes */
    public synchronized long[] sizeStats() {
        File[] files = dir.listFiles();
        if (files == null) return new long[]{0, 0};
        long total = 0;
        for (File f : files) total += f.length();
        return new long[]{files.length, total};
    }

    static HttpURLConnection openUpstream(String url, long existing) {
        String[] uas = {
                "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)",
                "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)",
                "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        };
        for (String ua : uas) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", ua);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
                int code = c.getResponseCode();
                if (code == 200 || code == 206) return c;
                try { c.disconnect(); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static final class Job implements Runnable {
        final String url;
        final String key;
        final File file;
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean failed = new AtomicBoolean(false);
        final AtomicLong written = new AtomicLong(0);
        final AtomicLong contentLength = new AtomicLong(-1);
        volatile String contentType = "video/mp4";
        Future<?> future;

        Job(String url, String key, File file) {
            this.url = url;
            this.key = key;
            this.file = file;
        }

        public boolean isReadyEnough(long minBytes) {
            return file.exists() && (done.get() || written.get() >= minBytes);
        }

        @Override public void run() {
            running.set(true);
            try {
                // resume if partial
                long existing = file.exists() ? file.length() : 0;
                HttpURLConnection c = openUpstream(url, existing);
                if (c == null) { failed.set(true); return; }
                int code = c.getResponseCode();
                if (code == 200) {
                    existing = 0; // full body
                } else if (code != 206) {
                    failed.set(true);
                    return;
                }
                String ct = c.getContentType();
                if (ct != null && !ct.isEmpty()) contentType = ct.split(";")[0].trim();
                long cl = c.getContentLength();
                if (code == 206) {
                    // Content-Range: bytes a-b/total
                    String cr = c.getHeaderField("Content-Range");
                    if (cr != null && cr.contains("/")) {
                        try {
                            contentLength.set(Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()));
                        } catch (Exception ignored) {}
                    }
                } else if (cl > 0) {
                    contentLength.set(cl);
                }
                try (InputStream in = c.getInputStream();
                     OutputStream out = new FileOutputStream(file, existing > 0 && code == 206)) {
                    if (existing > 0 && code == 206) written.set(existing);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        written.addAndGet(n);
                    }
                    out.flush();
                }
                done.set(true);
                Log.i(TAG, "cache done " + key + " bytes=" + written.get());
            } catch (Exception e) {
                Log.w(TAG, "cache fail " + key, e);
                failed.set(true);
            } finally {
                running.set(false);
            }
        }
    }
}
