package com.hongguotv.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.hongguotv.shared.CastProtocol;
import com.hongguotv.shared.NetUtil;

public class CastServerService extends Service {
    public static final String EXTRA_PAIR = "pair";
    public static final NowPlaying NOW = new NowPlaying();
    public static MediaCache CACHE;
    public static CastServerService INSTANCE;
    private CastHttpServer server;
    public CastHttpServer getServer() { return server; }

    @Override public void onCreate() {
        super.onCreate();
        NOW.attachContext(this);
        INSTANCE = this;
        CACHE = new MediaCache(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String pair = intent != null ? intent.getStringExtra(EXTRA_PAIR) : "0000";
        if (pair == null || pair.length() != 4) pair = "0000";
        startForeground(1, buildNotification());
        try {
            if (server != null) server.stop();
            server = new CastHttpServer(CastProtocol.DEFAULT_PORT, pair, NOW, CACHE, localIp());
            server.start();
        } catch (Exception e) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private String localIp() {
        String ip = NetUtil.localIpv4();
        return ip == null || ip.isEmpty() ? "127.0.0.1" : ip;
    }

    private Notification buildNotification() {
        String channelId = "cast";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(channelId, "推流服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("红果推流运行中")
                .setContentText("端口 " + CastProtocol.DEFAULT_PORT + " · 缓存推流")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .build();
    }
}
