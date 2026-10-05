package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Valid files that Falcon once failed to read, each from an h5py fixture: fractal-heap huge objects and
 * nested indirect blocks, virtual datasets and region references in every selection encoding, External
 * File List slots of unlimited size, and the external-file access policy.
 */
class P1ReadTest {

    @Test
    void hugeHeapObjectsAndNestedIndirectBlocks() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("heap_limits.h5"))) {
            Dataset huge = h5.root().dataset("huge_attr");
            assertEquals(10, huge.attributes().size());
            double[] wide = huge.attribute("wide").orElseThrow().readDoubles();
            assertEquals(1000, wide.length);
            assertEquals(499.5, wide[999]);
            assertEquals(3, huge.attribute("small3").orElseThrow().readInt());
            double[] vast = h5.root().dataset("huge_alone").attribute("vast").orElseThrow().readDoubles();
            assertEquals(10000, vast.length);
            assertEquals(9999.0, vast[9999]);

            Group nested = h5.root().group("nested"); // > 520 KB of links: nested indirect heap blocks
            assertEquals(2100, nested.childNames().size());
            String last = String.format("%05d", 2099) + "n".repeat(250);
            assertTrue(nested.childNames().contains(last));
            assertEquals(3, nested.dataset(last).readInt());
        }
    }

    @Test
    void virtualDatasetsInEveryMappingEncoding() throws IOException {
        for (String file : new String[] {"vds_default.h5", "vds_latest.h5"}) {
            try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file))) {
                // Same-file sources ("." in a version-0 block, a flag in version 1), hyperslab v1 / v3.
                assertArrayEquals(new int[] {6, 7, -1, -1, 10, 11, -1, -1, 0, 1, 2, 3},
                        h5.root().dataset("same_file").readInts(), file);
                assertArrayEquals(new int[] {4, -1, 5, -1, 6, -1, 7, -1}, h5.root().dataset("strided").readInts(), file);
                // Version 1 shares repeated file and dataset names by entry index.
                assertArrayEquals(new int[] {0, 1, 2, 3, 0, 1, 2, 3, 10, 11, 12, 13, 10, 11, 12, 13},
                        h5.root().dataset("shared_names").readInts(), file);
            }
        }
    }

    @Test
    void regionReferencesInEverySelectionEncoding() throws IOException {
        for (String file : new String[] {"regionrefs_default.h5", "regionrefs_latest.h5"}) {
            try (Hdf5File h5 = Hdf5File.open(Fixtures.path(file))) {
                Dataset refs = h5.root().dataset("refs");
                Selection[] regions = refs.readRegionReferences();
                assertEquals(7, regions.length);
                for (int i = 0; i < 6; i++) {
                    int[] expected = refs.attribute("expected" + i).orElseThrow().readInts();
                    assertArrayEquals(expected, regions[i].readInts(), file + " ref " + i);
                }
                assertTrue(regions[0].isRectangular());
                assertArrayEquals(new long[] {2, 2}, regions[0].shape());
                assertFalse(regions[2].isRectangular());              // points, in the order listed
                assertArrayEquals(new long[] {3}, regions[2].shape());
                assertArrayEquals(new long[] {0, 0}, regions[2].offset());
                assertEquals(0, regions[5].elementCount());           // a "none" selection
                assertNull(regions[6]);                               // a null reference
            }
        }
    }

    @Test
    void oneUnresolvableRegionReferenceDoesNotFailTheOthers() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("regionrefs_latest.h5"))) {
            Dataset refs = h5.root().dataset("refs");
            byte[] raw = refs.readRawBytes();
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(12 + 8, 999); // ref 1 -> a missing heap object
            Selection[] regions = Hdf5Object.resolveRegionReferences(refs.ctx, MemorySegment.ofArray(raw), 7, 12);
            assertArrayEquals(new int[] {1, 2, 5, 6}, regions[0].readInts());
            assertThrows(HdfFormatException.class, regions[1]::readInts);
            assertThrows(HdfFormatException.class, regions[1]::dataset);
            assertArrayEquals(new int[] {1, 11, 4}, regions[2].readInts());
        }
    }

    @Test
    void unlimitedExternalFileSlot() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("external_paths.h5"))) {
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, h5.root().dataset("unlimited").readInts());
        }
    }

    @Test
    void externalFilesOutsideTheDirectoryAreRefusedByDefault() throws IOException {
        Path file = Fixtures.path("external_paths.h5");
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("parent").readInts());
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("absolute").readInts());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_outside.h5"))) {
            // Refused, not silently replaced by the fill value.
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("v").readInts());
        }
        try (Hdf5File h5 = Hdf5File.open(file, ExternalFileAccess.none())) {
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("unlimited").readInts());
        }
        try (Hdf5File h5 = Hdf5File.open(file, ExternalFileAccess.unrestricted())) {
            // Allowed now; the file itself does not exist.
            HdfException e = assertThrows(HdfException.class, () -> h5.root().dataset("parent").readInts());
            assertFalse(e instanceof HdfUnsupportedException, e.toString());
        }
        try (Hdf5File h5 = Hdf5File.open(file, ExternalFileAccess.sameDirectory().allowDirectory(file.getParent().getParent()))) {
            HdfException e = assertThrows(HdfException.class, () -> h5.root().dataset("parent").readInts());
            assertFalse(e instanceof HdfUnsupportedException, e.toString());
            assertThrows(HdfUnsupportedException.class, () -> h5.root().dataset("absolute").readInts());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_outside.h5"), ExternalFileAccess.unrestricted())) {
            assertArrayEquals(new int[] {-1, -1, -1, -1}, h5.root().dataset("v").readInts()); // missing source
        }
    }

    @Test
    void closedFilesFailWithATypedException() throws IOException {
        Hdf5File h5 = Hdf5File.open(Fixtures.path("links.h5"));
        Group root = h5.root();
        Dataset x = root.group("data").dataset("x");
        assertTrue(h5.isOpen());
        h5.close();
        h5.close(); // idempotent
        assertFalse(h5.isOpen());
        assertThrows(HdfClosedException.class, x::readInts);
        assertThrows(HdfClosedException.class, root::links);
        assertThrows(HdfClosedException.class, x::attributes);
        assertInstanceOf(HdfException.class, assertThrows(HdfClosedException.class, () -> root.group("data")));
        assertThrows(HdfClosedException.class, x::datatype);
    }

    @Test
    void sharedObjectHeaderMessagesAreReportedNotMisread() throws IOException {
        // Under SOHM every dataspace, datatype, fill value, pipeline and attribute message is a reference
        // into the shared-message heap. Falcon does not read that heap yet (P2 S1): it must say so.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("sohm.h5"))) {
            assertEquals(java.util.List.of("a", "b", "group"), h5.root().childNames().stream().sorted().toList());
            // The first dataset keeps its own copies of the messages; the second refers to the shared heap.
            Dataset a = h5.root().dataset("a");
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, a.readInts());
            Dataset b = h5.root().dataset("b");
            assertThrows(HdfUnsupportedException.class, b::dataspace);
            assertThrows(HdfUnsupportedException.class, b::readInts);
            assertThrows(HdfUnsupportedException.class, a::attributes); // its attribute's dataspace is shared
        }
    }
}
