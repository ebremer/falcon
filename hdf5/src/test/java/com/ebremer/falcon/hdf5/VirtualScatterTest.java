package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Virtual datasets whose elements scatter over their sources (P2 PF7): a read takes from a source only the
 * elements it needs, not the box that bounds them, for a strided source, a source of another shape, and a
 * strided or point selection of the virtual dataset; and every such read gives what the whole read does.
 */
class VirtualScatterTest {

    private static final OpenOptions IN_FIXTURES = OpenOptions.defaults()
            .externalFileAccess(ExternalFileAccess.sameDirectory());

    private static int[] range(int from, int step, int count) {
        int[] a = new int[count];
        for (int i = 0; i < count; i++) {
            a[i] = from + i * step;
        }
        return a;
    }

    @Test
    void readsScatteredSourceElements() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_scatter.h5"))) {
            assertArrayEquals(range(0, 1024, 64), h5.root().dataset("strided").readInts());
            Dataset reshaped = h5.root().dataset("reshaped");
            assertArrayEquals(range(7, 512, 128), reshaped.select(new long[] {0, 7}, new long[] {128, 1}).readInts());
            assertArrayEquals(range(3 * 512 + 100, 1, 50),
                    reshaped.select(new long[] {3, 100}, new long[] {1, 50}).readInts());
            Dataset same = h5.root().dataset("same");
            assertArrayEquals(range(5, 1024, 64), same.select(new long[] {5}, new long[] {1024}, new long[] {64},
                    new long[] {1}).readInts());
            assertArrayEquals(new int[] {65535, 0, 4096, 4096},
                    same.selectPoints(new long[][] {{65535}, {0}, {4096}, {4096}}).readInts());
            // A strided selection of a grid whose source is flat: its rows' blocks, in order.
            assertArrayEquals(new int[] {2 * 512 + 10, 2 * 512 + 11, 2 * 512 + 30, 2 * 512 + 31,
                                         66 * 512 + 10, 66 * 512 + 11, 66 * 512 + 30, 66 * 512 + 31},
                    reshaped.select(new long[] {2, 10}, new long[] {64, 20}, new long[] {2, 2}, new long[] {1, 2})
                            .readInts());
        }
    }

    /** The source elements a read needs are read, not their bounding box: a few chunks instead of most. */
    @ParameterizedTest
    @CsvSource(textBlock = """
            strided
            column
            selection
            """)
    void readsOnlyTheChunksItNeeds(String read) throws IOException {
        Function<Hdf5File, int[]> scattered = switch (read) {
            case "strided" -> h5 -> h5.root().dataset("strided").readInts();
            case "column" -> h5 -> h5.root().dataset("reshaped").select(new long[] {0, 7}, new long[] {128, 1}).readInts();
            default -> h5 -> h5.root().dataset("same").select(new long[] {0}, new long[] {1024}, new long[] {64},
                    new long[] {1}).readInts();
        };
        long needed = bytesRead(scattered);
        // What reading the box that bounds those source elements would read.
        long box = bytesRead(h5 -> h5.root().dataset("flat").select(new long[] {0}, new long[] {64513}).readInts());
        long opening = bytesRead(h5 -> new int[] {h5.root().dataset("flat").datatype().size()});
        assertTrue((needed - opening) * 3 < box - opening, read + " read " + (needed - opening)
                + " bytes past opening the file; the bounding box, " + (box - opening));
    }

    private static long bytesRead(Function<Hdf5File, int[]> read) throws IOException {
        try (FileChannel channel = FileChannel.open(Fixtures.path("vds_scatter.h5"), StandardOpenOption.READ)) {
            RangeReader inner = RangeReader.of(channel);
            AtomicLong bytes = new AtomicLong();
            RangeReader counting = new RangeReader() {
                @Override
                public long size() throws IOException {
                    return inner.size();
                }

                @Override
                public void read(long position, ByteBuffer destination) throws IOException {
                    bytes.addAndGet(destination.remaining());
                    inner.read(position, destination);
                }
            };
            try (Hdf5File h5 = Hdf5File.open(counting, OpenOptions.defaults().readerPageSize(512).chunkCacheSize(0))) {
                read.apply(h5);
            }
            return bytes.get();
        }
    }

    /** Every strided or point selection of every virtual dataset reads what the whole read holds there. */
    @ParameterizedTest
    @CsvSource(textBlock = """
            vds.h5, vds
            vds.h5, vds_gap
            vds.h5, vds_cols
            vds.h5, vds_step
            vds_byteorder.h5, i4
            vds_unlimited.h5, rows
            vds_unlimited.h5, cols
            vds_unlimited.h5, printf
            vds_unlimited.h5, printf_names
            vds_unlimited.h5, floored
            vds_scatter.h5, strided
            vds_scatter.h5, reshaped
            """)
    void selectionsReadWhatTheWholeReadHolds(String file, String name) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file), IN_FIXTURES)) {
            Dataset dataset = h5.root().dataset(name);
            int[] whole = dataset.readInts();
            long[] dims = dataset.dataspace().dimensions();
            int rank = dims.length;
            // Every other element (from 1), in blocks of one, in every dimension.
            long[] start = new long[rank];
            long[] stride = new long[rank];
            long[] count = new long[rank];
            long[] block = new long[rank];
            boolean empty = false;
            for (int d = 0; d < rank; d++) {
                start[d] = Math.min(1, dims[d] - 1);
                stride[d] = 2;
                count[d] = (dims[d] - start[d] + 1) / 2;
                block[d] = 1;
                empty |= dims[d] == 0;
            }
            if (empty) {
                return;
            }
            int[] expected = new int[(int) java.util.Arrays.stream(count).reduce(1, (a, b) -> a * b)];
            long[] at = new long[rank];
            for (int i = 0; i < expected.length; i++) {
                long rest = i;
                long flat = 0;
                for (int d = rank - 1; d >= 0; d--) {
                    at[d] = start[d] + (rest % count[d]) * stride[d];
                    rest /= count[d];
                }
                for (int d = 0; d < rank; d++) {
                    flat = flat * dims[d] + at[d];
                }
                expected[i] = whole[(int) flat];
            }
            assertArrayEquals(expected, dataset.select(start, stride, count, block).readInts(), name);
            // Points, in no order, one twice.
            long[][] points = new long[5][rank];
            int[] pointValues = new int[5];
            for (int i = 0; i < points.length; i++) {
                long flat = (whole.length - 1L) * ((i * 3) % 5) / 4;
                if (i == 4) {
                    flat = (whole.length - 1L) * 3 / 4; // the same as point 1
                }
                long rest = flat;
                for (int d = rank - 1; d >= 0; d--) {
                    points[i][d] = rest % dims[d];
                    rest /= dims[d];
                }
                pointValues[i] = whole[(int) flat];
            }
            assertArrayEquals(pointValues, dataset.selectPoints(points).readInts(), name + " points");
        }
    }
}
