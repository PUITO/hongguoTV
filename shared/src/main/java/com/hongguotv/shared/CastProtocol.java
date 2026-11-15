package com.hongguotv.shared;

public final class CastProtocol {
    public static final int DEFAULT_PORT = 8765;
    public static final int TV_BIND_PORT = 8766;
    public static final String PATH_STATUS = "/api/status";
    public static final String PATH_NOW = "/api/now";
    public static final String PATH_CONTROL = "/api/control";
    public static final String PATH_SETTINGS = "/api/settings";
    public static final String PATH_STREAM = "/stream";
    public static final String PATH_STREAM_CURRENT = "/stream/current";
    public static final String PATH_CACHE_STATUS = "/api/cache";
    public static final String PATH_SEARCH = "/api/search";
    public static final String PATH_DETAIL = "/api/detail";
    public static final String PATH_REGISTER = "/api/register";
    public static final String HEADER_PAIR = "X-Hongguo-Pair";
    public static final String QR_PREFIX_PHONE = "HGTV1";
    public static final String QR_PREFIX_TV = "HGTV2";

    /** 电视直链播放 CDN 地址 */
    public static final String MODE_DIRECT = "direct";
    /** 手机缓存推流，电视播局域网地址 */
    public static final String MODE_PROXY = "proxy";

    private CastProtocol() {}

    public static String phoneQrPayload(String ip, int port, String pair) {
        return QR_PREFIX_PHONE + "|" + ip + "|" + port + "|" + pair;
    }

    public static String tvQrPayload(String tvIp, int bindPort, String session) {
        return QR_PREFIX_TV + "|" + tvIp + "|" + bindPort + "|" + session;
    }
}
