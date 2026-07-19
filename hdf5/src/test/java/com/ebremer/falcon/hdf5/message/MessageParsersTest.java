package com.ebremer.falcon.hdf5.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Byte-level tests for the object-header messages modern HDF5 no longer emits, built from hand-authored
 * bodies per the spec's field offsets: object modification time (old, type 14), B-tree 'K' Values (type
 * 19), and Driver Info (type 20). The current modification-time message (type 18) is also covered here
 * and end-to-end by the metadata fixtures.
 */
class MessageParsersTest {

    @Test
    void modificationTimeCurrent() {
        // type 18: version(1), reserved(3), seconds since epoch(4, LE) = 1_700_000_000.
        byte[] body = {0, 0, 0, 0, 0x00, (byte) 0xf1, 0x53, 0x65};
        assertEquals(1_700_000_000L, ObjectModificationTimeMessage.epochSeconds(HdfBuffer.of(body)));
    }

    @Test
    void modificationTimeOld() {
        // type 14: 14 ASCII chars "YYYYMMDDHHMMSS" (UTC) + 2 reserved.
        byte[] body = "20240115103005\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        long expected = Instant.parse("2024-01-15T10:30:05Z").getEpochSecond();
        assertEquals(expected, ObjectModificationTimeMessage.epochSecondsOld(HdfBuffer.of(body)));
    }

    @Test
    void btreeKValues() {
        // type 19: version(1), indexed-storage internal K(2), group internal K(2), group leaf K(2).
        byte[] body = {0, 0x40, 0, 0x10, 0, 0x04, 0}; // 64, 16, 4
        BTreeKValuesMessage k = BTreeKValuesMessage.parse(HdfBuffer.of(body));
        assertEquals(64, k.indexedStorageInternalNodeK());
        assertEquals(16, k.groupInternalNodeK());
        assertEquals(4, k.groupLeafNodeK());
    }

    @Test
    void driverInfo() {
        // type 20: version(1), driver id(8 ASCII), info size(2), info(size).
        byte[] body = {0, 'N', 'C', 'S', 'A', 'f', 'a', 'm', 'i', 0x04, 0, 1, 2, 3, 4};
        DriverInfoMessage d = DriverInfoMessage.parse(HdfBuffer.of(body));
        assertEquals("NCSAfami", d.driverName());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, d.info());
    }
}
