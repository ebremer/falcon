package com.ebremer.falcon.hdf5.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import org.junit.jupiter.api.Test;

/**
 * Byte-level datatype tests for classes h5py cannot emit directly (time, bit field, and native
 * complex class 11), built from hand-authored message bodies per the spec.
 */
class DatatypeMessageTest {

    @Test
    void timeClass() {
        // version 1, class 2 (0x12); size 8; bit precision 64.
        byte[] body = {0x12, 0, 0, 0, 8, 0, 0, 0, 0x40, 0x00};
        var t = (Datatype.Time) DatatypeMessage.parse(HdfBuffer.of(body), 0);
        assertEquals(8, t.size());
        assertEquals(64, t.bitPrecision());
    }

    @Test
    void bitFieldClass() {
        // version 1, class 4 (0x14); size 4; bit offset 0, bit precision 32.
        byte[] body = {0x14, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0x20, 0x00};
        var bf = (Datatype.BitField) DatatypeMessage.parse(HdfBuffer.of(body), 0);
        assertEquals(4, bf.size());
        assertEquals(0, bf.bitOffset());
        assertEquals(32, bf.bitPrecision());
    }

    @Test
    void complexClass11WithFloatBase() {
        // version 5, class 11 (0x5b); size 8; base = a little-endian float32 message.
        byte[] header = {0x5b, 0, 0, 0, 8, 0, 0, 0};
        byte[] float32 = {
            0x11, 0x20, 0x1f, 0x00, 4, 0, 0, 0,   // class/version, bit field, size
            0, 0, 0x20, 0x00,                     // bit offset 0, bit precision 32
            0x17, 0x08, 0x00, 0x17,               // exp loc 23, exp size 8, mant loc 0, mant size 23
            0x7f, 0, 0, 0                          // exponent bias 127
        };
        byte[] body = new byte[header.length + float32.length];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(float32, 0, body, header.length, float32.length);

        var c = (Datatype.Complex) DatatypeMessage.parse(HdfBuffer.of(body), 0);
        assertEquals(8, c.size());
        var base = (Datatype.FloatingPoint) c.base();
        assertEquals(4, base.size());
        assertEquals(23, base.mantissaSize());
        assertEquals(127, base.exponentBias());
    }
}
