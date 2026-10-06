package com.ebremer.falcon.core.compress;

import static org.junit.jupiter.api.Assertions.fail;

import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.blosc.B2ndArray;
import com.ebremer.falcon.core.compress.blosc.Blosc2Frame;
import com.ebremer.falcon.core.compress.blosc.BloscDecoder;
import com.ebremer.falcon.core.compress.bzip2.Bzip2Decoder;
import com.ebremer.falcon.core.compress.lzf.Lzf;
import com.ebremer.falcon.core.compress.sz.SzDecoder;
import com.ebremer.falcon.core.compress.zfp.ZfpDecoder;
import com.ebremer.falcon.core.compress.zfp.ZfpHeader;
import com.ebremer.falcon.core.compress.zlib.Zlib;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Corrupt-input hardening for the codecs. Truncated, bit-flipped, and random compressed streams must
 * fail as {@link CompressionFormatException} or {@link UnsupportedCompressionException} &mdash; never as
 * a hang, an OutOfMemoryError from a bogus length, or a raw JVM error. The entropy-coded zstd gets the
 * most attention: a frame drives table construction and bit-level state machines from untrusted bytes.
 */
class CompressionRobustnessTest {

    /**
     * Anything a codec is allowed to throw for bad input: its typed errors. Anything else &mdash; an
     * OutOfMemoryError, a NullPointerException, an index error leaking from a parser &mdash; is a defect.
     */
    private static void assertHandled(String what, Runnable action) {
        try {
            action.run();
        } catch (CompressionFormatException | UnsupportedCompressionException e) {
            return; // a typed, contained failure
        } catch (Throwable t) {
            fail(what + " threw an unacceptable " + t.getClass().getName() + ": " + t);
        }
        // Completing without throwing is fine too: a corrupt stream may still decode to plausible bytes.
    }

    /** The hex field {@code column} of each vector in {@code resource}. */
    private static List<byte[]> vectors(String resource, int column) {
        return Vectors.read(resource).stream().map(v -> Vectors.hex(v[column])).toList();
    }

    /** Truncates every stream at 32 points and flips bits in it 3000 times, decoding each. */
    private static void fuzz(String codec, List<byte[]> streams, Consumer<byte[]> decode) {
        for (byte[] stream : streams) {
            for (int length = 0; length < stream.length; length += Math.max(1, stream.length / 32)) {
                byte[] cut = Arrays.copyOf(stream, length);
                assertHandled("truncated " + codec + " stream of " + length + " bytes", () -> decode.accept(cut));
            }
        }
        Random random = new Random(4242);
        for (int trial = 0; trial < 3000; trial++) {
            byte[] stream = streams.get(random.nextInt(streams.size())).clone();
            if (stream.length == 0) {
                continue;
            }
            int flips = 1 + random.nextInt(3);
            for (int i = 0; i < flips; i++) {
                int at = random.nextInt(stream.length);
                stream[at] ^= (byte) (1 << random.nextInt(8));
            }
            assertHandled("bit-flipped " + codec + " stream", () -> decode.accept(stream));
        }
    }

