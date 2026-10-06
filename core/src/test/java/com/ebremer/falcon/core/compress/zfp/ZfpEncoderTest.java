package com.ebremer.falcon.core.compress.zfp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.Vectors;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * zfp compression against libzfp 1.0.1 (tools/fixtures/gen_zfp_encoder_vectors.py): through hdf5plugin's
 * H5Z-ZFP, a bare stream of 8-bit words with its header in the filter's client data, and through zfpy, as
 * numcodecs' zfpy codec calls it, header and stream in one of 64-bit words. Every mode, scalar type, and
 * dimensionality, partial and whole blocks, and NaN, infinities, subnormals, and integers at their limits:
 * each stream byte for byte libzfp's.
 */
class ZfpEncoderTest {

    private static final List<String[]> VECTORS = Vectors.read("zfp_encoder_vectors.txt");

    /** The header each vector's mode should make, from {@link ZfpHeader#of}, by the mode in its name. */
    private static final Map<String, UnaryOperator<ZfpHeader>> H5Z_MODES = Map.ofEntries(
            Map.entry("rate2", h -> h.withRate(2, true)),
            Map.entry("rate8", h -> h.withRate(8, true)),
            Map.entry("rate12", h -> h.withRate(12, true)),
            Map.entry("rate16", h -> h.withRate(16, true)),
            Map.entry("rate40", h -> h.withRate(40, true)),
            Map.entry("prec3", h -> h.withPrecision(3)),
            Map.entry("prec28", h -> h.withPrecision(28)),
            Map.entry("prec64", h -> h.withPrecision(64)),
            Map.entry("acc1e-2", h -> h.withAccuracy(1e-2)),
            Map.entry("acc1e-3", h -> h.withAccuracy(1e-3)),
            Map.entry("acc1e-9", h -> h.withAccuracy(1e-9)),
            Map.entry("acc100", h -> h.withAccuracy(100)),
            Map.entry("rev", ZfpHeader::withReversible));
    private static final Map<String, UnaryOperator<ZfpHeader>> ZFPY_MODES = Map.ofEntries(
            Map.entry("rate0.25", h -> h.withRate(0.25, false)),
            Map.entry("rate2", h -> h.withRate(2, false)),
            Map.entry("rate8", h -> h.withRate(8, false)),
            Map.entry("rate12", h -> h.withRate(12, false)),
            Map.entry("rate16", h -> h.withRate(16, false)),
            Map.entry("rate40", h -> h.withRate(40, false)),
            Map.entry("prec0", h -> h.withPrecision(0)),
            Map.entry("prec3", h -> h.withPrecision(3)),
            Map.entry("prec28", h -> h.withPrecision(28)),
            Map.entry("acc0", h -> h.withAccuracy(0)),
            Map.entry("acc1e-2", h -> h.withAccuracy(1e-2)),
            Map.entry("acc1e-3", h -> h.withAccuracy(1e-3)),
            Map.entry("acc1e-9", h -> h.withAccuracy(1e-9)),
            Map.entry("acc100", h -> h.withAccuracy(100)),
            Map.entry("rev", ZfpHeader::withReversible));

    @Test
    void encodesAsLibzfpDoes() {
        assertEquals(556, VECTORS.size());
        int h5z = 0;
        for (String[] v : VECTORS) {
            byte[] input = Vectors.hex(v[3]);
            byte[] expected = Vectors.hex(v[4]);
            if (v[1].equals("h5z")) {
                byte[] header = Vectors.hex(v[2]);
                ZfpHeader h = ZfpHeader.read(header, 0, header.length);
                assertArrayEquals(expected, ZfpEncoder.compress(h, input, 0, 8), v[0]);
                // the header H5Z-ZFP keeps, padded with zeros to its 32-bit client data words
                byte[] written = ZfpEncoder.header(h, 8);
                assertArrayEquals(header, Arrays.copyOf(written, header.length), v[0] + " header");
                h5z++;
            } else {
                ZfpHeader h = ZfpHeader.read(expected, 0, expected.length);
                assertArrayEquals(expected, ZfpEncoder.compressWithHeader(h, input, 0, 64), v[0]);
            }
        }
        assertEquals(274, h5z);
    }

