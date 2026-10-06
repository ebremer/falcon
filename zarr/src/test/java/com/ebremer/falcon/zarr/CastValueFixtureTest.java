package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Arrays zarr-python 3.4 wrote with the {@code cast_value} codec (cast-value-rs 0.4.2;
 * {@code tools/fixtures/gen_zarr_cast_value_fixtures.py}): Falcon must read every element exactly as
 * zarr-python reads it back (the {@code .expected.json} sidecars), including the fill values a cast turns into
 * another value, a shard's absent sub-chunks behind a cast, and the quirks of cast-value-rs (an int64 cast to a
 * float16 infinity reads back as int64's maximum). Writing the values it read must give the chunks zarr-python
 * wrote, byte for byte, wherever the codecs are deterministic (no gzip or zstd, whose encoders differ).
 */
class CastValueFixtureTest {

    private static Path fixture(String name) {
        var url = CastValueFixtureTest.class.getResource("/fixtures/" + name);
        if (url == null) {
            throw new IllegalStateException("fixture not found: " + name
                    + " (regenerate with tools/fixtures/gen_zarr_cast_value_fixtures.py)");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cast_f64_u8", "cast_f64_i16_away_blosc", "cast_f32_f16_tz_zstd", "cast_i32_u8_wrap_gzip",
        "cast_numpy_compat", "cast_nan_fill_map", "cast_sharded_inner", "cast_before_sharding", "cast_twice",
        "cast_u64_f32_up_big", "cast_i64_f16_overflow", "cast_transpose", "cast_f16_i32_tz"})
    void readsWhatZarrPythonReads(String name) throws IOException {
        JsonObject meta = Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject();
        ZarrArray array = Zarr.open(fixture(name)).asArray();
        assertEquals(meta.get("dtype").asString(), array.dataType().name(), name);
        JsonArray shape = meta.get("shape").asArray();
        for (int i = 0; i < shape.size(); i++) {
            assertEquals(shape.get(i).asNumber().longValue(), array.shape()[i], name + " shape");
        }
        List<JsonValue> want = meta.get("values").asArray().values();
        String[] got = values(array, array.selectAll());
        assertEquals(want.size(), got.length, name);
        for (int i = 0; i < got.length; i++) {
            assertEquals(want.get(i).asString(), got[i], name + "[" + i + "]");
        }
        // Every 3 x 3 region read alone gives what the whole read gave: a shard behind a cast_value is read
        // sub-chunk by sub-chunk, its absent sub-chunks the cast fill value cast back.
        for (long r = 0; r < array.shape()[0]; r += 2) {
            for (long c = 0; c < array.shape()[1]; c += 3) {
                long rows = Math.min(3, array.shape()[0] - r);
                long cols = Math.min(3, array.shape()[1] - c);
                String[] part = values(array, array.select(new long[] {r, c}, new long[] {rows, cols}));
                for (int k = 0; k < part.length; k++) {
                    long index = (r + k / cols) * array.shape()[1] + c + k % cols;
                    assertEquals(got[(int) index], part[k], name + " region at " + r + "," + c + " element " + k);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cast_f64_u8", "cast_f64_i16_away_blosc", "cast_numpy_compat", "cast_nan_fill_map",
        "cast_sharded_inner", "cast_before_sharding", "cast_twice", "cast_u64_f32_up_big", "cast_i64_f16_overflow",
        "cast_transpose", "cast_f16_i32_tz"})
    void writesTheChunksZarrPythonWrote(String name) throws IOException {
        Path root = fixture(name);
        JsonObject written = Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject()
                .get("written").asObject();
        MemoryStore store = new MemoryStore();
        store.set("zarr.json", Files.readAllBytes(root.resolve("zarr.json")));
        ZarrArray array = Zarr.openArray(store);
        Selection s = array.select(longs(written.get("origin").asArray()), longs(written.get("shape").asArray()));
        List<JsonValue> values = written.get("values").asArray().values();
        switch (array.dataType().kind()) {
            case FLOAT -> s.writeDoubles(values.stream().mapToDouble(v -> fromPythonHex(v.asString())).toArray());
            case UINT -> s.writeUnsignedLongs(values.stream().mapToLong(v -> Long.parseUnsignedLong(v.asString()))
                    .toArray());
            default -> s.writeLongs(values.stream().mapToLong(v -> Long.parseLong(v.asString())).toArray());
        }

        Map<String, byte[]> stored = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root.resolve("c"))) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                stored.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        Map<String, byte[]> ours = new TreeMap<>();
        for (String key : store.list()) {
            if (!key.equals("zarr.json")) {
                ours.put(key, store.get(key).orElseThrow());
            }
        }
        assertEquals(stored.keySet(), ours.keySet(), name + ": chunk keys");
        int subChunks = subChunkCount(Json.parse(Files.readAllBytes(root.resolve("zarr.json"))).asObject());
        for (var e : stored.entrySet()) {
            if (subChunks == 0) {
                assertArrayEquals(e.getValue(), ours.get(e.getKey()), name + ": " + e.getKey());
            } else {
                // zarr-python lays a shard's sub-chunks out in Morton order, Falcon in C order: each must match.
                for (int k = 0; k < subChunks; k++) {
                    assertArrayEquals(subChunk(e.getValue(), subChunks, k),
                            subChunk(ours.get(e.getKey()), subChunks, k), name + ": " + e.getKey() + " sub-chunk " + k);
                }
            }
        }
        assertTrue(!stored.isEmpty(), name);
    }

