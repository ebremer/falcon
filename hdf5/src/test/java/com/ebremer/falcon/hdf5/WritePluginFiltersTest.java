package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Writing the third-party filters (P2 S8): LZF, Blosc, LZ4, bitshuffle, Zstandard and bzip2, as h5py and
 * hdf5plugin write them. Every dataset hdf5plugin wrote into plugin_filters_write.h5, written again by
 * Falcon from its values with the same settings, has the same filters (ids, names, flags, client data) and,
 * but for zstd, the same chunks byte for byte, skipped where the plugin skipped them. (libhdf5 with
 * hdf5plugin reads what Falcon writes in {@code WriterInteropExport}; each chunk's encoding is checked on its
 * own in {@link WriteFilterConformanceTest}.)
 */
class WritePluginFiltersTest {

    private static final String LZ4_NAME =
            "HDF5 lz4 filter; see https://github.com/HDFGroup/hdf5_plugins/blob/master/docs/RegisteredFilterPlugins.md";
    private static final String ZSTD_NAME =
            "HDF5 zstd filter; see https://github.com/HDFGroup/hdf5_plugins/blob/master/docs/RegisteredFilterPlugins.md";
    private static final String BITSHUFFLE_NAME = "bitshuffle; see https://github.com/kiyo-masui/bitshuffle";
    private static final String[] CNAMES = {"blosclz", "lz4", "lz4hc", "snappy", "zlib", "zstd"};
    private static final String[] SHUFFLES = {"noshuffle", "shuffle", "bitshuffle"};

    @TempDir
    Path dir;

