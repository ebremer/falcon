package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.data.ChunkIndex;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A chunk index of a file read through a {@link RangeReader} is read ahead (P2 PF12): the reads of libhdf5's
 * own files of thousands of chunks, with indexes of every type, return what they return from memory, however
 * large the pages, however few of them are kept, and however many requests are made at once. (Where each
 * node of a B-tree lies, and what is asked for when, is {@code ChunkBTreeV1Test}'s.)
 */
@Timeout(300)
class ChunkIndexReadAheadTest {

    /** The version 1 B-trees, of two and three levels, and the indexes of the latest format beside them. */
    private static final String DATASETS = """
            big_index_old.h5, bt1
            big_index_old.h5, bt1_gz
            big_index_old.h5, bt1_3d
            big_index.h5, fa
            big_index.h5, bt2_gz
            ea_paged.h5, d
            paged_sparse.h5, fa_paged_sparse
            """;

    /** What reads of a dataset return: a box, a strided selection, points, the chunks of a part, and all of it. */
    private static List<Object> reads(Hdf5File h5, String name) {
        Dataset dataset = h5.root().dataset(name);
        long[] dims = dataset.dataspace().dimensions();
        int rank = dims.length;
        List<Object> out = new ArrayList<>();
        long[] at = new long[rank];
        long[] count = new long[rank];
        for (int d = 0; d < rank; d++) {
            at[d] = dims[d] / 3;
            count[d] = Math.max(1, dims[d] / 4);
        }
        out.add(dataset.select(at, count).readInts());
        long[] start = new long[rank];
        long[] stride = new long[rank];
        long[] counts = new long[rank];
        long[] block = new long[rank];
        Arrays.fill(block, 1);
        for (int d = 0; d < rank; d++) {
            stride[d] = d == rank - 1 ? 3 : 5;
            counts[d] = Math.max(1, (dims[d] - 1) / stride[d]);
            start[d] = 1;
        }
        out.add(dataset.select(start, stride, counts, block).readInts());
        long[][] points = new long[40][rank];
        for (int i = 0; i < points.length; i++) {
            for (int d = 0; d < rank; d++) {
                points[i][d] = (i * 37L + d * 11L) % dims[d];
            }
        }
        out.add(dataset.selectPoints(points).readInts());
        if (dataset.dataLayout() instanceof DataLayout.Chunked chunked) {
            // The chunks a read of a part finds, and the index read whole.
            StringBuilder found = new StringBuilder();
            for (ChunkRecord chunk : dataset.chunkIndex(chunked).overlapping(at, count, dims)) {
                found.append(Arrays.toString(chunk.offset())).append('@').append(chunk.address()).append('+')
                        .append(chunk.size()).append(' ');
            }
            out.add(found.toString());
            out.add(dataset.chunkIndex(chunked).size());
        }
        out.add(dataset.readInts());
        return out;
    }

    private static void assertSame(List<Object> expected, List<Object> actual, String what) {
        assertEquals(expected.size(), actual.size(), what);
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i) instanceof int[] ints) {
                assertArrayEquals(ints, (int[]) actual.get(i), what + ", read " + i);
            } else {
                assertEquals(expected.get(i), actual.get(i), what + ", read " + i);
            }
        }
    }

    /** An index of a grid of 100 x 200 cells of 10 x 10 elements, which notes what is asked of it. */
    private static ChunkIndex noting(List<String> calls, long[] dims) {
        ChunkLookup lookup = new ChunkLookup() {
            @Override
            public ChunkRecord find(long[] cell) {
                calls.add("find " + Arrays.toString(cell));
                return new ChunkRecord(new long[] {cell[0] * 10, cell[1] * 10}, 1000 + cell[0] * 200 + cell[1], 5, 0);
            }

            @Override
            public void ahead(long[][] cells) {
                calls.add("ahead " + Arrays.deepToString(cells));
            }
        };
        return ChunkIndex.of(new int[] {10, 10}, dims, lookup, () -> {
            calls.add("whole");
            return List.of();
        }, bytes -> { });
    }

    @Test
    void aReadOfSeveralCellsSaysWhichBeforeItLooksThemUp() {
        long[] dims = {1000, 2000};
        List<String> calls = new ArrayList<>();
        ChunkIndex index = noting(calls, dims);

        // A box over rows 2 and 3, columns 4 to 6, of the grid: the hint first, then the cells in their order.
        assertEquals(6, index.overlapping(new long[] {25, 40}, new long[] {10, 25}, dims).size());
        assertEquals(List.of("ahead [[2, 3], [4, 5, 6]]", "find [2, 4]", "find [2, 5]", "find [2, 6]", "find [3, 4]",
                "find [3, 5]", "find [3, 6]"), calls);

        // A strided selection's cells.
        calls.clear();
        assertEquals(6, index.inGrid(new long[][] {{1, 50}, {0, 7, 190}}).size());
        assertEquals("ahead [[1, 50], [0, 7, 190]]", calls.get(0));
        assertEquals(7, calls.size());

        // One cell is a path of its own: nothing to say of it.
        calls.clear();
        index.overlapping(new long[] {995, 1995}, new long[] {5, 5}, dims);
        index.inGrid(new long[][] {{7}, {9}});
        index.at(new long[] {3, 3});
        assertEquals(List.of("find [99, 199]", "find [7, 9]", "find [3, 3]"), calls);

        // More than an eighth of the grid is read whole, with no cell looked up, and none hinted.
        calls.clear();
        index.overlapping(new long[] {0, 0}, new long[] {600, 500}, dims);
        assertEquals(List.of("whole"), calls);
    }

    @Test
    void aHintNamesNoMoreOfADimensionThanItNamesCells() {
        long[] dims = {30, 10L * 100_000_000};   // 3 cells by a hundred million
        List<String> hints = new ArrayList<>();
        int[] finds = new int[1];
        ChunkLookup lookup = new ChunkLookup() {
            @Override
            public ChunkRecord find(long[] cell) {
                finds[0]++;
                return null;
            }

            @Override
            public void ahead(long[][] cells) {
                hints.add(cells[0].length + " x " + cells[1].length + ", from " + cells[0][0] + ", " + cells[1][0] + " to "
                        + cells[0][cells[0].length - 1] + ", " + cells[1][cells[1].length - 1]);
            }
        };
        ChunkIndex index = ChunkIndex.of(new int[] {10, 10}, dims, lookup, List::of, bytes -> { });
        // Two rows of 10,000 cells from the 70th on: the hint is of the first 4,096 of each row.
        index.overlapping(new long[] {10, 700}, new long[] {20, 100_000}, dims);
        assertEquals(List.of("2 x " + ChunkLookup.AHEAD_CELLS + ", from 1, 70 to 2, " + (70 + ChunkLookup.AHEAD_CELLS - 1)), hints);
        assertEquals(20_000, finds[0]);
    }

    @ParameterizedTest
    @CsvSource(textBlock = DATASETS)
    void readsAreTheSameHoweverTheIndexIsReadAhead(String file, String name) throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path(file));
        List<Object> expected;
        try (Hdf5File h5 = Hdf5File.open(bytes)) {
            expected = reads(h5, name);
        }
        for (int page : new int[] {512, 4096, OpenOptions.DEFAULT_READER_PAGE_SIZE}) {
            for (long cache : new long[] {0, 8L * page, 64L * page, OpenOptions.DEFAULT_READER_CACHE_SIZE}) {
                for (int requests : new int[] {1, 2, 8, 64}) {
                    AtomicInteger inFlight = new AtomicInteger();
                    AtomicInteger most = new AtomicInteger();
                    RangeReader store = new RangeReader() {
                        @Override
                        public long size() {
                            return bytes.length;
                        }

                        @Override
                        public void read(long position, ByteBuffer destination) {
                            most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                            destination.put(bytes, (int) position, destination.remaining());
                            inFlight.decrementAndGet();
                        }
                    };
                    String what = name + ": pages of " + page + ", " + cache + " bytes kept, " + requests + " requests at once";
                    OpenOptions options = OpenOptions.defaults().readerPageSize(page).readerCacheSize(cache)
                            .readerRequests(requests);
                    try (Hdf5File h5 = Hdf5File.open(store, options)) {
                        assertSame(expected, reads(h5, name), what);
                    }
                    assertTrue(most.get() <= requests, what + ": " + most.get() + " in flight");
                }
            }
        }
    }
}
