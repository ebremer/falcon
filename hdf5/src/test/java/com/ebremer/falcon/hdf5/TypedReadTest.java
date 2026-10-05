package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Typed reads of the composite datatypes (P2 A1): compound members, enumerations, arrays, complex
 * numbers, bit fields and opaque data, from files libhdf5 wrote ({@code typed.h5}, values as h5py reads
 * them) and from files Falcon wrote.
 */
class TypedReadTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("typed.h5"));
    }

    @AfterAll
    static void close() {
        h5.close();
    }

    private static Dataset ds(String name) {
        return h5.root().dataset(name);
    }

    // ------------------------------------------------------------------ compounds

    @Test
    void compoundMembersReadAsColumns() {
        Dataset records = ds("records");
        assertArrayEquals(new int[] {1, 2, 3}, records.member("id").readInts());
        assertArrayEquals(new int[] {-3, 40, -32768}, records.member("pos").member("x").readInts());
        assertArrayEquals(new double[] {1.5, -0.25, 1e30f}, records.member("pos").member("y").readDoubles());
        // An array member reads as its elements, every record's in turn.
        assertArrayEquals(new double[] {0.5, 1.5, 2.5, 3, 4, 5, -1, 0, 1}, records.member("vec").readDoubles());
        assertArrayEquals(new String[] {"RED", "BLUE", "GREEN"}, records.member("color").readStrings());
        assertArrayEquals(new int[] {0, 2, 1}, records.member("color").readInts());
        assertArrayEquals(new String[] {"alpha", "beta", "gamma!"}, records.member("name").readStrings());
        assertArrayEquals(new String[] {"one", "zwei é", ""}, records.member("label").readStrings());
        assertArrayEquals(new double[] {1, 2, -3.5, -0.25, 0, 0}, records.member("z").readComplexDoubles());

        Selection pos = records.member("pos");
        assertInstanceOf(Datatype.Compound.class, pos.datatype());
        assertArrayEquals(new long[] {3}, pos.shape());
        assertEquals(records, pos.dataset());
    }

    @Test
    void compoundReadsAsMapOfColumns() {
        Map<?, ?> columns = assertInstanceOf(Map.class, ds("records").read());
        assertEquals(List.of("id", "pos", "vec", "color", "name", "label", "z"), List.copyOf(columns.keySet()));
        assertArrayEquals(new int[] {1, 2, 3}, (int[]) columns.get("id"));
        Map<?, ?> pos = assertInstanceOf(Map.class, columns.get("pos"));
        assertArrayEquals(new int[] {-3, 40, -32768}, (int[]) pos.get("x"));
        assertArrayEquals(new double[] {0.5, 1.5, 2.5, 3, 4, 5, -1, 0, 1}, (double[]) columns.get("vec"));
        assertArrayEquals(new String[] {"RED", "BLUE", "GREEN"}, (String[]) columns.get("color"));
        assertArrayEquals(new String[] {"one", "zwei é", ""}, (String[]) columns.get("label"));
        Map<?, ?> z = assertInstanceOf(Map.class, columns.get("z")); // h5py's complex is a compound
        assertArrayEquals(new double[] {1, -3.5, 0}, (double[]) z.get("r"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) columns).put("x", 1));
    }

    @Test
    void compoundMembersOfSelectionsAndChunkedData() {
        Dataset table = ds("table"); // 6 x 5, chunks of 4 x 2, deflated; a = 10r + c, b = r + c/10
        assertArrayEquals(new int[] {12, 13, 22, 23}, table.select(new long[] {1, 2}, new long[] {2, 2}).member("a").readInts());
        // Rows 1, 3, 5 and columns 0, 3: a strided selection's member.
        Selection strided = table.select(new long[] {1, 0}, new long[] {2, 3}, new long[] {3, 2}, null);
        assertArrayEquals(new int[] {10, 13, 30, 33, 50, 53}, strided.member("a").readInts());
        assertArrayEquals(new double[] {1.0, 1.3, 3.0, 3.3, 5.0, 5.3}, strided.member("b").readDoubles());
        Selection points = table.selectPoints(new long[][] {{5, 4}, {0, 0}, {2, 3}});
        assertArrayEquals(new double[] {5.4, 0.0, 2.3}, points.member("b").readDoubles());
        int[] a = table.member("a").readInts();
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 5; c++) {
                assertEquals(10 * r + c, a[r * 5 + c]);
            }
        }
    }

    @Test
    void compoundAttributesReadByMember() {
        Dataset records = ds("records");
        Attribute rec = records.attribute("rec").orElseThrow();
        assertArrayEquals(new int[] {-3, 40}, rec.member("pos").member("x").readInts());
        assertArrayEquals(new String[] {"one", "zwei é"}, rec.member("label").readStrings());
        assertEquals("rec", rec.member("id").name());
        assertInstanceOf(Datatype.FixedPoint.class, rec.member("id").datatype());
        Map<?, ?> columns = assertInstanceOf(Map.class, rec.read());
        assertArrayEquals(new int[] {1, 2}, (int[]) columns.get("id"));
        assertArrayEquals(new String[] {"BLUE", "RED"}, records.attribute("color").orElseThrow().readStrings());
        assertArrayEquals(new double[] {1, -1}, records.attribute("z").orElseThrow().readComplexDoubles());
        Attribute zn = records.attribute("zn").orElseThrow(); // HDF5 2.0's native complex
        assertArrayEquals(new double[] {2, 3, -0.0, -1}, zn.readComplexDoubles());
        assertArrayEquals(new double[] {2, -0.0}, zn.readDoubles());
    }

    @Test
    void membersAreLookedUpByName() {
        Dataset records = ds("records");
        NoSuchElementException missing = assertThrows(NoSuchElementException.class, () -> records.member("nope"));
        assertTrue(missing.getMessage().contains("[id, pos, vec, color, name, label, z]"), missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> records.member("id").member("x"));
        assertThrows(IllegalArgumentException.class, () -> ds("enum_be").member("x"));
        assertThrows(IllegalArgumentException.class, () -> records.attribute("color").orElseThrow().member("x"));
        assertThrows(HdfUnsupportedException.class, records::readInts);
        assertThrows(HdfUnsupportedException.class, records::readDoubles);
    }

    // ------------------------------------------------------------------ enumerations

    @Test
    void enumerationsReadAsValuesOrNames() {
        Dataset be = ds("enum_be"); // big-endian int16 members NEG=-5, ZERO=0, BIG=300
        assertArrayEquals(new int[] {-5, 300, 0, -5}, be.readInts());
        assertArrayEquals(new double[] {-5, 300, 0, -5}, be.readDoubles());
        assertArrayEquals(new String[] {"NEG", "BIG", "ZERO", "NEG"}, be.readStrings());
        assertArrayEquals(new String[] {"NEG", "BIG", "ZERO", "NEG"}, (String[]) be.read());
        Datatype.Enumeration type = (Datatype.Enumeration) be.datatype();
        assertEquals(List.of(-5L, 0L, 300L), type.members().stream().map(Datatype.Enumeration.Member::value).sorted().toList());

        Dataset u8 = ds("enum_u8"); // uint8 members OFF=0, ON=255
        assertArrayEquals(new int[] {255, 0, 255}, u8.readInts());
        assertArrayEquals(new String[] {"ON", "OFF", "ON"}, u8.readStrings());
        assertEquals(255L, ((Datatype.Enumeration) u8.datatype()).members().get(1).value());

        Dataset unknown = ds("enum_unknown"); // 7 is no member's value
        assertArrayEquals(new int[] {0, 7, 2}, unknown.readInts());
        assertArrayEquals(new String[] {"RED", null, "BLUE"}, unknown.readStrings());
    }

    // ------------------------------------------------------------------ arrays

    @Test
    void arrayTypesReadAsTheirElements() {
        Dataset floats = ds("arr_f4"); // two elements of 2 x 3 floats
        float[] expected = new float[12];
        for (int i = 0; i < 12; i++) {
            expected[i] = i * 0.5f;
        }
        assertArrayEquals(expected, floats.readFloats());
        assertEquals(12, ((double[]) floats.read()).length);
        assertArrayEquals(new float[] {3, 3.5f, 4, 4.5f, 5, 5.5f}, floats.select(new long[] {1}, new long[] {1}).readFloats());
        assertArrayEquals(new String[] {"ab", "cd", "ef", "g"}, ds("arr_s").readStrings());
        assertArrayEquals(new String[] {"ab", "cd", "ef", "g"}, (String[]) ds("arr_s").read());
    }

    // ------------------------------------------------------------------ complex numbers

    @Test
    void complexNumbersReadAsPairsOrRealParts() throws IOException {
        assertArrayEquals(new double[] {1, 2, -3.5, -0.25, 0, 0}, ds("cx_h5py").readComplexDoubles());
        assertArrayEquals(new float[] {1, 2, -3.5f, -0.25f, 0, 0}, ds("cx_h5py").readComplexFloats());
        assertThrows(HdfUnsupportedException.class, () -> ds("cx_h5py").readDoubles()); // a compound, to libhdf5 too

        Dataset nativeComplex = ds("cx_native");
        assertInstanceOf(Datatype.Complex.class, nativeComplex.datatype());
        double[] pairs = {1, 2, -3.5, -0.25, 1e300, -1e-300};
        assertArrayEquals(pairs, nativeComplex.readComplexDoubles());
        assertArrayEquals(pairs, (double[]) nativeComplex.read());
        // libhdf5 converts a complex number to a real one by its real part.
        assertArrayEquals(new double[] {1, -3.5, 1e300}, nativeComplex.readDoubles());
        assertThrows(HdfUnsupportedException.class, nativeComplex::readInts);

        assertArrayEquals(new float[] {1, 2, -0.5f, 4}, ds("cx_native_be").readComplexFloats());
        assertArrayEquals(new double[] {1, 2, -0.5, 4}, ds("cx_native_be").readComplexDoubles());
        assertArrayEquals(new double[] {1.5, 0.25, -2, -1}, ds("cx_native_f16").readComplexDoubles());
        // A real number is a complex one with no imaginary part, as libhdf5 converts it.
        try (Hdf5File numeric = Hdf5File.open(Fixtures.path("conversions.h5"))) {
            Dataset i8 = numeric.root().dataset("i8");
            double[] real = i8.readDoubles();
            double[] complex = i8.readComplexDoubles();
            for (int i = 0; i < real.length; i++) {
                assertEquals(real[i], complex[2 * i]);
                assertEquals(0.0, complex[2 * i + 1]);
            }
        }
        assertThrows(HdfUnsupportedException.class, () -> ds("bits_b8").readComplexDoubles());
    }

    // ------------------------------------------------------------------ bit fields, opaque, sequences

    @Test
    void bitFieldsReadAsUnsignedIntegers() {
        assertArrayEquals(new int[] {0, 0x81, 0xFF}, ds("bits_b8").readInts());
        assertArrayEquals(new int[] {0, 0x81, 0xFF}, (int[]) ds("bits_b8").read());
        assertArrayEquals(new int[] {1, 0x8001, 0xFFFF}, ds("bits_b16be").readInts());
        assertArrayEquals(new long[] {0xDEADBEEFL, 1}, (long[]) ds("bits_b32").read());
        assertThrows(HdfUnsupportedException.class, () -> ds("bits_b32").readInts()); // 0xDEADBEEF does not fit
    }

    @Test
    void opaqueDataReadsAsBytes() {
        Dataset opaque = ds("opaque");
        assertEquals("falcon-tag", ((Datatype.Opaque) opaque.datatype()).tag());
        byte[][] elements = assertInstanceOf(byte[][].class, opaque.read());
        assertArrayEquals(new byte[] {0, 1, 2, 3}, elements[0]);
        assertArrayEquals(new byte[] {-1, -2, -3, -4}, elements[1]);
        assertArrayEquals(new byte[] {-1, -2, -3, -4}, opaque.selectPoints(new long[][] {{1}}).readRawBytes());
    }

    @Test
    void sequencesOfCompoundsReadAsRows() {
        Object[] rows = assertInstanceOf(Object[].class, ds("vlen_rec").read());
        assertEquals(3, rows.length);
        Map<?, ?> first = (Map<?, ?>) rows[0];
        assertArrayEquals(new int[] {1}, (int[]) first.get("a"));
        assertArrayEquals(new double[] {0.5}, (double[]) first.get("b"));
        assertArrayEquals(new int[0], (int[]) ((Map<?, ?>) rows[1]).get("a"));
        assertArrayEquals(new double[] {1.5, 2.5}, (double[]) ((Map<?, ?>) rows[2]).get("b"));
        assertThrows(HdfUnsupportedException.class, () -> ds("vlen_rec").readVlenInts());
    }

    // ------------------------------------------------------------------ files Falcon writes

    @Test
    void typedReadsOfWhatFalconWrites(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("typed.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.compoundDataset("records", new long[] {3},
                    Hdf5Writer.CompoundField.int32("a", new int[] {1, 2, 3}),
                    Hdf5Writer.CompoundField.float64("b", new double[] {1.5, 2.5, 3.5}));
            w.enumDataset("colors", new long[] {3},
                    Hdf5Writer.enumType().add("RED", 0).add("GREEN", 1).add("BLUE", 2), new int[] {2, 0, 1});
            w.int32ArrayDataset("arr", new long[] {2}, new int[] {3}, new int[] {1, 2, 3, 4, 5, 6});
            w.complexDataset("cx", new long[] {2}, new double[] {1, -2}, new double[] {0.5, 3});
        }
        try (Hdf5File written = Hdf5File.open(file)) {
            Group root = written.root();
            assertArrayEquals(new int[] {1, 2, 3}, root.dataset("records").member("a").readInts());
            assertArrayEquals(new double[] {2.5}, root.dataset("records").selectPoints(new long[][] {{1}}).member("b").readDoubles());
            assertArrayEquals(new String[] {"BLUE", "RED", "GREEN"}, root.dataset("colors").readStrings());
            assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, root.dataset("arr").readInts());
            assertArrayEquals(new double[] {1, 0.5, -2, 3}, root.dataset("cx").readComplexDoubles());
            assertTrue(Files.size(file) > 0);
        }
    }
}
