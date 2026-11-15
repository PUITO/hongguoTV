package com.hongguotv.phone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
    private TextView status, pairCodeView, searchResult, cacheStatus;
    private ImageView qrImage;
    private EditText urlInput, titleInput, searchInput, seriesInput, epInput;
    private SwitchCompat switchAutoNext, switchProxy;
    private String pairCode = "0000";
    private boolean running;
    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable cacheTick = new Runnable() {
        @Override public void run() {
            refreshCacheStatus();
            ui.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
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
        switchProxy = findViewById(R.id.switchProxy);

        pairCode = String.format(Locale.US, "%04d", new Random().nextInt(10000));
        pairCodeView.setText("配对码 " + pairCode);
        refreshQr();

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
        switchAutoNext.setOnCheckedChangeListener((b, c) -> {
            try { CastServerService.NOW.control("autonext", new JSONObject().put("value", c)); } catch (Exception ignored) {}
        });
        switchProxy.setOnCheckedChangeListener((b, c) -> {
            try { CastServerService.NOW.control("proxy", new JSONObject().put("value", c)); } catch (Exception ignored) {}
            refreshStatus();
        });
        refreshStatus();
        ui.post(cacheTick);
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
                    sb.append(o.optString("seriesId")).append("  ")
                            .append(o.optString("title")).append("\n");
                }
                if (sb.length() == 0) sb.append("无结果（站点结构可能变化，可手动填系列ID）");
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
        searchResult.setText("解析播放地址…");
        net.execute(() -> {
            try {
                JSONObject play = HongguoClient.resolvePlay(sid, vid);
                String url = play.optString("url");
                String title = play.optString("title");
                // build small playlist for auto-next attempts
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
                boolean proxy = switchProxy.isChecked();
                CastServerService.NOW.setPlaylist(pl, 0, proxy);
                if (CastServerService.CACHE != null) {
                    CastServerService.CACHE.setCurrent(url);
                    if (pl.length() > 1) {
                        String next = pl.getJSONObject(1).optString("url");
                        if (!next.isEmpty()) CastServerService.CACHE.prefetch(next);
                    }
                }
                ui.post(() -> {
                    searchResult.setText("已推送: " + title + "\n" + url);
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
        boolean proxy = switchProxy.isChecked();
        CastServerService.NOW.set(url, title, proxy);
        if (proxy && CastServerService.CACHE != null) CastServerService.CACHE.setCurrent(url);
        toast("已推送");
        refreshStatus();
    }

    private void speed(float s) {
        try { CastServerService.NOW.control("speed", new JSONObject().put("value", s)); toast("倍速 " + s); } catch (Exception ignored) {}
    }

    private void control(String action) {
        try {
            CastServerService.NOW.control(action, new JSONObject());
            if (("next".equals(action) || "prev".equals(action)) && CastServerService.CACHE != null) {
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
        String now = CastServerService.NOW.sourceUrl;
        status.setText("IP " + localIp() + " · 端口 " + CastProtocol.DEFAULT_PORT
                + " · " + (running ? "运行中" : "未启动")
                + "\n倍速 " + CastServerService.NOW.speed
                + " · 代理缓存 " + (CastServerService.NOW.useProxy ? "开" : "关")
                + "\n片源 " + (now == null || now.isEmpty() ? "空" : now));
    }

    private void refreshCacheStatus() {
        MediaCache.Job job = CastServerService.CACHE == null ? null : CastServerService.CACHE.currentJob();
        if (job == null) {
            cacheStatus.setText("缓存: 无");
            return;
        }
        long w = job.written.get();
        long t = job.contentLength.get();
        String pct = t > 0 ? String.format(Locale.US, " %.0f%%", w * 100.0 / t) : "";
        cacheStatus.setText("缓存: " + (w / 1024) + "KB" + pct
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
                    .put("pair", pairCode);
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + tvIp + ":" + bindPort + CastProtocol.PATH_REGISTER).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int code = c.getResponseCode();
            ui.post(() -> toast(code >= 200 && code < 300 ? "已绑定电视 " + tvIp : "绑定失败 " + code));
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
