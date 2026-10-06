package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.header.MessageType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Links (P2 WF10): hard links to objects of the session and of the file, moving and renaming links, external
 * links in groups of the original format (converted, as libhdf5 converts them), and dense storage going back
 * to compact once it shrinks. (libhdf5 reads the files in {@code WriterInteropExport}.)
 */
class WriteLinksEditTest {

    @TempDir
    Path dir;

    private Path copy(String fixture) throws IOException {
        Path file = dir.resolve(fixture);
        Files.copy(Fixtures.path(fixture), file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    private static List<String> names(Group group) {
        List<String> names = new ArrayList<>();
        for (Link link : group.links()) {
            names.add(link.name());
        }
        names.sort(null);
        return names;
    }

    /** Hard links in a new file: one object, several names, its count; one name deleted leaves the others. */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void hardLinksInANewFile(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("hard.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            w.group("a").intDataset("x", new int[] {1, 2, 3}, new long[] {3});
            Hdf5Writer.GroupWriter b = w.group("b");
            b.hardLink("y", "/a/x").hardLink("again", "/a/x").hardLink("up", "/a");
            w.hardLink("self", "/");                          // a cycle: the root reaches itself
            w.group("c").intDataset("only", new int[] {7}, new long[] {1});
            w.hardLink("kept", "/c/only");
            w.delete("c");                                    // the group goes; its dataset stays, as /kept
            w.referenceDataset("refs", new long[] {2}, new String[] {"/b/y", "/kept"});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            Dataset x = root.dataset("a/x");
            assertEquals(3, x.referenceCount());
            assertEquals(x.objectHeaderAddress(), root.dataset("b/y").objectHeaderAddress());
            assertEquals(x.objectHeaderAddress(), root.dataset("b/again").objectHeaderAddress());
            assertEquals(root.group("a").objectHeaderAddress(), root.group("b/up").objectHeaderAddress());
            assertEquals(2, root.group("a").referenceCount());
            assertEquals(root.objectHeaderAddress(), ((Link.Hard) root.link("self").orElseThrow()).objectHeaderAddress());
            assertEquals(2, root.referenceCount());
            assertTrue(root.link("c").isEmpty());
            assertArrayEquals(new int[] {7}, root.dataset("kept").readInts());
            assertEquals(1, root.dataset("kept").referenceCount());
            Hdf5Object[] refs = (Hdf5Object[]) root.dataset("refs").read();
            assertEquals(x.objectHeaderAddress(), refs[0].objectHeaderAddress());
            assertEquals(root.dataset("kept").objectHeaderAddress(), refs[1].objectHeaderAddress());
        }
    }

    /** Moving and renaming links in a new file: groups take what they hold; references name the new places. */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void movesInANewFile(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("move.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            Hdf5Writer.GroupWriter a = w.group("a");
            a.group("inner").intDataset("d", new int[] {1}, new long[] {1});
            Hdf5Writer.GroupWriter b = w.group("b");
            w.move("a", "/b/a2");                            // a group, with what it holds
            b.move("a2/inner/d", "/top");                    // a link at a path, to the root
            assertThrows(IllegalArgumentException.class, () -> w.move("missing", "/x"));
            w.intDataset("plain", new int[] {5}, new long[] {1});
            w.move("plain", "renamed");
            assertThrows(IllegalArgumentException.class, () -> w.move("renamed", "/no/such/group"));
            assertThrows(IllegalArgumentException.class, () -> w.move("renamed", "b"));
            w.referenceDataset("ref", new long[] {2}, new String[] {"/renamed", "/top"});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(List.of("b", "ref", "renamed", "top"), names(h5.root()));
            assertEquals(List.of("a2"), names(h5.root().group("b")));
            assertTrue(h5.root().group("b/a2/inner").links().isEmpty());
            assertArrayEquals(new int[] {5}, h5.root().dataset("renamed").readInts());
            assertArrayEquals(new int[] {1}, h5.root().dataset("top").readInts());
            Hdf5Object[] refs = (Hdf5Object[]) h5.root().dataset("ref").read();
            assertEquals(h5.root().dataset("renamed").objectHeaderAddress(), refs[0].objectHeaderAddress());
            assertEquals(h5.root().dataset("top").objectHeaderAddress(), refs[1].objectHeaderAddress());
        }
    }

    /** Moving a group into another, and a group into itself (refused). */
    @Test
    void movesGroupsWithWhatTheyHold() throws IOException {
        Path file = dir.resolve("groups.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            Hdf5Writer.GroupWriter a = w.group("a");
            a.group("inner").intDataset("d", new int[] {1, 2}, new long[] {2});
            a.softLink("soft", "inner/d");
            w.group("b");
            assertThrows(IllegalArgumentException.class, () -> w.move("a", "/a/inner/a"));
            w.move("a", "/b/a2");
            w.referenceDataset("ref", new long[] {1}, new String[] {"/b/a2/inner/d"});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(List.of("b", "ref"), names(h5.root()));
            assertArrayEquals(new int[] {1, 2}, h5.root().dataset("b/a2/inner/d").readInts());
            assertArrayEquals(new int[] {1, 2}, h5.root().dataset("b/a2/soft").readInts());
        }
    }

    /** Hard links and moves in files libhdf5 wrote, both group formats: counts, paths, and references follow. */
    @ParameterizedTest
    @ValueSource(strings = {"new_style_groups.h5", "old_style_groups.h5"})
    void hardLinksAndMovesInAFile(String fixture) throws IOException {
        Path file = copy(fixture);
        long gamma;
        int[] values;
        try (Hdf5File h5 = Hdf5File.open(file)) {
            gamma = h5.root().dataset("alpha/beta/gamma").objectHeaderAddress();
            values = h5.root().dataset("alpha/beta/gamma").readInts();
        }
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.hardLink("g2", "/alpha/beta/gamma");             // a second name for a dataset of the file
            w.group("empty").hardLink("back", "/alpha");
            w.group("alpha").move("beta", "/empty/beta2");     // a group of the file, moved
            w.move("root_ds", "renamed");                      // renamed
            w.group("added").intDataset("n", new int[] {4}, new long[] {1});
            w.group("empty").move("beta2/gamma", "/added/gamma"); // through a moved group (a path)
            w.referenceDataset("refs", new long[] {2}, new String[] {"/added/gamma", "/renamed"});
            assertThrows(IllegalArgumentException.class, () -> w.hardLink("bad", "/alpha/beta"));
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertEquals(List.of("added", "alpha", "empty", "g2", "refs", "renamed"), names(root));
            assertEquals(List.of("delta"), names(root.group("alpha")));
            assertEquals(List.of("back", "beta2"), names(root.group("empty")));
            assertTrue(root.group("empty/beta2").links().isEmpty());
            Dataset moved = root.dataset("added/gamma");
            assertEquals(gamma, moved.objectHeaderAddress());
            assertArrayEquals(values, moved.readInts());
            assertEquals(2, moved.referenceCount());
            assertEquals(2, root.group("alpha").referenceCount());
            Hdf5Object[] refs = (Hdf5Object[]) root.dataset("refs").read();
            assertEquals(gamma, refs[0].objectHeaderAddress());
            assertEquals(root.dataset("renamed").objectHeaderAddress(), refs[1].objectHeaderAddress());
        }
    }

    /**
     * An external link added to a group of the original format: libhdf5 converts the group to the new format
     * (link messages in its version-1 header), and so does Falcon, keeping its links; the root too, whose
     * superblock entry then caches no symbol table.
     */
    @Test
    void externalLinksConvertGroupsOfTheOriginalFormat() throws IOException {
        Path file = copy("old_style_groups.h5");
        int[] gamma;
        try (Hdf5File h5 = Hdf5File.open(file)) {
            gamma = h5.root().dataset("alpha/beta/gamma").readInts();
        }
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.group("alpha").externalLink("ext", "other.h5", "/x");
            w.externalLink("rootext", "other.h5", "/y");
            w.group("alpha").delete("delta");
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertEquals(List.of("alpha", "empty", "root_ds", "rootext"), names(root));
            assertEquals(List.of("beta", "ext"), names(root.group("alpha")));
            assertInstanceOf(Link.External.class, root.group("alpha").link("ext").orElseThrow());
            assertNull(root.group("alpha").header().find(MessageType.SYMBOL_TABLE));
            assertNull(root.header().find(MessageType.SYMBOL_TABLE));
            assertEquals(1, root.header().version());
            assertArrayEquals(gamma, root.dataset("alpha/beta/gamma").readInts());
        }
        Path created = dir.resolve("earliest.h5");
        try (Hdf5Writer w = Hdf5Writer.create(created, Hdf5Writer.Format.EARLIEST)) {
            w.externalLink("ext", "other.h5", "/x");
            w.group("g").softLink("s", "/ext");
            w.intDataset("d", new int[] {1}, new long[] {1});
        }
        try (Hdf5File h5 = Hdf5File.open(created)) {
            assertEquals(0, h5.superblockVersion());
            assertEquals(List.of("d", "ext", "g"), names(h5.root()));
            assertNull(h5.root().header().find(MessageType.SYMBOL_TABLE));
            assertTrue(h5.root().group("g").header().find(MessageType.SYMBOL_TABLE) != null, "a group without one keeps its table");
        }
    }

