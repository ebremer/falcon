package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Zarr v2 arrays zarr-python 3.4 wrote (P2 F4, {@code tools/fixtures/gen_zarr_v2_ext_fixtures.py}): the v2
 * dtypes (U, S, V, M8/m8, structured, {@code |O}), Fortran order, the numcodecs filters and compressors, and
 * blosc and zstd configurations. Falcon must translate each to the pipeline the sidecar names and read
 * exactly what zarr-python reads; writing the same values must store what zarr-python stored.
 */
class V2FixtureTest {

    private static final HexFormat HEX = HexFormat.of();

    /** Arrays whose pipelines use only the v3 codecs Falcon has always had. */
    private static final String[] PLAIN = {
        "v2x_utf32", "v2x_utf32_be", "v2x_bytes_s", "v2x_raw_v", "v2x_datetime_ms", "v2x_datetime_10s_be",
        "v2x_timedelta_ns", "v2x_struct", "v2x_struct_be", "v2x_vlen_utf8", "v2x_vlen_bytes", "v2x_order_f_2d",
        "v2x_order_f_3d", "v2x_order_f_str", "v2x_order_f_utf32", "v2x_zstd_checksum", "v2x_blosc_auto_u1",
        "v2x_blosc_zstd_bitshuffle", "v2x_blosc_utf32", "v2x_blosc_struct_be"};

    @ParameterizedTest
    @ValueSource(strings = {
        "v2x_utf32", "v2x_utf32_be", "v2x_bytes_s", "v2x_raw_v", "v2x_datetime_ms", "v2x_datetime_10s_be",
        "v2x_timedelta_ns", "v2x_struct", "v2x_struct_be", "v2x_vlen_utf8", "v2x_vlen_bytes", "v2x_order_f_2d",
        "v2x_order_f_3d", "v2x_order_f_str", "v2x_order_f_utf32", "v2x_zstd_checksum", "v2x_blosc_auto_u1",
        "v2x_blosc_zstd_bitshuffle", "v2x_blosc_utf32", "v2x_blosc_struct_be"})
    void readsZarrPythonsV2Array(String name) {
        check(name, Zarr.open(fixture(name)).asArray());
    }

    /** Arrays whose filters or compressor are numcodecs codecs ({@code numcodecs.<id>}). */
    @ParameterizedTest
    @ValueSource(strings = {
        "v2x_nc_zlib", "v2x_nc_lz4", "v2x_nc_delta_astype", "v2x_nc_delta_i8", "v2x_nc_fixedscaleoffset_u1",
        "v2x_nc_fixedscaleoffset_u2", "v2x_nc_quantize", "v2x_nc_bitround", "v2x_nc_astype", "v2x_nc_packbits",
        "v2x_nc_shuffle", "v2x_nc_crc32", "v2x_nc_crc32c", "v2x_nc_adler32", "v2x_nc_fletcher32", "v2x_nc_jenkins",
        "v2x_nc_crc32_compressor", "v2x_nc_chain", "v2x_nc_order_f_delta", "v2x_nc_vlen_utf8_lz4"})
    void readsZarrPythonsV2ArrayThroughNumcodecs(String name) {
        check(name, Zarr.open(fixture(name)).asArray());
    }

    /**
     * Writing the values zarr-python read back into an empty copy of each array stores, chunk for chunk,
     * the bytes zarr-python stored, for the arrays whose codecs are exact (no compressor, or a lossless
     * filter): Fortran order, each dtype's encoding, and the fill elements of partial chunks agree.
     */
    @ParameterizedTest
    @ValueSource(strings = {"v2x_raw_v", "v2x_datetime_ms", "v2x_datetime_10s_be", "v2x_struct", "v2x_order_f_str",
        "v2x_order_f_utf32"})
    void writingStoresWhatZarrPythonStored(String name) {
        writeAndCompareChunks(name);
    }

