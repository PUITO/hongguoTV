package com.hongguotv.shared;

/** 局域网手机推流协议（JSON over HTTP）。 */
public final class CastProtocol {
    public static final int DEFAULT_PORT = 8765;
    public static final String PATH_STATUS = "/api/status";
    public static final String PATH_NOW = "/api/now";
    public static final String PATH_STREAM = "/stream";
    public static final String PATH_PAIR = "/api/pair";
    public static final String HEADER_PAIR = "X-Hongguo-Pair";

    private CastProtocol() {}
}
