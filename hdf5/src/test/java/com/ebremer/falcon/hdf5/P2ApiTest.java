package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Read-API additions of P2: integer data read as floating point (A2), storage metadata (A3), and path
 * lookup (A5), each against what libhdf5 reports for the same files.
 */
class P2ApiTest {

    // ------------------------------------------------------------------ A2: numeric conversion

    @ParameterizedTest
    @ValueSource(strings = {"i8", "u8", "i16be", "u16", "i32", "u32be", "i64", "i64be", "u64", "u64be"})
    void integersReadAsFloatingPointAsLibhdf5ConvertsThem(String name) throws IOException {
        // libhdf5 rounds once, to nearest even: large int64 and uint64 values, and float32 from values
        // that rounding through double first would get wrong (2^60 + 2^36 + 1, 2^63 + 2^39 + 1).
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("conversions.h5"))) {
            Dataset dataset = h5.root().dataset(name);
            assertArrayEquals(dataset.attribute("expected_f8").orElseThrow().readDoubles(), dataset.readDoubles(), name);
            assertArrayEquals(dataset.attribute("expected_f4").orElseThrow().readFloats(), dataset.readFloats(), name);
            double[] expected = dataset.attribute("expected_f8").orElseThrow().readDoubles();
            assertArrayEquals(Arrays.copyOfRange(expected, 1, 3),
                    dataset.select(new long[] {1}, new long[] {2}).readDoubles(), name + " selection");
        }
    }

    @Test
    void integerAttributesAndSequencesReadAsFloatingPoint() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("conversions.h5"))) {
            Attribute u64 = h5.root().attribute("u64_attr").orElseThrow();
            assertArrayEquals(h5.root().attribute("u64_attr_f8").orElseThrow().readDoubles(), u64.readDoubles());
            assertArrayEquals(h5.root().attribute("u64_attr_f4").orElseThrow().readFloats(), u64.readFloats());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vlen_data.h5"))) {
            double[][] rows = h5.root().dataset("vseq_i4").readVlenDoubles();
            assertArrayEquals(new double[][] {{10}, {20, 21}, {}, {30, 31, 32}}, rows);
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("numeric.h5"))) {
            // Integers with a bit offset and reduced precision, against libhdf5's conversion to double.
            for (String name : List.of("i12_off4", "u12be_off3", "i24be", "i40_off20")) {
                Dataset dataset = h5.root().dataset(name);
                assertArrayEquals(dataset.attribute("expected").orElseThrow().readDoubles(), dataset.readDoubles(), name);
            }
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("datatypes.h5"))) {
            HdfUnsupportedException e = assertThrows(HdfUnsupportedException.class,
                    () -> h5.root().dataset("fixed_str").readDoubles());
            assertTrue(e.getMessage().contains("floating-point or integer"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ A3: storage metadata

    /** Fixture files in {@code storage_metadata.txt}, libhdf5's report for every dataset in them. */
    static Stream<String> storageFixtures() throws IOException {
        return storageLines().stream().map(line -> line[0]).distinct();
    }

    private static List<String[]> storageLines() throws IOException {
        List<String[]> lines = new ArrayList<>();
        for (String line : Files.readAllLines(Fixtures.path("storage_metadata.txt"), StandardCharsets.UTF_8)) {
            lines.add(line.split("\t", -1));
        }
        return lines;
    }

    @ParameterizedTest
    @MethodSource("storageFixtures")
    void storageMetadataMatchesLibhdf5(String file) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file))) {
            for (String[] f : storageLines()) {
                if (!f[0].equals(file)) {
                    continue;
                }
                Dataset dataset = h5.root().dataset(f[1]);
                String what = file + ":" + f[1];
                assertEquals(f[2], dataset.layout().name(), what + " layout");
                assertEquals(f[3], dataset.chunkShape().map(P2ApiTest::joined).orElse("-"), what + " chunk shape");
                assertEquals(Long.parseLong(f[4]), dataset.storageSize(), what + " storage size");
                List<Filter> filters = dataset.filters();
                assertEquals(Integer.parseInt(f[5]), filters.size(), what + " filters");
                for (int i = 0; i < filters.size(); i++) {
                    Filter filter = filters.get(i);
                    int at = 6 + 4 * i;
                    assertEquals(Integer.parseInt(f[at]), filter.id(), what + " filter id");
                    assertEquals((Integer.parseInt(f[at + 1]) & 1) != 0, filter.optional(), what + " optional");
                    long[] expected = f[at + 2].equals("-") ? new long[0]
                            : Arrays.stream(f[at + 2].split(" ")).mapToLong(Long::parseLong).toArray();
                    assertArrayEquals(expected, Arrays.stream(filter.clientData()).mapToLong(Integer::toUnsignedLong).toArray(),
                            what + " client data");
                    // This libhdf5 build has no szip, so names the stored filter as it does any unknown one.
                    String name = f[at + 3].equals("Unknown library filter") && filter.id() == Filter.SZIP ? "szip" : f[at + 3];
                    assertEquals(name, filter.name(), what + " filter name");
                }
            }
        }
    }

    @Test
    void filterRecordsAreValues() {
        int[] data = {1, 2};
        Filter filter = new Filter(Filter.ZSTD, "zstd", true, data);
        data[0] = 9;
        assertArrayEquals(new int[] {1, 2}, filter.clientData());
        filter.clientData()[1] = 9;
        assertEquals(new Filter(Filter.ZSTD, "zstd", true, new int[] {1, 2}), filter);
        assertEquals(new Filter(Filter.ZSTD, "zstd", true, new int[] {1, 2}).hashCode(), filter.hashCode());
    }

    private static String joined(long[] values) {
        return String.join(",", Arrays.stream(values).mapToObj(Long::toString).toList());
    }

    // ------------------------------------------------------------------ A5: paths

    @ParameterizedTest
    @ValueSource(strings = {"new_style_groups.h5", "old_style_groups.h5"})
    void pathsReachObjectsThroughGroups(String file) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file))) {
            Group root = h5.root();
            Dataset gamma = root.dataset("alpha/beta/gamma");
            assertEquals("/alpha/beta/gamma", gamma.path());
            assertEquals("gamma", gamma.name());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, gamma.readInts());
            // Repeated and trailing slashes, ".", and absolute paths from any group, as libhdf5 allows.
            assertEquals("/alpha/beta/gamma", root.dataset("alpha//beta/./gamma").path());
            assertEquals("/alpha/beta", root.group("alpha/beta/").path());
            Group alpha = root.group("alpha");
            assertEquals("/root_ds", alpha.dataset("/root_ds").path());
            assertEquals("/alpha/beta/gamma", alpha.dataset("/alpha/beta/gamma").path());
            assertEquals("/alpha/delta", alpha.child("delta").orElseThrow().path());
            assertSame(alpha, alpha.child(".").orElseThrow());
            assertEquals("/", alpha.child("/").orElseThrow().path());
            // Links by path: the link the last component names.
            Link beta = root.link("alpha/beta").orElseThrow();
            assertEquals("beta", beta.name());
            assertInstanceOf(Link.Hard.class, beta);
            assertEquals("root_ds", alpha.link("/root_ds").orElseThrow().name());
            assertTrue(root.link("/").isEmpty());
            assertTrue(root.link("alpha/.").isEmpty());
            assertTrue(root.link("alpha/missing").isEmpty());
            // Paths that reach nothing.
            assertTrue(root.child("alpha/missing").isEmpty());
            assertTrue(root.child("alpha/delta/x").isEmpty());
            assertTrue(root.child("").isEmpty());
            NoSuchElementException missing = assertThrows(NoSuchElementException.class,
                    () -> root.dataset("alpha/beta/missing"));
            assertTrue(missing.getMessage().contains("no child 'missing' in /alpha/beta"), missing.getMessage());
            NoSuchElementException notGroup = assertThrows(NoSuchElementException.class,
                    () -> root.dataset("alpha/delta/x"));
            assertTrue(notGroup.getMessage().contains("/alpha/delta is not a group"), notGroup.getMessage());
            IllegalArgumentException kind = assertThrows(IllegalArgumentException.class, () -> root.group("alpha/delta"));
            assertTrue(kind.getMessage().contains("is not a group"), kind.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"links.h5", "links_old.h5"})
    void pathsFollowSoftLinks(String file) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file))) {
            Group root = h5.root();
            // A soft link to a group, part-way along a path: the object is named by the path taken.
            Dataset x = root.dataset("links/soft_group/x");
            assertEquals("/links/soft_group/x", x.path());
            assertArrayEquals(new int[] {1, 2, 3}, x.readInts());
            assertArrayEquals(new int[] {1, 2, 3}, root.dataset("/links/chain").readInts());
            assertArrayEquals(new int[] {7}, root.group("links").dataset("soft_rel").readInts());
            assertInstanceOf(Link.Soft.class, root.link("links/soft_group").orElseThrow());
            assertTrue(root.child("links/loop_a").isEmpty());
            assertTrue(root.child("links/loop_a/x").isEmpty());
            NoSuchElementException dangling = assertThrows(NoSuchElementException.class,
                    () -> root.dataset("links/dangling/x"));
            assertTrue(dangling.getMessage().contains("'dangling' in /links is a soft link to /nowhere"), dangling.getMessage());
            if (file.equals("links.h5")) {
                // External links are followed; what they reach is named by its path in the other file.
                Dataset external = root.dataset("links/ext");
                assertArrayEquals(new int[] {42}, external.readInts());
                assertEquals("/y", external.path());
                assertTrue(root.child("links/ext/y").isEmpty()); // /y is a dataset
                NoSuchElementException beyond = assertThrows(NoSuchElementException.class, () -> root.dataset("links/ext/y"));
                assertTrue(beyond.getMessage().contains("/y is not a group"), beyond.getMessage());
                assertEquals(Optional.of("/y"), root.child("dense/ext").map(Hdf5Object::path));
                assertArrayEquals(new int[] {1, 2, 3}, root.dataset("dense/soft").readInts());
            }
        }
    }
}
