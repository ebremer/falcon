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

    @Test
    void referenceKindsByVersion() {
        // Codes 0 and 1 are the original object and region references; 2-4 are the revised (H5R_ref_t)
        // object, region, and attribute references of datatype version 4, and reserved before it.
        assertEquals(Datatype.ReferenceKind.OBJECT, referenceKind(0x17, 0));
        assertEquals(Datatype.ReferenceKind.DATASET_REGION, referenceKind(0x17, 1));
        assertEquals(Datatype.ReferenceKind.OTHER, referenceKind(0x17, 2));
        assertEquals(Datatype.ReferenceKind.REVISED_OBJECT, referenceKind(0x47, 0x12));
        assertEquals(Datatype.ReferenceKind.REVISED_DATASET_REGION, referenceKind(0x47, 0x13));
        assertEquals(Datatype.ReferenceKind.REVISED_ATTRIBUTE, referenceKind(0x47, 0x14));
    }

    private static Datatype.ReferenceKind referenceKind(int classAndVersion, int bits) {
        byte[] body = {(byte) classAndVersion, (byte) bits, 0, 0, 8, 0, 0, 0};
        return ((Datatype.Reference) DatatypeMessage.parse(HdfBuffer.of(body), 0)).kind();
    }

    @Test
    void version1CompoundArrayMembers() {
        // A pre-1.4 compound: member "v" is a 2x3 array of int16 (dimensionality in the member's legacy
        // block), member "s" a scalar int16 after it.
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(256).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x16).put((byte) 2).put((byte) 0).put((byte) 0).putInt(14);  // v1 compound, 2 members, 14 bytes
        b.put(new byte[] {'v', 0, 0, 0, 0, 0, 0, 0});                             // name padded to 8
        b.putInt(0).put((byte) 2).put(new byte[3]).putInt(0).putInt(0);         // offset, rank 2, rsv, perm, rsv
        b.putInt(2).putInt(3).putInt(0).putInt(0);                               // dimension sizes
        b.put(new byte[] {0x10, 0x08, 0, 0, 2, 0, 0, 0, 0, 0, 16, 0});           // int16
        b.put(new byte[] {'s', 0, 0, 0, 0, 0, 0, 0});
        b.putInt(12).put((byte) 0).put(new byte[3]).putInt(0).putInt(0);
        b.putInt(0).putInt(0).putInt(0).putInt(0);
        b.put(new byte[] {0x10, 0x08, 0, 0, 2, 0, 0, 0, 0, 0, 16, 0});
        var compound = (Datatype.Compound) DatatypeMessage.parse(HdfBuffer.of(java.util.Arrays.copyOf(b.array(), b.position())), 0);
        var v = (Datatype.Array) compound.members().get(0).type();
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {2, 3}, v.dimensions());
        assertEquals(12, v.size());
        assertEquals(2, v.base().size());
        assertEquals(12, compound.members().get(1).offset());
        assertEquals(Datatype.FixedPoint.class, compound.members().get(1).type().getClass());
    }
}
