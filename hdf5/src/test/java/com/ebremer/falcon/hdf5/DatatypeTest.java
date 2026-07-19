package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.datatype.DatatypeClass;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Datatype decoding validated against a broad h5py-generated fixture (one dataset per class). */
class DatatypeTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("datatypes.h5"));
    }

    @AfterAll
    static void close() {
        if (h5 != null) {
            h5.close();
        }
    }

    private static Datatype dt(String name) {
        return h5.root().dataset(name).datatype();
    }

    @Test
    void fixedPointSizeSignAndOrder() {
        var i4 = (Datatype.FixedPoint) dt("i4");
        assertEquals(4, i4.size());
        assertTrue(i4.signed());
        assertEquals(ByteOrder.LITTLE_ENDIAN, i4.byteOrder());
        assertEquals(32, i4.bitPrecision());

        assertEquals(1, ((Datatype.FixedPoint) dt("i1")).size());
        assertEquals(8, ((Datatype.FixedPoint) dt("i8")).size());
        assertFalse(((Datatype.FixedPoint) dt("u1")).signed());
        assertFalse(((Datatype.FixedPoint) dt("u8")).signed());
        assertEquals(ByteOrder.BIG_ENDIAN, ((Datatype.FixedPoint) dt("be_i4")).byteOrder());
    }

    @Test
    void floatingPointParameters() {
        var f4 = (Datatype.FloatingPoint) dt("f4");
        assertEquals(4, f4.size());
        assertEquals(23, f4.mantissaSize());
        assertEquals(8, f4.exponentSize());
        assertEquals(127, f4.exponentBias());
        assertEquals(ByteOrder.LITTLE_ENDIAN, f4.byteOrder());

        assertEquals(1023, ((Datatype.FloatingPoint) dt("f8")).exponentBias());
        assertEquals(15, ((Datatype.FloatingPoint) dt("f2")).exponentBias());
        assertEquals(ByteOrder.BIG_ENDIAN, ((Datatype.FloatingPoint) dt("be_f8")).byteOrder());
    }

    @Test
    void fixedLengthString() {
        var s = (Datatype.StringType) dt("fixed_str");
        assertEquals(10, s.size());
        assertEquals(Datatype.StringPadding.NULL_PAD, s.padding());
        assertEquals(Datatype.CharacterSet.ASCII, s.characterSet());
    }

    @Test
    void opaqueAndReference() {
        assertEquals(8, ((Datatype.Opaque) dt("opaque")).size());
        assertEquals(Datatype.ReferenceKind.OBJECT, ((Datatype.Reference) dt("objref")).kind());
    }

    @Test
    void compoundMembers() {
        var c = (Datatype.Compound) dt("compound");
        assertEquals(12, c.size());
        assertEquals(2, c.members().size());

        var a = c.members().get(0);
        assertEquals("a", a.name());
        assertEquals(0, a.offset());
        assertEquals(DatatypeClass.FIXED_POINT, a.type().typeClass());
        assertEquals(4, a.type().size());

        var b = c.members().get(1);
        assertEquals("b", b.name());
        assertEquals(4, b.offset());
        assertEquals(DatatypeClass.FLOATING_POINT, b.type().typeClass());
        assertEquals(8, b.type().size());
    }

    @Test
    void enumerationAndBoolean() {
        var e = (Datatype.Enumeration) dt("enum");
        assertEquals(DatatypeClass.FIXED_POINT, e.base().typeClass());
        assertEquals(4, e.base().size());
        assertEquals(Map.of("RED", 0L, "GREEN", 1L, "BLUE", 2L), enumMap(e));

        var b = (Datatype.Enumeration) dt("boolean");
        assertEquals(1, b.size());
        assertEquals(Map.of("FALSE", 0L, "TRUE", 1L), enumMap(b));
    }

    @Test
    void variableLength() {
        var seq = (Datatype.VariableLength) dt("vlen_i4");
        assertEquals(Datatype.VlenKind.SEQUENCE, seq.kind());
        assertEquals(DatatypeClass.FIXED_POINT, seq.base().typeClass());
        assertEquals(4, seq.base().size());

        var str = (Datatype.VariableLength) dt("vlen_str");
        assertEquals(Datatype.VlenKind.STRING, str.kind());
        assertEquals(Datatype.CharacterSet.UTF8, str.characterSet());
    }

    @Test
    void arrayStandaloneAndAsCompoundMember() {
        var a = (Datatype.Array) dt("array_std");
        assertArrayEquals(new int[] {2, 3}, a.dimensions());
        assertEquals(24, a.size());
        assertEquals(6, a.elementCount());
        assertEquals(DatatypeClass.FLOATING_POINT, a.base().typeClass());
        assertEquals(4, a.base().size());

        var c = (Datatype.Compound) dt("comp_arr");
        var member = (Datatype.Array) c.members().get(1).type();
        assertArrayEquals(new int[] {2, 3}, member.dimensions());
        assertEquals(DatatypeClass.FLOATING_POINT, member.base().typeClass());
    }

    @Test
    void complexIsStoredAsCompoundByH5py() {
        // Documents that h5py/HDF5 2.0 writes numpy complex as a compound {r, i}, not native class 11.
        var c = (Datatype.Compound) dt("c8");
        assertEquals(2, c.members().size());
        assertEquals("r", c.members().get(0).name());
        assertEquals("i", c.members().get(1).name());
        assertEquals(DatatypeClass.FLOATING_POINT, c.members().get(0).type().typeClass());
    }

    private static Map<String, Long> enumMap(Datatype.Enumeration e) {
        Map<String, Long> m = new HashMap<>();
        for (var member : e.members()) {
            m.put(member.name(), member.value());
        }
        return m;
    }
}
