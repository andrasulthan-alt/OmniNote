package io.github.andrasulthan.omninote;

/**
 * Argon2id (RFC 9106, version 0x13) with BLAKE2b (RFC 7693), written for OmniNote
 * without libraries. Turns a vault password into an encryption key.
 * Checked against the RFC 9106 test vector and the reference argon2 library.
 */
public final class Argon2 {

    private Argon2() {}

    private static final int BLOCK_WORDS = 128;
    private static final int TYPE_ID = 2;
    private static final int VERSION = 0x13;

    /** Argon2id with no secret or associated data. */
    public static byte[] hash(byte[] password, byte[] salt, int iterations, int memoryKiB, int lanes, int outLen) {
        return hash(password, salt, new byte[0], new byte[0], iterations, memoryKiB, lanes, outLen);
    }

    public static byte[] hash(byte[] password, byte[] salt, byte[] secret, byte[] ad,
                              int iterations, int memoryKiB, int lanes, int outLen) {
        Blake2b h = new Blake2b(64);
        h.update(le32(lanes));
        h.update(le32(outLen));
        h.update(le32(memoryKiB));
        h.update(le32(iterations));
        h.update(le32(VERSION));
        h.update(le32(TYPE_ID));
        h.update(le32(password.length));
        h.update(password);
        h.update(le32(salt.length));
        h.update(salt);
        h.update(le32(secret.length));
        h.update(secret);
        h.update(le32(ad.length));
        h.update(ad);
        byte[] h0 = h.digest();

        int blocks = 4 * lanes * (memoryKiB / (4 * lanes));
        int laneLength = blocks / lanes;
        int segmentLength = laneLength / 4;
        long[][] memory = new long[blocks][];

        for (int lane = 0; lane < lanes; lane++) {
            for (int i = 0; i < 2; i++) {
                byte[] input = new byte[72];
                System.arraycopy(h0, 0, input, 0, 64);
                System.arraycopy(le32(i), 0, input, 64, 4);
                System.arraycopy(le32(lane), 0, input, 68, 4);
                memory[lane * laneLength + i] = toWords(longHash(input, 1024));
            }
        }

        long[] zero = new long[BLOCK_WORDS];
        long[] input = new long[BLOCK_WORDS];
        long[] address = new long[BLOCK_WORDS];
        long[] tmp = new long[BLOCK_WORDS];

        for (int pass = 0; pass < iterations; pass++) {
            for (int slice = 0; slice < 4; slice++) {
                for (int lane = 0; lane < lanes; lane++) {
                    boolean independent = pass == 0 && slice < 2;
                    if (independent) {
                        java.util.Arrays.fill(input, 0);
                        input[0] = pass;
                        input[1] = lane;
                        input[2] = slice;
                        input[3] = blocks;
                        input[4] = iterations;
                        input[5] = TYPE_ID;
                    }
                    int startIndex = (pass == 0 && slice == 0) ? 2 : 0;
                    if (independent && startIndex != 0) nextAddresses(input, address, zero, tmp);
                    for (int index = startIndex; index < segmentLength; index++) {
                        int col = slice * segmentLength + index;
                        int cur = lane * laneLength + col;
                        int prev = (col == 0) ? lane * laneLength + laneLength - 1 : cur - 1;
                        long pseudo;
                        if (independent) {
                            if (index % BLOCK_WORDS == 0) nextAddresses(input, address, zero, tmp);
                            pseudo = address[index % BLOCK_WORDS];
                        } else {
                            pseudo = memory[prev][0];
                        }
                        long j1 = pseudo & 0xFFFFFFFFL;
                        long j2 = pseudo >>> 32;
                        int refLane = (pass == 0 && slice == 0) ? lane : (int) (j2 % lanes);
                        boolean sameLane = refLane == lane;
                        long area;
                        if (pass == 0) {
                            if (sameLane) {
                                area = (long) slice * segmentLength + index - 1;
                            } else {
                                area = (long) slice * segmentLength - (index == 0 ? 1 : 0);
                            }
                        } else {
                            if (sameLane) {
                                area = laneLength - segmentLength + index - 1;
                            } else {
                                area = laneLength - segmentLength - (index == 0 ? 1 : 0);
                            }
                        }
                        long x = (j1 * j1) >>> 32;
                        long y = (area * x) >>> 32;
                        long z = area - 1 - y;
                        long start = (pass == 0) ? 0 : ((long) (slice + 1) * segmentLength) % laneLength;
                        int refCol = (int) ((start + z) % laneLength);
                        long[] ref = memory[refLane * laneLength + refCol];
                        long[] out = new long[BLOCK_WORDS];
                        compress(memory[prev], ref, out, tmp);
                        if (pass > 0 && memory[cur] != null) {
                            long[] old = memory[cur];
                            for (int k = 0; k < BLOCK_WORDS; k++) out[k] ^= old[k];
                        }
                        memory[cur] = out;
                    }
                }
            }
        }

        long[] last = memory[laneLength - 1].clone();
        for (int lane = 1; lane < lanes; lane++) {
            long[] b = memory[lane * laneLength + laneLength - 1];
            for (int k = 0; k < BLOCK_WORDS; k++) last[k] ^= b[k];
        }
        return longHash(toBytes(last), outLen);
    }

    private static void nextAddresses(long[] input, long[] address, long[] zero, long[] tmp) {
        input[6]++;
        long[] first = new long[BLOCK_WORDS];
        compress(zero, input, first, tmp);
        compress(zero, first, address, tmp);
    }

