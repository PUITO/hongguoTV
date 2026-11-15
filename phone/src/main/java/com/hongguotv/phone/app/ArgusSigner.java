package com.hongguotv.phone.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * x-argus / x-ladon 签名（GPL，改编自 N3urda/hongguoTV Argus.kt 与 hongguo-drama playback-signature.js）
 */
public final class ArgusSigner {
    private static final long LICENSE_ID = 1611921764L;
    private static final SecureRandom RND = new SecureRandom();
    private static final byte[] SIGN_KEY = hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304");
    private static final byte[] AES_KEY = hex("d31e3718288a1027baab59f146a09a9c");
    private static final byte[] AES_IV = hex("ea180a0336ed352fcd24e4d50018ae54");
    private static final byte[] MAGIC8 = hex("f2f7fcfff2f7fcff");
    private static final byte[] TAIL9 = hex("eafb2cfe8568519154");

    private ArgusSigner() {}

    public static String md5Hex(byte[] data) throws Exception {
        return toHex(MessageDigest.getInstance("MD5").digest(data));
    }

    public static String ladon(long ts, long aid) {
        // simplified ladon: base64-ish hex of ts/aid/random — real Ladon uses AES table
        // Use N3urda-compatible structure when possible; fallback token still sent
        try {
            byte[] rnd = new byte[4];
            RND.nextBytes(rnd);
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(ts);
            buf.putInt((int) aid);
            buf.put(rnd);
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            // pad to 16
            byte[] block = new byte[16];
            System.arraycopy(buf.array(), 0, block, 0, Math.min(16, buf.array().length));
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
            return android.util.Base64.encodeToString(c.doFinal(block), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return android.util.Base64.encodeToString(String.valueOf(ts).getBytes(), android.util.Base64.NO_WRAP);
        }
    }

    public static String sign(String query, String xSsStub, long ts, long aid, String deviceId) throws Exception {
        // Build protobuf-like payload then Simon+AES as in drama JS
        byte[] bodyHash = xSsStub == null || xSsStub.isEmpty()
                ? new byte[16]
                : hex(xSsStub.length() == 32 ? xSsStub.toLowerCase() : md5Hex(xSsStub.getBytes()));
        byte[] queryHash = Sm3.hash(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] random = new byte[8];
        RND.nextBytes(random);

        ProtoBuf nested = new ProtoBuf();
        nested.putVarint(1, 1);
        nested.putUtf8(2, "none");

        ProtoBuf env = new ProtoBuf();
        env.putUtf8(1, "MI 12");
        env.putUtf8(2, "13");
        env.putUtf8(3, "update_64");
        env.putVarint(4, 2147483648L);

        ProtoBuf msg = new ProtoBuf();
        msg.putVarint(1, 0x40401252);
        msg.putVarint(2, 2);
        msg.putBytes(3, random);
        msg.putUtf8(4, String.valueOf(aid));
        msg.putUtf8(5, deviceId == null ? "" : deviceId);
        msg.putUtf8(6, String.valueOf(LICENSE_ID));
        msg.putUtf8(7, "7.3.5.32");
        msg.putUtf8(8, "v04.07.01-ml-android");
        msg.putVarint(9, 135135744);
        msg.putBytes(10, new byte[8]);
        msg.putVarint(11, 0);
        msg.putVarint(12, ts * 2);
        msg.putBytes(13, slice(Sm3.hash(bodyHash), 6));
        msg.putBytes(14, slice(queryHash, 6));
        msg.putProtobuf(15, nested);
        msg.putBytes(16, new byte[0]);
        msg.putUtf8(20, "none");
        msg.putVarint(21, 738);
        msg.putProtobuf(23, env);
        msg.putVarint(25, 2);

        byte[] secret = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c");
        byte[] derived = Sm3.hash(concat(secret, hex("3ccc567b"), secret));
        long[] keys = new long[72];
        for (int i = 0; i < 4; i++) keys[i] = le64(derived, i * 8);
        for (int i = 4; i < 72; i++) {
            long x = Long.rotateRight(keys[i - 1], 3) ^ keys[i - 3];
            keys[i] = ~keys[i - 4] ^ x ^ Long.rotateRight(x, 1)
                    ^ (((0x3dc94c3a046d678bL >>> ((i - 4) % 62)) & 1)) ^ 3;
        }
        byte[] blocks = pad16(msg.toBuf());
        ByteArrayOut enc = new ByteArrayOut();
        for (int at = 0; at < blocks.length; at += 16) {
            long a = le64(blocks, at);
            long b = le64(blocks, at + 8);
            for (long k : keys) {
                long next = a ^ (Long.rotateLeft(b, 1) & Long.rotateLeft(b, 8)) ^ Long.rotateLeft(b, 2) ^ k;
                a = b;
                b = next;
            }
            enc.write(leBytes(a));
            enc.write(leBytes(b));
        }
        byte[] mask = hex("d04ffdffd04ffdff");
        byte[] scrambled = enc.toByteArray();
        for (int i = 0; i < scrambled.length; i++) {
            scrambled[i] = (byte) (scrambled[i] ^ mask[i % 8]);
        }
        byte[] container = concat(hex("a6e783ee7001100918"), reverse(concat(mask, scrambled)), hex("567b"));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(md5(slice(secret, 0, 16)), "AES"),
                new IvParameterSpec(md5(slice(secret, 16, 32))));
        byte[] out = concat(hex("3ccc"), cipher.doFinal(container));
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
    }

