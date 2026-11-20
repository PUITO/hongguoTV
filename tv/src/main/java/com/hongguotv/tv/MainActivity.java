package com.hongguotv.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TV 首页：展示绑定二维码 / 已绑定状态。
 * 不主动进入播放页；仅在轮询到手机有推送（url 非空）时被动打开 PlayerActivity。
 */
public class MainActivity extends Activity {
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean playerOpened = new AtomicBoolean(false);
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private TextView message, sessionText, ipInfo, hint, bindStatus, speedLabel;
    private ImageView qrImage;
    private Button btnConnect, btnRefresh, btnUnbind, btnEnterPlayer;
    private EditText ipInput, pairInput;
    private String session;
    private String tvIp = "";
    private TvBindServer bindServer;
    private String lastPushUrl = "";
    private long lastPushAt = 0;
    private boolean pushBaselineReady = false;

    /** 已绑定后轮询手机是否有新推送 */
    private final Runnable pollPush = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            if (!TvBindState.INSTANCE.bound) {
                ui.postDelayed(this, 1500);
                return;
            }
            if (playerOpened.get()) {
                ui.postDelayed(this, 2000);
                return;
            }
            if (polling.getAndSet(true)) {
                ui.postDelayed(this, 1500);
                return;
            }
            final String ip = TvBindState.INSTANCE.phoneIp;
            final int port = TvBindState.INSTANCE.phonePort;
            final String pair = TvBindState.INSTANCE.pair;
            net.execute(() -> {
                try {
                    JSONObject now = httpGet(
                            "http://" + ip + ":" + port + CastProtocol.PATH_NOW, pair);
                    String url = now.optString("url", "");
                    String title = now.optString("title", "");
                    long updated = now.optLong("updatedAt", 0);
                    boolean hasPush = url != null && !url.isEmpty()
                            && (url.startsWith("http://") || url.startsWith("https://"));
                    // 首次轮询只记录基线，不进播放（避免打开 App 就进推送页）
                    if (!pushBaselineReady) {
                        lastPushUrl = hasPush ? url : "";
                        lastPushAt = updated;
                        pushBaselineReady = true;
                        ui.post(() -> {
                            if (message != null && TvBindState.INSTANCE.bound) {
                                message.setText("已绑定 " + ip
                                        + "\n等待手机推送（新推送才会进入播放）");
                            }
                        });
                        return;
                    }
                    boolean isNew = hasPush && (
                            !url.equals(lastPushUrl)
                                    || (updated > 0 && updated != lastPushAt && !url.equals(lastPushUrl)));
                    // 更稳妥：以 url 变化或 updatedAt 严格变大为准
                    if (hasPush && updated > lastPushAt) isNew = true;
                    if (isNew && playerOpened.compareAndSet(false, true)) {
                        lastPushUrl = url;
                        lastPushAt = updated;
                        final String fUrl = url;
                        final String fTitle = (title == null || title.isEmpty()) ? "推送播放" : title;
                        ui.post(() -> {
                            if (message != null) {
                                message.setText("收到推送，进入播放…\n" + fTitle);
                            }
                            startPlayer(ip, port, pair, fUrl, fTitle);
                        });
                    } else {
                        ui.post(() -> {
                            if (message != null && TvBindState.INSTANCE.bound && !playerOpened.get()) {
                                message.setText("已绑定 " + ip
                                        + "\n等待手机推送（不会自动进播放页）"
                                        + "\n可点「解除绑定」重新扫码");
                            }
                        });
                    }
                } catch (Exception e) {
                    ui.post(() -> {
                        if (message != null && TvBindState.INSTANCE.bound) {
                            message.setText("已绑定 " + ip
                                    + "\n手机暂不可达：" + e.getMessage()
                                    + "\n请确认手机 App 推流服务已开启");
                        }
                    });
                } finally {
                    polling.set(false);
                    ui.postDelayed(this, 1500);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupDefaultSpeedUi();
        View logsBtn = findViewById(R.id.btnTvLogs);
        if (logsBtn != null) logsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));
        TvBindState.INSTANCE.load(this);

        message = findViewById(R.id.message);
        sessionText = findViewById(R.id.sessionText);
        ipInfo = findViewById(R.id.ipInfo);
        hint = findViewById(R.id.hint);
        qrImage = findViewById(R.id.qrImage);
        bindStatus = findViewById(R.id.bindStatus);
        ipInput = findViewById(R.id.ipInput);
        pairInput = findViewById(R.id.pairInput);
        btnConnect = findViewById(R.id.btnConnect);
        btnRefresh = findViewById(R.id.btnRefreshIp);
        btnUnbind = findViewById(R.id.btnUnbind);
        btnEnterPlayer = findViewById(R.id.btnEnterPlayer);

        session = String.format(Locale.US, "%06d", new Random().nextInt(1_000_000));
        TvBindState.INSTANCE.session = session;

        refreshIpAndQr();
        updateHomeUi();

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                refreshIpAndQr();
                if (message != null) message.setText("已重新检测本机 IP");
            });
        }

        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                String ip = text(ipInput);
                String pair = text(pairInput);
                if (ip.isEmpty() || ip.startsWith("0.")) {
                    if (message != null) message.setText("请填写手机的局域网 IP（不是电视 IP）");
                    return;
                }
                if (pair.length() != 4) {
                    if (message != null) message.setText("请填写手机上的 4 位配对码");
                    return;
                }
                TvBindState.INSTANCE.phoneIp = ip;
                TvBindState.INSTANCE.phonePort = CastProtocol.DEFAULT_PORT;
                TvBindState.INSTANCE.pair = pair;
                TvBindState.INSTANCE.bound = true;
                TvBindState.INSTANCE.save(this);
                updateHomeUi();
                if (message != null) {
                    message.setText("已绑定手机 " + ip + "\n停留在首页，收到推送后再进播放");
                }
                Toast.makeText(this, "绑定成功，等待推送", Toast.LENGTH_SHORT).show();
            });
        }

        if (btnUnbind != null) {
            btnUnbind.setOnClickListener(v -> {
                TvBindState.INSTANCE.clear(this);
                lastPushUrl = "";
                lastPushAt = 0;
                pushBaselineReady = false;
                playerOpened.set(false);
                updateHomeUi();
                refreshIpAndQr();
                if (message != null) message.setText("已解除绑定，可重新扫码或手动连接");
                Toast.makeText(this, "已解除绑定", Toast.LENGTH_SHORT).show();
            });
        }

        // 可选：手动进播放页（调试用），默认隐藏逻辑——有绑定时显示
        if (btnEnterPlayer != null) {
            btnEnterPlayer.setOnClickListener(v -> {
                if (!TvBindState.INSTANCE.bound) {
                    Toast.makeText(this, "请先绑定手机", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (playerOpened.compareAndSet(false, true)) {
                    startPlayer(
                            TvBindState.INSTANCE.phoneIp,
                            TvBindState.INSTANCE.phonePort,
                            TvBindState.INSTANCE.pair,
                            lastPushUrl,
                            "手动进入");
                }
            });
        }

        try {
            bindServer = new TvBindServer(CastProtocol.TV_BIND_PORT, session, () -> ui.post(() -> {
                // 手机扫码绑定成功
                TvBindState.INSTANCE.bound = true;
                TvBindState.INSTANCE.save(MainActivity.this);
                updateHomeUi();
                if (message != null) {
                    message.setText("手机已扫码绑定 "
                            + TvBindState.INSTANCE.phoneIp
                            + "\n停留首页，等待推送后自动进入播放");
                }
                Toast.makeText(MainActivity.this, "绑定成功", Toast.LENGTH_SHORT).show();
            }));
            bindServer.start();
        } catch (Exception e) {
            if (message != null) {
                message.setText("绑定服务启动失败: " + e.getMessage() + "\n请用下方手动连接");
            }
        }

        // 仅轮询推送，不自动进播放
        ui.post(pollPush);
    }

    private void updateHomeUi() {
        boolean bound = TvBindState.INSTANCE.bound;
        if (bindStatus != null) {
            bindStatus.setText(bound
                    ? ("状态：已绑定 " + TvBindState.INSTANCE.phoneIp
                    + ":" + TvBindState.INSTANCE.phonePort
                    + "  配对码 " + TvBindState.INSTANCE.pair)
                    : "状态：未绑定 · 请用手机扫码或手动填写");
        }
        if (btnUnbind != null) {
            btnUnbind.setVisibility(bound ? View.VISIBLE : View.GONE);
        }
        if (btnEnterPlayer != null) {
            btnEnterPlayer.setVisibility(bound ? View.VISIBLE : View.GONE);
        }
        // 已绑定时二维码仍可显示，便于换机重绑前先解绑
        if (message != null) {
            if (bound) {
                message.setText("已绑定 " + TvBindState.INSTANCE.phoneIp
                        + "\n等待手机推送（收到推送后自动进入播放）"
                        + "\n点「解除绑定」可重新扫码");
            } else {
                message.setText("等待手机扫码绑定…");
            }
        }
    }

    private void refreshIpAndQr() {
        tvIp = NetUtil.localIpv4();
        List<String> all = NetUtil.allIpv4();
        if (tvIp == null || tvIp.isEmpty()) {
            if (ipInfo != null) {
                ipInfo.setText("本机 IP：未检测到\n请确认电视已连 Wi‑Fi/有线，点「重新检测 IP」");
            }
            if (hint != null) {
                hint.setText("IP 无效时二维码无法绑定。可先在手机查看 IP，用手动连接。");
            }
            if (sessionText != null) {
                sessionText.setText("会话 " + session + "\nIP 无效，请刷新或手动连接");
            }
            if (qrImage != null) qrImage.setImageBitmap(null);
            return;
        }
        String extra = all.size() > 1 ? ("\n其他地址: " + all) : "";
        if (ipInfo != null) ipInfo.setText("本机 IP：" + tvIp + extra);
        String payload = CastProtocol.tvQrPayload(tvIp, CastProtocol.TV_BIND_PORT, session);
        Bitmap qr = QrBitmaps.encode(payload, 512);
        if (qrImage != null) qrImage.setImageBitmap(qr);
        if (sessionText != null) {
            sessionText.setText("会话 " + session + "\n端口 " + CastProtocol.TV_BIND_PORT);
        }
        if (hint != null) {
            hint.setText("用手机扫描二维码完成绑定。\n绑定后留在本页，手机推片后自动进入播放。");
        }
    }

    /** 真正启动播放页（被动推送或用户手动） */
    private void startPlayer(String ip, int port, String pair, String url, String title) {
        try {
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_PHONE_IP, ip);
            i.putExtra(PlayerActivity.EXTRA_PHONE_PORT, port);
            i.putExtra(PlayerActivity.EXTRA_PAIR, pair);
            if (url != null && !url.isEmpty()) {
                i.putExtra(PlayerActivity.EXTRA_URL, url);
            }
            i.putExtra(PlayerActivity.EXTRA_TITLE, title == null ? "" : title);
            startActivity(i);
        } catch (Exception e) {
            playerOpened.set(false);
            if (message != null) message.setText("打开播放器失败: " + e.getMessage());
        }
    }

    private static JSONObject httpGet(String urlSpec, String pair) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlSpec).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
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
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放页返回：留在首页，继续等下一次推送
        playerOpened.set(false);
        updateHomeUi();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 首页不再用确认键强制进播放，避免误触；仅返回桌面
        return super.onKeyDown(keyCode, event);
    }

    private void setupDefaultSpeedUi() {
        speedLabel = findViewById(R.id.speedLabel);
        refreshSpeedLabel();
        float[] vals = {0.75f, 1.0f, 1.5f, 2.0f, 3.0f};
        int[] ids = {R.id.btnSp075, R.id.btnSp10, R.id.btnSp15, R.id.btnSp20, R.id.btnSp30};
        for (int i = 0; i < ids.length; i++) {
            final float sp = vals[i];
            View b = findViewById(ids[i]);
            if (b != null) b.setOnClickListener(v -> {
                TvPrefs.setDefaultSpeed(this, sp);
                refreshSpeedLabel();
                TvLog.i("默认倍速设为 " + sp);
                Toast.makeText(this, "默认倍速 " + sp + "x（下次播放生效）", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void refreshSpeedLabel() {
        if (speedLabel == null) return;
        float s = TvPrefs.defaultSpeed(this);
        if (Math.abs(s - Math.round(s)) < 0.01f) {
            speedLabel.setText("当前默认：" + Math.round(s) + "x");
        } else {
            speedLabel.setText(String.format(java.util.Locale.US, "当前默认：%.2fx", s));
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(pollPush);
        if (bindServer != null) bindServer.stop();
        net.shutdownNow();
        super.onDestroy();
    }
}
