package com.hongguotv.phone;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImageLoader {
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private ImageLoader() {}

    public static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        view.setTag(url);
        pool.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (InputStream in = c.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    ui.post(() -> {
                        if (url.equals(view.getTag()) && bmp != null) view.setImageBitmap(bmp);
                    });
                }
            } catch (Exception e) {
                ui.post(() -> {
                    if (url.equals(view.getTag())) view.setImageDrawable(null);
                });
            }
        });
    }
}