    /** {@link ZfpHeader#withRate} and the rest set the parameters zfp's {@code zfp_stream_set_*} set. */
    @Test
    void modesAreZfps() {
        for (String[] v : VECTORS) {
            String mode = v[0].split("_")[2];
            boolean h5z = v[1].equals("h5z");
            byte[] header = Vectors.hex(h5z ? v[2] : v[4]);
            ZfpHeader read = ZfpHeader.read(header, 0, header.length);
            UnaryOperator<ZfpHeader> set = mode.equals("expert")
                    ? h -> v[0].endsWith("zero") ? h.withParameters(200, 500, 30, -10)
                            : h.withParameters(300, 900, 40, -30)
                    : (h5z ? H5Z_MODES : ZFPY_MODES).get(mode);
            ZfpHeader made = set.apply(ZfpHeader.of(read.type(), read.nx(), read.ny(), read.nz(), read.nw()));
            assertEquals(read, made, v[0]);
        }
    }

    /** The reversible mode gives every value back, bit for bit, NaN payloads and signed zeros included. */
    @Test
    void reversibleRoundTrips() {
        int checked = 0;
        for (String[] v : VECTORS) {
            if (!v[0].split("_")[2].equals("rev")) {
                continue;
            }
            byte[] input = Vectors.hex(v[3]);
            byte[] stream = Vectors.hex(v[4]);
            ZfpHeader h = v[1].equals("h5z") ? ZfpHeader.read(Vectors.hex(v[2]), 0, v[2].length() / 2)
                    : ZfpHeader.read(stream, 0, stream.length);
            byte[] encoded = ZfpEncoder.compressWithHeader(h, input, 0, 8);
            assertArrayEquals(input, ZfpDecoder.decompress(encoded, 0, encoded.length, input.length), v[0]);
            checked++;
        }
        assertTrue(checked > 60, "reversible vectors: " + checked);
    }

    /**
     * Random fields of every type, 1 to 4 dimensions, and sizes 1 to 9 (whole and partial blocks), with random
     * bit patterns: the reversible mode gives each back bit for bit, with either word size.
     */
    @Test
    void reversibleRoundTripsRandomFields() {
        java.util.Random random = new java.util.Random(31);
        for (int trial = 0; trial < 400; trial++) {
            ZfpHeader.Type type = ZfpHeader.Type.values()[trial % 4];
            int dims = 1 + random.nextInt(4);
            long[] n = new long[4];
            for (int d = 0; d < dims; d++) {
                n[d] = 1 + random.nextInt(9);
            }
            ZfpHeader h = ZfpHeader.of(type, n[0], n[1], n[2], n[3]).withReversible();
            byte[] input = new byte[(int) (h.elements() * type.size())];
            if (trial % 3 == 0) {
                random.nextBytes(input); // any bits: NaNs, infinities, subnormals, integers at their limits
            } else {
                for (int i = 0; i < input.length; i += type.size()) { // smooth-ish small values
                    long v = Double.doubleToRawLongBits(Math.sin(i * 0.01) * 100);
                    if (type == ZfpHeader.Type.FLOAT) {
                        v = Float.floatToRawIntBits((float) Math.sin(i * 0.01));
                    } else if (type == ZfpHeader.Type.INT32 || type == ZfpHeader.Type.INT64) {
                        v = (long) (Math.sin(i * 0.01) * 1000);
                    }
                    for (int b = 0; b < type.size(); b++) {
                        input[i + b] = (byte) (v >>> (8 * b));
                    }
                }
            }
            int wordBits = trial % 2 == 0 ? 8 : 64;
            byte[] stream = ZfpEncoder.compress(h, input, 0, wordBits);
            assertEquals(0, stream.length % (wordBits / 8));
            assertArrayEquals(input, ZfpDecoder.decompress(h, stream, 0, stream.length, input.length),
                    "trial " + trial);
        }
    }