    /** {@link #writingStoresWhatZarrPythonStored} for arrays with exact numcodecs filters. */
    @ParameterizedTest
    @ValueSource(strings = {"v2x_nc_delta_i8", "v2x_nc_fixedscaleoffset_u2", "v2x_nc_astype", "v2x_nc_packbits",
        "v2x_nc_crc32", "v2x_nc_crc32c", "v2x_nc_fletcher32", "v2x_nc_jenkins", "v2x_nc_crc32_compressor"})
    void writingThroughNumcodecsStoresWhatZarrPythonStored(String name) {
        writeAndCompareChunks(name);
    }

    /**
     * Blosc chunks Falcon writes into a v2 array carry the type size and shuffle numcodecs gives c-blosc for
     * it (the element size after the filters; the automatic shuffle resolved), as zarr-python's own do.
     */
    @Test
    void bloscChunksWrittenIntoV2ArraysCarryNumcodecsTypeSizeAndShuffle() {
        for (String name : List.of("v2x_blosc_auto_u1", "v2x_blosc_zstd_bitshuffle", "v2x_blosc_utf32",
                "v2x_blosc_struct_be")) {
            compareBloscHeaders(name, 0x07); // the shuffle bits and the memcpy flag
        }
    }

    /** {@link #bloscChunksWrittenIntoV2ArraysCarryNumcodecsTypeSizeAndShuffle} after numcodecs filters. */
    @Test
    void bloscChunksAfterNumcodecsFiltersCarryTheirTypeSize() {
        compareBloscHeaders("v2x_nc_delta_astype", 0x07);
        compareBloscHeaders("v2x_nc_chain", 0x07);
    }

    /** Blosc chunks Falcon writes into v2 arrays are compressed with the compressor their cname names. */
    @Test
    void bloscChunksUseTheCompressorTheirCnameNames() {
        for (String name : List.of("v2x_blosc_auto_u1", "v2x_blosc_zstd_bitshuffle", "v2x_blosc_utf32",
                "v2x_blosc_struct_be")) {
            compareBloscHeaders(name, 0xe7); // and the compressor's format code
        }
    }

    private static void compareBloscHeaders(String name, int flagMask) {
        Path src = fixture(name);
        MemoryStore store = copy(src, false);
        write(Zarr.openArray(store), expected(name));
        MemoryStore original = copy(src, true);
        for (String key : original.list()) {
            if (key.startsWith(".")) {
                continue;
            }
            byte[] want = original.get(key).orElseThrow();
            byte[] got = store.get(key).orElseThrow();
            assertEquals(want[3], got[3], name + " " + key + ": type size");
            assertEquals(want[2] & flagMask, got[2] & flagMask,
                    name + " " + key + ": flags " + Integer.toHexString(got[2] & 0xff));
        }
    }

    /** Every array round-trips through Falcon's writer: what it writes into a copy reads back the same. */
    @Test
    void everyPlainFixtureRoundTripsThroughFalconsWriter() {
        for (String name : PLAIN) {
            MemoryStore store = copy(fixture(name), false);
            ZarrArray a = Zarr.openArray(store);
            write(a, expected(name));
            check(name, Zarr.openArray(store));
        }
    }

