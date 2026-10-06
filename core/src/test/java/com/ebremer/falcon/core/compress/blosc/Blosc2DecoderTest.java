package com.ebremer.falcon.core.compress.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.Vectors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * c-blosc2 chunks (Blosc format versions 3 to 6), decoded against reference chunks that c-blosc2 3.3.2
 * wrote or read ({@code tools/fixtures/gen_blosc2_vectors.py}, via imagecodecs): every internal codec and
 * filter, type sizes 1 to 255, the split modes, special-value chunks, several filters in a pipeline, and
 * headers with and without the extended part. What c-blosc2 refuses, or what Falcon does not read, is
 * refused with a typed exception.
 */
class Blosc2DecoderTest {

    private static final int EXTENDED = 32;

    private record Vector(String name, String cname, String filter, int typeSize, byte[] original, byte[] chunk) {
    }

    private static List<Vector> vectors() {
        List<Vector> out = new ArrayList<>();
        for (String[] v : Vectors.read("blosc2_vectors.txt")) {
            out.add(new Vector(v[0], v[1], v[3], Integer.parseInt(v[4]), Vectors.hex(v[5]), Vectors.hex(v[6])));
        }
        return out;
    }

    @Test
    void decodesEveryReferenceChunk() {
        List<Vector> vectors = vectors();
        assertTrue(vectors.size() >= 120, "expected the full vector set, got " + vectors.size());
        List<String> failures = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Vector v : vectors) {
            byte[] chunk = v.chunk();
            seen.add("v" + chunk[0]);
            seen.add(v.cname());
            seen.add(v.filter());
            seen.add(((chunk[2] & 0x05) == 0x05 ? "extended" : "short") + "-header");
            if ((chunk[2] & 0x02) != 0) {
                seen.add("memcpy");
            }
            if (chunk.length >= EXTENDED && (chunk[2] & 0x05) == 0x05 && (chunk[31] & 0x70) != 0) {
                seen.add("special-" + ((chunk[31] >> 4) & 7));
            }
            try {
                byte[] out = BloscDecoder.decompress(chunk);
                if (!Arrays.equals(v.original(), out)) {
                    failures.add(v.name() + ": " + (out.length != v.original().length
                            ? "length " + out.length + " != " + v.original().length
                            : "content differs at byte " + Arrays.mismatch(out, v.original())));
                }
                assertEquals(v.original().length, BloscDecoder.decompressedSize(chunk), v.name());
                assertArrayEquals(v.original(), BloscDecoder.decompress(chunk, v.original().length), v.name());
                if (v.original().length > 0) {
                    assertThrows(CompressionFormatException.class,
                            () -> BloscDecoder.decompress(chunk, v.original().length - 1), v.name());
                }
            } catch (RuntimeException e) {
                failures.add(v.name() + " (" + v.cname() + "/" + v.filter() + "): threw " + e);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + "/" + vectors.size() + " chunks failed:\n  "
                + String.join("\n  ", failures));
        // Every version, codec, filter, header form, and special value the set is meant to hold.
        for (String expected : List.of("v3", "v4", "v5", "v6", "blosclz", "lz4", "lz4hc", "zlib", "zstd",
                "noshuffle", "shuffle", "bitshuffle", "delta", "delta+shuffle", "shuffle/meta2", "trunc+shuffle",
                "extended-header", "short-header", "memcpy", "special-1", "special-2", "special-3")) {
            assertTrue(seen.contains(expected), "no vector covers " + expected + ": " + seen);
        }
    }

    // ---- crafted chunks --------------------------------------------------------------------------------

    /** A 32-byte extended header; the caller appends offsets and streams, or a repeated value. */
    private static byte[] header(int version, int flags, int typeSize, int nbytes, int blocksize, int cbytes) {
        byte[] out = new byte[cbytes];
        out[0] = (byte) version;
        out[1] = 1;
        out[2] = (byte) (flags | 0x05);
        out[3] = (byte) typeSize;
        putLe32(out, 4, nbytes);
        putLe32(out, 8, blocksize);
        putLe32(out, 12, cbytes);
        return out;
    }

    /** One block of {@code data}, stored as a single raw stream (flags: not split). */
    private static byte[] rawChunk(byte[] data, int typeSize) {
        byte[] out = header(5, 0x10, typeSize, data.length, data.length, EXTENDED + 4 + 4 + data.length);
        putLe32(out, EXTENDED, EXTENDED + 4);
        putLe32(out, EXTENDED + 4, data.length);
        System.arraycopy(data, 0, out, EXTENDED + 8, data.length);
        return out;
    }

    private static byte[] special(int kind, int typeSize, int nbytes, int blocksize, byte[] value) {
        byte[] out = header(5, 0, typeSize, nbytes, blocksize, EXTENDED + value.length);
        out[31] = (byte) (kind << 4);
        System.arraycopy(value, 0, out, EXTENDED, value.length);
        return out;
    }

