package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZarrWriteTest {

    private static int[] sequence(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i;
        }
        return out;
    }

    // ---- round trips ------------------------------------------------------------------------------

    @Test
    void writesAndReadsWholeArray() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {10}, DataType.INT32).chunkShape(4).build());
        a.writeInts(sequence(10));
        assertArrayEquals(sequence(10), Zarr.openArray(store).readInts());
    }

    @Test
    void roundTripsWithGzipAndChecksum() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {12}, DataType.INT32)
                .chunkShape(5).gzip(6).crc32c().build());
        a.writeInts(sequence(12));
        assertArrayEquals(sequence(12), Zarr.openArray(store).readInts());
    }

    @Test
    void roundTripsTwoDimensional() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4, 6}, DataType.INT32)
                .chunkShape(2, 3).build());
        a.writeInts(sequence(24));
        ZarrArray reopened = Zarr.openArray(store);
        assertArrayEquals(sequence(24), reopened.readInts());
        assertArrayEquals(new int[] {8, 9, 10, 14, 15, 16},
                reopened.select(new long[] {1, 2}, new long[] {2, 3}).readInts());
    }

    @Test
    void roundTripsFloatsAndBigEndian() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {5}, DataType.FLOAT64)
                .chunkShape(2).endian(ByteOrder.BIG_ENDIAN).fillValue(Double.NaN).build());
        double[] values = {1.5, -2.25, 3.0, 4.75, 5.5};
        a.writeDoubles(values);
        assertArrayEquals(values, Zarr.openArray(store).readDoubles());
    }

    @Test
    void roundTripsEdgeChunks() {
        MemoryStore store = new MemoryStore();
        // 10 elements in chunks of 4: the last chunk is an edge chunk with 2 valid elements.
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {10}, DataType.INT32).chunkShape(4).build());
        a.writeInts(sequence(10));
        assertArrayEquals(sequence(10), Zarr.openArray(store).readInts());
    }

    // ---- partial writes ---------------------------------------------------------------------------

    @Test
    void partialWriteUpdatesOnlyTheSelection() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {10}, DataType.INT32).chunkShape(4).build());
        a.writeInts(sequence(10));

        // spans chunk 0 (index 3) and chunk 1 (indices 4..6)
        a.select(new long[] {3}, new long[] {4}).writeInts(new int[] {100, 101, 102, 103});
        assertArrayEquals(new int[] {0, 1, 2, 100, 101, 102, 103, 7, 8, 9},
                Zarr.openArray(store).readInts());
    }

    @Test
    void partialWriteIntoAnEmptyArrayFillsTheRest() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {8}, DataType.INT32)
                .chunkShape(4).fillValue(9).build());
        a.select(new long[] {2}, new long[] {3}).writeInts(new int[] {1, 2, 3});
        assertArrayEquals(new int[] {9, 9, 1, 2, 3, 9, 9, 9}, Zarr.openArray(store).readInts());
    }

    // ---- empty chunks -----------------------------------------------------------------------------

    @Test
    void allFillChunksAreNotStored() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {8}, DataType.INT32).chunkShape(4).build());
        a.writeInts(new int[8]); // all zeros == the fill value
        assertEquals(List.of("zarr.json"), store.list());

        a.writeInts(new int[] {1, 2, 3, 4, 0, 0, 0, 0});
        assertEquals(List.of("c/0", "zarr.json"), store.list()); // only the non-fill chunk

        a.writeInts(new int[8]); // back to all fill: the chunk is deleted again
        assertEquals(List.of("zarr.json"), store.list());
    }

    // ---- sharding ---------------------------------------------------------------------------------

    @Test
    void roundTripsShardedArray() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {16}, DataType.INT32)
                .chunkShape(8).sharding(4).build());
        a.writeInts(sequence(16));
        assertArrayEquals(sequence(16), Zarr.openArray(store).readInts());
        assertTrue(store.exists("c/0") && store.exists("c/1"));
    }

    @Test
    void shardOmitsAllFillSubChunks() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.INT32)
                .chunkShape(4).sharding(2).build());
        a.writeInts(new int[] {0, 0, 5, 6}); // first sub-chunk is all fill
        assertArrayEquals(new int[] {0, 0, 5, 6}, Zarr.openArray(store).readInts());

        // one 8-byte sub-chunk payload + a 2-entry index (32 bytes) + crc32c (4) = 44 bytes
        assertEquals(44, store.get("c/0").orElseThrow().length);
    }

    @Test
    void roundTripsShardedArrayWithIndexAtStartAndInnerGzip() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4, 4}, DataType.INT32)
                .chunkShape(4, 4).sharding(2, 2).shardIndexAtStart().gzip(4).crc32c().build());
        a.writeInts(sequence(16));
        assertArrayEquals(sequence(16), Zarr.openArray(store).readInts());
    }

    // ---- metadata ---------------------------------------------------------------------------------

    @Test
    void writtenMetadataIsSpecOrdered() {
        MemoryStore store = new MemoryStore();
        Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.INT32).chunkShape(2).build());
        String json = new String(store.get("zarr.json").orElseThrow(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[4],\"data_type\":\"int32\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[2]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\",\"configuration\":{\"separator\":\"/\"}},"
                + "\"fill_value\":0,"
                + "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}],"
                + "\"attributes\":{}}", json);
    }

    @Test
    void singleByteTypesOmitEndian() {
        MemoryStore store = new MemoryStore();
        Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.UINT8).build());
        String json = new String(store.get("zarr.json").orElseThrow(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"codecs\":[{\"name\":\"bytes\"}]"), json);
    }

    @Test
    void attributesAndDimensionNamesRoundTrip() {
        MemoryStore store = new MemoryStore();
        JsonObject attrs = JsonObject.builder().put("units", "K").put("scale", 2).build();
        Zarr.createArray(store, ArraySpec.builder(new long[] {2, 3}, DataType.INT32)
                .attributes(attrs).dimensionNames("y", null).build());

        ZarrArray a = Zarr.openArray(store);
        assertEquals("K", a.attributes().get("units").asString());
        assertEquals(2, a.attributes().get("scale").asNumber().intValue());
        List<String> names = a.dimensionNames().orElseThrow();
        assertEquals("y", names.get(0));
        assertEquals(null, names.get(1));
    }

    // ---- hierarchy --------------------------------------------------------------------------------

    @Test
    void createsNestedHierarchy() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store, JsonObject.builder().put("title", "root").build());
        ZarrGroup sub = root.createGroup("sub");
        sub.createArray("values", ArraySpec.builder(new long[] {4}, DataType.INT32).chunkShape(2).build())
                .writeInts(new int[] {1, 2, 3, 4});
        root.createArray("top", ArraySpec.builder(new long[] {2}, DataType.INT32).build())
                .writeInts(new int[] {7, 8});

        ZarrGroup reopened = Zarr.openGroup(store);
        assertEquals("root", reopened.attributes().get("title").asString());
        assertEquals(List.of("sub", "top"), reopened.childNames());
        assertArrayEquals(new int[] {7, 8}, reopened.array("top").readInts());
        assertArrayEquals(new int[] {1, 2, 3, 4}, reopened.group("sub").array("values").readInts());
    }

    @Test
    void writesToTheFilesystemAndReopens(@TempDir Path tmp) {
        Path root = tmp.resolve("store");
        FileSystemStore fs = FileSystemStore.open(root);
        ZarrGroup group = Zarr.createGroup(fs);
        group.createArray("data", ArraySpec.builder(new long[] {6}, DataType.FLOAT64)
                .chunkShape(4).gzip(5).build())
                .writeDoubles(new double[] {1, 2, 3, 4, 5, 6});

        // reopen read-only from the directory
        ZarrGroup reopened = Zarr.open(root).asGroup();
        assertArrayEquals(new double[] {1, 2, 3, 4, 5, 6}, reopened.array("data").readDoubles());
    }

    @Test
    void readOnlyStoreRejectsWrites(@TempDir Path tmp) {
        Path root = tmp.resolve("store");
        FileSystemStore writable = FileSystemStore.open(root);
        Zarr.createArray(writable, ArraySpec.builder(new long[] {4}, DataType.INT32).build())
                .writeInts(new int[] {1, 2, 3, 4});

        ZarrArray readOnly = Zarr.open(root).asArray();
        assertThrows(UnsupportedOperationException.class, () -> readOnly.writeInts(new int[] {9, 9, 9, 9}));
    }

    // ---- randomized round trips --------------------------------------------------------------------

    @Test
    void randomizedRoundTrips() {
        Random random = new Random(20260720L);
        for (int trial = 0; trial < 25; trial++) {
            int rows = 1 + random.nextInt(7);
            int cols = 1 + random.nextInt(7);
            int chunkRows = 1 + random.nextInt(rows);
            int chunkCols = 1 + random.nextInt(cols);
            int[] values = new int[rows * cols];
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextInt(1000) - 500;
            }

            MemoryStore store = new MemoryStore();
            ArraySpec.Builder spec = ArraySpec.builder(new long[] {rows, cols}, DataType.INT32)
                    .chunkShape(chunkRows, chunkCols);
            if (random.nextBoolean()) {
                spec.gzip(1 + random.nextInt(9));
            }
            if (random.nextBoolean()) {
                spec.crc32c();
            }
            ZarrArray a = Zarr.createArray(store, spec.build());
            a.writeInts(values);
            assertArrayEquals(values, Zarr.openArray(store).readInts(),
                    "trial " + trial + " shape=" + rows + "x" + cols
                            + " chunks=" + chunkRows + "x" + chunkCols);
        }
    }

    @Test
    void writeLengthIsValidated() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.INT32).build());
        assertThrows(IllegalArgumentException.class, () -> a.writeInts(new int[] {1, 2, 3}));
        assertFalse(store.exists("c/0"));
    }

    @Test
    void emptyAttributesObjectIsPreserved() {
        MemoryStore store = new MemoryStore();
        Zarr.createGroup(store);
        assertEquals("{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}",
                Json.write(Json.parse(store.get("zarr.json").orElseThrow())));
        assertTrue(Zarr.openGroup(store).attributes().members().isEmpty());
        assertTrue(new JsonObject(Map.of()).members().isEmpty());
    }
}
