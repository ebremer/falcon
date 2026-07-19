package com.ebremer.falcon.hdf5.header;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.Fixtures;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.MappedHdfFile;
import com.ebremer.falcon.hdf5.superblock.Superblock;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectHeaderTest {

    private static FileContext contextFor(MappedHdfFile file, Superblock sb) {
        return new FileContext(file.buffer(), sb.sizeOfOffsets(), sb.sizeOfLengths());
    }

    @Test
    void parsesVersion1HeaderWithSymbolTable() throws IOException {
        try (MappedHdfFile file = MappedHdfFile.openReadOnly(Fixtures.path("old_style_groups.h5"))) {
            Superblock sb = Superblock.parse(file.buffer());
            ObjectHeader header = ObjectHeader.parse(contextFor(file, sb), sb.rootObjectHeaderAddress());
            assertEquals(1, header.version());
            assertTrue(header.contains(MessageType.SYMBOL_TABLE));
        }
    }

    @Test
    void parsesVersion2HeaderMessages() throws IOException {
        try (MappedHdfFile file = MappedHdfFile.openReadOnly(Fixtures.path("new_style_groups.h5"))) {
            Superblock sb = Superblock.parse(file.buffer());
            ObjectHeader header = ObjectHeader.parse(contextFor(file, sb), sb.rootObjectHeaderAddress());
            assertEquals(2, header.version());

            List<Integer> types = header.messages().stream().map(HeaderMessage::type).toList();
            assertTrue(types.contains(MessageType.LINK_INFO), "expected Link Info (2)");
            assertTrue(types.contains(MessageType.GROUP_INFO), "expected Group Info (10)");
            assertTrue(types.contains(MessageType.LINK), "expected Link (6)");
            assertFalse(types.contains(MessageType.NIL), "NIL padding should be dropped");
        }
    }
}
