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
                int resume = WatchHistory.lastIndex(this, seriesId);
                if (eps != null && resume >= 0 && resume < eps.length()) {
                    episodeAdapter.setSelected(resume);
                    try {
                        GridView g = findViewById(R.id.episodeGrid);
                        if (g != null) g.setSelection(resume);
                    } catch (Exception ignored) {}
                    meta.append("\n上次看到第 " + (resume + 1) + " 集");
                } else {
                    episodeAdapter.setSelected(0);
                }
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
        AppLog.i("用户点击" + (pushTv ? "推送" : "播放") + " 选中集=" + episodeAdapter.getSelected());
        ProgressDialog pd = ProgressDialog.show(this, null, "正在取流（App/公共）…", true, true);
        net.execute(() -> {
            try {
                JSONArray eps = detail.getJSONArray("episodes");
                if (idx < 0 || idx >= eps.length()) throw new IllegalStateException("无此集");
                JSONObject ep = eps.getJSONObject(idx);
                String vid = ep.optString("vid");
                boolean locked = ep.optBoolean("locked");
                JSONObject resolved;
                                // 始终按 preferredQuality 走 App 取流，避免公共源 360p 试看
                AppLog.i("调用 resolveEpisode vid=" + vid + " ep=" + (idx + 1) + " locked=" + locked
                        + " pref=" + DeviceSettings.INSTANCE.preferredQuality);
                resolved = HongguoClient.resolveEpisode(seriesId, vid, idx + 1,
                        DeviceSettings.INSTANCE.preferredQuality);
                AppLog.i("resolve 结果 source=" + resolved.optString("source")
                        + " quality=" + resolved.optString("quality")
                        + " height=" + resolved.optInt("height")
                        + " url=" + AppLog.clip(resolved.optString("url"), 80));
                String url = resolved.optString("url");
                if (url.isEmpty()) throw new IllegalStateException("取流失败 "
                        + com.hongguotv.phone.core.AppPlayback.lastError());
                String title = detail.optString("title") + " 第" + (idx + 1) + "集";
                WatchHistory.updateProgress(SeriesDetailActivity.this, seriesId, idx,
                        detail.optString("title"), detail.optString("cover"));
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
                            // 有序全列表：每集带 vid，联播切集时按 preferredQuality 重解析
                            JSONArray pl = new JSONArray();
                            for (int i = 0; i < eps.length(); i++) {
                                JSONObject e = eps.getJSONObject(i);
                                JSONObject item = new JSONObject()
                                        .put("title", detail.optString("title") + " 第" + (i + 1) + "集")
                                        .put("seriesId", seriesId)
                                        .put("vid", e.optString("vid"))
                                        .put("episode", i + 1);
                                if (i == idx) {
                                    item.put("url", fUrl)
                                            .put("quality", fQuality)
                                            .put("source", resolved.optString("source", "app"));
                                } else {
                                    // 占位：不把公共 360 写死进列表，next 时再取流
                                    item.put("url", "")
                                            .put("quality", "")
                                            .put("source", "");
                                }
                                pl.put(item);
                            }
                            CastServerService.NOW.setPlaylist(pl, idx);
                            // TV 只收手机流：强制 source 为明文路径，TV 播 http://phone:8765/stream/current
                            CastServerService.NOW.sourceUrl = fUrl;
                            if (CastServerService.CACHE != null && !fUrl.startsWith("file://") && !fUrl.startsWith("/")) {
                                CastServerService.CACHE.setCurrent(fUrl);
                            }
                            WatchHistory.updateProgress(this, seriesId, idx,
                                    detail.optString("title"), detail.optString("cover"));
                            EpisodePrefetcher.prefetchAfter(detail, idx);
                            Toast.makeText(this, "已推送电视 · " + fQuality
                                    + (fUrl.startsWith("file") ? "（明文）" : ""), Toast.LENGTH_SHORT).show();
                            AppLog.i("推送TV source=" + AppLog.clip(fUrl, 80));
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
                        WatchHistory.updateProgress(this, seriesId, idx,
                                detail.optString("title"), detail.optString("cover"));
                        EpisodePrefetcher.prefetchAfter(detail, idx);
                    }
                });
            } catch (Exception e) {
                AppLog.e("播放取流异常: " + e.getMessage() + " last=" + com.hongguotv.phone.core.AppPlayback.lastError(), e);
                ui.post(() -> {
                    pd.dismiss();
                    String msg = "取流失败: " + e.getMessage()
                            + " | " + com.hongguotv.phone.core.AppPlayback.lastError()
                            + " | 请到设置-运行日志查看";
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
