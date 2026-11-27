package com.hongguotv.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 日志查看：可选中复制、平滑滚动、导出文件。
 * 用户上滑阅读时不自动跳到底部；靠近底部时才跟随最新日志。
 */
public class LogActivity extends Activity {
    private static final int NEAR_BOTTOM_PX = 180;
    private static final long REFRESH_MS = 2500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private TextView logMeta;
    private ScrollView logScroll;
    private String lastRendered = "";
    private boolean userDragging;
    private boolean stickToBottom = true;
    private long pauseAutoUntil;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (System.currentTimeMillis() >= pauseAutoUntil) {
                render(false);
            }
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        logView = findViewById(R.id.logText);
        logMeta = findViewById(R.id.logMeta);
        logScroll = findViewById(R.id.logScroll);

        // 不使用 ScrollingMovementMethod，避免与外层 ScrollView 抢滚动导致抖动
        logView.setTextIsSelectable(true);
        logView.setFocusable(true);
        logView.setFocusableInTouchMode(true);

        logScroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                userDragging = true;
                pauseAutoUntil = System.currentTimeMillis() + 4000;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                userDragging = false;
                stickToBottom = isNearBottom();
                pauseAutoUntil = System.currentTimeMillis() + 2500;
            }
            return false;
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            stickToBottom = true;
            render(true);
        });
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            AppLog.clear();
            lastRendered = "";
            stickToBottom = true;
            render(true);
        });
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyAll());
        findViewById(R.id.btnExport).setOnClickListener(v -> exportShare());

        AppLog.i("打开日志界面");
        render(true);
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private boolean isNearBottom() {
        if (logScroll == null) return true;
        View child = logScroll.getChildAt(0);
        if (child == null) return true;
        int diff = child.getBottom() - (logScroll.getHeight() + logScroll.getScrollY());
        return diff <= NEAR_BOTTOM_PX;
    }

    private void render(boolean force) {
        List<String> lines = AppLog.snapshot();
        StringBuilder sb = new StringBuilder(Math.min(lines.size() * 80, 256_000));
        // 展示全部内存日志（最多 800 行），不再截断到 400 导致「导出不全」的错觉
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        if (sb.length() == 0) {
            sb.append("（暂无日志，去片源点播放后再回来）\n");
        }
        String text = sb.toString();
        if (!force && text.equals(lastRendered)) {
            if (logMeta != null) {
                logMeta.setText(lines.size() + " 行");
            }
            return;
        }

        // 选中文本时不打断
        int selStart = logView.getSelectionStart();
        int selEnd = logView.getSelectionEnd();
        boolean selecting = logView.hasSelection() && selStart >= 0 && selEnd > selStart;
        if (selecting && !force) {
            if (logMeta != null) logMeta.setText(lines.size() + " 行 · 选中中");
            return;
        }

        boolean wasNearBottom = stickToBottom || isNearBottom();
        int oldY = logScroll.getScrollY();
        lastRendered = text;
        logView.setText(text);
        if (logMeta != null) {
            logMeta.setText(lines.size() + " 行 · " + (text.length() / 1024) + "KB");
        }

        logScroll.post(() -> {
            if (userDragging) return;
            if (wasNearBottom && !selecting) {
                logScroll.fullScroll(View.FOCUS_DOWN);
            } else {
                // 保持阅读位置，避免跳到底部
                logScroll.scrollTo(0, Math.min(oldY, Math.max(0, logScroll.getChildAt(0).getHeight())));
            }
        });
    }

    private void copyAll() {
        try {
            String dump = AppLog.dump();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            // 部分系统对超长文本有限制：分段提示
            cm.setPrimaryClip(ClipData.newPlainText("hongguoTV log", dump));
            Toast.makeText(this, "已复制全部日志 (" + dump.length() + " 字符)\n可粘贴到备忘录/微信",
                    Toast.LENGTH_LONG).show();
            AppLog.i("日志已复制 chars=" + dump.length());
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void exportShare() {
        try {
            String dump = AppLog.dump();
            File f = AppLog.writeExportFile(this);
            // 额外写一份到外部 files，方便文件管理器找到
            File ext = null;
            try {
                File dir = getExternalFilesDir("logs");
                if (dir != null) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    ext = new File(dir, "hongguotv-log-latest.txt");
                    try (FileOutputStream fos = new FileOutputStream(ext)) {
                        fos.write(dump.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {}

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "hongguoTV 日志");
            // EXTRA_TEXT 过长会失败：只放摘要，完整内容走文件
            String preview = dump.length() > 3000
                    ? dump.substring(0, 3000) + "\n…(全文见附件文件，共 " + dump.length() + " 字符)"
                    : dump;
            share.putExtra(Intent.EXTRA_TEXT, preview);
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable t) {
                AppLog.w("FileProvider: " + t.getMessage());
            }
            startActivity(Intent.createChooser(share, "导出日志文件"));
            String tip = "已导出 " + f.getName() + " (" + dump.length() + " 字符)";
            if (ext != null) tip += "\n路径: " + ext.getAbsolutePath();
            Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
            AppLog.i("已导出日志 bytes=" + dump.length());
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
