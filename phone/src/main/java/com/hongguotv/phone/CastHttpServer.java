package com.hongguotv.phone;

import com.hongguotv.shared.CastProtocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CastHttpServer {
    private final int port;
    private final String pairCode;
    private final NowPlaying now;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService pool;

    public CastHttpServer(int port, String pairCode, NowPlaying now) {
        this.port = port;
        this.pairCode = pairCode;
        this.now = now;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        pool = Executors.newCachedThreadPool();
        pool.execute(this::acceptLoop);
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
            s.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            String pairHeader = "";
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

            boolean needPair = !(CastProtocol.PATH_STATUS.equals(path) || "/".equals(path));
            if (needPair && !pairCode.equals(pairHeader)) {
                writeJson(s, 403, new JSONObject().put("ok", false).put("error", "pair_required"));
                return;
            }

            if ("GET".equals(method) && (CastProtocol.PATH_STATUS.equals(path) || "/".equals(path))) {
                writeJson(s, 200, new JSONObject()
                        .put("ok", true)
                        .put("service", "hongguoTV-phone")
                        .put("version", "0.2.0")
                        .put("pairRequired", true)
                        .put("hasNow", now.url != null && !now.url.isEmpty()));
                return;
            }
            if ("GET".equals(method) && CastProtocol.PATH_NOW.equals(path)) {
                writeJson(s, 200, now.toJson());
                return;
            }
            if ("POST".equals(method) && CastProtocol.PATH_NOW.equals(path)) {
                JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                if (req.has("playlist")) {
                    JSONArray pl = req.getJSONArray("playlist");
                    int start = req.optInt("index", 0);
                    now.setPlaylist(pl, start);
                    writeJson(s, 200, now.toJson());
                    return;
                }
                String url = req.optString("url", "");
                String title = req.optString("title", "");
                if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    writeJson(s, 400, new JSONObject().put("ok", false).put("error", "invalid_url"));
                    return;
                }
                now.set(url, title);
                writeJson(s, 200, now.toJson());
                return;
            }
            if ("GET".equals(method) && CastProtocol.PATH_CONTROL.equals(path)) {
                writeJson(s, 200, now.toJson());
                return;
            }
            if ("POST".equals(method) && CastProtocol.PATH_CONTROL.equals(path)) {
                JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                String action = req.optString("action", "");
                now.control(action, req);
                writeJson(s, 200, now.toJson());
                return;
            }
            writeJson(s, 404, new JSONObject().put("ok", false).put("error", "not_found"));
        } catch (Exception e) {
            try {
                writeJson(socket, 500, new JSONObject().put("ok", false).put("error", "server"));
            } catch (Exception ignored) {}
        }
    }

    private static void writeJson(Socket socket, int code, JSONObject json) throws IOException {
        byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
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
}
