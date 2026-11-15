package com.hongguotv.tv;

import com.hongguotv.shared.CastProtocol;

import org.json.JSONObject;

import java.io.BufferedReader;
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

/** 电视侧绑定服务：接收手机扫码后的 register。 */
public final class TvBindServer {
    private final int port;
    private final String session;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Runnable onBound;

    public TvBindServer(int port, String session, Runnable onBound) {
        this.port = port;
        this.session = session;
        this.onBound = onBound;
    }

    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        pool = Executors.newCachedThreadPool();
        pool.execute(this::loop);
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    private void loop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handle(socket));
            } catch (Exception e) {
                if (!running.get()) break;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1];
            int qi = path.indexOf('?');
            if (qi >= 0) path = path.substring(0, qi);
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
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

            if ("POST".equals(method) && CastProtocol.PATH_REGISTER.equals(path)) {
                JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                String sess = req.optString("session", "");
                if (!session.equals(sess)) {
                    write(s, 403, new JSONObject().put("ok", false).put("error", "session"));
                    return;
                }
                TvBindState st = TvBindState.INSTANCE;
                st.session = sess;
                st.phoneIp = req.optString("phoneIp", "");
                st.phonePort = req.optInt("port", CastProtocol.DEFAULT_PORT);
                st.pair = req.optString("pair", "");
                st.bound = st.phoneIp.length() > 0 && st.pair.length() > 0;
                write(s, 200, new JSONObject().put("ok", true).put("bound", st.bound));
                if (st.bound && onBound != null) onBound.run();
                return;
            }
            if ("GET".equals(method) && CastProtocol.PATH_STATUS.equals(path)) {
                write(s, 200, new JSONObject().put("ok", true).put("role", "tv").put("session", session)
                        .put("bound", TvBindState.INSTANCE.bound));
                return;
            }
            write(s, 404, new JSONObject().put("ok", false));
        } catch (Exception ignored) {}
    }

    private static void write(Socket socket, int code, JSONObject json) throws Exception {
        byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + " OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                + data.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }
}