    private static void putLe32(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
        b[off + 2] = (byte) (value >>> 16);
        b[off + 3] = (byte) (value >>> 24);
    }

    private static byte[] sample(int n) {
        byte[] data = new byte[n];
        for (int i = 0; i < n; i++) {
            data[i] = (byte) (i * 7 + i / 64);
        }
        return data;
    }

    private static void assertMalformed(byte[] chunk, String expected) {
        CompressionFormatException e = assertThrows(CompressionFormatException.class,
                () -> BloscDecoder.decompress(chunk));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    private static void assertUnsupported(byte[] chunk, String expected) {
        UnsupportedCompressionException e = assertThrows(UnsupportedCompressionException.class,
                () -> BloscDecoder.decompress(chunk));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    @Test
    void aRawStreamChunkDecodes() {
        byte[] data = sample(100);
        assertArrayEquals(data, BloscDecoder.decompress(rawChunk(data, 4)));
    }

    @Test
    void whatFalconDoesNotReadIsUnsupported() {
        byte[] data = sample(100);
        byte[] version7 = rawChunk(data, 4);
        version7[0] = 7;
        assertUnsupported(version7, "version 7");

        byte[] vlBlocks = rawChunk(data, 4);
        vlBlocks[0] = 6;
        vlBlocks[30] = 0x01;
        assertUnsupported(vlBlocks, "variable-length blocks");

        byte[] dictionary = rawChunk(data, 4);
        dictionary[31] = 0x01;
        assertUnsupported(dictionary, "dictionary");

        byte[] lazy = rawChunk(data, 4);
        lazy[31] = 0x08;
        assertUnsupported(lazy, "lazy");

        byte[] instrumented = rawChunk(data, 4);
        instrumented[31] = (byte) 0x80;
        assertUnsupported(instrumented, "instrumented");

        byte[] userCodec = rawChunk(data, 4);
        userCodec[2] = (byte) (userCodec[2] | (6 << 5));
        userCodec[22] = (byte) 160;
        assertUnsupported(userCodec, "codec 160");

        byte[] superChunkCodec = rawChunk(data, 4);
        superChunkCodec[2] = (byte) (superChunkCodec[2] | (7 << 5));
        assertUnsupported(superChunkCodec, "super-chunk");

        byte[] bytedelta = rawChunk(data, 4);
        bytedelta[20] = 35;
        assertUnsupported(bytedelta, "bytedelta");

        byte[] userFilter = rawChunk(data, 4);
        userFilter[16] = (byte) 200;
        assertUnsupported(userFilter, "user-defined");
    }

    @Test
    void whatCBlosc2RefusesIsMalformed() {
        byte[] data = sample(100);
        for (int codec : new int[] {2, 5}) {
            byte[] reserved = rawChunk(data, 4);
            reserved[2] = (byte) (reserved[2] | (codec << 5));
            assertMalformed(reserved, "reserved Blosc2 internal codec " + codec);
        }
        byte[] undefinedFilter = rawChunk(data, 4);
        undefinedFilter[21] = 5;
        assertMalformed(undefinedFilter, "undefined Blosc2 filter 5");

        byte[] blocksize0 = rawChunk(data, 4);
        putLe32(blocksize0, 8, 0);
        assertMalformed(blocksize0, "block size 0");

        byte[] typeSize0 = rawChunk(data, 4);
        typeSize0[3] = 0;
        assertMalformed(typeSize0, "type size of zero");

        byte[] negative = rawChunk(data, 4);
        putLe32(negative, 4, -1);
        assertMalformed(negative, "negative size");

        byte[] shortExtended = Arrays.copyOf(rawChunk(data, 4), 20);
        putLe32(shortExtended, 12, 20);
        assertMalformed(shortExtended, "shorter than its 32-byte extended header");

        byte[] cbytesOver = rawChunk(data, 4);
        putLe32(cbytesOver, 12, cbytesOver.length + 1);
        assertMalformed(cbytesOver, "only " + cbytesOver.length + " are present");

        byte[] startInHeader = rawChunk(data, 4);
        putLe32(startInHeader, EXTENDED, 8);
        assertMalformed(startInHeader, "outside the chunk's streams");

        byte[] startPastEnd = rawChunk(data, 4);
        putLe32(startPastEnd, EXTENDED, startPastEnd.length);
        assertMalformed(startPastEnd, "outside the chunk's streams");

        byte[] streamTooLong = rawChunk(data, 4);
        putLe32(streamTooLong, EXTENDED + 4, data.length + 1);
        assertMalformed(streamTooLong, "payload is truncated");

        byte[] offsetsTruncated = header(5, 0x10, 4, 100, 10, EXTENDED + 8); // 10 blocks need 40 bytes of offsets
        assertMalformed(offsetsTruncated, "offset table is truncated");

        byte[] memcpyPadded = header(5, 0x02, 4, 8, 8, EXTENDED + 9);
        assertMalformed(memcpyPadded, "does not hold 8 bytes");

        byte[] unknownSpecial = special(5, 4, 64, 64, new byte[0]);
        assertMalformed(unknownSpecial, "unknown Blosc2 special value 5");
    }

    @Test
    void streamsCBlosc2RefusesAreMalformed() {
        // One block of 8 bytes in a single stream: csize, then a token.
        byte[] notARun = header(5, 0x10, 1, 8, 8, EXTENDED + 4 + 5);
        putLe32(notARun, EXTENDED, EXTENDED + 4);
        putLe32(notARun, EXTENDED + 4, -7);
        notARun[EXTENDED + 8] = 0x02; // a token without the run bit
        assertMalformed(notARun, "token 2 is not a run");

        byte[] run = notARun.clone();
        run[EXTENDED + 8] = 0x01;
        assertArrayEquals(new byte[] {7, 7, 7, 7, 7, 7, 7, 7}, BloscDecoder.decompress(run));

        byte[] wideRun = run.clone();
        putLe32(wideRun, EXTENDED + 4, -256);
        assertMalformed(wideRun, "does not name a byte");

        byte[] noToken = Arrays.copyOf(run, run.length - 1);
        putLe32(noToken, 12, noToken.length);
        assertMalformed(noToken, "token is truncated");

        // Split into 4 streams, a 10-byte block does not divide; c-blosc2 decodes such a chunk wrongly.
        byte[] ragged = header(5, 0, 4, 20, 10, EXTENDED + 8 + 2 * 4 * 4);
        putLe32(ragged, EXTENDED, EXTENDED + 8);
        putLe32(ragged, EXTENDED + 4, EXTENDED + 8 + 16);
        assertMalformed(ragged, "does not divide into 4 streams");
    }

    @Test
    void specialChunksFillTheirSize() {
        // A lazy flag on a special chunk is ignored, as c-blosc2 does: there are no blocks to fetch.
        byte[] lazyZeros = special(1, 4, 4096, 1024, new byte[0]);
        lazyZeros[31] |= 0x08;
        assertArrayEquals(new byte[4096], BloscDecoder.decompress(lazyZeros));

        // Uninitialised values: c-blosc2 leaves its output untouched; Falcon's is zeros.
        assertArrayEquals(new byte[4000], BloscDecoder.decompress(special(4, 8, 4000, 4000, new byte[0])));

        byte[] nan16 = special(2, 2, 64, 64, new byte[0]);
        assertMalformed(nan16, "NaN needs 4 or 8 bytes");
        byte[] nanRagged = special(2, 4, 62, 62, new byte[0]);
        assertMalformed(nanRagged, "not a whole number of 4-byte values");
        byte[] nanSplitValues = special(2, 8, 64, 12, new byte[0]); // 12-byte blocks cut float64s in two
        assertMalformed(nanSplitValues, "does not hold whole 8-byte values");

        byte[] value = special(3, 4, 64, 64, new byte[] {1, 2, 3});
        assertMalformed(value, "repeated value of 3 bytes does not fill 64 bytes");
        byte[] valueEmpty = special(3, 4, 0, 1, new byte[] {1, 2, 3, 4});
        assertMalformed(valueEmpty, "does not fill 0 bytes");
        assertArrayEquals(new byte[] {9, 8, 9, 8, 9, 8},
                BloscDecoder.decompress(special(3, 2, 6, 6, new byte[] {9, 8})));
    }

    @Test
    void aSpecialChunkClaimingGigabytesIsRefusedBeforeAllocating() {
        // 32 bytes that legitimately mean 2 GB of zeros: only the caller's maximum stops them.
        byte[] zeros = special(1, 4, Integer.MAX_VALUE - 8, 1 << 20, new byte[0]);
        CompressionFormatException e = assertThrows(CompressionFormatException.class,
                () -> BloscDecoder.decompress(zeros, 1 << 20));
        assertTrue(e.getMessage().contains("more than the 1048576 expected"), e.getMessage());
        assertEquals(Integer.MAX_VALUE - 8, BloscDecoder.decompressedSize(zeros));
    }

    @Test
    void theAlphaFormatsLastFilterSlotIsIgnored() {
        byte[] data = sample(64);
        byte[] alpha = rawChunk(data, 4);
        alpha[0] = 3;
        alpha[21] = 0x2a; // garbage the alpha series left in filters[5]
        assertArrayEquals(data, BloscDecoder.decompress(alpha));
        byte[] beta = alpha.clone();
        beta[0] = 4;
        assertUnsupported(beta, "filter 42 (a registered plugin)"); // from version 4 on, slot 5 is read
    }
}
