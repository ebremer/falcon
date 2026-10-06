package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataType.Field;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The extension data types zarr-python 3.4 writes (P2 F14): {@code numpy.datetime64} and
 * {@code numpy.timedelta64} (read and written as {@code long}s), {@code fixed_length_utf32} (as
 * {@code String}s), and {@code null_terminated_bytes}, {@code raw_bytes}, and {@code struct} (as byte
 * strings). zarr-python's own arrays ({@code gen_zarr_extension_fixtures.py}) read as zarr-python reads them,
 * and zarr-python reads Falcon's ({@code WriteZarrCases.java} + {@code check_zarr_writer.py}).
 */
class ExtensionDataTypesTest {

    static final DataType POINT = DataType.struct(new Field("x", DataType.INT16), new Field("y", DataType.UINT8));
    /** zarr-python's STRUCT in gen_zarr_extension_fixtures.py: every kind of field, and a nested struct. */
    static final DataType RECORD = DataType.struct(new Field("a", DataType.INT32), new Field("b", DataType.FLOAT64),
            new Field("c", DataType.nullTerminatedBytes(2)), new Field("d", DataType.fixedLengthUtf32(2)),
            new Field("t", DataType.datetime64("s", 1)), new Field("f", DataType.BOOL), new Field("p", POINT));

    private static ZarrArray create(MemoryStore store, ArraySpec spec) {
        return Zarr.createArray(store, spec);
    }

    private static ZarrArray array(DataType type, long n, long chunk) {
        return create(new MemoryStore(), ArraySpec.builder(new long[] {n}, type).chunkShape(chunk).build());
    }

    private static JsonObject zarrJson(MemoryStore store) {
        return Json.parse(store.get("zarr.json").orElseThrow()).asObject();
    }

    // ---- data_type JSON --------------------------------------------------------------------------------

    @Test
    void eachTypeWritesAndReadsItsDataTypeJsonAsZarrPythonDoes() {
        assertEquals("{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"ms\",\"scale_factor\":1}}",
                DataType.datetime64("ms", 1).toJson().toJson());
        assertEquals("{\"name\":\"numpy.timedelta64\",\"configuration\":{\"unit\":\"generic\",\"scale_factor\":10}}",
                DataType.timedelta64("generic", 10).toJson().toJson());
        assertEquals("{\"name\":\"fixed_length_utf32\",\"configuration\":{\"length_bytes\":12}}",
                DataType.fixedLengthUtf32(3).toJson().toJson());
        assertEquals("{\"name\":\"null_terminated_bytes\",\"configuration\":{\"length_bytes\":4}}",
                DataType.nullTerminatedBytes(4).toJson().toJson());
        assertEquals("{\"name\":\"raw_bytes\",\"configuration\":{\"length_bytes\":3}}",
                DataType.rawBytes(3).toJson().toJson());
        assertEquals("{\"name\":\"struct\",\"configuration\":{\"fields\":[{\"name\":\"x\",\"data_type\":\"int16\"},"
                + "{\"name\":\"y\",\"data_type\":\"uint8\"}]}}", POINT.toJson().toJson());
        assertEquals(new JsonString("float32"), DataType.FLOAT32.toJson());

        for (DataType t : List.of(DataType.datetime64("ns", 1), DataType.timedelta64("μs", 7),
                DataType.fixedLengthUtf32(5), DataType.nullTerminatedBytes(1), DataType.rawBytes(9), POINT, RECORD,
                DataType.INT64, DataType.of("r24"))) {
            DataType back = DataType.fromJson(Json.parse(t.toJson().toJson()));
            assertEquals(t, back);
            assertEquals(t.hashCode(), back.hashCode());
            assertEquals(t.toJson(), back.toJson());
        }
        assertNotEquals(DataType.datetime64("s", 1), DataType.datetime64("s", 2));
        assertNotEquals(DataType.datetime64("s", 1), DataType.timedelta64("s", 1));
        assertNotEquals(DataType.nullTerminatedBytes(2), DataType.rawBytes(2));
        assertNotEquals(POINT, DataType.struct(new Field("x", DataType.INT16), new Field("z", DataType.UINT8)));

        // zarr-python's legacy "structured" form: [name, data_type] pairs; it is the same type as "struct".
        assertEquals(POINT, DataType.fromJson(Json.parse(
                "{\"name\":\"structured\",\"configuration\":{\"fields\":[[\"x\",\"int16\"],[\"y\",\"uint8\"]]}}")));
        assertEquals(34, RECORD.byteCount());
        assertEquals(List.of(0, 4, 12, 14, 22, 30, 31), RECORD.fields().stream()
                .map(f -> RECORD.fieldOffset(f.name())).toList());
        assertEquals("s", RECORD.fields().get(4).type().unit());
        assertEquals(10, DataType.timedelta64("ms", 10).scaleFactor());
        assertThrows(IllegalStateException.class, DataType.INT64::unit);
        assertThrows(IllegalArgumentException.class, () -> POINT.fieldOffset("z"));
    }

