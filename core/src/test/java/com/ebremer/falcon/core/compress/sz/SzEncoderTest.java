package com.ebremer.falcon.core.compress.sz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.Vectors;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SZ 2 compression against libSZ (SZ 2.1.12 through hdf5plugin's H5Z-SZ, Windows x86-64, via
 * tools/fixtures/gen_sz_encoder_vectors.py): every data type, 1 to 4 dimensions (and dimensions of length 1),
 * every error-bound mode Falcon writes, and data of every kind SZ treats apart (smooth, noisy, constant,
 * nearly constant, sparse, wide-ranging with zeros and signs, NaN and infinities, 20 and 21 values). SZ's own
 * bytes, those beneath its zstd stage, are libSZ's, but for what libSZ leaves undefined (a parameter byte
 * {@code convertSZParamsToBytes} never writes; the two values a 1-D integer copy reads from past its input)
 * and the zstd frames of a point-wise relative stream's signs, compared as the signs. Falcon's whole streams
 * decode as libSZ's do, within each bound.
 */
class SzEncoderTest {

    private static final List<String[]> VECTORS = Vectors.read("sz_encoder_vectors.txt");

    /** One vector: its data type, dimensions, mode, bounds, input, and libSZ's bytes beneath zstd. */
    private record Case(String name, int type, long r5, long r4, long r3, long r2, long r1, int mode, double abs,
                        double rel, double pwr, byte[] input, byte[] expected) {

        static Case of(String[] v) {
            return new Case(v[0], Integer.parseInt(v[1]), Long.parseLong(v[2]), Long.parseLong(v[3]),
                    Long.parseLong(v[4]), Long.parseLong(v[5]), Long.parseLong(v[6]), Integer.parseInt(v[7]),
                    real(v[8]), real(v[9]), real(v[10]), Vectors.hex(v[11]), Vectors.hex(v[12]));
        }

        int count() {
            return input.length / SzDecoder.elementSize(type);
        }

        SzEncoder.Encoded encode(boolean msvcInt64Range) {
            return SzEncoder.encode(type, input, r5, r4, r3, r2, r1, mode, abs, rel, pwr, msvcInt64Range);
        }

        byte[] compress() {
            return SzEncoder.compress(type, input, r5, r4, r3, r2, r1, mode, abs, rel, pwr);
        }

        byte[] decode(byte[] stream) {
            return SzDecoder.decompress(type, stream, 0, stream.length, r5, r4, r3, r2, r1, input.length);
        }

        private static double real(String hex) {
            return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
        }
    }

    @Test
    void encodesAsLibSz() {
        assertEquals(1014, VECTORS.size());
        int floats = 0;
        int stored = 0;
        for (String[] v : VECTORS) {
            Case c = Case.of(v);
            // as hdf5plugin's MSVC build computes int64 ranges (see int64RangesAreComputedOverTheValues)
            SzEncoder.Encoded e = c.encode(true);
            if (c.type() <= SzDecoder.DOUBLE && c.count() <= SzEncoder.MIN_NUM_OF_ELEMENTS) {
                assertFalse(e.lossless(), c.name());
                assertArrayEquals(c.input(), e.bytes(), c.name()); // as it is, and so is libSZ's
                stored++;
                continue;
            }
            assertArrayEquals(normalize(c.expected(), c), normalize(e.bytes(), c), c.name());
            floats += c.type() <= SzDecoder.DOUBLE ? 1 : 0;
        }
        assertEquals(2, stored);
        assertEquals(548, floats);
    }