    /** A box read from a Fortran-ordered array takes each element from its place in the F-ordered chunk. */
    @Test
    void fortranOrderBoxesRead() {
        ZarrArray a = Zarr.open(fixture("v2x_order_f_2d")).asArray();
        List<JsonValue> all = expected("v2x_order_f_2d").get("values").asArray().values();
        long[] box = a.select(new long[] {1, 2}, new long[] {3, 4}).readLongs();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(all.get((1 + r) * 7 + 2 + c).asNumber().longValue(), box[r * 4 + c], r + "," + c);
            }
        }
        ZarrArray s = Zarr.open(fixture("v2x_order_f_str")).asArray();
        assertArrayEquals(new String[] {"", "δ", "y", "z"}, s.select(new long[] {1, 1}, new long[] {2, 2}).readStrings());
    }

    /** A box written into a Fortran-ordered array lands in the F-ordered chunks it overlaps, and only there. */
    @Test
    void fortranOrderBoxesWrite() {
        MemoryStore store = copy(fixture("v2x_order_f_3d"), true);
        ZarrArray a = Zarr.openArray(store);
        double[] before = a.readDoubles();
        double[] box = new double[2 * 2 * 3];
        for (int i = 0; i < box.length; i++) {
            box[i] = 100 + i;
        }
        a.select(new long[] {1, 1, 1}, new long[] {2, 2, 3}).writeDoubles(box);
        double[] after = Zarr.openArray(store).readDoubles();
        int n = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 5; k++) {
                    int at = (i * 4 + j) * 5 + k;
                    boolean inside = i >= 1 && j >= 1 && j < 3 && k >= 1 && k < 4;
                    assertEquals(inside ? box[n++] : before[at], after[at], i + "," + j + "," + k);
                }
            }
        }
    }

    /** A v2 hierarchy's .zmetadata goes through the same translation: Fortran order and an object codec. */
    @Test
    void consolidatedV2MetadataIsTranslatedToo() throws IOException {
        MemoryStore store = new MemoryStore();
        Path src = fixture("v2x_order_f_str");
        try (Stream<Path> files = Files.list(src)) {
            for (Path f : files.toList()) {
                if (!f.getFileName().toString().startsWith(".")) {
                    store.set("s/" + f.getFileName(), Files.readAllBytes(f));
                }
            }
        }
        String zarray = Files.readString(src.resolve(".zarray"));
        store.set(".zgroup", "{\"zarr_format\":2}".getBytes(StandardCharsets.UTF_8));
        store.set(".zmetadata", ("{\"zarr_consolidated_format\":1,\"metadata\":{\".zgroup\":{\"zarr_format\":2},"
                + "\"s/.zarray\":" + zarray + "}}").getBytes(StandardCharsets.UTF_8));
        ZarrGroup root = Zarr.openGroup(store);
        assertTrue(root.isConsolidated());
        ZarrArray s = root.array("s"); // only the snapshot has its .zarray
        assertEquals(List.of("transpose", "vlen-utf8"), s.codecNames());
        assertArrayEquals(strings(expected("v2x_order_f_str")), s.readStrings());
    }

    /**
     * Every v2 fixture with a bit flipped in each chunk, and with its {@code .zarray} cut short or a member
     * replaced by a wrong-typed value: each reads or fails as a {@link ZarrException}.
     */
    @Test
    void corruptV2ArraysAreRejected() throws IOException {
        Random random = new Random(29);
        List<String> names;
        try (Stream<Path> dirs = Files.list(fixture("v2x_raw_v").getParent())) {
            names = dirs.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("v2x_") && !n.endsWith(".json"))
                    .sorted().toList();
        }
        String[] junk = {"null", "-1", "1.5", "\"\"", "[]", "{}", "[[\"a\"]]", "\"|O\"", "\"<U0\"", "[{\"id\":7}]"};
        for (String name : names) {
            for (int trial = 0; trial < 20; trial++) {
                MemoryStore store = copy(fixture(name), true);
                for (String key : store.list()) {
                    byte[] chunk = store.get(key).orElseThrow();
                    if (!key.startsWith(".") && chunk.length > 0) {
                        chunk[random.nextInt(chunk.length)] ^= (byte) (1 << random.nextInt(8));
                        store.set(key, chunk);
                    }
                }
                assertHandled(name + " trial " + trial, () -> readEverything(Zarr.openArray(store)));
            }
            JsonObject zarray = Json.parse(copy(fixture(name), false).get(".zarray").orElseThrow()).asObject();
            for (String member : zarray.members().keySet()) {
                for (String value : junk) {
                    JsonObject.Builder b = JsonObject.builder();
                    zarray.members().forEach((k, v) -> b.put(k, k.equals(member) ? Json.parse(value) : v));
                    MemoryStore store = copy(fixture(name), true);
                    store.set(".zarray", b.build().toJson().getBytes(StandardCharsets.UTF_8));
                    assertHandled(name + " ." + member + " = " + value, () -> readEverything(Zarr.openArray(store)));
                }
            }
            byte[] text = copy(fixture(name), false).get(".zarray").orElseThrow();
            MemoryStore store = copy(fixture(name), true);
            store.set(".zarray", Arrays.copyOf(text, text.length / 2));
            assertHandled(name + " cut", () -> readEverything(Zarr.openArray(store)));
        }
    }

    private static void assertHandled(String what, Runnable action) {
        try {
            action.run();
        } catch (ZarrException e) {
            return; // a typed, contained failure
        } catch (Throwable t) {
            throw new AssertionError(what + " threw an unacceptable " + t.getClass().getName() + ": " + t, t);
        }
    }

    private static void readEverything(ZarrArray array) {
        switch (array.dataType().kind()) {
            case BYTES, FIXED_BYTES, RAW_BYTES, STRUCT -> array.readByteArrays();
            case STRING, FIXED_STRING -> array.readStrings();
            case DATETIME, TIMEDELTA -> array.readLongs();
            default -> array.readRawBytes();
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static void check(String name, ZarrArray a) {
        JsonObject want = expected(name);
        List<JsonValue> shape = want.get("shape").asArray().values();
        assertArrayEquals(shape.stream().mapToLong(v -> v.asNumber().longValue()).toArray(), a.shape(), name);
        assertEquals(want.get("codecs").asArray().values().stream().map(JsonValue::asString).toList(),
                a.codecNames(), name);
        List<JsonValue> values = want.get("values").asArray().values();
        switch (a.dataType().kind()) {
            case INT, UINT, DATETIME, TIMEDELTA -> assertArrayEquals(
                    values.stream().mapToLong(v -> v.asNumber().longValue()).toArray(), a.readLongs(), name);
            case BOOL -> {
                double[] got = a.readDoubles();
                for (int i = 0; i < got.length; i++) {
                    assertEquals(values.get(i).asBoolean() ? 1.0 : 0.0, got[i], name + "[" + i + "]");
                }
            }
            case FLOAT -> {
                double[] got = a.readDoubles();
                assertEquals(values.size(), got.length, name);
                for (int i = 0; i < got.length; i++) {
                    JsonValue v = values.get(i);
                    if (v instanceof JsonString) {
                        assertTrue(Double.isNaN(got[i]), name + "[" + i + "]");
                    } else {
                        assertEquals(v.asNumber().doubleValue(), got[i], name + "[" + i + "]");
                    }
                }
            }
            case STRING, FIXED_STRING -> assertArrayEquals(strings(want), a.readStrings(), name);
            default -> { // byte strings, raw bytes, structs: hex (a struct packed little-endian)
                byte[][] got = a.readByteArrays();
                assertEquals(values.size(), got.length, name);
                for (int i = 0; i < got.length; i++) {
                    assertEquals(values.get(i).asString(), HEX.formatHex(got[i]), name + "[" + i + "]");
                }
            }
        }
    }

    private static String[] strings(JsonObject want) {
        return want.get("values").asArray().values().stream().map(JsonValue::asString).toArray(String[]::new);
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
            case STRING, FIXED_STRING -> a.writeStrings(strings(want));
            default -> a.writeByteArrays(values.stream().map(v -> HEX.parseHex(v.asString())).toArray(byte[][]::new));
        }
    }

    private static void writeAndCompareChunks(String name) {
        Path src = fixture(name);
        MemoryStore store = copy(src, false);
        write(Zarr.openArray(store), expected(name));
        MemoryStore original = copy(src, true);
        List<String> want = original.list().stream().filter(k -> !k.startsWith(".")).sorted().toList();
        assertEquals(want, store.list().stream().filter(k -> !k.startsWith(".")).sorted().toList(), name + ": chunk keys");
        for (String key : want) {
            assertArrayEquals(original.get(key).orElseThrow(), store.get(key).orElseThrow(), name + ": chunk " + key);
        }
    }

    /** The fixture's files in a {@link MemoryStore}: its metadata, and its chunks if {@code withChunks}. */
    private static MemoryStore copy(Path dir, boolean withChunks) {
        MemoryStore store = new MemoryStore();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String key = f.getFileName().toString();
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
            return Path.of(V2FixtureTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name + " (tools/fixtures/gen_zarr_v2_ext_fixtures.py)", e);
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
