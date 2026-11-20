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
import android.widget.FrameLayout;
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

    private static final float[] SPEED_STEPS = {0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f, 3.0f};

    private VideoView video;
    private TextView titleView, meta;
    private View overlay;
    private SpeedMediaController controller;
    private MediaPlayer mediaPlayer;
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

    /** 部分机顶盒会丢掉 PlaybackParams，播放中周期性重设 */
    private final Runnable reapplySpeed = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (prepared && mediaPlayer != null) {
                applySpeedOnce(currentSpeed);
            }
            if (!destroyed) ui.postDelayed(this, 2500);
        }
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
                if (!destroyed) ui.postDelayed(this, 800);
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

        // 隐藏旧底部按钮条，倍速只走 MediaController
        hideLegacyButtons();

        controller = new SpeedMediaController(this);
        controller.setAnchorView(video);
        controller.setMediaPlayer(video);
        controller.setOnSpeedChangeListener(speed -> {
            currentSpeed = speed;
            applySpeed(speed, true);
            // 同步回手机，遥控与其它端一致
            try {
                postControlJson(new JSONObject().put("action", "speed").put("value", speed));
            } catch (Exception ignored) {}
            Toast.makeText(this, "倍速 " + speed + "x", Toast.LENGTH_SHORT).show();
            updateMetaSpeed();
        });
        video.setMediaController(controller);
        controller.setSpeedLabel(currentSpeed);

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
                        meta.setText(String.format(Locale.US, "倍速 %.2fx", currentSpeed));
                    } else {
                        meta.setText(String.format(Locale.US, "倍速 %.2fx · %d秒 · %dx%d",
                                currentSpeed, dur / 1000, vw, vh));
                    }
                }
            } catch (Exception ignored) {}
            // prepared 后必须再设倍速
            applySpeed(currentSpeed, true);
            try {
                if (!video.isPlaying()) video.start();
            } catch (Exception ignored) {}
            try {
                controller.setSpeedLabel(currentSpeed);
                controller.show(overlayHideMs);
            } catch (Exception ignored) {}
            showTitleOverlay();
        });

        // 点击画面显示系统控制器（含倍速）
        video.setOnClickListener(v -> {
            try { controller.show(overlayHideMs); } catch (Exception ignored) {}
            showTitleOverlay();
        });

        if (url != null && !url.isEmpty()) playUrl(url);
        ui.post(poll);
        ui.postDelayed(reapplySpeed, 4000);
    }

    private void hideLegacyButtons() {
        int[] ids = {R.id.btnPrev, R.id.btnNext, R.id.btnPlayPause, R.id.btnSpeed};
        for (int id : ids) {
            View b = findViewById(id);
            if (b != null) b.setVisibility(View.GONE);
        }
        // 整行按钮容器
        View overlay = findViewById(R.id.overlay);
        if (overlay instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) overlay;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof android.widget.LinearLayout) {
                    android.widget.LinearLayout ll = (android.widget.LinearLayout) child;
                    if (ll.getChildCount() >= 3) {
                        // 可能是按钮行
                        boolean allButtons = true;
                        for (int j = 0; j < ll.getChildCount(); j++) {
                            if (!(ll.getChildAt(j) instanceof android.widget.Button)) {
                                allButtons = false;
                                break;
                            }
                        }
                        if (allButtons) ll.setVisibility(View.GONE);
                    }
                }
            }
        }
    }

    private void showTitleOverlay() {
        if (!showOverlayPref || overlay == null) return;
        // 只显示标题/meta，不显示按钮
        overlay.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, overlayHideMs);
    }

    private void updateMetaSpeed() {
        if (meta == null) return;
        String base = meta.getText() == null ? "" : meta.getText().toString();
        // 简单重写
        meta.setText(String.format(Locale.US, "倍速 %.2fx", currentSpeed));
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
        // 注意：不要在这里用 defaultSpeed 覆盖正在播放的倍速
        // 倍速只跟 NOW.speed（手机遥控 / 播放器内选择）走
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
        boolean urlChanged = url != null && !url.isEmpty() && !url.equals(currentUrl);
        boolean episodeChanged = (updated > 0 && updated != lastUpdatedAt) || (index != lastIndex);
        if (url != null && !url.isEmpty() && (urlChanged || episodeChanged)) {
            lastUpdatedAt = updated;
            lastIndex = index;
            playUrl(url);
        }

        // 手机遥控倍速：只要 NOW.speed 变化就立刻作用到 MediaPlayer
        if (Math.abs(speed - currentSpeed) > 0.01f) {
            currentSpeed = speed;
            applySpeed(speed, true);
            if (controller != null) controller.setSpeedLabel(speed);
        }

        if (cmd != lastCommandId) {
            lastCommandId = cmd;
            if (index != lastIndex && url != null && !url.isEmpty()) {
                lastIndex = index;
                lastUpdatedAt = updated;
                playUrl(url);
            } else if (seekMs > 0) {
                try { video.seekTo((int) seekMs); } catch (Exception ignored) {}
            } else if (seekMs == 0 && prepared) {
                try { video.seekTo(0); } catch (Exception ignored) {}
            }
            try {
                if (playing) {
                    if (!video.isPlaying()) video.start();
                } else {
                    if (video.isPlaying()) video.pause();
                }
            } catch (Exception ignored) {}
            // 命令变化后再强制倍速（换集/起播）
            applySpeed(currentSpeed, true);
            if (controller != null) controller.setSpeedLabel(currentSpeed);
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
            showTitleOverlay();
            if (meta != null) meta.setText("加载中…");
            try { controller.show(overlayHideMs); } catch (Exception ignored) {}
        } catch (Exception e) {
            Toast.makeText(this, "打开流失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * @param force  true 时即使与记录相同也再 set 一次
     */
    private void applySpeed(float speed, boolean force) {
        if (speed < 0.75f) speed = 0.75f;
        if (speed > 3.0f) speed = 3.0f;
        currentSpeed = speed;
        if (controller != null) {
            try { controller.setSpeedLabel(speed); } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT < 23) return;
        // 立刻 + 延迟重试（部分盒子 onPrepared 后立刻 set 会被吞掉）
        applySpeedOnce(speed);
        ui.removeCallbacks(speedRetry1);
        ui.removeCallbacks(speedRetry2);
        ui.postDelayed(speedRetry1, 200);
        ui.postDelayed(speedRetry2, 800);
    }

    private final Runnable speedRetry1 = () -> applySpeedOnce(currentSpeed);
    private final Runnable speedRetry2 = () -> applySpeedOnce(currentSpeed);

    private void applySpeedOnce(float speed) {
        if (Build.VERSION.SDK_INT < 23) return;
        PlaybackParams params = new PlaybackParams();
        try {
            params.setSpeed(speed);
            params.setPitch(1.0f);
        } catch (Exception ignored) {}
        boolean ok = false;
        if (mediaPlayer != null) {
            try {
                // 必须在 started 状态才稳
                try {
                    if (!mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                    }
                } catch (Exception ignored) {}
                mediaPlayer.setPlaybackParams(params);
                ok = true;
                // 校验
                try {
                    float actual = mediaPlayer.getPlaybackParams().getSpeed();
                    if (Math.abs(actual - speed) > 0.08f) ok = false;
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        if (!ok && video != null) {
            try {
                video.getClass().getMethod("setPlaybackParams", PlaybackParams.class)
                        .invoke(video, params);
                ok = true;
            } catch (Exception ignored) {}
        }
        // 再试一次：从当前 params 改 speed
        if (!ok && mediaPlayer != null) {
            try {
                PlaybackParams cur = mediaPlayer.getPlaybackParams();
                cur.setSpeed(speed);
                mediaPlayer.setPlaybackParams(cur);
            } catch (Exception ignored) {}
        }
    }

    /** 上键加快、下键减慢，到边界不再循环 */
    private void cycleLocalSpeed(boolean forward) {
        int idx = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_STEPS.length; i++) {
            float d = Math.abs(SPEED_STEPS[i] - currentSpeed);
            if (d < best) { best = d; idx = i; }
        }
        if (forward) {
            if (idx < SPEED_STEPS.length - 1) idx++;
        } else {
            if (idx > 0) idx--;
        }
        float next = SPEED_STEPS[idx];
        if (Math.abs(next - currentSpeed) < 0.01f) {
            Toast.makeText(this, "已是 " + formatSpeed(next), Toast.LENGTH_SHORT).show();
            try { controller.show(overlayHideMs); } catch (Exception ignored) {}
            return;
        }
        currentSpeed = next;
        applySpeed(next, true);
        try {
            postControlJson(new JSONObject().put("action", "speed").put("value", next));
        } catch (Exception ignored) {}
        Toast.makeText(this, "倍速 " + formatSpeed(next), Toast.LENGTH_SHORT).show();
        try { controller.show(overlayHideMs); } catch (Exception ignored) {}
        updateMetaSpeed();
    }

    private static String formatSpeed(float s) {
        if (Math.abs(s - Math.round(s)) < 0.01f) return Math.round(s) + "x";
        return String.format(java.util.Locale.US, "%.2fx", s);
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
                finish();
                return true;
            }
            lastBackMs = now;
            Toast.makeText(this, "再按一次返回到首页", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!allowLocalControl) return super.onKeyDown(keyCode, event);
        try { controller.show(overlayHideMs); } catch (Exception ignored) {}
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            try {
                if (video.isPlaying()) video.pause();
                else video.start();
            } catch (Exception ignored) {}
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            cycleLocalSpeed(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            cycleLocalSpeed(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            try { postControlJson(new JSONObject().put("action", "next")); } catch (Exception ignored) {}
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            try { postControlJson(new JSONObject().put("action", "prev")); } catch (Exception ignored) {}
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
        ui.removeCallbacks(reapplySpeed);
        ui.removeCallbacks(speedRetry1);
        ui.removeCallbacks(speedRetry2);
        mediaPlayer = null;
        net.shutdownNow();
        super.onDestroy();
    }
}