    /** The number of sub-chunks in a shard, if the outermost array->bytes codec is sharding_indexed, else 0. */
    private static int subChunkCount(JsonObject meta) {
        JsonArray chunk = meta.get("chunk_grid").asObject().get("configuration").asObject()
                .get("chunk_shape").asArray();
        for (JsonValue codec : meta.get("codecs").asArray().values()) {
            if (codec.asObject().get("name").asString().equals("sharding_indexed")) {
                JsonArray sub = codec.asObject().get("configuration").asObject().get("chunk_shape").asArray();
                int n = 1;
                for (int i = 0; i < sub.size(); i++) {
                    n *= chunk.get(i).asNumber().intValue() / sub.get(i).asNumber().intValue();
                }
                return n;
            }
        }
        return 0;
    }

    /** Sub-chunk {@code k}'s bytes in a shard indexed at its end (little-endian, crc32c), or null if absent. */
    private static byte[] subChunk(byte[] shard, int count, int k) {
        ByteBuffer index = ByteBuffer.wrap(shard, shard.length - 4 - 16 * count, 16 * count)
                .slice().order(ByteOrder.LITTLE_ENDIAN);
        long offset = index.getLong(16 * k);
        long length = index.getLong(16 * k + 8);
        return offset == -1 && length == -1 ? null
                : Arrays.copyOfRange(shard, (int) offset, (int) (offset + length));
    }

    private static long[] longs(JsonArray a) {
        return a.values().stream().mapToLong(v -> v.asNumber().longValue()).toArray();
    }

    /** The inverse of {@link #pythonHex}. */
    private static double fromPythonHex(String s) {
        return switch (s) {
            case "nan" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(s);
        };
    }

    /** The elements as the sidecar writes them: integers in decimal, floats as Python's float.hex(). */
    private static String[] values(ZarrArray array, Selection s) {
        int n = (int) s.elementCount();
        String[] out = new String[n];
        DataTypeKind kind = array.dataType().kind();
        if (kind == DataTypeKind.FLOAT) {
            double[] d = s.readDoubles();
            for (int i = 0; i < n; i++) {
                out[i] = pythonHex(d[i]);
            }
        } else if (kind == DataTypeKind.UINT) {
            long[] v = s.readUnsignedLongs();
            for (int i = 0; i < n; i++) {
                out[i] = Long.toUnsignedString(v[i]);
            }
        } else {
            long[] v = s.readLongs();
            for (int i = 0; i < n; i++) {
                out[i] = Long.toString(v[i]);
            }
        }
        return out;
    }

    /** Python's {@code float.hex()}: {@code 0x1.8000000000000p+1}, {@code -0x0.0p+0}, {@code nan}, {@code inf}. */
    static String pythonHex(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        long bits = Double.doubleToRawLongBits(d);
        String sign = bits < 0 ? "-" : "";
        int exponent = (int) ((bits >>> 52) & 0x7ff);
        long mantissa = bits & 0xfffffffffffffL;
        if (exponent == 0 && mantissa == 0) {
            return sign + "0x0.0p+0";
        }
        int e = exponent == 0 ? -1022 : exponent - 1023;
        return sign + (exponent == 0 ? "0x0." : "0x1.") + String.format("%013x", mantissa) + "p"
                + (e >= 0 ? "+" : "") + e;
    }
}
