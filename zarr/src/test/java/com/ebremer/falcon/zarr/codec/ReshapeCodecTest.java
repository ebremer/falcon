package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code reshape} codec (zarr-extensions {@code codecs/reshape}): its output shape for each form of
 * {@code shape}, every rule the spec gives (the spec's own allowed and refused examples among them), the codec
 * resolved per chunk shape, and its place in a pipeline: with {@code transpose}, a shard of the reshaped chunk,
 * bytes&rarr;bytes codecs, and variable-length elements. zarr-python 3.4 has no reshape codec, so the spec is the
 * oracle; ReshapeFixtureTest checks stored chunks against NumPy's reshape and zarr-python's other codecs.
 */
class ReshapeCodecTest {

    private static final JsonObject BYTES = codec("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}");

    private static JsonObject codec(String json) {
        return Json.parse(json).asObject();
    }

    private static JsonObject reshape(String shape) {
        return codec("{\"name\":\"reshape\",\"configuration\":{\"shape\":" + shape + "}}");
    }

    /** The reshaped shape of a chunk of {@code input} under {@code shape}, malformed JSON refused as a pipeline does. */
    private static int[] resolve(String shape, int... input) {
        try {
            return ReshapeCodec.parse(codec("{\"shape\":" + shape + "}"), input).encodedShape(input);
        } catch (JsonException e) {
            throw new ZarrFormatException("invalid codec configuration: " + e.getMessage(), e);
        }
    }

    private static void refused(String shape, String why, int... input) {
        ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> resolve(shape, input), shape);
        assertTrue(e.getMessage().contains(why), shape + ": " + e.getMessage());
    }

    @Test
    void theSpecsExampleMergesAndKeeps() {
        // chunk_shape [100, 50, 64, 3], "shape": [[0, 1], [2], 3]
        assertArrayEquals(new int[] {5000, 64, 3}, resolve("[[0,1],[2],3]", 100, 50, 64, 3));
    }

    @Test
    void eachFormOfShapeResolves() {
        assertArrayEquals(new int[] {24}, resolve("[[0,1]]", 4, 6));
        assertArrayEquals(new int[] {24}, resolve("[-1]", 4, 6));
        assertArrayEquals(new int[] {24}, resolve("[24]", 4, 6));
        assertArrayEquals(new int[] {3, 8}, resolve("[3,8]", 4, 6));        // sizes alone: only the count matters
        assertArrayEquals(new int[] {4, 2, 3}, resolve("[[0],2,-1]", 4, 6)); // a split
        assertArrayEquals(new int[] {4, 6}, resolve("[-1,[1]]", 4, 6));
        assertArrayEquals(new int[] {1, 4, 1, 6, 1}, resolve("[[],[0],[],[1],1]", 4, 6)); // empty input_dims: 1
        assertArrayEquals(new int[] {6, 10, 20}, resolve("[[0,1],10,[3,4]]", 2, 3, 10, 4, 5)); // the spec's allowed one
        assertArrayEquals(new int[] {1, 2}, resolve("[1,-1]", 2));
        assertArrayEquals(new int[] {}, resolve("[]"));                          // a scalar stays one
        assertArrayEquals(new int[] {1, 1}, resolve("[[],-1]"));
        assertArrayEquals(new int[] {7}, resolve("[[0,1,2]]", 7, 1, 1));
    }

    @Test
    void theInputDimensionsMustStrictlyIncrease() {
        // the spec's refused examples
        refused("[[1],[0]]", "strictly increase", 2, 3);
        refused("[[1,0],10,[3,4]]", "strictly increase", 2, 3, 10, 4, 5);
        refused("[[3,4],10,[0,1]]", "strictly increase", 2, 3, 10, 4, 5);
        refused("[[0,0]]", "strictly increase", 4, 4);
        refused("[[0],[0,1]]", "strictly increase", 4, 4);
        refused("[[0,2]]", "names input dimension 2", 4, 6);
        refused("[[-1]]", "names input dimension -1", 4, 6);
    }

    @Test
    void sizesArePositiveAndOneIsAutomatic() {
        refused("[0,24]", "positive", 4, 6);
        refused("[-2,-12]", "positive", 4, 6);
        refused("[-1,-1]", "more than once", 4, 6);
        assertThrows(ZarrFormatException.class, () -> resolve("[\"4\",6]", 4, 6));
        assertThrows(ZarrFormatException.class, () -> resolve("[2.5,6]", 4, 6));
        assertThrows(ZarrFormatException.class, () -> resolve("[1e30]", 4, 6));
        assertThrows(ZarrFormatException.class, () -> resolve("[[0.5]]", 4, 6));
        assertThrows(ZarrFormatException.class, () -> resolve("{\"a\":1}", 4, 6));
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT32, new long[] {4},
                List.of(codec("{\"name\":\"reshape\",\"configuration\":{}}"), BYTES)));
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT32, new long[] {4},
                List.of(codec("{\"name\":\"reshape\"}"), BYTES)));
    }

    @Test
    void theElementCountsMustAgree() {
        refused("[5,5]", "element counts differ", 4, 6);
        refused("[25]", "element counts differ", 4, 6);
        refused("[5,-1]", "no size for -1", 4, 6);
        refused("[48,-1]", "no size for -1", 4, 6);
        refused("[2147483647,2147483647,-1]", "no size for -1", 4, 6);
        refused("[2147483647,2147483647]", "element counts differ", 4, 6);
        refused("[[0],5]", "element counts differ", 4, 6);
    }

    @Test
    void anOutputDimensionOfInputDimensionsSpansExactlyThem() {
        // [2, [1], 2] has 24 elements, but 2 output elements before dimension 1 are not the input's 4
        refused("[2,[1],2]", "does not span input dimensions [1]", 4, 6);
        refused("[[1],8]", "does not span input dimensions [1]", 2, 3, 4);
        refused("[[1,2],2]", "does not span input dimensions [1, 2]", 2, 3, 4);
        // sizes around a dimension of input dimensions are free, if the counts before and after it agree
        assertArrayEquals(new int[] {2, 12, 2}, resolve("[[0],12,2]", 2, 6, 4));
        assertArrayEquals(new int[] {3, 2, 24, 1}, resolve("[3,2,[2],1]", 2, 3, 24));
        // (with the counts agreeing, a prefix that holds makes the suffix hold: the spec's second rule follows)
    }

    @Test
    void theCodecIsResolvedForEachChunkShape() {
        List<JsonObject> codecs = List.of(reshape("[[0,1],-1]"), BYTES);
        // a rectilinear grid's chunks of 2 x 3 x 4 and 5 x 3 x 4 reshape to 6 x 4 and 15 x 4
        ChunkPipeline small = ChunkPipeline.of(DataType.INT16, new long[] {2, 3, 4}, codecs);
        ChunkPipeline large = ChunkPipeline.of(DataType.INT16, new long[] {5, 3, 4}, codecs);
        byte[] a = shorts(24);
        byte[] b = shorts(60);
        assertArrayEquals(a, small.encode(a, new byte[2])); // C order kept: the bytes are the elements
        assertArrayEquals(b, large.decode(large.encode(b, new byte[2])));
        // explicit sizes fit only some chunk shapes
        List<JsonObject> sized = List.of(reshape("[6,4]"), BYTES);
        ChunkPipeline.of(DataType.INT16, new long[] {2, 3, 4}, sized);
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT16, new long[] {5, 3, 4}, sized));
    }

    @Test
    void itComesBeforeTheArrayToBytesCodec() {
        ZarrFormatException e = assertThrows(ZarrFormatException.class,
                () -> ChunkPipeline.of(DataType.INT32, new long[] {4, 6}, List.of(BYTES, reshape("[-1]"))));
        assertTrue(e.getMessage().contains("'reshape' appears after the array->bytes codec"), e.getMessage());
    }

    @Test
    void composesWithTransposeOnEitherSide() {
        int[] values = new int[24];
        for (int i = 0; i < 24; i++) {
            values[i] = i * 10 + 1;
        }
        byte[] chunk = ints(values);
        // transpose then reshape: the stored order is the transpose's, the 6 x 4 transposed chunk flattened
        ChunkPipeline after = ChunkPipeline.of(DataType.INT32, new long[] {4, 6},
                List.of(codec("{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}}"), reshape("[[0,1]]"), BYTES));
        int[] transposed = new int[24];
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 4; c++) {
                transposed[r * 4 + c] = values[c * 6 + r];
            }
        }
        assertArrayEquals(ints(transposed), after.encode(chunk, new byte[4]));
        assertArrayEquals(chunk, after.decode(after.encode(chunk, new byte[4])));
        // reshape then transpose: 4 x 6 as 4 x 2 x 3, stored as 3 x 4 x 2
        ChunkPipeline before = ChunkPipeline.of(DataType.INT32, new long[] {4, 6},
                List.of(reshape("[[0],2,-1]"), codec("{\"name\":\"transpose\",\"configuration\":{\"order\":[2,0,1]}}"),
                        BYTES));
        int[] stored = new int[24];
        for (int k = 0; k < 3; k++) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 2; j++) {
                    stored[(k * 4 + i) * 2 + j] = values[i * 6 + j * 3 + k];
                }
            }
        }
        assertArrayEquals(ints(stored), before.encode(chunk, new byte[4]));
        assertArrayEquals(chunk, before.decode(before.encode(chunk, new byte[4])));
        // a transpose after reshape is checked against the reshaped rank
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT32, new long[] {4, 6},
                List.of(reshape("[[0,1]]"), codec("{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}}"),
                        BYTES)));
    }

    @Test
    void aShardAfterItHoldsTheReshapedChunk() {
        String shard = "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":%s,\"codecs\":[{\"name\":"
                + "\"bytes\",\"configuration\":{\"endian\":\"little\"}},{\"name\":\"numcodecs.bz2\"}],"
                + "\"index_codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},{\"name\":\"crc32c\"}]}}";
        ChunkPipeline p = ChunkPipeline.of(DataType.INT32, new long[] {4, 6},
                List.of(reshape("[[0,1]]"), codec(String.format(shard, "[8]"))));
        assertTrue(p.isSharded());
        assertNull(p.subChunkShape()); // a read decodes the whole chunk: its region is not the shard's
        assertTrue(!p.canUpdateShard());
        byte[] chunk = ints(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
            23, 24});
        assertArrayEquals(chunk, p.decode(p.encode(chunk, new byte[4])));
        byte[] region = p.decodeRegion(ChunkBytes.of(p.encode(chunk, new byte[4])), new byte[4], new int[] {1, 2},
                new int[] {2, 3});
        assertArrayEquals(ints(new int[] {9, 10, 11, 15, 16, 17}), region);
        // the shard's chunk_shape is of the reshaped rank, and must divide the reshaped chunk
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT32, new long[] {4, 6},
                List.of(reshape("[[0,1]]"), codec(String.format(shard, "[2,3]")))));
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT32, new long[] {4, 6},
                List.of(reshape("[[0,1]]"), codec(String.format(shard, "[5]")))));
    }

    @Test
    void variableLengthElementsKeepTheirOrder() {
        ChunkPipeline p = ChunkPipeline.of(DataType.STRING, new long[] {2, 3},
                List.of(reshape("[-1]"), codec("{\"name\":\"vlen-utf8\"}"), codec("{\"name\":\"numcodecs.bz2\"}")));
        String[] strings = {"a", "", "δδ", "zarr", "x", "😀"};
        byte[] stored = p.encodeStrings(strings);
        assertArrayEquals(strings, p.decodeStrings(stored, 6));
        ChunkPipeline plain = ChunkPipeline.of(DataType.STRING, new long[] {6},
                List.of(codec("{\"name\":\"vlen-utf8\"}"), codec("{\"name\":\"numcodecs.bz2\"}")));
        assertArrayEquals(plain.encodeStrings(strings), stored); // the same bytes as the flat chunk
    }

    @Test
    void anArraySpecWritesItAndReadsItBack() {
        JsonArray shape = Json.parse("[[0,1],[2]]").asArray();
        ArraySpec spec = ArraySpec.builder(new long[] {7, 6, 5}, DataType.INT32).chunkShape(4, 3, 5).reshape(shape)
                .sharding(6, 5).bz2(3).build();
        JsonObject json = spec.toJson();
        List<String> names = new ArrayList<>();
        json.get("codecs").asArray().values().forEach(c -> names.add(c.asObject().get("name").asString()));
        assertEquals(List.of("reshape", "sharding_indexed"), names);
        assertEquals("{\"name\":\"reshape\",\"configuration\":{\"shape\":[[0,1],[2]]}}",
                json.get("codecs").asArray().get(0).toJson());
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store, new JsonObject(Map.of()), true);
        ZarrArray a = root.createArray("r", spec);
        int[] values = new int[210];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 3 - 100;
        }
        a.writeInts(values);
        assertArrayEquals(values, Zarr.openArray(store, "r").readInts());
        // a box written over chunk boundaries merges with what the chunks held
        a.select(new long[] {1, 1, 1}, new long[] {5, 4, 3}).writeInts(new int[60]);
        int[] expected = values.clone();
        for (int i = 1; i < 6; i++) {
            for (int j = 1; j < 5; j++) {
                for (int k = 1; k < 4; k++) {
                    expected[(i * 6 + j) * 5 + k] = 0;
                }
            }
        }
        assertArrayEquals(expected, a.readInts());
        // the reshaped chunk of 12 x 5 does not divide into sub-chunks of 5 x 5
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {7, 6, 5}, DataType.INT32)
                .chunkShape(4, 3, 5).reshape(shape).sharding(5, 5).build());
        // nor does a sharding shape of the chunk's rank fit the reshaped one
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {7, 6, 5}, DataType.INT32)
                .chunkShape(4, 3, 5).reshape(shape).sharding(2, 3, 5).build());
    }

    @Test
    void anArraySpecChecksEveryChunkShapeOfARectilinearGrid() {
        // input_dims fit every chunk shape
        ArraySpec spec = ArraySpec.builder(new long[] {10, 8}, DataType.FLOAT64).chunkLengths(0, 2, 5, 3)
                .chunkShape(10, 4).reshape(Json.parse("[[0,1]]").asArray()).bz2(9).build();
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createGroup(store, new JsonObject(Map.of()), true).createArray("r", spec);
        double[] values = new double[80];
        for (int i = 0; i < values.length; i++) {
            values[i] = i / 4.0;
        }
        a.writeDoubles(values);
        assertArrayEquals(values, a.readDoubles());
        // a size fits the chunks of 2 rows (8 elements) but not those of 5
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {10, 8}, DataType.FLOAT64)
                .chunkLengths(0, 2, 5, 3).chunkShape(10, 4).reshape(Json.parse("[8]").asArray()).build());
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {10, 8}, DataType.FLOAT64)
                .reshape(Json.parse("[[1],[0]]").asArray()).build());
    }

    private static byte[] ints(int[] values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    private static byte[] shorts(int n) {
        ByteBuffer b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            b.putShort((short) (i * 7 - 3));
        }
        return b.array();
    }
}
