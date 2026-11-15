package com.hongguotv.phone.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 匿名设备注册（GPL 协议复现，参考 N3urda/hongguoTV DeviceRegister + hongguo-drama playback-device.js）
 */
public final class DeviceRegister {
    private static final String URL =
            "https://log5-applog-lf.fqnovel.com/service/2/device_register/"
                    + "?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1";
    private static final byte[] MAGIC = new byte[]{0x74, 0x63, 0x05, 0x10, 0x00, 0x00};
    private static final byte[] KDF_SALT = hex(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");
    private static final SecureRandom RND = new SecureRandom();

    public static final class Identity {
        public final String deviceId;
        public final String installId;
        public final String cdid;

        public Identity(String deviceId, String installId, String cdid) {
            this.deviceId = deviceId;
            this.installId = installId;
            this.cdid = cdid;
        }
    }

    private static volatile Identity cached;

    public static synchronized Identity obtain() throws Exception {
        if (cached != null) return cached;
        cached = register();
        return cached;
    }

    public static synchronized void clear() { cached = null; }

    private static Identity register() throws Exception {
        String cdid = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        JSONObject header = new JSONObject()
                .put("display_name", "红果免费短剧")
                .put("update_version_code", 73932)
                .put("manifest_version_code", 73932)
                .put("app_version_minor", "7.3.9.32")
                .put("aid", 8662)
                .put("channel", "update_64")
                .put("package", "com.phoenix.read")
                .put("app_version", "7.3.9.32")
                .put("version_code", 73932)
                .put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
                .put("os", "Android")
                .put("os_version", "13")
                .put("os_api", 33)
                .put("device_model", "MI 12")
                .put("device_brand", "Xiaomi")
                .put("device_manufacturer", "Xiaomi")
                .put("cpu_abi", "arm64-v8a")
                .put("density_dpi", 440)
                .put("display_density", "mdpi")
                .put("resolution", "1080x2400")
                .put("language", "zh")
                .put("timezone", 8)
                .put("access", "wifi")
                .put("not_request_sender", 0)
                .put("carrier", "中国移动")
                .put("mcc_mnc", "46000")
                .put("rom", "MIUI")
                .put("rom_version", "V14")
                .put("cdid", cdid)
                .put("sig_hash", "aea615ab910015038f73c47e45d21466")
                .put("openudid", randomHex(16))
                .put("clientudid", UUID.randomUUID().toString())
                .put("region", "CN")
                .put("tz_name", "Asia/Shanghai")
                .put("tz_offset", 28800)
                .put("sim_serial_number", JSONObject.NULL)
                .put("oaid_may_support", true)
                .put("req_id", UUID.randomUUID().toString())
                .put("device_platform", "android")
                .put("custom", new JSONObject().put("app_session_id", UUID.randomUUID().toString()).put("is_new_user", 0));
        JSONObject bodyJson = new JSONObject()
                .put("magic_tag", "ss_app_log")
                .put("header", header)
                .put("_gen_time", now);
        byte[] plain = gzip(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
        byte[] data = ttEncrypt(plain);

        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/octet-stream;tt-data=a");
        c.setRequestProperty("log-encode-type", "gzip");
        c.setRequestProperty("x-ss-dp", "8662");
        c.setRequestProperty("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 13; zh_CN; MI 12)");
        c.setRequestProperty("x-ss-stub", md5Hex(data).toUpperCase());
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        String resp = bos.toString("UTF-8");
        if (code >= 400) throw new IllegalStateException("register HTTP " + code + " " + resp);
        JSONObject o = new JSONObject(resp);
        String deviceId = pickId(o, "device_id_str", "device_id");
        String installId = pickId(o, "install_id_str", "install_id");
        if (deviceId.isEmpty() || installId.isEmpty()) {
            throw new IllegalStateException("register invalid: " + resp.substring(0, Math.min(200, resp.length())));
        }
        return new Identity(deviceId, installId, cdid);
    }

    /** MAGIC || rnd[32] || AES-128-CBC(key=SHA512(SHA512(rnd)+salt)[0:16], iv=[16:32]) */
    static byte[] ttEncrypt(byte[] data) throws Exception {
        byte[] rnd = new byte[32];
        RND.nextBytes(rnd);
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        byte[] h1 = sha512.digest(rnd);
        sha512.reset();
        sha512.update(h1);
        sha512.update(KDF_SALT);
        byte[] h2 = sha512.digest();
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        System.arraycopy(h2, 0, key, 0, 16);
        System.arraycopy(h2, 16, iv, 0, 16);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] enc = cipher.doFinal(data);
        byte[] out = new byte[MAGIC.length + rnd.length + enc.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(rnd, 0, out, MAGIC.length, rnd.length);
        System.arraycopy(enc, 0, out, MAGIC.length + rnd.length, enc.length);
        return out;
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data);
        }
        return bos.toByteArray();
    }

    private static String pickId(JSONObject o, String primary, String fallback) {
        String a = o.optString(primary, "");
        if (a.matches("\\d{1,30}") && a.matches(".*[1-9].*")) return a;
        Object b = o.opt(fallback);
        if (b instanceof Number) {
            long v = ((Number) b).longValue();
            return v > 0 ? String.valueOf(v) : "";
        }
        if (b != null) {
            String s = String.valueOf(b);
            if (s.matches("\\d{1,30}") && s.matches(".*[1-9].*")) return s;
        }
        return "";
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        return hexLower(b);
    }

    private static String md5Hex(byte[] data) throws Exception {
        return hexLower(MessageDigest.getInstance("MD5").digest(data));
    }

    private static String hexLower(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
