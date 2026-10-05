package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.data.ChunkIndex;
import com.ebremer.falcon.hdf5.data.Hyperslab;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The read paths of P2 PF1–PF3 give the same results as the paths they replace, and do only the work they
 * should: chunk lookups by coordinate (PF1), virtual-dataset selections that read only what they map to
 * (PF2), and lookups by name through the name indexes (PF3). Where work is observable without timing it,
 * through the bytes a {@link RangeReader} is asked for, it is asserted, so a regression to reading whole
 * indexes fails here (timings are in {@code Benchmarks}).
 */
class PerformanceTest {

    // ------------------------------------------------------------------ PF1: chunk index

    @Test
    void chunkIndexFindsWhatAScanFinds() {
        Random random = new Random(1);
        int[] chunkDims = {3, 5, 2};
        for (int trial = 0; trial < 200; trial++) {
            long[] grid = {1 + random.nextInt(6), 1 + random.nextInt(6), 1 + random.nextInt(6)};
            List<ChunkRecord> chunks = new ArrayList<>();
            for (long a = 0; a < grid[0]; a++) {
                for (long b = 0; b < grid[1]; b++) {
                    for (long c = 0; c < grid[2]; c++) {
                        if (random.nextInt(3) == 0) { // sparse, and listed in no particular order
                            chunks.add(chunks.isEmpty() ? 0 : random.nextInt(chunks.size() + 1),
                                    new ChunkRecord(new long[] {a * 3, b * 5, c * 2}, 1000 + chunks.size(), 7, 0));
                        }
                    }
                }
            }
            ChunkIndex index = ChunkIndex.of(chunks, chunkDims);
            long[] dims = {grid[0] * 3 - random.nextInt(3), grid[1] * 5 - random.nextInt(5), grid[2] * 2 - random.nextInt(2)};
            for (int q = 0; q < 20; q++) {
                long[] offset = new long[3];
                long[] count = new long[3];
                for (int d = 0; d < 3; d++) {
                    offset[d] = random.nextInt((int) dims[d]);
                    count[d] = random.nextInt((int) (dims[d] - offset[d]) + 1);
                }
                List<String> expected = chunks.stream().filter(ch -> overlaps(ch, chunkDims, dims, offset, count))
                        .sorted((x, y) -> Arrays.compare(x.offset(), y.offset()))
                        .map(ch -> Arrays.toString(ch.offset()) + "@" + ch.address()).toList();
                List<String> found = index.overlapping(offset, count, dims).stream()
                        .map(ch -> Arrays.toString(ch.offset()) + "@" + ch.address()).toList();
                assertEquals(expected, found, Arrays.toString(offset) + " " + Arrays.toString(count));
            }
            assertEquals(7L * chunks.size(), index.storedBytes());
        }
    }

    private static boolean overlaps(ChunkRecord chunk, int[] chunkDims, long[] dims, long[] offset, long[] count) {
        for (int d = 0; d < dims.length; d++) {
            long lo = Math.max(chunk.offset()[d], offset[d]);
            long hi = Math.min(Math.min(chunk.offset()[d] + chunkDims[d], dims[d]), offset[d] + count[d]);
            if (lo >= hi) {
                return false;
            }
        }
        return true;
    }

    @Test
    void chunkIndexRejectsWhatOnlyCorruptionMakes() {
        int[] dims = {4};
        assertThrows(HdfFormatException.class,
                () -> ChunkIndex.of(List.of(new ChunkRecord(new long[] {2}, 100, 16, 0)), dims));
        assertThrows(HdfFormatException.class, () -> ChunkIndex.of(List.of(
                new ChunkRecord(new long[] {4}, 100, 16, 0), new ChunkRecord(new long[] {4}, 200, 16, 0)), dims));
        assertThrows(HdfFormatException.class,
                () -> ChunkIndex.of(List.of(new ChunkRecord(new long[] {0}, 100, 16, 0)), new int[] {0}));
    }

    // ------------------------------------------------------------------ selections against whole reads

    /** Every chunked and virtual dataset of every fixture: {@code file \t path}. */
    static Stream<String> chunkedAndVirtual() throws IOException {
        return Files.readAllLines(Fixtures.path("storage_metadata.txt"), StandardCharsets.UTF_8).stream()
                .map(line -> line.split("\t"))
                .filter(f -> f[2].equals("CHUNKED") || f[2].equals("VIRTUAL"))
                .map(f -> f[0] + "\t" + f[1]);
    }

