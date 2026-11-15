package com.hongguotv.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.hongguotv.shared.CastProtocol;
import com.hongguotv.shared.NetUtil;
import com.hongguotv.shared.QrBitmaps;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView message, sessionText, ipInfo, hint;
    private ImageView qrImage;
    private String session;
    private String tvIp = "";
    private TvBindServer bindServer;
    private final Runnable pollBound = new Runnable() {
        @Override public void run() {
            if (TvBindState.INSTANCE.bound) {
                message.setText("已绑定手机 " + TvBindState.INSTANCE.phoneIp + "，正在进入播放…");
                openPlayer(TvBindState.INSTANCE.phoneIp, TvBindState.INSTANCE.phonePort, TvBindState.INSTANCE.pair);
                return;
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TvBindState.INSTANCE.load(this);
        if (TvBindState.INSTANCE.bound) {
            // 已绑定则直接进播放器，避免每次都要扫码
            ui.post(() -> openPlayer(TvBindState.INSTANCE.phoneIp, TvBindState.INSTANCE.phonePort, TvBindState.INSTANCE.pair));
        }
        message = findViewById(R.id.message);
        sessionText = findViewById(R.id.sessionText);
        ipInfo = findViewById(R.id.ipInfo);
        hint = findViewById(R.id.hint);
        qrImage = findViewById(R.id.qrImage);
        EditText ipInput = findViewById(R.id.ipInput);
        EditText pairInput = findViewById(R.id.pairInput);
        Button btn = findViewById(R.id.btnConnect);
        Button btnRefresh = findViewById(R.id.btnRefreshIp);

        session = String.format(Locale.US, "%06d", new Random().nextInt(1_000_000));
        TvBindState.INSTANCE.session = session;

        refreshIpAndQr();
        btnRefresh.setOnClickListener(v -> {
            refreshIpAndQr();
            message.setText("已重新检测本机 IP");
        });

        try {
            bindServer = new TvBindServer(CastProtocol.TV_BIND_PORT, session, () -> ui.post(pollBound));
            bindServer.start();
            message.setText("等待手机扫码绑定（TV 仅接收推送）…");
            ui.post(pollBound);
        } catch (Exception e) {
            message.setText("绑定服务启动失败: " + e.getMessage() + "\n请用下方手动连接");
        }

        btn.setOnClickListener(v -> {
            String ip = text(ipInput);
            String pair = text(pairInput);
            if (ip.isEmpty() || ip.startsWith("0.")) {
                message.setText("请填写手机的局域网 IP（不是电视 IP）");
                return;
            }
            if (pair.length() != 4) {
                message.setText("请填写手机上的 4 位配对码");
                return;
            }
            message.setText("正在连接手机 " + ip + " …");
            openPlayer(ip, CastProtocol.DEFAULT_PORT, pair);
        });
    }

    private void refreshIpAndQr() {
        tvIp = NetUtil.localIpv4();
        List<String> all = NetUtil.allIpv4();
        if (tvIp == null || tvIp.isEmpty()) {
            ipInfo.setText("本机 IP：未检测到\n请确认电视已连 Wi‑Fi/有线，点「重新检测 IP」");
            hint.setText("IP 无效时二维码无法绑定。可先在手机查看 IP，用左侧手动连接。");
            sessionText.setText("会话 " + session + "\nIP 无效，请刷新或手动连接");
            qrImage.setImageBitmap(null);
            return;
        }
        String extra = all.size() > 1 ? ("\n其他地址: " + all) : "";
        ipInfo.setText("本机 IP：" + tvIp + extra);
        String payload = CastProtocol.tvQrPayload(tvIp, CastProtocol.TV_BIND_PORT, session);
        Bitmap qr = QrBitmaps.encode(payload, 512);
        qrImage.setImageBitmap(qr);
        sessionText.setText("会话 " + session + "\n端口 " + CastProtocol.TV_BIND_PORT);
        hint.setText("用手机「红果推流」扫描右侧二维码完成绑定。\n绑定后由手机统一控制播放与设置。");
    }

    private void openPlayer(String ip, int port, String pair) {
        if (ip == null || ip.isEmpty()) {
            message.setText("手机 IP 为空");
            return;
        }
        message.setText("连接手机 " + ip + " …");
        net.execute(() -> {
            String play = "";
            String title = "等待推送";
            try {
                String nowUrl = "http://" + ip + ":" + port + CastProtocol.PATH_NOW;
                JSONObject now = httpGet(nowUrl, pair);
                play = now.optString("playUrl", now.optString("sourceUrl", ""));
                title = now.optString("title", "等待推送");
            } catch (Exception e) {
                // 无当前片源也进播放器，等手机推送
            }
            final String fPlay = play;
            final String fTitle = title;
            ui.post(() -> {
                try {
                    TvBindState.INSTANCE.phoneIp = ip;
                    TvBindState.INSTANCE.phonePort = port;
                    TvBindState.INSTANCE.pair = pair;
                    TvBindState.INSTANCE.bound = true;
                    TvBindState.INSTANCE.save(MainActivity.this);
                    Intent i = new Intent(this, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_PHONE_IP, ip);
                    i.putExtra(PlayerActivity.EXTRA_PHONE_PORT, port);
                    i.putExtra(PlayerActivity.EXTRA_PAIR, pair);
                    i.putExtra(PlayerActivity.EXTRA_URL, fPlay);
                    i.putExtra(PlayerActivity.EXTRA_TITLE, fTitle);
                    startActivity(i);
                } catch (Exception e) {
                    message.setText("打开播放器失败: " + e.getMessage());
                }
            });
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