    @Test
    void zlibSurvivesCorruption() {
        List<byte[]> streams = new java.util.ArrayList<>();
        for (int level : new int[] {1, 6, 9}) {
            byte[] data = new byte[4000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 61 < 30 ? i / 9 : i * 31);
            }
            streams.add(Zlib.compress(data, level));
        }
        fuzz("zlib", streams, stream -> Zlib.decompress(stream, 0, stream.length, 4000));
    }

    @Test
    void zstdSurvivesCorruption() {
        fuzz("zstd", vectors("zstd_vectors.txt", 3), ZstdDecoder::decompress);
    }

    @Test
    void zstdSurvivesRandomBytes() {
        Random random = new Random(99);
        for (int trial = 0; trial < 2000; trial++) {
            byte[] junk = new byte[random.nextInt(64)];
            random.nextBytes(junk);
            if (junk.length >= 4) { // give it the magic number so it gets past the first check
                junk[0] = (byte) 0x28;
                junk[1] = (byte) 0xb5;
                junk[2] = (byte) 0x2f;
                junk[3] = (byte) 0xfd;
            }
            assertHandled("random zstd-magic input", () -> ZstdDecoder.decompress(junk));
        }
    }

    @Test
    void bloscSurvivesCorruption() {
        fuzz("blosc", vectors("blosc_vectors.txt", 6), BloscDecoder::decompress);
    }

    @Test
    void blosc2SurvivesCorruption() {
        // A 32-byte special-value chunk may rightly claim 2 GB, so a flipped size is bounded as callers bound it.
        fuzz("blosc2", vectors("blosc2_vectors.txt", 6), stream -> BloscDecoder.decompress(stream, 16 << 20));
    }

    @Test
    void bzip2SurvivesCorruption() {
        // A flipped bit mostly fails a CRC; the rest must fail on structure, never past the bound.
        List<byte[]> streams = Vectors.read("bzip2_vectors.txt").stream().filter(v -> !v[7].equals("-"))
                .map(v -> Vectors.hex(v[7])).toList();
        fuzz("bzip2", streams, stream -> Bzip2Decoder.decompress(stream, 0, stream.length, 1 << 20));
    }

    @Test
    void bzip2SurvivesRandomBytes() {
        Random random = new Random(307);
        for (int trial = 0; trial < 2000; trial++) {
            byte[] junk = new byte[4 + random.nextInt(200)];
            random.nextBytes(junk);
            junk[0] = 'B'; // past the header, so the block structure is what gets tested
            junk[1] = 'Z';
            junk[2] = 'h';
            junk[3] = (byte) ('1' + random.nextInt(9));
            if (trial % 2 == 0 && junk.length >= 10) { // and often past the block magic too
                byte[] magic = {0x31, 0x41, 0x59, 0x26, 0x53, 0x59};
                System.arraycopy(magic, 0, junk, 4, magic.length);
            }
            assertHandled("random bzip2-header input", () -> Bzip2Decoder.decompress(junk, 0, junk.length, 1 << 20));
        }
    }

    @Test
    void blosc2FramesSurviveCorruption() {
        // Each frame read as the HDF5 filter reads it: its b2nd array, or its first chunk.
        fuzz("blosc2 frame", vectors("blosc2_frame_vectors.txt", 2), stream -> {
            Blosc2Frame frame = Blosc2Frame.read(stream, 16 << 20);
            B2ndArray array = B2ndArray.of(frame);
            if (array != null) {
                array.read();
            } else {
                frame.chunk(0);
            }
        });
    }

    @Test
    void lzfSurvivesCorruption() {
        fuzz("lzf", vectors("lzf_vectors.txt", 3), stream -> Lzf.decompress(stream, 0, stream.length, 0));
    }

    @Test
    void zfpSurvivesCorruption() {
        // Each stream is [header length][header][compressed field], so the header is corrupted too.
        List<byte[]> streams = Vectors.read("zfp_vectors.txt").stream().map(v -> {
            byte[] header = Vectors.hex(v[1]);
            byte[] data = Vectors.hex(v[2]);
            byte[] stream = new byte[1 + header.length + data.length];
            stream[0] = (byte) header.length;
            System.arraycopy(header, 0, stream, 1, header.length);
            System.arraycopy(data, 0, stream, 1 + header.length, data.length);
            return stream;
        }).toList();
        fuzz("zfp", streams, stream -> {
            if (stream.length == 0) {
                return;
            }
            int headerLength = Math.min(stream[0] & 0xff, stream.length - 1);
            ZfpHeader header = ZfpHeader.read(stream, 1, headerLength);
            ZfpDecoder.decompress(header, stream, 1 + headerLength, stream.length - 1 - headerLength, 1 << 20);
            ZfpDecoder.decompress(stream, 1, stream.length - 1, 1 << 20); // as one stream, header first
        });
    }

    @Test
    void szSurvivesCorruption() {
        // Both the streams as stored (mostly zstd) and their SZ bytes beneath, which the decoder also reads
        // uncompressed: corrupting those drives the SZ parser, Huffman trees, and predictors themselves.
        for (String[] v : Vectors.read("sz_vectors.txt")) {
            if (!v[0].contains("_2d_") && !v[0].contains("_3d_")) {
                continue;
            }
            byte[] stream = Vectors.hex(v[7]);
            List<byte[]> streams = new java.util.ArrayList<>(List.of(stream));
            if (stream.length > 4 && stream[0] == 0x28 && stream[1] == (byte) 0xb5) {
                streams.add(ZstdDecoder.decompress(stream));
            }
            int type = Integer.parseInt(v[1]);
            long[] r = {Long.parseLong(v[2]), Long.parseLong(v[3]), Long.parseLong(v[4]), Long.parseLong(v[5]),
                Long.parseLong(v[6])};
            fuzz("sz " + v[0], streams, s -> SzDecoder.decompress(type, s, 0, s.length, r[0], r[1], r[2], r[3], r[4],
                    1 << 20));
        }
    }

    @Test
    void bitshuffleSurvivesCorruption() {
        for (String[] v : Vectors.read("bitshuffle_vectors.txt")) {
            int elementSize = Integer.parseInt(v[1]);
            int elements = Integer.parseInt(v[2]);
            int blockSize = Integer.parseInt(v[3]);
            Bitshuffle.BlockCodec codec = switch (v[4]) {
                case "lz4" -> Bitshuffle.BlockCodec.LZ4;
                case "zstd" -> Bitshuffle.BlockCodec.ZSTD;
                default -> null;
            };
            if (codec != null) {
                fuzz("bitshuffle " + v[0], List.of(Vectors.hex(v[6])), stream -> Bitshuffle.decompress(
                        stream, 0, stream.length, elements, elementSize, blockSize, codec));
            }
        }
    }
}
