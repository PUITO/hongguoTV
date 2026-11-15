package com.hongguotv.tv;

import android.app.Activity;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.hongguotv.shared.CastProtocol;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    private TextView titleView, meta;
    private View overlay;
    private String phoneIp, pair;
    private int phonePort;
    private long lastCommandId = -1;
    private String currentUrl = "";
    private float currentSpeed = 1f;
    private final float[] speeds = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private int speedIdx = 1;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private boolean destroyed;
    private final Runnable hideOverlay = () -> { if (overlay != null) overlay.setVisibility(View.GONE); };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            net.execute(() -> {
                try {
                    JSONObject st = httpGet("http://" + phoneIp + ":" + phonePort + CastProtocol.PATH_CONTROL, pair);
                    ui.post(() -> applyState(st));
                } catch (Exception ignored) {}
                if (!destroyed) ui.postDelayed(this, 1200);
            });
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        video = findViewById(R.id.video);
        titleView = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        overlay = findViewById(R.id.overlay);
        phoneIp = getIntent().getStringExtra(EXTRA_PHONE_IP);
        phonePort = getIntent().getIntExtra(EXTRA_PHONE_PORT, CastProtocol.DEFAULT_PORT);
        pair = getIntent().getStringExtra(EXTRA_PAIR);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(t == null || t.isEmpty() ? "播放中" : t);

        findViewById(R.id.btnPrev).setOnClickListener(v -> postControl("prev"));
        findViewById(R.id.btnNext).setOnClickListener(v -> postControl("next"));
        findViewById(R.id.btnPlayPause).setOnClickListener(v -> {
            if (video.isPlaying()) { video.pause(); postControl("pause"); }
            else { video.start(); postControl("play"); }
            showOverlay();
        });
        findViewById(R.id.btnSpeed).setOnClickListener(v -> {
            speedIdx = (speedIdx + 1) % speeds.length;
            currentSpeed = speeds[speedIdx];
            applySpeed(currentSpeed);
            try { postControlJson(new JSONObject().put("action", "speed").put("value", currentSpeed)); } catch (Exception ignored) {}
            meta.setText("倍速 " + currentSpeed + "x");
            showOverlay();
        });

        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "播放失败 " + what, Toast.LENGTH_LONG).show();
            return true;
        });
        video.setOnCompletionListener(mp -> postControl("next"));
        video.setOnPreparedListener(mp -> {
            applySpeed(currentSpeed);
            video.start();
        });

        if (url != null && !url.isEmpty()) playUrl(url);
        else Toast.makeText(this, "等待手机推送片源…", Toast.LENGTH_LONG).show();
        showOverlay();
        ui.post(poll);
    }

    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, 5000);
    }

    private void applyState(JSONObject st) {
        if (st == null) return;
        long cmd = st.optLong("commandId", 0);
        String url = st.optString("url", "");
        String title = st.optString("title", "");
        boolean playing = st.optBoolean("playing", true);
        float speed = (float) st.optDouble("speed", currentSpeed);
        long seekMs = st.optLong("seekMs", -1);
        int index = st.optInt("index", 0);
        int pl = st.optInt("playlistSize", 0);

        if (title != null && !title.isEmpty()) titleView.setText(title);
        meta.setText("第" + (index + 1) + "/" + Math.max(pl, 1) + " · " + speed + "x"
                + (st.optBoolean("useProxy", true) ? " · 缓存流" : ""));
        if (url != null && !url.isEmpty() && !url.equals(currentUrl)) playUrl(url);
        if (cmd != lastCommandId) {
            lastCommandId = cmd;
            currentSpeed = speed;
            if (seekMs >= 0) try { video.seekTo((int) seekMs); } catch (Exception ignored) {}
            applySpeed(speed);
            if (playing) { if (!video.isPlaying()) video.start(); }
            else if (video.isPlaying()) video.pause();
        }
    }

    private void playUrl(String url) {
        currentUrl = url;
        video.setVideoURI(Uri.parse(url));
        video.start();
        showOverlay();
    }

    private void applySpeed(float speed) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(speed);
            video.getClass().getMethod("setPlaybackParams", PlaybackParams.class).invoke(video, params);
        } catch (Exception ignored) {}
    }

    private void postControl(String action) {
        try { postControlJson(new JSONObject().put("action", action)); } catch (Exception ignored) {}
    }

    private void postControlJson(JSONObject body) {
        net.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + phoneIp + ":" + phonePort + CastProtocol.PATH_CONTROL).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty(CastProtocol.HEADER_PAIR, pair);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(data); }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {}
        });
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
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        if (code >= 400) throw new IllegalStateException("HTTP " + code);
        return new JSONObject(sb.toString());
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        showOverlay();
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (video.isPlaying()) { video.pause(); postControl("pause"); }
            else { video.start(); postControl("play"); }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            postControl("next"); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            postControl("prev"); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(poll);
        ui.removeCallbacks(hideOverlay);
        net.shutdownNow();
        super.onDestroy();
    }
}
