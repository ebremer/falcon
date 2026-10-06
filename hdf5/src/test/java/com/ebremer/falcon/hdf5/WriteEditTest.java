package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Changing an existing file in place (P2 WF6): adding objects, links and attributes, writing data into its
 * datasets, and deleting links and attributes, in files Falcon wrote and files libhdf5 wrote. (libhdf5
 * reads the changed files in {@code WriterInteropExport}.)
 */
class WriteEditTest {

    @TempDir
    Path dir;

    private Path copy(String fixture) throws IOException {
        Path file = dir.resolve(fixture);
        Files.copy(Fixtures.path(fixture), file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    private static int[] range(int n) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }
        return values;
    }

    private static List<String> names(Group group) {
        List<String> names = new ArrayList<>();
        for (Link link : group.links()) {
            names.add(link.name());
        }
        names.sort(null);
        return names;
    }

    /** A file Falcon wrote, changed in two sessions. */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void changesAFileFalconWrote(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("edit.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            w.intDataset("ints", range(12), new long[] {3, 4});
            w.createDataset("rows", Datatype.float64(), 0, 2).chunked(2, 2).maxShape(Hdf5Writer.UNLIMITED, 2).deflate(4)
                    .append(new double[] {0, 0.5, 1, 1.5, 2, 2.5});
            w.shortDataset("small", new short[] {1, 2, 3}, new long[] {3}).compact();
            Hdf5Writer.GroupWriter g = w.group("g");
            g.intDataset("old", new int[] {9}, new long[] {1});
            g.intDataset("kept", new int[] {8}, new long[] {1});
            g.stringAttribute("a1", "one").stringAttribute("a2", "two").stringAttribute("a3", "three");
        }

        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.intDataset("added", new int[] {1, 2}, new long[] {2});
            w.root().stringAttribute("title", "changed");
            Hdf5Writer.GroupWriter g = w.group("g");
            g.delete("old");
            for (int i = 0; i < 20; i++) {
                g.softLink("s" + i, "/ints");
            }
            g.stringAttribute("a1", "uno").deleteAttribute("a2").attribute("a4", Datatype.int32(), new long[] {2}, new int[] {4, 44});
            g.group("sub").intDataset("deep", new int[] {7}, new long[] {1});
            w.dataset("ints").write(new long[] {1, 1}, new long[] {2, 2}, new int[] {-1, -2, -3, -4})
                    .stringAttribute("units", "m");
            w.dataset("rows").append(new double[] {3, 3.5}).write(new long[] {0, 1}, new long[] {1, 1}, new double[] {-0.5});
            w.dataset("small").write(new long[] {2}, new long[] {1}, new short[] {30});
            w.createDataset("refs", Datatype.objectReference(), 3).write(new String[] {"/g/kept", "/added", "/g/sub/deep"});
            assertThrows(IllegalArgumentException.class, () -> w.intDataset("ints", new int[] {1}, new long[] {1}));
            assertThrows(IllegalStateException.class, () -> w.dataset("rows").deflate(1));
            assertThrows(IllegalArgumentException.class, () -> w.dataset("g"));
            assertThrows(IllegalArgumentException.class, () -> w.group("ints"));
            assertThrows(IllegalArgumentException.class, () -> w.delete("missing"));
        }

        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertEquals(List.of("added", "g", "ints", "refs", "rows", "small"), names(root));
            assertEquals("changed", root.attribute("title").orElseThrow().readString());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, -1, -2, 7, 8, -3, -4, 11}, root.dataset("ints").readInts());
            assertEquals("m", root.dataset("ints").attribute("units").orElseThrow().readString());
            assertArrayEquals(new double[] {0, -0.5, 1, 1.5, 2, 2.5, 3, 3.5}, root.dataset("rows").readDoubles());
            assertArrayEquals(new long[] {4, 2}, root.dataset("rows").dataspace().dimensions());
            assertArrayEquals(new int[] {1, 2, 30}, root.dataset("small").readInts());
            Group g = root.group("g");
            assertFalse(g.child("old").isPresent());
            assertEquals(22, g.links().size()); // kept, s0..s19, sub
            assertArrayEquals(new int[] {8}, g.dataset("kept").readInts());
            assertEquals(12, g.dataset("s7").readInts().length);
            assertEquals("uno", g.attribute("a1").orElseThrow().readString());
            assertTrue(g.attribute("a2").isEmpty());
            assertEquals("three", g.attribute("a3").orElseThrow().readString());
            assertArrayEquals(new int[] {4, 44}, g.attribute("a4").orElseThrow().readInts());
            assertArrayEquals(new int[] {7}, g.dataset("sub/deep").readInts());
            Hdf5Object[] refs = root.dataset("refs").readObjectReferences();
            assertEquals("/g/kept", refs[0].path());
            assertEquals("/g/sub/deep", refs[2].path());
        }

        // A second session on the changed file.
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.group("g").delete("sub").delete("s3").intDataset("sub", new int[] {5}, new long[] {1});
            w.dataset("rows").append(new double[] {4, 4.5});
            w.delete("small");
            w.root().deleteAttribute("title");
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertEquals(List.of("added", "g", "ints", "refs", "rows"), names(root));
            assertTrue(root.attribute("title").isEmpty());
            assertArrayEquals(new double[] {0, -0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5}, root.dataset("rows").readDoubles());
            Group g = root.group("g");
            assertEquals(21, g.links().size());
            assertArrayEquals(new int[] {5}, g.dataset("sub").readInts());
            assertTrue(g.link("s3").isEmpty());
        }
    }

    /** {@link Hdf5Writer#abort()} leaves the file as it was (but for contiguous data written in place). */
    @Test
    void abortLeavesTheFileAsItWas() throws IOException {
        Path file = copy("chunk_indexes.h5");
        byte[] before = Files.readAllBytes(file);
        Hdf5Writer w = Hdf5Writer.open(file);
        w.intDataset("more", range(100), new long[] {100});
        w.dataset("extensible").append(new int[] {12, 13, 14, 15});
        w.group("g").stringAttribute("x", "y");
        w.abort();
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    /** Files of a kind Falcon does not change are refused when opened. */
    @ParameterizedTest
    @ValueSource(strings = {"family_latest_0.h5", "fsinfo_v0_persist.h5"})
    void refusesFilesItDoesNotChange(String fixture) throws IOException {
        Path file = copy(fixture);
        assertThrows(HdfUnsupportedException.class, () -> Hdf5Writer.open(file));
    }

    /**
     * Writing a dataset whose filter Falcon cannot apply (a third-party one other than the five of S8) is
     * refused before anything is written. The file: Falcon's own, in the original format (no checksums), its
     * bitshuffle filter renumbered to 32010, which no plugin registers.
     */
    @Test
    void refusesDataItCannotFilter() throws IOException {
        Path file = dir.resolve("unknown_filter.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, Hdf5Writer.Format.EARLIEST)) {
            w.intChunkedDataset("d", range(10), new long[] {10}, new long[] {5}).bitshuffle();
        }
        byte[] bytes = Files.readAllBytes(file);
        byte[] name = "bitshuffle; see".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int at = 0;
        while (!Arrays.equals(bytes, at, at + name.length, name, 0, name.length)) {
            at++;
        }
        assertEquals(0x7D08, (bytes[at - 8] & 0xff) | (bytes[at - 7] & 0xff) << 8); // version 1: id, name length, flags, count
        bytes[at - 8] = 0x0A; // 32008 -> 32010
        Files.write(file, bytes);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            Hdf5Writer.DatasetWriter d = w.dataset("d");
            HdfUnsupportedException e = assertThrows(HdfUnsupportedException.class,
                    () -> d.write(new long[] {0}, new long[] {1}, new int[] {5}));
            assertTrue(e.getMessage().contains("filter 32010"), e.getMessage());
            d.stringAttribute("note", "attributes still change");
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset d = h5.root().dataset("d");
            assertEquals(32010, d.filters().getFirst().id());
            assertEquals("attributes still change", d.attribute("note").orElseThrow().readString());
        }
    }

    /** Every chunk index libhdf5 writes, rewritten as Falcon's after chunks are written. */
    @Test
    void writesIntoEveryChunkIndex() throws IOException {
        Path file = copy("chunk_indexes.h5");
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("single").write(new long[] {1}, new long[] {2}, new int[] {-1, -2});
            w.dataset("implicit").write(new long[] {8}, new long[] {2}, new int[] {-8, -9});
            w.dataset("fixed").write(new long[] {4}, new long[] {2}, new int[] {-4, -5});
            w.dataset("extensible").append(new int[] {12, 13, 14, 15, 16});
            w.dataset("extensible_big").write(new long[] {600}, new long[] {1}, new int[] {-600});
            w.dataset("extensible_gz").append(range(7));
            w.dataset("btree2").extend(5, 6).write(new long[] {4, 4}, new long[] {1, 2}, new int[] {44, 45});
            w.dataset("btree2_gz").write(new long[] {0, 0}, new long[] {1, 1}, new int[] {-100});
            w.dataset("btree2_deep").write(new long[] {39, 39}, new long[] {1, 1}, new int[] {-1});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertArrayEquals(new int[] {0, -1, -2, 3, 4}, root.dataset("single").readInts());
            int[] implicit = range(10);
            implicit[8] = -8;
            implicit[9] = -9;
            assertArrayEquals(implicit, root.dataset("implicit").readInts());
            int[] fixed = range(20);
            fixed[4] = -4;
            fixed[5] = -5;
            assertArrayEquals(fixed, root.dataset("fixed").readInts());
            assertArrayEquals(range(17), root.dataset("extensible").readInts());
            int[] big = range(1200);
            big[600] = -600;
            assertArrayEquals(big, root.dataset("extensible_big").readInts());
            int[] gz = Arrays.copyOf(range(200), 207);
            System.arraycopy(range(7), 0, gz, 200, 7);
            assertArrayEquals(gz, root.dataset("extensible_gz").readInts());
            int[] btree2 = root.dataset("btree2").readInts();
            assertEquals(30, btree2.length);
            assertEquals(5, btree2[1 * 6 + 1]);
            assertEquals(44, btree2[4 * 6 + 4]);
            assertEquals(45, btree2[4 * 6 + 5]);
            assertEquals(0, btree2[4 * 6 + 3]);
            assertEquals(-100, root.dataset("btree2_gz").readInts()[0]);
            int[] deep = root.dataset("btree2_deep").readInts();
            assertEquals(-1, deep[1599]);
            assertEquals(1598, deep[1598]);
        }
    }

    /** Groups and attributes of files libhdf5 wrote: compact and dense, original and new style. */
    @ParameterizedTest
    @ValueSource(strings = {"new_style_groups.h5", "old_style_groups.h5"})
    void changesGroupsLibhdf5Wrote(String fixture) throws IOException {
        Path file = copy(fixture);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            Hdf5Writer.GroupWriter alpha = w.group("alpha");
            for (int i = 0; i < 300; i++) {
                alpha.intDataset(String.format("n%03d", i), new int[] {i}, new long[] {1});
            }
            alpha.delete("delta");
            alpha.group("beta").delete("gamma").softLink("up", "/root_ds");
            w.group("empty").stringAttribute("now", "not empty");
            for (int i = 0; i < 12; i++) {
                w.root().attribute("r" + i, Datatype.int16(), new long[0], new int[] {i});
            }
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group alpha = h5.root().group("alpha");
            assertEquals(301, alpha.links().size());
            assertArrayEquals(new int[] {123}, alpha.dataset("n123").readInts());
            assertTrue(alpha.child("delta").isEmpty());
            Group beta = alpha.group("beta");
            assertEquals(List.of("up"), names(beta));
            assertArrayEquals(range(4), beta.dataset("up").readInts());
            assertEquals("not empty", h5.root().group("empty").attribute("now").orElseThrow().readString());
            assertEquals(12, h5.root().attributes().size());
            assertArrayEquals(new int[] {11}, h5.root().attribute("r11").orElseThrow().readInts());
        }
    }

    /** Dense links and attributes libhdf5 wrote, added to and deleted from. */
    @Test
    void changesDenseStorageLibhdf5Wrote() throws IOException {
        Path links = copy("dense_links.h5");
        try (Hdf5Writer w = Hdf5Writer.open(links)) {
            Hdf5Writer.GroupWriter dense = w.group("dense");
            dense.delete("link03").delete("link17").intDataset("link03", new int[] {-3}, new long[] {1});
            dense.externalLink("ext", "other.h5", "/x");
        }
        try (Hdf5File h5 = Hdf5File.open(links)) {
            Group dense = h5.root().group("dense");
            assertEquals(20, dense.links().size());
            assertArrayEquals(new int[] {-3}, dense.dataset("link03").readInts());
            assertArrayEquals(new int[] {5}, dense.dataset("link05").readInts());
            assertTrue(dense.link("link17").isEmpty());
            assertEquals(new Link.External("ext", "other.h5", "/x"), dense.link("ext").orElseThrow());
        }
        Path attributes = copy("dense_attrs.h5");
        try (Hdf5Writer w = Hdf5Writer.open(attributes)) {
            w.dataset("d").deleteAttribute("attr00").intAttribute("attr05", new int[] {-5}, new long[0])
                    .stringAttribute("added", "yes");
        }
        try (Hdf5File h5 = Hdf5File.open(attributes)) {
            Dataset d = h5.root().dataset("d");
            assertEquals(20, d.attributes().size());
            assertTrue(d.attribute("attr00").isEmpty());
            assertArrayEquals(new int[] {-5}, d.attribute("attr05").orElseThrow().readInts());
            assertArrayEquals(new int[] {190}, d.attribute("attr19").orElseThrow().readInts());
            assertEquals("yes", d.attribute("added").orElseThrow().readString());
        }
    }

    /**
     * Groups and objects that track creation order (h5py's {@code track_order}): links and attributes
     * added take the next creation order, and dense ones keep their creation-order index.
     */
    @ParameterizedTest
    @ValueSource(strings = {"tracked_order.h5", "tracked_order_old.h5"})
    void keepsCreationOrder(String fixture) throws IOException {
        Path file = copy(fixture);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.group("small").delete("a").intDataset("d", new int[] {100}, new long[] {1});
            w.group("big").delete("z05").softLink("new", "/d");
            w.dataset("d").intAttribute("x", new int[] {-1}, new long[0]).deleteAttribute("y")
                    .intAttribute("v", new int[] {118}, new long[0]);
            w.dataset("dd").deleteAttribute("q00").intAttribute("p", new int[] {7}, new long[0]);
            for (int i = 0; i < 9; i++) { // past the compact limit: dense, with a creation-order index
                w.dataset("d").intAttribute("m" + i, new int[] {i}, new long[0]);
            }
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertEquals(List.of("b", "c", "d"), names(root.group("small")));
            assertArrayEquals(new int[] {100}, root.group("small").dataset("d").readInts());
            Group big = root.group("big");
            assertEquals(20, big.links().size());
            assertTrue(big.link("z05").isEmpty());
            assertArrayEquals(range(3), big.dataset("new").readInts());
            Dataset d = root.dataset("d");
            assertEquals(12, d.attributes().size());
            assertArrayEquals(new int[] {-1}, d.attribute("x").orElseThrow().readInts());
            assertTrue(d.attribute("y").isEmpty());
            assertArrayEquals(new int[] {(int) 'w'}, d.attribute("w").orElseThrow().readInts());
            Dataset dd = root.dataset("dd");
            assertEquals(20, dd.attributes().size());
            assertArrayEquals(new int[] {7}, dd.attribute("p").orElseThrow().readInts());
        }
    }

    /**
     * A file whose objects share messages (SOHM): their data is written (a shared filter pipeline applies),
     * and new objects are added (see {@code WriteEditSharedTest} for the shared attributes).
     */
    @ParameterizedTest
    @ValueSource(strings = {"sohm.h5", "sohm_latest.h5"})
    void changesFilesWithSharedMessages(String fixture) throws IOException {
        Path file = copy(fixture);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("b").write(new long[] {4}, new long[] {2}, new int[] {-4, -5});
            w.intDataset("added", new int[] {1}, new long[] {1});
            w.group("group").intDataset("inner", new int[] {2}, new long[] {1});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(new int[] {0, 1, 2, 3, -4, -5}, h5.root().dataset("b").readInts());
            assertArrayEquals(new int[] {7}, h5.root().dataset("a").attribute("units").orElseThrow().readInts());
            assertArrayEquals(new int[] {1}, h5.root().dataset("added").readInts());
            assertArrayEquals(new int[] {2}, h5.root().dataset("group/inner").readInts());
            assertEquals("shared", h5.root().group("group").attribute("title").orElseThrow().readString());
        }
    }

    /**
     * Contiguous and compact datasets libhdf5 wrote: written in place; one never allocated gets its block,
     * filled with its fill value; a big-endian one converts the values.
     */
    @Test
    void writesContiguousAndCompactData() throws IOException {
        Path file = copy("data_contiguous.h5");
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("c_i4").write(new long[] {1}, new long[] {2}, new int[] {-1, -2});
            w.dataset("c_be_i4").write(new long[] {4}, new long[] {1}, new int[] {-4});
            w.dataset("c_2d").write(new long[] {1, 0}, new long[] {1, 3}, new int[] {9, 9, 9});
            w.dataset("c_str").write(new long[] {3}, new long[] {1}, new String[] {"xyz"});
            w.dataset("compact_i4").write(new long[] {2}, new long[] {1}, new int[] {33});
            w.dataset("unwritten").write(new long[] {1}, new long[] {1}, new int[] {1});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertArrayEquals(new int[] {0, -1, -2, 3, 4}, root.dataset("c_i4").readInts());
            assertArrayEquals(new int[] {0, 1, 2, 3, -4}, root.dataset("c_be_i4").readInts());
            assertArrayEquals(new int[] {0, 1, 2, 9, 9, 9}, root.dataset("c_2d").readInts());
            assertArrayEquals(new String[] {"abc", "de", "fghij", "xyz"}, root.dataset("c_str").readStrings());
            assertArrayEquals(new int[] {10, 20, 33}, root.dataset("compact_i4").readInts());
            assertArrayEquals(new int[] {7, 1, 7, 7}, root.dataset("unwritten").readInts());
        }
    }

    /** A reference to an object whose link was deleted in the session does not resolve. */
    @Test
    void referencesToDeletedObjectsFail() throws IOException {
        Path file = copy("new_style_groups.h5");
        Hdf5Writer w = Hdf5Writer.open(file);
        w.group("alpha").delete("beta");
        w.createDataset("refs", Datatype.objectReference(), 2).write(new String[] {"/root_ds", "/alpha/beta/gamma"});
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, w::close);
        assertTrue(missing.getMessage().contains("/alpha/beta/gamma"), missing.getMessage());
        w.abort();
    }

    /** A second hard link deleted lowers its object's reference count. */
    @Test
    void deletingAHardLinkLowersTheReferenceCount() throws IOException {
        Path file = copy("metadata.h5");
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(2, h5.root().dataset("plain").referenceCount());
        }
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.delete("hardlink");
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(1, h5.root().dataset("plain").referenceCount());
            assertTrue(h5.root().link("hardlink").isEmpty());
        }
    }

    /** Files with a user block, and with B-tree 'K' values other than libhdf5's defaults. */
    @ParameterizedTest
    @ValueSource(strings = {"userblock_v0.h5", "userblock_v3.h5", "btree_k_earliest.h5", "btree_k_latest.h5"})
    void changesFilesOfOtherLayouts(String fixture) throws IOException {
        Path file = copy(fixture);
        List<String> before;
        try (Hdf5File h5 = Hdf5File.open(file)) {
            before = names(h5.root());
        }
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            Hdf5Writer.GroupWriter g = w.group("added");
            for (int i = 0; i < 200; i++) {
                g.softLink("l" + i, "/x" + i);
            }
            w.createDataset("chunks", Datatype.int32(), 0).chunked(1).maxShape(Hdf5Writer.UNLIMITED).append(range(300));
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            List<String> after = names(h5.root());
            assertTrue(after.containsAll(before));
            assertEquals(before.size() + 2, after.size());
            assertEquals(200, h5.root().group("added").links().size());
            assertArrayEquals(range(300), h5.root().dataset("chunks").readInts());
        }
    }
}
