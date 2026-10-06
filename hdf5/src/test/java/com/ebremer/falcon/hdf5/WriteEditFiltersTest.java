package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Writing into datasets of a file whose filters carry parameters of their own (P2 WF10): szip of either
 * coding, n-bit (atomic and compound), scale-offset (integer and decimal-scaled float), partial edge chunks
 * stored unfiltered; and new datasets with szip's nearest-neighbour coding and a user block (P2 WF7).
 * (libhdf5 reads the changed files in {@code WriterInteropExport}.)
 */
class WriteEditFiltersTest {

    @TempDir
    Path dir;

    private Path copy(String fixture) throws IOException {
        Path file = dir.resolve(fixture);
        Files.copy(Fixtures.path(fixture), file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    private static int[] range(int from, int n) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = from + i;
        }
        return values;
    }

    /** szip datasets libhdf5 wrote, nearest-neighbour and entropy coded, both byte orders. */
    @Test
    void writesIntoSzipDatasets() throws IOException {
        Path file = copy("szip.h5");
        float[] f32;
        int[] multi;
        try (Hdf5File h5 = Hdf5File.open(file)) {
            f32 = h5.root().dataset("f32_nn").readFloats();
            multi = h5.root().dataset("i32_nn_multi").readInts();
        }
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("f32_nn").write(new long[] {10}, new long[] {3}, new float[] {-1.5f, 2.25f, 1e9f});
            w.dataset("i32_nn_multi").write(new long[] {250}, new long[] {10}, range(-5000, 10));
            w.dataset("be_i16_nn").write(new long[] {0}, new long[] {2}, new short[] {-32768, 32767});
            w.dataset("i16_ec").write(new long[] {255}, new long[] {1}, new short[] {-7});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            f32[10] = -1.5f;
            f32[11] = 2.25f;
            f32[12] = 1e9f;
            assertArrayEquals(f32, h5.root().dataset("f32_nn").readFloats());
            System.arraycopy(range(-5000, 10), 0, multi, 250, 10);
            assertArrayEquals(multi, h5.root().dataset("i32_nn_multi").readInts());
            int[] be = h5.root().dataset("be_i16_nn").readInts();
            assertEquals(-32768, be[0]);
            assertEquals(32767, be[1]);
            assertEquals(-7, h5.root().dataset("i16_ec").readInts()[255]);
        }
    }

    /** Scale-offset datasets libhdf5 wrote: integers (fill value, big-endian, 64-bit) and decimal-scaled floats. */
    @Test
    void writesIntoScaleOffsetDatasets() throws IOException {
        Path file = copy("scaleoffset.h5");
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("i4_fill").write(new long[] {3}, new long[] {4}, new int[] {-1, 1_000_000, 5, -1});
            w.dataset("be_i4").write(new long[] {60}, new long[] {8}, range(-4, 8));
            w.dataset("i8_neg").write(new long[] {18}, new long[] {2}, new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
            w.dataset("u8_big").write(new long[] {0}, new long[] {1}, new java.math.BigInteger[] {
                new java.math.BigInteger("18446744073709551615")});
            w.dataset("f4_d2").write(new long[] {4}, new long[] {4}, new float[] {1.25f, -3.5f, 100.01f, 0});
            w.dataset("f8_d3_fill").write(new long[] {0}, new long[] {3}, new double[] {2.125, -7.5, 0.001});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            int[] fill = root.dataset("i4_fill").readInts();
            assertArrayEquals(new int[] {-1, 1_000_000, 5, -1}, Arrays.copyOfRange(fill, 3, 7));
            assertArrayEquals(range(-4, 8), Arrays.copyOfRange(root.dataset("be_i4").readInts(), 60, 68));
            long[] neg = root.dataset("i8_neg").readLongs();
            assertEquals(Long.MIN_VALUE, neg[18]);
            assertEquals(Long.MAX_VALUE, neg[19]);
            assertEquals(new java.math.BigInteger("18446744073709551615"),
                    ((java.math.BigInteger[]) root.dataset("u8_big").read())[0]);
            float[] f4 = root.dataset("f4_d2").readFloats();
            assertArrayEquals(new float[] {1.25f, -3.5f, 100.01f, 0}, Arrays.copyOfRange(f4, 4, 8), 0.005f);
            double[] f8 = root.dataset("f8_d3_fill").readDoubles();
            assertArrayEquals(new double[] {2.125, -7.5, 0.001}, Arrays.copyOf(f8, 3), 0.0005);
        }
    }

    /** n-bit datasets libhdf5 wrote: an atomic one, a compound of two, and one at full precision. */
    @Test
    void writesIntoNbitDatasets() throws IOException {
        Path nbit = copy("nbit_data.h5");
        try (Hdf5Writer w = Hdf5Writer.open(nbit)) {
            w.dataset("nbit_u").write(new long[] {17}, new long[] {3}, new long[] {65535, 0, 1234});
        }
        try (Hdf5File h5 = Hdf5File.open(nbit)) {
            assertArrayEquals(new long[] {65535, 0, 1234}, Arrays.copyOfRange(h5.root().dataset("nbit_u").readLongs(), 17, 20));
        }
        Path compound = copy("compound_nbit.h5");
        try (Hdf5Writer w = Hdf5Writer.open(compound)) {
            w.dataset("c").write(new long[] {1}, new long[] {2},
                    Map.of("a", new short[] {-2048, 2047}, "b", new long[] {1_048_575, 3}));
        }
        try (Hdf5File h5 = Hdf5File.open(compound)) {
            Dataset c = h5.root().dataset("c");
            assertArrayEquals(new int[] {-2048, 2047}, Arrays.copyOfRange(c.member("a").readInts(), 1, 3));
            assertArrayEquals(new long[] {1_048_575, 3}, Arrays.copyOfRange(c.member("b").readLongs(), 1, 3));
        }
        Path edge = copy("filter_edge.h5");
        try (Hdf5Writer w = Hdf5Writer.open(edge)) {
            w.dataset("nbit_full_be").write(new long[] {11}, new long[] {1}, new int[] {Integer.MIN_VALUE});
        }
        try (Hdf5File h5 = Hdf5File.open(edge)) {
            assertEquals(Integer.MIN_VALUE, h5.root().dataset("nbit_full_be").readInts()[11]);
        }
    }

    /**
     * Partial edge chunks stored unfiltered stay so in a dataset of fixed size (its fixed-array index keeps
     * the layout's flag); a dataset that grows is indexed by a B-tree, whose layout cannot say so, so its
     * every chunk is filtered, and the flag goes.
     */
    @Test
    void keepsPartialEdgeChunksUnfiltered() throws IOException {
        Path file = copy("partial_edges.h5");
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("fixed").write(new long[] {0}, new long[] {1}, new int[] {-100});
            w.dataset("fixed").write(new long[] {9}, new long[] {1}, new int[] {-9});
            w.dataset("plane").write(new long[] {4, 6}, new long[] {1, 1}, new int[] {-34});
            w.dataset("grows").append(new int[] {10, 11, 12});
        }
        byte[] bytes = Files.readAllBytes(file);
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset fixed = h5.root().dataset("fixed");
            int[] expected = range(0, 10);
            expected[0] = -100;
            expected[9] = -9;
            assertArrayEquals(expected, fixed.readInts());
            DataLayout.Chunked layout = (DataLayout.Chunked) fixed.dataLayout();
            assertTrue(layout.dontFilterPartialBoundChunks());
            for (ChunkRecord chunk : fixed.chunkIndex(layout).all()) {
                if (chunk.offset()[0] == 8) { // the partial chunk: elements 8 and 9, stored as they are
                    ByteBuffer raw = ByteBuffer.wrap(bytes, (int) chunk.address(), 8).order(ByteOrder.LITTLE_ENDIAN);
                    assertEquals(16, chunk.size());
                    assertEquals(8, raw.getInt());
                    assertEquals(-9, raw.getInt());
                }
            }
            int[] plane = range(0, 35);
            plane[34] = -34;
            assertArrayEquals(plane, h5.root().dataset("plane").readInts());
            assertTrue(((DataLayout.Chunked) h5.root().dataset("plane").dataLayout()).dontFilterPartialBoundChunks());
            Dataset grows = h5.root().dataset("grows");
            assertArrayEquals(range(0, 13), grows.readInts());
            assertFalse(((DataLayout.Chunked) grows.dataLayout()).dontFilterPartialBoundChunks());
        }
    }

    /**
     * Data in external raw files (an External File List): written into the slots that hold it, across two
     * files, found as the reader finds them; a file the access policy refuses (outside the HDF5 file's
     * directory) is refused for writing too.
     */
    @Test
    void writesIntoExternalRawData() throws IOException {
        Path file = copy("external.h5");
        copy("external_a.bin");
        copy("external_b.bin");
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("ext").write(new long[] {4}, new long[] {4}, new int[] {-4, -5, -6, -7});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(new int[] {0, 1, 2, 3, -4, -5, -6, -7, 8, 9, 10, 11}, h5.root().dataset("ext").readInts());
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("external_b.bin"))).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(-6, b.getInt(16)); // the second slot starts 16 bytes into its file
        assertEquals(-7, b.getInt(20));
        Path paths = copy("external_paths.h5");
        copy("external_unlimited.raw");
        try (Hdf5Writer w = Hdf5Writer.open(paths)) {
            w.dataset("unlimited").write(new long[] {5}, new long[] {1}, new int[] {-5});
            org.junit.jupiter.api.Assertions.assertThrows(HdfUnsupportedException.class,
                    () -> w.dataset("parent").write(new long[] {0}, new long[] {1}, new int[] {1}));
        }
        try (Hdf5File h5 = Hdf5File.open(paths)) {
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, -5}, h5.root().dataset("unlimited").readInts());
        }
    }

    /** New datasets with szip's nearest-neighbour coding, of several block sizes and types (P2 WF7). */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void writesSzipNearestNeighbour(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("nn.h5");
        int[] smooth = new int[1000];
        double[] wave = new double[1000];
        for (int i = 0; i < smooth.length; i++) {
            smooth[i] = 1000 + i / 3;
            wave[i] = Math.sin(i / 50.0);
        }
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            w.createDataset("nn", Datatype.int32(), 1000).chunked(256).szip(Hdf5Writer.SzipCoding.NEAREST_NEIGHBOUR, 32)
                    .write(smooth);
            w.createDataset("nn_be", Datatype.int16().withByteOrder(java.nio.ByteOrder.BIG_ENDIAN), 1000).chunked(500)
                    .shuffle().szip(Hdf5Writer.SzipCoding.NEAREST_NEIGHBOUR, 16).write(smooth);
            w.createDataset("wave", Datatype.float64(), 1000).chunked(1000).szip(Hdf5Writer.SzipCoding.ENTROPY, 16)
                    .write(wave);
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(smooth, h5.root().dataset("nn").readInts());
            assertArrayEquals(smooth, h5.root().dataset("nn_be").readInts());
            assertArrayEquals(wave, h5.root().dataset("wave").readDoubles());
            Filter szip = h5.root().dataset("nn").filters().getFirst();
            assertEquals(32, szip.clientData()[1]);
            assertTrue((szip.clientData()[0] & 32) != 0, "nearest-neighbour coding");
            assertTrue(h5.root().dataset("nn").storageSize() < 1000 * 4 / 4, "smooth data shrinks under NN coding");
        }
    }

    /** A user block: the given bytes, zero-padded to 512, then the HDF5 file, which reads and changes as usual. */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void writesAUserBlock(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("user.h5");
        byte[] header = "MATLAB 7.3 MAT-file, Platform: Java, Created by: Falcon".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try (Hdf5Writer w = Hdf5Writer.create(file, format, header)) {
            w.intDataset("x", range(0, 4), new long[] {4});
        }
        byte[] bytes = Files.readAllBytes(file);
        assertArrayEquals(header, Arrays.copyOf(bytes, header.length));
        assertEquals(0, bytes[511]);
        assertEquals((byte) 0x89, bytes[512]);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.intDataset("y", range(5, 2), new long[] {2});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(range(0, 4), h5.root().dataset("x").readInts());
            assertArrayEquals(range(5, 2), h5.root().dataset("y").readInts());
        }
        assertArrayEquals(header, Arrays.copyOf(Files.readAllBytes(file), header.length));
        byte[] large = new byte[600];
        large[599] = 1;
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("large.h5"), format, large)) {
            w.intDataset("x", range(0, 1), new long[] {1});
        }
        assertEquals((byte) 0x89, Files.readAllBytes(dir.resolve("large.h5"))[1024]);
    }

    /** A user block that holds the HDF5 signature where readers look for one is refused. */
    @Test
    void refusesAUserBlockThatLooksLikeHdf5() {
        byte[] block = new byte[1024];
        System.arraycopy(new byte[] {(byte) 0x89, 'H', 'D', 'F', '\r', '\n', 0x1a, '\n'}, 0, block, 512, 8);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Hdf5Writer.create(dir.resolve("bad.h5"), Hdf5Writer.Format.LATEST, block));
    }
}
