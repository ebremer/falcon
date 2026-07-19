package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * File Space Info (message 23) and free-space managers, reached through the superblock extension. The
 * fixture persists its free space with the free-space-manager strategy after deleting a dataset.
 */
class FreeSpaceTest {

    @Test
    void readsFileSpaceInfoAndFreeSpace() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("free_space.h5"))) {
            FileSpaceInfo info = h5.fileSpaceInfo().orElseThrow();
            assertEquals(FileSpaceInfo.Strategy.FSM_AGGR, info.strategy());
            assertTrue(info.persistingFreeSpace());
            assertEquals(1, info.freeSpaceSectionThreshold());
            assertEquals(1725, info.totalFreeSpace()); // matches h5py's H5Fget_freespace()
            assertTrue(info.freeSectionCount() > 0);
            assertEquals(Files.size(Fixtures.path("free_space.h5")), info.endOfFileAddress());
        }
    }

    @Test
    void filesWithoutASuperblockExtensionHaveNoInfo() throws IOException {
        // A v3-superblock file written with the default strategy has no superblock extension...
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("new_style_groups.h5"))) {
            assertTrue(h5.fileSpaceInfo().isEmpty());
        }
        // ...and a v0-superblock file has no extension mechanism at all.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("old_style_groups.h5"))) {
            assertTrue(h5.fileSpaceInfo().isEmpty());
        }
    }
}
