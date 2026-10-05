package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.SharedMessage;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Read features added in P2, each from a fixture made by libhdf5: shared object header messages (SOHM)
 * revised references, and unlimited and printf-style virtual datasets.
 */
class P2ReadTest {

    @ParameterizedTest
    @ValueSource(strings = {"sohm.h5", "sohm_latest.h5"})
    void sharedObjectHeaderMessages(String fixture) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            Group root = h5.root();
            // libhdf5 keeps the first copy of each message in its object header and moves it to the
            // shared-message heap when a second object shares it: "b" refers to the heap for all four.
            Dataset b = root.dataset("b");
            for (int type : new int[] {MessageType.DATASPACE, MessageType.DATATYPE, MessageType.FILL_VALUE,
                    MessageType.FILTER_PIPELINE}) {
                HeaderMessage message = b.header().find(type);
                assertTrue(SharedMessage.isShared(message), "message type " + type + " of b is shared");
            }
            for (String name : List.of("a", "b")) {
                Dataset d = root.dataset(name);
                assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, d.readInts(), name);
                assertArrayEquals(new byte[] {-7, -1, -1, -1}, d.fillValueBytes().orElseThrow(), name);
                assertEquals(7, d.attribute("units").orElseThrow().readInt(), name);
            }
            assertEquals(42, root.dataset("s1").readInt());
            assertEquals(42, root.dataset("s2").readInt());
            assertEquals(Dataspace.Kind.SCALAR, root.dataset("s2").dataspace().kind());
            assertEquals("shared", root.group("group").attribute("title").orElseThrow().readString());

            Datatype.Compound pair = assertInstanceOf(Datatype.Compound.class, root.dataset("pair2").datatype());
            assertEquals(List.of("x", "y"), pair.members().stream().map(Datatype.Compound.Member::name).toList());

            // Dense attribute storage whose records name the shared-message heap, and a huge heap object.
            Dataset many = root.dataset("many");
            assertEquals(13, many.attributes().size());
            assertEquals(5, many.attribute("attr05").orElseThrow().readInt());
            double[] big = many.attribute("big").orElseThrow().readDoubles();
            assertEquals(1000, big.length);
            assertEquals(999.0, big[999]);
        }
    }

    @Test
    void revisedReferences() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("refs_revised.h5"))) {
            Group root = h5.root();
            long data = root.dataset("data").objectHeaderAddress();
            long grp = root.group("grp").objectHeaderAddress();
            Dataset objects = root.dataset("objects");
            // libhdf5 writes every H5T_STD_REF datatype as a (revised) object reference.
            assertEquals(Datatype.ReferenceKind.REVISED_OBJECT,
                    assertInstanceOf(Datatype.Reference.class, objects.datatype()).kind());
            Hdf5Object[] targets = assertInstanceOf(Hdf5Object[].class, objects.read());
            assertEquals(data, targets[0].objectHeaderAddress());
            assertEquals(grp, targets[1].objectHeaderAddress());
            assertNull(targets[2]);
            assertEquals(root.objectHeaderAddress(), targets[3].objectHeaderAddress());

            Selection[] regions = root.dataset("regions").readRegionReferences();
            assertTrue(regions[0].isRectangular());
            assertArrayEquals(new int[] {6, 7, 8, 11, 12, 13}, regions[0].readInts());
            assertArrayEquals(new int[] {0, 19, 12}, regions[1].readInts()); // points, as listed
            assertNull(regions[2]);
            assertEquals(20, regions[3].readInts().length);                  // all
            assertArrayEquals(new int[] {0, 1, 13, 14, 18, 19}, regions[4].readInts()); // two blocks

            Attribute[] attributes = root.dataset("attributes").readAttributeReferences();
            assertEquals(42, attributes[0].readInt());
            assertEquals("group", attributes[1].readString());
            assertNull(attributes[2]);

            // Each element carries its own kind.
            Dataset mixed = root.dataset("mixed");
            Hdf5Object[] mixedObjects = mixed.readObjectReferences();
            assertEquals(List.of(grp, data, data), Stream.of(mixedObjects).limit(3).map(Hdf5Object::objectHeaderAddress).toList());
            assertNull(mixedObjects[3]);
            Selection[] mixedRegions = mixed.readRegionReferences();
            assertThrows(HdfUnsupportedException.class, mixedRegions[0]::readInts);
            assertArrayEquals(new int[] {6, 7, 8, 11, 12, 13}, mixedRegions[1].readInts());
            assertThrows(HdfUnsupportedException.class, mixedRegions[2]::readInts);
            assertNull(mixedRegions[3]);
            assertThrows(HdfUnsupportedException.class, mixed::readAttributeReferences);

            // A reference into another file is followed into it (see OtherFileObjectsTest).
            Dataset external = root.dataset("external");
            assertArrayEquals(new int[] {0, 1, 2}, ((Dataset) external.readObjectReferences()[0]).readInts());

            Attribute onRoot = root.attribute("refs").orElseThrow();
            assertArrayEquals(new int[] {6, 7, 8, 11, 12, 13}, onRoot.readRegionReferences()[0].readInts());
            assertEquals(grp, onRoot.readObjectReferences()[1].objectHeaderAddress());

            // Original region references in an attribute.
            Selection[] old = assertInstanceOf(Selection[].class, root.attribute("old_regions").orElseThrow().read());
            assertArrayEquals(new int[] {6, 7, 8, 11, 12, 13}, old[0].readInts());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4}, old[1].readInts());
        }
    }

    /** The virtual datasets of vds_unlimited.h5, with libhdf5's own reading of each stored beside it. */
    private static final List<String> UNLIMITED_VDS = List.of("rows", "cols", "printf", "printf_moved", "printf_gap",
            "printf_names", "printf_none", "floored", "moved", "reldir");

    @ParameterizedTest
    @FieldSource("UNLIMITED_VDS")
    void virtualDatasetsReadAsLibhdf5ReadsThem(String name) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_unlimited.h5"))) {
            Dataset v = h5.root().dataset(name);
            long[] shape = v.attribute("expected_shape").orElseThrow().readLongs();
            assertArrayEquals(shape, v.dataspace().dimensions(), name + " extent");
            assertArrayEquals(v.attribute("expected").orElseThrow().readInts(), v.readInts(), name);
        }
    }

    @Test
    void virtualSourcesThePolicyRefuses() throws IOException {
        // An absolute name outside the directory is refused, then found by its file name beside the file
        // (as libhdf5 finds a moved source). Found nowhere, the read fails instead of filling: the refused
        // file may be the real source. Allowed to look there, Falcon fills as libhdf5 does.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_unlimited.h5"))) {
            assertArrayEquals(new int[] {10, 10}, h5.root().dataset("moved").readInts());
            Dataset lost = h5.root().dataset("lost");
            HdfUnsupportedException e = assertThrows(HdfUnsupportedException.class, lost::readInts);
            assertTrue(e.getMessage().contains("vds_lost.h5"), e.getMessage());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_unlimited.h5"), ExternalFileAccess.unrestricted())) {
            assertArrayEquals(new int[] {-1, -1}, h5.root().dataset("lost").readInts());
        }
        // With no access to other files, even the extent of an unlimited mapping cannot be found.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_unlimited.h5"), ExternalFileAccess.none())) {
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("rows").dataspace());
            assertArrayEquals(new long[] {8}, h5.root().dataset("printf_names").dataspace().dimensions()); // same file
        }
    }

    @Test
    void printfSourceNames() {
        assertEquals("src_12.h5", VirtualDataset.expand("src_%b.h5", 12));
        assertEquals("pct%_3_3.h5", VirtualDataset.expand("pct%%_%b_%b.h5", 3));
        assertEquals("100%", VirtualDataset.expand("100%%", -1));
        assertThrows(HdfFormatException.class, () -> VirtualDataset.expand("bad%d", 0));
        assertThrows(HdfFormatException.class, () -> VirtualDataset.expand("trailing%", 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sohm_latest.h5"})
    void tinySharedMessagesLiveInTheirHeapIds(String fixture) throws IOException {
        // The latest format's scalar dataspace (4 bytes) is a tiny object of the shared-message heap.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            Dataset s2 = h5.root().dataset("s2");
            HeaderMessage shared = s2.header().find(MessageType.DATASPACE);
            assertTrue(SharedMessage.isShared(shared));
            assertNotSame(s2.ctx.buffer(), SharedMessage.resolve(s2.ctx, shared).buffer());
        }
    }
}
