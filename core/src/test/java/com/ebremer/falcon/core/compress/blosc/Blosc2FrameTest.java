package com.ebremer.falcon.core.compress.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.Vectors;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Blosc2 contiguous frames and b2nd arrays, against frames hdf5plugin's Blosc2 filter (hdf5-blosc2, c-blosc2
 * 3.3.2) wrote or read back ({@code tools/fixtures/gen_blosc2_frame_vectors.py}): plain one-chunk frames, b2nd
 * frames of rank 2 to 4 with and without padding, special chunks, several chunks, the "caterva" metalayer, and
 * variable-length metalayers. Malformed frames are refused with typed exceptions.
 */
class Blosc2FrameTest {

    private record Vector(String name, String kind, byte[] frame, byte[] expected) {
    }

    private static List<Vector> vectors() {
        List<Vector> out = new ArrayList<>();
        for (String[] v : Vectors.read("blosc2_frame_vectors.txt")) {
            out.add(new Vector(v[0], v[1], Vectors.hex(v[2]), v.length > 3 ? Vectors.hex(v[3]) : new byte[0]));
        }
        return out;
    }

    /** What the filter reads from a frame: its b2nd array, or its first chunk. */
    private static byte[] decode(byte[] frame, int maxBytes) {
        Blosc2Frame f = Blosc2Frame.read(frame, maxBytes);
        B2ndArray array = B2ndArray.of(f);
        return array != null ? array.read() : f.chunk(0);
    }

