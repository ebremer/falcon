package com.ebremer.falcon.hdf5.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.HdfException;
import com.ebremer.falcon.hdf5.HdfFormatException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class HdfBufferTest {

    /** Builds a 64-byte fixture with known little-endian values at (deliberately unaligned) offsets. */
    private static HdfBuffer fixture() {
        byte[] data = new byte[64];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(0, (byte) 0x81);
        bb.putShort(1, (short) 0x1234);          // unaligned short
        bb.putInt(3, 0x11223344);                // unaligned int
        bb.putLong(7, 0x0102030405060708L);      // unaligned long
        bb.put(15, (byte) 0xEF);                 // 3-byte LE value 0xABCDEF
        bb.put(16, (byte) 0xCD);
        bb.put(17, (byte) 0xAB);
        for (int k = 20; k < 24; k++) {          // all-ones 4-byte address
            bb.put(k, (byte) 0xFF);
        }
        for (int k = 24; k < 32; k++) {          // all-ones 8-byte address
            bb.put(k, (byte) 0xFF);
        }
        bb.putInt(32, 0xFFFFFFFF);               // unsigned int 0xFFFFFFFF
        for (int k = 40; k < 46; k++) {          // 6-byte LE value 0x060504030201
            bb.put(k, (byte) (k - 40 + 1));
        }
        return HdfBuffer.of(data);
    }

    @Test
    void readsLittleEndianPrimitivesAtUnalignedOffsets() {
        HdfBuffer b = fixture();
        assertEquals((byte) 0x81, b.getByte(0));
        assertEquals(0x81, b.getUnsignedByte(0));
        assertEquals(0x1234, b.getUnsignedShort(1));
        assertEquals(0x11223344, b.getInt(3));
        assertEquals(0x0102030405060708L, b.getLong(7));
    }

    @Test
    void unsignedIntIsNotSignExtended() {
        assertEquals(4_294_967_295L, fixture().getUnsignedInt(32));
    }

    @Test
    void variableWidthUnsignedValues() {
        HdfBuffer b = fixture();
        assertEquals(0xABCDEFL, b.getUnsignedValue(15, 3));
        assertEquals(0x060504030201L, b.getUnsignedValue(40, 6));
        assertThrows(HdfException.class, () -> b.getUnsignedValue(0, 9));
        assertThrows(HdfException.class, () -> b.getUnsignedValue(0, 0));
    }

    @Test
    void undefinedAddressDetection() {
        HdfBuffer b = fixture();
        assertEquals(HdfBuffer.UNDEFINED_ADDRESS, b.getAddress(20, 4));
        assertEquals(HdfBuffer.UNDEFINED_ADDRESS, b.getAddress(24, 8));
        assertEquals(0x0102030405060708L, b.getAddress(7, 8)); // a real (defined) address
        assertTrue(HdfBuffer.isAllOnes(0xFFFFL, 2));
        assertFalse(HdfBuffer.isAllOnes(0xFFFEL, 2));
        assertTrue(HdfBuffer.isAllOnes(-1L, 8));
    }

    @Test
    void cursorReadsAdvancePosition() {
        HdfBuffer b = fixture();
        b.position(3);
        assertEquals(0x11223344, b.readInt());
        assertEquals(7, b.position());
        assertEquals(0x0102030405060708L, b.readLong());
        assertEquals(15, b.position());
        b.position(0);
        assertEquals(0x81, b.readUnsignedByte());
    }

    @Test
    void sliceIsRelativeAndBounded() {
        HdfBuffer b = fixture();
        HdfBuffer s = b.slice(7, 8);
        assertEquals(8, s.size());
        assertEquals(0x0102030405060708L, s.getLong(0));
        assertThrows(HdfFormatException.class, () -> s.getLong(1)); // 1+8 > 8
    }

    @Test
    void signatureMatching() {
        HdfBuffer b = fixture();
        byte[] fourFs = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertTrue(b.hasSignature(20, fourFs));
        assertFalse(b.hasSignature(0, fourFs));
        assertFalse(b.hasSignature(62, fourFs)); // would run off the end
    }

    @Test
    void getBytesCopies() {
        HdfBuffer b = fixture();
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xCD, (byte) 0xAB}, b.getBytes(15, 3));
    }

    @Test
    void outOfBoundsThrowsFormatException() {
        HdfBuffer b = fixture();
        assertThrows(HdfFormatException.class, () -> b.getInt(61)); // 61+4 > 64
        assertThrows(HdfFormatException.class, () -> b.getLong(57)); // 57+8 > 64
        assertThrows(HdfFormatException.class, () -> b.position(65));
    }
}
