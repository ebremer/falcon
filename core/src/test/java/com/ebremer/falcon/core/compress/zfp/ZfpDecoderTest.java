package com.ebremer.falcon.core.compress.zfp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.Vectors;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * zfp against libzfp 1.0.1 (hdf5plugin's H5Z-ZFP, via tools/fixtures/gen_zfp_vectors.py): every mode, scalar
 * type and dimensionality, with partial blocks, decoded bit for bit as libzfp decodes it.
 */
class ZfpDecoderTest {

    private static final List<String[]> VECTORS = Vectors.read("zfp_vectors.txt");

    @Test
    void decodesAsLibzfpDoes() {
        assertEquals(190, VECTORS.size());
        for (String[] v : VECTORS) {
            byte[] header = Vectors.hex(v[1]);
            byte[] stream = Vectors.hex(v[2]);
            byte[] expected = Vectors.hex(v[3]);
            ZfpHeader h = ZfpHeader.read(header, 0, header.length);
            assertEquals(expected.length, h.elements() * h.type().size(), v[0]);
            assertArrayEquals(expected, ZfpDecoder.decompress(h, stream, 0, stream.length, expected.length), v[0]);
        }
    }

    @Test
    void readsEveryModeAndShape() {
        ZfpHeader rate = header("f4_1d_rate12");
        assertEquals(ZfpHeader.Type.FLOAT, rate.type());
        assertEquals(37, rate.nx());
        assertEquals(1, rate.dimensions());
        assertEquals(48, rate.minbits()); // 12 bits per value, 4 values a block
        assertEquals(48, rate.maxbits());
        ZfpHeader precision = header("i8_4d_prec28");
        assertEquals(ZfpHeader.Type.INT64, precision.type());
        assertEquals(4, precision.dimensions());
        // HDF5's (5, 4, 3, 2): zfp's x is the last, fastest dimension
        assertEquals(List.of(2L, 3L, 4L, 5L), List.of(precision.nx(), precision.ny(), precision.nz(), precision.nw()));
        assertEquals(28, precision.maxprec());
        ZfpHeader accuracy = header("f8_3d_acc1e-2");
        assertEquals(-7, accuracy.minexp()); // 2^-7 <= 0.01 < 2^-6
        assertTrue(header("i4_2d_rev").reversible());
        ZfpHeader expert = header("f8_2d_expert"); // a 64-bit mode: each parameter
        assertEquals(List.of(300, 900, 40, -30), List.of(expert.minbits(), expert.maxbits(), expert.maxprec(), expert.minexp()));
        ZfpHeader longRate = header("f4_4d_rate40"); // fixed rate past 2048 bits a block is also a 64-bit mode
        assertEquals(40 * 256, longRate.maxbits());
    }

    @Test
    void decodesAStreamThatStartsWithItsHeader() {
        // zfp_write_header then zfp_compress in one stream, as numcodecs' zfpy writes it: the field's bits follow
        // the header's (96 or 148 of them) directly.
        for (String name : List.of("f8_3d_prec28", "i4_2d_rev", "f4_1d_expert", "i8_4d_rate40")) {
            String[] v = vector(name);
            byte[] header = Vectors.hex(v[1]);
            byte[] stream = Vectors.hex(v[2]);
            int headerBits = isLong(header) ? ZfpHeader.LONG_HEADER_BITS : ZfpHeader.SHORT_HEADER_BITS;
            byte[] combined = concatenateBits(header, headerBits, stream);
            assertArrayEquals(Vectors.hex(v[3]), ZfpDecoder.decompress(combined, 0, combined.length, 1 << 20), name);
        }
    }

    @Test
    void refusesMalformedInput() {
        String[] v = vector("f8_2d_prec28");
        byte[] header = Vectors.hex(v[1]);
        byte[] stream = Vectors.hex(v[2]);
        ZfpHeader h = ZfpHeader.read(header, 0, header.length);
        // a truncated stream
        assertThrows(CompressionFormatException.class,
                () -> ZfpDecoder.decompress(h, stream, 0, stream.length / 2, 1 << 20));
        // a field larger than the caller allows
        assertThrows(CompressionFormatException.class, () -> ZfpDecoder.decompress(h, stream, 0, stream.length, 100));
        // not a zfp header, a truncated one, and another codec version
        assertThrows(CompressionFormatException.class, () -> ZfpHeader.read(new byte[12], 0, 12));
        assertThrows(CompressionFormatException.class, () -> ZfpHeader.read(header, 0, 10));
        byte[] codec4 = header.clone();
        codec4[3] = 4;
        assertThrows(UnsupportedCompressionException.class, () -> ZfpHeader.read(codec4, 0, codec4.length));
        // fixed precision of more than 64 bit planes (short mode 2048 + 100)
        byte[] badPrecision = header.clone();
        int mode = 2048 + 100; // bits 84 to 95
        badPrecision[10] = (byte) ((badPrecision[10] & 0x0f) | ((mode & 0x0f) << 4));
        badPrecision[11] = (byte) (mode >>> 4);
        assertThrows(CompressionFormatException.class, () -> ZfpHeader.read(badPrecision, 0, badPrecision.length));
        assertThrows(IllegalArgumentException.class,
                () -> new ZfpHeader(ZfpHeader.Type.FLOAT, 4, 0, 1, 0, 1, 10, 10, 0));
    }

    private static boolean isLong(byte[] header) {
        // the 12 mode bits after 84 bits of magic and metadata are all ones in the 64-bit form
        return ((header[10] & 0xf0) >>> 4 | (header[11] & 0xff) << 4) == 0xfff;
    }

    /** {@code headerBits} of {@code header}, then every bit of {@code stream}, least significant first. */
    private static byte[] concatenateBits(byte[] header, int headerBits, byte[] stream) {
        byte[] out = new byte[(headerBits + 8 * stream.length + 7) / 8];
        int at = 0;
        for (int i = 0; i < headerBits; i++, at++) {
            out[at >>> 3] |= (byte) (((header[i >>> 3] >>> (i & 7)) & 1) << (at & 7));
        }
        for (int i = 0; i < 8 * stream.length; i++, at++) {
            out[at >>> 3] |= (byte) (((stream[i >>> 3] >>> (i & 7)) & 1) << (at & 7));
        }
        return out;
    }

    private static ZfpHeader header(String name) {
        byte[] header = Vectors.hex(vector(name)[1]);
        return ZfpHeader.read(header, 0, header.length);
    }

    private static String[] vector(String name) {
        return VECTORS.stream().filter(v -> v[0].equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no vector " + name + " in " + Arrays.toString(
                        VECTORS.stream().map(v -> v[0]).toArray())));
    }
}
