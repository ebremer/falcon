package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-file object cache (P2 PF6): every handle of an object shares what any of them has read of it
 * (its header, attributes, links, and a dataset's datatype, shape, layout, and chunk index), however it was
 * reached, within the cache's bound.
 */
class ObjectCacheTest {

    /** A file of a group reached by two hard links, and {@code count} datasets with an attribute each. */
    private static Path file(Path dir, int count) throws IOException {
        Path path = dir.resolve("objects.h5");
        try (Hdf5Writer w = Hdf5Writer.create(path)) {
            Hdf5Writer.GroupWriter g = w.root().group("g");
            g.stringAttribute("title", "a group");
            g.intChunkedDataset("chunked", range(4096), new long[] {64, 64}, new long[] {8, 8});
            for (int i = 0; i < count; i++) {
                g.intDataset(String.format("d%04d", i), new int[] {i, i + 1, i + 2, i + 3}, new long[] {4})
                        .stringAttribute("units", "m");
            }
            w.root().hardLink("alias", "/g");
        }
        return path;
    }

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    @Test
    void handlesOfAnObjectShareWhatTheyRead(@TempDir Path dir) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(file(dir, 3))) {
            Group g = h5.root().group("g");
            Group alias = h5.root().group("alias");     // the same group, by another path
            assertEquals("/alias", alias.path());
            assertSame(g.state, alias.state);
            assertSame(g.links(), alias.links());
            assertSame(g.attributes(), alias.attributes());
            assertSame(g.header(), alias.header());

            Dataset a = g.dataset("chunked");
            Dataset b = h5.root().dataset("alias/chunked");
            assertNotSame(a, b);
            assertSame(a.datatype(), b.datatype());
            assertSame(a.dataspace(), b.dataspace());
            assertArrayEquals(range(4096), a.readInts());
            assertSame(a.state.chunkIndex, b.state.chunkIndex); // read once, for both
            assertArrayEquals(range(4096), b.readInts());

            // An object reached through a reference, and the children of a group, share it too.
            Hdf5Object dereferenced = Hdf5Object.dereference(g.ctx, a.objectHeaderAddress());
            assertSame(a.state, dereferenced.state);
            assertSame(a.state, ((Dataset) g.child("chunked").orElseThrow()).state);
        }
    }

    @Test
    void theCacheKeepsWithinItsBound(@TempDir Path dir) throws IOException {
        Path path = file(dir, 600);
        long bound = 64 << 10;
        try (Hdf5File h5 = Hdf5File.open(path, OpenOptions.defaults().objectCacheSize(bound))) {
            Group g = h5.root().group("g");
            List<Dataset> held = new ArrayList<>();
            for (Hdf5Object child : g.children()) {
                if (child instanceof Dataset d && d.name().startsWith("d")) {
                    assertEquals("m", d.attribute("units").orElseThrow().readStrings()[0]);
                    assertEquals(4, d.readInts().length);
                    held.add(d);
                }
            }
            long[] usage = ObjectCache.of(g.ctx).usage();
            assertTrue(usage[1] <= bound, "the cache keeps " + usage[1] + " bytes, over its bound of " + bound);
            assertTrue(usage[0] < 600, usage[0] + " objects kept");
            // A handle keeps what it read, evicted or not; a new handle of an evicted object reads it again.
            Dataset first = held.getFirst();
            Dataset again = g.dataset(first.name());
            assertNotSame(first.state, again.state);
            assertArrayEquals(first.readInts(), again.readInts());
            assertEquals(first.attributes().size(), again.attributes().size());
        }
    }

    @Test
    void aCacheOfZeroSharesNothing(@TempDir Path dir) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(file(dir, 2), OpenOptions.defaults().objectCacheSize(0))) {
            Dataset a = h5.root().dataset("g/chunked");
            Dataset b = h5.root().dataset("g/chunked");
            assertNotSame(a.state, b.state);
            assertArrayEquals(a.readInts(), b.readInts());
            assertEquals(0, ObjectCache.of(a.ctx).usage()[0]);
        }
    }

    @Test
    void threadsShareOneObjectsState(@TempDir Path dir) throws Exception {
        try (Hdf5File h5 = Hdf5File.open(file(dir, 50))) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<int[]>> reads = new ArrayList<>();
                for (int t = 0; t < 64; t++) {
                    int i = t % 50;
                    reads.add(pool.submit(() -> h5.root().group("alias").dataset(String.format("d%04d", i)).readInts()));
                }
                for (int t = 0; t < reads.size(); t++) {
                    int i = t % 50;
                    assertArrayEquals(new int[] {i, i + 1, i + 2, i + 3}, reads.get(t).get());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void optionsTakeTheCacheSize() {
        assertEquals(16L << 20, OpenOptions.defaults().objectCacheSize());
        assertEquals(1234, OpenOptions.defaults().objectCacheSize(1234).objectCacheSize());
        assertEquals(1234, OpenOptions.defaults().objectCacheSize(1234).chunkCacheSize(7).objectCacheSize());
        assertThrows(IllegalArgumentException.class, () -> OpenOptions.defaults().objectCacheSize(-1));
        assertTrue(OpenOptions.defaults().toString().contains("objectCacheSize=16777216"));
    }
}