    /**
     * Without the scalar type, zfp lets a float block's rate fall below its 9-bit exponent (zfpy then overruns
     * its own buffer); the remaining budget wraps around, so the block is coded in full, and decodes.
     */
    @Test
    void aRateBelowTheExponentStillDecodes() {
        ZfpHeader h = ZfpHeader.of(ZfpHeader.Type.FLOAT, 8, 0, 0, 0).withRate(0.25, false);
        assertEquals(1, h.maxbits());
        byte[] input = new byte[32];
        for (int i = 0; i < 8; i++) {
            int bits = Float.floatToRawIntBits(1.5f * i - 2);
            for (int b = 0; b < 4; b++) {
                input[4 * i + b] = (byte) (bits >>> (8 * b));
            }
        }
        byte[] stream = ZfpEncoder.compressWithHeader(h, input, 0, 8);
        assertArrayEquals(input, ZfpDecoder.decompress(stream, 0, stream.length, 32)); // 64 planes: exact here
    }

    /** The modes as a header records them: the short forms, and the long one past their ranges. */
    @Test
    void encodesTheMode() {
        ZfpHeader f = ZfpHeader.of(ZfpHeader.Type.FLOAT, 37, 0, 0, 0);
        assertEquals(47, f.withRate(12, true).encodedMode());        // 48 bits a block, less one
        assertEquals(2048 + 2, f.withPrecision(3).encodedMode());
        assertEquals(2048 + 128, f.withReversible().encodedMode());
        assertEquals(-7 + 1074 + 2177, f.withAccuracy(1e-2).encodedMode()); // 2^-7 <= 0.01 < 2^-6
        assertTrue(Long.compareUnsigned(f.encodedMode(), ZfpHeader.MODE_SHORT_MAX) > 0); // zfp's defaults: expert
        assertTrue(Long.compareUnsigned(f.withPrecision(64).encodedMode(), ZfpHeader.MODE_SHORT_MAX) > 0);
        ZfpHeader expert = f.withParameters(300, 900, 40, -30);
        byte[] header = ZfpEncoder.header(expert, 8);
        assertEquals(ZfpHeader.LONG_HEADER_BITS, 8 * header.length - 4); // 148 bits, padded to 19 bytes
        assertEquals(expert, ZfpHeader.read(header, 0, header.length));
        assertEquals(ZfpHeader.SHORT_HEADER_BITS, 8 * ZfpEncoder.header(f.withReversible(), 8).length);
        assertEquals(16, ZfpEncoder.header(f.withReversible(), 64).length); // 96 bits in two 64-bit words
        // a subnormal tolerance: frexp's exponent of the value itself
        assertEquals(-1074, f.withAccuracy(Double.MIN_VALUE).minexp());
        assertEquals(-1023, f.withAccuracy(0x1p-1023).minexp());
    }

    @Test
    void refusesWhatZfpCannotRecord() {
        ZfpHeader f = ZfpHeader.of(ZfpHeader.Type.FLOAT, 37, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> f.withRate(Double.NaN, true));
        assertThrows(IllegalArgumentException.class, () -> f.withRate(-1, true));
        assertThrows(IllegalArgumentException.class, () -> f.withRate(0.1, false)); // no bits a block
        assertThrows(IllegalArgumentException.class, () -> f.withRate(9000, false)); // past 32768 bits a block
        assertThrows(IllegalArgumentException.class, () -> f.withPrecision(-1));
        assertThrows(IllegalArgumentException.class, () -> f.withAccuracy(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> f.withParameters(10, 5, 20, 0));
        // sizes a header cannot hold: 2^24 + 1 in two dimensions
        ZfpHeader wide = ZfpHeader.of(ZfpHeader.Type.INT32, (1 << 24) + 1, 1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> ZfpEncoder.header(wide, 8));
        // too few values, and a word size zfp has not
        assertThrows(IllegalArgumentException.class, () -> ZfpEncoder.compress(f, new byte[100], 0, 8));
        assertThrows(IllegalArgumentException.class, () -> ZfpEncoder.compress(f, new byte[148], 0, 12));
    }
}
