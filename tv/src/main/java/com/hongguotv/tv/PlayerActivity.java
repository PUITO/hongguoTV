package com.hongguotv.tv;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

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

/**
 * TV 播放：ExoPlayer（支持 0.75–3x 倍速；VideoView/MediaPlayer 在多数盒子上无效）。
 */
public class PlayerActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PHONE_IP = "phone_ip";
    public static final String EXTRA_PHONE_PORT = "phone_port";
    public static final String EXTRA_PAIR = "pair";

    private static final float[] SPEED_STEPS = {
            0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f, 3.0f
    };

    private PlayerView playerView;
    private ExoPlayer player;
    private TextView titleView, meta, speedHint;
    private View overlay;
    private String phoneIp, pair;
    private int phonePort;
    private long lastCommandId = -1;
    private long lastSettingsRev = -1;
    private String currentUrl = "";
    private long lastUpdatedAt = -1;
    private int lastIndex = -1;
    private float currentSpeed = 1.0f;
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
                    JSONObject st = httpGet(
                            "http://" + phoneIp + ":" + phonePort + CastProtocol.PATH_CONTROL, pair);
                    ui.post(() -> applyState(st));
                } catch (Exception e) {
                    TvLog.w("poll: " + e.getMessage());
                }
                if (!destroyed) ui.postDelayed(this, 800);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.playerView);
        titleView = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        speedHint = findViewById(R.id.speedHint);
        overlay = findViewById(R.id.overlay);
        phoneIp = getIntent().getStringExtra(EXTRA_PHONE_IP);
        phonePort = getIntent().getIntExtra(EXTRA_PHONE_PORT, CastProtocol.DEFAULT_PORT);
        pair = getIntent().getStringExtra(EXTRA_PAIR);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(t == null || t.isEmpty() ? "播放中" : t);

        currentSpeed = TvPrefs.defaultSpeed(this);
        TvLog.i("Player 启动 defaultSpeed=" + currentSpeed + " url=" + clip(url));

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(overlayHideMs);
        player.setPlaybackSpeed(currentSpeed);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    float actual = player.getPlaybackParameters().speed;
                    TvLog.i("STATE_READY actualSpeed=" + actual + " target=" + currentSpeed);
                    if (Math.abs(actual - currentSpeed) > 0.05f) {
                        player.setPlaybackSpeed(currentSpeed);
                        TvLog.w("READY 后重设倍速 -> " + currentSpeed);
                    }
                    updateMeta();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                TvLog.e("播放错误: " + error.getErrorCodeName() + " " + error.getMessage());
                Toast.makeText(PlayerActivity.this,
                        "播放失败: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
            }
        });

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    TvLog.i("播放完成 -> next");
                    try {
                        postControlJson(new JSONObject().put("action", "next").put("fromComplete", true));
                    } catch (Exception ignored) {}
                }
            }
        });

        if (url != null && !url.isEmpty()) playUrl(url);
        ui.post(poll);
        showTitleOverlay();
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
        try {
            playerView.setControllerShowTimeoutMs(overlayHideMs);
        } catch (Exception ignored) {}
    }

    private void applyState(JSONObject st) {
        if (st == null) return;
        applySettings(st.optJSONObject("settings"));
        long cmd = st.optLong("commandId", 0);
        String url = st.optString("url", "");
        String title = st.optString("title", "");
        boolean playing = st.optBoolean("playing", true);
        long seekMs = st.optLong("seekMs", -1);
        int index = st.optInt("index", 0);
        int pl = st.optInt("playlistSize", 0);
        String mode = st.optString("playMode", CastProtocol.MODE_PROXY);

        if (title != null && !title.isEmpty()) titleView.setText(title);
        updateMeta(index, pl, mode);

        long updated = st.optLong("updatedAt", 0);
        boolean urlChanged = url != null && !url.isEmpty() && !url.equals(currentUrl);
        boolean episodeChanged = (updated > 0 && updated != lastUpdatedAt) || (index != lastIndex);
        if (url != null && !url.isEmpty() && (urlChanged || episodeChanged)) {
            lastUpdatedAt = updated;
            lastIndex = index;
            playUrl(url);
        }

        if (cmd != lastCommandId) {
            lastCommandId = cmd;
            if (index != lastIndex && url != null && !url.isEmpty()) {
                lastIndex = index;
                lastUpdatedAt = updated;
                playUrl(url);
            } else if (seekMs > 0 && player != null) {
                try { player.seekTo(seekMs); } catch (Exception ignored) {}
            } else if (seekMs == 0 && player != null) {
                try { player.seekTo(0); } catch (Exception ignored) {}
            }
            try {
                if (player != null) {
                    if (playing) player.play();
                    else player.pause();
                }
            } catch (Exception ignored) {}
            // 换集后保持本机倍速
            applyLocalSpeed(currentSpeed, "command");
        }
    }

    private void playUrl(String url) {
        if (url == null || url.isEmpty() || player == null) return;
        currentUrl = url;
        TvLog.i("playUrl " + clip(url));
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            player.prepare();
            player.setPlaybackSpeed(currentSpeed);
            player.play();
            showTitleOverlay();
            if (meta != null) meta.setText("加载中… · " + formatSpeed(currentSpeed));
        } catch (Exception e) {
            TvLog.e("打开流失败: " + e.getMessage());
            Toast.makeText(this, "打开流失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyLocalSpeed(float speed, String reason) {
        if (speed < 0.75f) speed = 0.75f;
        if (speed > 3.0f) speed = 3.0f;
        currentSpeed = speed;
        if (player != null) {
            try {
                player.setPlaybackSpeed(speed);
                float actual = player.getPlaybackParameters().speed;
                TvLog.i("setSpeed reason=" + reason + " target=" + speed + " actual=" + actual);
                if (Math.abs(actual - speed) > 0.08f) {
                    TvLog.w("倍速未对齐 actual=" + actual);
                    Toast.makeText(this, "倍速可能未生效 actual=" + actual, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                TvLog.e("setPlaybackSpeed 失败: " + e.getMessage());
                Toast.makeText(this, "倍速设置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            TvLog.w("setSpeed 时 player=null");
        }
        updateMeta();
        if (speedHint != null) {
            speedHint.setText("倍速 " + formatSpeed(speed) + " · 上/下键调节");
        }
    }

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
            return;
        }
        applyLocalSpeed(next, forward ? "dpad-up" : "dpad-down");
        Toast.makeText(this, "倍速 " + formatSpeed(next), Toast.LENGTH_SHORT).show();
        try { playerView.showController(); } catch (Exception ignored) {}
        showTitleOverlay();
    }

    private void updateMeta() {
        updateMeta(lastIndex < 0 ? 0 : lastIndex, 0, "");
    }

    private void updateMeta(int index, int pl, String mode) {
        if (meta == null) return;
        String modeLabel = CastProtocol.MODE_DIRECT.equals(mode) ? "直链" : "推流";
        if (pl > 0) {
            meta.setText(String.format(Locale.US, "第%d/%d · %s · %s",
                    index + 1, pl, formatSpeed(currentSpeed), modeLabel));
        } else {
            meta.setText(String.format(Locale.US, "%s", formatSpeed(currentSpeed)));
        }
    }

    private void showTitleOverlay() {
        if (!showOverlayPref || overlay == null) return;
        overlay.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, overlayHideMs);
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
            } catch (Exception e) {
                TvLog.w("postControl: " + e.getMessage());
            }
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

    private static String formatSpeed(float s) {
        if (Math.abs(s - Math.round(s)) < 0.01f) return Math.round(s) + "x";
        return String.format(Locale.US, "%.2fx", s);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
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
        try { playerView.showController(); } catch (Exception ignored) {}
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            try {
                if (player != null) {
                    if (player.isPlaying()) player.pause();
                    else player.play();
                }
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
        // 菜单键打开日志
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            startActivity(new android.content.Intent(this, LogActivity.class));
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
    protected void onStop() {
        super.onStop();
        try {
            if (player != null) player.pause();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(poll);
        ui.removeCallbacks(hideOverlay);
        try {
            if (player != null) {
                player.release();
                player = null;
            }
        } catch (Exception ignored) {}
        net.shutdownNow();
        super.onDestroy();
    }
}
