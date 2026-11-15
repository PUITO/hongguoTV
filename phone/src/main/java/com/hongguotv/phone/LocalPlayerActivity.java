package com.hongguotv.phone;

import android.app.Activity;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            controller.show(3000);
        });
        video.setOnCompletionListener(mp -> playIndex(index + 1));
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "播放失败，可能需切换清晰度或片源", Toast.LENGTH_LONG).show();
            return true;
        });
        // long press title area: push to TV
        titleView.setOnLongClickListener(v -> {
            pushCurrent();
            return true;
        });
        titleView.setOnClickListener(v -> {
            if (controller.isShowing()) controller.hide();
            else controller.show(4000);
        });
        if (url != null) {
            video.setVideoURI(Uri.parse(url));
            video.start();
        }
    }

    private void playIndex(int next) {
        if (detail == null) return;
        try {
            JSONArray eps = detail.getJSONArray("episodes");
            if (next < 0 || next >= eps.length()) {
                Toast.makeText(this, "没有更多集了", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject ep = eps.getJSONObject(next);
            if (ep.optBoolean("locked") || ep.optString("url").isEmpty()) {
                Toast.makeText(this, "本集公共源不可用（需 App 高清源）", Toast.LENGTH_SHORT).show();
                return;
            }
            index = next;
            String title = detail.optString("title") + " 第" + (index + 1) + "集";
            titleView.setText(title);
            video.setVideoURI(Uri.parse(ep.optString("url")));
            video.start();
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
            if (DeviceSettings.INSTANCE.isProxy() && CastServerService.CACHE != null) {
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
