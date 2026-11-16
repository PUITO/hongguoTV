package com.hongguotv.tv;

import android.app.Activity;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.view.WindowManager;
import android.widget.MediaController;
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
    private MediaController controller;
    private String phoneIp, pair;
    private int phonePort;
    private long lastCommandId = -1;
    private long lastSettingsRev = -1;
    private String currentUrl = "";
    private float currentSpeed = 1.5f;
    private long lastBackMs = 0;
    private boolean allowLocalControl = true;
    private boolean showOverlayPref = true;
    private int overlayHideMs = 5000;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private boolean destroyed;
    private final Runnable hideOverlay = () -> {
        if (overlay != null && showOverlayPref) overlay.setVisibility(View.GONE);
    };
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

        try {
            controller = new MediaController(this);
            controller.setAnchorView(video);
            controller.setMediaPlayer(video);
            video.setMediaController(controller);
        } catch (Exception e) {
            controller = null;
        }

        // Hide static bottom buttons — MediaController is primary UI
        View btnPrev = findViewById(R.id.btnPrev);
        View btnNext = findViewById(R.id.btnNext);
        View btnPP = findViewById(R.id.btnPlayPause);
        View btnSpeed = findViewById(R.id.btnSpeed);
        if (btnPrev != null) btnPrev.setVisibility(View.GONE);
        if (btnNext != null) btnNext.setVisibility(View.GONE);
        if (btnPP != null) btnPP.setVisibility(View.GONE);
        if (btnSpeed != null) btnSpeed.setVisibility(View.GONE);

        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "播放失败 " + what + "（可在手机切换推流/直链或清晰度）", Toast.LENGTH_LONG).show();
            return true;
        });
        video.setOnCompletionListener(mp -> {
            try {
                postControlJson(new JSONObject().put("action", "next").put("fromComplete", true));
            } catch (Exception ignored) {}
        });
        video.setOnPreparedListener(mp -> {
            try {
                int vw = mp.getVideoWidth();
                int vh = mp.getVideoHeight();
                int dur = mp.getDuration();
                if (vw > 0 && vh > 0) fitVideoView(vw, vh);
                mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                if (dur <= 0) {
                    meta.setText("时长未知/0 · 可在手机重推或换 1080p");
                } else {
                    meta.setText(String.format(java.util.Locale.US, "时长 %d 秒 · %dx%d", dur / 1000, vw, vh));
                }
            } catch (Exception ignored) {}
            applySpeed(currentSpeed);
            video.start();
            if (controller != null) controller.show(4000);
        });

        try {
            if (url != null && !url.isEmpty()) playUrl(url);
            else Toast.makeText(this, "等待手机推送…", Toast.LENGTH_LONG).show();
            showOverlay();
            ui.post(poll);
        } catch (Exception e) {
            Toast.makeText(this, "播放器异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applySettings(JSONObject settings) {
        if (settings == null) return;
        long rev = settings.optLong("revision", 0);
        if (rev == lastSettingsRev) return;
        lastSettingsRev = rev;
        showOverlayPref = settings.optBoolean("showTvOverlay", true);
        allowLocalControl = settings.optBoolean("allowTvLocalControl", true);
        overlayHideMs = Math.max(1000, settings.optInt("overlayHideMs", 5000));
        boolean keepOn = settings.optBoolean("tvKeepScreenOn", true);
        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!showOverlayPref) overlay.setVisibility(View.GONE);
        float def = (float) settings.optDouble("defaultSpeed", currentSpeed);
        if (Math.abs(def - currentSpeed) > 0.01f && lastCommandId < 0) {
            currentSpeed = def;
            applySpeed(currentSpeed);
        }
    }

    private void showOverlay() {
        if (!showOverlayPref) return;
        overlay.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, overlayHideMs);
        if (controller != null) controller.show(overlayHideMs);
    }

    private void applyState(JSONObject st) {
        if (st == null) return;
        applySettings(st.optJSONObject("settings"));
        long cmd = st.optLong("commandId", 0);
        String url = st.optString("url", "");
        String title = st.optString("title", "");
        boolean playing = st.optBoolean("playing", true);
        float speed = (float) st.optDouble("speed", currentSpeed);
        long seekMs = st.optLong("seekMs", -1);
        int index = st.optInt("index", 0);
        int pl = st.optInt("playlistSize", 0);
        String mode = st.optString("playMode", CastProtocol.MODE_PROXY);

        if (title != null && !title.isEmpty()) titleView.setText(title);
        meta.setText("第" + (index + 1) + "/" + Math.max(pl, 1)
                + " · " + speed + "x · "
                + (CastProtocol.MODE_DIRECT.equals(mode) ? "直链" : "推流"));

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
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        try {
            video.stopPlayback();
        } catch (Exception ignored) {}
        try {
            video.setVideoURI(Uri.parse(url));
            video.requestFocus();
            // start 在 onPrepared；若部分机顶盒需主动 start
            video.start();
            showOverlay();
            meta.setText("加载中…");
        } catch (Exception e) {
            Toast.makeText(this, "打开流失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applySpeed(float speed) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(speed);
            // API 23+: VideoView#setPlaybackParams
            video.getClass().getMethod("setPlaybackParams", PlaybackParams.class).invoke(video, params);
        } catch (Exception ignored) {}
    }

    private void postControlJson(JSONObject body) {
        net.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://" + phoneIp + ":" + phonePort + CastProtocol.PATH_CONTROL).openConnection();
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBackMs < 2000) {
                // 二次返回：退出播放并结束到桌面，避免反复进绑定页
                try {
                    finishAffinity();
                } catch (Exception e) {
                    finish();
                }
                return true;
            }
            lastBackMs = now;
            Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!allowLocalControl) return super.onKeyDown(keyCode, event);
        showOverlay();
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (video.isPlaying()) {
                video.pause();
                try { postControlJson(new JSONObject().put("action", "pause")); } catch (Exception ignored) {}
            } else {
                video.start();
                try { postControlJson(new JSONObject().put("action", "play")); } catch (Exception ignored) {}
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            try { postControlJson(new JSONObject().put("action", "next")); } catch (Exception ignored) {}
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            try { postControlJson(new JSONObject().put("action", "prev")); } catch (Exception ignored) {}
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    
    private void fitVideoView(int videoW, int videoH) {
        if (video == null || videoW <= 0 || videoH <= 0) return;
        View parent = (View) video.getParent();
        if (parent == null) return;
        int viewW = parent.getWidth();
        int viewH = parent.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            parent.post(() -> fitVideoView(videoW, videoH));
            return;
        }
        float videoRatio = videoW / (float) videoH;
        float viewRatio = viewW / (float) viewH;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(0, 0);
        if (videoRatio > viewRatio) {
            lp.width = viewW;
            lp.height = Math.max(1, Math.round(viewW / videoRatio));
        } else {
            lp.height = viewH;
            lp.width = Math.max(1, Math.round(viewH * videoRatio));
        }
        lp.gravity = Gravity.CENTER;
        video.setLayoutParams(lp);
    }

    @Override public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackMs < 2000) {
            try { finishAffinity(); } catch (Exception e) { finish(); }
            return;
        }
        lastBackMs = now;
        Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(poll);
        ui.removeCallbacks(hideOverlay);
        net.shutdownNow();
        super.onDestroy();
    }
}
