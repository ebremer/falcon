package com.ebremer.falcon.core.compress;

import static org.junit.jupiter.api.Assertions.fail;

import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.blosc.BloscDecoder;
import com.ebremer.falcon.core.compress.lzf.Lzf;
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
    void lzfSurvivesCorruption() {
        fuzz("lzf", vectors("lzf_vectors.txt", 3), stream -> Lzf.decompress(stream, 0, stream.length, 0));
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
