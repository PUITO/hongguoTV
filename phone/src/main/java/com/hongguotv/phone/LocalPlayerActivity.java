package com.hongguotv.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocalPlayerActivity extends Activity implements TextureView.SurfaceTextureListener {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_SERIES_ID = "seriesId";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_DETAIL_JSON = "detail";

    private static final String VIDEO_UA =
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)";

    private TextureView texture;
    private TextView titleView, loading, timeText;
    private View controls;
    private SeekBar seek;
    private ImageButton btnPlay;
    private MediaPlayer player;
    private Surface surface;
    private JSONObject detail;
    private int index;
    private String currentUrl;
    private boolean surfaceReady;
    private boolean userSeeking;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            ui.postDelayed(this, 500);
        }
    };

    private final Runnable hideControls = () -> {
        if (controls != null && player != null && player.isPlaying()) {
            controls.setVisibility(View.GONE);
            titleView.setVisibility(View.GONE);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_local_player);
            texture = findViewById(R.id.texture);
            titleView = findViewById(R.id.title);
            loading = findViewById(R.id.loading);
            controls = findViewById(R.id.controls);
            timeText = findViewById(R.id.timeText);
            seek = findViewById(R.id.seek);
            btnPlay = findViewById(R.id.btnPlay);
            texture.setSurfaceTextureListener(this);

            index = getIntent().getIntExtra(EXTRA_INDEX, 0);
            try {
                String sid0 = getIntent().getStringExtra(EXTRA_SERIES_ID);
                if (sid0 != null) {
                    WatchHistory.updateProgress(this, sid0, index,
                            getIntent().getStringExtra(EXTRA_TITLE), null);
                }
            } catch (Exception ignored) {}
            try {
                String raw = getIntent().getStringExtra(EXTRA_DETAIL_JSON);
                detail = raw == null ? null : new JSONObject(raw);
            } catch (Exception e) {
                detail = null;
            }
            currentUrl = getIntent().getStringExtra(EXTRA_URL);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            titleView.setText(title == null ? "播放" : title);

            findViewById(R.id.btnPrev).setOnClickListener(v -> playIndex(index - 1));
            findViewById(R.id.btnNext).setOnClickListener(v -> playIndex(index + 1));
            findViewById(R.id.btnPush).setOnClickListener(v -> pushCurrent());
            btnPlay.setOnClickListener(v -> togglePlay());

            findViewById(R.id.root).setOnClickListener(v -> {
                showControls();
            });

            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && player != null) {
                        try {
                            int dur = player.getDuration();
                            if (dur > 0) {
                                int pos = (int) (dur * (progress / 1000.0));
                                timeText.setText(fmt(pos) + " / " + fmt(dur));
                            }
                        } catch (Exception ignored) {}
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    userSeeking = false;
                    if (player != null) {
                        try {
                            int dur = player.getDuration();
                            if (dur > 0) {
                                int pos = (int) (dur * (seekBar.getProgress() / 1000.0));
                                player.seekTo(pos);
                            }
                        } catch (Exception ignored) {}
                    }
                    showControls();
                }
            });

            AppLog.i("LocalPlayer url=" + AppLog.clip(currentUrl, 100));
            ensureCastServiceAsync(() -> {
                // 等 surface
            });
        } catch (Exception e) {
            AppLog.e("LocalPlayer onCreate", e);
            Toast.makeText(this, "播放页异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        titleView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, 4000);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surfaceReady = true;
        surface = new Surface(st);
        AppLog.i("Surface ready " + width + "x" + height);
        openPlayer(currentUrl);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {}

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceReady = false;
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    private void openPlayer(String url) {
        if (url == null || url.isEmpty()) {
            loading.setText("无播放地址");
            return;
        }
        if (!surfaceReady || surface == null) {
            currentUrl = url;
            return;
        }
        releasePlayer();
        currentUrl = url;
        loading.setVisibility(View.VISIBLE);
        loading.setText("加载中…");
        showControls();

        new Thread(() -> {
            final String playUrl = buildPlayUrl(url);
            ui.post(() -> startMediaPlayer(playUrl, url));
        }, "open-player").start();
    }

    /**
     * CDN 对 Java Http 常 403，但对系统 MediaPlayer 可能放行。
     * 本地播放：先直连 CDN；TV 推流仍走手机代理缓存。
     */
    private String buildPlayUrl(String url) {
        if (url != null && (url.startsWith("file://") || url.startsWith("/"))) {
            AppLog.i("已是本地明文");
            return url;
        }
        // CDN 密文需先在取流阶段解密；此处仅直连尝试
        AppLog.i("本地播放地址 " + AppLog.clip(url, 80));
        return url;
    }

    private void startMediaPlayer(String playUrl, String originUrl) {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            player.setSurface(surface);

            if (playUrl.startsWith("file://") || playUrl.startsWith("/")) {
                String path = playUrl.startsWith("file://") ? playUrl.substring(7) : playUrl;
                player.setDataSource(path);
                AppLog.i("MediaPlayer 本地明文 " + path);
            } else if (playUrl.contains("127.0.0.1")) {
                player.setDataSource(playUrl);
                AppLog.i("MediaPlayer 代理 setDataSource");
            } else {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", VIDEO_UA);
                headers.put("Referer", "https://www.hongguoduanju.com/");
                headers.put("Accept", "*/*");
                player.setDataSource(this, Uri.parse(playUrl), headers);
                AppLog.i("MediaPlayer 直连+UA setDataSource");
            }

            player.setOnPreparedListener(mp -> {
                int w = mp.getVideoWidth();
                int h = mp.getVideoHeight();
                int dur = mp.getDuration();
                AppLog.i("MediaPlayer prepared " + w + "x" + h + " durationMs=" + dur);
                loading.setVisibility(View.GONE);
                try {
                    float speed = DeviceSettings.INSTANCE.defaultSpeed;
                    if (Build.VERSION.SDK_INT >= 23 && speed > 0.1f) {
                        PlaybackParams params = mp.getPlaybackParams();
                        params.setSpeed(speed);
                        mp.setPlaybackParams(params);
                    }
                } catch (Exception ignored) {}
                mp.start();
                btnPlay.setImageResource(android.R.drawable.ic_media_pause);
                ui.removeCallbacks(tick);
                ui.post(tick);
                showControls();
                if (w <= 0 || h <= 0) {
                    AppLog.w("视频宽高为 0，有声无画（编码）");
                    Toast.makeText(this, "有声无画：请确认使用 1080p(HEVC)", Toast.LENGTH_LONG).show();
                } else {
                    try {
                        mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    } catch (Exception ignored) {}
                    fitTextureToVideo(w, h);
                }
                // 连播预缓存
                if (DeviceSettings.INSTANCE.autoNext && detail != null) {
                    EpisodePrefetcher.prefetchAfter(detail, index);
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                AppLog.e("MediaPlayer error what=" + what + " extra=" + extra + " url=" + AppLog.clip(playUrl, 60));
                loading.setVisibility(View.VISIBLE);
                loading.setText("播放失败 what=" + what);
                // 若代理失败，再试一次直连
                if (playUrl.contains("127.0.0.1") && originUrl != null && !originUrl.contains("127.0.0.1")) {
                    ui.postDelayed(() -> {
                        AppLog.i("代理失败，尝试直连");
                        startMediaPlayer(originUrl, null);
                    }, 300);
                } else {
                    Toast.makeText(this, "播放失败 what=" + what + " extra=" + extra, Toast.LENGTH_LONG).show();
                }
                return true;
            });
            player.setOnCompletionListener(mp -> playIndex(index + 1));
            player.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    loading.setVisibility(View.VISIBLE);
                    loading.setText("缓冲中…");
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    loading.setVisibility(View.GONE);
                    AppLog.i("MediaPlayer info rendering/buffer end what=" + what);
                }
                return false;
            });
            player.prepareAsync();
        } catch (Exception e) {
            AppLog.e("startMediaPlayer", e);
            loading.setText("无法打开: " + e.getMessage());
            Toast.makeText(this, "无法打开: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void ensureCastServiceAsync(Runnable done) {
        new Thread(() -> {
            ensureCastServiceBlocking();
            if (done != null) ui.post(done);
        }).start();
    }

    private void ensureCastServiceBlocking() {
        if (CastServerService.INSTANCE != null && CastServerService.INSTANCE.getServer() != null) return;
        try {
            Intent i = new Intent(this, CastServerService.class);
            i.putExtra(CastServerService.EXTRA_PAIR, "0000");
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            for (int n = 0; n < 50; n++) {
                if (CastServerService.INSTANCE != null && CastServerService.INSTANCE.getServer() != null) break;
                try { Thread.sleep(40); } catch (InterruptedException ignored) {}
            }
            AppLog.i("投屏服务 " + (CastServerService.INSTANCE != null ? "就绪" : "未就绪"));
        } catch (Exception e) {
            AppLog.w("启动投屏服务: " + e.getMessage());
        }
    }

    private void togglePlay() {
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                btnPlay.setImageResource(android.R.drawable.ic_media_play);
            } else {
                player.start();
                btnPlay.setImageResource(android.R.drawable.ic_media_pause);
            }
            showControls();
        } catch (Exception ignored) {}
    }

    private void updateProgress() {
        if (player == null || userSeeking) return;
        try {
            int pos = player.getCurrentPosition();
            int dur = player.getDuration();
            if (dur > 0) {
                seek.setProgress((int) (1000.0 * pos / dur));
                timeText.setText(fmt(pos) + " / " + fmt(dur));
            }
        } catch (Exception ignored) {}
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    
    private void fitTextureToVideo(int videoW, int videoH) {
        if (texture == null || videoW <= 0 || videoH <= 0) return;
        View parent = (View) texture.getParent();
        if (parent == null) return;
        int viewW = parent.getWidth();
        int viewH = parent.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            parent.post(() -> fitTextureToVideo(videoW, videoH));
            return;
        }
        float videoRatio = videoW / (float) videoH;
        float viewRatio = viewW / (float) viewH;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) texture.getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(0, 0);
        if (videoRatio > viewRatio) {
            lp.width = viewW;
            lp.height = Math.max(1, Math.round(viewW / videoRatio));
        } else {
            lp.height = viewH;
            lp.width = Math.max(1, Math.round(viewH * videoRatio));
        }
        lp.gravity = Gravity.CENTER;
        texture.setLayoutParams(lp);
        AppLog.i("画面适配 video=" + videoW + "x" + videoH + " -> " + lp.width + "x" + lp.height);
    }

    private void releasePlayer() {
        ui.removeCallbacks(tick);
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void playIndex(int idx) {
        if (detail == null) {
            Toast.makeText(this, "无剧集列表，请回详情页选集", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONArray eps = detail.getJSONArray("episodes");
            if (idx < 0 || idx >= eps.length()) return;
            JSONObject ep = eps.getJSONObject(idx);
            String u = ep.optString("url");
            if (u.isEmpty()) {
                Toast.makeText(this, "本集无地址，请回详情重新取流", Toast.LENGTH_SHORT).show();
                return;
            }
            index = idx;
            titleView.setText(detail.optString("title") + " 第" + (index + 1) + "集");
            try {
                WatchHistory.updateProgress(this, seriesId, index,
                        detail.optString("title"), detail.optString("cover"));
            } catch (Exception ignored) {}
            openPlayer(u);
        } catch (Exception e) {
            Toast.makeText(this, "切换失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void pushCurrent() {
        if (currentUrl == null || currentUrl.isEmpty()) return;
        try {
            ensureCastServiceBlocking();
            JSONArray pl = new JSONArray();
            if (detail != null) {
                JSONArray eps = detail.getJSONArray("episodes");
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.getJSONObject(i);
                    String u = e.optString("url");
                    if (u.isEmpty()) continue;
                    pl.put(new JSONObject()
                            .put("url", u)
                            .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                            .put("seriesId", detail.optString("seriesId")));
                }
            }
            if (pl.length() == 0) {
                pl.put(new JSONObject().put("url", currentUrl).put("title", titleView.getText().toString()));
            }
            int playIdx = Math.max(0, Math.min(index, pl.length() - 1));
            CastServerService.NOW.setPlaylist(pl, playIdx);
            CastServerService.NOW.sourceUrl = currentUrl;
            if (CastServerService.CACHE != null) CastServerService.CACHE.setCurrent(currentUrl);
            Toast.makeText(this, "已推送到电视", Toast.LENGTH_SHORT).show();
            AppLog.i("推送TV " + AppLog.clip(currentUrl, 80));
        } catch (Exception e) {
            Toast.makeText(this, "推送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        releasePlayer();
        ui.removeCallbacks(hideControls);
        super.onDestroy();
    }
}
