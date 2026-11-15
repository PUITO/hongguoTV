package com.hongguotv.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

public class LogActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        logView = findViewById(R.id.logText);
        logView.setMovementMethod(new ScrollingMovementMethod());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> render());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            AppLog.clear();
            render();
        });
        findViewById(R.id.btnExport).setOnClickListener(v -> exportShare());
        AppLog.i("打开日志界面");
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void render() {
        List<String> lines = AppLog.snapshot();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - 400);
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        if (sb.length() == 0) sb.append("（暂无日志，去片源点播放后再回来）\n");
        logView.setText(sb.toString());
        logView.post(() -> {
            try {
                int scroll = logView.getLayout() == null ? 0
                        : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
                if (scroll > 0) logView.scrollTo(0, scroll);
            } catch (Exception ignored) {}
        });
    }

    private void exportShare() {
        try {
            String dump = AppLog.dump();
            File f = AppLog.writeExportFile(this);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "hongguoTV 日志");
            share.putExtra(Intent.EXTRA_TEXT, dump);
            try {
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", f);
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
            startActivity(Intent.createChooser(share, "导出日志"));
            AppLog.i("已导出日志 bytes=" + dump.length());
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