    /** G(X, Y): out = P(X xor Y) xor (X xor Y). */
    private static void compress(long[] x, long[] y, long[] out, long[] r) {
        for (int i = 0; i < BLOCK_WORDS; i++) r[i] = x[i] ^ y[i];
        long[] q = r.clone();
        for (int row = 0; row < 8; row++) {
            int b = row * 16;
            permute(q, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15);
        }
        for (int col = 0; col < 8; col++) {
            int b = col * 2;
            permute(q, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
                b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113);
        }
        for (int i = 0; i < BLOCK_WORDS; i++) out[i] = q[i] ^ r[i];
    }

    private static void permute(long[] v, int i0, int i1, int i2, int i3, int i4, int i5, int i6, int i7,
                                int i8, int i9, int i10, int i11, int i12, int i13, int i14, int i15) {
        gb(v, i0, i4, i8, i12);
        gb(v, i1, i5, i9, i13);
        gb(v, i2, i6, i10, i14);
        gb(v, i3, i7, i11, i15);
        gb(v, i0, i5, i10, i15);
        gb(v, i1, i6, i11, i12);
        gb(v, i2, i7, i8, i13);
        gb(v, i3, i4, i9, i14);
    }

    private static long fBlaMka(long x, long y) {
        return x + y + 2 * ((x & 0xFFFFFFFFL) * (y & 0xFFFFFFFFL));
    }

    private static void gb(long[] v, int a, int b, int c, int d) {
        v[a] = fBlaMka(v[a], v[b]);
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = fBlaMka(v[c], v[d]);
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = fBlaMka(v[a], v[b]);
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = fBlaMka(v[c], v[d]);
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    /** H' from RFC 9106: hash of any length built from BLAKE2b. */
    private static byte[] longHash(byte[] input, int outLen) {
        if (outLen <= 64) {
            Blake2b h = new Blake2b(outLen);
            h.update(le32(outLen));
            h.update(input);
            return h.digest();
        }
        byte[] out = new byte[outLen];
        Blake2b h = new Blake2b(64);
        h.update(le32(outLen));
        h.update(input);
        byte[] v = h.digest();
        System.arraycopy(v, 0, out, 0, 32);
        int pos = 32;
        int r = (outLen + 31) / 32 - 2;
        for (int i = 2; i <= r; i++) {
            Blake2b hi = new Blake2b(64);
            hi.update(v);
            v = hi.digest();
            System.arraycopy(v, 0, out, pos, 32);
            pos += 32;
        }
        Blake2b last = new Blake2b(outLen - 32 * r);
        last.update(v);
        byte[] tail = last.digest();
        System.arraycopy(tail, 0, out, pos, tail.length);
        return out;
    }

    private static byte[] le32(int value) {
        return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }

    private static long[] toWords(byte[] bytes) {
        long[] w = new long[bytes.length / 8];
        for (int i = 0; i < w.length; i++) {
            long v = 0;
            for (int k = 7; k >= 0; k--) v = (v << 8) | (bytes[i * 8 + k] & 0xFFL);
            w[i] = v;
        }
        return w;
    }

    private static byte[] toBytes(long[] words) {
        byte[] b = new byte[words.length * 8];
        for (int i = 0; i < words.length; i++) {
            long v = words[i];
            for (int k = 0; k < 8; k++) {
                b[i * 8 + k] = (byte) v;
                v >>>= 8;
            }
        }
        return b;
    }

    /** BLAKE2b (RFC 7693), unkeyed, any output length up to 64 bytes. */
    static final class Blake2b {
        private static final long[] IV = {
            0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
        };
        private static final byte[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3}
        };

        private final long[] h = new long[8];
        private final byte[] buf = new byte[128];
        private int bufLen = 0;
        private long counter = 0;
        private final int outLen;

        Blake2b(int outLen) {
            this.outLen = outLen;
            System.arraycopy(IV, 0, h, 0, 8);
            h[0] ^= 0x01010000L ^ outLen;
        }

        void update(byte[] data) {
            int off = 0;
            int len = data.length;
            while (len > 0) {
                if (bufLen == 128) {
                    counter += 128;
                    compressBlock(false);
                    bufLen = 0;
                }
                int take = Math.min(128 - bufLen, len);
                System.arraycopy(data, off, buf, bufLen, take);
                bufLen += take;
                off += take;
                len -= take;
            }
        }

        byte[] digest() {
            counter += bufLen;
            for (int i = bufLen; i < 128; i++) buf[i] = 0;
            compressBlock(true);
            byte[] out = new byte[outLen];
            for (int i = 0; i < outLen; i++) out[i] = (byte) (h[i / 8] >>> (8 * (i % 8)));
            return out;
        }

        private void compressBlock(boolean last) {
            long[] m = new long[16];
            for (int i = 0; i < 16; i++) {
                long v = 0;
                for (int k = 7; k >= 0; k--) v = (v << 8) | (buf[i * 8 + k] & 0xFFL);
                m[i] = v;
            }
            long[] v = new long[16];
            System.arraycopy(h, 0, v, 0, 8);
            System.arraycopy(IV, 0, v, 8, 8);
            v[12] ^= counter;
            if (last) v[14] = ~v[14];
            for (int r = 0; r < 12; r++) {
                byte[] s = SIGMA[r];
                mix(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
                mix(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
                mix(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
                mix(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
                mix(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
                mix(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
                mix(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
                mix(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
            }
            for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
        }

        private static void mix(long[] v, int a, int b, int c, int d, long x, long y) {
            v[a] = v[a] + v[b] + x;
            v[d] = Long.rotateRight(v[d] ^ v[a], 32);
            v[c] = v[c] + v[d];
            v[b] = Long.rotateRight(v[b] ^ v[c], 24);
            v[a] = v[a] + v[b] + y;
            v[d] = Long.rotateRight(v[d] ^ v[a], 16);
            v[c] = v[c] + v[d];
            v[b] = Long.rotateRight(v[b] ^ v[c], 63);
        }
    }
}
