package com.hongguotv.tv;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

public class LogActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTextSize(14f);
        tv.setPadding(24, 24, 24, 24);
        tv.setText(TvLog.dump().isEmpty() ? "（暂无日志）" : TvLog.dump());
        scroll.addView(tv);
        setContentView(scroll);
    }
}
