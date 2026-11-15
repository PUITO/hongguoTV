package com.hongguotv.tv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import android.app.Activity;

import com.hongguotv.shared.CastProtocol;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView message;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EditText ipInput = findViewById(R.id.ipInput);
        EditText pairInput = findViewById(R.id.pairInput);
        Button btn = findViewById(R.id.btnConnect);
        message = findViewById(R.id.message);
        btn.requestFocus();
        btn.setOnClickListener(v -> {
            String ip = text(ipInput);
            String pair = text(pairInput);
            if (ip.isEmpty() || pair.length() != 4) {
                message.setText("请填写手机 IP 与 4 位配对码");
                return;
            }
            message.setText("连接中…");
            net.execute(() -> connect(ip, pair));
        });
    }

    private void connect(String ip, String pair) {
        try {
            String statusUrl = "http://" + ip + ":" + CastProtocol.DEFAULT_PORT + CastProtocol.PATH_STATUS;
            JSONObject status = httpGet(statusUrl, null);
            if (!status.optBoolean("ok", false)) throw new IllegalStateException("手机服务无响应");

            String nowUrl = "http://" + ip + ":" + CastProtocol.DEFAULT_PORT + CastProtocol.PATH_NOW;
            JSONObject now = httpGet(nowUrl, pair);
            String play = now.optString("url", "");
            String title = now.optString("title", "");
            if (play.isEmpty()) {
                ui.post(() -> message.setText("已连接，但手机尚未设置片源。请在手机点「推送到电视」。"));
                return;
            }
            ui.post(() -> {
                Intent i = new Intent(this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URL, play);
                i.putExtra(PlayerActivity.EXTRA_TITLE, title);
                i.putExtra(PlayerActivity.EXTRA_PHONE_IP, ip);
                i.putExtra(PlayerActivity.EXTRA_PAIR, pair);
                startActivity(i);
            });
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ui.post(() -> message.setText("连接失败: " + msg));
        }
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

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    @Override protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