    private static Vector vector(String name) {
        return vectors().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void decodesEveryReferenceFrame() {
        List<Vector> vectors = vectors();
        assertTrue(vectors.size() >= 24, "expected the full vector set, got " + vectors.size());
        int b2nd = 0;
        for (Vector v : vectors) {
            Blosc2Frame frame = Blosc2Frame.read(v.frame(), v.expected().length);
            B2ndArray array = B2ndArray.of(frame);
            assertEquals(v.kind().equals("b2nd"), array != null, v.name());
            b2nd += array != null ? 1 : 0;
            assertArrayEquals(v.expected(), array != null ? array.read() : frame.chunk(0), v.name());
            // One byte less than the data is refused before anything is decoded wrongly.
            assertThrows(CompressionFormatException.class, () -> decode(v.frame(), v.expected().length - 1), v.name());
        }
        assertTrue(b2nd >= 15, "b2nd frames: " + b2nd);
    }

    @Test
    void readsTheFrameAndArrayLayout() {
        Blosc2Frame frame = Blosc2Frame.read(vector("crafted_multichunk_padded_f4").frame(), 1 << 20);
        assertEquals(4, frame.typeSize());
        assertEquals(6, frame.chunkCount()); // a 10 x 7 array in 4 x 4 chunks
        assertEquals(Set.of("b2nd"), frame.metalayerNames());
        B2ndArray array = B2ndArray.of(frame);
        assertNotNull(array);
        assertEquals(2, array.ndim());
        assertArrayEquals(new long[] {10, 7}, array.shape());
        assertArrayEquals(new int[] {4, 4}, array.chunkShape());
        assertArrayEquals(new int[] {3, 2}, array.blockShape());
        assertEquals(0x97, frame.metalayer("b2nd")[0] & 0xff);
        frame.metalayer("b2nd")[0] = 0; // a copy
        assertEquals(0x97, frame.metalayer("b2nd")[0] & 0xff);
        assertNull(frame.metalayer("caterva"));

        Blosc2Frame other = Blosc2Frame.read(vector("crafted_other_metalayers_f4").frame(), 1 << 20);
        assertEquals(List.of("b2nd", "units", "history"), List.copyOf(other.metalayerNames()));
        assertArrayEquals("¦metres".getBytes(StandardCharsets.ISO_8859_1), other.metalayer("units"));
        assertNotNull(B2ndArray.of(Blosc2Frame.read(vector("crafted_caterva_metalayer_u1").frame(), 1 << 20)));
        Blosc2Frame plain = Blosc2Frame.read(vector("crafted_plain_two_chunks_i4").frame(), 1 << 20);
        assertNull(B2ndArray.of(plain));
        assertEquals(2, plain.chunkCount());
        assertEquals(1024, plain.chunk(1).length);
        assertThrows(CompressionFormatException.class, () -> plain.chunk(2));
        assertThrows(CompressionFormatException.class, () -> plain.chunk(-1));
    }

    // ---- malformed frames ---------------------------------------------------------------------------------

    private static byte[] mutate(String name, Consumer<byte[]> change) {
        byte[] frame = vector(name).frame().clone();
        change.accept(frame);
        return frame;
    }

    private static void refused(Class<? extends RuntimeException> type, byte[] frame) {
        assertThrows(type, () -> decode(frame, 1 << 20));
    }

    private static void putBe32(byte[] b, int off, int value) {
        b[off] = (byte) (value >>> 24);
        b[off + 1] = (byte) (value >>> 16);
        b[off + 2] = (byte) (value >>> 8);
        b[off + 3] = (byte) value;
    }

    private static void putBe64(byte[] b, int off, long value) {
        putBe32(b, off, (int) (value >>> 32));
        putBe32(b, off + 4, (int) value);
    }

    /**
     * Where the b2nd metalayer's content starts in a frame: the offset in the header's index (after 0xa4 "b2nd"
     * 0xd2), past the bin32 marker and length. For two dimensions the content is 0x97, version, ndim, then
     * shape at +5 and +14, chunkshape at +24 and +29, blockshape at +35 and +40, the dtype's format at +44,
     * its str32 marker at +45 and length at +46.
     */
    private static int b2ndContent(byte[] frame) {
        return Blosc2Frame.be32(frame, 100) + 5;
    }

    @Test
    void refusesMalformedFrames() {
        String b2nd = "crafted_multichunk_padded_f4";
        String plain = "crafted_plain_two_chunks_i4";
        refused(CompressionFormatException.class, Arrays.copyOf(vector(plain).frame(), 80));
        refused(CompressionFormatException.class, mutate(plain, f -> f[3] = 'X'));                  // magic
        refused(CompressionFormatException.class, Arrays.copyOf(vector(plain).frame(), vector(plain).frame().length - 1));
        refused(CompressionFormatException.class, mutate(plain, f -> f[f.length - 23] = 0));       // trailer marker
        refused(CompressionFormatException.class, mutate(plain, f -> putBe32(f, f.length - 22, 3))); // trailer length
        refused(CompressionFormatException.class, mutate(plain, f -> putBe32(f, 11, 40)));          // header length
        refused(CompressionFormatException.class, mutate(plain, f -> putBe32(f, 48, 0)));           // type size
        refused(CompressionFormatException.class, mutate(plain, f -> f[26] = 1));                   // sparse frame
        refused(UnsupportedCompressionException.class, mutate(plain, f -> f[25] = 0x14));           // version 4
        refused(CompressionFormatException.class, mutate(plain, f -> f[70] = 7));                   // filters
        refused(CompressionFormatException.class, mutate(plain, f -> putBe32(f, 58, -1)));          // chunk size
        refused(CompressionFormatException.class, mutate(plain, f -> putBe32(f, 58, 512)));         // 4 chunks, 2 offsets
        refused(CompressionFormatException.class, mutate(plain, f -> putBe64(f, 39, 1L << 40)));    // index outside
        refused(CompressionFormatException.class, mutate(plain, f -> f[91] = 0));                   // metalayer map
        refused(CompressionFormatException.class, mutate(plain, f -> putBe64(f, 30, 1L << 40)));    // too many chunks
        // More chunks than the frame may decode to bytes.
        assertThrows(CompressionFormatException.class, () -> Blosc2Frame.read(vector(plain).frame(), 1));

        refused(CompressionFormatException.class, mutate(b2nd, f -> f[b2ndContent(f) + 2] = 17));  // ndim
        refused(CompressionFormatException.class, mutate(b2nd, f -> f[b2ndContent(f) + 2] = -1));
        refused(CompressionFormatException.class, mutate(b2nd, f -> putBe64(f, b2ndContent(f) + 5, -10)));   // shape
        refused(CompressionFormatException.class, mutate(b2nd, f -> putBe32(f, b2ndContent(f) + 24, 0)));     // chunkshape
        refused(CompressionFormatException.class, mutate(b2nd, f -> putBe32(f, b2ndContent(f) + 35, 0)));     // blockshape
        refused(CompressionFormatException.class, mutate(b2nd, f -> putBe64(f, b2ndContent(f) + 5, 20)));     // 10 chunks
        refused(CompressionFormatException.class, mutate(b2nd, f -> putBe32(f, b2ndContent(f) + 35, 4)));     // chunk sizes
        refused(CompressionFormatException.class, mutate(b2nd, f -> f[b2ndContent(f) + 45] = 0));             // dtype marker
        // A metalayer cut short: its content ends before the dtype's declared length.
        refused(CompressionFormatException.class, mutate(b2nd, f -> putBe32(f, b2ndContent(f) + 46, 400)));
        // Padded to far more than the array: a 1 x 1 array in a 1 x 4096 block.
        byte[] padded = mutate(b2nd, f -> {
            putBe64(f, b2ndContent(f) + 5, 1);
            putBe64(f, b2ndContent(f) + 14, 1);
            putBe32(f, b2ndContent(f) + 35, 1);
            putBe32(f, b2ndContent(f) + 40, 4096);
        });
        refused(UnsupportedCompressionException.class, padded);
        // The array larger than the frame may decode to.
        assertThrows(CompressionFormatException.class,
                () -> B2ndArray.of(Blosc2Frame.read(vector(b2nd).frame(), 279)).read());
    }

    @Test
    void refusesMalformedSpecialChunks() {
        String zeros = "crafted_plain_special_zeros_f4";
        String nan = "crafted_plain_special_nan_f8";
        int index = vector(zeros).frame().length; // the index chunk sits before the 35-byte trailer
        byte[] unknown = mutate(zeros, f -> f[index - 35 - 1] = (byte) 0x80); // no special bit
        refused(CompressionFormatException.class, unknown);
        refused(CompressionFormatException.class, mutate(zeros, f -> {                          // not whole values
            putBe64(f, 30, 402);
            putBe32(f, 58, 402);
        }));
        refused(CompressionFormatException.class, mutate(nan, f -> putBe32(f, 48, 2)));            // NaN of 2 bytes
        refused(CompressionFormatException.class, mutate(zeros, f -> putBe32(f, 58, 0)));         // variable size
        assertThrows(CompressionFormatException.class,
                () -> Blosc2Frame.read(vector(nan).frame(), 1 << 20).chunk(0, 159));
        assertArrayEquals(new byte[400], decode(vector(zeros).frame(), 400));
    }
}