    /**
     * Falcon's whole streams, zstd stage and all, decode as libSZ's bytes for the same data decode; floats and
     * doubles come back within their bound (integers, as libSZ gives them back, not always: its 4-D coder's
     * slips, and fractional bounds, which it rounds).
     */
    @Test
    void decodesAsLibSzsStreamsDo() {
        int bounded = 0;
        int int64 = 0;
        for (String[] v : VECTORS) {
            Case c = Case.of(v);
            byte[] stream = c.compress();
            byte[] back = c.decode(stream);
            if (c.type() == SzDecoder.INT64 && !Arrays.equals(c.encode(true).bytes(), c.encode(false).bytes())) {
                int64++; // libSZ's range is wrong here: see int64RangesAreComputedOverTheValues
                continue;
            }
            assertArrayEquals(c.decode(c.expected()), back, c.name());
            if (c.type() <= SzDecoder.DOUBLE) {
                assertWithinBound(c, back);
                bounded++;
            }
        }
        assertEquals(550, bounded);
        assertEquals(25, int64); // of 58 int64 vectors
    }

    /**
     * hdf5plugin's MSVC build of libSZ reads int64 data through a 32-bit {@code long} when it computes the
     * value range, so values that need more bits come back far outside the bound; Falcon computes the range over
     * the values (int64's, and uint64's, which libSZ reads right), and only reproduces that build's bytes when
     * its tests ask.
     */
    @Test
    void int64RangesAreComputedOverTheValues() {
        int n = 300;
        byte[] data = new byte[8 * n];
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = 5_000_000_000_000L + (long) (Math.sin(i * 0.05) * 1e9) + i % 5;
            for (int k = 0; k < 8; k++) {
                data[8 * i + k] = (byte) (values[i] >>> 8 * k);
            }
        }
        for (int type : new int[] {SzDecoder.INT64, SzDecoder.UINT64}) {
            byte[] stream = SzEncoder.compress(type, data, 0, 0, 0, 15, 20, SzEncoder.ABS, 3, 0, 0);
            byte[] back = SzDecoder.decompress(type, stream, 0, stream.length, 0, 0, 0, 15, 20, data.length);
            for (int i = 0; i < n; i++) {
                long got = SzEncoder.le64(back, 8 * i);
                assertTrue(Math.abs(got - values[i]) <= 3, type + " value " + i + ": " + got + " for " + values[i]);
            }
            byte[] msvc = SzEncoder.encode(type, data, 0, 0, 0, 15, 20, SzEncoder.ABS, 3, 0, 0, true).bytes();
            assertEquals(type == SzDecoder.UINT64, Arrays.equals(msvc,
                    SzEncoder.encode(type, data, 0, 0, 0, 15, 20, SzEncoder.ABS, 3, 0, 0).bytes()), "the MSVC range");
        }
    }

    /**
     * The lossless stage is zstd, but where SZ skips it: 20 floats or doubles or fewer, kept as they are (20
     * integers are compressed, here into a stored copy, as libSZ compresses them).
     */
    @Test
    void theLosslessStageIsZstd() {
        byte[] data = new byte[4 * 21];
        for (int i = 0; i < 21; i++) {
            int bits = Float.floatToIntBits((float) Math.sin(i * 0.3));
            for (int k = 0; k < 4; k++) {
                data[4 * i + k] = (byte) (bits >>> 8 * k);
            }
        }
        byte[] stream = SzEncoder.compress(SzDecoder.FLOAT, data, 0, 0, 0, 0, 21, SzEncoder.ABS, 1e-3, 0, 0);
        assertArrayEquals(new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd}, Arrays.copyOf(stream, 4));
        assertArrayEquals(SzEncoder.encode(SzDecoder.FLOAT, data, 0, 0, 0, 0, 21, SzEncoder.ABS, 1e-3, 0, 0).bytes(),
                ZstdDecoder.decompress(stream));
        byte[] twenty = Arrays.copyOf(data, 80);
        assertArrayEquals(twenty, SzEncoder.compress(SzDecoder.FLOAT, twenty, 0, 0, 0, 4, 5, SzEncoder.ABS, 1e-3, 0, 0));
        // libSZ's bytes (hdf5plugin's H5Z-SZ) for these 20 bytes, beneath zstd: the two past them are 0 here
        byte[] bytes = Vectors.hex("7895b0c6d5dbd9cebba286684b332016141b2a40");
        SzEncoder.Encoded e = SzEncoder.encode(SzDecoder.UINT8, bytes, 0, 0, 0, 0, 20, SzEncoder.REL, 0, 0.5, 0);
        assertTrue(e.lossless());
        assertArrayEquals(Vectors.hex("02010c5044006426ac120000000038d1b717650000010000000000000000000000000000000000"
                + "167895b0c6d5dbd9cebba286684b332016141b2a400000"), e.bytes());
    }

    @Test
    void refusesWhatSzDoesNotCompress() {
        byte[] floats = new byte[4 * 40];
        // types, modes (PSNR and the rest), and bounds
        assertThrows(IllegalArgumentException.class, () -> SzEncoder.compress(10, floats, 0, 0, 0, 0, 40, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SzEncoder.compress(-1, floats, 0, 0, 0, 0, 40, 0, 1, 0, 0));
        for (int mode : new int[] {4, 5, 6, 11, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SzEncoder.compress(SzDecoder.FLOAT, floats, 0, 0, 0, 0, 40, mode, 1, 1, 1), "mode " + mode);
        }
        assertThrows(IllegalArgumentException.class,
                () -> SzEncoder.compress(SzDecoder.INT32, floats, 0, 0, 0, 0, 40, SzEncoder.PW_REL, 0, 0, 1e-3));
        for (double bad : new double[] {0, -1e-3, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SzEncoder.compress(SzDecoder.FLOAT, floats, 0, 0, 0, 0, 40, SzEncoder.ABS, bad, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> SzEncoder.compress(SzDecoder.DOUBLE, floats, 0, 0, 0, 0, 20, SzEncoder.REL, 1, bad, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> SzEncoder.compress(SzDecoder.FLOAT, floats, 0, 0, 0, 0, 40, SzEncoder.ABS_OR_REL, 1, bad, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> SzEncoder.compress(SzDecoder.FLOAT, floats, 0, 0, 0, 0, 40, SzEncoder.PW_REL, 1, 1, bad));
            assertThrows(IllegalArgumentException.class,
                    () -> SzEncoder.check(SzDecoder.UINT16, SzEncoder.ABS_AND_REL, bad, 1, 1));
        }
        // five dimensions longer than 1, none, negative ones, and data that does not fill them
        assertThrows(IllegalArgumentException.class,
                () -> SzEncoder.compress(SzDecoder.UINT8, new byte[32], 2, 2, 2, 2, 2, SzEncoder.ABS, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SzEncoder.compress(SzDecoder.UINT8, new byte[0], 0, 0, 0, 0, 0, SzEncoder.ABS, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SzEncoder.compress(SzDecoder.FLOAT, floats, 0, 0, 0, -4, -10, SzEncoder.ABS, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SzEncoder.compress(SzDecoder.FLOAT, floats, 0, 0, 0, 0, 39, SzEncoder.ABS, 1, 0, 0));
        // what SZ does take, with dimensions of length 1 dropped
        SzEncoder.check(SzDecoder.INT8, SzEncoder.ABS_AND_REL, 1, 0.1, 0);
        SzEncoder.compress(SzDecoder.FLOAT, floats, 1, 2, 1, 4, 5, SzEncoder.PW_REL, 0, 0, 1e-3);
        assertArrayEquals(new long[] {5, 4, 2, 0, 0}, SzEncoder.filterDimensions(1, 2, 1, 4, 5));
    }

    /**
     * SZ's bytes with what libSZ leaves undefined set to 0, and a point-wise relative stream's zstd-compressed
     * signs replaced by the signs.
     */
    private static byte[] normalize(byte[] stream, Case c) {
        byte[] b = stream.clone();
        b[19] = 0; // convertSZParamsToBytes' byte 15: never written, malloc's
        int size = SzDecoder.elementSize(c.type());
        if (c.type() >= SzDecoder.UINT8 && (b[3] & 0xFF) == 0x50 && b.length == 40 + size * (c.count() + 2)) {
            Arrays.fill(b, b.length - 2 * size, b.length, (byte) 0); // read from past libSZ's input
        }
        return c.type() <= SzDecoder.DOUBLE ? signs(b, c.type(), c.count()) : b;
    }

    /** A point-wise relative classic stream with its zstd-compressed signs replaced by the signs. */
    private static byte[] signs(byte[] b, int type, int count) {
        int flag = b[3] & 0xFF;
        if ((flag & 0x20) == 0 || (flag & 0x91) != 0) {
            return b; // not point-wise relative, or a constant, stored, or regression stream
        }
        int w = type == SzDecoder.FLOAT ? 4 : 8;
        int sizeAt = 4 + (type == SzDecoder.FLOAT ? SzParams.META_FLOAT : SzParams.META_DOUBLE) + 8 + 4 + 1 + 8;
        SzBuffer in = new SzBuffer(b, 0, b.length);
        int signs = in.be32(sizeAt);
        int pos = sizeAt + 4 + 4 + w + 1 + ((flag & 0x08) != 0 ? 2 : 0) + 8;
        int signsAt = (int) (pos + 8 + 8 + 8 + w + in.be64(pos));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(b, 0, sizeAt);
        out.write(b, sizeAt + 4, signsAt - sizeAt - 4);
        if (signs > 0) {
            out.writeBytes(ZstdDecoder.decompress(b, signsAt, signs, count));
        }
        out.write(b, signsAt + signs, b.length - signsAt - signs);
        return out.toByteArray();
    }

    /**
     * Each value within the case's bound, NaN and infinities aside; a zero, under a point-wise bound, within the
     * smallest value that is not (SZ's MSST19 form codes zeros as a value just below it).
     */
    private static void assertWithinBound(Case c, byte[] back) {
        int n = c.count();
        double[] in = new double[n];
        double[] got = new double[n];
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double nearZero = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            in[i] = value(c.input(), i, c.type());
            got[i] = value(back, i, c.type());
            if (Double.isFinite(in[i])) {
                lo = Math.min(lo, in[i]);
                hi = Math.max(hi, in[i]);
                nearZero = in[i] == 0 ? nearZero : Math.min(nearZero, Math.abs(in[i]));
            }
        }
        double bound = switch (c.mode()) {
            case SzEncoder.ABS -> c.abs();
            case SzEncoder.REL -> c.rel() * (hi - lo);
            case SzEncoder.ABS_AND_REL -> Math.min(c.abs(), c.rel() * (hi - lo));
            case SzEncoder.ABS_OR_REL -> Math.max(c.abs(), c.rel() * (hi - lo));
            default -> -1;
        };
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(in[i])) {
                continue;
            }
            if (c.mode() == SzEncoder.PW_REL && i == 0 && in[0] < 0) {
                continue; // a sign libSZ's MSST19 range pass never records: lost, as libSZ loses it
            }
            if (c.mode() == SzEncoder.PW_REL && in[i] == 0) {
                assertTrue(Math.abs(got[i]) <= nearZero, c.name() + " value " + i + ": " + got[i] + " for 0");
                continue;
            }
            double limit = c.mode() == SzEncoder.PW_REL ? c.pwr() * Math.abs(in[i]) : bound;
            // the bound, with the slack of the arithmetic SZ does in the values' own precision
            double slack = c.type() == SzDecoder.FLOAT ? 2 * Math.ulp((float) in[i]) + Math.ulp((float) limit)
                    : 4 * Math.ulp(in[i]);
            double err = Math.abs(got[i] - in[i]);
            assertTrue(err <= limit * (1 + 1e-6) + slack,
                    c.name() + " value " + i + ": " + got[i] + " for " + in[i] + ", bound " + limit);
        }
    }

    private static double value(byte[] b, int i, int type) {
        return type == SzDecoder.FLOAT ? Float.intBitsToFloat(SzEncoder.le32(b, 4 * i))
                : Double.longBitsToDouble(SzEncoder.le64(b, 8 * i));
    }
}
