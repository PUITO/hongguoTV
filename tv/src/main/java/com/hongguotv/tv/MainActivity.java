package com.hongguotv.tv;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import android.app.Activity;

import com.hongguotv.shared.CastProtocol;
import com.hongguotv.shared.QrBitmaps;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView message;
    private TextView sessionText;
    private ImageView qrImage;
    private String session;
    private TvBindServer bindServer;
    private final Runnable pollBound = new Runnable() {
        @Override public void run() {
            if (TvBindState.INSTANCE.bound) {
                message.setText("已绑定手机 " + TvBindState.INSTANCE.phoneIp + "，正在拉取片源…");
                openPlayer(TvBindState.INSTANCE.phoneIp, TvBindState.INSTANCE.phonePort, TvBindState.INSTANCE.pair);
                return;
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        message = findViewById(R.id.message);
        sessionText = findViewById(R.id.sessionText);
        qrImage = findViewById(R.id.qrImage);
        EditText ipInput = findViewById(R.id.ipInput);
        EditText pairInput = findViewById(R.id.pairInput);
        Button btn = findViewById(R.id.btnConnect);

        session = String.format(Locale.US, "%06d", new Random().nextInt(1_000_000));
        TvBindState.INSTANCE.session = session;
        String tvIp = localIp();
        String payload = CastProtocol.tvQrPayload(tvIp, CastProtocol.TV_BIND_PORT, session);
        Bitmap qr = QrBitmaps.encode(payload, 512);
        qrImage.setImageBitmap(qr);
        sessionText.setText("本机 " + tvIp + "\n会话 " + session);

        try {
            bindServer = new TvBindServer(CastProtocol.TV_BIND_PORT, session, () -> ui.post(pollBound));
            bindServer.start();
            message.setText("等待手机扫码绑定…");
            ui.post(pollBound);
        } catch (Exception e) {
            message.setText("绑定服务启动失败: " + e.getMessage() + "（仍可手动输入）");
        }

        btn.setOnClickListener(v -> {
            String ip = text(ipInput);
            String pair = text(pairInput);
            if (ip.isEmpty() || pair.length() != 4) {
                message.setText("手动连接请填写手机 IP 与 4 位配对码");
                return;
            }
            message.setText("连接中…");
            openPlayer(ip, CastProtocol.DEFAULT_PORT, pair);
        });
    }

    private void openPlayer(String ip, int port, String pair) {
        net.execute(() -> {
            try {
                String nowUrl = "http://" + ip + ":" + port + CastProtocol.PATH_NOW;
                JSONObject now = httpGet(nowUrl, pair);
                String play = now.optString("url", "");
                String title = now.optString("title", "");
                ui.post(() -> {
                    Intent i = new Intent(this, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_URL, play);
                    i.putExtra(PlayerActivity.EXTRA_TITLE, title);
                    i.putExtra(PlayerActivity.EXTRA_PHONE_IP, ip);
                    i.putExtra(PlayerActivity.EXTRA_PHONE_PORT, port);
                    i.putExtra(PlayerActivity.EXTRA_PAIR, pair);
                    startActivity(i);
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ui.post(() -> message.setText("连接失败: " + msg));
            }
        });
    }

    private static JSONObject httpGet(String urlSpec, String pair) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlSpec).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod("GET");
        if (pair != null) c.setRequestProperty(CastProtocol.HEADER_PAIR, pair);
        int code = c.getResponseCode();
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) throw new IllegalStateException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + sb);
        return new JSONObject(sb.toString());
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
        ui.removeCallbacks(pollBound);
        if (bindServer != null) bindServer.stop();
        net.shutdownNow();
        super.onDestroy();
    }
}
