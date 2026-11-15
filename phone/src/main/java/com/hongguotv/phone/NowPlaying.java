package com.hongguotv.phone;

/** 当前推给电视的片源。 */
public final class NowPlaying {
    public volatile String url = "";
    public volatile String title = "";
    public volatile long updatedAt;

    public synchronized void set(String url, String title) {
        this.url = url == null ? "" : url.trim();
        this.title = title == null ? "" : title.trim();
        this.updatedAt = System.currentTimeMillis();
    }
}
