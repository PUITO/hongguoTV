package com.hongguotv.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SeriesDetailActivity extends Activity {
    public static final String EXTRA_SERIES_ID = "seriesId";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_COVER = "cover";

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String seriesId;
    private JSONObject detail;
    private int selectedEp = 0;
    private GridView grid;
    private TextView meta;
    private List<String> epLabels = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_series_detail);
        seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String cover = getIntent().getStringExtra(EXTRA_COVER);
        TextView titleView = findViewById(R.id.detailTitle);
        titleView.setText(title == null ? "剧集" : title);
        ImageView coverView = findViewById(R.id.cover);
        ImageLoader.load(coverView, cover);
        meta = findViewById(R.id.meta);
        grid = findViewById(R.id.episodeGrid);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlayPhone).setOnClickListener(v -> play(false));
        findViewById(R.id.btnPushTv).setOnClickListener(v -> play(true));
        meta.setText("加载选集中…");
        net.execute(this::loadDetail);
    }

    private void loadDetail() {
        try {
            detail = HongguoClient.detail(seriesId);
            JSONArray eps = detail.optJSONArray("episodes");
            epLabels.clear();
            if (eps != null) {
                for (int i = 0; i < eps.length(); i++) {
                    epLabels.add(String.valueOf(i + 1));
                }
            }
            ui.post(() -> {
                meta.setText(detail.optString("title") + "\n共 "
                        + detail.optInt("episodeCnt") + " 集 · 可播 "
                        + detail.optInt("accessible") + " 集\n点击集数后选择播放方式");
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1, epLabels);
                grid.setAdapter(adapter);
                grid.setOnItemClickListener((parent, view, position, id) -> {
                    selectedEp = position;
                    Toast.makeText(this, "已选第" + (position + 1) + "集", Toast.LENGTH_SHORT).show();
                });
                try {
                    WatchHistory.add(this, new JSONObject()
                            .put("seriesId", seriesId)
                            .put("title", detail.optString("title"))
                            .put("cover", detail.optString("cover")));
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            ui.post(() -> meta.setText("加载失败: " + e.getMessage()));
        }
    }

    private void play(boolean pushTv) {
        if (detail == null) {
            Toast.makeText(this, "尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONArray eps = detail.getJSONArray("episodes");
            if (eps.length() == 0) {
                Toast.makeText(this, "无剧集", Toast.LENGTH_SHORT).show();
                return;
            }
            int idx = Math.min(selectedEp, eps.length() - 1);
            JSONObject ep = eps.getJSONObject(idx);
            String url = ep.optString("url");
            if (url.isEmpty()) {
                Toast.makeText(this, "该集无播放地址", Toast.LENGTH_SHORT).show();
                return;
            }
            String title = detail.optString("title") + " 第" + (idx + 1) + "集";
            if (pushTv) {
                // build playlist from accessible eps
                JSONArray pl = new JSONArray();
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.getJSONObject(i);
                    if (e.optString("url").isEmpty()) continue;
                    pl.put(new JSONObject()
                            .put("url", e.optString("url"))
                            .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                            .put("seriesId", seriesId));
                }
                CastServerService.NOW.setPlaylist(pl, idx);
                if (DeviceSettings.INSTANCE.isProxy() && CastServerService.CACHE != null) {
                    CastServerService.CACHE.setCurrent(url);
                }
                Toast.makeText(this, "已推送到电视", Toast.LENGTH_SHORT).show();
            } else {
                Intent i = new Intent(this, LocalPlayerActivity.class);
                i.putExtra(LocalPlayerActivity.EXTRA_URL, url);
                i.putExtra(LocalPlayerActivity.EXTRA_TITLE, title);
                i.putExtra(LocalPlayerActivity.EXTRA_SERIES_ID, seriesId);
                i.putExtra(LocalPlayerActivity.EXTRA_INDEX, idx);
                i.putExtra(LocalPlayerActivity.EXTRA_DETAIL_JSON, detail.toString());
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
