package com.hongguotv.tv;

import android.app.Activity;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.hongguotv.shared.CastProtocol;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PHONE_IP = "phone_ip";
    public static final String EXTRA_PHONE_PORT = "phone_port";
    public static final String EXTRA_PAIR = "pair";

    private VideoView video;
    private TextView titleView;
    private String phoneIp;
    private int phonePort;
    private String pair;
    private long lastCommandId = -1;
    private String currentUrl = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private boolean destroyed;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            net.execute(() -> {
                try {
                    String u = "http://" + phoneIp + ":" + phonePort + CastProtocol.PATH_CONTROL;
                    JSONObject st = httpGet(u, pair);
                    ui.post(() -> applyState(st));
                } catch (Exception ignored) {}
                if (!destroyed) ui.postDelayed(poll, 1200);
            });
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        video = findViewById(R.id.video);
        titleView = findViewById(R.id.title);
        phoneIp = getIntent().getStringExtra(EXTRA_PHONE_IP);
        phonePort = getIntent().getIntExtra(EXTRA_PHONE_PORT, CastProtocol.DEFAULT_PORT);
        pair = getIntent().getStringExtra(EXTRA_PAIR);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(t == null || t.isEmpty() ? "播放中" : t);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "播放失败 what=" + what, Toast.LENGTH_LONG).show();
            return true;
        });
        video.setOnCompletionListener(mp -> {
            // 通知手机端可切下一集：本地先尝试从 control 拉 next 状态
            net.execute(() -> {
                try {
                    // 电视播完后主动请求状态；手机若 autoNext 会在 next 时更新 url
                    String u = "http://" + phoneIp + ":" + phonePort + CastProtocol.PATH_CONTROL;
                    // post next
                    postControl("next");
                    JSONObject st = httpGet(u, pair);
                    ui.post(() -> applyState(st));
                } catch (Exception ignored) {}
            });
        });
        if (url != null && !url.isEmpty()) playUrl(url);
        else Toast.makeText(this, "暂无片源，等待手机推送…", Toast.LENGTH_LONG).show();
        ui.post(poll);
    }

    private void applyState(JSONObject st) {
        if (st == null) return;
        long cmd = st.optLong("commandId", 0);
        String url = st.optString("url", "");
        String title = st.optString("title", "");
        boolean playing = st.optBoolean("playing", true);
        float speed = (float) st.optDouble("speed", 1.0);
        long seekMs = st.optLong("seekMs", -1);

        if (title != null && !title.isEmpty()) titleView.setText(title);
        if (url != null && !url.isEmpty() && !url.equals(currentUrl)) {
            playUrl(url);
        }
        if (cmd != lastCommandId) {
            lastCommandId = cmd;
            if (seekMs >= 0) {
                try { video.seekTo((int) seekMs); } catch (Exception ignored) {}
            }
            applySpeed(speed);
            if (playing) {
                if (!video.isPlaying()) video.start();
            } else {
                if (video.isPlaying()) video.pause();
            }
        } else {
            applySpeed(speed);
        }
    }

    private void playUrl(String url) {
        currentUrl = url;
        video.setVideoURI(Uri.parse(url));
        video.setOnPreparedListener(mp -> {
            applySpeed((float) 1.0);
            video.start();
        });
        video.start();
    }

    private void applySpeed(float speed) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(speed);
            // VideoView#setPlaybackParams since API 26
            video.getClass().getMethod("setPlaybackParams", PlaybackParams.class).invoke(video, params);
        } catch (Exception ignored) {}
    }

    private void postControl(String action) {
        try {
            URL url = new URL("http://" + phoneIp + ":" + phonePort + CastProtocol.PATH_CONTROL);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty(CastProtocol.HEADER_PAIR, pair);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = ("{\"action\":\"" + action + "\"}").getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(data);
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {}
    }

    private static JSONObject httpGet(String urlSpec, String pair) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlSpec).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestMethod("GET");
        if (pair != null) c.setRequestProperty(CastProtocol.HEADER_PAIR, pair);
        int code = c.getResponseCode();
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        if (code >= 400) throw new IllegalStateException("HTTP " + code);
        return new JSONObject(sb.toString());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(poll);
        net.shutdownNow();
        super.onDestroy();
    }
}
