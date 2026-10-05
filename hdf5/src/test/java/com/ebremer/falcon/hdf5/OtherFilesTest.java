package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where a file's bytes come from, and its other files' (P2 A7, A9, A10): cache sizes, paths whose file
 * system cannot map them, and a resolver that opens the external raw data and virtual-dataset sources of
 * a file read through a {@link RangeReader}.
 */
class OtherFilesTest {

    private static final Path FIXTURES = Fixtures.path("vds.h5").getParent();

    // ------------------------------------------------------------------ A7: cache sizes

    @Test
    void cacheSizesAreOptions() {
        OpenOptions defaults = OpenOptions.defaults();
        assertEquals(16L << 20, defaults.chunkCacheSize());
        assertEquals(64 << 10, defaults.readerPageSize());
        assertEquals(16L << 20, defaults.readerCacheSize());
        OpenOptions custom = defaults.chunkCacheSize(0).readerPageSize(1 << 20).readerCacheSize(256L << 20);
        assertEquals(0, custom.chunkCacheSize());
        assertEquals(1 << 20, custom.readerPageSize());
        assertEquals(256L << 20, custom.readerCacheSize());
        assertEquals(16L << 20, defaults.chunkCacheSize()); // each setter returns a changed copy
        assertTrue(custom.toString().contains("readerPageSize=1048576"), custom.toString());
        assertThrows(IllegalArgumentException.class, () -> defaults.chunkCacheSize(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.readerPageSize(511));
        assertThrows(IllegalArgumentException.class, () -> defaults.readerPageSize((1 << 30) + 1));
        assertThrows(IllegalArgumentException.class, () -> defaults.readerCacheSize(-1));
    }

    /** Any cache sizes, however small or odd, read the same data. */
    @ParameterizedTest
    @ValueSource(strings = {"chunked_data.h5", "chunk_indexes.h5", "dense_links.h5", "szip.h5"})
    void anyCacheSizesReadTheSameData(String fixture) throws IOException {
        Path path = Fixtures.path(fixture);
        OpenOptions tiny = OpenOptions.defaults().chunkCacheSize(0).readerPageSize(521).readerCacheSize(0);
        try (Hdf5File mapped = Hdf5File.open(path);
             FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
             Hdf5File paged = Hdf5File.open(RangeReader.of(channel), tiny);
             Hdf5File uncached = Hdf5File.open(path, OpenOptions.defaults().chunkCacheSize(0))) {
            assertEquals(contents(mapped.root()), contents(paged.root()));
            assertEquals(contents(mapped.root()), contents(uncached.root()));
        }
    }

    @Test
    void pageSizeAndPageCacheSetTheRequests() throws IOException {
        Path path = Fixtures.path("dense_big.h5"); // under 1 MiB
        assertTrue(Files.size(path) < 1 << 20);
        assertEquals(1, requests(path, OpenOptions.defaults().readerPageSize(1 << 20)),
                "one page holds the whole file, so one request reads it");
        int small = requests(path, OpenOptions.defaults().readerPageSize(4096));
        int onePage = requests(path, OpenOptions.defaults().readerPageSize(4096).readerCacheSize(4096));
        assertTrue(requests(path, OpenOptions.defaults().readerPageSize(512)) > small, "smaller pages, more requests");
        assertTrue(small > 1, small + " requests");
        assertTrue(onePage > small, "a one-page cache reads pages again: " + onePage + " requests against " + small);
    }

    /** The reader requests that opening {@code path} and listing its root group's children take. */
    private static int requests(Path path, OpenOptions options) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            AtomicInteger calls = new AtomicInteger();
            RangeReader inner = RangeReader.of(channel);
            RangeReader counting = new RangeReader() {
                @Override
                public long size() throws IOException {
                    return inner.size();
                }

                @Override
                public void read(long position, ByteBuffer destination) throws IOException {
                    calls.incrementAndGet();
                    inner.read(position, destination);
                }
            };
            try (Hdf5File h5 = Hdf5File.open(counting, options)) {
                for (Hdf5Object child : h5.root().children()) {
                    child.attributes();
                }
            }
            return calls.get();
        }
    }

    // ------------------------------------------------------------------ A9: paths that cannot be mapped

    @Test
    void pathsThatCannotBeMappedAreReadThroughAChannel() throws IOException {
        Path real = Fixtures.path("chunked_data.h5");
        Path unmappable = UnmappableFileSystem.wrap(real);
        assertThrows(UnsupportedOperationException.class, () -> FileChannel.open(unmappable, StandardOpenOption.READ));
        try (Hdf5File mapped = Hdf5File.open(real); Hdf5File h5 = Hdf5File.open(unmappable)) {
            assertSame(unmappable, h5.path());
            assertEquals(contents(mapped.root()), contents(h5.root()));
        }
    }

    @Test
    void otherFilesOfAnUnmappableFileAreFoundInItsFileSystem() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(UnmappableFileSystem.wrap(Fixtures.path("vds.h5")))) {
            assertArrayEquals(new int[] {0, 1, 2, 3, 10, 11, 12, 13}, h5.root().dataset("vds").readInts());
        }
        try (Hdf5File h5 = Hdf5File.open(UnmappableFileSystem.wrap(Fixtures.path("external.h5")))) {
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, h5.root().dataset("ext").readInts());
        }
        // The policy still holds: a name that climbs out of the file's directory is refused.
        try (Hdf5File h5 = Hdf5File.open(UnmappableFileSystem.wrap(Fixtures.path("external_paths.h5")))) {
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("parent").readInts());
        }
    }

    @Test
    void closingTheFileClosesItsChannel(@TempDir Path dir) throws IOException {
        Path copy = dir.resolve("copy.h5");
        Files.copy(Fixtures.path("chunked_data.h5"), copy);
        Hdf5File h5 = Hdf5File.open(UnmappableFileSystem.wrap(copy));
        h5.root().children();
        h5.close();
        assertThrows(HdfClosedException.class, () -> h5.root().children().getFirst().attributes());
        Files.delete(copy); // on Windows an open channel would prevent this
        assertFalse(Files.exists(copy));
    }

    // ------------------------------------------------------------------ A10: a resolver

    /** A resolver over the fixture directory that records each request and closes what it opened. */
    private static final class FixtureResolver implements ExternalFileAccess.Resolver {
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final List<ClosingReader> opened = Collections.synchronizedList(new ArrayList<>());

        @Override
        public RangeReader open(String name, ExternalFileAccess.Purpose purpose) throws IOException {
            requests.add(purpose + ":" + name);
            Path file = FIXTURES.resolve(name);
            if (!Files.exists(file)) {
                return null;
            }
            ClosingReader reader = new ClosingReader(FileChannel.open(file, StandardOpenOption.READ));
            opened.add(reader);
            return reader;
        }
    }

    /** A reader over a channel it closes when it is closed. */
    private static final class ClosingReader implements RangeReader, AutoCloseable {
        final FileChannel channel;
        final RangeReader inner;

        ClosingReader(FileChannel channel) {
            this.channel = channel;
            this.inner = RangeReader.of(channel);
        }

        @Override
        public long size() throws IOException {
            return inner.size();
        }

        @Override
        public void read(long position, ByteBuffer destination) throws IOException {
            inner.read(position, destination);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    @Test
    void aResolverOpensTheSourcesOfAFileReadThroughAReader() throws IOException {
        FixtureResolver resolver = new FixtureResolver();
        OpenOptions options = OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(resolver));
        byte[] bytes = Files.readAllBytes(Fixtures.path("vds.h5"));
        Hdf5File h5 = Hdf5File.open(bytes, options);
        try (h5) {
            Group root = h5.root();
            assertArrayEquals(new int[] {0, 1, 2, 3, 10, 11, 12, 13}, root.dataset("vds").readInts());
            assertArrayEquals(new int[] {0, 1, 2, 3, -1, -1, -1, -1, 10, 11, 12, 13}, root.dataset("vds_gap").readInts());
            assertArrayEquals(new int[] {1, 11, 2, 12}, root.dataset("vds_cols").select(new long[] {1, 0}, new long[] {2, 2}).readInts());
            assertEquals(List.of("VIRTUAL_SOURCE:vds_src0.h5", "VIRTUAL_SOURCE:vds_src1.h5"), List.copyOf(resolver.requests));
            assertTrue(resolver.opened.stream().allMatch(r -> r.channel.isOpen()), "sources stay open while the file is");
        }
        assertTrue(resolver.opened.stream().noneMatch(r -> r.channel.isOpen()), "the file's close closes its sources");
    }

    @Test
    void aResolverReadsExternalRawData() throws IOException {
        FixtureResolver resolver = new FixtureResolver();
        OpenOptions options = OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(resolver));
        try (FileChannel channel = FileChannel.open(Fixtures.path("external.h5"), StandardOpenOption.READ);
             Hdf5File h5 = Hdf5File.open(RangeReader.of(channel), options)) {
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, h5.root().dataset("ext").readInts());
            assertEquals(List.of("RAW_DATA:external_a.bin", "RAW_DATA:external_b.bin"), List.copyOf(resolver.requests));
            assertTrue(resolver.opened.stream().noneMatch(r -> r.channel.isOpen()), "raw data readers close after the read");
        }
    }

    @Test
    void aResolverDecidesWhatIsMissingAndWhatIsRefused() throws IOException {
        byte[] vds = Files.readAllBytes(Fixtures.path("vds.h5"));
        // A source the resolver does not find, or cannot open, reads as the fill value, as in libhdf5.
        ExternalFileAccess.Resolver onlyFirst = (name, purpose) -> name.equals("vds_src0.h5")
                ? RangeReader.of(FileChannel.open(FIXTURES.resolve(name))) : null;
        try (Hdf5File h5 = Hdf5File.open(vds, OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(onlyFirst)))) {
            assertArrayEquals(new int[] {0, 1, 2, 3, -1, -1, -1, -1}, h5.root().dataset("vds").readInts());
        }
        ExternalFileAccess.Resolver failing = (name, purpose) -> {
            throw new IOException("unreachable store");
        };
        try (Hdf5File h5 = Hdf5File.open(vds, OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(failing)))) {
            assertArrayEquals(new int[] {-1, -1, -1, -1, -1, -1, -1, -1}, h5.root().dataset("vds").readInts());
        }
        // A refusal fails the read.
        ExternalFileAccess.Resolver refusing = (name, purpose) -> {
            throw new HdfUnsupportedException("not allowed: " + name);
        };
        try (Hdf5File h5 = Hdf5File.open(vds, OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(refusing)))) {
            HdfUnsupportedException e = assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("vds").readInts());
            assertTrue(e.getMessage().contains("not allowed: vds_src0.h5"), e.getMessage());
        }
        // External raw data the resolver does not find, or cannot read, fails the read.
        byte[] external = Files.readAllBytes(Fixtures.path("external.h5"));
        for (ExternalFileAccess.Resolver resolver : List.of((ExternalFileAccess.Resolver) (n, p) -> null, failing)) {
            try (Hdf5File h5 = Hdf5File.open(external, OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(resolver)))) {
                HdfException e = assertThrows(HdfException.class, () -> h5.root().dataset("ext").readInts());
                assertTrue(e.getMessage().contains("external_a.bin"), e.getMessage());
            }
        }
        assertThrows(IllegalStateException.class, () -> ExternalFileAccess.resolvedBy(failing).allowDirectory(FIXTURES));
        assertThrows(NullPointerException.class, () -> ExternalFileAccess.resolvedBy(null));
    }

    @Test
    void aResolverAlsoServesFilesOpenedFromAPath() throws IOException {
        FixtureResolver resolver = new FixtureResolver();
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds.h5"), ExternalFileAccess.resolvedBy(resolver))) {
            assertArrayEquals(new int[] {0, -1, 1, -1, 2, -1, 3, -1}, h5.root().dataset("vds_step").readInts());
            assertEquals(List.of("VIRTUAL_SOURCE:vds_src0.h5"), List.copyOf(resolver.requests));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Every dataset's path and raw bytes (or the failure that reading it gives), and every attribute's. */
    private static List<String> contents(Group group) {
        List<String> out = new ArrayList<>();
        for (Hdf5Object child : group.children()) {
            for (Attribute attribute : child.attributes()) {
                out.add(child.path() + "@" + attribute.name() + "=" + java.util.Arrays.toString(attribute.readRawBytes()));
            }
            if (child instanceof Dataset dataset) {
                String value;
                try {
                    value = java.util.Arrays.toString(dataset.readRawBytes());
                } catch (HdfException e) {
                    value = e.getClass().getSimpleName();
                }
                out.add(dataset.path() + "=" + value);
            } else if (child instanceof Group sub) {
                out.addAll(contents(sub));
            }
        }
        return out;
    }
}
