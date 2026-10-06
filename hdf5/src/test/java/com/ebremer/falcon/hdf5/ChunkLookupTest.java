package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Chunks looked up one at a time (P2 PF5): a small read finds its chunks in the file's chunk index, of
 * every type libhdf5 writes, without reading the rest of the index, and finds what reading the whole index
 * finds. The fixtures are libhdf5's, of thousands of chunks each.
 */
class ChunkLookupTest {

    /** Every type of index, of thousands of chunks: paged arrays, sparse ones, and B-trees of several levels. */
    private static final String DATASETS = """
            big_index.h5, fa
            big_index.h5, implicit
            big_index.h5, bt2
            big_index.h5, bt2_gz
            big_index_old.h5, bt1
            big_index_old.h5, bt1_gz
            big_index_old.h5, bt1_3d
            ea_paged.h5, d
            paged_sparse.h5, ea_sparse
            paged_sparse.h5, fa_paged
            paged_sparse.h5, fa_paged_gz
            paged_sparse.h5, fa_paged_sparse
            """;

    @ParameterizedTest
    @CsvSource(textBlock = DATASETS)
    void findsEachChunkAsTheWholeIndexDoes(String file, String name) throws IOException {
        int[] whole;
        long[] dims;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file))) {
            Dataset dataset = h5.root().dataset(name);
            whole = dataset.readInts();
            dims = dataset.dataspace().dimensions();
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file))) { // a fresh file: nothing read yet
            Dataset dataset = h5.root().dataset(name);
            long[] ones = new long[dims.length];
            java.util.Arrays.fill(ones, 1);
            for (long flat : samples(whole.length)) {
                long[] at = coordinates(flat, dims);
                assertEquals(whole[(int) flat], dataset.select(at, ones).readInts()[0], name + " at " + flat);
            }
            // Points (each chunk looked up for them) and a strided selection (the cells it falls in).
            long[][] points = new long[8][];
            int[] expected = new int[8];
            for (int i = 0; i < points.length; i++) {
                long flat = (whole.length - 1) * (long) (7 - i) / 7;
                points[i] = coordinates(flat, dims);
                expected[i] = whole[(int) flat];
            }
            assertArrayEquals(expected, dataset.selectPoints(points).readInts(), name);
            long[] start = new long[dims.length];
            long[] stride = new long[dims.length];
            long[] count = new long[dims.length];
            long[] block = new long[dims.length];
            java.util.Arrays.fill(block, 1);
            for (int d = 0; d < dims.length; d++) {
                stride[d] = Math.max(1, dims[d] / 3);
                count[d] = Math.min(dims[d], 3);
                start[d] = dims[d] - 1 - (count[d] - 1) * stride[d];
            }
            int[] strided = dataset.select(start, stride, count, block).readInts();
            int k = 0;
            long[] at = new long[dims.length];
            for (long cell = 0; cell < strided.length; cell++) {
                long rest = cell;
                for (int d = dims.length - 1; d >= 0; d--) {
                    at[d] = start[d] + (rest % count[d]) * stride[d];
                    rest /= count[d];
                }
                assertEquals(whole[(int) flat(at, dims)], strided[k++], name + " strided");
            }
        }
    }

    /** A one-element read reads a few of the index's entries or nodes: far less than reading it all. */
    @ParameterizedTest
    @CsvSource(textBlock = """
            big_index.h5, fa
            big_index.h5, bt2
            big_index_old.h5, bt1
            ea_paged.h5, d
            paged_sparse.h5, ea_sparse
            """)
    void aSmallReadReadsLittleOfTheIndex(String file, String name) throws IOException {
        long index = bytesRead(file, name, dataset -> dataset.storageSize()); // the whole index, and nothing else
        long one = bytesRead(file, name, dataset -> {
            long[] dims = dataset.dataspace().dimensions();
            long[] middle = new long[dims.length];
            long[] ones = new long[dims.length];
            for (int d = 0; d < dims.length; d++) {
                middle[d] = dims[d] / 2;
                ones[d] = 1;
            }
            return dataset.select(middle, ones).readInts()[0];
        });
        long opening = bytesRead(file, name, dataset -> dataset.datatype().size() + dataset.dataspace().rank());
        assertTrue((one - opening) * 4 < index - opening, name + ": a one-element read read " + (one - opening)
                + " bytes past opening the dataset; the whole index, " + (index - opening));
    }

    /** The bytes a fresh file's read of a dataset reads through a reader of 512-byte pages. */
    private static long bytesRead(String file, String name, java.util.function.ToLongFunction<Dataset> read)
            throws IOException {
        Path path = Fixtures.path(file);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
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
            try (Hdf5File h5 = Hdf5File.open(counting, OpenOptions.defaults().readerPageSize(512))) {
                read.applyAsLong(h5.root().dataset(name));
            }
            return bytes.get();
        }
    }

    /** Positions spread over {@code n} elements: the first, the last, and some between. */
    private static long[] samples(long n) {
        int count = (int) Math.min(n, 40);
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = count == 1 ? 0 : (n - 1) * i / (count - 1);
        }
        return out;
    }

    private static long[] coordinates(long flat, long[] dims) {
        long[] at = new long[dims.length];
        for (int d = dims.length - 1; d >= 0; d--) {
            at[d] = flat % dims[d];
            flat /= dims[d];
        }
        return at;
    }

    private static long flat(long[] at, long[] dims) {
        long flat = 0;
        for (int d = 0; d < dims.length; d++) {
            flat = flat * dims[d] + at[d];
        }
        return flat;
    }
}
