package com.hongguotv.phone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.hongguotv.shared.CastProtocol;
import com.hongguotv.shared.QrBitmaps;

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
    private TextView status, bindStatus, pairCodeView, searchResult, cacheStatus;
    private ImageView qrImage;
    private EditText urlInput, titleInput, searchInput, seriesInput, epInput;
    private SwitchCompat switchAutoNext, switchOverlay, switchKeepOn, switchTvLocal;
    private RadioGroup playModeGroup;
    private String pairCode = "0000";
    private boolean running;
    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable cacheTick = new Runnable() {
        @Override public void run() {
            refreshCacheStatus();
            refreshBindStatus();
            ui.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        bindStatus = findViewById(R.id.bindStatus);
        pairCodeView = findViewById(R.id.pairCode);
        qrImage = findViewById(R.id.qrImage);
        urlInput = findViewById(R.id.urlInput);
        titleInput = findViewById(R.id.titleInput);
        searchInput = findViewById(R.id.searchInput);
        seriesInput = findViewById(R.id.seriesInput);
        epInput = findViewById(R.id.epInput);
        searchResult = findViewById(R.id.searchResult);
        cacheStatus = findViewById(R.id.cacheStatus);
        switchAutoNext = findViewById(R.id.switchAutoNext);
        switchOverlay = findViewById(R.id.switchOverlay);
        switchKeepOn = findViewById(R.id.switchKeepOn);
        switchTvLocal = findViewById(R.id.switchTvLocal);
        playModeGroup = findViewById(R.id.playModeGroup);

        pairCode = String.format(Locale.US, "%04d", new Random().nextInt(10000));
        pairCodeView.setText("配对码 " + pairCode);
        refreshQr();
        syncUiFromSettings();

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
        findViewById(R.id.btnSp075).setOnClickListener(v -> speed(0.75f));
        findViewById(R.id.btnSp10).setOnClickListener(v -> speed(1.0f));
        findViewById(R.id.btnSp125).setOnClickListener(v -> speed(1.25f));
        findViewById(R.id.btnSp15).setOnClickListener(v -> speed(1.5f));
        findViewById(R.id.btnSp20).setOnClickListener(v -> speed(2.0f));
        findViewById(R.id.btnDef075).setOnClickListener(v -> setDefaultSpeed(0.75f));
        findViewById(R.id.btnDef10).setOnClickListener(v -> setDefaultSpeed(1.0f));
        findViewById(R.id.btnDef125).setOnClickListener(v -> setDefaultSpeed(1.25f));
        findViewById(R.id.btnDef15).setOnClickListener(v -> setDefaultSpeed(1.5f));
        findViewById(R.id.btnDef20).setOnClickListener(v -> setDefaultSpeed(2.0f));

        playModeGroup.setOnCheckedChangeListener((g, id) -> {
            String mode = (id == R.id.modeDirect) ? CastProtocol.MODE_DIRECT : CastProtocol.MODE_PROXY;
            applySetting(new JSONObject() {{ try { put("playMode", mode); } catch (Exception ignored) {} }});
            toast(CastProtocol.MODE_DIRECT.equals(mode) ? "已切换：电视直链" : "已切换：手机推流");
            refreshStatus();
        });
        switchAutoNext.setOnCheckedChangeListener((b, c) -> applySetting(json("autoNext", c)));
        switchOverlay.setOnCheckedChangeListener((b, c) -> applySetting(json("showTvOverlay", c)));
        switchKeepOn.setOnCheckedChangeListener((b, c) -> applySetting(json("tvKeepScreenOn", c)));
        switchTvLocal.setOnCheckedChangeListener((b, c) -> applySetting(json("allowTvLocalControl", c)));

        refreshStatus();
        ui.post(cacheTick);
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
        // bump now so TV picks up settings revision via poll
        CastServerService.NOW.commandId++;
        if (o.has("playMode") && CastServerService.CACHE != null && DeviceSettings.INSTANCE.isProxy()) {
            String u = CastServerService.NOW.sourceUrl;
            if (u != null && !u.isEmpty()) CastServerService.CACHE.setCurrent(u);
        }
        refreshStatus();
    }

    private JSONObject json(String k, boolean v) {
        try { return new JSONObject().put(k, v); } catch (Exception e) { return new JSONObject(); }
    }

    private void setDefaultSpeed(float s) {
        try {
            applySetting(new JSONObject().put("defaultSpeed", s));
            speed(s);
            toast("默认倍速 " + s);
        } catch (Exception ignored) {}
    }

    private void doSearch() {
        String kw = text(searchInput);
        if (kw.isEmpty()) { toast("输入关键词"); return; }
        searchResult.setText("搜索中…");
        net.execute(() -> {
            try {
                JSONArray items = HongguoClient.search(kw);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    sb.append(o.optString("seriesId")).append("  ").append(o.optString("title")).append("\n");
                }
                if (sb.length() == 0) sb.append("无结果，可手动填系列ID");
                ui.post(() -> searchResult.setText(sb.toString()));
            } catch (Exception e) {
                ui.post(() -> searchResult.setText("搜索失败: " + e.getMessage()));
            }
        });
    }

    private void resolveAndPush() {
        String sid = text(seriesInput);
        String ep = text(epInput);
        if (sid.isEmpty()) { toast("填写系列ID"); return; }
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
                    refreshStatus();
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
        refreshStatus();
    }

    private void speed(float s) {
        try {
            CastServerService.NOW.control("speed", new JSONObject().put("value", s));
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
            refreshStatus();
        } catch (Exception ignored) {}
    }

    private void startServer() {
        Intent i = new Intent(this, CastServerService.class);
        i.putExtra(CastServerService.EXTRA_PAIR, pairCode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        running = true;
        refreshQr();
        refreshStatus();
        toast("服务已启动");
    }

    private void stopServer() {
        stopService(new Intent(this, CastServerService.class));
        running = false;
        refreshStatus();
    }

    private void refreshQr() {
        qrImage.setImageBitmap(QrBitmaps.encode(
                CastProtocol.phoneQrPayload(localIp(), CastProtocol.DEFAULT_PORT, pairCode), 512));
    }

    private void refreshStatus() {
        DeviceSettings s = DeviceSettings.INSTANCE;
        String mode = s.isProxy() ? "推流" : "直链";
        status.setText("IP " + localIp() + " · " + (running ? "服务开" : "服务关")
                + " · 模式 " + mode
                + "\n倍速 " + CastServerService.NOW.speed + " · 默认 " + s.defaultSpeed
                + "\n片源 " + (CastServerService.NOW.sourceUrl.isEmpty() ? "空" : CastServerService.NOW.sourceUrl));
    }

    private void refreshBindStatus() {
        DeviceSettings s = DeviceSettings.INSTANCE;
        if (s.bound) {
            bindStatus.setText("已绑定电视 " + s.boundTvIp + " · 设置由手机统一管理");
        } else {
            bindStatus.setText("未绑定电视 · 请扫电视二维码");
        }
    }

    private void refreshCacheStatus() {
        if (!DeviceSettings.INSTANCE.isProxy()) {
            cacheStatus.setText("直链模式：电视直接访问源站（不走手机缓存）");
            return;
        }
        MediaCache.Job job = CastServerService.CACHE == null ? null : CastServerService.CACHE.currentJob();
        if (job == null) {
            cacheStatus.setText("推流缓存: 无");
            return;
        }
        long w = job.written.get();
        long t = job.contentLength.get();
        String pct = t > 0 ? String.format(Locale.US, " %.0f%%", w * 100.0 / t) : "";
        cacheStatus.setText("推流缓存: " + (w / 1024) + "KB" + pct
                + (job.done.get() ? " 完成" : job.running.get() ? " 下载中" : job.failed.get() ? " 失败" : ""));
    }

    private void startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("对准电视二维码");
        integrator.setBeepEnabled(false);
        integrator.initiateScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startScan();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) handleScan(result.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleScan(String raw) {
        String[] p = raw.trim().split("\\|");
        if (p.length >= 4 && CastProtocol.QR_PREFIX_TV.equals(p[0])) {
            if (!running) startServer();
            int bindPort = CastProtocol.TV_BIND_PORT;
            try { bindPort = Integer.parseInt(p[2]); } catch (Exception ignored) {}
            final int port = bindPort;
            net.execute(() -> registerToTv(p[1], port, p[3]));
            return;
        }
        toast("无法识别的二维码");
    }

    private void registerToTv(String tvIp, int bindPort, String session) {
        try {
            JSONObject body = new JSONObject()
                    .put("session", session)
                    .put("phoneIp", localIp())
                    .put("port", CastProtocol.DEFAULT_PORT)
                    .put("pair", pairCode)
                    .put("settings", DeviceSettings.INSTANCE.toJson());
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + tvIp + ":" + bindPort + CastProtocol.PATH_REGISTER).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                DeviceSettings.INSTANCE.setBound(tvIp, bindPort, session);
            }
            ui.post(() -> {
                toast(code >= 200 && code < 300 ? "已绑定，设置由手机管理" : "绑定失败 " + code);
                refreshBindStatus();
            });
        } catch (Exception e) {
            ui.post(() -> toast("绑定失败: " + e.getMessage()));
        }
    }

    @SuppressWarnings("deprecation")
    private String localIp() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) return Formatter.formatIpAddress(ip);
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private static String text(EditText e) { return e.getText() == null ? "" : e.getText().toString().trim(); }

    @Override protected void onDestroy() {
        ui.removeCallbacks(cacheTick);
        net.shutdownNow();
        super.onDestroy();
    }
}