    /**
     * Random boxes of every chunked dataset (through its cached chunk index) and every virtual dataset
     * (read lazily) hold what the same box of the whole dataset holds.
     */
    @ParameterizedTest
    @MethodSource("chunkedAndVirtual")
    void selectionsReadWhatTheWholeDatasetHolds(String fileAndPath) throws IOException {
        String[] f = fileAndPath.split("\t");
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(f[0]))) {
            Dataset dataset = h5.root().dataset(f[1]);
            byte[] whole;
            long[] dims;
            try {
                dims = dataset.dataspace().dimensions();
                whole = dataset.readRawBytes();
            } catch (HdfException e) {
                return; // a dataset Falcon does not read whole (an unsupported source, say) is tested elsewhere
            }
            int elementSize = dataset.datatype().size();
            Random random = new Random(fileAndPath.hashCode());
            for (int q = 0; q < 12; q++) {
                long[] offset = new long[dims.length];
                long[] count = new long[dims.length];
                for (int d = 0; d < dims.length; d++) {
                    offset[d] = dims[d] == 0 ? 0 : random.nextLong(dims[d]);
                    count[d] = random.nextLong(dims[d] - offset[d] + 1);
                }
                byte[] expected = Hyperslab.extract(MemorySegment.ofArray(whole), dims, offset, count, elementSize);
                byte[] selected = dataset.selectionData(offset, count).toArray(ValueLayout.JAVA_BYTE);
                assertArrayEquals(expected, selected, fileAndPath + " " + Arrays.toString(offset) + " " + Arrays.toString(count));
            }
            if (dims.length > 0 && dims[0] > 0) {
                ByteArrayCollector blocks = new ByteArrayCollector();
                dataset.blocks(Math.max(1, dims[0] / 3)).forEach(block ->
                        blocks.add(block.dataset().selectionData(block.offset(), block.shape())));
                assertArrayEquals(whole, blocks.bytes(), fileAndPath + " in blocks");
            }
        }
    }

    private static final class ByteArrayCollector {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        void add(MemorySegment segment) {
            out.writeBytes(segment.toArray(ValueLayout.JAVA_BYTE));
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ PF2: lazy virtual datasets

    @Test
    void aVirtualSelectionOpensOnlyTheSourcesItReads(@TempDir Path dir) throws IOException {
        // Row 0 of /vds comes from vds_src0.h5, row 1 from vds_src1.h5, here not an HDF5 file at all.
        Files.copy(Fixtures.path("vds.h5"), dir.resolve("vds.h5"));
        Files.copy(Fixtures.path("vds_src0.h5"), dir.resolve("vds_src0.h5"));
        Files.write(dir.resolve("vds_src1.h5"), new byte[4096]);
        try (Hdf5File h5 = Hdf5File.open(dir.resolve("vds.h5"))) {
            Dataset vds = h5.root().dataset("vds");
            assertArrayEquals(new int[] {0, 1, 2, 3}, vds.select(new long[] {0, 0}, new long[] {1, 4}).readInts());
            assertArrayEquals(new int[] {1, 2}, vds.select(new long[] {0, 1}, new long[] {1, 2}).readInts());
            assertThrows(HdfFormatException.class, () -> vds.select(new long[] {1, 0}, new long[] {1, 1}).readInts());
            assertThrows(HdfFormatException.class, vds::readInts);
        }
    }

    @Test
    void virtualSourcesStayOpenUntilTheFileCloses() throws IOException {
        Dataset vds;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds.h5"))) {
            vds = h5.root().dataset("vds");
            int[] first = vds.readInts();
            for (int i = 0; i < 3; i++) { // later reads reuse the sources found
                assertArrayEquals(first, vds.readInts());
            }
            assertArrayEquals(new int[] {0, 1, 2, 3, 10, 11, 12, 13}, first);
        }
        assertThrows(HdfClosedException.class, vds::readInts);
    }

    // ------------------------------------------------------------------ PF3: lookups by name

    @ParameterizedTest
    @ValueSource(strings = {"dense_big.h5", "oldstyle_big.h5", "dense_links_big.h5", "dense_links.h5",
        "links.h5", "links_old.h5", "new_style_groups.h5", "old_style_groups.h5", "dense_attrs.h5", "attributes.h5",
        "heap_limits.h5", "sohm.h5", "sohm_latest.h5"})
    void lookupsByNameFindWhatTheListingsHold(String file) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file))) {
            forEachObject(h5.root(), new HashSet<>(), object -> {
                List<String> attributeNames = object.attributes().stream().map(Attribute::name).toList();
                for (int i = 0; i < attributeNames.size(); i += 1 + attributeNames.size() / 300) {
                    String name = attributeNames.get(i);
                    Attribute found = fresh(h5, object).attribute(name).orElseThrow(() -> new AssertionError(name));
                    assertEquals(name, found.name());
                    assertEquals(describe(object.attribute(name).orElseThrow()), describe(found), name);
                }
                assertTrue(fresh(h5, object).attribute("no such attribute").isEmpty());
                if (object instanceof Group group) {
                    List<Link> links = group.links();
                    for (int i = 0; i < links.size(); i += 1 + links.size() / 300) {
                        Link link = links.get(i);
                        assertEquals(link, ((Group) fresh(h5, group)).link(link.name()).orElseThrow(), link.name());
                    }
                    for (Link link : links) {
                        if (link.name().chars().anyMatch(c -> c > 127)) { // every non-ASCII name
                            assertEquals(link, ((Group) fresh(h5, group)).link(link.name()).orElseThrow(), link.name());
                        }
                    }
                    for (String missing : List.of("no such link", "link99999", "zzzz", "\u0000", "Zeta2")) {
                        assertTrue(((Group) fresh(h5, group)).link(missing).isEmpty(), missing);
                    }
                }
            });
        }
    }

    /** An attribute's type, shape, and values (where Falcon reads them), for comparison. */
    private static String describe(Attribute attribute) {
        String values;
        try {
            values = Arrays.deepToString(new Object[] {attribute.read()});
        } catch (HdfUnsupportedException e) {
            values = "unsupported";
        }
        return attribute.datatype() + " " + Arrays.toString(attribute.dataspace().dimensions()) + " " + values;
    }

    /** The same object, as a new handle with nothing loaded yet. */
    private static Hdf5Object fresh(Hdf5File h5, Hdf5Object object) {
        return Hdf5Object.classify(h5.root().ctx, object.name(), "", object.objectHeaderAddress());
    }

    private static void forEachObject(Hdf5Object object, Set<Long> seen, Consumer<Hdf5Object> action) {
        if (!seen.add(object.objectHeaderAddress())) {
            return;
        }
        action.accept(object);
        if (object instanceof Group group) {
            for (Hdf5Object child : group.children()) {
                forEachObject(child, seen, action);
            }
        }
    }

    /** Counts the bytes a reader is asked for. */
    private static final class CountingReader implements RangeReader {
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

    private interface Work {
        void on(Hdf5File h5);
    }

    /** The bytes {@code work} makes Falcon read from a fresh open of {@code file}, beyond opening it. */
    private static long bytesRead(Path file, Work work) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            CountingReader reader = new CountingReader(RangeReader.of(channel));
            try (Hdf5File h5 = Hdf5File.open(reader)) {
                long opened = reader.bytes.get();
                work.on(h5);
                return reader.bytes.get() - opened;
            }
        }
    }

    /**
     * A lookup in a 20,000-link dense group reads the name index and one heap object, not every link: in
     * this file, about a fifth of the bytes the listing reads. The 3,000 attributes and the old-style
     * group's 5,000 links lie within a few of the reader's 64 KiB pages, so there a lookup can only be
     * held to reading no more than the listing; {@code Benchmarks} times the difference.
     */
    @Test
    void aLookupByNameReadsTheIndexNotTheWholeGroup() throws IOException {
        Path dense = Fixtures.path("dense_big.h5");
        long lookup = bytesRead(dense, h5 -> assertTrue(h5.root().group("many").link("link12345").isPresent()));
        long listing = bytesRead(dense, h5 -> assertEquals(20008, h5.root().group("many").links().size()));
        assertTrue(lookup * 3 < listing, "dense link lookup read " + lookup + " bytes, the listing " + listing);

        long attribute = bytesRead(dense, h5 -> assertEquals(2345,
                h5.root().dataset("d").attribute("attr2345").orElseThrow().readInt()));
        long attributes = bytesRead(dense, h5 -> assertEquals(3008, h5.root().dataset("d").attributes().size()));
        assertTrue(attribute <= attributes, "attribute lookup read " + attribute + " bytes, the listing " + attributes);

        Path old = Fixtures.path("oldstyle_big.h5");
        long oldLookup = bytesRead(old, h5 -> assertTrue(h5.root().group("many").link("link3456").isPresent()));
        long oldListing = bytesRead(old, h5 -> assertEquals(5009, h5.root().group("many").links().size()));
        assertTrue(oldLookup <= oldListing, "old-style lookup read " + oldLookup + " bytes, the listing " + oldListing);
    }

    @Test
    void oldStyleGroupsWrittenByFalconAreOrderedForLookup(@TempDir Path dir) throws IOException {
        // strcmp order (UTF-8 bytes) differs from Java's UTF-16 order for a supplementary character.
        List<String> names = new ArrayList<>(List.of("été", "日本語", "😀", "",
                "Zeta", "zeta", "a b"));
        for (int i = 0; i < 100; i++) {
            names.add("n" + i);
        }
        Path file = dir.resolve("old.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, Hdf5Writer.Format.EARLIEST)) {
            for (int i = 0; i < names.size(); i++) {
                w.intDataset(names.get(i), new int[] {i}, new long[] {1});
            }
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            for (int i = 0; i < names.size(); i++) {
                Group root = (Group) Hdf5Object.classify(h5.root().ctx, "", "/", h5.root().objectHeaderAddress());
                assertEquals(i, root.dataset(names.get(i)).readInt(), names.get(i));
            }
        }
    }
}
