package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.header.MessageType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Older and rarer on-disk forms (P2 S5), superblock-level accessors (S6), and virtual-dataset views (S7),
 * each from a fixture libhdf5 made or read back: layout messages of versions 1 and 2, VAX floats, File
 * Space Info version 0, B-tree 'K' values, family-driver information, and the "first missing" view and
 * printf gap.
 */
class P2FormatsTest {

    @ParameterizedTest
    @CsvSource({"v1_chunked, 1", "v2_chunked, 2", "v1_contiguous, 1", "v2_compact, 2"})
    void layoutMessagesOfVersions1And2(String name, int version) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("legacy_layouts.h5"))) {
            Dataset dataset = h5.root().dataset(name);
            assertEquals(version, dataset.header().find(MessageType.DATA_LAYOUT).body().getUnsignedByte(0));
            assertArrayEquals(h5.root().group("expected").dataset(name).readRawBytes(), dataset.readRawBytes(), name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"vax_f32", "vax_f64"})
    void vaxOrderFloats(String name) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vax.h5"))) {
            Dataset dataset = h5.root().dataset(name);
            assertTrue(assertInstanceOfFloat(dataset.datatype()).vaxOrder());
            // libhdf5's own conversion of the stored values back to doubles.
            assertArrayEquals(dataset.attribute("expected").orElseThrow().readDoubles(), dataset.readDoubles(), name);
        }
    }

    private static Datatype.FloatingPoint assertInstanceOfFloat(Datatype type) {
        return org.junit.jupiter.api.Assertions.assertInstanceOf(Datatype.FloatingPoint.class, type);
    }

    @Test
    void fileSpaceInfoOfVersion0() throws IOException {
        // libhdf5 reads these as FSM_AGGR persisting (1312 bytes free) and AGGR, with threshold 1.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("fsinfo_v0_persist.h5"))) {
            FileSpaceInfo info = h5.fileSpaceInfo().orElseThrow();
            assertEquals(FileSpaceInfo.Strategy.FSM_AGGR, info.strategy());
            assertTrue(info.persistingFreeSpace());
            assertEquals(1, info.freeSpaceSectionThreshold());
            assertEquals(4096, info.fileSpacePageSize());
            assertEquals(1312, info.totalFreeSpace());
            assertEquals(99, h5.root().dataset("kept").readInts()[99]);
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("fsinfo_v0_aggr.h5"))) {
            FileSpaceInfo info = h5.fileSpaceInfo().orElseThrow();
            assertEquals(FileSpaceInfo.Strategy.AGGR, info.strategy());
            assertEquals(false, info.persistingFreeSpace());
            assertEquals(1, info.freeSpaceSectionThreshold());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"btree_k_earliest.h5", "btree_k_latest.h5"})
    void btreeKValues(String fixture) throws IOException {
        // Written with H5Pset_sym_k(8, 6) and H5Pset_istore_k(64): in a version-1 superblock, or the
        // extension's message 19.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            assertEquals(new BTreeKValues(6, 8, 64), h5.btreeKValues());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, h5.root().dataset("d").readInts());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vax.h5"))) {
            assertEquals(BTreeKValues.DEFAULTS, h5.btreeKValues());
            assertTrue(h5.driverInfo().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"family_earliest_0.h5", "family_latest_0.h5"})
    void familyDriverInformation(String fixture) throws IOException {
        // A version-0 superblock's driver information block, or the extension's message 20.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            DriverInfo driver = h5.driverInfo().orElseThrow();
            assertEquals("NCSAfami", driver.driverId());
            assertEquals(1 << 20, ByteBuffer.wrap(driver.information()).order(ByteOrder.LITTLE_ENDIAN).getLong());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, h5.root().dataset("d").readInts());
        }
    }

    @Test
    void virtualDatasetViewsAndPrintfGaps() throws IOException {
        for (OpenOptions.VirtualView view : OpenOptions.VirtualView.values()) {
            for (int gap = 0; gap <= 1; gap++) {
                OpenOptions options = OpenOptions.defaults().virtualView(view).virtualPrintfGap(gap);
                String key = (view == OpenOptions.VirtualView.FIRST_MISSING ? "first" : "last") + ":" + gap;
                try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_views.h5"), options)) {
                    for (String name : List.of("two_lengths", "printf_gap", "partial_block")) {
                        Dataset v = h5.root().dataset(name);
                        String what = name + " " + key;
                        assertArrayEquals(v.attribute("expected_shape:" + key).orElseThrow().readLongs(),
                                v.dataspace().dimensions(), what + " extent");
                        assertArrayEquals(v.attribute("expected:" + key).orElseThrow().readInts(), v.readInts(), what);
                    }
                }
            }
        }
    }
}
