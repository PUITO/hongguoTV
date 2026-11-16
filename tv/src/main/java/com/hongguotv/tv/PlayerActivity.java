package com.hongguotv.tv;

import android.app.Activity;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PHONE_IP = "phone_ip";
    public static final String EXTRA_PHONE_PORT = "phone_port";
    public static final String EXTRA_PAIR = "pair";

    private static final float[] SPEED_STEPS = {0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};

    private VideoView video;
    private TextView titleView, meta;
    private View overlay;
    private Button btnPrev, btnNext, btnPlayPause, btnSpeed;
    private MediaController controller;
    private MediaPlayer mediaPlayer; // from onPrepared — 倍速必须作用在它上面
    private String phoneIp, pair;
    private int phonePort;
    private long lastCommandId = -1;
    private long lastSettingsRev = -1;
    private String currentUrl = "";
    private long lastUpdatedAt = -1;
    private int lastIndex = -1;
    private float currentSpeed = 1.5f;
    private long lastBackMs = 0;
    private boolean allowLocalControl = true;
    private boolean showOverlayPref = true;
    private int overlayHideMs = 5000;
    private boolean prepared;
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
                    JSONObject st = httpGet(
                            "http://" + phoneIp + ":" + phonePort + CastProtocol.PATH_CONTROL, pair);
                    ui.post(() -> applyState(st));
                } catch (Exception ignored) {}
                if (!destroyed) ui.postDelayed(this, 1000);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnSpeed = findViewById(R.id.btnSpeed);
        // 显示本地控制条（倍速必须可见）
        if (btnPrev != null) {
            btnPrev.setVisibility(View.VISIBLE);
            btnPrev.setOnClickListener(v -> {
                showOverlay();
                postControlJson(jsonAction("prev"));
            });
        }
        if (btnNext != null) {
            btnNext.setVisibility(View.VISIBLE);
            btnNext.setOnClickListener(v -> {
                showOverlay();
                postControlJson(jsonAction("next"));
            });
        }
        if (btnPlayPause != null) {
            btnPlayPause.setVisibility(View.VISIBLE);
            btnPlayPause.setOnClickListener(v -> {
                showOverlay();
                togglePlayPause();
            });
        }
        if (btnSpeed != null) {
            btnSpeed.setVisibility(View.VISIBLE);
            updateSpeedButton();
            btnSpeed.setOnClickListener(v -> {
                showOverlay();
                cycleSpeed(true);
            });
        }

        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "播放失败 " + what + "/" + extra, Toast.LENGTH_LONG).show();
            return true;
        });
        video.setOnCompletionListener(mp -> {
            try {
                postControlJson(new JSONObject().put("action", "next").put("fromComplete", true));
            } catch (Exception ignored) {}
        });
        video.setOnPreparedListener(mp -> {
            mediaPlayer = mp;
            prepared = true;
            try {
                int vw = mp.getVideoWidth();
                int vh = mp.getVideoHeight();
                int dur = mp.getDuration();
                if (vw > 0 && vh > 0) fitVideoView(vw, vh);
                try {
                    mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                } catch (Exception ignored) {}
                if (meta != null) {
                    if (dur <= 0) {
                        meta.setText(String.format(Locale.US, "倍速 %.2fx · 时长未知", currentSpeed));
                    } else {
                        meta.setText(String.format(Locale.US, "倍速 %.2fx · 时长 %d 秒 · %dx%d",
                                currentSpeed, dur / 1000, vw, vh));
                    }
                }
            } catch (Exception ignored) {}
            // 准备好后必须再设一次倍速（prepare 前设置会无效）
            applySpeed(currentSpeed);
            try {
                if (!video.isPlaying()) video.start();
            } catch (Exception ignored) {}
            if (controller != null) {
                try { controller.show(4000); } catch (Exception ignored) {}
            }
            showOverlay();
        });

        if (url != null && !url.isEmpty()) playUrl(url);
        ui.post(poll);
    }

    private JSONObject jsonAction(String action) {
        try {
            return new JSONObject().put("action", action);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void togglePlayPause() {
        try {
            if (video.isPlaying()) {
                video.pause();
                postControlJson(jsonAction("pause"));
                if (btnPlayPause != null) btnPlayPause.setText("播放");
            } else {
                video.start();
                postControlJson(jsonAction("play"));
                if (btnPlayPause != null) btnPlayPause.setText("暂停");
            }
        } catch (Exception ignored) {}
    }

    /** 本地循环倍速，并同步到手机 NOW（其他端跟随） */
    private void cycleSpeed(boolean forward) {
        int idx = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_STEPS.length; i++) {
            float d = Math.abs(SPEED_STEPS[i] - currentSpeed);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        if (forward) idx = (idx + 1) % SPEED_STEPS.length;
        else idx = (idx - 1 + SPEED_STEPS.length) % SPEED_STEPS.length;
        float next = SPEED_STEPS[idx];
        currentSpeed = next;
        applySpeed(next);
        updateSpeedButton();
        try {
            postControlJson(new JSONObject().put("action", "speed").put("value", next));
        } catch (Exception ignored) {}
        Toast.makeText(this, "倍速 " + next + "x", Toast.LENGTH_SHORT).show();
    }

    private void updateSpeedButton() {
        if (btnSpeed != null) {
            btnSpeed.setText(String.format(Locale.US, "%.2fx", currentSpeed));
        }
    }

    private void applySettings(JSONObject settings) {
        if (settings == null) return;
        long rev = settings.optLong("revision", 0);
        if (rev > 0 && rev == lastSettingsRev) return;
        lastSettingsRev = rev;
        showOverlayPref = settings.optBoolean("showTvOverlay", true);
        allowLocalControl = settings.optBoolean("allowTvLocalControl", true);
        overlayHideMs = Math.max(1000, settings.optInt("overlayHideMs", 5000));
        boolean keepOn = settings.optBoolean("tvKeepScreenOn", true);
        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!showOverlayPref && overlay != null) overlay.setVisibility(View.GONE);
        float def = (float) settings.optDouble("defaultSpeed", currentSpeed);
        if (Math.abs(def - currentSpeed) > 0.01f) {
            currentSpeed = def;
            applySpeed(currentSpeed);
            updateSpeedButton();
        }
    }

    private void showOverlay() {
        if (!showOverlayPref || overlay == null) return;
        overlay.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, overlayHideMs);
        if (controller != null) {
            try { controller.show(overlayHideMs); } catch (Exception ignored) {}
        }
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
        if (meta != null) {
            meta.setText(String.format(Locale.US, "第%d/%d · %.2fx · %s",
                    index + 1, Math.max(pl, 1), speed,
                    CastProtocol.MODE_DIRECT.equals(mode) ? "直链" : "推流"));
        }

        long updated = st.optLong("updatedAt", 0);
        // 联播关键：代理路径常为 /stream/current，靠 index/updatedAt/完整 url 判断换集
        boolean urlChanged = url != null && !url.isEmpty() && !url.equals(currentUrl);
        boolean episodeChanged = (updated > 0 && updated != lastUpdatedAt) || (index != lastIndex);
        if (url != null && !url.isEmpty() && (urlChanged || episodeChanged)) {
            lastUpdatedAt = updated;
            lastIndex = index;
            playUrl(url);
        }

        // 倍速：只要与当前不同就应用（不依赖 commandId，避免漏同步）
        if (Math.abs(speed - currentSpeed) > 0.01f) {
            currentSpeed = speed;
            applySpeed(speed);
            updateSpeedButton();
        }

        if (cmd != lastCommandId) {
            lastCommandId = cmd;
            // 新命令且 index 已变但 URL 因某种原因没触发：再强制一次
            if (index != lastIndex && url != null && !url.isEmpty()) {
                lastIndex = index;
                lastUpdatedAt = updated;
                playUrl(url);
            } else if (seekMs >= 0 && seekMs != 0) {
                try { video.seekTo((int) seekMs); } catch (Exception ignored) {}
            } else if (seekMs == 0 && prepared && video != null) {
                // 新一集 applyIndex 会把 seekMs 置 0，从头播
                try { video.seekTo(0); } catch (Exception ignored) {}
            }
            try {
                if (playing) {
                    if (!video.isPlaying()) video.start();
                    if (btnPlayPause != null) btnPlayPause.setText("暂停");
                } else {
                    if (video.isPlaying()) video.pause();
                    if (btnPlayPause != null) btnPlayPause.setText("播放");
                }
            } catch (Exception ignored) {}
            applySpeed(currentSpeed);
        }
    }

    private void playUrl(String url) {
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        prepared = false;
        mediaPlayer = null;
        try {
            video.stopPlayback();
        } catch (Exception ignored) {}
        try {
            video.setVideoURI(Uri.parse(url));
            video.requestFocus();
            video.start();
            showOverlay();
            if (meta != null) meta.setText("加载中…");
        } catch (Exception e) {
            Toast.makeText(this, "打开流失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 真正把倍速作用到 MediaPlayer。
     * 部分 TV 的 VideoView.setPlaybackParams 无效，必须用 onPrepared 拿到的 MediaPlayer。
     */
    private void applySpeed(float speed) {
        if (speed < 0.5f) speed = 0.5f;
        if (speed > 2.0f) speed = 2.0f;
        currentSpeed = speed;
        updateSpeedButton();
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        if (mediaPlayer == null) {
            // 尚未 prepared，等 onPrepared 再设
            return;
        }
        try {
            PlaybackParams params;
            try {
                params = mediaPlayer.getPlaybackParams();
            } catch (Exception e) {
                params = new PlaybackParams();
            }
            params.setSpeed(speed);
            // 部分机顶盒需要指定 pitch
            try {
                params.setPitch(1.0f);
            } catch (Exception ignored) {}
            mediaPlayer.setPlaybackParams(params);
            // 设置后若被暂停，恢复播放状态
            if (video != null && !video.isPlaying()) {
                // 不强制 start，尊重 pause 状态
            }
        } catch (Exception e) {
            // 最后尝试反射 VideoView
            try {
                PlaybackParams params = new PlaybackParams();
                params.setSpeed(speed);
                video.getClass().getMethod("setPlaybackParams", PlaybackParams.class)
                        .invoke(video, params);
            } catch (Exception ignored) {}
        }
    }

    private void postControlJson(JSONObject body) {
        if (body == null) return;
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
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
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
        if (stream == null) throw new IllegalStateException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code >= 400) throw new IllegalStateException("HTTP " + code);
        return new JSONObject(sb.toString());
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBackMs < 2000) {
                try {
                    finish();
                } catch (Exception e) {
                    finish();
                }
                return true;
            }
            lastBackMs = now;
            Toast.makeText(this, "再按一次返回到首页", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!allowLocalControl) return super.onKeyDown(keyCode, event);
        showOverlay();
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            togglePlayPause();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // 长按右键切倍速？短按下一集。用 MEDIA_FAST_FORWARD 切倍速
            if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                cycleSpeed(true);
            } else {
                postControlJson(jsonAction("next"));
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                cycleSpeed(false);
            } else {
                postControlJson(jsonAction("prev"));
            }
            return true;
        }
        // 上/下键循环倍速，方便遥控
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            cycleSpeed(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            cycleSpeed(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackMs < 2000) {
            finish();
            return;
        }
        lastBackMs = now;
        Toast.makeText(this, "再按一次返回到首页", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(poll);
        ui.removeCallbacks(hideOverlay);
        mediaPlayer = null;
        net.shutdownNow();
        super.onDestroy();
    }
}
