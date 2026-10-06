package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Selections (P2 A4): regular hyperslabs with gaps and point selections, read through every layout and
 * chunk index, and every reader on a selection.
 */
class SelectionTest {

    /** Fixtures holding every layout, chunk index, filter, and datatype class Falcon reads. */
    static Stream<String> fixtures() {
        return Stream.of("data_contiguous.h5", "chunked_data.h5", "chunk_indexes.h5", "implicit.h5",
                "paged_sparse.h5", "chunk_maxshape.h5", "layout_v4.h5", "filtered_single.h5",
                "unwritten_earliest.h5", "unwritten_latest.h5", "external.h5", "vds.h5", "vds_default.h5",
                "vds_latest.h5", "typed.h5", "numeric.h5", "nbit_data.h5", "scaleoffset.h5", "szip.h5",
                "plugin_filters.h5", "legacy_layouts.h5", "userblock_v0.h5", "compound_nbit.h5",
                "vlen_data.h5", "datatypes.h5", "filter_edge.h5", "ea_paged.h5", "vds_unlimited.h5",
                "blosc2.h5");
    }

    /**
     * Every dataset's random strided and point selections read the elements a whole read holds, byte for
     * byte, in the selection's order: through every layout, chunk index, and filter in the fixtures.
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void selectionsReadWhatTheWholeDatasetHolds(String fixture) throws IOException {
        Random random = new Random(fixture.hashCode());
        int checked = 0;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture), OpenOptions.defaults()
                .externalFileAccess(ExternalFileAccess.sameDirectory()))) {
            for (Dataset dataset : datasets(h5.root())) {
                long[] dims;
                byte[] whole;
                try {
                    dims = dataset.dataspace().dimensions();
                    if (dataset.dataspace().elementCount() > 400_000) {
                        continue;
                    }
                    whole = dataset.readRawBytes();
                } catch (HdfException e) {
                    continue; // a dataset Falcon does not read whole is not this test's concern
                }
                int size = dataset.datatype().size();
                for (int trial = 0; trial < 6; trial++) {
                    checkStrided(dataset, dims, whole, size, random);
                    checkPoints(dataset, dims, whole, size, random);
                }
                checked++;
            }
        }
        assertTrue(checked > 0, "no dataset checked in " + fixture);
    }

    private static void checkStrided(Dataset dataset, long[] dims, byte[] whole, int size, Random random) {
        int rank = dims.length;
        long[] start = new long[rank];
        long[] stride = new long[rank];
        long[] count = new long[rank];
        long[] block = new long[rank];
        long[][] indices = new long[rank][];
        for (int d = 0; d < rank; d++) {
            if (dims[d] == 0) {
                return;
            }
            block[d] = 1 + random.nextLong(Math.max(1, dims[d] / 3));
            stride[d] = block[d] + random.nextLong(1 + dims[d] / 2);
            start[d] = random.nextLong(dims[d] - Math.min(block[d], dims[d]) + 1);
            block[d] = Math.min(block[d], dims[d] - start[d]);
            long most = (dims[d] - start[d] - block[d]) / stride[d] + 1;
            count[d] = 1 + random.nextLong(most);
            List<Long> list = new ArrayList<>();
            for (long b = 0; b < count[d]; b++) {
                for (long j = 0; j < block[d]; j++) {
                    list.add(start[d] + b * stride[d] + j);
                }
            }
            indices[d] = list.stream().mapToLong(Long::longValue).toArray();
        }
        Selection selection = dataset.select(start, stride, count, block);
        long[] shape = new long[rank];
        for (int d = 0; d < rank; d++) {
            shape[d] = indices[d].length;
        }
        assertArrayEquals(shape, selection.shape(), dataset.path());
        String what = dataset.path() + " start=" + Arrays.toString(start) + " stride=" + Arrays.toString(stride)
                + " count=" + Arrays.toString(count) + " block=" + Arrays.toString(block);
        assertArrayEquals(pick(whole, dims, size, product(indices)), selection.readRawBytes(), what);
    }

    private static void checkPoints(Dataset dataset, long[] dims, byte[] whole, int size, Random random) {
        for (long d : dims) {
            if (d == 0) {
                return;
            }
        }
        long[][] points = new long[random.nextInt(12)][dims.length];
        for (long[] point : points) {
            for (int d = 0; d < dims.length; d++) {
                point[d] = random.nextLong(dims[d]);
            }
        }
        if (points.length > 2) {
            points[points.length - 1] = points[0].clone(); // a point may repeat
        }
        Selection selection = dataset.selectPoints(points);
        assertArrayEquals(new long[] {points.length}, selection.shape());
        assertArrayEquals(pick(whole, dims, size, points), selection.readRawBytes(),
                dataset.path() + " points " + Arrays.deepToString(points));
    }

    /** Every combination of the per-dimension indices, in row-major order. */
    private static long[][] product(long[][] indices) {
        int rank = indices.length;
        int n = 1;
        for (long[] axis : indices) {
            n *= axis.length;
        }
        long[][] out = new long[n][rank];
        for (int i = 0; i < n; i++) {
            int rest = i;
            for (int d = rank - 1; d >= 0; d--) {
                out[i][d] = indices[d][rest % indices[d].length];
                rest /= indices[d].length;
            }
        }
        return out;
    }