    @Test
    void badExtensionDataTypesAreRefused() {
        for (String bad : List.of(
                "{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"sec\",\"scale_factor\":1}}",
                "{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"s\",\"scale_factor\":0}}",
                "{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"s\",\"scale_factor\":2147483648}}",
                "{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"s\"}}",
                "{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"s\",\"scale_factor\":1,\"x\":1}}",
                "{\"name\":\"numpy.timedelta64\"}",
                "\"numpy.timedelta64\"",
                "{\"name\":\"fixed_length_utf32\",\"configuration\":{\"length_bytes\":10}}",
                "{\"name\":\"fixed_length_utf32\",\"configuration\":{\"length_bytes\":0}}",
                "{\"name\":\"raw_bytes\",\"configuration\":{\"length_bytes\":-1}}",
                "{\"name\":\"raw_bytes\",\"configuration\":{\"length_bytes\":2.5}}",
                "{\"name\":\"struct\",\"configuration\":{\"fields\":[]}}",
                "{\"name\":\"struct\",\"configuration\":{\"fields\":[{\"name\":\"a\",\"data_type\":\"string\"}]}}",
                "{\"name\":\"struct\",\"configuration\":{\"fields\":[{\"name\":\"a\",\"data_type\":\"int8\"},"
                        + "{\"name\":\"a\",\"data_type\":\"int8\"}]}}",
                "{\"name\":\"struct\",\"configuration\":{\"fields\":[{\"name\":\"\",\"data_type\":\"int8\"}]}}",
                "{\"name\":\"struct\",\"configuration\":{\"fields\":[{\"data_type\":\"int8\"}]}}",
                "{\"name\":\"struct\",\"configuration\":{\"fields\":[\"a\"]}}",
                "7")) {
            assertThrows(ZarrFormatException.class, () -> DataType.fromJson(Json.parse(bad)), bad);
        }
        // a configuration on a type Falcon does not know, or on a core type: an extension it does not implement
        assertThrows(ZarrUnsupportedException.class,
                () -> DataType.fromJson(Json.parse("{\"name\":\"bfloat16\",\"configuration\":{}}")));
        assertThrows(ZarrUnsupportedException.class,
                () -> DataType.fromJson(Json.parse("{\"name\":\"float64\",\"configuration\":{\"x\":1}}")));
        assertThrows(IllegalArgumentException.class, () -> DataType.datetime64("seconds", 1));
        assertThrows(IllegalArgumentException.class, () -> DataType.fixedLengthUtf32(0));
        assertThrows(IllegalArgumentException.class, () -> DataType.struct(new Field("s", DataType.STRING)));
    }

    // ---- times -----------------------------------------------------------------------------------------

