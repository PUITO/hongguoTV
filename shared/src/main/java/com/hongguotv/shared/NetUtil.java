package com.hongguotv.shared;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** 获取局域网 IPv4（电视/手机均可用，不依赖 WifiManager）。 */
public final class NetUtil {
    private NetUtil() {}

    public static String localIpv4() {
        List<String> all = allIpv4();
        for (String ip : all) {
            if (ip.startsWith("192.168.")) return ip;
        }
        for (String ip : all) {
            if (ip.startsWith("10.")) return ip;
        }
        for (String ip : all) {
            if (ip.startsWith("172.")) {
                try {
                    int second = Integer.parseInt(ip.split("\\.")[1]);
                    if (second >= 16 && second <= 31) return ip;
                } catch (Exception ignored) {}
            }
        }
        return all.isEmpty() ? "" : all.get(0);
    }

    public static List<String> allIpv4() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return list;
            for (NetworkInterface ni : Collections.list(en)) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    if (addr.isLoopbackAddress()) continue;
                    String host = addr.getHostAddress();
                    if (host == null || host.isEmpty()) continue;
                    if (!list.contains(host)) list.add(host);
                }
            }
        } catch (Exception ignored) {}
        return list;
    }
}
