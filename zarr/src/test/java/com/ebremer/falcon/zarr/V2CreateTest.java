package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNull;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Creating Zarr v2 arrays and groups: {@link ArraySpec.Builder#zarrFormat(int) zarrFormat(2)},
 * {@link Zarr#createGroup(com.ebremer.falcon.zarr.store.Store, JsonObject, boolean, int)}, children taking
 * their group's format, and a v2 group's {@code .zmetadata}. The oracle is zarr-python 3.4's own v2 arrays
 * (the {@code v2_*} and {@code v2x_*} fixtures): Falcon creates each again from a spec, and must write the
 * {@code .zarray} zarr-python wrote and, given the same values, its chunks.
 */
class V2CreateTest {

    private static final HexFormat HEX = HexFormat.of();

    /** Fixtures whose chunks zarr-python wrote with exact codecs, which Falcon must store byte for byte. */
    private static final List<String> EXACT = List.of(
            "v2_attrs", "v2_be_int32", "v2_bool", "v2_float32_nan", "v2_int64", "v2_le_int32", "v2_partial_fill",
            "v2_slash_2d", "v2_uint8", "v2x_raw_v", "v2x_datetime_ms", "v2x_datetime_10s_be", "v2x_struct",
            "v2x_order_f_str", "v2x_order_f_utf32", "v2x_nc_delta_i8", "v2x_nc_fixedscaleoffset_u2",
            "v2x_nc_astype", "v2x_nc_packbits", "v2x_nc_crc32", "v2x_nc_crc32c", "v2x_nc_fletcher32",
            "v2x_nc_jenkins", "v2x_nc_crc32_compressor", "v2x_nc_bz2", "v2x_nc_delta_bz2");

    /** zarr-python's v2 arrays Falcon can write: all but those compressed with zfpy, which Falcon only reads. */
    static Stream<String> fixtures() throws IOException, URISyntaxException {
        Path dir = Path.of(V2CreateTest.class.getResource("/fixtures").toURI());
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> (n.startsWith("v2_") || n.startsWith("v2x_")) && !n.contains("."))
                    .filter(n -> !n.contains("zfpy"))
                    .sorted().toList().stream();
        }
    }

    /**
     * Each fixture, created again from a spec built with the public API (its data type, shape, chunks, fill
     * value, order, filters, compressor, separator, and attributes), has zarr-python's {@code .zarray}, its
     * members in zarr-python's order, and its {@code .zattrs}. Writing the fixture's values stores what
     * writing them into a copy of zarr-python's metadata stores, chunk for chunk, and for the exact codecs
     * what zarr-python stored.
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void recreatesZarrPythonsV2Array(String name) {
        MemoryStore original = copy(fixture(name), true);
        JsonObject zarray = json(original, ".zarray");
        MemoryStore created = new MemoryStore();
        ZarrArray a = Zarr.createArray(created, specOf(original));
        assertEquals(2, a.zarrFormat());

        JsonObject got = json(created, ".zarray");
        assertEquals(List.copyOf(zarray.members().keySet()), List.copyOf(got.members().keySet()), name + ": members");
        assertSame(zarray, got, name + ".zarray");
        JsonValue attrs = original.get(".zattrs").map(b -> Json.parse(b)).orElse(JsonObject.builder().build());
        assertSame(attrs, json(created, ".zattrs"), name + ".zattrs");

        JsonObject expected = expected(name);
        write(a, expected);
        MemoryStore copy = copy(fixture(name), false);
        write(Zarr.openArray(copy), expected);
        assertEquals(chunkKeys(copy), chunkKeys(created), name + ": chunk keys");
        for (String key : chunkKeys(copy)) {
            assertArrayEquals(copy.get(key).orElseThrow(), created.get(key).orElseThrow(), name + ": chunk " + key);
            if (EXACT.contains(name)) {
                assertArrayEquals(original.get(key).orElseThrow(), created.get(key).orElseThrow(),
                        name + ": zarr-python's chunk " + key);
            }
        }
    }

    /** The default spec writes the {@code .zarray} zarr-python 3.4 writes, but with no compressor. */
    @Test
    void writesTheZarrayZarrPythonWrites() {
        MemoryStore store = new MemoryStore();
        Zarr.createArray(store, ArraySpec.builder(new long[] {4, 5}, DataType.FLOAT64).zarrFormat(2)
                .chunkShape(2, 2).build());
        assertEquals("{\"shape\":[4,5],\"chunks\":[2,2],\"dtype\":\"<f8\",\"fill_value\":0.0,\"order\":\"C\","
                + "\"filters\":null,\"dimension_separator\":\".\",\"compressor\":null,\"zarr_format\":2}",
                text(store, ".zarray"));
        assertEquals("{}", text(store, ".zattrs"));
        assertEquals(List.of(".zarray", ".zattrs"), store.list().stream().sorted().toList());

        MemoryStore zstd = new MemoryStore();
        Zarr.createArray(zstd, ArraySpec.builder(new long[0], DataType.INT16).zarrFormat(2).zstd(3)
                .attributes(JsonObject.builder().put("a", 1).build()).build());
        assertEquals("{\"shape\":[],\"chunks\":[],\"dtype\":\"<i2\",\"fill_value\":0,\"order\":\"C\",\"filters\":null,"
                + "\"dimension_separator\":\".\",\"compressor\":{\"id\":\"zstd\",\"level\":3},\"zarr_format\":2}",
                text(zstd, ".zarray"));
        assertEquals("{\"a\":1}", text(zstd, ".zattrs"));
    }

    /** Each data type's NumPy dtype, as zarr-python 3.4 writes it (checked against it by hand). */
    @Test
    void dataTypesBecomeNumpyDtypes() {
        assertEquals("|b1", dtype(DataType.BOOL, ByteOrder.BIG_ENDIAN));
        assertEquals("|i1", dtype(DataType.INT8, ByteOrder.BIG_ENDIAN));
        assertEquals(">u2", dtype(DataType.UINT16, ByteOrder.BIG_ENDIAN));
        assertEquals("<f2", dtype(DataType.FLOAT16, ByteOrder.LITTLE_ENDIAN));
        assertEquals("<c16", dtype(DataType.COMPLEX128, ByteOrder.LITTLE_ENDIAN));
        assertEquals(">U2", dtype(DataType.fixedLengthUtf32(2), ByteOrder.BIG_ENDIAN));
        assertEquals("|S3", dtype(DataType.nullTerminatedBytes(3), ByteOrder.BIG_ENDIAN));
        assertEquals("|V3", dtype(DataType.rawBytes(3), ByteOrder.LITTLE_ENDIAN));
        assertEquals("<M8[10s]", dtype(DataType.datetime64("s", 10), ByteOrder.LITTLE_ENDIAN));
        assertEquals(">m8[3ms]", dtype(DataType.timedelta64("ms", 3), ByteOrder.BIG_ENDIAN));
        assertEquals("<m8", dtype(DataType.timedelta64("generic", 1), ByteOrder.LITTLE_ENDIAN));
        assertEquals("<M8[us]", dtype(DataType.datetime64("μs", 1), ByteOrder.LITTLE_ENDIAN));
        assertEquals("|O", dtype(DataType.STRING, ByteOrder.LITTLE_ENDIAN));

        DataType nested = DataType.struct(
                new DataType.Field("a", DataType.struct(new DataType.Field("x", DataType.INT16),
                        new DataType.Field("y", DataType.UINT8))),
                new DataType.Field("b", DataType.FLOAT32));
        assertEquals("[[\"a\",[[\"x\",\"<i2\"],[\"y\",\"|u1\"]]],[\"b\",\"<f4\"]]",
                zarray(ArraySpec.builder(new long[] {4}, nested).zarrFormat(2)).get("dtype").toJson());

        // the variable-length types' object codec is the first filter, before the caller's
        JsonObject strings = zarray(ArraySpec.builder(new long[] {4}, DataType.STRING).zarrFormat(2)
                .filters(JsonObject.builder().put("id", "crc32").build()));
        assertEquals("[{\"id\":\"vlen-utf8\"},{\"id\":\"crc32\"}]", strings.get("filters").toJson());
        assertEquals("\"\"", strings.get("fill_value").toJson());
        JsonObject bytes = zarray(ArraySpec.builder(new long[] {4}, DataType.BYTES).zarrFormat(2)
                .fillValue(new JsonString("YWI=")));
        assertEquals("[{\"id\":\"vlen-bytes\"}]", bytes.get("filters").toJson());
        assertEquals("\"YWI=\"", bytes.get("fill_value").toJson());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> zarray(ArraySpec.builder(new long[] {4}, DataType.of("r16")).zarrFormat(2)))
                .getMessage().contains("rawBytes"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> zarray(ArraySpec.builder(new long[] {4}, DataType.datetime64("generic", 2)).zarrFormat(2)))
                .getMessage().contains("generic"));
    }

    /** Fill values in v2's form, as zarr-python 3.4 writes them (checked against it by hand). */
    @Test
    void fillValuesTakeV2sForm() {
        assertEquals("\"NaN\"", fill(DataType.FLOAT64, b -> b.fillValue(Double.longBitsToDouble(0x7ff8000000000001L))));
        assertEquals("\"-Infinity\"", fill(DataType.FLOAT32, b -> b.fillValue(Double.NEGATIVE_INFINITY)));
        assertEquals(0.1f, new BigDecimal(fill(DataType.FLOAT32, b -> b.fillValue(0.1f))).floatValue());
        assertEquals("[\"NaN\",\"Infinity\"]",
                fill(DataType.COMPLEX64, b -> b.fillValue(JsonArray.of(new JsonString("0x7fc00001"),
                        new JsonString("Infinity")))));
        assertEquals("true", fill(DataType.BOOL, b -> b.fillValue(1)));
        assertEquals("18446744073709551615",
                fill(DataType.UINT64, b -> b.fillValue(new JsonNumber("18446744073709551615"))));
        assertEquals("-9223372036854775808", fill(DataType.datetime64("ns", 1), b -> { }));
        assertEquals("5", fill(DataType.datetime64("ns", 1), b -> b.fillValue(5)));
        assertEquals("\"ab\"", fill(DataType.fixedLengthUtf32(3), b -> b.fillValue(new JsonString("ab"))));
        assertEquals("\"AAAA\"", fill(DataType.rawBytes(3), b -> { }));
        assertEquals("\"x\"", fill(DataType.STRING, b -> b.fillValue(new JsonString("x"))));
        String s = fill(DataType.nullTerminatedBytes(3), b -> b.fillValue(new JsonString("YWI=")));
        assertEquals("ab", new String(Base64.getDecoder().decode(s.substring(1, s.length() - 1)),
                StandardCharsets.US_ASCII).replace("\0", ""));

        // a struct's whole element in the array's byte order: zarr-python's fill for (1, 2.5) as >i4, >f8
        DataType struct = DataType.struct(new DataType.Field("a", DataType.INT32),
                new DataType.Field("b", DataType.FLOAT64));
        assertEquals("\"AAAAAUAEAAAAAAAA\"", fill(struct, b -> b.endian(ByteOrder.BIG_ENDIAN)
                .fillValue(JsonObject.builder().put("a", 1).put("b", JsonNumber.of(2.5)).build())));

        // null, v2's "no fill value", is kept and reads as the type's default
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {3}, DataType.INT32).zarrFormat(2)
                .fillValue(JsonNull.INSTANCE).build());
        assertEquals("null", json(store, ".zarray").get("fill_value").toJson());
        assertArrayEquals(new int[3], a.readInts());
        // a v3 array has no null fill value
        assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {3}, DataType.INT32).fillValue(JsonNull.INSTANCE).build());
    }

    /** Fortran order, filters, and each compressor, written and read back; the order is v2's alone. */
    @Test
    void orderFiltersAndCompressors() {
        long[] shape = {5, 7};
        int[] values = new int[35];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * i - 100;
        }
        List<Consumer<ArraySpec.Builder>> compressors = List.of(
                b -> { }, b -> b.gzip(5), b -> b.zstd(1), b -> b.blosc(), b -> b.blosc("lz4", 9, "bitshuffle"),
                b -> b.bz2(9), b -> b.compressor(JsonObject.builder().put("id", "zlib").put("level", 1).build()),
                b -> b.compressor(JsonObject.builder().put("id", "lz4").put("acceleration", 1).build()));
        for (Consumer<ArraySpec.Builder> compressor : compressors) {
            ArraySpec.Builder b = ArraySpec.builder(shape, DataType.INT32).zarrFormat(2).chunkShape(2, 3).order('F')
                    .endian(ByteOrder.BIG_ENDIAN).separator("/")
                    .filters(JsonObject.builder().put("id", "delta").put("dtype", "<i4").build());
            compressor.accept(b);
            MemoryStore store = new MemoryStore();
            ZarrArray a = Zarr.createArray(store, b.build());
            a.writeInts(values);
            assertArrayEquals(values, Zarr.openArray(store).readInts());
            assertTrue(store.exists("0/0") && store.exists("2/2"), store.list().toString());
            assertEquals("F", json(store, ".zarray").get("order").asString());
            assertEquals(">i4", json(store, ".zarray").get("dtype").asString());
        }
        JsonObject blosc = zarray(ArraySpec.builder(new long[] {4}, DataType.UINT8).zarrFormat(2).blosc());
        assertEquals("{\"id\":\"blosc\",\"cname\":\"zstd\",\"clevel\":5,\"shuffle\":0,\"blocksize\":0}",
                blosc.get("compressor").toJson());
        blosc = zarray(ArraySpec.builder(new long[] {4}, DataType.FLOAT32).zarrFormat(2).blosc());
        assertEquals(1, blosc.get("compressor").asObject().get("shuffle").asNumber().intValue());
        assertEquals("{\"id\":\"bz2\",\"level\":9}", zarray(ArraySpec.builder(new long[] {4}, DataType.UINT8)
                .zarrFormat(2).bz2(9)).get("compressor").toJson());
        assertEquals("{\"id\":\"gzip\",\"level\":5}", zarray(ArraySpec.builder(new long[] {4}, DataType.UINT8)
                .zarrFormat(2).gzip(5)).get("compressor").toJson());

        // a resize rewrites the .zarray, and the cut-off elements read as the fill value
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(shape, DataType.INT32).zarrFormat(2).chunkShape(2, 3)
                .order('F').fillValue(-1).build());
        a.writeInts(values);
        a.resize(3, 4).resize(5, 7);
        int[] got = Zarr.openArray(store).readInts();
        for (int i = 0; i < 35; i++) {
            assertEquals(i / 7 < 3 && i % 7 < 4 ? values[i] : -1, got[i], "element " + i);
        }
        assertEquals("[5,7]", json(store, ".zarray").get("shape").toJson());
    }

    /** What Zarr v2 cannot describe is refused when the spec is built, and v2's settings for a v3 spec. */
    @Test
    void refusesWhatTheFormatCannotHold() {
        long[] shape = {8, 8};
        Map<String, Consumer<ArraySpec.Builder>> v3Only = Map.of(
                "sharding", b -> b.chunkShape(4, 4).sharding(2, 2),
                "cast_value", b -> b.castValue(DataType.FLOAT32),
                "reshape", b -> b.reshape(JsonArray.of(JsonNumber.of(-1))),
                "crc32c", b -> b.crc32c(),
                "rectilinear", b -> b.chunkLengths(0, 3, 5),
                "dimension names", b -> b.dimensionNames("y", "x"),
                "default chunk key encoding", b -> b.chunkKeyEncoding("default"),
                "foo chunk key encoding", b -> b.chunkKeyEncoding("foo"),
                "one compressor", b -> b.gzip(1).zstd(),
                "zfpy", b -> b.compressor(JsonObject.builder().put("id", "zfpy").put("mode", 4).build()));
        v3Only.forEach((what, setting) -> {
            ArraySpec.Builder b = ArraySpec.builder(shape, DataType.FLOAT64).zarrFormat(2);
            setting.accept(b);
            String message = assertThrows(IllegalArgumentException.class, b::build, what).getMessage();
            assertTrue(message.contains(what.split(" ")[0]), what + ": " + message);
        });
        // v2's settings, for a spec that names no format or v3
        for (Consumer<ArraySpec.Builder> setting : List.<Consumer<ArraySpec.Builder>>of(b -> b.order('F'),
                b -> b.filters(), b -> b.compressor(JsonObject.builder().put("id", "zlib").build()))) {
            ArraySpec.Builder b = ArraySpec.builder(shape, DataType.FLOAT64);
            setting.accept(b);
            assertTrue(assertThrows(IllegalArgumentException.class, b::build).getMessage().contains("zarrFormat(2)"));
            setting.accept(b.zarrFormat(3));
            assertThrows(IllegalArgumentException.class, b::build);
        }
        // an unknown filter, and an object codec where it does not belong
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(shape, DataType.INT8).zarrFormat(2)
                .filters(JsonObject.builder().put("id", "categorize").build()).build());
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(shape, DataType.INT8).zarrFormat(2)
                .filters(JsonObject.builder().put("id", "vlen-utf8").build()).build());
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(shape, DataType.INT8).zarrFormat(4));
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(shape, DataType.INT8).order('X'));
        // the v2 chunk key encoding is v2's own
        assertEquals("/", zarray(ArraySpec.builder(shape, DataType.INT8).zarrFormat(2).chunkKeyEncoding("v2")
                .separator("/")).get("dimension_separator").asString());
    }

    /** A v2 group's children are v2, as zarr-python makes them; a spec naming the other format is refused. */
    @Test
    void childrenTakeTheirGroupsFormat() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store, JsonObject.builder().put("title", "t").build(), false, 2);
        assertEquals(2, root.zarrFormat());
        assertEquals("{\"zarr_format\":2}", text(store, ".zgroup"));
        assertEquals("{\"title\":\"t\"}", text(store, ".zattrs"));

        ZarrGroup g = root.createGroup("g", JsonObject.builder().put("k", 1).build());
        ArraySpec plain = ArraySpec.builder(new long[] {6}, DataType.INT16).chunkShape(4).build();
        ZarrArray a = g.createArray("a", plain);
        assertEquals(2, g.zarrFormat());
        assertEquals(2, a.zarrFormat());
        assertEquals(List.of(".zattrs", ".zgroup", "g/.zattrs", "g/.zgroup", "g/a/.zarray", "g/a/.zattrs"),
                store.list().stream().sorted().toList());
        a.writeInts(new int[] {1, 2, 3, 4, 5, 6});
        assertArrayEquals(new int[] {5, 6, 0, 0}, toInts(store.get("g/a/1").orElseThrow()));
        assertEquals(1, Zarr.openGroup(store).group("g").attributes().get("k").asNumber().intValue());
        assertEquals(List.of("a"), Zarr.openGroup(store).group("g").childNames());

        // the other format, named, is refused before anything is written
        ArraySpec v3 = ArraySpec.builder(new long[] {6}, DataType.INT16).zarrFormat(3).build();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> g.createArray("b", v3)).getMessage()
                .contains("Zarr v3 array cannot be created in the Zarr v2 group"));
        ArraySpec sharded = ArraySpec.builder(new long[] {8}, DataType.INT16).chunkShape(8).sharding(4).build();
        assertThrows(IllegalArgumentException.class, () -> g.createArray("b", sharded));
        assertFalse(store.list().stream().anyMatch(k -> k.startsWith("g/b")));

        MemoryStore v3store = new MemoryStore();
        ZarrGroup v3root = Zarr.createGroup(v3store);
        assertEquals(3, v3root.zarrFormat());
        ArraySpec v2 = ArraySpec.builder(new long[] {6}, DataType.INT16).zarrFormat(2).build();
        assertThrows(IllegalArgumentException.class, () -> v3root.createArray("b", v2));
        assertEquals(3, v3root.createArray("c", plain).zarrFormat());
        assertEquals(3, v3root.createGroup("d").zarrFormat());
        assertEquals(List.of("c/zarr.json", "d/zarr.json", "zarr.json"), v3store.list().stream().sorted().toList());

        assertThrows(IllegalArgumentException.class,
                () -> Zarr.createGroup(new MemoryStore(), JsonObject.builder().build(), false, 1));
        // overwrite replaces a v3 hierarchy with a v2 one
        ZarrGroup over = Zarr.createGroup(v3store, JsonObject.builder().build(), true, 2);
        assertEquals(2, over.zarrFormat());
        assertEquals(List.of(".zattrs", ".zgroup"), v3store.list().stream().sorted().toList());

        // the spec's own format, at a store's root
        assertEquals(2, Zarr.createArray(new MemoryStore(), v2).zarrFormat());
        assertEquals(3, Zarr.createArray(new MemoryStore(), plain).zarrFormat());
        assertEquals("<i2", v2.toJson().get("dtype").asString());
        assertEquals("int16", plain.toJson().get("data_type").asString());
    }

    /** A v2 group's consolidate() writes its .zmetadata as zarr-python does, which Zarr.open then uses. */
    @Test
    void consolidatesAV2Hierarchy() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store, JsonObject.builder().put("r", 1).build(), false, 2);
        ZarrGroup g = root.createGroup("g", JsonObject.builder().put("q", 1).build());
        g.createArray("a", ArraySpec.builder(new long[] {3}, DataType.INT8).build()).writeInts(new int[] {1, 2, 3});
        root.createArray("b", ArraySpec.builder(new long[] {2}, DataType.FLOAT32).build());
        store.set("empty/x", new byte[1]); // a directory with no node is left out

        ZarrGroup consolidated = root.consolidate();
        assertTrue(consolidated.isConsolidated());
        JsonObject zmetadata = json(store, ".zmetadata");
        assertEquals(1, zmetadata.get("zarr_consolidated_format").asNumber().intValue());
        JsonObject metadata = zmetadata.get("metadata").asObject();
        assertEquals(List.of(".zgroup", ".zattrs", "b/.zattrs", "b/.zarray", "g/.zattrs", "g/.zgroup",
                "g/a/.zattrs", "g/a/.zarray"), List.copyOf(metadata.members().keySet()));
        assertEquals(json(store, "g/a/.zarray"), metadata.get("g/a/.zarray"));
        assertEquals("{\"q\":1}", metadata.get("g/.zattrs").toJson());

        // Zarr.open answers from it: a node added since is not seen until consolidating again
        root.createArray("late", ArraySpec.builder(new long[] {1}, DataType.INT8).build());
        ZarrGroup opened = Zarr.openGroup(store);
        assertTrue(opened.isConsolidated());
        assertEquals(List.of("b", "g"), opened.childNames());
        assertArrayEquals(new int[] {1, 2, 3}, opened.group("g").array("a").readInts());
        assertEquals(2, opened.group("g").array("a").zarrFormat());
        assertEquals(List.of("b", "g", "late"), root.consolidate().childNames());

        // valid Zarr Falcon cannot read is embedded; malformed metadata stops it, writing nothing
        store.set("odd/.zarray", ("{\"zarr_format\":2,\"shape\":[2],\"chunks\":[2],\"dtype\":\"<i4\","
                + "\"compressor\":null,\"fill_value\":0,\"order\":\"C\",\"filters\":[{\"id\":\"categorize\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        root.consolidate();
        assertTrue(json(store, ".zmetadata").get("metadata").asObject().has("odd/.zarray"));
        byte[] before = store.get(".zmetadata").orElseThrow();
        store.set("bad/.zgroup", "{\"zarr_format\":7}".getBytes(StandardCharsets.UTF_8));
        assertThrows(ZarrFormatException.class, root::consolidate);
        assertArrayEquals(before, store.get(".zmetadata").orElseThrow());
    }

    /** Reading reports each node's format, consolidated snapshots included. */
    @Test
    void nodesReportTheirFormat() {
        assertEquals(2, Zarr.open(fixture("v2x_struct")).zarrFormat());
        ZarrGroup v2 = Zarr.openGroup(com.ebremer.falcon.zarr.store.FileSystemStore.open(fixture("consolidated_v2")));
        assertTrue(v2.isConsolidated());
        assertEquals(2, v2.zarrFormat());
        for (ZarrNode child : v2.children()) {
            assertEquals(2, child.zarrFormat(), child.path());
        }
        MemoryStore v3 = new MemoryStore();
        Zarr.createGroup(v3).createArray("a", ArraySpec.builder(new long[] {1}, DataType.INT8).build());
        assertEquals(3, Zarr.openGroup(v3).consolidate().array("a").zarrFormat());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v2x_nc_zfpy_f4_rate", "v2x_nc_delta_zfpy_i4"})
    void zfpyIsReadButNotCreated(String name) {
        assertThrows(IllegalArgumentException.class, () -> specOf(copy(fixture(name), false)));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** The spec a fixture's metadata describes, built with the public API from what Falcon reads. */
    static ArraySpec specOf(MemoryStore fixture) {
        JsonObject zarray = json(fixture, ".zarray");
        ZarrArray read = Zarr.openArray(fixture);
        ArraySpec.Builder b = ArraySpec.builder(read.shape(), read.dataType()).zarrFormat(2)
                .chunkShape(read.chunkShape())
                .order(zarray.get("order").asString().charAt(0))
                .separator(read.separator())
                .attributes(read.attributes())
                .endian(zarray.get("dtype").toJson().contains(">") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
                .fillValue(zarray.get("fill_value") instanceof JsonNull ? JsonNull.INSTANCE : read.fillValue());
        List<JsonObject> filters = new ArrayList<>();
        if (zarray.get("filters") instanceof JsonArray list) {
            list.values().forEach(f -> filters.add(f.asObject()));
            if (read.dataType().isVariableLength()) {
                filters.remove(0); // the object codec, which the spec adds by itself
            }
        }
        if (!filters.isEmpty()) {
            b.filters(filters.toArray(JsonObject[]::new));
        }
        if (zarray.get("compressor") instanceof JsonObject compressor) {
            b.compressor(compressor);
        }
        return b.build();
    }

    /** Whether two JSON values are the same, numbers compared by value ({@code 0} is {@code 0.0}). */
    static void assertSame(JsonValue want, JsonValue got, String what) {
        assertTrue(same(want, got), what + ": wanted " + want.toJson() + ", got " + got.toJson());
    }

    private static boolean same(JsonValue a, JsonValue b) {
        if (a instanceof JsonNumber n && b instanceof JsonNumber m && n.isFinite() && m.isFinite()) {
            return new BigDecimal(n.literal()).compareTo(new BigDecimal(m.literal())) == 0;
        }
        if (a instanceof JsonObject o && b instanceof JsonObject p) {
            return o.members().keySet().equals(p.members().keySet())
                    && o.members().keySet().stream().allMatch(k -> same(o.get(k), p.get(k)));
        }
        if (a instanceof JsonArray x && b instanceof JsonArray y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!same(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static String dtype(DataType type, ByteOrder order) {
        return zarray(ArraySpec.builder(new long[] {4}, type).zarrFormat(2).endian(order)).get("dtype").asString();
    }

    private static String fill(DataType type, Consumer<ArraySpec.Builder> setting) {
        ArraySpec.Builder b = ArraySpec.builder(new long[] {4}, type).zarrFormat(2);
        setting.accept(b);
        return zarray(b).get("fill_value").toJson();
    }

    /** The {@code .zarray} the spec creates. */
    private static JsonObject zarray(ArraySpec.Builder b) {
        MemoryStore store = new MemoryStore();
        Zarr.createArray(store, b.build());
        return json(store, ".zarray");
    }

    private static int[] toInts(byte[] littleEndian) {
        int[] out = new int[littleEndian.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((littleEndian[2 * i] & 0xff) | (littleEndian[2 * i + 1] << 8));
        }
        return out;
    }

    private static List<String> chunkKeys(MemoryStore store) {
        return store.list().stream().filter(k -> !k.startsWith(".") && !k.contains("/.")).sorted().toList();
    }

    private static JsonObject json(MemoryStore store, String key) {
        return Json.parse(store.get(key).orElseThrow()).asObject();
    }

    private static String text(MemoryStore store, String key) {
        return new String(store.get(key).orElseThrow(), StandardCharsets.UTF_8);
    }

    /** Writes the sidecar's values (every element, C order) into {@code a}. */
    private static void write(ZarrArray a, JsonObject want) {
        List<JsonValue> values = want.get("values").asArray().values();
        switch (a.dataType().kind()) {
            case INT, UINT, DATETIME, TIMEDELTA ->
                    a.writeLongs(values.stream().mapToLong(v -> v.asNumber().longValue()).toArray());
            case BOOL -> a.writeLongs(values.stream().mapToLong(v -> v.asBoolean() ? 1 : 0).toArray());
            case FLOAT -> a.writeDoubles(values.stream().mapToDouble(v -> v instanceof JsonNumber n
                    ? n.doubleValue() : Double.NaN).toArray());
            case STRING, FIXED_STRING ->
                    a.writeStrings(values.stream().map(JsonValue::asString).toArray(String[]::new));
            default -> a.writeByteArrays(values.stream().map(v -> HEX.parseHex(v.asString())).toArray(byte[][]::new));
        }
    }

    /** The fixture's files in a {@link MemoryStore}: its metadata, and its chunks if {@code withChunks}. */
    private static MemoryStore copy(Path dir, boolean withChunks) {
        MemoryStore store = new MemoryStore();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String key = dir.relativize(f).toString().replace('\\', '/');
                if (key.startsWith(".") || withChunks) {
                    store.set(key, Files.readAllBytes(f));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return store;
    }

    private static Path fixture(String name) {
        try {
            return Path.of(V2CreateTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name, e);
        }
    }

    private static JsonObject expected(String name) {
        try {
            return Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
