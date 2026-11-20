package com.hongguotv.tv;

import androidx.multidex.MultiDexApplication;

public class App extends MultiDexApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        TvLog.i("App onCreate");
    }
}
