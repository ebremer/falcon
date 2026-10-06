package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Zarr v3 arrays with the {@code reshape} codec ({@code tools/fixtures/gen_zarr_reshape_fixtures.py}): each chunk
 * reshaped with NumPy and stored as zarr-python stores an array of the reshaped shape with the codecs after
 * reshape, zarr-python 3.4 having no reshape codec of its own. Falcon reads every element, and writing the same
 * values into an empty copy stores the very same chunks: merged, split, and explicitly sized dimensions, empty
 * {@code input_dims}, a shard of the reshaped chunk, transpose on either side, a rectilinear grid whose chunks
 * reshape to different shapes, and strings.
 */
class ReshapeFixtureTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "reshape_spec_example_uint16", "reshape_merge_int32", "reshape_split_float64", "reshape_sizes_uint8",
        "reshape_empty_dims_int64", "reshape_sharded_int16", "reshape_sharded_3d_to_2d_float32",
        "reshape_after_transpose_int32", "reshape_before_transpose_int32", "reshape_rectilinear_int32",
        "reshape_rectilinear_sharded_int32", "reshape_strings"})
    void readsEveryElement(String name) {
        ZarrArray a = Zarr.open(Bz2ZfpyFixtureTest.fixture(name)).asArray();
        check(name, a);
        // a box across chunk boundaries reads the same elements as the whole
        long[] shape = a.shape();
        long[] origin = new long[shape.length];
        long[] extent = shape.clone();
        origin[0] = 1;
        extent[0] = shape[0] - 2;
        Selection box = a.select(origin, extent);
        byte[] whole = a.dataType().isVariableLength() ? null : a.readRawBytes();
        if (whole != null) {
            int size = a.dataType().byteCount();
            long row = whole.length / shape[0];
            assertArrayEquals(java.util.Arrays.copyOfRange(whole, (int) row, (int) (row * (shape[0] - 1))),
                    box.readRawBytes(), name + " box");
            assertEquals(0, row % size);
        } else {
            String[] all = a.readStrings();
            int row = all.length / (int) shape[0];
            assertArrayEquals(java.util.Arrays.copyOfRange(all, row, row * ((int) shape[0] - 1)), box.readStrings(),
                    name + " box");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "reshape_spec_example_uint16", "reshape_merge_int32", "reshape_split_float64", "reshape_sizes_uint8",
        "reshape_empty_dims_int64", "reshape_sharded_int16", "reshape_sharded_3d_to_2d_float32",
        "reshape_after_transpose_int32", "reshape_before_transpose_int32", "reshape_rectilinear_int32",
        "reshape_rectilinear_sharded_int32", "reshape_strings"})
    void writingStoresTheSameChunks(String name) {
        Map<String, byte[]> original = Bz2ZfpyFixtureTest.files(Bz2ZfpyFixtureTest.fixture(name), true);
        MemoryStore store = Bz2ZfpyFixtureTest.store(Bz2ZfpyFixtureTest.files(Bz2ZfpyFixtureTest.fixture(name), false));
        ZarrArray a = Zarr.openArray(store);
        write(a, Bz2ZfpyFixtureTest.expected(name));
        assertEquals(original.keySet(), Bz2ZfpyFixtureTest.keys(store), name + ": keys");
        for (Map.Entry<String, byte[]> e : original.entrySet()) {
            if (!e.getKey().equals("zarr.json")) {
                assertArrayEquals(e.getValue(), store.get(e.getKey()).orElseThrow(), name + ": chunk " + e.getKey());
            }
        }
        check(name, a);
    }

    /** A box written into a reshaped array re-encodes the chunks it touches, merging what they held. */
    @ParameterizedTest
    @ValueSource(strings = {"reshape_split_float64", "reshape_sharded_int16", "reshape_before_transpose_int32",
        "reshape_rectilinear_sharded_int32"})
    void partialWritesMergeWithWhatIsStored(String name) {
        MemoryStore store = Bz2ZfpyFixtureTest.store(Bz2ZfpyFixtureTest.files(Bz2ZfpyFixtureTest.fixture(name), true));
        ZarrArray a = Zarr.openArray(store);
        long[] shape = a.shape();
        double[] values = a.readDoubles();
        long[] origin = {1, 1};
        long[] extent = {shape[0] - 3, shape[1] - 2};
        double[] box = new double[(int) (extent[0] * extent[1])];
        java.util.Arrays.fill(box, 7);
        a.select(origin, extent).writeDoubles(box);
        for (int r = 1; r < shape[0] - 2; r++) {
            for (int c = 1; c < shape[1] - 1; c++) {
                values[(int) (r * shape[1] + c)] = 7;
            }
        }
        assertArrayEquals(values, Zarr.openArray(store).readDoubles(), name);
    }

    static void check(String name, ZarrArray a) {
        JsonObject want = Bz2ZfpyFixtureTest.expected(name);
        if (a.dataType().isVariableLength()) {
            assertArrayEquals(want.get("shape").asArray().values().stream().mapToLong(v -> v.asNumber().longValue())
                    .toArray(), a.shape(), name);
            assertEquals(want.get("codecs").asArray().values().stream().map(JsonValue::asString).toList(),
                    a.codecNames(), name);
            assertArrayEquals(strings(want), a.readStrings(), name);
        } else {
            Bz2ZfpyFixtureTest.check(name, a);
        }
    }

    private static String[] strings(JsonObject want) {
        return want.get("values").asArray().values().stream().map(JsonValue::asString).toArray(String[]::new);
    }

    private static void write(ZarrArray a, JsonObject want) {
        List<JsonValue> values = want.get("values").asArray().values();
        switch (a.dataType().kind()) {
            case STRING -> a.writeStrings(strings(want));
            case FLOAT -> a.writeDoubles(values.stream().mapToDouble(v -> v.asNumber().doubleValue()).toArray());
            default -> a.writeLongs(values.stream().mapToLong(v -> v.asNumber().longValue()).toArray());
        }
    }
}