    /** Every dataset of one source (its "source" attribute) reads the same, whatever its filters. */
    @Test
    void everyFilterOfTheFixtureReadsTheSameValues() throws IOException {
        Map<String, byte[]> sources = new HashMap<>();
        int checked = 0;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("plugin_filters_write.h5"))) {
            for (Hdf5Object object : h5.root().children()) {
                Dataset dataset = (Dataset) object;
                String source = dataset.attribute("source").orElseThrow().readString();
                byte[] values = dataset.readRawBytes();
                byte[] first = sources.putIfAbsent(source, values);
                if (first != null) {
                    assertArrayEquals(first, values, dataset.name());
                    checked++;
                }
            }
        }
        assertEquals(14, sources.size());
        assertEquals(86, checked);
    }

    /**
     * Each dataset hdf5plugin wrote, written again by Falcon from its values and settings: the same filter
     * pipeline, and the same chunks, filter masks included (zstd's frames aside).
     */
    @Test
    void writesWhatHdf5pluginWrote() throws IOException {
        Path out = dir.resolve("again.h5");
        List<String> names = new ArrayList<>();
        Map<String, Integer> sameChunks = new HashMap<>();
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("plugin_filters_write.h5"));
             Hdf5Writer w = Hdf5Writer.create(out)) {
            for (Hdf5Object object : h5.root().children()) {
                Dataset dataset = (Dataset) object;
                long[] shape = dataset.dataspace().dimensions();
                Hdf5Writer.DatasetWriter d = w.createDataset(dataset.name(), dataset.datatype(), shape)
                        .chunked(dataset.chunkShape().orElseThrow());
                if (dataset.dataspace().isUnlimited(0)) {
                    d.maxShape(Hdf5Writer.UNLIMITED);
                }
                for (Filter filter : dataset.filters()) {
                    apply(d, filter);
                }
                d.writeRaw(new long[shape.length], shape, dataset.readRawBytes());
                names.add(dataset.name());
            }
        }
        byte[] theirs = Files.readAllBytes(Fixtures.path("plugin_filters_write.h5"));
        byte[] ours = Files.readAllBytes(out);
        try (Hdf5File plugin = Hdf5File.open(Fixtures.path("plugin_filters_write.h5"));
             Hdf5File falcon = Hdf5File.open(out)) {
            for (String name : names) {
                Dataset expected = plugin.root().dataset(name);
                Dataset actual = falcon.root().dataset(name);
                assertEquals(expected.filters(), actual.filters(), name);
                assertArrayEquals(expected.readRawBytes(), actual.readRawBytes(), name);
                if (name.contains("zstd")) {
                    continue; // Falcon's zstd frames are its own
                }
                List<ChunkRecord> a = chunks(expected);
                List<ChunkRecord> b = chunks(actual);
                assertEquals(a.size(), b.size(), name);
                for (int i = 0; i < a.size(); i++) {
                    ChunkRecord x = a.get(i);
                    ChunkRecord y = b.get(i);
                    assertArrayEquals(x.offset(), y.offset(), name);
                    assertEquals(x.filterMask(), y.filterMask(), name + " chunk " + Arrays.toString(x.offset()));
                    byte[] xs = Arrays.copyOfRange(theirs, (int) x.address(), (int) (x.address() + x.size()));
                    byte[] ys = Arrays.copyOfRange(ours, (int) y.address(), (int) (y.address() + y.size()));
                    assertArrayEquals(xs, ys, name + " chunk " + Arrays.toString(x.offset()));
                    sameChunks.merge(name.substring(0, name.indexOf('_')), 1, Integer::sum);
                }
            }
        }
        assertEquals(100, names.size());
        assertEquals(Map.of("bitshuffle", 115, "blosc", 214, "grow", 12, "lz4", 37, "lzf", 63), sameChunks);
    }

    /** The DatasetWriter call that sets {@code filter} up as hdf5plugin (or h5py) did. */
    private static void apply(Hdf5Writer.DatasetWriter d, Filter filter) {
        int[] cd = filter.clientData();
        switch (filter.id()) {
            case Filter.SHUFFLE -> d.shuffle();
            case Filter.FLETCHER32 -> d.fletcher32();
            case Filter.LZF -> d.lzf();
            case Filter.BLOSC -> d.blosc(CNAMES[cd[6]], cd[4], SHUFFLES[cd[5]]);
            case Filter.LZ4 -> d.lz4(cd[0]);
            case Filter.BITSHUFFLE -> d.bitshuffle(new String[] {"none", "", "lz4", "zstd"}[cd[4]], cd[3],
                    cd.length > 5 ? cd[5] : 0);
            case Filter.ZSTD -> d.zstd(cd[0]);
            case Filter.BZIP2 -> d.bzip2(cd.length > 0 ? cd[0] : 9);
            default -> throw new AssertionError("filter " + filter);
        }
    }

    /**
     * bzip2.h5's datasets, written again by Falcon from their values with the same filters: every chunk the
     * plugin wrote is Falcon's byte for byte (libbzip2's), at each block size, after shuffle and before
     * fletcher32, in big-endian data, in noise bzip2 cannot shrink (stored compressed all the same), and in a
     * chunk of three bzip2 blocks.
     */
    @Test
    void writesWhatTheBzip2PluginWrote() throws IOException {
        Path out = dir.resolve("bzip2_again.h5");
        List<String> names = new ArrayList<>();
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("bzip2.h5"));
             Hdf5Writer w = Hdf5Writer.create(out)) {
            for (Hdf5Object object : h5.root().children()) {
                if (object instanceof Dataset dataset) {
                    long[] shape = dataset.dataspace().dimensions();
                    Hdf5Writer.DatasetWriter d = w.createDataset(dataset.name(), dataset.datatype(), shape)
                            .chunked(dataset.chunkShape().orElseThrow());
                    for (Filter filter : dataset.filters()) {
                        apply(d, filter);
                    }
                    d.writeRaw(new long[shape.length], shape, dataset.readRawBytes());
                    names.add(dataset.name());
                }
            }
        }
        byte[] theirs = Files.readAllBytes(Fixtures.path("bzip2.h5"));
        byte[] ours = Files.readAllBytes(out);
        int same = 0;
        try (Hdf5File plugin = Hdf5File.open(Fixtures.path("bzip2.h5"));
             Hdf5File falcon = Hdf5File.open(out)) {
            for (String name : names) {
                Dataset expected = plugin.root().dataset(name);
                Dataset actual = falcon.root().dataset(name);
                if (!name.equals("bzip2_no_options_i4")) { // Falcon records the block size it defaults to, 9
                    assertEquals(expected.filters(), actual.filters(), name);
                }
                assertArrayEquals(expected.readRawBytes(), actual.readRawBytes(), name);
                Map<String, ChunkRecord> written = new HashMap<>();
                for (ChunkRecord chunk : chunks(actual)) {
                    written.put(Arrays.toString(chunk.offset()), chunk);
                }
                for (ChunkRecord x : chunks(expected)) { // the chunks the plugin wrote (not the unwritten ones)
                    ChunkRecord y = written.get(Arrays.toString(x.offset()));
                    assertEquals(0, y.filterMask(), name);
                    byte[] xs = Arrays.copyOfRange(theirs, (int) x.address(), (int) (x.address() + x.size()));
                    byte[] ys = Arrays.copyOfRange(ours, (int) y.address(), (int) (y.address() + y.size()));
                    assertArrayEquals(xs, ys, name + " chunk " + Arrays.toString(x.offset()));
                    same++;
                }
            }
        }
        assertEquals(11, names.size());
        assertEquals(63, same);
    }

    private static List<ChunkRecord> chunks(Dataset dataset) {
        DataLayout.Chunked chunked = (DataLayout.Chunked) dataset.dataLayout();
        List<ChunkRecord> all = new ArrayList<>(dataset.chunkIndex(chunked).all());
        all.sort((p, q) -> Arrays.compare(p.offset(), q.offset()));
        return all;
    }

    /** What each method stores: hdf5plugin's defaults, and each plugin's set_local client data. */
    @Test
    void defaultsAndClientDataAreThePlugins() throws IOException {
        Path file = dir.resolve("defaults.h5");
        float[] values = new float[1000];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i % 37) * 0.5f;
        }
        Map<String, Consumer<Hdf5Writer.DatasetWriter>> setups = new java.util.LinkedHashMap<>();
        setups.put("lzf", Hdf5Writer.DatasetWriter::lzf);
        setups.put("blosc", Hdf5Writer.DatasetWriter::blosc);
        setups.put("blosc_zstd", d -> d.blosc("zstd", 9, "bitshuffle"));
        setups.put("lz4", Hdf5Writer.DatasetWriter::lz4);
        setups.put("lz4_blocks", d -> d.lz4(100));
        setups.put("bitshuffle", Hdf5Writer.DatasetWriter::bitshuffle);
        setups.put("bitshuffle_none", d -> d.bitshuffle("none", 16, 9));
        setups.put("bitshuffle_zstd", d -> d.bitshuffle("zstd", 0, 7));
        setups.put("zstd", Hdf5Writer.DatasetWriter::zstd);
        setups.put("zstd_fast", d -> d.zstd(-131072));
        setups.put("bzip2", Hdf5Writer.DatasetWriter::bzip2);
        setups.put("bzip2_small", d -> d.bzip2(1));
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            setups.forEach((name, setup) -> {
                Hdf5Writer.DatasetWriter d = w.createDataset(name, Datatype.float32(), 1000).chunked(300);
                setup.accept(d);
                d.write(values);
            });
        }
        Map<String, Filter> expected = Map.ofEntries(
                Map.entry("lzf", new Filter(Filter.LZF, "lzf", true, new int[] {4, 0x0105, 1200})),
                Map.entry("blosc", new Filter(Filter.BLOSC, "blosc", true, new int[] {2, 2, 4, 1200, 5, 1, 1})),
                Map.entry("blosc_zstd", new Filter(Filter.BLOSC, "blosc", true, new int[] {2, 2, 4, 1200, 9, 2, 5})),
                Map.entry("lz4", new Filter(Filter.LZ4, LZ4_NAME, true, new int[] {0})),
                Map.entry("lz4_blocks", new Filter(Filter.LZ4, LZ4_NAME, true, new int[] {100})),
                Map.entry("bitshuffle", new Filter(Filter.BITSHUFFLE, BITSHUFFLE_NAME, true, new int[] {0, 4, 4, 0, 2})),
                Map.entry("bitshuffle_none", new Filter(Filter.BITSHUFFLE, BITSHUFFLE_NAME, true,
                        new int[] {0, 4, 4, 16, 0})),
                Map.entry("bitshuffle_zstd", new Filter(Filter.BITSHUFFLE, BITSHUFFLE_NAME, true,
                        new int[] {0, 4, 4, 0, 3, 7})),
                Map.entry("zstd", new Filter(Filter.ZSTD, ZSTD_NAME, true, new int[] {3})),
                Map.entry("zstd_fast", new Filter(Filter.ZSTD, ZSTD_NAME, true, new int[] {-131072})),
                Map.entry("bzip2", new Filter(Filter.BZIP2, "bzip2", true, new int[] {9})),
                Map.entry("bzip2_small", new Filter(Filter.BZIP2, "bzip2", true, new int[] {1})));
        try (Hdf5File h5 = Hdf5File.open(file)) {
            for (String name : setups.keySet()) {
                Dataset d = h5.root().dataset(name);
                assertEquals(List.of(expected.get(name)), d.filters(), name);
                assertArrayEquals(values, d.readFloats(), name);
            }
        }
    }

    /**
     * Pipelines that combine them with the built-in filters, partial edge chunks, chunks a filter cannot
     * shrink (stored unfiltered, their mask bit set), datasets that grow, and every type size: all read back,
     * in both formats (version 1 pipeline messages pad each name to 8 bytes).
     */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void combinationsRoundTrip(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve(format + ".h5");
        Random random = new Random(11);
        int[] smooth = new int[2000];
        int[] noise = new int[2000];
        for (int i = 0; i < smooth.length; i++) {
            smooth[i] = i / 3 + (i % 5);
            noise[i] = random.nextInt();
        }
        double[] doubles = new double[777];
        for (int i = 0; i < doubles.length; i++) {
            doubles[i] = Math.sin(i / 10.0) * 100;
        }
        String[] labels = new String[45];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "label " + (i % 4) + " " + "x".repeat(i % 7);
        }
        float[] vectors = new float[300];
        for (int i = 0; i < vectors.length; i++) {
            vectors[i] = i % 3 == 0 ? i : -i * 0.25f;
        }
        // the datatypes of the strings, the array and the compound, from datasets given their data
        Path types = dir.resolve(format + "_types.h5");
        try (Hdf5Writer w = Hdf5Writer.create(types, format)) {
            w.root().fixedStringDataset("strings", labels, new long[] {45}, 300);
            w.float32ArrayDataset("vectors", new long[] {100}, new int[] {3}, vectors);
            w.compoundDataset("compound", new long[] {100}, Hdf5Writer.CompoundField.int32("a", Arrays.copyOf(smooth, 100)),
                    Hdf5Writer.CompoundField.float64("b", Arrays.copyOf(doubles, 100)));
        }
        try (Hdf5File typed = Hdf5File.open(types); Hdf5Writer w = Hdf5Writer.create(file, format)) {
            for (String name : List.of("strings", "vectors", "compound")) {
                Dataset d = typed.root().dataset(name);
                long n = d.dataspace().dimensions()[0];
                for (String filter : List.of("lz4", "blosc", "bitshuffle")) {
                    Hdf5Writer.DatasetWriter out = w.createDataset(name + "_" + filter, d.datatype(), n).chunked(n / 3);
                    switch (filter) {
                        case "lz4" -> out.lz4();
                        case "blosc" -> out.blosc("lz4hc", 4, "bitshuffle");
                        default -> out.bitshuffle("lz4", 8, 0);
                    }
                    out.writeRaw(new long[] {0}, new long[] {n}, d.readRawBytes());
                }
            }
            w.intChunkedDataset("shuffle_lzf_fletcher", smooth, new long[] {2000}, new long[] {300}).shuffle().lzf()
                    .fletcher32();
            w.intChunkedDataset("lzf_noise", noise, new long[] {2000}, new long[] {300}).lzf();
            w.intChunkedDataset("deflate_blosc", smooth, new long[] {40, 50}, new long[] {16, 16}).deflate(1)
                    .blosc("lz4", 9, "noshuffle");
            w.intChunkedDataset("blosc_noise", noise, new long[] {2000}, new long[] {300}).blosc("blosclz", 9, "shuffle");
            w.intChunkedDataset("blosc_c0", smooth, new long[] {2000}, new long[] {300}).blosc("zlib", 0, "shuffle");
            // in a version-1 object header, libhdf5 refuses a precision under half the type's bits
            w.intChunkedDataset("nbit_lz4", smooth, new long[] {2000}, new long[] {512})
                    .nbit(format == Hdf5Writer.Format.EARLIEST ? 16 : 12).lz4(64);
            w.intChunkedDataset("scaleoffset_zstd", smooth, new long[] {2000}, new long[] {512}).scaleOffset().zstd(19);
            w.intChunkedDataset("szip_bitshuffle", smooth, new long[] {2000}, new long[] {512}).szip().bitshuffle();
            w.doubleChunkedDataset("bitshuffle_zstd_doubles", doubles, new long[] {777}, new long[] {100})
                    .bitshuffle("zstd", 8, 1).fletcher32();
            w.createDataset("growing_zstd", Datatype.int32(), 0).chunked(256).maxShape(Hdf5Writer.UNLIMITED).zstd()
                    .append(Arrays.copyOf(smooth, 700)).append(Arrays.copyOfRange(smooth, 700, 1000));
            w.createDataset("tiny_lzf", Datatype.int8(), 3).chunked(3).lzf().write(new byte[] {1, 1, 1});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertArrayEquals(smooth, root.dataset("shuffle_lzf_fletcher").readInts());
            assertArrayEquals(noise, root.dataset("lzf_noise").readInts());
            assertArrayEquals(smooth, root.dataset("deflate_blosc").readInts());
            assertArrayEquals(noise, root.dataset("blosc_noise").readInts());
            assertArrayEquals(smooth, root.dataset("blosc_c0").readInts());
            assertArrayEquals(smooth, root.dataset("nbit_lz4").readInts());
            assertArrayEquals(smooth, root.dataset("scaleoffset_zstd").readInts());
            assertArrayEquals(smooth, root.dataset("szip_bitshuffle").readInts());
            assertArrayEquals(doubles, root.dataset("bitshuffle_zstd_doubles").readDoubles());
            for (String filter : List.of("lz4", "blosc", "bitshuffle")) {
                assertArrayEquals(labels, root.dataset("strings_" + filter).readStrings(), filter);
                assertArrayEquals(vectors, root.dataset("vectors_" + filter).readFloats(), filter);
                assertArrayEquals(Arrays.copyOf(smooth, 100), root.dataset("compound_" + filter).member("a").readInts(),
                        filter);
                assertArrayEquals(Arrays.copyOf(doubles, 100), root.dataset("compound_" + filter).member("b")
                        .readDoubles(), filter);
            }
            assertArrayEquals(Arrays.copyOf(smooth, 1000), root.dataset("growing_zstd").readInts());
            assertArrayEquals(new int[] {1, 1, 1}, root.dataset("tiny_lzf").readInts());
            // the chunks LZF and Blosc could not shrink are stored unfiltered, their mask bit set (the edge chunk,
            // a third zeros, shrinks)
            assertEquals(List.of(1, 1, 1, 1, 1, 1, 0), chunks(root.dataset("lzf_noise")).stream().map(ChunkRecord::filterMask)
                    .toList());
            assertEquals(List.of(1, 1, 1, 1, 1, 1, 0), chunks(root.dataset("blosc_noise")).stream()
                    .map(ChunkRecord::filterMask).toList());
            assertTrue(chunks(root.dataset("blosc_c0")).stream().allMatch(c -> c.filterMask() == 1));
            assertTrue(chunks(root.dataset("tiny_lzf")).stream().allMatch(c -> c.filterMask() == 1));
            // Blosc cannot shrink what deflate left: the second filter is skipped, bit 1
            assertTrue(chunks(root.dataset("deflate_blosc")).stream().allMatch(c -> c.filterMask() == 2));
            // the array's base type is what Blosc shuffles by
            assertEquals(4, root.dataset("vectors_blosc").filters().getFirst().clientData()[2]);
            assertEquals(12, root.dataset("vectors_bitshuffle").filters().getFirst().clientData()[2]);
            // a type above 255 bytes is a stream of bytes to Blosc
            assertEquals(1, root.dataset("strings_blosc").filters().getFirst().clientData()[2]);
            assertEquals(List.of(Filter.SHUFFLE, Filter.LZF, Filter.FLETCHER32),
                    root.dataset("shuffle_lzf_fletcher").filters().stream().map(Filter::id).toList());
            assertEquals("lzf", root.dataset("shuffle_lzf_fletcher").filters().get(1).name());
            assertEquals(BITSHUFFLE_NAME, root.dataset("szip_bitshuffle").filters().get(1).name());
        }
    }

    @Test
    void argumentsAreChecked() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("args.h5"))) {
            Hdf5Writer.DatasetWriter d = w.createDataset("d", Datatype.int32(), 100).chunked(10);
            assertThrows(IllegalArgumentException.class, () -> d.blosc("lzma", 5, "shuffle"));
            assertThrows(IllegalArgumentException.class, () -> d.blosc("lz4", 10, "shuffle"));
            assertThrows(IllegalArgumentException.class, () -> d.blosc("lz4", -1, "shuffle"));
            assertThrows(IllegalArgumentException.class, () -> d.blosc("lz4", 5, "byteshuffle"));
            assertThrows(NullPointerException.class, () -> d.blosc(null, 5, "shuffle"));
            assertThrows(IllegalArgumentException.class, () -> d.lz4(-1));
            assertThrows(IllegalArgumentException.class, () -> d.lz4(0x7E000001));
            assertThrows(IllegalArgumentException.class, () -> d.bitshuffle("lzf", 0, 0));
            assertThrows(IllegalArgumentException.class, () -> d.bitshuffle("lz4", 12, 0));
            assertThrows(IllegalArgumentException.class, () -> d.bitshuffle("lz4", -8, 0));
            assertThrows(IllegalArgumentException.class, () -> d.bitshuffle("zstd", 0, 23));
            assertThrows(IllegalArgumentException.class, () -> d.zstd(23));
            assertThrows(IllegalArgumentException.class, () -> d.zstd(-131073));
            assertThrows(IllegalArgumentException.class, () -> d.bzip2(0));
            assertThrows(IllegalArgumentException.class, () -> d.bzip2(10));
            d.lzf();
            assertThrows(IllegalStateException.class, d::lzf); // once each
            d.zstd(1);
            d.write(new int[100]);
            assertThrows(IllegalStateException.class, d::lz4); // configured before data is written
            Hdf5Writer.DatasetWriter contiguous = w.createDataset("c", Datatype.int32(), 10);
            assertThrows(IllegalStateException.class, contiguous::blosc); // chunked datasets only
            assertThrows(IllegalStateException.class, contiguous::bitshuffle);
        }
    }

    /**
     * Writing into datasets the plugins wrote (P2 S8): plugin_filters.h5, plugin_filters_write.h5 and bzip2.h5
     * copied, a run rewritten in every LZF, Blosc, LZ4, bitshuffle, Zstandard and bzip2 dataset, and the datasets
     * that can grow appended to; then everything reads back as written.
     */
    @Test
    void writesIntoDatasetsThePluginsWrote() throws IOException {
        for (String fixture : List.of("plugin_filters.h5", "plugin_filters_write.h5", "bzip2.h5")) {
            Path file = dir.resolve(fixture);
            Files.copy(Fixtures.path(fixture), file, StandardCopyOption.REPLACE_EXISTING);
            Map<String, byte[]> expected = new HashMap<>();
            List<String> names = new ArrayList<>();
            try (Hdf5File h5 = Hdf5File.open(file)) {
                for (Hdf5Object object : h5.root().children()) {
                    if (object instanceof Dataset dataset && !dataset.filters().isEmpty()) {
                        names.add(dataset.name());
                        expected.put(dataset.name(), dataset.readRawBytes());
                    }
                }
            }
            try (Hdf5Writer w = Hdf5Writer.open(file)) {
                for (String name : names) {
                    Hdf5Writer.DatasetWriter d = w.dataset(name);
                    byte[] values = expected.get(name);
                    long[] shape = d.shape();
                    int size = values.length / (int) Arrays.stream(shape).reduce(1, (a, b) -> a * b);
                    long[] offset = new long[shape.length];
                    long[] count = shape.clone();
                    count[0] = Math.min(3, shape[0]);
                    offset[0] = shape[0] / 2;
                    byte[] run = new byte[(int) (Arrays.stream(count).reduce(1, (a, b) -> a * b) * size)];
                    for (int i = 0; i < run.length; i++) {
                        run[i] = values[i % values.length]; // values the type takes (the first elements again)
                    }
                    d.writeRaw(offset, count, run);
                    int row = (int) (values.length / shape[0]);
                    System.arraycopy(run, 0, values, (int) offset[0] * row, run.length);
                    if (name.startsWith("grow_")) {
                        int[] more = {-1, -2, -3, -4, -5, -6, -7, -8, -9, -10};
                        d.append(more);
                        byte[] grown = Arrays.copyOf(values, values.length + 40);
                        java.nio.ByteBuffer.wrap(grown, values.length, 40).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .asIntBuffer().put(more);
                        values = grown;
                    }
                    expected.put(name, values);
                }
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                for (String name : names) {
                    assertArrayEquals(expected.get(name), h5.root().dataset(name).readRawBytes(), fixture + " " + name);
                }
            }
            assertEquals(Map.of("plugin_filters.h5", 20, "plugin_filters_write.h5", 100, "bzip2.h5", 11).get(fixture),
                    names.size());
        }
    }
}
