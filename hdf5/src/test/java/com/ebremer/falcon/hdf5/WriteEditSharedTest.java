package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Files whose objects share messages in the shared-message table (SOHM, P2 WF10): attributes kept there
 * are deleted, replaced and added to, compact and dense, their index's counts following, as libhdf5 keeps
 * them; a dataset that shares its dataspace grows, its own dataspace now. (libhdf5 reads and changes the
 * files further, deleting what is still shared, in {@code WriterInteropExport}.)
 */
class WriteEditSharedTest {

    @TempDir
    Path dir;

    private Path copy(String fixture) throws IOException {
        Path file = dir.resolve(fixture);
        Files.copy(Fixtures.path(fixture), file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    /** The shared-message heap ID of an object's shared message of {@code type}. */
    private static byte[] sharedId(Hdf5Object object, int type) {
        for (HeaderMessage message : object.header().messages()) {
            if (message.type() == type && (message.flags() & 0x02) != 0) {
                return message.buffer().getBytes(message.bodyOffset() + 2, 8);
            }
        }
        throw new AssertionError("no shared message of type " + type);
    }

    private static int count(Hdf5File h5, int type, byte[] heapId) {
        return SharedMessages.of(h5.context()).count(type, heapId);
    }

    /**
     * Shared attributes, in object headers and in dense storage: one held by two datasets is deleted from
     * one (its count drops), one held once is replaced (it leaves the index), dense ones deleted and added.
     */
    @ParameterizedTest
    @ValueSource(strings = {"sohm.h5", "sohm_latest.h5"})
    void changesSharedAttributes(String fixture) throws IOException {
        Path file = copy(fixture);
        byte[] units;
        byte[] title;
        try (Hdf5File h5 = Hdf5File.open(file)) {
            units = sharedId(h5.root().dataset("b"), MessageType.ATTRIBUTE);
            title = sharedId(h5.root().group("group"), MessageType.ATTRIBUTE);
            assertEquals(2, count(h5, MessageType.ATTRIBUTE, units));
            assertEquals(1, count(h5, MessageType.ATTRIBUTE, title));
        }
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("a").deleteAttribute("units");
            w.group("group").stringAttribute("title", "unshared now").stringAttribute("t", "x");
            Hdf5Writer.DatasetWriter many = w.dataset("many");
            many.deleteAttribute("attr00").deleteAttribute("attr01").intAttribute("attr05", new int[] {-5}, new long[0])
                    .stringAttribute("added", "yes");
            w.dataset("b").write(new long[] {4}, new long[] {2}, new int[] {-4, -5});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertTrue(root.dataset("a").attribute("units").isEmpty());
            assertArrayEquals(new int[] {7}, root.dataset("b").attribute("units").orElseThrow().readInts());
            assertEquals(1, count(h5, MessageType.ATTRIBUTE, units));
            assertEquals(0, count(h5, MessageType.ATTRIBUTE, title));
            assertEquals("unshared now", root.group("group").attribute("title").orElseThrow().readString());
            assertEquals("x", root.group("group").attribute("t").orElseThrow().readString());
            Dataset many = root.dataset("many");
            assertEquals(12, many.attributes().size());
            assertTrue(many.attribute("attr00").isEmpty());
            assertArrayEquals(new int[] {-5}, many.attribute("attr05").orElseThrow().readInts());
            assertArrayEquals(new int[] {11}, many.attribute("attr11").orElseThrow().readInts());
            assertEquals(1000, many.attribute("big").orElseThrow().readDoubles().length);
            assertEquals("yes", many.attribute("added").orElseThrow().readString());
            assertArrayEquals(new int[] {0, 1, 2, 3, -4, -5}, root.dataset("b").readInts());
        }
    }

    /**
     * An index that is a version-2 B-tree: attributes released from it (one leaving it, one's count
     * dropping), and datasets growing whose dataspaces are shared (in the heap) or shareable (kept in their
     * own header).
     */
    @Test
    void changesAnIndexThatIsABTree() throws IOException {
        Path file = copy("sohm_btree.h5");
        byte[] same;
        byte[] space;
        try (Hdf5File h5 = Hdf5File.open(file)) {
            same = sharedId(h5.root().dataset("d2"), MessageType.ATTRIBUTE);
            space = sharedId(h5.root().dataset("e2"), MessageType.DATASPACE);
            assertEquals(2, count(h5, MessageType.ATTRIBUTE, same));
            assertEquals(3, count(h5, MessageType.DATASPACE, space));
        }
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("d1").deleteAttribute("same");
            w.group("g").deleteAttribute("u0").intAttribute("u1", new int[] {-1}, new long[0]);
            w.dataset("e2").append(new int[] {4, 5, 6});
            w.dataset("e1").append(new int[] {9});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertEquals(1, count(h5, MessageType.ATTRIBUTE, same));
            assertTrue(root.dataset("d1").attribute("same").isEmpty());
            assertArrayEquals(new int[] {7}, root.dataset("d2").attribute("same").orElseThrow().readInts());
            assertTrue(root.group("g").attribute("u0").isEmpty());
            assertArrayEquals(new int[] {-1}, root.group("g").attribute("u1").orElseThrow().readInts());
            assertArrayEquals(new int[] {102}, root.group("g").attribute("u2").orElseThrow().readInts());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6}, root.dataset("e2").readInts());
            assertArrayEquals(new int[] {0, 1, 2, 3, 9}, root.dataset("e1").readInts());
            assertArrayEquals(new int[] {0, 1, 2, 3}, root.dataset("e3").readInts());
            assertEquals(1, count(h5, MessageType.DATASPACE, space), "only e3 shares the dataspace now");
        }
    }
}
