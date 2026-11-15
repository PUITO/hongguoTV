package com.hongguotv.tv;

import android.net.Uri;
import android.os.Bundle;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import android.app.Activity;

public class PlayerActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PHONE_IP = "phone_ip";
    public static final String EXTRA_PAIR = "pair";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        VideoView video = findViewById(R.id.video);
        TextView title = findViewById(R.id.title);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        title.setText(t == null || t.isEmpty() ? "播放中" : t);
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "无播放地址", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoURI(Uri.parse(url));
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "播放失败 what=" + what + " extra=" + extra, Toast.LENGTH_LONG).show();
            return true;
        });
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            video.start();
        });
    }
}
