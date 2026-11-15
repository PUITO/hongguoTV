package com.hongguotv.phone;

import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hongguotv.shared.CastProtocol;

import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity {
    private TextView status;
    private TextView pairCodeView;
    private EditText urlInput;
    private EditText titleInput;
    private String pairCode = "0000";
    private boolean running;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        pairCodeView = findViewById(R.id.pairCode);
        urlInput = findViewById(R.id.urlInput);
        titleInput = findViewById(R.id.titleInput);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnPush = findViewById(R.id.btnPush);
        Button btnStop = findViewById(R.id.btnStop);

        pairCode = String.format(Locale.US, "%04d", new Random().nextInt(10000));
        pairCodeView.setText("配对码 " + pairCode);

        btnStart.setOnClickListener(v -> startServer());
        btnStop.setOnClickListener(v -> stopServer());
        btnPush.setOnClickListener(v -> {
            String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
            String title = titleInput.getText() != null ? titleInput.getText().toString().trim() : "";
            if (url.isEmpty()) {
                Toast.makeText(this, "请填写播放地址", Toast.LENGTH_SHORT).show();
                return;
            }
            CastServerService.NOW.set(url, title);
            Toast.makeText(this, "已更新当前片源，电视端下拉即可", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        refreshStatus();
    }

    private void startServer() {
        Intent i = new Intent(this, CastServerService.class);
        i.putExtra(CastServerService.EXTRA_PAIR, pairCode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        running = true;
        refreshStatus();
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
    }

    private void stopServer() {
        stopService(new Intent(this, CastServerService.class));
        running = false;
        refreshStatus();
    }

    private void refreshStatus() {
        String ip = localIp();
        String now = CastServerService.NOW.url;
        status.setText("IP: " + ip + "\n端口: " + CastProtocol.DEFAULT_PORT
                + "\n服务: " + (running ? "运行中" : "未启动")
                + "\n当前片源: " + (now == null || now.isEmpty() ? "（空）" : now));
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
        return "未知（请在系统设置中查看 WLAN IP）";
    }
}
