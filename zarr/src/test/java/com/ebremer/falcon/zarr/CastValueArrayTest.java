package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Arrays with the {@code cast_value} codec through the public API: {@link ArraySpec.Builder#castValue} writes the
 * codec where and as zarr-python writes it, a write casts and a read casts back, and what cannot be cast fails
 * the build or the write.
 */
class CastValueArrayTest {

    @Test
    void theSpecWritesTheCodecAsZarrPythonDoes() {
        ArraySpec spec = ArraySpec.builder(new long[] {10}, DataType.FLOAT64).chunkShape(4)
                .castValue(DataType.UINT8, "nearest-even", "clamp", null).blosc().build();
        assertEquals(Json.parse("[{\"name\":\"cast_value\",\"configuration\":{\"data_type\":\"uint8\","
                + "\"out_of_range\":\"clamp\"}},{\"name\":\"bytes\"},{\"name\":\"blosc\",\"configuration\":"
                + "{\"cname\":\"zstd\",\"clevel\":5,\"shuffle\":\"noshuffle\",\"typesize\":1,\"blocksize\":0}}]"),
                spec.toJson().get("codecs"));

        // inside the shard, as zarr-python puts its filters; a big-endian float32 target keeps the endian
        spec = ArraySpec.builder(new long[] {8, 8}, DataType.FLOAT64).chunkShape(4, 4).sharding(2, 2)
                .endian(ByteOrder.BIG_ENDIAN)
                .castValue(DataType.FLOAT32, "towards-zero", null,
                        Json.parse("{\"encode\":[[\"NaN\",0.0]]}").asObject()).build();
        JsonObject sharding = spec.toJson().get("codecs").asArray().get(0).asObject();
        assertEquals("sharding_indexed", sharding.get("name").asString());
        assertEquals(Json.parse("[{\"name\":\"cast_value\",\"configuration\":{\"data_type\":\"float32\","
                + "\"rounding\":\"towards-zero\",\"scalar_map\":{\"encode\":[[\"NaN\",0.0]]}}},"
                + "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}}]"),
                sharding.get("configuration").asObject().get("codecs"));
    }

    @Test
    void theBuilderRefusesWhatCannotBeCast() {
        ArraySpec.Builder b = ArraySpec.builder(new long[] {4}, DataType.FLOAT64);
        assertThrows(IllegalArgumentException.class, () -> b.castValue(DataType.BOOL));
        assertThrows(IllegalArgumentException.class, () -> b.castValue(DataType.COMPLEX64));
        assertThrows(IllegalArgumentException.class, () -> b.castValue(DataType.INT8, "bogus", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> b.castValue(DataType.INT8, "nearest-even", "saturate", null));
        // checked when built: wrap needs an integer target, the fill value must cast both ways, the scalar_map
        // must hold values of the types, and the elements must be numbers
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {4}, DataType.FLOAT64)
                .castValue(DataType.FLOAT32, "nearest-even", "wrap", null).build());
        IllegalArgumentException fill = assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {4}, DataType.FLOAT64).fillValue(300)
                        .castValue(DataType.UINT8).build());
        assertTrue(fill.getMessage().contains("fill value"), fill.getMessage());
        ArraySpec.builder(new long[] {4}, DataType.FLOAT64).fillValue(300)
                .castValue(DataType.UINT8, "nearest-even", "clamp", null).build();
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {4}, DataType.FLOAT64)
                .castValue(DataType.UINT8, "nearest-even", null, Json.parse("{\"encode\":[[1,256]]}").asObject())
                .build());
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {4}, DataType.STRING)
                .castValue(DataType.UINT8).build());
    }

    @Test
    void writesCastAndReadsCastBack() {
        // towards-zero into int16, clamped; shards whose sub-chunks hold the cast; fill 2.5 casts to 2
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {13, 7}, DataType.FLOAT64)
                .chunkShape(6, 4).sharding(3, 2).fillValue(2.5)
                .castValue(DataType.INT16, "towards-zero", "clamp", null).build());
        double[] values = new double[7 * 4];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i - 14) * 2500.75;
        }
        a.select(new long[] {2, 1}, new long[] {7, 4}).writeDoubles(values);
        double[] all = a.readDoubles();
        for (int r = 0; r < 13; r++) {
            for (int c = 0; c < 7; c++) {
                double want;
                if (r >= 2 && r < 9 && c >= 1 && c < 5) {
                    double v = values[(r - 2) * 4 + (c - 1)];
                    want = Math.max(-32768, Math.min(32767, v < 0 ? Math.ceil(v) : Math.floor(v)));
                } else {
                    // a written sub-chunk's other elements are the fill value cast and cast back; the rest is
                    // absent and reads as the fill value itself. Rows 2..8 and columns 1..4 touch the 3 x 2
                    // sub-chunks of the first three block rows and block columns.
                    boolean touched = r / 3 <= 2 && c / 2 <= 2;
                    want = touched ? 2 : 2.5;
                }
                assertEquals(want, all[r * 7 + c], "element " + r + "," + c);
            }
        }
        // reopened, and written over whole
        ZarrArray again = Zarr.openArray(store);
        double[] whole = new double[91];
        for (int i = 0; i < whole.length; i++) {
            whole[i] = i - 0.5;
        }
        again.writeDoubles(whole);
        double[] back = again.readDoubles();
        for (int i = 0; i < whole.length; i++) {
            assertEquals(i == 0 ? 0 : i - 1, back[i], "element " + i); // -0.5 truncates to -0.0
        }
    }

    @Test
    void theSpecificationsNumpyExample() {
        ZarrArray a = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {6}, DataType.FLOAT64)
                .castValue(DataType.UINT8, "towards-zero", "wrap",
                        Json.parse("{\"encode\":[[\"NaN\",0],[\"Infinity\",0],[\"-Infinity\",0]]}").asObject())
                .build());
        a.writeDoubles(new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 300.7, -1.5, 128});
        assertArrayEquals(new double[] {0, 0, 0, 44, 255, 128}, a.readDoubles());
    }

    @Test
    void anArrayWhoseFillValueCannotBeCastFailsWhenItsCodecsAreBuilt() {
        // zarr-python refuses this one when it opens it; Falcon builds the codec pipeline on first use
        MemoryStore store = new MemoryStore();
        store.set("zarr.json", ("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[4],\"data_type\":\"float64\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[4]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":300,\"codecs\":["
                + "{\"name\":\"cast_value\",\"configuration\":{\"data_type\":\"uint8\"}},{\"name\":\"bytes\"}],"
                + "\"attributes\":{}}").getBytes(StandardCharsets.UTF_8));
        ZarrArray a = Zarr.openArray(store);
        ZarrFormatException e = assertThrows(ZarrFormatException.class, a::readDoubles);
        assertTrue(e.getMessage().contains("fill value") && e.getMessage().contains("300"), e.getMessage());
        assertThrows(ZarrFormatException.class, () -> a.writeDoubles(new double[] {1, 2, 3, 4}));
    }

    @Test
    void aWriteThatCannotBeCastFailsAndStoresNothing() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.FLOAT32)
                .castValue(DataType.INT8).build());
        ZarrFormatException nan = assertThrows(ZarrFormatException.class,
                () -> a.writeDoubles(new double[] {1, Double.NaN, 2, 3}));
        assertTrue(nan.getMessage().contains("cast_value"), nan.getMessage());
        assertThrows(ZarrFormatException.class, () -> a.writeDoubles(new double[] {1, 128, 2, 3}));
        assertFalse(store.exists("c/0"));
        a.writeDoubles(new double[] {1, 127.4, -128.5, 3});
        assertArrayEquals(new double[] {1, 127, -128, 3}, a.readDoubles());
    }
}
