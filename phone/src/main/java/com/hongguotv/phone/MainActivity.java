package com.hongguotv.phone;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.hongguotv.shared.CastProtocol;
import com.hongguotv.shared.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAMERA = 1001;
    private ViewFlipper flipper;
    private TextView topBar, status, bindStatus, pairCodeView, searchResult, cacheStatus, remoteHint;
    private EditText urlInput, titleInput, searchInput, seriesInput, epInput;
    private SwitchCompat switchAutoNext, switchOverlay, switchKeepOn, switchTvLocal;
    private RadioGroup playModeGroup;
    private ListView seriesList;
    private TextView listHeader;
    private SeriesListAdapter seriesAdapter;
    private Spinner speedSpinner;
    private Spinner qualitySpinner;
    private boolean showHistory;
    private boolean searching;
    private TextView nowTitle, nowMeta, tabRecommend, tabHistory;
    private TextView[] speedChips;
    private TextView[] qualityChips;
    private float currentUiSpeed = 1.5f;
    private String pairCode = "0000";
    private boolean running;
    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshCacheStatus();
            refreshBindStatus();
            refreshStatus();
            refreshNowPlaying();
            ui.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        flipper = findViewById(R.id.flipper);
        topBar = findViewById(R.id.topBar);
        status = findViewById(R.id.status);
        bindStatus = findViewById(R.id.bindStatus);
        pairCodeView = findViewById(R.id.pairCode);
        urlInput = findViewById(R.id.urlInput);
        titleInput = findViewById(R.id.titleInput);
        searchInput = findViewById(R.id.searchInput);
        seriesInput = findViewById(R.id.seriesInput);
        epInput = findViewById(R.id.epInput);
        searchResult = findViewById(R.id.searchResult);
        cacheStatus = findViewById(R.id.cacheStatus);
        remoteHint = findViewById(R.id.remoteHint);
        switchAutoNext = findViewById(R.id.switchAutoNext);
        switchOverlay = findViewById(R.id.switchOverlay);
        switchKeepOn = findViewById(R.id.switchKeepOn);
        switchTvLocal = findViewById(R.id.switchTvLocal);
        playModeGroup = findViewById(R.id.playModeGroup);
        seriesList = findViewById(R.id.seriesList);
        listHeader = findViewById(R.id.listHeader);
        speedSpinner = findViewById(R.id.speedSpinner);
        seriesAdapter = new SeriesListAdapter(this);
        seriesList.setAdapter(seriesAdapter);
        seriesList.setOnItemClickListener((parent, view, position, id) -> {
            try {
                org.json.JSONObject o = (org.json.JSONObject) seriesAdapter.getItem(position);
                if (o == null) return;
                Intent di = new Intent(this, SeriesDetailActivity.class);
                di.putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, o.optString("seriesId"));
                di.putExtra(SeriesDetailActivity.EXTRA_TITLE, o.optString("title"));
                di.putExtra(SeriesDetailActivity.EXTRA_COVER, o.optString("cover"));
                startActivity(di);
            } catch (Exception ignored) {}
        });
        seriesList.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!showHistory) {
                toast("长按删除仅在「历史」中可用");
                return true;
            }
            try {
                org.json.JSONObject o = (org.json.JSONObject) seriesAdapter.getItem(position);
                if (o == null) return true;
                String sid = o.optString("seriesId");
                String title = o.optString("title", sid);
                new AlertDialog.Builder(this)
                        .setTitle("删除历史")
                        .setMessage("删除「" + title + "」？")
                        .setPositiveButton("删除", (d, w) -> {
                            WatchHistory.remove(this, sid);
                            loadSourceList(false);
        setupCacheControls();

                            toast("已删除");
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } catch (Exception ignored) {}
            return true;
        });
        String[] speeds = new String[]{"0.75","1.0","1.25","1.5","2.0"};
        ArrayAdapter<String> spAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, speeds);
        speedSpinner.setAdapter(spAdapter);
        float def = DeviceSettings.INSTANCE.defaultSpeed;
        int sel = 3; // 1.5
        for (int i = 0; i < speeds.length; i++) {
            try { if (Math.abs(Float.parseFloat(speeds[i]) - def) < 0.01f) sel = i; } catch (Exception ignored) {}
        }
        speedSpinner.setSelection(sel);
        speedSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                try {
                    float s = Float.parseFloat(speeds[position]);
                    applySetting(put("defaultSpeed", s));
                    speed(s);
                } catch (Exception ignored) {}
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); return true; }
            return false;
        });
        qualitySpinner = findViewById(R.id.qualitySpinner);
        String[] qualities = new String[]{"auto", "360p", "480p", "720p", "1080p"};
        ArrayAdapter<String> qAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, qualities);
        qualitySpinner.setAdapter(qAdapter);
        String pq = DeviceSettings.INSTANCE.preferredQuality;
        int qsel = 2;
        for (int i = 0; i < qualities.length; i++) if (qualities[i].equals(pq)) qsel = i;
        qualitySpinner.setSelection(qsel);
        qualitySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                applySetting(put("preferredQuality", qualities[position]));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        tabRecommend = findViewById(R.id.tabRecommend);
        tabHistory = findViewById(R.id.tabHistory);
        if (tabRecommend != null) tabRecommend.setOnClickListener(v -> {
            showHistory = false; searching = false; updateTabStyle(); loadSourceList(true);
        });
        if (tabHistory != null) tabHistory.setOnClickListener(v -> {
            showHistory = true; searching = false; updateTabStyle(); loadSourceList(false);
        });
        nowTitle = findViewById(R.id.nowTitle);
        nowMeta = findViewById(R.id.nowMeta);
        speedChips = new TextView[]{
                findViewById(R.id.btnSp075), findViewById(R.id.btnSp10), findViewById(R.id.btnSp125),
                findViewById(R.id.btnSp15), findViewById(R.id.btnSp20)
        };
        float[] speedVals = new float[]{0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        for (int i = 0; i < speedChips.length; i++) {
            final float sv = speedVals[i];
            if (speedChips[i] != null) speedChips[i].setOnClickListener(v -> {
                currentUiSpeed = sv;
                // 与设置页同步，并实时推给 TV / 本机 NOW
                applySetting(put("defaultSpeed", sv));
                speed(sv);
                highlightSpeedChips();
            });
        }
        qualityChips = new TextView[]{
                findViewById(R.id.chipQAuto), findViewById(R.id.chipQ360),
                findViewById(R.id.chipQ720), findViewById(R.id.chipQ1080)
        };
        // 必须与 qualityChips 一一对应（之前 720 芯片写成了 480p）
        String[] qVals = new String[]{"auto", "360p", "720p", "1080p"};
        for (int i = 0; i < qualityChips.length; i++) {
            final String qv = qVals[i];
            if (qualityChips[i] != null) qualityChips[i].setOnClickListener(v -> {
                applySetting(put("preferredQuality", qv));
                highlightQualityChips();
                toast("清晰度偏好 " + qv + "（下次取流/预缓存生效）");
            });
        }
        currentUiSpeed = DeviceSettings.INSTANCE.defaultSpeed;
        updateTabStyle();
        highlightSpeedChips();
        highlightQualityChips();
        loadSourceList(false);

        SettingsStore.load(this);
        String savedPair = SettingsStore.loadPair(this, "");
        if (savedPair != null && savedPair.length() == 4) {
            pairCode = savedPair;
        } else {
            pairCode = String.format(Locale.US, "%04d", new Random().nextInt(10000));
            SettingsStore.savePair(this, pairCode);
        }
        pairCodeView.setText("配对码 " + pairCode);
        currentUiSpeed = DeviceSettings.INSTANCE.defaultSpeed;
        syncUiFromSettings();
        highlightSpeedChips();
        highlightQualityChips();
        showPage(0);

        findViewById(R.id.navHome).setOnClickListener(v -> showPage(0));
        findViewById(R.id.navSource).setOnClickListener(v -> showPage(1));
        findViewById(R.id.navSettings).setOnClickListener(v -> showPage(2));
        findViewById(R.id.navRemote).setOnClickListener(v -> showPage(3));
        View openLogs = findViewById(R.id.btnOpenLogs);
        if (openLogs != null) openLogs.setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));
        View clearDev = findViewById(R.id.btnClearDevice);
        if (clearDev != null) clearDev.setOnClickListener(v -> {
            com.hongguotv.phone.core.AppPlayback.clearIdentity();
            toast("已重置播放设备，请重试播放");
        });
        AppLog.i("MainActivity 启动");
        com.hongguotv.phone.core.AppPlayback.init(this);
        com.hongguotv.phone.core.PlaintextCache.init(getCacheDir());
        refreshCacheStats();

        findViewById(R.id.btnStart).setOnClickListener(v -> startServer());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopServer());
        findViewById(R.id.btnScanTv).setOnClickListener(v -> startScan());
        findViewById(R.id.btnPush).setOnClickListener(v -> pushManual());
        findViewById(R.id.btnSearch).setOnClickListener(v -> doSearch());
        findViewById(R.id.btnResolve).setOnClickListener(v -> resolveAndPush());
        findViewById(R.id.btnPlay).setOnClickListener(v -> control("play"));
        findViewById(R.id.btnPause).setOnClickListener(v -> control("pause"));
        findViewById(R.id.btnNext).setOnClickListener(v -> control("next"));
        findViewById(R.id.btnPrev).setOnClickListener(v -> control("prev"));

        playModeGroup.setOnCheckedChangeListener((g, id) -> {
            String mode = (id == R.id.modeDirect) ? CastProtocol.MODE_DIRECT : CastProtocol.MODE_PROXY;
            applySetting(put("playMode", mode));
            toast(CastProtocol.MODE_DIRECT.equals(mode) ? "已切换：电视直链" : "已切换：手机推流");
        });
        switchAutoNext.setOnCheckedChangeListener((b, c) -> applySetting(put("autoNext", c)));
        switchOverlay.setOnCheckedChangeListener((b, c) -> applySetting(put("showTvOverlay", c)));
        switchKeepOn.setOnCheckedChangeListener((b, c) -> applySetting(put("tvKeepScreenOn", c)));
        switchTvLocal.setOnCheckedChangeListener((b, c) -> applySetting(put("allowTvLocalControl", c)));

        ui.post(tick);
    }

    private void showPage(int index) {
        flipper.setDisplayedChild(index);
        String[] titles = {"连接电视", "片源", "设置", "遥控"};
        topBar.setText("红果推流 · " + titles[index]);
        highlightNav(index);
        if (index == 1 && !searching) loadSourceList(false);
        if (index == 2 || index == 3) {
            currentUiSpeed = DeviceSettings.INSTANCE.defaultSpeed;
            highlightSpeedChips();
            highlightQualityChips();
            highlightPrefetchChips();
            highlightCacheMbChips();
            refreshCacheStats();
        }
    }

    private void highlightNav(int index) {
        int[] ids = {R.id.navHome, R.id.navSource, R.id.navSettings, R.id.navRemote};
        for (int i = 0; i < ids.length; i++) {
            Button b = findViewById(ids[i]);
            b.setTextColor(i == index ? 0xFF1565C0 : 0xFF666666);
        }
    }

    private void syncUiFromSettings() {
        DeviceSettings s = DeviceSettings.INSTANCE;
        playModeGroup.check(s.isProxy() ? R.id.modeProxy : R.id.modeDirect);
        switchAutoNext.setChecked(s.autoNext);
        switchOverlay.setChecked(s.showTvOverlay);
        switchKeepOn.setChecked(s.tvKeepScreenOn);
        switchTvLocal.setChecked(s.allowTvLocalControl);
    }

    private void applySetting(JSONObject o) {
        DeviceSettings.INSTANCE.applyJson(o);
        SettingsStore.save(this);
        CastServerService.NOW.commandId++;
        if (o.has("playMode") && CastServerService.CACHE != null && DeviceSettings.INSTANCE.isProxy()) {
            String u = CastServerService.NOW.sourceUrl;
            if (u != null && !u.isEmpty()) CastServerService.CACHE.setCurrent(u);
        }
        refreshStatus();
    }

    private JSONObject put(String k, Object v) {
        try { return new JSONObject().put(k, v); } catch (Exception e) { return new JSONObject(); }
    }

    private void setDefaultSpeed(float s) {
        applySetting(put("defaultSpeed", s));
        speed(s);
        toast("默认倍速 " + s);
    }

    private void doSearch() {
        String kw = text(searchInput);
        searching = true;
        if (listHeader != null) listHeader.setText(kw.isEmpty() ? "推荐" : ("搜索：" + kw));
        if (searchResult != null) searchResult.setVisibility(android.view.View.VISIBLE);
        net.execute(() -> {
            try {
                JSONArray items = HongguoClient.search(kw);
                ui.post(() -> {
                    seriesAdapter.setData(items);
                    if (items.length() == 0) toast("未找到相关剧集，可下拉推荐或换关键词");
                });
            } catch (Exception e) {
                ui.post(() -> toast("搜索失败: " + e.getMessage()));
            }
        });
    }

    private void loadSourceList(boolean forceHome) {
        final boolean history = showHistory && !forceHome;
        net.execute(() -> {
            try {
                JSONArray show;
                String header;
                if (history) {
                    show = WatchHistory.list(this);
                    header = show.length() == 0
                            ? "历史为空 · 观看后会出现在这里 · 长按可删除"
                            : ("历史 " + show.length() + " 部 · 长按删除");
                } else {
                    show = HongguoClient.homeFeed();
                    header = "推荐 " + show.length() + " 部 · 点击进入详情";
                }
                JSONArray finalShow = show;
                String finalHeader = header;
                ui.post(() -> {
                    if (listHeader != null) listHeader.setText(finalHeader);
                    seriesAdapter.setData(finalShow);
                    updateTabStyle();
                });
            } catch (Exception e) {
                ui.post(() -> toast("加载失败: " + e.getMessage()));
            }
        });
    }

    private void resolveAndPush() {
        String sid = text(seriesInput);
        String ep = text(epInput);
        if (sid.isEmpty()) { toast("填写系列 ID"); return; }
        if (ep.isEmpty()) ep = "1";
        final String vid = ep;
        if (!running) startServer();
        searchResult.setText("解析中…");
        net.execute(() -> {
            try {
                JSONObject play = HongguoClient.resolvePlay(sid, vid);
                JSONArray pl = new JSONArray();
                int startEp = Integer.parseInt(vid);
                for (int i = startEp; i < startEp + 5; i++) {
                    try {
                        JSONObject p = (i == startEp) ? play : HongguoClient.resolvePlay(sid, String.valueOf(i));
                        pl.put(new JSONObject()
                                .put("url", p.optString("url"))
                                .put("title", p.optString("title"))
                                .put("seriesId", sid)
                                .put("vid", String.valueOf(i)));
                    } catch (Exception ignore) { break; }
                }
                CastServerService.NOW.setPlaylist(pl, 0);
                if (DeviceSettings.INSTANCE.isProxy() && CastServerService.CACHE != null) {
                    CastServerService.CACHE.setCurrent(play.optString("url"));
                    if (pl.length() > 1) CastServerService.CACHE.prefetch(pl.getJSONObject(1).optString("url"));
                }
                ui.post(() -> {
                    searchResult.setText("已推送: " + play.optString("title"));
                    toast("已推送到电视");
                    showPage(3);
                });
            } catch (Exception e) {
                ui.post(() -> searchResult.setText("解析失败: " + e.getMessage()));
            }
        });
    }

    private void pushManual() {
        String url = text(urlInput);
        String title = text(titleInput);
        if (url.isEmpty()) { toast("填写 URL"); return; }
        if (!running) startServer();
        CastServerService.NOW.set(url, title);
        if (DeviceSettings.INSTANCE.isProxy() && CastServerService.CACHE != null) {
            CastServerService.CACHE.setCurrent(url);
        }
        toast("已推送");
        showPage(3);
    }

    private void speed(float s) {
        try {
            currentUiSpeed = s;
            DeviceSettings.INSTANCE.defaultSpeed = s;
            SettingsStore.save(this);
            CastServerService.NOW.control("speed", new JSONObject().put("value", s));
            highlightSpeedChips();
            toast("倍速 " + s);
        } catch (Exception ignored) {}
    }

    private void control(String action) {
        try {
            CastServerService.NOW.control(action, new JSONObject());
            if (("next".equals(action) || "prev".equals(action))
                    && DeviceSettings.INSTANCE.isProxy()
                    && CastServerService.CACHE != null) {
                String u = CastServerService.NOW.sourceUrl;
                if (u != null && !u.isEmpty()) CastServerService.CACHE.setCurrent(u);
            }
        } catch (Exception ignored) {}
    }

    private void startServer() {
        String ip = localIp();
        if (ip.isEmpty()) {
            toast("未获取到手机 IP，请检查 Wi‑Fi");
            return;
        }
        Intent i = new Intent(this, CastServerService.class);
        i.putExtra(CastServerService.EXTRA_PAIR, pairCode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        running = true;
        toast("服务已启动 " + ip);
        refreshStatus();
    }

    private void stopServer() {
        stopService(new Intent(this, CastServerService.class));
        running = false;
        refreshStatus();
    }

    private void refreshStatus() {
        DeviceSettings s = DeviceSettings.INSTANCE;
        String ip = localIp();
        String mode = s.isProxy() ? "推流" : "直链";
        status.setText("本机 IP：" + (ip.isEmpty() ? "未检测到" : ip)
                + "\n服务：" + (running ? "已启动 · 端口 " + CastProtocol.DEFAULT_PORT : "未启动")
                + "\n模式：" + mode + " · 倍速 " + CastServerService.NOW.speed);
        remoteHint.setText(s.bound
                ? ("已绑定 " + s.boundTvIp + " · 片源 "
                + (CastServerService.NOW.sourceUrl.isEmpty() ? "空" : CastServerService.NOW.title))
                : "请先在「连接」页扫码绑定电视");
    }

    private void refreshBindStatus() {
        DeviceSettings s = DeviceSettings.INSTANCE;
        bindStatus.setText(s.bound
                ? ("已绑定电视 " + s.boundTvIp)
                : "未绑定 · 请扫描电视上的二维码");
    }

    private void refreshCacheStatus() {
        if (!DeviceSettings.INSTANCE.isProxy()) {
            cacheStatus.setText("直链模式：电视直接访问源站");
            return;
        }
        MediaCache.Job job = CastServerService.CACHE == null ? null : CastServerService.CACHE.currentJob();
        if (job == null) {
            cacheStatus.setText("推流缓存：无");
            return;
        }
        long w = job.written.get();
        long t = job.contentLength.get();
        String pct = t > 0 ? String.format(Locale.US, " %.0f%%", w * 100.0 / t) : "";
        cacheStatus.setText("推流缓存：" + (w / 1024) + "KB" + pct
                + (job.done.get() ? " 完成" : job.running.get() ? " 下载中" : job.failed.get() ? " 失败" : ""));
    }

    private void startScan() {
        if (!running) startServer();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("对准电视屏幕上的二维码");
        integrator.setBeepEnabled(false);
        integrator.initiateScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else {
            toast("需要相机权限才能扫码");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) handleScan(result.getContents());
            else toast("未识别到二维码");
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleScan(String raw) {
        String[] p = raw.trim().split("\\|");
        if (p.length >= 4 && CastProtocol.QR_PREFIX_TV.equals(p[0])) {
            String tvIp = p[1];
            if (tvIp.isEmpty() || "0.0.0.0".equals(tvIp) || tvIp.startsWith("0.")) {
                toast("电视二维码 IP 无效，请在电视点「重新检测 IP」后再扫");
                return;
            }
            int bindPort = CastProtocol.TV_BIND_PORT;
            try { bindPort = Integer.parseInt(p[2]); } catch (Exception ignored) {}
            final int port = bindPort;
            toast("正在绑定 " + tvIp + "…");
            net.execute(() -> registerToTv(tvIp, port, p[3]));
            return;
        }
        toast("无法识别：请扫电视上的绑定码");
    }

    private void registerToTv(String tvIp, int bindPort, String session) {
        try {
            String phoneIp = localIp();
            if (phoneIp.isEmpty()) {
                ui.post(() -> toast("手机 IP 无效，请检查 Wi‑Fi"));
                return;
            }
            JSONObject body = new JSONObject()
                    .put("session", session)
                    .put("phoneIp", phoneIp)
                    .put("port", CastProtocol.DEFAULT_PORT)
                    .put("pair", pairCode)
                    .put("settings", DeviceSettings.INSTANCE.toJson());
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://" + tvIp + ":" + bindPort + CastProtocol.PATH_REGISTER).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                DeviceSettings.INSTANCE.setBound(tvIp, bindPort, session);
                SettingsStore.save(this);
            }
            final int finalCode = code;
            ui.post(() -> {
                if (finalCode >= 200 && finalCode < 300) {
                    toast("绑定成功");
                    refreshBindStatus();
                    showPage(1);
                } else {
                    toast("绑定失败 HTTP " + finalCode);
                }
            });
        } catch (Exception e) {
            ui.post(() -> toast("绑定失败: " + e.getMessage()
                    + "\n可改用电视端「手动连接」填手机 IP + 配对码"));
        }
    }


    private void updateTabStyle() {
        if (tabRecommend != null) {
            tabRecommend.setBackgroundResource(showHistory ? R.drawable.bg_tab_normal : R.drawable.bg_tab_selected);
            tabRecommend.setTextColor(showHistory ? 0xFF666666 : 0xFFFFFFFF);
        }
        if (tabHistory != null) {
            tabHistory.setBackgroundResource(showHistory ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
            tabHistory.setTextColor(showHistory ? 0xFFFFFFFF : 0xFF666666);
        }
    }

    private void highlightSpeedChips() {
        float[] vals = new float[]{0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        float cur = DeviceSettings.INSTANCE.defaultSpeed;
        if (Math.abs(cur) < 0.01f) cur = currentUiSpeed;
        currentUiSpeed = cur;
        if (speedChips == null) return;
        for (int i = 0; i < speedChips.length; i++) {
            if (speedChips[i] == null) continue;
            boolean on = Math.abs(vals[i] - cur) < 0.01f;
            speedChips[i].setBackgroundResource(on ? R.drawable.bg_chip_selected : R.drawable.bg_chip_normal);
            speedChips[i].setTextColor(on ? 0xFFFFFFFF : 0xFF333333);
        }
    }

    private void highlightQualityChips() {
        String[] vals = new String[]{"auto", "360p", "720p", "1080p"};
        String cur = DeviceSettings.INSTANCE.preferredQuality;
        if (qualityChips == null) return;
        for (int i = 0; i < qualityChips.length; i++) {
            if (qualityChips[i] == null) continue;
            boolean on = vals[i].equals(cur);
            qualityChips[i].setBackgroundResource(on ? R.drawable.bg_chip_selected : R.drawable.bg_chip_normal);
            qualityChips[i].setTextColor(on ? 0xFFFFFFFF : 0xFF333333);
        }
    }

    private void refreshNowPlaying() {
        if (nowTitle == null) return;
        String title = CastServerService.NOW.title;
        String src = CastServerService.NOW.sourceUrl;
        int idx = CastServerService.NOW.index;
        int size = CastServerService.NOW.playlist.length();
        DeviceSettings s = DeviceSettings.INSTANCE;
        if (src == null || src.isEmpty()) {
            nowTitle.setText("暂无推送");
            nowMeta.setText("在「片源」选择剧集推送到电视后，这里显示播放信息");
        } else {
            nowTitle.setText(title == null || title.isEmpty() ? "播放中" : title);
            String mode = s.isProxy() ? "推流" : "直链";
            nowMeta.setText("进度 第" + (idx + 1) + (size > 0 ? ("/" + size) : "")
                    + " 集 · " + CastServerService.NOW.speed + "x · " + mode
                    + " · " + s.preferredQuality
                    + (CastServerService.NOW.playing ? " · 播放中" : " · 已暂停"));
        }
        if (remoteHint != null) {
            remoteHint.setText(s.bound
                    ? ("已绑定 " + s.boundTvIp)
                    : "未绑定电视 · 请先在「连接」扫码");
        }
        highlightSpeedChips();
    }

    private String localIp() {
        return NetUtil.localIpv4();
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
    private static String text(EditText e) { return e.getText() == null ? "" : e.getText().toString().trim(); }

    @Override protected void onDestroy() {
        ui.removeCallbacks(tick);
        net.shutdownNow();
        super.onDestroy();
    }

    private void setupCacheControls() {
        try {
            int[] prefIds = {R.id.chipPrefetch0, R.id.chipPrefetch1, R.id.chipPrefetch2, R.id.chipPrefetch3};
            int curPref = Math.max(0, Math.min(3, DeviceSettings.INSTANCE.prefetchEpisodes));
            for (int i = 0; i < prefIds.length; i++) {
                final int n = i;
                TextView chip = findViewById(prefIds[i]);
                if (chip == null) continue;
                chip.setOnClickListener(v -> {
                    DeviceSettings.INSTANCE.prefetchEpisodes = n;
                    try {
                        DeviceSettings.INSTANCE.applyJson(new JSONObject().put("prefetchEpisodes", n));
                    } catch (Exception ignored) {}
                    persistSettings();
                    highlightPrefetchChips();
                    refreshCacheStats();
                    toast(n == 0 ? "已关闭预缓存" : ("预缓存 " + n + " 集"));
                });
            }
            highlightPrefetchChips();

            int[] mbIds = {R.id.chipMb128, R.id.chipMb256, R.id.chipMb512, R.id.chipMb1024, R.id.chipMb2048};
            int[] mbVals = {128, 256, 512, 1024, 2048};
            for (int i = 0; i < mbIds.length; i++) {
                final int mb = mbVals[i];
                TextView chip = findViewById(mbIds[i]);
                if (chip == null) continue;
                chip.setOnClickListener(v -> {
                    DeviceSettings.INSTANCE.cacheMaxMb = mb;
                    try {
                        DeviceSettings.INSTANCE.applyJson(new JSONObject().put("cacheMaxMb", mb));
                    } catch (Exception ignored) {}
                    persistSettings();
                    try { com.hongguotv.phone.core.PlaintextCache.enforceLimits(); } catch (Exception ignored) {}
                    highlightCacheMbChips();
                    refreshCacheStats();
                    toast("缓存上限 " + mb + " MB");
                });
            }
            highlightCacheMbChips();

            Button clear = findViewById(R.id.btnClearCache);
            if (clear != null) {
                clear.setOnClickListener(v -> {
                    com.hongguotv.phone.core.PlaintextCache.clear();
                    refreshCacheStats();
                    toast("缓存已清理");
                });
            }
            refreshCacheStats();
        } catch (Exception e) {
            AppLog.w("setupCacheControls: " + e.getMessage());
        }
    }

    private void highlightPrefetchChips() {
        int[] ids = {R.id.chipPrefetch0, R.id.chipPrefetch1, R.id.chipPrefetch2, R.id.chipPrefetch3};
        int cur = Math.max(0, Math.min(3, DeviceSettings.INSTANCE.prefetchEpisodes));
        for (int i = 0; i < ids.length; i++) {
            TextView c = findViewById(ids[i]);
            if (c == null) continue;
            c.setBackgroundResource(i == cur ? R.drawable.bg_chip_selected : R.drawable.bg_chip_normal);
            c.setTextColor(i == cur ? 0xFFFFFFFF : 0xFF333333);
        }
    }

    private void highlightCacheMbChips() {
        int[] ids = {R.id.chipMb128, R.id.chipMb256, R.id.chipMb512, R.id.chipMb1024, R.id.chipMb2048};
        int[] vals = {128, 256, 512, 1024, 2048};
        int want = DeviceSettings.INSTANCE.cacheMaxMb;
        for (int i = 0; i < ids.length; i++) {
            TextView c = findViewById(ids[i]);
            if (c == null) continue;
            boolean on = vals[i] == want;
            c.setBackgroundResource(on ? R.drawable.bg_chip_selected : R.drawable.bg_chip_normal);
            c.setTextColor(on ? 0xFFFFFFFF : 0xFF333333);
        }
    }

    private void persistSettings() {
        try {
            DeviceSettings.INSTANCE.revision++;
            SettingsStore.save(this);
        } catch (Exception ignored) {}
    }

    private void refreshCacheStats() {
        final TextView stats = findViewById(R.id.cacheStats);
        if (stats == null) return;
        try {
            com.hongguotv.phone.core.PlaintextCache.Stats s = com.hongguotv.phone.core.PlaintextCache.stats();
            int files = s.getFiles();
            double mb = s.mb();
            String text = String.format(java.util.Locale.US,
                    "当前缓存：%d 个文件 · %.1f MB（上限 %d MB / 预取 %d 集）",
                    files, mb, DeviceSettings.INSTANCE.cacheMaxMb,
                    DeviceSettings.INSTANCE.prefetchEpisodes);
            stats.setText(text);
        } catch (Throwable e) {
            stats.setText("当前缓存：0 个文件 · 0.0 MB（初始化后刷新）");
            AppLog.w("refreshCacheStats: " + e.getMessage());
        }
    }

}