    /**
     * Dense storage shrunk below the group's or object's minimum (6) goes back to compact messages, as
     * libhdf5 moves it.
     */
    @Test
    void denseStorageShrinksBackToCompact() throws IOException {
        Path links = copy("dense_links.h5");
        try (Hdf5Writer w = Hdf5Writer.open(links)) {
            Hdf5Writer.GroupWriter dense = w.group("dense");
            for (int i = 0; i < 16; i++) {
                dense.delete(String.format("link%02d", i));
            }
        }
        try (Hdf5File h5 = Hdf5File.open(links)) {
            Group dense = h5.root().group("dense");
            assertEquals(List.of("link16", "link17", "link18", "link19"), names(dense));
            assertEquals(4, dense.header().messages().stream().filter(m -> m.type() == MessageType.LINK).count());
            assertArrayEquals(new int[] {19}, dense.dataset("link19").readInts());
        }
        Path attributes = copy("dense_attrs.h5");
        List<String> kept;
        try (Hdf5File h5 = Hdf5File.open(attributes)) {
            kept = new ArrayList<>(h5.root().dataset("d").attributes().stream().map(Attribute::name).toList());
        }
        try (Hdf5Writer w = Hdf5Writer.open(attributes)) {
            Hdf5Writer.DatasetWriter d = w.dataset("d");
            for (String name : kept.subList(0, 16)) {
                d.deleteAttribute(name);
            }
            d.stringAttribute("new", "one");
        }
        try (Hdf5File h5 = Hdf5File.open(attributes)) {
            Dataset d = h5.root().dataset("d");
            assertEquals(5, d.attributes().size());
            assertEquals(5, d.header().messages().stream().filter(m -> m.type() == MessageType.ATTRIBUTE).count());
            assertEquals("one", d.attribute("new").orElseThrow().readString());
            for (String name : kept.subList(16, 20)) {
                assertFalse(d.attribute(name).isEmpty(), name);
            }
        }
    }

    /** A dataset's hard links in a file being changed: one deleted, the other keeps it (its count lowered). */
    @Test
    void deletingOneOfTwoLinksKeepsTheObject() throws IOException {
        Path file = copy("metadata.h5");
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("hardlink").stringAttribute("via", "the second name");
            w.delete("plain");
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset kept = h5.root().dataset("hardlink");
            assertEquals(1, kept.referenceCount());
            assertEquals("the second name", kept.attribute("via").orElseThrow().readString());
        }
    }
}
