package com.ebremer.falcon.hdf5.superblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.Fixtures;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.io.MappedHdfFile;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class SuperblockTest {

    @Test
    void parsesOriginalVersion0() throws IOException {
        try (MappedHdfFile file = MappedHdfFile.openReadOnly(Fixtures.path("old_style_groups.h5"))) {
            Superblock sb = Superblock.parse(file.buffer());
            assertEquals(0, sb.version());
            assertEquals(8, sb.sizeOfOffsets());
            assertEquals(8, sb.sizeOfLengths());
            assertEquals(0, sb.baseAddress());
            assertTrue(sb.rootObjectHeaderAddress() > 0);
            assertTrue(sb.rootObjectHeaderAddress() < sb.endOfFileAddress());
            // its root symbol table entry caches the root group's symbol table: a B-tree node and a local heap
            assertTrue(file.buffer().hasSignature(sb.rootBTreeAddress(), new byte[] {'T', 'R', 'E', 'E'}));
            assertTrue(file.buffer().hasSignature(sb.rootHeapAddress(), new byte[] {'H', 'E', 'A', 'P'}));
        }
    }

    @Test
    void parsesChecksummedVersion3AndVerifiesChecksum() throws IOException {
        // Superblock.parse throws on a checksum mismatch, so a clean parse is the verification.
        try (MappedHdfFile file = MappedHdfFile.openReadOnly(Fixtures.path("new_style_groups.h5"))) {
            Superblock sb = Superblock.parse(file.buffer());
            assertEquals(3, sb.version());
            assertEquals(8, sb.sizeOfOffsets());
            assertEquals(0, sb.baseAddress());
            assertTrue(sb.rootObjectHeaderAddress() > 0);
            assertTrue(sb.rootObjectHeaderAddress() < sb.endOfFileAddress());
            assertEquals(HdfBuffer.UNDEFINED_ADDRESS, sb.rootBTreeAddress(), "no root symbol table entry to cache one");
            assertEquals(HdfBuffer.UNDEFINED_ADDRESS, sb.rootHeapAddress());
        }
    }
}