    @Test
    void timesReadAndWriteAsLongsWithNaTAsTheDefaultFill() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = create(store, ArraySpec.builder(new long[] {5}, DataType.datetime64("ms", 1)).chunkShape(2)
                .zstd().build());
        assertEquals(JsonNumber.of(Long.MIN_VALUE), zarrJson(store).get("fill_value")); // NaT, as zarr-python
        assertEquals(DataType.datetime64("ms", 1), Zarr.openArray(store).dataType());
        a.select(new long[] {0}, new long[] {3}).writeLongs(new long[] {1577836800001L, Long.MIN_VALUE, -1});
        assertArrayEquals(new long[] {1577836800001L, Long.MIN_VALUE, -1, Long.MIN_VALUE, Long.MIN_VALUE},
                a.readLongs());
        assertFalse(store.exists("c/2")); // only fill: not stored

        ZarrException e = assertThrows(ZarrException.class, a::readDoubles);
        assertTrue(e.getMessage().contains("readLongs"), e.getMessage());
        assertThrows(ZarrException.class, a::readInts);
        assertThrows(ZarrException.class, () -> a.writeDoubles(new double[5]));

        ZarrArray d = create(new MemoryStore(), ArraySpec.builder(new long[] {3}, DataType.timedelta64("ns", 1))
                .endian(ByteOrder.BIG_ENDIAN).fillValue(5L).build());
        assertArrayEquals(new long[] {5, 5, 5}, d.readLongs());
        d.writeInts(new int[] {-1, 0, 7});
        assertArrayEquals(new long[] {-1, 0, 7}, d.readLongs());
        assertArrayEquals(new byte[] {-1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7},
                d.readRawBytes()); // big-endian
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {1}, DataType.timedelta64("s", 1))
                .fillValue(1.5).build());
    }

    @Test
    void aTimeFillValueReadsAsAnIntegerOrNaT() {
        DataType t = DataType.timedelta64("s", 1);
        assertArrayEquals(new byte[] {0, 0, 0, 0, 0, 0, 0, -128}, t.decodeFillValue(new JsonString("NaT"),
                ByteOrder.LITTLE_ENDIAN));
        assertArrayEquals(new byte[] {0, 0, 0, 0, 0, 0, 0, 9}, t.decodeFillValue(JsonNumber.of(9), ByteOrder.BIG_ENDIAN));
        assertEquals(JsonNumber.of(Long.MIN_VALUE), t.encodeFillValue(new byte[] {0, 0, 0, 0, 0, 0, 0, -128},
                ByteOrder.LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> t.decodeFillValue(new JsonString("nat"), ByteOrder.LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> t.decodeFillValue(Json.parse("9223372036854775808"),
                ByteOrder.LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> t.decodeFillValue(Json.parse("1.5"), ByteOrder.LITTLE_ENDIAN));
    }

    // ---- fixed_length_utf32 ----------------------------------------------------------------------------

    @Test
    void fixedLengthStringsHoldCodePointsAndDropTrailingNuls() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = create(store, ArraySpec.builder(new long[] {6}, DataType.fixedLengthUtf32(3)).chunkShape(3)
                .fillValue(new JsonString("zz")).build());
        String[] values = {"", "abc", "😀😁x", "a\0b", "n\0\0", null};
        a.writeStrings(values);
        assertArrayEquals(new String[] {"", "abc", "😀😁x", "a\0b", "n", ""}, a.readStrings());
        assertArrayEquals(new String[] {"zz", "zz"}, create(new MemoryStore(), ArraySpec.builder(new long[] {2},
                DataType.fixedLengthUtf32(3)).fillValue(new JsonString("zz")).build()).readStrings());

        // Four code points do not fit three: refused, and nothing is written.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> a.writeStrings(new String[] {"ok", "abcd", "", "", "", ""}));
        assertTrue(e.getMessage().contains("index 1"), e.getMessage());
        assertEquals("abc", a.readStrings()[1]);
        assertThrows(ZarrException.class, a::readByteArrays);
        assertThrows(ZarrException.class, a::readLongs);

        // UTF-32 code units in the bytes codec's order; a fill too long is cut to fit, as numpy cuts it.
        ZarrArray be = create(new MemoryStore(), ArraySpec.builder(new long[] {1}, DataType.fixedLengthUtf32(2))
                .endian(ByteOrder.BIG_ENDIAN).fillValue(new JsonString("xyz")).build());
        assertArrayEquals(new String[] {"xy"}, be.readStrings());
        be.writeStrings(new String[] {"😀"});
        assertArrayEquals(new byte[] {0, 1, (byte) 0xf6, 0, 0, 0, 0, 0}, be.readRawBytes());
        byte[] invalid = {0, 0x11, 0, 0, 0, 0, 0, 0}; // 0x110000 is past the last code point
        be.writeRawBytes(invalid);
        assertThrows(ZarrFormatException.class, be::readStrings);
    }

    // ---- null_terminated_bytes and raw_bytes -------------------------------------------------------------

    @Test
    void byteStringTypesReadAsByteArrays() {
        ZarrArray s = create(new MemoryStore(), ArraySpec.builder(new long[] {4}, DataType.nullTerminatedBytes(3))
                .fillValue(new JsonString("YWI=")).build()); // b"ab"
        assertArrayEquals(new byte[] {'a', 'b'}, s.readByteArrays()[3]);
        s.writeByteArrays(new byte[][] {{}, {1, 0, 2}, {'x', 0, 0}, null});
        byte[][] got = s.readByteArrays();
        assertArrayEquals(new byte[0], got[0]);
        assertArrayEquals(new byte[] {1, 0, 2}, got[1]);
        assertArrayEquals(new byte[] {'x'}, got[2]); // trailing NULs are padding, as in numpy
        assertArrayEquals(new byte[0], got[3]);
        assertThrows(IllegalArgumentException.class, () -> s.writeByteArrays(new byte[][] {{1, 2, 3, 4}, {}, {}, {}}));
        assertThrows(ZarrException.class, s::readStrings);

        ZarrArray v = create(new MemoryStore(), ArraySpec.builder(new long[] {2}, DataType.rawBytes(3))
                .fillValue(new JsonString("AQI=")).build()); // two bytes: zero-padded to three
        assertArrayEquals(new byte[] {1, 2, 0}, v.readByteArrays()[0]);
        v.writeByteArrays(new byte[][] {{0, 0, 0}, {9, 0, 0}});
        assertArrayEquals(new byte[] {9, 0, 0}, v.readByteArrays()[1]); // raw: whole, zeros included
        assertThrows(IllegalArgumentException.class, () -> v.writeByteArrays(new byte[][] {{1}, {1, 2, 3}}));

        // r* takes the byte-array accessors too
        ZarrArray r = array(DataType.of("r16"), 2, 2);
        r.writeByteArrays(new byte[][] {{1, 2}, {3, 4}});
        assertArrayEquals(new byte[] {1, 2, 3, 4}, r.readRawBytes());
        assertArrayEquals(new byte[] {3, 4}, r.readByteArrays()[1]);
    }

    @Test
    void byteStringFillValuesAreBase64AsZarrPythonWritesThem() {
        assertEquals(new JsonString(""), DataType.nullTerminatedBytes(4).defaultFillValue());
        assertEquals(new JsonString("AAAA"), DataType.rawBytes(3).defaultFillValue());
        assertEquals(new JsonString("YQ=="), DataType.nullTerminatedBytes(3).encodeFillValue(new byte[] {'a', 0, 0},
                ByteOrder.LITTLE_ENDIAN));
        assertArrayEquals(new byte[] {'a', 'b'}, DataType.nullTerminatedBytes(2).decodeFillValue(new JsonString("YWJj"),
                ByteOrder.LITTLE_ENDIAN)); // cut to fit, as numpy cuts
        assertThrows(ZarrFormatException.class,
                () -> DataType.rawBytes(2).decodeFillValue(new JsonString("not base64!"), ByteOrder.LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class,
                () -> DataType.rawBytes(2).decodeFillValue(JsonNumber.of(0), ByteOrder.LITTLE_ENDIAN));
    }

    // ---- struct ----------------------------------------------------------------------------------------

    private static byte[] record(int a, double b, String c, String d, long t, boolean f, int x, int y) {
        ByteBuffer bb = ByteBuffer.allocate(34).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(a).putDouble(b);
        bb.put(Arrays.copyOf(c.getBytes(StandardCharsets.US_ASCII), 2));
        int[] cps = d.codePoints().toArray();
        bb.putInt(cps.length > 0 ? cps[0] : 0).putInt(cps.length > 1 ? cps[1] : 0);
        bb.putLong(t).put((byte) (f ? 1 : 0)).putShort((short) x).put((byte) y);
        return bb.array();
    }

    @Test
    void structsReadAndWriteWholeElementsLittleEndianWhateverTheStoredOrder() {
        byte[][] records = {record(1, 2.5, "ab", "😀", 0, true, -2, 255),
            record(-7, -0.0, "", "q", Long.MIN_VALUE, false, 300, 1)};
        for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            MemoryStore store = new MemoryStore();
            ZarrArray a = create(store, ArraySpec.builder(new long[] {2}, RECORD).endian(order).build());
            a.writeByteArrays(records);
            byte[][] got = a.readByteArrays();
            assertArrayEquals(records[0], got[0]);
            assertArrayEquals(records[1], got[1]);
            byte[] stored = store.get("c/0").orElseThrow();
            // a: int32 1, stored in the array's order; d: one UTF-32 unit swapped in place; f, c: unswapped
            assertArrayEquals(order == ByteOrder.BIG_ENDIAN ? new byte[] {0, 0, 0, 1} : new byte[] {1, 0, 0, 0},
                    Arrays.copyOf(stored, 4));
            assertArrayEquals(order == ByteOrder.BIG_ENDIAN ? new byte[] {0, 1, (byte) 0xf6, 0}
                    : new byte[] {0, (byte) 0xf6, 1, 0}, Arrays.copyOfRange(stored, 14, 18));
            assertArrayEquals(new byte[] {'a', 'b'}, Arrays.copyOfRange(stored, 12, 14));
            assertEquals(order == ByteOrder.BIG_ENDIAN ? "big" : "little", zarrJson(store).get("codecs").asArray()
                    .get(0).asObject().get("configuration").asObject().get("endian").asString());
        }
        ZarrArray a = array(RECORD, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> a.writeByteArrays(new byte[][] {new byte[33]}));
        assertThrows(ZarrException.class, a::readDoubles);
        assertThrows(ZarrException.class, () -> a.writeLongs(new long[1]));

        // A struct of single bytes only has no byte order: its bytes codec carries no endian, as zarr-python's.
        MemoryStore store = new MemoryStore();
        create(store, ArraySpec.builder(new long[] {1}, DataType.struct(new Field("a", DataType.UINT8),
                new Field("s", DataType.nullTerminatedBytes(3)))).endian(ByteOrder.BIG_ENDIAN).build());
        assertFalse(zarrJson(store).get("codecs").asArray().get(0).asObject().has("configuration"));
    }

    @Test
    void structFillValuesAreObjectsOrBase64() {
        // The default: each field's own default, NaT for a time.
        assertEquals(Json.parse("{\"a\":0,\"b\":0.0,\"c\":\"\",\"d\":\"\",\"t\":-9223372036854775808,\"f\":false,"
                + "\"p\":{\"x\":0,\"y\":0}}"), RECORD.defaultFillValue());
        JsonValue fill = Json.parse("{\"a\":7,\"b\":1.5,\"c\":\"cQ==\",\"d\":\"z\",\"t\":-9223372036854775808,"
                + "\"f\":true,\"p\":{\"x\":-1,\"y\":255}}");
        byte[] le = RECORD.decodeFillValue(fill, ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(record(7, 1.5, "q", "z", Long.MIN_VALUE, true, -1, 255), le);
        assertEquals(fill, RECORD.encodeFillValue(le, ByteOrder.LITTLE_ENDIAN));
        byte[] be = RECORD.decodeFillValue(fill, ByteOrder.BIG_ENDIAN);
        assertEquals(fill, RECORD.encodeFillValue(be, ByteOrder.BIG_ENDIAN));
        assertArrayEquals(new byte[] {0, 0, 0, 7}, Arrays.copyOf(be, 4));

        // A field left out takes its default, as in zarr-python; base64 text is the packed little-endian element.
        assertArrayEquals(record(0, 2.5, "", "", Long.MIN_VALUE, false, 0, 0),
                RECORD.decodeFillValue(Json.parse("{\"b\":2.5}"), ByteOrder.LITTLE_ENDIAN));
        String b64 = java.util.Base64.getEncoder().encodeToString(le);
        assertArrayEquals(be, RECORD.decodeFillValue(new JsonString(b64), ByteOrder.BIG_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> RECORD.decodeFillValue(new JsonString("AAAA"),
                ByteOrder.LITTLE_ENDIAN));
        ZarrFormatException e = assertThrows(ZarrFormatException.class,
                () -> RECORD.decodeFillValue(Json.parse("{\"a\":1.5}"), ByteOrder.LITTLE_ENDIAN));
        assertTrue(e.getMessage().contains("'a'"), e.getMessage());

        ZarrArray a = create(new MemoryStore(), ArraySpec.builder(new long[] {2}, RECORD).fillValue(fill).build());
        assertArrayEquals(le, a.readByteArrays()[1]);
    }

    // ---- through the rest of Falcon -------------------------------------------------------------------

    /** One element per type, written then read back through a layout. */
    private static void roundTrip(DataType type, ArraySpec.Builder spec, Object values) {
        MemoryStore store = new MemoryStore();
        ZarrArray a = create(store, spec.build());
        switch (type.kind()) {
            case DATETIME, TIMEDELTA -> {
                a.writeLongs((long[]) values);
                assertArrayEquals((long[]) values, a.readLongs(), type.toString());
                assertArrayEquals((long[]) values, Zarr.openArray(store).withChunkCache(1 << 20).readLongs());
            }
            case FIXED_STRING -> {
                a.writeStrings((String[]) values);
                assertArrayEquals((String[]) values, a.readStrings(), type.toString());
            }
            default -> {
                a.writeByteArrays((byte[][]) values);
                byte[][] got = a.readByteArrays();
                for (int i = 0; i < got.length; i++) {
                    assertArrayEquals(((byte[][]) values)[i], got[i], type + " element " + i);
                }
            }
        }
    }

    @Test
    void everyTypeGoesThroughShardingTransposeCompressionAndTheChunkCache() {
        long[] times = new long[24];
        String[] texts = new String[24];
        byte[][] bytes = new byte[24][];
        byte[][] raw = new byte[24][];
        byte[][] records = new byte[24][];
        for (int i = 0; i < 24; i++) {
            times[i] = i % 5 == 0 ? Long.MIN_VALUE : i * 1_000_003L - 7;
            texts[i] = i % 4 == 0 ? "" : "é" + i;
            bytes[i] = i % 3 == 0 ? new byte[0] : new byte[] {(byte) i, 0, (byte) -i};
            raw[i] = new byte[] {(byte) i, 0, (byte) (i * 7)};
            records[i] = record(i * 100_000, i / 3.0, i % 2 == 0 ? "z" : "", "ß", i, i % 3 == 0, -i, i);
        }
        Object[][] cases = {
            {DataType.datetime64("us", 1), times}, {DataType.timedelta64("D", 3), times},
            {DataType.fixedLengthUtf32(3), texts}, {DataType.nullTerminatedBytes(3), bytes},
            {DataType.rawBytes(3), raw}, {RECORD, records}};
        for (Object[] c : cases) {
            DataType type = (DataType) c[0];
            long[] shape = {4, 6};
            roundTrip(type, ArraySpec.builder(shape, type).chunkShape(4, 6), c[1]);
            roundTrip(type, ArraySpec.builder(shape, type).chunkShape(2, 4).sharding(1, 2).zstd(), c[1]);
            roundTrip(type, ArraySpec.builder(shape, type).chunkShape(3, 3).blosc().crc32c()
                    .endian(ByteOrder.BIG_ENDIAN), c[1]);
            roundTrip(type, ArraySpec.builder(shape, type).chunkShape(4, 4).gzip(1).shardIndexAtStart()
                    .sharding(2, 2), c[1]);
        }
    }

    @Test
    void transposedArraysOfEachKindRead() {
        // ArraySpec does not build transpose; the metadata is hand-made, as zarr-python writes it.
        for (DataType type : List.of(DataType.datetime64("s", 1), DataType.fixedLengthUtf32(2),
                DataType.rawBytes(2), RECORD)) {
            MemoryStore store = new MemoryStore();
            String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[2,3],\"data_type\":"
                    + type.toJson().toJson() + ",\"chunk_grid\":{\"name\":\"regular\",\"configuration\":"
                    + "{\"chunk_shape\":[2,3]}},\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":"
                    + type.defaultFillValue().toJson() + ",\"codecs\":[{\"name\":\"transpose\",\"configuration\":"
                    + "{\"order\":[1,0]}},{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}}]}";
            store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
            ZarrArray a = Zarr.openArray(store);
            byte[] elements = new byte[6 * type.byteCount()];
            for (int i = 0; i < elements.length; i++) {
                elements[i] = (byte) (i % 3 == 0 ? 0 : i); // no code unit past U+10FFFF
            }
            a.writeRawBytes(elements);
            assertArrayEquals(elements, a.readRawBytes(), type.toString());
            int es = type.byteCount();
            // element (0, 1) is stored second in C order, but third once transposed
            assertArrayEquals(Arrays.copyOfRange(elements, es, 2 * es),
                    Arrays.copyOfRange(store.get("c/0/0").orElseThrow(), 2 * es, 3 * es), type.toString());
        }
    }

    @Test
    void anAllFillChunkIsNotStoredUnlessWriteEmptyChunks() {
        Object[][] cases = {
            {DataType.datetime64("s", 1), new long[] {Long.MIN_VALUE, Long.MIN_VALUE}},
            {DataType.fixedLengthUtf32(2), new String[] {"", ""}},
            {DataType.nullTerminatedBytes(2), new byte[][] {{}, {}}},
            {DataType.rawBytes(2), new byte[][] {{0, 0}, {0, 0}}},
            {RECORD, new byte[][] {record(0, 0, "", "", Long.MIN_VALUE, false, 0, 0),
                record(0, 0, "", "", Long.MIN_VALUE, false, 0, 0)}}};
        for (Object[] c : cases) {
            DataType type = (DataType) c[0];
            for (boolean sharded : new boolean[] {false, true}) {
                for (boolean writeEmpty : new boolean[] {false, true}) {
                    MemoryStore store = new MemoryStore();
                    ArraySpec.Builder spec = ArraySpec.builder(new long[] {2}, type);
                    if (sharded) {
                        spec.sharding(1);
                    }
                    ZarrArray a = create(store, spec.build()).withWriteEmptyChunks(writeEmpty);
                    switch (type.kind()) {
                        case DATETIME -> a.writeLongs((long[]) c[1]);
                        case FIXED_STRING -> a.writeStrings((String[]) c[1]);
                        default -> a.writeByteArrays((byte[][]) c[1]);
                    }
                    assertEquals(writeEmpty, store.exists("c/0"), type + " sharded=" + sharded);
                }
            }
        }
    }

    @Test
    void resizeAndConsolidatedMetadataKeepTheType() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        ZarrArray s = root.createArray("s", ArraySpec.builder(new long[] {2}, RECORD).chunkShape(1).build());
        byte[] one = record(1, 1, "a", "b", 1, true, 1, 1);
        s.writeByteArrays(new byte[][] {one, one});
        ZarrArray u = root.createArray("u", ArraySpec.builder(new long[] {2}, DataType.fixedLengthUtf32(1))
                .fillValue(new JsonString("-")).build());
        u.writeStrings(new String[] {"a", "b"});

        ZarrArray grown = s.resize(3);
        assertEquals(RECORD, grown.dataType());
        assertArrayEquals(RECORD.decodeFillValue(RECORD.defaultFillValue(), ByteOrder.LITTLE_ENDIAN),
                grown.readByteArrays()[2]);
        assertArrayEquals(new String[] {"a", "-"}, u.resize(1).resize(2).readStrings()); // cut off: fill
        assertArrayEquals(new String[] {"a", "-"}, Zarr.openArray(store, "u").readStrings());

        root.consolidate();
        ZarrGroup consolidated = Zarr.openGroup(store);
        assertTrue(consolidated.isConsolidated());
        assertEquals(RECORD, consolidated.array("s").dataType());
        assertArrayEquals(one, consolidated.array("s").readByteArrays()[0]);
        assertArrayEquals(new String[] {"a", "-"}, consolidated.array("u").readStrings());
    }

    // ---- zarr-python's arrays --------------------------------------------------------------------------

    private static Path fixture(String name) {
        try {
            return Path.of(ExtensionDataTypesTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name + " (tools/fixtures/gen_zarr_extension_fixtures.py)", e);
        }
    }

    private static JsonObject expected(String name) throws IOException {
        return Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject();
    }

    @Test
    void zarrPythonsArraysReadAsZarrPythonReadsThem() throws IOException {
        HexFormat hex = HexFormat.of();
        for (String name : List.of("ext_datetime64_ms", "ext_datetime64_10s_be", "ext_timedelta64_ns", "ext_utf32",
                "ext_utf32_be", "ext_null_bytes", "ext_raw_bytes", "ext_struct", "ext_struct_be",
                "ext_structured_legacy", "ext_struct_b64_fill")) {
            JsonObject want = expected(name);
            ZarrArray a = Zarr.open(fixture(name)).asArray();
            List<JsonValue> values = want.get("values").asArray().values();
            assertEquals(a.dataType(), DataType.fromJson(want.get("data_type")), name);
            switch (a.dataType().kind()) {
                case DATETIME, TIMEDELTA -> assertArrayEquals(
                        values.stream().mapToLong(v -> v.asNumber().longValue()).toArray(), a.readLongs(), name);
                case FIXED_STRING -> assertArrayEquals(values.stream().map(JsonValue::asString).toArray(String[]::new),
                        a.readStrings(), name);
                default -> {
                    byte[][] got = a.readByteArrays();
                    assertEquals(values.size(), got.length, name);
                    for (int i = 0; i < got.length; i++) {
                        assertEquals(values.get(i).asString(), hex.formatHex(got[i]), name + " element " + i);
                    }
                }
            }
        }
        DataType legacy = Zarr.open(fixture("ext_structured_legacy")).asArray().dataType();
        assertEquals("struct", legacy.name());
        assertEquals(DataTypeKind.STRUCT, legacy.kind());
        // zarr-python's struct of every kind of field is the RECORD this test writes
        assertEquals(RECORD, Zarr.open(fixture("ext_struct")).asArray().dataType());
    }
}
