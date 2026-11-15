package com.hongguotv.phone;

import android.app.Activity;
import android.content.Intent;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

public class LocalPlayerActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_SERIES_ID = "seriesId";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_DETAIL_JSON = "detail";

    private VideoView video;
    private TextView titleView;
    private MediaController controller;
    private JSONObject detail;
    private int index;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingUrl;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_local_player);
            video = findViewById(R.id.video);
            titleView = findViewById(R.id.title);
            index = getIntent().getIntExtra(EXTRA_INDEX, 0);
            try {
                String raw = getIntent().getStringExtra(EXTRA_DETAIL_JSON);
                detail = raw == null ? null : new JSONObject(raw);
            } catch (Exception e) {
                detail = null;
            }
            String url = getIntent().getStringExtra(EXTRA_URL);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            titleView.setText(title == null ? "播放" : title);

            controller = new MediaController(this);
            controller.setAnchorView(video);
            controller.setMediaPlayer(video);
            video.setMediaController(controller);
            video.setOnPreparedListener(mp -> {
                try {
                    float speed = DeviceSettings.INSTANCE.defaultSpeed;
                    if (Build.VERSION.SDK_INT >= 23) {
                        PlaybackParams params = mp.getPlaybackParams();
                        params.setSpeed(speed);
                        mp.setPlaybackParams(params);
                    }
                } catch (Exception ignored) {}
                video.start();
                try { controller.show(3000); } catch (Exception ignored) {}
            });
            video.setOnCompletionListener(mp -> playIndex(index + 1));
            video.setOnErrorListener((mp, what, extra) -> {
                AppLog.e("VideoView error what=" + what + " extra=" + extra);
                Toast.makeText(this, "播放失败 what=" + what, Toast.LENGTH_LONG).show();
                return true;
            });

            // 可选按钮（布局里可能没有）
            setClick(R.id.btnPrev, v -> playIndex(index - 1));
            setClick(R.id.btnNext, v -> playIndex(index + 1));
            setClick(R.id.btnPush, v -> pushCurrent());

            pendingUrl = url;
            // 不阻塞主线程：异步尝试代理，再开播
            startPlaybackSafely(url);
        } catch (Exception e) {
            AppLog.e("LocalPlayer onCreate 崩溃", e);
            Toast.makeText(this, "播放页异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setClick(int id, View.OnClickListener l) {
        View v = findViewById(id);
        if (v != null) v.setOnClickListener(l);
    }

    private void startPlaybackSafely(String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "无播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String play = url;
            try {
                ensureCastService();
                play = toPlayableUrl(url);
            } catch (Exception e) {
                AppLog.w("代理准备失败，直连: " + e.getMessage());
            }
            final String finalUrl = play;
            ui.post(() -> {
                try {
                    AppLog.i("VideoView 打开 " + AppLog.clip(finalUrl, 100));
                    video.setVideoURI(Uri.parse(finalUrl));
                } catch (Exception e) {
                    AppLog.e("setVideoURI 失败", e);
                    Toast.makeText(this, "无法打开视频: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }, "play-proxy").start();
    }

    private void ensureCastService() {
        if (CastServerService.INSTANCE != null) return;
        try {
            Intent i = new Intent(this, CastServerService.class);
            String pair = "0000";
            try {
                pair = getSharedPreferences("device_settings", MODE_PRIVATE)
                        .getString("pairCode", "0000");
            } catch (Exception ignored) {}
            if (pair == null || pair.isEmpty()) pair = "0000";
            i.putExtra(CastServerService.EXTRA_PAIR, pair);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            for (int n = 0; n < 40 && CastServerService.INSTANCE == null; n++) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            AppLog.i("投屏服务 " + (CastServerService.INSTANCE != null ? "已就绪" : "未就绪"));
        } catch (Exception e) {
            AppLog.w("启动投屏服务失败: " + e.getMessage());
        }
    }

    private String toPlayableUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        try {
            CastServerService svc = CastServerService.INSTANCE;
            if (svc != null && svc.getServer() != null) {
                String proxy = svc.getServer().proxyUrl(url);
                if (proxy != null && !proxy.isEmpty()) {
                    AppLog.i("本地播放走代理 " + AppLog.clip(proxy, 80));
                    return proxy;
                }
            }
            AppLog.w("投屏服务未启动，直连 CDN");
        } catch (Exception e) {
            AppLog.w("代理地址失败: " + e.getMessage());
        }
        return url;
    }

    private void playIndex(int idx) {
        if (detail == null) return;
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
            startPlaybackSafely(u);
        } catch (Exception e) {
            Toast.makeText(this, "切换失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void pushCurrent() {
        if (detail == null) return;
        try {
            JSONArray eps = detail.getJSONArray("episodes");
            JSONArray pl = new JSONArray();
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.getJSONObject(i);
                if (e.optBoolean("locked") || e.optString("url").isEmpty()) continue;
                pl.put(new JSONObject()
                        .put("url", e.optString("url"))
                        .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                        .put("seriesId", detail.optString("seriesId")));
            }
            int playIdx = 0;
            for (int i = 0; i < pl.length(); i++) {
                if (pl.getJSONObject(i).optString("title").contains("第" + (index + 1) + "集")) {
                    playIdx = i;
                    break;
                }
            }
            CastServerService.NOW.setPlaylist(pl, playIdx);
            if (CastServerService.CACHE != null) {
                CastServerService.CACHE.setCurrent(eps.getJSONObject(index).optString("url"));
            }
            Toast.makeText(this, "已推送到电视", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "推送失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onBackPressed() {
        if (controller != null && controller.isShowing()) {
            controller.hide();
            return;
        }
        super.onBackPressed();
    }
}