    private static class ProtoBuf {
        private final List<byte[]> parts = new ArrayList<>();
        ProtoBuf putVarint(int tag, long value) {
            parts.add(varint(((long) tag << 3)));
            parts.add(varint(value));
            return this;
        }
        ProtoBuf putBytes(int tag, byte[] value) {
            parts.add(varint(((long) tag << 3) | 2));
            parts.add(varint(value.length));
            parts.add(value);
            return this;
        }
        ProtoBuf putUtf8(int tag, String s) {
            return putBytes(tag, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        ProtoBuf putProtobuf(int tag, ProtoBuf nested) {
            return putBytes(tag, nested.toBuf());
        }
        byte[] toBuf() {
            int n = 0;
            for (byte[] p : parts) n += p.length;
            byte[] out = new byte[n];
            int at = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, out, at, p.length);
                at += p.length;
            }
            return out;
        }
    }

    private static byte[] varint(long value) {
        long n = value & 0xffffffffL;
        ByteArrayOut out = new ByteArrayOut();
        while (n >= 128) {
            out.write((int) ((n & 127) | 128));
            n >>>= 7;
        }
        out.write((int) (n & 127));
        return out.toByteArray();
    }

    private static class ByteArrayOut {
        private byte[] buf = new byte[64];
        private int size;
        void write(int b) {
            if (size >= buf.length) {
                byte[] n = new byte[buf.length * 2];
                System.arraycopy(buf, 0, n, 0, size);
                buf = n;
            }
            buf[size++] = (byte) b;
        }
        void write(byte[] data) {
            for (byte b : data) write(b & 0xff);
        }
        byte[] toByteArray() {
            byte[] o = new byte[size];
            System.arraycopy(buf, 0, o, 0, size);
            return o;
        }
    }

    private static byte[] pad16(byte[] data) {
        int n = ((data.length + 15) / 16) * 16;
        if (n == data.length) return data;
        byte[] out = new byte[n];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    private static byte[] md5(byte[] data) throws Exception {
        return MessageDigest.getInstance("MD5").digest(data);
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long) (b[off + i] & 0xff)) << (8 * i);
        return v;
    }

    private static byte[] leBytes(long v) {
        byte[] o = new byte[8];
        for (int i = 0; i < 8; i++) o[i] = (byte) (v >>> (8 * i));
        return o;
    }

    private static byte[] slice(byte[] b, int n) {
        byte[] o = new byte[n];
        System.arraycopy(b, 0, o, 0, Math.min(n, b.length));
        return o;
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] o = new byte[to - from];
        System.arraycopy(b, from, o, 0, o.length);
        return o;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] o = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, o, at, p.length);
            at += p.length;
        }
        return o;
    }

    private static byte[] reverse(byte[] b) {
        byte[] o = new byte[b.length];
        for (int i = 0; i < b.length; i++) o[i] = b[b.length - 1 - i];
        return o;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
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
