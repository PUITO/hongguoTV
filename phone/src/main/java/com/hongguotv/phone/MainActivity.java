package com.hongguotv.phone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
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
    private TextView status;
    private TextView pairCodeView;
    private ImageView qrImage;
    private EditText urlInput;
    private EditText titleInput;
    private SwitchCompat switchAutoNext;
    private String pairCode = "0000";
    private boolean running;
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        pairCodeView = findViewById(R.id.pairCode);
        qrImage = findViewById(R.id.qrImage);
        urlInput = findViewById(R.id.urlInput);
        titleInput = findViewById(R.id.titleInput);
        switchAutoNext = findViewById(R.id.switchAutoNext);

        pairCode = String.format(Locale.US, "%04d", new Random().nextInt(10000));
        pairCodeView.setText("配对码 " + pairCode);
        refreshQr();

        findViewById(R.id.btnStart).setOnClickListener(v -> startServer());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopServer());
        findViewById(R.id.btnScanTv).setOnClickListener(v -> startScan());
        findViewById(R.id.btnPush).setOnClickListener(v -> pushNow());
        findViewById(R.id.btnPlay).setOnClickListener(v -> control("play", null));
        findViewById(R.id.btnPause).setOnClickListener(v -> control("pause", null));
        findViewById(R.id.btnNext).setOnClickListener(v -> control("next", null));
        findViewById(R.id.btnSp075).setOnClickListener(v -> speed(0.75f));
        findViewById(R.id.btnSp10).setOnClickListener(v -> speed(1.0f));
        findViewById(R.id.btnSp125).setOnClickListener(v -> speed(1.25f));
        findViewById(R.id.btnSp15).setOnClickListener(v -> speed(1.5f));
        findViewById(R.id.btnSp20).setOnClickListener(v -> speed(2.0f));
        switchAutoNext.setOnCheckedChangeListener((b, checked) -> {
            try {
                JSONObject body = new JSONObject().put("action", "autonext").put("value", checked);
                CastServerService.NOW.control("autonext", body);
            } catch (Exception ignored) {}
        });
        refreshStatus();
    }

    private void refreshQr() {
        String ip = localIp();
        String payload = CastProtocol.phoneQrPayload(ip, CastProtocol.DEFAULT_PORT, pairCode);
        Bitmap bmp = QrBitmaps.encode(payload, 512);
        qrImage.setImageBitmap(bmp);
    }

    private void startServer() {
        Intent i = new Intent(this, CastServerService.class);
        i.putExtra(CastServerService.EXTRA_PAIR, pairCode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        running = true;
        refreshQr();
        refreshStatus();
        Toast.makeText(this, "服务已启动，电视可扫码或手动输入", Toast.LENGTH_SHORT).show();
    }

    private void stopServer() {
        stopService(new Intent(this, CastServerService.class));
        running = false;
        refreshStatus();
    }

    private void pushNow() {
        String url = text(urlInput);
        String title = text(titleInput);
        if (url.isEmpty()) {
            Toast.makeText(this, "请填写播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        CastServerService.NOW.set(url, title);
        Toast.makeText(this, "已推送，电视将自动刷新", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void speed(float s) {
        try {
            CastServerService.NOW.control("speed", new JSONObject().put("value", s));
            Toast.makeText(this, "倍速 " + s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private void control(String action, JSONObject extra) {
        try {
            JSONObject body = extra == null ? new JSONObject() : extra;
            body.put("action", action);
            CastServerService.NOW.control(action, body);
        } catch (Exception ignored) {}
    }

    private void startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("对准电视上的二维码");
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show();
        }
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
        // HGTV2|tvIp|bindPort|session
        String[] p = raw.trim().split("\\|");
        if (p.length >= 4 && CastProtocol.QR_PREFIX_TV.equals(p[0])) {
            String tvIp = p[1];
            int bindPort = CastProtocol.TV_BIND_PORT;
            try { bindPort = Integer.parseInt(p[2]); } catch (Exception ignored) {}
            String session = p[3];
            if (!running) startServer();
            final int port = bindPort;
            net.execute(() -> registerToTv(tvIp, port, session));
            return;
        }
        // 也支持扫到手机码（自测）
        if (p.length >= 4 && CastProtocol.QR_PREFIX_PHONE.equals(p[0])) {
            Toast.makeText(this, "这是手机端二维码，请扫电视上的码", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "无法识别的二维码", Toast.LENGTH_SHORT).show();
    }

    private void registerToTv(String tvIp, int bindPort, String session) {
        try {
            JSONObject body = new JSONObject()
                    .put("session", session)
                    .put("phoneIp", localIp())
                    .put("port", CastProtocol.DEFAULT_PORT)
                    .put("pair", pairCode);
            URL url = new URL("http://" + tvIp + ":" + bindPort + CastProtocol.PATH_REGISTER);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int code = c.getResponseCode();
            runOnUiThread(() -> Toast.makeText(this,
                    code >= 200 && code < 300 ? "已绑定电视 " + tvIp : "绑定失败 HTTP " + code,
                    Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "绑定失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void refreshStatus() {
        String ip = localIp();
        String now = CastServerService.NOW.url;
        status.setText("IP: " + ip + "  端口: " + CastProtocol.DEFAULT_PORT
                + "\n服务: " + (running ? "运行中" : "未启动")
                + "\n倍速: " + CastServerService.NOW.speed
                + "  连播: " + (CastServerService.NOW.autoNext ? "开" : "关")
                + "\n片源: " + (now == null || now.isEmpty() ? "（空）" : now));
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

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    @Override protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
