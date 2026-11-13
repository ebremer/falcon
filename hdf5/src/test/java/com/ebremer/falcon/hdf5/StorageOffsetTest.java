package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contiguous data read in place (P2 A13): {@link Dataset#storageOffset()}, where a dataset's data lies in
 * its file, and {@link Dataset#storageSegment()}, those bytes of a mapped or in-memory file as a view.
 * libhdf5's {@code H5Dget_offset} is the oracle for the offsets, in {@code storage_metadata.txt}, which
 * {@code P2ApiTest} holds every fixture to; here the bytes at each offset must be the dataset's, however the
 * file is opened.
 */
class StorageOffsetTest {

    private static final Path FIXTURES = Fixtures.path("numeric.h5").getParent();

    /** libhdf5's report for every dataset of the fixtures: file, path, layout, chunk shape, size, offset, filters. */
    private static List<String[]> oracle() throws IOException {
        List<String[]> lines = new ArrayList<>();
        for (String line : Files.readAllLines(Fixtures.path("storage_metadata.txt"), StandardCharsets.UTF_8)) {
            lines.add(line.split("\t", -1));
        }
        return lines;
    }

    static Stream<String> fixtures() throws IOException {
        return oracle().stream().map(line -> line[0]).distinct();
    }

    /** The dataset's bytes lie in {@code file} at its offset, and its segment is a read-only view of them. */
    private static void assertReadInPlace(byte[] file, Dataset dataset, boolean mapped, String what) {
        long offset = dataset.storageOffset().orElseThrow(() -> new AssertionError(what + " has no offset"));
        long size = dataset.storageSize();
        assertTrue(offset >= 0 && size <= file.length - offset, what + ": " + size + " bytes at " + offset);
        byte[] stored = Arrays.copyOfRange(file, (int) offset, (int) (offset + size));
        assertArrayEquals(stored, dataset.readRawBytes(), what + ": the bytes at its offset are the dataset's");
        MemorySegment view = dataset.storageSegment().orElseThrow(() -> new AssertionError(what + " has no segment"));
        assertEquals(size, view.byteSize(), what + " segment size");
        assertTrue(view.isReadOnly(), what + ": the segment is read-only");
        assertEquals(mapped, view.isMapped(), what + ": a view of the mapping, or of the array");
        assertArrayEquals(stored, view.toArray(ValueLayout.JAVA_BYTE), what + ": the segment is those bytes");
    }

    // ------------------------------------------------------------------ every fixture, however it is opened

    @ParameterizedTest
    @MethodSource("fixtures")
    void theBytesAtTheOffsetAreTheDatasets(String file) throws IOException {
        Path path = FIXTURES.resolve(file);
        byte[] bytes = Files.readAllBytes(path);
        try (Hdf5File mapped = Hdf5File.open(path);
             Hdf5File inMemory = Hdf5File.open(bytes.clone());
             FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
             Hdf5File onDemand = Hdf5File.open(RangeReader.of(channel))) {
            for (String[] f : oracle()) {
                if (!f[0].equals(file)) {
                    continue;
                }
                String what = file + ":" + f[1];
                Dataset dataset = mapped.root().dataset(f[1]);
                Dataset held = inMemory.root().dataset(f[1]);
                Dataset remote = onDemand.root().dataset(f[1]);
                OptionalLong offset = dataset.storageOffset();
                // The data is where it is, however the file is read; only a file in memory has bytes to view.
                assertEquals(offset, held.storageOffset(), what + " from bytes");
                assertEquals(offset, remote.storageOffset(), what + " through a range reader");
                assertTrue(remote.storageSegment().isEmpty(), what + ": no view of a file read on demand");
                if (offset.isEmpty()) {
                    assertTrue(dataset.storageSegment().isEmpty(), what + ": no offset, no segment");
                    assertTrue(held.storageSegment().isEmpty(), what + ": no offset, no segment, from bytes");
                    continue;
                }
                assertEquals("CONTIGUOUS", f[2], what + ": only contiguous data has an offset");
                assertReadInPlace(bytes, dataset, true, what);
                assertReadInPlace(bytes, held, false, what + " from bytes");
            }
        }
    }

    // ------------------------------------------------------------------ the segment is the file's own bytes

    @Test
    void theSegmentOfAFileInMemoryIsTheArray() throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path("attributes.h5"));
        try (Hdf5File h5 = Hdf5File.open(bytes)) {
            Dataset data = h5.root().dataset("data");
            int at = (int) data.storageOffset().orElseThrow();
            MemorySegment view = data.storageSegment().orElseThrow();
            assertEquals(24, view.byteSize());
            assertThrows(IllegalArgumentException.class, () -> view.set(ValueLayout.JAVA_BYTE, 0, (byte) 1));
            // Nothing was copied: what the array holds, the segment holds.
            bytes[at + 5] ^= 0x5a;
            assertEquals(bytes[at + 5], view.get(ValueLayout.JAVA_BYTE, 5));
            assertEquals(bytes[at + 5], data.storageSegment().orElseThrow().get(ValueLayout.JAVA_BYTE, 5));
        }
    }

    @Test
    void theSegmentOfAMappedFileEndsWithTheFile() throws IOException {
        Dataset data;
        MemorySegment view;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("attributes.h5"))) {
            data = h5.root().dataset("data");
            view = data.storageSegment().orElseThrow();
            assertTrue(view.isMapped());
            assertThrows(IllegalArgumentException.class, () -> view.set(ValueLayout.JAVA_BYTE, 0, (byte) 1));
            assertEquals(data.readRawBytes()[23], view.get(ValueLayout.JAVA_BYTE, 23));
        }
        // Closing the file unmaps it: its segments are gone with it, and its datasets say nothing more.
        assertThrows(IllegalStateException.class, () -> view.get(ValueLayout.JAVA_BYTE, 0));
        assertThrows(HdfClosedException.class, data::storageOffset);
        assertThrows(HdfClosedException.class, data::storageSegment);
    }

    // ------------------------------------------------------------------ files Falcon writes, and user blocks

    private static final byte[] WHOLE = new byte[4099];
    private static final byte[] STREAMED = new byte[3 * 1000];
    private static final double[] DOUBLES = new double[257];

    static {
        new Random(11).nextBytes(WHOLE);
        new Random(12).nextBytes(STREAMED);
        for (int i = 0; i < DOUBLES.length; i++) {
            DOUBLES[i] = i / 7.0;
        }
    }

    /** One dataset of each storage Falcon writes, after a user block that holds {@code userBlock}, if any. */
    private static Path write(Path file, Hdf5Writer.Format format, byte[] userBlock) throws IOException {
        try (Hdf5Writer w = userBlock == null ? Hdf5Writer.create(file, format) : Hdf5Writer.create(file, format, userBlock)) {
            w.byteDataset("whole", WHOLE, new long[] {WHOLE.length});
            Hdf5Writer.GroupWriter group = w.group("group");
            // Written a piece at a time, and its last piece first: its place is fixed when it is made.
            Hdf5Writer.DatasetWriter streamed = group.createDataset("streamed", Datatype.int8(), STREAMED.length);
            for (int at = 2000; at >= 0; at -= 1000) {
                streamed.writeRaw(new long[] {at}, new long[] {1000}, Arrays.copyOfRange(STREAMED, at, at + 1000));
            }
            group.doubleDataset("doubles", DOUBLES, new long[] {DOUBLES.length});
            w.byteDataset("compact", new byte[] {1, 2, 3, 4}, new long[] {4}).compact();
            w.intChunkedDataset("chunked", new int[] {1, 2, 3, 4, 5, 6, 7, 8}, new long[] {8}, new long[] {4});
        }
        return file;
    }

    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void aUserBlockMovesTheDataByItsSize(Hdf5Writer.Format format, @TempDir Path dir) throws IOException {
        Path plain = write(dir.resolve("plain.h5"), format, null);
        byte[] plainBytes = Files.readAllBytes(plain);
        try (Hdf5File a = Hdf5File.open(plain)) {
            for (String name : List.of("whole", "group/streamed", "group/doubles")) {
                assertReadInPlace(plainBytes, a.root().dataset(name), true, format + " " + name);
            }
            assertArrayEquals(STREAMED, a.root().dataset("group/streamed").storageSegment().orElseThrow()
                    .toArray(ValueLayout.JAVA_BYTE));
            MemorySegment doubles = a.root().dataset("group/doubles").storageSegment().orElseThrow();
            assertEquals(DOUBLES[200], doubles.get(ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 200 * 8L));
            for (String name : List.of("compact", "chunked")) {
                assertTrue(a.root().dataset(name).storageOffset().isEmpty(), format + " " + name + " has no one place");
                assertTrue(a.root().dataset(name).storageSegment().isEmpty(), format + " " + name + " has no segment");
            }
            // The same file after a user block: every byte of it lies the block's size further on. A block
            // is the smallest libhdf5 allows that holds its content, so 513 bytes make one of 1,024.
            for (int[] block : new int[][] {{1, 512}, {512, 512}, {513, 1024}, {5000, 8192}}) {
                byte[] content = new byte[block[0]];
                Arrays.fill(content, (byte) 'u');
                Path after = write(dir.resolve("after" + block[0] + ".h5"), format, content);
                byte[] afterBytes = Files.readAllBytes(after);
                assertEquals(plainBytes.length + block[1], afterBytes.length, format + " after " + block[0] + " bytes");
                try (Hdf5File b = Hdf5File.open(after)) {
                    for (String name : List.of("whole", "group/streamed", "group/doubles")) {
                        String what = format + " " + name + " after a user block of " + block[1];
                        assertEquals(a.root().dataset(name).storageOffset().orElseThrow() + block[1],
                                b.root().dataset(name).storageOffset().orElseThrow(), what);
                        assertReadInPlace(afterBytes, b.root().dataset(name), true, what);
                    }
                    for (String name : List.of("compact", "chunked")) {
                        assertTrue(b.root().dataset(name).storageOffset().isEmpty(), format + " " + name + " after a user block");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ a file read on demand

    @Test
    void aFileReadOnDemandGivesTheOffsetAndReadsNoData(@TempDir Path dir) throws IOException {
        byte[] data = new byte[3 << 20];
        new Random(7).nextBytes(data);
        Path file = dir.resolve("large.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.group("store").byteDataset("bytes", data, new long[] {data.length});
        }
        long offset;
        try (Hdf5File mapped = Hdf5File.open(file)) {
            offset = mapped.root().dataset("store/bytes").storageOffset().orElseThrow();
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            RangeReader inner = RangeReader.of(channel);
            AtomicLong read = new AtomicLong();
            RangeReader counting = new RangeReader() {
                @Override
                public long size() throws IOException {
                    return inner.size();
                }

                @Override
                public void read(long position, ByteBuffer destination) throws IOException {
                    read.addAndGet(destination.remaining());
                    inner.read(position, destination);
                }
            };
            try (Hdf5File h5 = Hdf5File.open(counting)) {
                Dataset dataset = h5.root().dataset("store/bytes");
                assertEquals(offset, dataset.storageOffset().orElseThrow());
                assertEquals(data.length, dataset.storageSize());
                assertTrue(dataset.storageSegment().isEmpty(), "a segment of it would be the whole dataset, read");
                assertTrue(read.get() < data.length / 8, "finding where 3 MiB lie read " + read.get() + " bytes");
                // The caller reads the range it wants, with what it reads the file with.
                ByteBuffer piece = ByteBuffer.allocate(5000);
                inner.read(offset + 1_234_567, piece);
                assertArrayEquals(Arrays.copyOfRange(data, 1_234_567, 1_239_567), piece.array());
            }
        }
    }

    // ------------------------------------------------------------------ a file with a metadata cache image

    /** Each dataset reached through hard links, once. */
    private static void datasets(Group group, Set<Long> seen, List<Dataset> found) {
        for (Link link : group.links()) {
            if (link instanceof Link.Hard hard && seen.add(hard.objectHeaderAddress())) {
                Hdf5Object child = group.child(hard.name()).orElseThrow();
                if (child instanceof Group inner) {
                    datasets(inner, seen, found);
                } else if (child instanceof Dataset dataset) {
                    found.add(dataset);
                }
            }
        }
    }

    /**
     * libhdf5 2.0 cannot read {@code mdc_image.h5} back, so the storage oracle leaves it out: its metadata is
     * read from the image, and its data lies where its layout says, in the file itself.
     */
    @ParameterizedTest
    @MethodSource("imageFixtures")
    void dataOfAFileWithACacheImageIsReadWhereItIsStored(String file) throws IOException {
        Path path = Fixtures.path(file);
        byte[] bytes = Files.readAllBytes(path);
        try (Hdf5File h5 = Hdf5File.open(path)) {
            List<Dataset> found = new ArrayList<>();
            datasets(h5.root(), new HashSet<>(), found);
            int inPlace = 0;
            for (Dataset dataset : found) {
                if (dataset.storageOffset().isPresent()) {
                    assertReadInPlace(bytes, dataset, true, file + ":" + dataset.path());
                    inPlace++;
                } else {
                    assertTrue(dataset.storageSegment().isEmpty(), file + ":" + dataset.path());
                }
            }
            assertTrue(inPlace > 0, file + " has contiguous data");
        }
    }

    static Stream<String> imageFixtures() {
        return Stream.of("mdc_image.h5", "mdc_image_userblock.h5");
    }

    // ------------------------------------------------------------------ data recorded outside the file

    /** Where the 16 bytes of a version-3 contiguous layout (address, size) lie in {@code file}: once only. */
    private static int layoutAt(byte[] file, long address, long size) {
        byte[] wanted = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(address).putLong(size).array();
        int found = -1;
        for (int at = 0; at + 16 <= file.length; at++) {
            if (Arrays.equals(file, at, at + 16, wanted, 0, 16)) {
                assertEquals(-1, found, "the layout is found twice");
                found = at;
            }
        }
        assertTrue(found >= 0, "the layout is not found");
        return found;
    }

    @Test
    void dataRecordedOutsideTheFileIsRefused() throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path("attributes.h5"));
        long offset;
        try (Hdf5File h5 = Hdf5File.open(bytes)) {
            offset = h5.root().dataset("data").storageOffset().orElseThrow();
        }
        int layout = layoutAt(bytes, offset, 24);
        // Its last byte one past the file's end, and its first: neither is a place in the file.
        for (long address : new long[] {bytes.length - 23, bytes.length}) {
            byte[] corrupt = bytes.clone();
            ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putLong(layout, address);
            try (Hdf5File h5 = Hdf5File.open(corrupt)) {
                Dataset data = h5.root().dataset("data");
                assertThrows(HdfFormatException.class, data::storageOffset, "at " + address);
                assertThrows(HdfFormatException.class, data::storageSegment, "at " + address);
            }
        }
        // The last place it fits is one.
        byte[] moved = bytes.clone();
        ByteBuffer.wrap(moved).order(ByteOrder.LITTLE_ENDIAN).putLong(layout, bytes.length - 24);
        try (Hdf5File h5 = Hdf5File.open(moved)) {
            Dataset data = h5.root().dataset("data");
            assertEquals(bytes.length - 24, data.storageOffset().orElseThrow());
            assertReadInPlace(moved, data, false, "moved to the file's end");
        }
    }
}
