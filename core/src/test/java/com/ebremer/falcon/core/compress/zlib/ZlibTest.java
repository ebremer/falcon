package com.ebremer.falcon.core.compress.zlib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * zlib streams as HDF5's deflate filter and numcodecs' zlib codec use them: java.util.zip's streams at every
 * level, read back within a bound, and refused as zlib's inflate refuses them (truncated, a failed check, a
 * preset dictionary), with bytes after the stream ignored.
 */
class ZlibTest {

    private static byte[] sample(int length) {
        byte[] data = new byte[length];
        Random random = new Random(length);
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i % 97 < 50 ? i / 7 : random.nextInt(4));
        }
        return data;
    }

    @Test
    void roundTripsAtEveryLevel() {
        for (int level = 0; level <= 9; level++) {
            for (int length : new int[] {0, 1, 63, 64, 1000, 200_000}) {
                byte[] data = sample(length);
                byte[] stream = Zlib.compress(data, level);
                assertArrayEquals(data, Zlib.decompress(stream, 0, stream.length, length), level + "/" + length);
            }
        }
    }

    @Test
    void compressesAsDeflaterDoes() {
        byte[] data = sample(50_000);
        Deflater deflater = new Deflater(6);
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[100_000];
        int n = deflater.deflate(buffer);
        deflater.end();
        assertArrayEquals(Arrays.copyOf(buffer, n), Zlib.compress(data, 6));
    }

    @Test
    void boundsWhatAStreamMayDecodeTo() {
        byte[] stream = Zlib.compress(new byte[100_000], 9); // a few hundred bytes for 100 KB
        assertArrayEquals(new byte[100_000], Zlib.decompress(stream, 0, stream.length, 100_000));
        CompressionFormatException e = assertThrows(CompressionFormatException.class,
                () -> Zlib.decompress(stream, 0, stream.length, 99_999));
        assertTrue(e.getMessage().contains("more than the 99999 bytes"), e.getMessage());
    }

    @Test
    void refusesWhatInflateRefuses() {
        byte[] data = sample(5000);
        byte[] stream = Zlib.compress(data, 6);
        // ends early: its last bytes, the Adler-32, or half of it
        for (int cut : new int[] {1, 4, stream.length / 2}) {
            assertThrows(CompressionFormatException.class,
                    () -> Zlib.decompress(stream, 0, stream.length - cut, 10_000), "cut " + cut);
        }
        byte[] badCheck = stream.clone();
        badCheck[badCheck.length - 1] ^= 1;
        assertThrows(CompressionFormatException.class, () -> Zlib.decompress(badCheck, 0, badCheck.length, 10_000));
        Deflater deflater = new Deflater();
        deflater.setDictionary(new byte[] {1, 2, 3});
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[10_000];
        int n = deflater.deflate(buffer);
        deflater.end();
        assertThrows(CompressionFormatException.class, () -> Zlib.decompress(buffer, 0, n, 10_000));
        assertThrows(CompressionFormatException.class, () -> Zlib.decompress(new byte[] {1, 2, 3}, 0, 3, 10));
    }

    @Test
    void ignoresBytesAfterTheStream() {
        byte[] data = sample(3000);
        byte[] stream = Zlib.compress(data, 1);
        byte[] padded = Arrays.copyOf(stream, stream.length + 20);
        Arrays.fill(padded, stream.length, padded.length, (byte) 0x5a);
        assertArrayEquals(data, Zlib.decompress(padded, 0, padded.length, data.length));
        byte[] shifted = new byte[stream.length + 7];
        System.arraycopy(stream, 0, shifted, 5, stream.length);
        assertArrayEquals(data, Zlib.decompress(shifted, 5, stream.length, data.length));
    }
}
