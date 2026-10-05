package com.ebremer.falcon.hdf5.header;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** Shared-message bodies, built byte by byte (h5py writes only version 3). */
class SharedMessageTest {

    @Test
    void version1HoldsASymbolTableEntry() {
        // version 1: version, type, 6 reserved, then a symbol-table entry whose local-heap offset (L)
        // comes before the object-header address (O); libhdf5's H5O__shared_decode skips the offset.
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 1).put((byte) 0).put(new byte[6]).putLong(0x1234).putLong(0x800);
        FileContext ctx = new FileContext(HdfBuffer.of(b.array()), 8, 8);
        assertEquals(0x800, SharedMessage.objectHeaderAddress(ctx, 0));
    }

    @Test
    void versions2And3HoldTheAddressDirectly() {
        ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 2).put((byte) 0).putLong(0x900).put((byte) 3).put((byte) 2).putLong(0xA00);
        FileContext ctx = new FileContext(HdfBuffer.of(b.array()), 8, 8);
        assertEquals(0x900, SharedMessage.objectHeaderAddress(ctx, 0));
        assertEquals(0xA00, SharedMessage.objectHeaderAddress(ctx, 10));
    }
}
