package com.hongguotv.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private EpisodeAdapter episodeAdapter;
    private TextView meta, sourceNote;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_series_detail);
        seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String cover = getIntent().getStringExtra(EXTRA_COVER);
        ((TextView) findViewById(R.id.detailTitle)).setText(title == null ? "剧集" : title);
        ImageLoader.load(findViewById(R.id.cover), cover);
        meta = findViewById(R.id.meta);
        sourceNote = findViewById(R.id.sourceNote);
        GridView grid = findViewById(R.id.episodeGrid);
        episodeAdapter = new EpisodeAdapter(this);
        grid.setAdapter(episodeAdapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            episodeAdapter.setSelected(position);
            try {
                JSONObject ep = (JSONObject) episodeAdapter.getItem(position);
                if (ep != null && ep.optBoolean("locked")) {
                    Toast.makeText(this, "本集公共源锁定，需 App 高清解析", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ignored) {}
        });
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
            ui.post(() -> {
                meta.setText(detail.optString("title") + "\n共 "
                        + detail.optInt("episodeCnt") + " 集 · 公共可播 "
                        + detail.optInt("accessible") + " 集\n清晰度偏好 "
                        + DeviceSettings.INSTANCE.preferredQuality);
                if (sourceNote != null) sourceNote.setText(detail.optString("note"));
                episodeAdapter.setData(eps);
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
            int idx = episodeAdapter.getSelected();
            if (idx < 0 || idx >= eps.length()) idx = 0;
            JSONObject ep = eps.getJSONObject(idx);
            if (ep.optBoolean("locked") || ep.optString("url").isEmpty()) {
                Toast.makeText(this, "选中集不可播（公共源限制），请换未锁定集", Toast.LENGTH_LONG).show();
                return;
            }
            String url = ep.optString("url");
            String title = detail.optString("title") + " 第" + (idx + 1) + "集";
            if (pushTv) {
                JSONArray pl = new JSONArray();
                int playIndex = 0;
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.getJSONObject(i);
                    if (e.optBoolean("locked") || e.optString("url").isEmpty()) continue;
                    if (i == idx) playIndex = pl.length();
                    pl.put(new JSONObject()
                            .put("url", e.optString("url"))
                            .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                            .put("seriesId", seriesId)
                            .put("quality", e.optString("quality")));
                }
                CastServerService.NOW.setPlaylist(pl, playIndex);
                if (DeviceSettings.INSTANCE.isProxy() && CastServerService.CACHE != null) {
                    CastServerService.CACHE.setCurrent(url);
                }
                Toast.makeText(this, "已推送到电视（" + ep.optString("quality", "360p") + "）", Toast.LENGTH_SHORT).show();
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
