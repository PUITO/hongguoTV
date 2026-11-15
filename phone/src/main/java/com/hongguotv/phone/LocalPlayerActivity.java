package com.hongguotv.phone;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
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
        findViewById(R.id.btnPrev).setOnClickListener(v -> switchEp(-1));
        findViewById(R.id.btnNext).setOnClickListener(v -> switchEp(1));
        findViewById(R.id.btnPush).setOnClickListener(v -> pushCurrent());
        if (url != null) {
            video.setVideoURI(Uri.parse(url));
            video.setOnPreparedListener(mp -> video.start());
            video.start();
        }
    }

    private void switchEp(int delta) {
        if (detail == null) return;
        try {
            JSONArray eps = detail.getJSONArray("episodes");
            int next = index + delta;
            if (next < 0 || next >= eps.length()) {
                Toast.makeText(this, "没有更多集了", Toast.LENGTH_SHORT).show();
                return;
            }
            index = next;
            JSONObject ep = eps.getJSONObject(index);
            String url = ep.optString("url");
            String title = detail.optString("title") + " 第" + (index + 1) + "集";
            titleView.setText(title);
            video.setVideoURI(Uri.parse(url));
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
                if (e.optString("url").isEmpty()) continue;
                pl.put(new JSONObject()
                        .put("url", e.optString("url"))
                        .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                        .put("seriesId", detail.optString("seriesId")));
            }
            CastServerService.NOW.setPlaylist(pl, index);
            if (DeviceSettings.INSTANCE.isProxy() && CastServerService.CACHE != null) {
                CastServerService.CACHE.setCurrent(eps.getJSONObject(index).optString("url"));
            }
            Toast.makeText(this, "已推送到电视", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "推送失败", Toast.LENGTH_SHORT).show();
        }
    }
}
