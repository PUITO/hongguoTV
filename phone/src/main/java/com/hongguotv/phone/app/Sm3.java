package com.hongguotv.phone.app;

/** SM3 (GB/T 32905) — ported from hongguo-drama src/native/sm3.js */
public final class Sm3 {
    private Sm3() {}

    public static byte[] hash(byte[] source) {
        int len = ((source.length + 9 + 63) / 64) * 64;
        byte[] data = new byte[len];
        System.arraycopy(source, 0, data, 0, source.length);
        data[source.length] = (byte) 0x80;
        long bitLen = (long) source.length * 8L;
        for (int i = 0; i < 8; i++) {
            data[data.length - 1 - i] = (byte) (bitLen >>> (i * 8));
        }
        // big-endian bit length at end
        data[data.length - 8] = (byte) (bitLen >>> 56);
        data[data.length - 7] = (byte) (bitLen >>> 48);
        data[data.length - 6] = (byte) (bitLen >>> 40);
        data[data.length - 5] = (byte) (bitLen >>> 32);
        data[data.length - 4] = (byte) (bitLen >>> 24);
        data[data.length - 3] = (byte) (bitLen >>> 16);
        data[data.length - 2] = (byte) (bitLen >>> 8);
        data[data.length - 1] = (byte) bitLen;

        int[] state = {
                0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
                0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
        };
        int[] words = new int[68];
        for (int offset = 0; offset < data.length; offset += 64) {
            for (int i = 0; i < 16; i++) {
                words[i] = ((data[offset + i * 4] & 0xff) << 24)
                        | ((data[offset + i * 4 + 1] & 0xff) << 16)
                        | ((data[offset + i * 4 + 2] & 0xff) << 8)
                        | (data[offset + i * 4 + 3] & 0xff);
            }
            for (int i = 16; i < 68; i++) {
                int x = words[i - 16] ^ words[i - 9] ^ rot(words[i - 3], 15);
                words[i] = x ^ rot(x, 15) ^ rot(x, 23) ^ rot(words[i - 13], 7) ^ words[i - 6];
            }
            int a = state[0], b = state[1], c = state[2], d = state[3];
            int e = state[4], f = state[5], g = state[6], h = state[7];
            for (int i = 0; i < 64; i++) {
                int a12 = rot(a, 12);
                int ss1 = rot(a12 + e + rot(i < 16 ? 0x79cc4519 : 0x7a879d8a, i), 7);
                int ss2 = ss1 ^ a12;
                int ff = i < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c));
                int gg = i < 16 ? (e ^ f ^ g) : ((e & f) | (~e & g));
                int tt1 = ff + d + ss2 + (words[i] ^ words[i + 4]);
                int tt2 = gg + h + ss1 + words[i];
                d = c; c = rot(b, 9); b = a; a = tt1;
                h = g; g = rot(f, 19); f = e;
                e = tt2 ^ rot(tt2, 9) ^ rot(tt2, 17);
            }
            state[0] ^= a; state[1] ^= b; state[2] ^= c; state[3] ^= d;
            state[4] ^= e; state[5] ^= f; state[6] ^= g; state[7] ^= h;
        }
        byte[] digest = new byte[32];
        for (int i = 0; i < 8; i++) {
            digest[i * 4] = (byte) (state[i] >>> 24);
            digest[i * 4 + 1] = (byte) (state[i] >>> 16);
            digest[i * 4 + 2] = (byte) (state[i] >>> 8);
            digest[i * 4 + 3] = (byte) state[i];
        }
        return digest;
    }

    private static int rot(int x, int n) {
        n &= 31;
        return (x << n) | (x >>> (32 - n));
    }
}
