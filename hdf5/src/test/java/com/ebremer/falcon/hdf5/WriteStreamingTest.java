package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Streaming writes (P2 WF1): data goes to the file as it is written, datasets grow, and files pass
 * 2 GB. (libhdf5 reads the same cases in {@code WriterInteropExport}.)
 */
class WriteStreamingTest {

    @TempDir
    Path dir;

    /** The size of the hidden temporary file the writer streams into. */
    private long streamed() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".tmp")).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void dataGoesToTheFileAsItIsWritten(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("stream.h5");
        int rows = 1000;
        int columns = 1000;
        Random random = new Random(1);
        int[] expected = new int[rows * columns];
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            Hdf5Writer.DatasetWriter d = w.createDataset("grid", Datatype.int32(), rows, columns).chunked(100, 1000);
            for (int r = 0; r < rows; r += 100) {
                int[] block = new int[100 * columns];
                for (int i = 0; i < block.length; i++) {
                    block[i] = random.nextInt();
                }
                System.arraycopy(block, 0, expected, r * columns, block.length);
                d.write(new long[] {r, 0}, new long[] {100, columns}, block);
                // Each block of rows completes its chunks, which are in the file already.
                assertTrue(streamed() >= (r + 100L) * columns * 4, "streamed " + streamed());
            }
        }
        assertTrue(Files.size(file) > 4_000_000);
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(expected, h5.root().dataset("grid").readInts());
        }
    }

    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void datasetsGrowAndChunksAreWrittenAgain(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("grow.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            Hdf5Writer.DatasetWriter d = w.createDataset("rows", Datatype.float64(), 0, 3).chunked(4, 3)
                    .maxShape(Hdf5Writer.UNLIMITED, 3).fillValue(-1.0).deflate(6);
            for (int i = 0; i < 10; i++) {
                d.append(new double[] {i, i + 0.5, -i});
            }
            assertArrayEquals(new long[] {10, 3}, d.shape());
            d.write(new long[] {1, 0}, new long[] {2, 1}, new double[] {100, 200}); // rewrites a stored chunk
            d.extend(13, 3);                                                          // three unwritten rows
            assertThrows(IllegalArgumentException.class, () -> d.extend(12, 3));      // never shrinks
            assertThrows(IllegalStateException.class, () -> d.deflate(1));            // configured before writing
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset rows = h5.root().dataset("rows");
            assertArrayEquals(new long[] {13, 3}, rows.dataspace().dimensions());
            assertTrue(rows.dataspace().isUnlimited(0));
            double[] values = rows.readDoubles();
            for (int i = 0; i < 10; i++) {
                double first = i == 1 ? 100 : i == 2 ? 200 : i;
                assertArrayEquals(new double[] {first, i + 0.5, -i}, Arrays.copyOfRange(values, 3 * i, 3 * i + 3), "row " + i);
            }
            assertArrayEquals(new double[] {-1, -1, -1, -1, -1, -1, -1, -1, -1}, Arrays.copyOfRange(values, 30, 39));
        }
    }

    /** Thousands of chunks: a version-1 B-tree of three levels (64 entries a node). */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void manyChunksMakeADeepIndex(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("many.h5");
        int[] values = new int[5000];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 3;
        }
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            Hdf5Writer.DatasetWriter d = w.createDataset("many", Datatype.int32(), 0).chunked(1).maxShape(Hdf5Writer.UNLIMITED);
            d.append(values);
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset many = h5.root().dataset("many");
            assertArrayEquals(values, many.readInts());
            assertArrayEquals(new int[] {9, 14997}, many.selectPoints(new long[][] {{3}, {4999}}).readInts());
        }
    }

    @Test
    void contiguousDataIsWrittenInPlace() throws IOException {
        Path file = dir.resolve("contiguous.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            Hdf5Writer.DatasetWriter d = w.createDataset("v", Datatype.int16(), 4, 5).fillValue(9);
            d.write(new long[] {1, 1}, new long[] {2, 3}, new short[] {1, 2, 3, 4, 5, 6});
            d.writeRaw(new long[] {3, 4}, new long[] {1, 1}, new byte[] {(byte) 0xFF, 0x7F});
            w.createDataset("never", Datatype.uint8(), 3); // never written: reads as the default fill
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(new int[] {9, 9, 9, 9, 9, 9, 1, 2, 3, 9, 9, 4, 5, 6, 9, 9, 9, 9, 9, 32767},
                    h5.root().dataset("v").readInts());
            assertArrayEquals(new int[] {0, 0, 0}, h5.root().dataset("never").readInts());
        }
    }

    @Test
    void variableLengthDataStreamsThroughManyHeapCollections() throws IOException {
        Path file = dir.resolve("strings.h5");
        int n = 70_000; // more objects than one global-heap collection holds
        String[] expected = new String[n];
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            Hdf5Writer.DatasetWriter chunked = w.createDataset("chunked", Datatype.variableString(), 0)
                    .chunked(1000).maxShape(Hdf5Writer.UNLIMITED).deflate(1);
            Hdf5Writer.DatasetWriter contiguous = w.createDataset("contiguous", Datatype.variableString(), n);
            for (int start = 0; start < n; start += 7000) {
                String[] batch = new String[7000];
                for (int i = 0; i < batch.length; i++) {
                    batch[i] = "s" + (start + i) + "é".repeat((start + i) % 5);
                    expected[start + i] = batch[i];
                }
                chunked.append(batch);
                contiguous.write(new long[] {start}, new long[] {batch.length}, batch);
            }
            Hdf5Writer.DatasetWriter rows = w.createDataset("rows", Datatype.sequenceOf(Datatype.float32()), 3);
            rows.write(new float[][] {{1, 2}, {}, {3.5f}});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(expected, h5.root().dataset("chunked").readStrings());
            assertArrayEquals(expected, h5.root().dataset("contiguous").readStrings());
            assertArrayEquals(new float[][] {{1, 2}, {}, {3.5f}}, h5.root().dataset("rows").readVlenFloats());
        }
    }

    @Test
    void writesAreChecked() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("checks.h5"))) {
            Hdf5Writer.DatasetWriter d = w.createDataset("d", Datatype.uint8(), 4);
            assertThrows(IllegalArgumentException.class, () -> d.write(new int[] {0, 1, 2, 256}));     // out of range
            assertThrows(IllegalArgumentException.class, () -> d.write(new int[] {0, 1, -1, 2}));
            assertThrows(IllegalArgumentException.class, () -> d.write(new int[] {1, 2, 3}));          // wrong count
            assertThrows(IllegalArgumentException.class, () -> d.write(new String[] {"a", "b", "c", "d"}));
            assertThrows(IllegalArgumentException.class, () -> d.write(new long[] {3}, new long[] {2}, new int[] {1, 2}));
            assertThrows(IllegalArgumentException.class, () -> d.writeRaw(new long[] {0}, new long[] {2}, new byte[3]));
            assertThrows(IllegalStateException.class, () -> d.extend(5));           // cannot grow
            assertThrows(IllegalStateException.class, () -> d.append(new int[] {1})); // nor append
            Hdf5Writer.DatasetWriter given = w.intDataset("given", new int[] {1}, new long[] {1});
            assertThrows(IllegalStateException.class, () -> given.write(new int[] {2})); // made with its data
            Hdf5Writer.DatasetWriter unchunked = w.createDataset("unchunked", Datatype.int8(), 2).maxShape(Hdf5Writer.UNLIMITED);
            assertThrows(IllegalStateException.class, () -> unchunked.write(new byte[] {1, 2}));    // must be chunked
            w.abort();
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.count(), "abort deletes the temporary file");
        }
    }

    /**
     * A dataset given its data whole is written when the next dataset or group is added (P2 WF9), so only
     * one such dataset's data is held; it is configured before then.
     */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void givenDataIsWrittenWhenTheNextObjectIsAdded(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("given.h5");
        Random random = new Random(2);
        int[] values = new int[1_000_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextInt();
        }
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            Hdf5Writer.DatasetWriter first = w.intChunkedDataset("first", values, new long[] {1000, 1000}, new long[] {100, 1000})
                    .shuffle().deflate(1);
            assertEquals(0, streamed(), "held until the next object");
            Hdf5Writer.GroupWriter next = w.group("next");
            assertTrue(streamed() >= 3_000_000, "streamed " + streamed());
            assertThrows(IllegalStateException.class, () -> first.fletcher32()); // its data is written
            first.intAttribute("still", new int[] {1}, new long[] {1});           // attributes are not data
            Hdf5Writer.DatasetWriter strings = next.stringDataset("strings", new String[] {"a", "bc"}, new long[] {2});
            long before = streamed();
            next.doubleDataset("doubles", new double[] {1.5}, new long[] {1}).compact();
            assertTrue(streamed() > before, "the strings' heap ids are written when the next dataset is added");
            assertThrows(IllegalStateException.class, strings::compact);
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset first = h5.root().dataset("first");
            assertArrayEquals(values, first.readInts());
            assertEquals(2, first.filters().size());
            assertArrayEquals(new int[] {1}, first.attribute("still").orElseThrow().readInts());
            assertArrayEquals(new String[] {"a", "bc"}, h5.root().dataset("next/strings").readStrings());
            assertEquals(Dataset.Layout.COMPACT, h5.root().dataset("next/doubles").layout());
        }
    }

    /** A file past 2 GB: a contiguous dataset whose block ends beyond 2^31 bytes, written only at its ends. */
    @Test
    void filesPass2GB() throws IOException {
        Path file = dir.resolve("large.h5");
        long n = (1L << 31) + 4096;
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            Hdf5Writer.DatasetWriter d = w.createDataset("big", Datatype.int8(), n);
            d.write(new long[] {0}, new long[] {3}, new byte[] {1, 2, 3});
            d.write(new long[] {n - 3}, new long[] {3}, new byte[] {7, 8, 9});
            w.createDataset("after", Datatype.int32(), 2).write(new int[] {42, 43}); // its block lies past 2 GB
        } catch (IOException | java.io.UncheckedIOException e) {
            assumeTrue(false, "the file system cannot hold a 2 GB file here: " + e);
        }
        assumeTrue(Files.size(file) > Integer.MAX_VALUE, "the file did not pass 2 GB");
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset big = h5.root().dataset("big");
            assertArrayEquals(new int[] {1, 2, 3, 0}, big.select(new long[] {0}, new long[] {4}).readInts());
            assertArrayEquals(new int[] {0, 7, 8, 9}, big.select(new long[] {n - 4}, new long[] {4}).readInts());
            assertArrayEquals(new int[] {42, 43}, h5.root().dataset("after").readInts());
        }
    }
}
