package com.hongguotv.phone;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.GridView;
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
        grid.setOnItemClickListener((parent, view, position, id) -> episodeAdapter.setSelected(position));
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
                        + detail.optInt("episodeCnt") + " 集 · 公共可播约 "
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
        final int idx = episodeAdapter.getSelected();
        ProgressDialog pd = ProgressDialog.show(this, null, "正在取流（App/公共）…", true, true);
        net.execute(() -> {
            try {
                JSONArray eps = detail.getJSONArray("episodes");
                if (idx < 0 || idx >= eps.length()) throw new IllegalStateException("无此集");
                JSONObject ep = eps.getJSONObject(idx);
                String vid = ep.optString("vid");
                boolean locked = ep.optBoolean("locked");
                JSONObject resolved;
                if (!locked && ep.optString("url").length() > 0) {
                    // still try App for higher quality when preferred not 360p
                    String pref = DeviceSettings.INSTANCE.preferredQuality;
                    if (pref != null && !"360p".equals(pref)) {
                        try {
                            resolved = HongguoClient.resolveEpisode(seriesId, vid, idx + 1, pref);
                        } catch (Exception e) {
                            resolved = ep;
                        }
                    } else {
                        resolved = ep;
                    }
                } else {
                    resolved = HongguoClient.resolveEpisode(seriesId, vid, idx + 1,
                            DeviceSettings.INSTANCE.preferredQuality);
                }
                String url = resolved.optString("url");
                if (url.isEmpty()) throw new IllegalStateException("取流失败 "
                        + com.hongguotv.phone.core.AppPlayback.lastError());
                String title = detail.optString("title") + " 第" + (idx + 1) + "集";
                // write back resolved url
                ep.put("url", url);
                ep.put("locked", false);
                ep.put("quality", resolved.optString("quality", ep.optString("quality")));
                ep.put("source", resolved.optString("source", "app"));
                final String fUrl = url;
                final String fTitle = title;
                final String fQuality = ep.optString("quality");
                ui.post(() -> {
                    pd.dismiss();
                    episodeAdapter.notifyDataSetChanged();
                    if (pushTv) {
                        try {
                            JSONArray pl = new JSONArray();
                            pl.put(new JSONObject()
                                    .put("url", fUrl)
                                    .put("title", fTitle)
                                    .put("seriesId", seriesId)
                                    .put("quality", fQuality));
                            // also enqueue unlocked neighbors if any
                            for (int i = 0; i < eps.length(); i++) {
                                if (i == idx) continue;
                                JSONObject e = eps.getJSONObject(i);
                                if (e.optBoolean("locked") || e.optString("url").isEmpty()) continue;
                                pl.put(new JSONObject()
                                        .put("url", e.optString("url"))
                                        .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                                        .put("seriesId", seriesId));
                            }
                            CastServerService.NOW.setPlaylist(pl, 0);
                            if (DeviceSettings.INSTANCE.isProxy() && CastServerService.CACHE != null) {
                                CastServerService.CACHE.setCurrent(fUrl);
                            }
                            Toast.makeText(this, "已推送电视 · " + fQuality, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "推送失败", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Intent i = new Intent(this, LocalPlayerActivity.class);
                        i.putExtra(LocalPlayerActivity.EXTRA_URL, fUrl);
                        i.putExtra(LocalPlayerActivity.EXTRA_TITLE, fTitle);
                        i.putExtra(LocalPlayerActivity.EXTRA_SERIES_ID, seriesId);
                        i.putExtra(LocalPlayerActivity.EXTRA_INDEX, idx);
                        i.putExtra(LocalPlayerActivity.EXTRA_DETAIL_JSON, detail.toString());
                        startActivity(i);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    pd.dismiss();
                    Toast.makeText(this, "取流失败: " + e.getMessage()
                            + "\n" + com.hongguotv.phone.core.AppPlayback.lastError(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
