package com.hongguotv.shared;

/** 局域网手机推流协议（JSON over HTTP）。 */
public final class CastProtocol {
    public static final int DEFAULT_PORT = 8765;
    public static final int TV_BIND_PORT = 8766;
    public static final String PATH_STATUS = "/api/status";
    public static final String PATH_NOW = "/api/now";
    public static final String PATH_CONTROL = "/api/control";
    public static final String PATH_STREAM = "/stream";
    public static final String PATH_PAIR = "/api/pair";
    public static final String PATH_BIND = "/api/bind";
    public static final String PATH_REGISTER = "/api/register";
    public static final String HEADER_PAIR = "X-Hongguo-Pair";

    /** QR / 深链：HGTV1|{ip}|{port}|{pair}  手机作服务端 */
    public static final String QR_PREFIX_PHONE = "HGTV1";
    /** QR：HGTV2|{tvIp}|{bindPort}|{session}  电视展示、手机扫码 */
    public static final String QR_PREFIX_TV = "HGTV2";

    private CastProtocol() {}

    public static String phoneQrPayload(String ip, int port, String pair) {
        return QR_PREFIX_PHONE + "|" + ip + "|" + port + "|" + pair;
    }

    public static String tvQrPayload(String tvIp, int bindPort, String session) {
        return QR_PREFIX_TV + "|" + tvIp + "|" + bindPort + "|" + session;
    }
}
