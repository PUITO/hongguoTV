package com.hongguotv.phone;


import com.hongguotv.shared.CastProtocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CastHttpServer {
    private final int port;
    private final String pairCode;
    private final NowPlaying now;
    private final MediaCache cache;
    private final String phoneIp;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService pool;

    public CastHttpServer(int port, String pairCode, NowPlaying now, MediaCache cache, String phoneIp) {
        this.port = port;
        this.pairCode = pairCode;
        this.now = now;
        this.cache = cache;
        this.phoneIp = phoneIp;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        pool = Executors.newCachedThreadPool();
        pool.execute(this::acceptLoop);
    }

    /** 供本机播放：把源写入 now 后走 /stream/current，带官方 UA 拉 CDN。 */
    public String proxyUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty()) return sourceUrl;
        try {
            now.sourceUrl = sourceUrl;
            if (cache != null) cache.setCurrent(sourceUrl);
            return "http://127.0.0.1:" + port + CastProtocol.PATH_STREAM_CURRENT;
        } catch (Exception e) {
            return sourceUrl;
        }
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!running.get()) break;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(60000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1];
            String query = "";
            int qi = path.indexOf('?');
            if (qi >= 0) { query = path.substring(qi + 1); path = path.substring(0, qi); }

            String pairHeader = "";
            String rangeHeader = "";
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String name = line.substring(0, c).trim();
                    String value = line.substring(c + 1).trim();
                    if (CastProtocol.HEADER_PAIR.equalsIgnoreCase(name)) pairHeader = value;
                    if ("Content-Length".equalsIgnoreCase(name)) {
                        try { contentLength = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                    }
                    if ("Range".equalsIgnoreCase(name)) rangeHeader = value;
                }
            }
            char[] bodyBuf = new char[Math.max(0, contentLength)];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(bodyBuf, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyBuf, 0, read);

            // stream endpoints: no pair required (TV player may not send custom headers on VideoView)
            if ("GET".equals(method) && (CastProtocol.PATH_STREAM_CURRENT.equals(path) || path.startsWith("/stream/"))) {
                serveStream(s, rangeHeader);
                return;
            }

            boolean publicPath = CastProtocol.PATH_STATUS.equals(path) || "/".equals(path);
            if (!publicPath && !pairCode.equals(pairHeader)) {
                writeJson(s, 403, new JSONObject().put("ok", false).put("error", "pair_required"));
                return;
            }

            if ("GET".equals(method) && publicPath) {
                writeJson(s, 200, new JSONObject()
                        .put("ok", true)
                        .put("service", "hongguoTV-phone")
                        .put("version", "0.3.0")
                        .put("pairRequired", true)
                        .put("hasNow", now.sourceUrl != null && !now.sourceUrl.isEmpty()));
                return;
            }
            if ("GET".equals(method) && CastProtocol.PATH_NOW.equals(path)) {
                writeJson(s, 200, now.toJson(phoneIp, port));
                return;
            }
            if ("POST".equals(method) && CastProtocol.PATH_NOW.equals(path)) {
                JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                if (req.has("playlist")) {
                    JSONArray pl = req.getJSONArray("playlist");
                    int start = req.optInt("index", 0);
                    now.setPlaylist(pl, start);
                    onSourceChanged();
                    writeJson(s, 200, now.toJson(phoneIp, port));
                    return;
                }
                String url = req.optString("url", "");
                String title = req.optString("title", "");
                if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    writeJson(s, 400, new JSONObject().put("ok", false).put("error", "invalid_url"));
                    return;
                }
                now.set(url, title);
                onSourceChanged();
                writeJson(s, 200, now.toJson(phoneIp, port));
                return;
            }
            if (("GET".equals(method) || "POST".equals(method)) && CastProtocol.PATH_SETTINGS.equals(path)) {
                if ("POST".equals(method)) {
                    JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                    DeviceSettings.INSTANCE.applyJson(req);
                    // sync autoNext/speed defaults into now when relevant
                    if (req.has("defaultSpeed")) {
                        now.speed = DeviceSettings.INSTANCE.defaultSpeed;
                        now.control("speed", new JSONObject().put("value", DeviceSettings.INSTANCE.defaultSpeed));
                    }
                    onSourceChanged();
                }
                writeJson(s, 200, new JSONObject().put("ok", true).put("settings", DeviceSettings.INSTANCE.toJson()));
                return;
            }
            if (("GET".equals(method) || "POST".equals(method)) && CastProtocol.PATH_CONTROL.equals(path)) {
                if ("POST".equals(method)) {
                    JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                    now.control(req.optString("action", ""), req);
                    onSourceChanged();
                }
                writeJson(s, 200, now.toJson(phoneIp, port));
                return;
            }
            if ("GET".equals(method) && CastProtocol.PATH_CACHE_STATUS.equals(path)) {
                MediaCache.Job job = cache.currentJob();
                JSONObject j = new JSONObject().put("ok", true);
                if (job != null) {
                    j.put("written", job.written.get())
                            .put("contentLength", job.contentLength.get())
                            .put("done", job.done.get())
                            .put("failed", job.failed.get())
                            .put("running", job.running.get());
                }
                writeJson(s, 200, j);
                return;
            }
            if ("GET".equals(method) && CastProtocol.PATH_SEARCH.equals(path)) {
                String kw = queryParam(query, "q");
                try {
                    writeJson(s, 200, new JSONObject().put("ok", true).put("items", HongguoClient.search(kw)));
                } catch (Exception e) {
                    writeJson(s, 500, new JSONObject().put("ok", false).put("error", String.valueOf(e.getMessage())));
                }
                return;
            }
            if ("GET".equals(method) && CastProtocol.PATH_DETAIL.equals(path)) {
                String sid = queryParam(query, "seriesId");
                try {
                    writeJson(s, 200, HongguoClient.detail(sid).put("ok", true));
                } catch (Exception e) {
                    writeJson(s, 500, new JSONObject().put("ok", false).put("error", String.valueOf(e.getMessage())));
                }
                return;
            }
            writeJson(s, 404, new JSONObject().put("ok", false).put("error", "not_found"));
        } catch (Exception e) {
            try { writeJson(socket, 500, new JSONObject().put("ok", false).put("error", "server")); } catch (Exception ignored) {}
        }
    }

    private void onSourceChanged() {
        String src = now.sourceUrl;
        if (src != null && !src.isEmpty() && DeviceSettings.INSTANCE.isProxy()) {
            cache.setCurrent(src);
            // prefetch next
            synchronized (now) {
                if (now.index + 1 < now.playlist.length()) {
                    JSONObject next = now.playlist.optJSONObject(now.index + 1);
                    if (next != null) {
                        String nu = next.optString("url", "");
                        if (!nu.isEmpty()) cache.prefetch(nu);
                    }
                }
            }
            cache.clearOld(8);
        }
    }

    private void serveStream(Socket s, String rangeHeader) throws Exception {
        String src = now.sourceUrl;
        AppLog.i("serveStream src=" + AppLog.clip(src, 90) + " range=" + rangeHeader);
        if (src == null || src.isEmpty()) {
            writeText(s, 404, "no source");
            return;
        }
        // 本地明文文件（解密缓存）
        if (src.startsWith("file://") || src.startsWith("/")) {
            String path = src.startsWith("file://") ? src.substring(7) : src;
            File f = new File(path);
            if (f.exists() && f.length() > 0) {
                AppLog.i("serveStream 本地明文 " + f.getName() + " size=" + f.length());
                serveFileRange(s, f, "video/mp4", rangeHeader, null);
                return;
            }
            writeText(s, 404, "plain missing");
            return;
        }
        cache.setCurrent(src);
        MediaCache.Job job = cache.jobForUrl(src);
        for (int i = 0; i < 50; i++) {
            if (job != null && job.file.exists() && job.file.length() > 64 * 1024) break;
            if (job != null && job.failed.get()) break;
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        File file = cache.fileFor(src);
        if (file.exists() && file.length() > 0) {
            serveFileRange(s, file, job != null ? job.contentType : "video/mp4", rangeHeader, job);
            return;
        }
        relayUpstream(s, src, rangeHeader);
    }

    private void serveFileRange(Socket s, File file, String contentType, String rangeHeader, MediaCache.Job job) throws Exception {
        long fileLen = file.length();
        long total = job != null && job.contentLength.get() > 0 ? job.contentLength.get() : fileLen;
        long start = 0, end = fileLen - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            partial = true;
            String r = rangeHeader.substring(6).trim();
            String[] ab = r.split("-");
            try { start = Long.parseLong(ab[0]); } catch (Exception ignored) {}
            if (ab.length > 1 && !ab[1].isEmpty()) {
                try { end = Long.parseLong(ab[1]); } catch (Exception ignored) {}
            }
            if (end >= fileLen) end = fileLen - 1;
            if (start < 0) start = 0;
        }
        if (start > end || start >= fileLen) {
            writeText(s, 416, "range");
            return;
        }
        long len = end - start + 1;
        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        if (partial) {
            h.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(total > 0 ? total : fileLen).append("\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long left = len;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    private void relayUpstream(Socket s, String src, String rangeHeader) throws Exception {
        String[] uas = new String[] {
                "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)",
                "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)",
                "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "okhttp/4.9.3",
        };
        HttpURLConnection c = null;
        int code = 0;
        for (String ua : uas) {
            try {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", ua);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Connection", "keep-alive");
                // 部分 CDN 对 Referer 敏感：先不带，失败再带
                if (rangeHeader != null && !rangeHeader.isEmpty()) {
                    c.setRequestProperty("Range", rangeHeader);
                }
                code = c.getResponseCode();
                AppLog.i("relayUpstream try ua=" + AppLog.clip(ua, 40) + " HTTP " + code
                        + " ct=" + c.getContentType() + " cl=" + c.getContentLength());
                if (code == 200 || code == 206) break;
                // log small error body
                try {
                    InputStream es = c.getErrorStream();
                    if (es != null) {
                        byte[] eb = new byte[200];
                        int n = es.read(eb);
                        if (n > 0) AppLog.w("upstream err body=" + new String(eb, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {}
            } catch (Exception e) {
                AppLog.w("relay try fail: " + e.getMessage());
            }
        }
        if (c == null || (code != 200 && code != 206)) {
            // 最后再试一次带 Referer
            try {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", uas[0]);
                c.setRequestProperty("Referer", "https://www.hongguoduanju.com/");
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (rangeHeader != null && !rangeHeader.isEmpty()) c.setRequestProperty("Range", rangeHeader);
                code = c.getResponseCode();
                AppLog.i("relayUpstream +Referer HTTP " + code);
            } catch (Exception e) {
                AppLog.e("relayUpstream final", e);
                writeText(s, 502, "upstream");
                return;
            }
        }
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        if (in == null || code >= 400) {
            AppLog.w("relayUpstream fail code=" + code);
            writeText(s, code >= 400 ? code : 502, "upstream " + code);
            return;
        }
        String ct = c.getContentType();
        if (ct == null || ct.contains("text/html")) ct = "video/mp4";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(" OK\r\n");
        h.append("Content-Type: ").append(ct).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        String cr = c.getHeaderField("Content-Range");
        if (cr != null) h.append("Content-Range: ").append(cr).append("\r\n");
        int cl = c.getContentLength();
        if (cl > 0) h.append("Content-Length: ").append(cl).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        byte[] buf = new byte[64 * 1024];
        int n;
        long total = 0;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            total += n;
        }
        out.flush();
        in.close();
        AppLog.i("relayUpstream done bytes=" + total);
    }

    private static String queryParam(String query, String name) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            int e = part.indexOf('=');
            String k = e < 0 ? part : part.substring(0, e);
            if (name.equals(k)) {
                try {
                    return java.net.URLDecoder.decode(e < 0 ? "" : part.substring(e + 1), "UTF-8");
                } catch (Exception ex) {
                    return e < 0 ? "" : part.substring(e + 1);
                }
            }
        }
        return "";
    }

    private static void writeJson(Socket socket, int code, JSONObject json) throws IOException {
        byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : "ERR";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private static void writeText(Socket socket, int code, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + " ERR\r\nContent-Type: text/plain\r\nContent-Length: "
                + data.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }
}
