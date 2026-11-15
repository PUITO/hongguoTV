package com.hongguotv.tv;

/** 电视绑定到的手机端信息。 */
public final class TvBindState {
    public static final TvBindState INSTANCE = new TvBindState();
    public volatile String session = "";
    public volatile String phoneIp = "";
    public volatile int phonePort = 8765;
    public volatile String pair = "";
    public volatile boolean bound;

    private TvBindState() {}
}
