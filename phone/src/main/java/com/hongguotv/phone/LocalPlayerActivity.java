package com.hongguotv.phone;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 使用 MediaPlayer + 官方 UA 头播放 CDN，避免 VideoView 无请求头导致黑屏。
 */
public class LocalPlayerActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_SERIES_ID = "seriesId";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_DETAIL_JSON = "detail";

    private static final String VIDEO_UA =
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)";

    private SurfaceView surface;
    private TextView titleView;
    private MediaPlayer player;
    private JSONObject detail;
    private int index;
    private String currentUrl;
    private boolean surfaceReady;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_local_player);
            surface = findViewById(R.id.surface);
            titleView = findViewById(R.id.title);
            surface.getHolder().addCallback(this);
            index = getIntent().getIntExtra(EXTRA_INDEX, 0);
            try {
                String raw = getIntent().getStringExtra(EXTRA_DETAIL_JSON);
                detail = raw == null ? null : new JSONObject(raw);
            } catch (Exception e) {
                detail = null;
            }
            currentUrl = getIntent().getStringExtra(EXTRA_URL);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            titleView.setText(title == null ? "播放" : title);

            findViewById(R.id.btnPlayPause).setOnClickListener(v -> togglePlay());
            findViewById(R.id.btnPush).setOnClickListener(v -> pushCurrent());

            AppLog.i("LocalPlayer url=" + AppLog.clip(currentUrl, 100));
        } catch (Exception e) {
            AppLog.e("LocalPlayer onCreate", e);
            Toast.makeText(this, "播放页异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        openPlayer(currentUrl);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        releasePlayer();
    }

    private void openPlayer(String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "无播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!surfaceReady) {
            currentUrl = url;
            return;
        }
        releasePlayer();
        currentUrl = url;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            player.setDisplay(surface.getHolder());
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", VIDEO_UA);
            headers.put("Referer", "https://www.hongguoduanju.com/");
            headers.put("Accept", "*/*");
            // 优先直连 CDN + 请求头；失败再试本机代理
            boolean opened = false;
            try {
                player.setDataSource(this, android.net.Uri.parse(url), headers);
                opened = true;
                AppLog.i("MediaPlayer setDataSource 直连+UA");
            } catch (Exception e) {
                AppLog.w("直连 setDataSource 失败: " + e.getMessage());
            }
            if (!opened) {
                String proxy = tryProxy(url);
                player.setDataSource(proxy);
                AppLog.i("MediaPlayer setDataSource 代理 " + AppLog.clip(proxy, 80));
            }
            player.setOnPreparedListener(mp -> {
                try {
                    float speed = DeviceSettings.INSTANCE.defaultSpeed;
                    if (Build.VERSION.SDK_INT >= 23 && speed > 0.1f) {
                        PlaybackParams params = mp.getPlaybackParams();
                        params.setSpeed(speed);
                        mp.setPlaybackParams(params);
                    }
                } catch (Exception ignored) {}
                AppLog.i("MediaPlayer prepared durationMs=" + mp.getDuration());
                mp.start();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                AppLog.e("MediaPlayer error what=" + what + " extra=" + extra);
                // 直连失败时再试代理
                if (url.startsWith("http") && !url.contains("127.0.0.1")) {
                    ui.post(() -> retryViaProxy(url));
                } else {
                    ui.post(() -> Toast.makeText(this, "播放失败 what=" + what + " extra=" + extra, Toast.LENGTH_LONG).show());
                }
                return true;
            });
            player.setOnCompletionListener(mp -> playIndex(index + 1));
            player.setOnInfoListener((mp, what, extra) -> {
                AppLog.i("MediaPlayer info what=" + what + " extra=" + extra);
                return false;
            });
            player.prepareAsync();
        } catch (Exception e) {
            AppLog.e("openPlayer 失败", e);
            Toast.makeText(this, "无法打开: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void retryViaProxy(String url) {
        try {
            String proxy = tryProxy(url);
            AppLog.i("错误后改走代理 " + proxy);
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            player.setDisplay(surface.getHolder());
            player.setDataSource(proxy);
            player.setOnPreparedListener(mp -> {
                AppLog.i("代理 prepared durationMs=" + mp.getDuration());
                mp.start();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                AppLog.e("代理播放仍失败 what=" + what + " extra=" + extra);
                Toast.makeText(this, "代理播放失败 what=" + what, Toast.LENGTH_LONG).show();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            AppLog.e("retryViaProxy", e);
            Toast.makeText(this, "代理失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String tryProxy(String url) {
        try {
            if (CastServerService.INSTANCE == null) {
                android.content.Intent i = new android.content.Intent(this, CastServerService.class);
                i.putExtra(CastServerService.EXTRA_PAIR, "0000");
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
                for (int n = 0; n < 40 && CastServerService.INSTANCE == null; n++) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
            CastServerService svc = CastServerService.INSTANCE;
            if (svc != null && svc.getServer() != null) {
                return svc.getServer().proxyUrl(url);
            }
        } catch (Exception e) {
            AppLog.w("tryProxy: " + e.getMessage());
        }
        return url;
    }

    private void togglePlay() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.pause();
            else player.start();
        } catch (Exception ignored) {}
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void playIndex(int idx) {
        if (detail == null) return;
        try {
            JSONArray eps = detail.getJSONArray("episodes");
            if (idx < 0 || idx >= eps.length()) return;
            JSONObject ep = eps.getJSONObject(idx);
            String u = ep.optString("url");
            if (u.isEmpty()) {
                Toast.makeText(this, "本集无地址", Toast.LENGTH_SHORT).show();
                return;
            }
            index = idx;
            titleView.setText(detail.optString("title") + " 第" + (index + 1) + "集");
            openPlayer(u);
        } catch (Exception e) {
            Toast.makeText(this, "切换失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void pushCurrent() {
        if (detail == null || currentUrl == null) return;
        try {
            JSONArray eps = detail.getJSONArray("episodes");
            JSONArray pl = new JSONArray();
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.getJSONObject(i);
                String u = e.optString("url");
                if (u.isEmpty()) continue;
                pl.put(new JSONObject()
                        .put("url", u)
                        .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                        .put("seriesId", detail.optString("seriesId")));
            }
            int playIdx = Math.max(0, Math.min(index, pl.length() - 1));
            // 确保当前集用最新 CDN 地址
            if (index < eps.length()) {
                // rebuild index from matched url
            }
            CastServerService.NOW.setPlaylist(pl.length() > 0 ? pl :
                    new JSONArray().put(new JSONObject().put("url", currentUrl).put("title", titleView.getText().toString())),
                    playIdx);
            if (CastServerService.CACHE != null) {
                CastServerService.CACHE.setCurrent(currentUrl);
            }
            // 推送时用当前实际播放地址
            CastServerService.NOW.sourceUrl = currentUrl;
            Toast.makeText(this, "已推送到电视", Toast.LENGTH_SHORT).show();
            AppLog.i("推送电视 url=" + AppLog.clip(currentUrl, 80));
        } catch (Exception e) {
            Toast.makeText(this, "推送失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }
}
