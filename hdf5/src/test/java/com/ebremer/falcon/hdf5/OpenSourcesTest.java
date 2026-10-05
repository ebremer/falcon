package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Opening HDF5 files from something other than a path (P2 A6): bytes in memory, and a {@link RangeReader}
 * over a channel or any other source, read on demand.
 */
class OpenSourcesTest {

    private static final Path FIXTURES = Fixtures.path("numeric.h5").getParent();

    /** Without a path, external files are allowed in the fixture directory, as the path-opened file allows. */
    private static final OpenOptions IN_FIXTURES = OpenOptions.defaults()
            .externalFileAccess(ExternalFileAccess.sameDirectory().allowDirectory(FIXTURES));

    static Stream<String> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".h5")).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyFixtureReadsTheSameFromBytesAndRangeReaders(String file) throws IOException {
        Path path = FIXTURES.resolve(file);
        String mapped = describe(() -> Hdf5File.open(path));
        assertFalse(mapped.startsWith("! "), file + " does not open: " + mapped);
        assertEquals(mapped, describe(() -> Hdf5File.open(Files.readAllBytes(path), IN_FIXTURES)), file + " from bytes");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            assertEquals(mapped, describe(() -> Hdf5File.open(RangeReader.of(channel), IN_FIXTURES)),
                    file + " through a file channel");
        }
        try (SeekableByteChannel channel = new BytesChannel(Files.readAllBytes(path))) {
            assertEquals(mapped, describe(() -> Hdf5File.open(RangeReader.of(channel), IN_FIXTURES)),
                    file + " through another channel");
        }
    }

    private interface Opener {
        Hdf5File open() throws IOException;
    }

    /** Everything Falcon reads from a file: each object's kind and path, and each dataset's storage and bytes. */
    private static String describe(Opener opener) {
        StringBuilder out = new StringBuilder();
        try (Hdf5File h5 = opener.open()) {
            describe(h5.root(), out, new HashSet<>());
        } catch (IOException | RuntimeException e) {
            out.append("! ").append(e.getClass().getSimpleName());
        }
        return out.toString();
    }

    private static void describe(Hdf5Object object, StringBuilder out, Set<Long> seen) {
        out.append(object.path()).append(object.isGroup() ? " group" : "").append('\n');
        if (!seen.add(object.objectHeaderAddress())) {
            return;
        }
        attempt(out, () -> object.attributes().stream().map(Attribute::name).toList());
        switch (object) {
            case Group group -> {
                List<Hdf5Object> children = new ArrayList<>();
                attempt(out, () -> children.addAll(group.children()));
                for (Hdf5Object child : children) {
                    describe(child, out, seen);
                }
            }
            case Dataset dataset -> {
                attempt(out, () -> dataset.layout() + " " + dataset.storageSize() + " " + dataset.filters());
                attempt(out, () -> Arrays.toString(dataset.dataspace().dimensions()));
                attempt(out, () -> Arrays.hashCode(dataset.readRawBytes()));
            }
            default -> { }
        }
    }

    private static void attempt(StringBuilder out, java.util.function.Supplier<Object> read) {
        try {
            out.append("  ").append(read.get()).append('\n');
        } catch (RuntimeException e) {
            out.append("  ! ").append(e.getClass().getSimpleName()).append('\n');
        }
    }

    // ------------------------------------------------------------------ reading on demand

    /** A file of 8 MiB of contiguous doubles and 8 MiB of chunked ones. */
    private static Path largeFile(Path dir) throws IOException {
        Path file = dir.resolve("large.h5");
        double[] data = new double[1 << 20];
        for (int i = 0; i < data.length; i++) {
            data[i] = i;
        }
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleDataset("contiguous", data, new long[] {1024, 1024});
            w.doubleChunkedDataset("chunked", data, new long[] {1024, 1024}, new long[] {64, 64});
        }
        return file;
    }

    /** Counts the bytes it reads, and fails reads at or above {@code failFrom}. */
    private static final class CountingReader implements RangeReader {
        final RangeReader inner;
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong calls = new AtomicLong();
        volatile long failFrom = Long.MAX_VALUE;

        CountingReader(RangeReader inner) {
            this.inner = inner;
        }

        @Override
        public long size() throws IOException {
            return inner.size();
        }

        @Override
        public void read(long position, ByteBuffer destination) throws IOException {
            if (position + destination.remaining() > failFrom) {
                throw new IOException("unreachable range");
            }
            calls.incrementAndGet();
            bytes.addAndGet(destination.remaining());
            inner.read(position, destination);
        }
    }

    @Test
    void aRangeReaderFetchesOnlyWhatIsRead(@TempDir Path dir) throws IOException {
        Path file = largeFile(dir);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            CountingReader reader = new CountingReader(RangeReader.of(channel));
            try (Hdf5File h5 = Hdf5File.open(reader)) {
                assertNull(h5.path());
                Dataset contiguous = h5.root().dataset("contiguous");
                assertArrayEquals(new double[] {500 * 1024 + 500, 500 * 1024 + 501, 501 * 1024 + 500, 501 * 1024 + 501},
                        contiguous.select(new long[] {500, 500}, new long[] {2, 2}).readDoubles());
                Dataset chunked = h5.root().dataset("chunked");
                assertArrayEquals(new double[] {700 * 1024 + 10, 700 * 1024 + 11},
                        chunked.select(new long[] {700, 10}, new long[] {1, 2}).readDoubles());
                assertEquals(16L << 20, contiguous.storageSize() + chunked.storageSize());
                // Metadata pages, the pages holding the selected contiguous runs, and one 32 KiB chunk:
                // a few hundred KiB of a 16 MiB file.
                assertTrue(reader.bytes.get() < 1 << 20, reader.bytes.get() + " bytes read");
            }
        }
    }

    @Test
    void readsThroughARangeReaderAreThreadSafe(@TempDir Path dir) throws IOException {
        Path file = largeFile(dir);
        double[] expected;
        try (Hdf5File h5 = Hdf5File.open(file)) {
            expected = h5.root().dataset("contiguous").readDoubles();
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file);
             FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            for (RangeReader reader : List.of(RangeReader.of(new BytesChannel(Files.readAllBytes(file))),
                    RangeReader.of(fileChannel), RangeReader.of(channel))) {
                try (Hdf5File h5 = Hdf5File.open(reader)) {
                    for (String name : List.of("contiguous", "chunked")) {
                        double[] parallel = h5.root().dataset(name).blocks(7).parallel()
                                .map(Selection::readDoubles)
                                .reduce(new double[0], OpenSourcesTest::concat, OpenSourcesTest::concat);
                        assertArrayEquals(expected, parallel, name);
                    }
                    assertArrayEquals(expected, h5.root().dataset("contiguous").readDoubles());
                }
            }
        }
    }

    private static double[] concat(double[] a, double[] b) {
        double[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void readerFailuresSurfaceAsIoErrors(@TempDir Path dir) throws IOException {
        Path file = largeFile(dir);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            CountingReader reader = new CountingReader(RangeReader.of(channel));
            try (Hdf5File h5 = Hdf5File.open(reader)) {
                Dataset dataset = h5.root().dataset("contiguous");
                dataset.dataspace();
                reader.failFrom = 0; // the source goes away
                UncheckedIOException e = assertThrows(UncheckedIOException.class, dataset::readDoubles);
                assertEquals("unreachable range", e.getCause().getMessage());
            }
            // Failing while opening is the open's own IOException.
            CountingReader broken = new CountingReader(RangeReader.of(channel));
            broken.failFrom = 0;
            IOException open = assertThrows(IOException.class, () -> Hdf5File.open(broken));
            assertEquals("unreachable range", open.getMessage());
        }
        RangeReader failingSize = new RangeReader() {
            @Override
            public long size() throws IOException {
                throw new IOException("no size");
            }

            @Override
            public void read(long position, ByteBuffer destination) {
                throw new AssertionError("not reached");
            }
        };
        assertEquals("no size", assertThrows(IOException.class, () -> Hdf5File.open(failingSize)).getMessage());
        RangeReader shortReads = new RangeReader() {
            @Override
            public long size() {
                return 4096;
            }

            @Override
            public void read(long position, ByteBuffer destination) {
                destination.put((byte) 0); // a reader that does not fill the buffer
            }
        };
        assertThrows(EOFException.class, () -> Hdf5File.open(shortReads));
        assertThrows(HdfFormatException.class, () -> Hdf5File.open(new byte[1000]));
    }

    @Test
    void aClosedFileFromBytesOrAReaderReadsNothing() throws IOException {
        byte[] bytes = Files.readAllBytes(Fixtures.path("chunked_data.h5"));
        int[] expected;
        try (Hdf5File mapped = Hdf5File.open(Fixtures.path("chunked_data.h5"))) {
            expected = mapped.root().dataset("gzip_i4").readInts();
        }
        try (SeekableByteChannel channel = new BytesChannel(bytes)) {
            for (Hdf5File h5 : List.of(Hdf5File.open(bytes), Hdf5File.open(RangeReader.of(channel)))) {
                assertNull(h5.path());
                Dataset dataset = h5.root().dataset("gzip_i4");
                assertArrayEquals(expected, dataset.readInts());
                assertTrue(h5.isOpen());
                h5.close();
                h5.close();
                assertFalse(h5.isOpen());
                assertThrows(HdfClosedException.class, dataset::readInts);
                assertThrows(HdfClosedException.class, () -> h5.root().childNames());
            }
        }
    }

    // ------------------------------------------------------------------ other files

    @Test
    void aFileWithoutAPathOpensNoOtherFileByDefault() throws IOException {
        byte[] external = Files.readAllBytes(Fixtures.path("external.h5"));
        try (Hdf5File h5 = Hdf5File.open(external)) {
            HdfUnsupportedException e = assertThrows(HdfUnsupportedException.class,
                    () -> h5.root().dataset("ext").readRawBytes());
            assertTrue(e.getMessage().contains("not opened from a path"), e.getMessage());
        }
        try (Hdf5File h5 = Hdf5File.open(external, IN_FIXTURES);
             Hdf5File mapped = Hdf5File.open(Fixtures.path("external.h5"))) {
            assertArrayEquals(mapped.root().dataset("ext").readRawBytes(), h5.root().dataset("ext").readRawBytes());
        }
        // A virtual dataset's sources in this same file need no other file; those in other files are refused.
        try (Hdf5File h5 = Hdf5File.open(Files.readAllBytes(Fixtures.path("vds_default.h5")));
             Hdf5File mapped = Hdf5File.open(Fixtures.path("vds_default.h5"))) {
            assertArrayEquals(mapped.root().dataset("same_file").readInts(), h5.root().dataset("same_file").readInts());
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("shared_names").readInts());
        }
        try (Hdf5File h5 = Hdf5File.open(Files.readAllBytes(Fixtures.path("external.h5")),
                OpenOptions.defaults().externalFileAccess(ExternalFileAccess.none()))) {
            assertInstanceOf(HdfUnsupportedException.class,
                    assertThrows(RuntimeException.class, () -> h5.root().dataset("ext").readRawBytes()));
        }
    }

    @Test
    void aFileWithAUserBlockOpensFromBytes() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Files.readAllBytes(Fixtures.path("userblock_v3.h5")));
             Hdf5File mapped = Hdf5File.open(Fixtures.path("userblock_v3.h5"))) {
            assertEquals(mapped.root().childNames(), h5.root().childNames());
            assertEquals(mapped.superblockVersion(), h5.superblockVersion());
        }
    }

    @Test
    void rangeReaderOfChannelRejectsNull() {
        assertThrows(NullPointerException.class, () -> RangeReader.of(null));
        assertThrows(NullPointerException.class, () -> Hdf5File.open((RangeReader) null));
    }

    /** A seekable channel over bytes in memory: one that is not a {@link FileChannel}. */
    private static final class BytesChannel implements SeekableByteChannel {
        private final byte[] bytes;
        private long position;
        private boolean open = true;

        BytesChannel(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (position >= bytes.length) {
                return -1;
            }
            int n = (int) Math.min(dst.remaining(), Math.min(bytes.length - position, 1000)); // short reads too
            dst.put(bytes, (int) position, n);
            position += n;
            return n;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            position = newPosition;
            return this;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