    /** The bytes of the elements at {@code coordinates} of row-major data of shape {@code dims}. */
    private static byte[] pick(byte[] whole, long[] dims, int size, long[][] coordinates) {
        byte[] out = new byte[coordinates.length * size];
        for (int i = 0; i < coordinates.length; i++) {
            long flat = 0;
            for (int d = 0; d < dims.length; d++) {
                flat = flat * dims[d] + coordinates[i][d];
            }
            System.arraycopy(whole, (int) (flat * size), out, i * size, size);
        }
        return out;
    }

    private static List<Dataset> datasets(Group group) {
        List<Dataset> out = new ArrayList<>();
        for (Hdf5Object child : group.children()) {
            if (child instanceof Dataset dataset) {
                out.add(dataset);
            } else if (child instanceof Group sub && !sub.path().equals(group.path())) {
                out.addAll(datasets(sub));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the selection's shape

    @Test
    void stridedAndPointSelectionsDescribeThemselves() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("typed.h5"))) {
            Dataset table = h5.root().dataset("table"); // 6 x 5
            Selection strided = table.select(new long[] {1, 0}, new long[] {2, 3}, new long[] {3, 2}, new long[] {1, 2});
            assertFalse(strided.isRectangular());
            assertArrayEquals(new long[] {3, 4}, strided.shape());
            assertArrayEquals(new long[] {1, 0}, strided.offset());
            assertEquals(12, strided.elementCount());
            assertArrayEquals(new int[] {10, 11, 13, 14, 30, 31, 33, 34, 50, 51, 53, 54}, strided.member("a").readInts());

            // Blocks that abut (stride equal to block) make one block: a rectangular selection.
            Selection abutting = table.select(new long[] {2, 1}, new long[] {2, 1}, new long[] {2, 3}, new long[] {2, 1});
            assertTrue(abutting.isRectangular());
            assertArrayEquals(new long[] {4, 3}, abutting.shape());
            assertArrayEquals(new long[] {2, 1}, abutting.offset());

            Selection points = table.selectPoints(new long[][] {{4, 1}, {0, 3}, {4, 1}});
            assertFalse(points.isRectangular());
            assertArrayEquals(new long[] {3}, points.shape());
            assertArrayEquals(new long[] {0, 1}, points.offset()); // the corner of the bounding box
            assertArrayEquals(new int[] {41, 3, 41}, points.member("a").readInts());

            Selection none = table.selectPoints(new long[0][]);
            assertEquals(0, none.elementCount());
            assertArrayEquals(new int[0], none.member("a").readInts());
            assertEquals(0, table.select(new long[] {0, 0}, null, new long[] {0, 2}, null).elementCount());
        }
    }

