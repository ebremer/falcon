package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dense storage of any size (P2 WF5): fractal heaps beyond one direct block (root and child indirect
 * blocks) and name indexes of several levels. (libhdf5 reads the same in {@code WriterInteropExport}.)
 */
class WriteDenseStorageTest {

    @TempDir
    Path dir;

    /** 100,000 links: a heap of about 2 MB, with child indirect blocks, under a name index four levels deep. */
    @Test
    void aGroupOfAHundredThousandLinks() throws IOException {
        Path file = dir.resolve("links.h5");
        int n = 100_000;
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            Hdf5Writer.GroupWriter g = w.group("many");
            for (int i = 0; i < 20; i++) {
                g.intDataset(String.format("d%06d", i), new int[] {i}, new long[] {1});
            }
            for (int i = 20; i < n; i++) {
                g.softLink(String.format("l%06d", i), String.format("d%06d", i % 20));
            }
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group g = h5.root().group("many");
            List<Link> links = g.links();
            assertEquals(n, links.size());
            Set<String> names = new HashSet<>();
            for (Link link : links) {
                names.add(link.name());
            }
            assertEquals(n, names.size());
            for (int i : new int[] {20, 4321, 50_000, 99_999}) {
                String name = String.format("l%06d", i);
                assertEquals(new Link.Soft(name, String.format("d%06d", i % 20)), g.link(name).orElseThrow());
                assertArrayEquals(new int[] {i % 20}, g.dataset(name).readInts(), name);
            }
            assertTrue(g.link("l100000").isEmpty());
        }
    }

    /** Thousands of attributes, and attributes from a few bytes to 60 KB: large blocks, and blocks skipped. */
    @Test
    void manyAndLargeAttributes() throws IOException {
        Path file = dir.resolve("attributes.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            Hdf5Writer.DatasetWriter many = w.intDataset("many", new int[] {0}, new long[] {1});
            for (int i = 0; i < 3000; i++) {
                many.intAttribute(String.format("a%04d", i), new int[] {i, -i}, new long[] {2});
            }
            Hdf5Writer.GroupWriter large = w.group("large");
            for (int i = 0; i < 40; i++) {
                double[] values = new double[1 + i * 187]; // 8 bytes to 58 KB
                java.util.Arrays.fill(values, i);
                large.doubleAttribute("x" + i, values, new long[] {values.length});
                large.stringAttribute("s" + i, "é".repeat(i));
            }
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset many = h5.root().dataset("many");
            assertEquals(3000, many.attributes().size());
            for (int i : new int[] {0, 1234, 2999}) {
                assertArrayEquals(new int[] {i, -i}, many.attribute(String.format("a%04d", i)).orElseThrow().readInts());
            }
            Group large = h5.root().group("large");
            assertEquals(80, large.attributes().size());
            for (int i = 0; i < 40; i++) {
                double[] values = large.attribute("x" + i).orElseThrow().readDoubles();
                assertEquals(1 + i * 187, values.length);
                assertEquals(i, values[values.length - 1]);
                assertEquals("é".repeat(i), large.attribute("s" + i).orElseThrow().readString());
            }
        }
    }

    /** Names whose hashes are equal are ordered by their bytes, as libhdf5 orders them. */
    @Test
    void namesOfEqualHashAreFound() throws IOException {
        Path file = dir.resolve("hashes.h5");
        // Distinct names until two share a lookup3 hash (the name index orders by hash, then name).
        java.util.Map<Integer, String> seen = new java.util.HashMap<>();
        String[] pair = null;
        for (int i = 0; pair == null; i++) {
            String name = "n" + i;
            String other = seen.put(Hdf5Object.nameHash(name), name);
            if (other != null) {
                pair = new String[] {other, name};
            }
        }
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            Hdf5Writer.GroupWriter g = w.group("g");
            for (int i = 0; i < 2000; i++) {
                g.softLink("f" + i, "/x");
            }
            g.softLink(pair[0], "/first").softLink(pair[1], "/second");
            g.attribute(pair[0], Datatype.int8(), new long[0], new int[] {1})
                    .attribute(pair[1], Datatype.int8(), new long[0], new int[] {2});
            for (int i = 0; i < 2000; i++) {
                g.attribute("a" + i, Datatype.int8(), new long[0], new int[] {0});
            }
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group g = h5.root().group("g");
            assertEquals(new Link.Soft(pair[0], "/first"), g.link(pair[0]).orElseThrow());
            assertEquals(new Link.Soft(pair[1], "/second"), g.link(pair[1]).orElseThrow());
            assertArrayEquals(new int[] {1}, g.attribute(pair[0]).orElseThrow().readInts());
            assertArrayEquals(new int[] {2}, g.attribute(pair[1]).orElseThrow().readInts());
        }
    }
}