    @Test
    void invalidSelectionsAreRefused() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("typed.h5"))) {
            Dataset table = h5.root().dataset("table"); // 6 x 5
            long[] zero = {0, 0};
            assertThrows(IllegalArgumentException.class, () -> table.select(new long[] {0}, null, new long[] {1}, null));
            assertThrows(IllegalArgumentException.class, () -> table.select(zero, new long[] {0, 1}, new long[] {2, 2}, null));
            assertThrows(IllegalArgumentException.class, () -> table.select(zero, null, new long[] {2, 2}, new long[] {0, 1}));
            assertThrows(IllegalArgumentException.class, () -> table.select(new long[] {-1, 0}, null, new long[] {1, 1}, null));
            // Blocks that overlap, and a selection that reaches past the extent.
            assertThrows(IllegalArgumentException.class, () -> table.select(zero, new long[] {1, 1}, new long[] {2, 2}, new long[] {2, 1}));
            assertThrows(IllegalArgumentException.class, () -> table.select(zero, new long[] {3, 1}, new long[] {3, 5}, new long[] {1, 1}));
            assertThrows(IllegalArgumentException.class, () -> table.select(zero, new long[] {Long.MAX_VALUE, 1},
                    new long[] {2, 1}, null));
            assertThrows(IllegalArgumentException.class, () -> table.selectPoints(new long[][] {{6, 0}}));
            assertThrows(IllegalArgumentException.class, () -> table.selectPoints(new long[][] {{0, -1}}));
            assertThrows(IllegalArgumentException.class, () -> table.selectPoints(new long[][] {{0}}));
        }
    }

    // ------------------------------------------------------------------ every reader on a selection

    @Test
    void selectionsHaveEveryReader() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vlen_data.h5"))) {
            for (Dataset dataset : datasets(h5.root())) {
                long n = dataset.dataspace().elementCount();
                long[][] reversed = new long[(int) n][];
                for (int i = 0; i < n; i++) {
                    reversed[i] = new long[] {n - 1 - i};
                }
                Selection points = dataset.selectPoints(reversed);
                Object whole = dataset.read();
                Object picked = points.read();
                assertEquals(java.lang.reflect.Array.getLength(whole), java.lang.reflect.Array.getLength(picked));
                for (int i = 0; i < n; i++) {
                    Object a = java.lang.reflect.Array.get(whole, (int) (n - 1 - i));
                    Object b = java.lang.reflect.Array.get(picked, i);
                    assertTrue(java.util.Objects.deepEquals(a, b), dataset.path() + " element " + i);
                }
                if (whole instanceof String[] strings) {
                    assertEquals(strings[(int) n - 1], points.readStrings()[0]);
                } else if (whole instanceof int[][] rows) {
                    assertArrayEquals(rows[(int) n - 1], points.readVlenInts()[0]);
                    assertArrayEquals(Arrays.stream(rows[(int) n - 1]).asDoubleStream().toArray(), points.readVlenDoubles()[0]);
                }
            }
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("references.h5"))) {
            Dataset refs = h5.root().dataset("refs");
            Hdf5Object[] objects = refs.selectPoints(new long[][] {{2}, {0}}).readObjectReferences();
            assertArrayEquals(new int[] {0, 1, 2}, ((Dataset) objects[0]).readInts());
            assertEquals(h5.root().dataset("target_a").objectHeaderAddress(), objects[1].objectHeaderAddress());
            Selection[] regions = h5.root().dataset("rrefs").select(new long[] {1}, new long[] {1}).readRegionReferences();
            assertArrayEquals(new int[] {6, 7, 8, 11, 12, 13}, regions[0].readInts());
            assertInstanceOf(Hdf5Object[].class, refs.select(new long[] {0}, new long[] {2}).read());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("refs_revised.h5"))) {
            Attribute[] attributes = h5.root().dataset("attributes").selectPoints(new long[][] {{1}}).readAttributeReferences();
            assertEquals("title", attributes[0].name());
        }
    }

    // ------------------------------------------------------------------ reading only what is selected

    @Test
    void stridedAndPointSelectionsReadOnlyTheirChunks(@TempDir Path dir) throws IOException {
        // 2000 x 100 ints in chunks of 10 rows, deflated: 200 chunks.
        int rows = 2000;
        int columns = 100;
        int[] data = new int[rows * columns];
        Random random = new Random(7);
        for (int i = 0; i < data.length; i++) {
            data[i] = random.nextInt(); // incompressible, so each chunk keeps its size
        }
        Path file = dir.resolve("chunks.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.intChunkedDataset("grid", data, new long[] {rows, columns}, new long[] {10, columns}).deflate(1);
        }
        long fileSize = java.nio.file.Files.size(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            CountingReader reader = new CountingReader(RangeReader.of(channel));
            try (Hdf5File h5 = Hdf5File.open(reader, OpenOptions.defaults().readerPageSize(4096))) {
                Dataset grid = h5.root().dataset("grid");
                grid.select(new long[] {0, 0}, new long[] {1, 1}).readInts(); // reads the chunk index
                reader.bytes.set(0);
                // Every 100th row: 20 rows in 20 of the 200 chunks.
                int[] sampled = grid.select(new long[] {5, 0}, new long[] {100, 1}, new long[] {20, columns}, null).readInts();
                for (int r = 0; r < 20; r++) {
                    for (int c = 0; c < columns; c++) {
                        assertEquals(data[(5 + 100 * r) * columns + c], sampled[r * columns + c]);
                    }
                }
                assertTrue(reader.bytes.get() < fileSize / 5, reader.bytes.get() + " of " + fileSize + " bytes read");
                reader.bytes.set(0);
                int[] points = grid.selectPoints(new long[][] {{1999, 99}, {0, 0}, {1000, 50}}).readInts();
                assertArrayEquals(new int[] {data[1999 * columns + 99], data[0], data[1000 * columns + 50]}, points);
                assertTrue(reader.bytes.get() < fileSize / 20, reader.bytes.get() + " of " + fileSize + " bytes read");
            }
        }
    }

    /**
     * Elements copied a run at a time (P2 PF8): blocks along the last dimension that cross chunks, points
     * listed along a row across a chunk boundary, repeated and out of order, all as the whole read has them.
     */
    @Test
    void runsOfElementsAreCopiedAcrossChunks(@TempDir Path dir) throws IOException {
        int rows = 40;
        int columns = 50;
        int[] data = new int[rows * columns];
        for (int i = 0; i < data.length; i++) {
            data[i] = i * 7 - 3;
        }
        Path file = dir.resolve("runs.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.intChunkedDataset("grid", data, new long[] {rows, columns}, new long[] {6, 7}).deflate(1);
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset grid = h5.root().dataset("grid");
            // Blocks of 5 x 9 every 7 x 11 (crossing chunks of 6 x 7), touching blocks (stride = block), and
            // single elements a stride apart.
            long[][][] hyperslabs = {
                {{1, 3}, {7, 11}, {5, 4}, {5, 9}},
                {{0, 2}, {3, 6}, {13, 8}, {3, 6}},
                {{2, 1}, {3, 5}, {12, 9}, {1, 1}},
            };
            for (long[][] h : hyperslabs) {
                int[] got = grid.select(h[0], h[1], h[2], h[3]).readInts();
                List<Integer> expected = new ArrayList<>();
                for (long i = 0; i < h[2][0] * h[3][0]; i++) {
                    long r = h[0][0] + i / h[3][0] * h[1][0] + i % h[3][0];
                    for (long j = 0; j < h[2][1] * h[3][1]; j++) {
                        long c = h[0][1] + j / h[3][1] * h[1][1] + j % h[3][1];
                        expected.add(data[(int) (r * columns + c)]);
                    }
                }
                assertArrayEquals(expected.stream().mapToInt(Integer::intValue).toArray(), got, Arrays.deepToString(h));
            }
            long[][] points = {{3, 4}, {3, 5}, {3, 6}, {3, 7}, {3, 8}, {3, 8}, {39, 49}, {3, 3}, {20, 0}, {20, 1}};
            int[] expected = new int[points.length];
            for (int i = 0; i < points.length; i++) {
                expected[i] = data[(int) (points[i][0] * columns + points[i][1])];
            }
            assertArrayEquals(expected, grid.selectPoints(points).readInts());
        }
    }

    /**
     * Points of a dataset whose chunk grid has more cells than a {@code long} can number with the points
     * (2^40 x 2^40, chunks of one) are still ordered by chunk, each read once.
     */
    @Test
    void pointsOfAVastGridAreOrderedByChunk(@TempDir Path dir) throws IOException {
        long huge = 1L << 40;
        Path file = dir.resolve("vast.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.createDataset("sparse", com.ebremer.falcon.hdf5.datatype.Datatype.int32(), 2, 2).chunked(1, 1)
                    .maxShape(Hdf5Writer.UNLIMITED, Hdf5Writer.UNLIMITED).extend(huge, huge)
                    .write(new long[] {huge - 1, 0}, new long[] {1, 2}, new int[] {5, 6})
                    .write(new long[] {0, huge - 1}, new long[] {1, 1}, new int[] {7});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            long[][] points = {{0, huge - 1}, {huge - 1, 1}, {12345, 67890}, {huge - 1, 0}, {0, huge - 1}};
            assertArrayEquals(new int[] {7, 6, 0, 5, 7}, h5.root().dataset("sparse").selectPoints(points).readInts());
        }
    }

    @Test
    void stridedSelectionsOfContiguousDataReadOnlyTheirRuns(@TempDir Path dir) throws IOException {
        int n = 1 << 20;
        double[] data = new double[n];
        for (int i = 0; i < n; i++) {
            data[i] = i * 0.5;
        }
        Path file = dir.resolve("contiguous.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleDataset("line", data, new long[] {n});
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            CountingReader reader = new CountingReader(RangeReader.of(channel));
            try (Hdf5File h5 = Hdf5File.open(reader, OpenOptions.defaults().readerPageSize(4096))) {
                Dataset line = h5.root().dataset("line");
                line.datatype();
                line.dataspace();
                reader.bytes.set(0);
                // 16 runs of 4 values, 64 Ki values apart.
                double[] runs = line.select(new long[] {3}, new long[] {1 << 16}, new long[] {16}, new long[] {4}).readDoubles();
                for (int b = 0; b < 16; b++) {
                    for (int j = 0; j < 4; j++) {
                        assertEquals(data[3 + (b << 16) + j], runs[b * 4 + j]);
                    }
                }
                assertTrue(reader.bytes.get() <= 16 * 2 * 4096, reader.bytes.get() + " bytes read");
            }
        }
    }

    /** A reader that counts the bytes it is asked for. */
    static final class CountingReader implements RangeReader {
        final RangeReader inner;
        final AtomicLong bytes = new AtomicLong();

        CountingReader(RangeReader inner) {
            this.inner = inner;
        }

        @Override
        public long size() throws IOException {
            return inner.size();
        }

        @Override
        public void read(long position, ByteBuffer destination) throws IOException {
            bytes.addAndGet(destination.remaining());
            inner.read(position, destination);
        }
    }
}
