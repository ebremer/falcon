package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@code cast_value} codec's configuration, checked as zarr-python checks it (unknown keys, the data types,
 * the modes, the scalar_map's entries), and its place in the pipeline: the codecs after it see the type it casts
 * to, the fill value is cast for them, and a fill value that cannot be cast both ways is refused.
 */
class CastValueCodecTest {

    private static final String BYTES = "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}";

    private static ChunkPipeline pipeline(DataType type, int n, String... codecs) {
        List<JsonObject> specs = new ArrayList<>();
        for (String c : codecs) {
            specs.add(Json.parse(c).asObject());
        }
        return ChunkPipeline.of(type, new long[] {n}, specs);
    }

    private static String cast(String configuration) {
        return "{\"name\":\"cast_value\",\"configuration\":" + configuration + "}";
    }

    private static ChunkPipeline cast(DataType type, String configuration) {
        return pipeline(type, 4, cast(configuration), BYTES);
    }

    private static byte[] doubles(double... values) {
        ByteBuffer b = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            b.putDouble(v);
        }
        return b.array();
    }

    private static double[] toDoubles(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] out = new double[bytes.length / 8];
        for (int i = 0; i < out.length; i++) {
            out[i] = b.getDouble(8 * i);
        }
        return out;
    }

    // ---- configuration ------------------------------------------------------------------------------

    @Test
    void refusesUnknownKeysAsTheSpecificationRequires() {
        ZarrFormatException e = assertThrows(ZarrFormatException.class,
                () -> cast(DataType.FLOAT64, "{\"data_type\":\"uint8\",\"extra\":1}"));
        assertTrue(e.getMessage().contains("extra"), e.getMessage());
        assertThrows(ZarrFormatException.class, () -> cast(DataType.FLOAT64, "{}"));
        assertThrows(ZarrFormatException.class,
                () -> pipeline(DataType.FLOAT64, 4, "{\"name\":\"cast_value\"}", BYTES));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"bool\"", "\"complex64\"", "\"string\"", "\"r16\"", "\"variable_length_bytes\"",
        "{\"name\":\"numpy.datetime64\",\"configuration\":{\"unit\":\"s\",\"scale_factor\":1}}"})
    void castsOnlyToIntegersAndFloats(String dataType) {
        assertThrows(ZarrFormatException.class, () -> cast(DataType.FLOAT64, "{\"data_type\":" + dataType + "}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bfloat16", "int4", "uint2", "float8_e4m3", "float4_e2m1fn", "float8_e8m0fnu"})
    void refusesByNameTheSpecificationsTypesFalconLacks(String name) {
        for (String json : new String[] {"\"" + name + "\"", "{\"name\":\"" + name + "\"}"}) {
            ZarrUnsupportedException e = assertThrows(ZarrUnsupportedException.class,
                    () -> cast(DataType.FLOAT32, "{\"data_type\":" + json + "}"));
            assertTrue(e.getMessage().contains(name), e.getMessage());
        }
        assertThrows(ZarrUnsupportedException.class, () -> cast(DataType.FLOAT32, "{\"data_type\":\"nonesuch\"}"));
    }

    @Test
    void readsTheObjectFormOfTheDataType() {
        ChunkPipeline p = cast(DataType.FLOAT64, "{\"data_type\":{\"name\":\"int16\"}}");
        assertEquals(8, p.encode(doubles(1, 2, 3, 4), new byte[8]).length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"complex64", "bool", "r16"})
    void castsOnlyIntegerAndFloatElements(String source) {
        DataType type = DataType.of(source);
        assertThrows(ZarrFormatException.class, () -> cast(type, "{\"data_type\":\"float32\"}"));
    }

    @Test
    void refusesVariableLengthElements() {
        assertThrows(ZarrFormatException.class, () -> pipeline(DataType.STRING, 4,
                cast("{\"data_type\":\"uint8\"}"), "{\"name\":\"vlen-utf8\"}"));
    }

    @Test
    void checksTheModes() {
        for (String rounding : new String[] {"nearest-even", "towards-zero", "towards-positive", "towards-negative",
            "nearest-away"}) {
            cast(DataType.FLOAT64, "{\"data_type\":\"uint8\",\"rounding\":\"" + rounding + "\"}");
        }
        for (String bad : new String[] {"\"bogus\"", "null", "1", "\"NEAREST-EVEN\""}) {
            assertThrows(ZarrFormatException.class,
                    () -> cast(DataType.FLOAT64, "{\"data_type\":\"uint8\",\"rounding\":" + bad + "}"));
            assertThrows(ZarrFormatException.class,
                    () -> cast(DataType.FLOAT64, "{\"data_type\":\"uint8\",\"out_of_range\":" + (bad.equals("null")
                            ? "\"none\"" : bad) + "}"));
        }
        cast(DataType.FLOAT64, "{\"data_type\":\"uint8\",\"out_of_range\":null}"); // null is absent, as in zarr-python
        cast(DataType.FLOAT64, "{\"data_type\":\"uint8\",\"out_of_range\":\"wrap\"}");
        cast(DataType.FLOAT64, "{\"data_type\":\"float32\",\"out_of_range\":\"clamp\"}");
        ZarrFormatException e = assertThrows(ZarrFormatException.class,
                () -> cast(DataType.INT32, "{\"data_type\":\"float32\",\"out_of_range\":\"wrap\"}"));
        assertTrue(e.getMessage().contains("wrap"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[]", "\"x\"", "{\"encode\":{\"1\":3}}", "{\"encode\":[[1,2,3]]}", "{\"encode\":[1]}",
        "{\"encode\":[[1.5,3]]}", "{\"encode\":[[\"NaN\",3]]}", "{\"encode\":[[3e9,3]]}", "{\"encode\":[[1,300]]}",
        "{\"encode\":[[1,-1]]}", "{\"decode\":[[256,1]]}", "{\"decode\":[[1,\"NaN\"]]}", "{\"encode\":[[1,true]]}"})
    void checksTheScalarMapsEntries(String map) {
        // int32 elements cast to uint8: encode keys are int32, values uint8; decode the other way round
        assertThrows(ZarrFormatException.class,
                () -> cast(DataType.INT32, "{\"data_type\":\"uint8\",\"scalar_map\":" + map + "}"));
    }

    @Test
    void readsTheScalarMapAsZarrPythonDoesWhereItIsLoose() {
        // null sides and a null map are absent; members other than encode and decode are ignored
        cast(DataType.INT32, "{\"data_type\":\"uint8\",\"scalar_map\":null}");
        cast(DataType.INT32, "{\"data_type\":\"uint8\",\"scalar_map\":{\"encode\":null,\"decode\":[[1,2]]}}");
        cast(DataType.INT32, "{\"data_type\":\"uint8\",\"scalar_map\":{\"comment\":\"x\"}}");
        // an integer written as a float with no fraction is an integer, as in zarr-python
        ChunkPipeline p = cast(DataType.INT32, "{\"data_type\":\"uint8\",\"scalar_map\":{\"encode\":[[1.0,3.0]]}}");
        assertArrayEquals(new byte[] {3, 2, 3, 4}, p.encode(ints(1, 2, 3, 4), new byte[4]));
    }

    private static byte[] ints(int... values) {
        ByteBuffer b = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    @Test
    void theFirstOfARepeatedKeyWins() {
        // the specification's rule; zarr-python's dict keeps the last
        ChunkPipeline p = cast(DataType.INT32, "{\"data_type\":\"uint8\",\"scalar_map\":{\"encode\":[[1,10],[1,20]]}}");
        assertArrayEquals(new byte[] {10, 2, 3, 4}, p.encode(ints(1, 2, 3, 4), new byte[4]));
        // 0.0 and -0.0 are one value: the first entry takes both
        p = cast(DataType.FLOAT64, "{\"data_type\":\"uint8\",\"scalar_map\":{\"encode\":[[-0.0,7],[0.0,9]]}}");
        assertArrayEquals(new byte[] {7, 7, 1, 2}, p.encode(doubles(0.0, -0.0, 1, 2), new byte[8]));
    }

    @Test
    void aNanKeyMatchesEveryNanAndHexStringsGiveExactBits() {
        double payload = Double.longBitsToDouble(0xfff4000000000001L);
        ChunkPipeline p = cast(DataType.FLOAT64,
                "{\"data_type\":\"uint8\",\"scalar_map\":{\"encode\":[[\"0x7ff8000000000001\",5],[\"Infinity\",6]]}}");
        assertArrayEquals(new byte[] {5, 5, 6, 1}, p.encode(doubles(Double.NaN, payload, Double.POSITIVE_INFINITY, 1),
                new byte[8]));
        // a decode value given in hex keeps its payload (zarr-python cannot read a hex float here at all)
        p = cast(DataType.FLOAT32, "{\"data_type\":\"uint8\",\"scalar_map\":{\"decode\":[[0,\"0x7fc00123\"]]}}");
        byte[] decoded = p.decode(new byte[] {0, 1, 2, 3});
        assertEquals(0x7fc00123, ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
    }

    // ---- the pipeline -------------------------------------------------------------------------------

    @Test
    void theCodecsAfterACastSeeItsType() {
        ChunkPipeline p = cast(DataType.FLOAT64, "{\"data_type\":\"uint8\",\"out_of_range\":\"clamp\"}");
        byte[] stored = p.encode(doubles(-3, 2.5, 3.5, 300), new byte[8]);
        assertArrayEquals(new byte[] {0, 2, 4, (byte) 255}, stored); // ties to even, clamped
        assertArrayEquals(new double[] {0, 2, 4, 255}, toDoubles(p.decode(stored)));
        assertThrows(ZarrFormatException.class, () -> p.decode(new byte[] {1, 2, 3})); // a uint8 chunk is 4 bytes

        // big-endian: the array's elements and the stored int16 are both big-endian
        ChunkPipeline big = pipeline(DataType.FLOAT32, 2, cast("{\"data_type\":\"int16\"}"),
                "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}}");
        byte[] in = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putFloat(258f).putFloat(-2f).array();
        assertArrayEquals(new byte[] {1, 2, (byte) 0xff, (byte) 0xfe}, big.encode(in, new byte[4]));
        assertArrayEquals(in, big.decode(new byte[] {1, 2, (byte) 0xff, (byte) 0xfe}));

        // Blosc's type size defaults to the cast type's: 2, not float64's 8
        ChunkPipeline blosc = pipeline(DataType.FLOAT64, 64, cast("{\"data_type\":\"uint16\"}"), BYTES,
                "{\"name\":\"blosc\",\"configuration\":{\"cname\":\"lz4\",\"clevel\":5,\"shuffle\":\"shuffle\"}}");
        double[] values = new double[64];
        for (int i = 0; i < 64; i++) {
            values[i] = i * 1000;
        }
        byte[] buffer = blosc.encode(doubles(values), new byte[8]);
        assertEquals(2, buffer[3], "blosc type size");
        assertArrayEquals(values, toDoubles(blosc.decode(buffer)));
    }

    @Test
    void castsTwiceInARow() {
        ChunkPipeline p = pipeline(DataType.FLOAT64, 4, cast("{\"data_type\":\"float32\"}"),
                cast("{\"data_type\":\"int8\",\"rounding\":\"towards-zero\",\"out_of_range\":\"clamp\"}"), BYTES);
        byte[] stored = p.encode(doubles(-1.9, 1.9, 1e10, Double.NEGATIVE_INFINITY), new byte[8]);
        assertArrayEquals(new byte[] {-1, 1, 127, -128}, stored);
    }

    @Test
    void aShardBehindACastFillsWithTheCastFillValue() {
        // fill 3.7 casts to 4: a sub-chunk of values that cast to 4 is left out of the shard, and reads as 4.0
        ChunkPipeline p = pipeline(DataType.FLOAT64, 6, cast("{\"data_type\":\"int8\"}"),
                "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[3],\"codecs\":[" + BYTES + "]}}");
        byte[] fill = doubles(3.7);
        byte[] shard = p.encode(doubles(1, 2, 3, 4.2, 3.9, 4.4), fill);
        assertEquals(3 + 2 * 16 + 4, shard.length, "one sub-chunk of three int8s, and the index");
        byte[] decoded = p.decodeChunk(ChunkBytes.of(shard), fill, new int[] {0}, new int[] {6});
        assertArrayEquals(new double[] {1, 2, 3, 4, 4, 4}, toDoubles(decoded));
        // a region read decodes only the sub-chunks it needs, through the cast
        assertArrayEquals(new double[] {3, 4}, toDoubles(p.decodeRegion(ChunkBytes.of(shard), fill, new int[] {2},
                new int[] {2})));
        assertArrayEquals(new int[] {3}, p.subChunkShape());
        p.checkFillValue(fill);
    }

    @Test
    void aTransposeBeforeACastStillDecodesTheWholeChunk() {
        ChunkPipeline p = ChunkPipeline.of(DataType.FLOAT64, new long[] {2, 2}, List.of(
                Json.parse("{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}}").asObject(),
                Json.parse(cast("{\"data_type\":\"int16\"}")).asObject(),
                Json.parse("{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[1,2],\"codecs\":["
                        + BYTES + "]}}").asObject()));
        assertNull(p.subChunkShape());
        byte[] stored = p.encode(doubles(1, 2, 3, 4), new byte[8]);
        assertArrayEquals(new double[] {1, 2, 3, 4}, toDoubles(p.decode(stored)));
    }

    @Test
    void aValueThatCannotBeCastFailsWithAFormatError() {
        ChunkPipeline p = cast(DataType.FLOAT64, "{\"data_type\":\"uint8\"}");
        ZarrFormatException nan = assertThrows(ZarrFormatException.class,
                () -> p.encode(doubles(1, Double.NaN, 2, 3), new byte[8]));
        assertTrue(nan.getMessage().contains("NaN") && nan.getMessage().contains("uint8"), nan.getMessage());
        ZarrFormatException range = assertThrows(ZarrFormatException.class,
                () -> p.encode(doubles(1, 256, 2, 3), new byte[8]));
        assertTrue(range.getMessage().contains("out of range") && range.getMessage().contains("[0, 255]"),
                range.getMessage());
        // decoding too: a stored float32 beyond uint8 cannot become an element of the array
        ChunkPipeline back = cast(DataType.UINT8, "{\"data_type\":\"float32\"}");
        byte[] stored = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(1).putFloat(300).array();
        assertThrows(ZarrFormatException.class, () -> back.decode(stored));
        // a float overflowing a narrower float is an error without clamp, infinity with it
        ChunkPipeline f32 = cast(DataType.FLOAT64, "{\"data_type\":\"float32\"}");
        assertThrows(ZarrFormatException.class, () -> f32.encode(doubles(1e300, 0, 0, 0), new byte[8]));
        ChunkPipeline clamp = cast(DataType.FLOAT64, "{\"data_type\":\"float32\",\"out_of_range\":\"clamp\"}");
        assertEquals(Float.NEGATIVE_INFINITY, ByteBuffer.wrap(clamp.encode(doubles(-1e300, 0, 0, 0), new byte[8]))
                .order(ByteOrder.LITTLE_ENDIAN).getFloat(0));
    }

    @Test
    void theFillValueMustSurviveTheCastBothWays() {
        // forward: 300 is no uint8, and nothing says what to do with it
        ChunkPipeline p = cast(DataType.FLOAT64, "{\"data_type\":\"uint8\"}");
        p.checkFillValue(doubles(255));
        ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> p.checkFillValue(doubles(300)));
        assertTrue(e.getMessage().contains("fill value"), e.getMessage());
        assertThrows(ZarrFormatException.class, () -> p.checkFillValue(doubles(Double.NaN)));
        // back: the scalar_map sends 5 to 1e10, which is no int8 (zarr-python, which casts only forward, opens it)
        ChunkPipeline back = cast(DataType.INT8, "{\"data_type\":\"float32\",\"scalar_map\":{\"encode\":[[5,1e10]]}}");
        back.checkFillValue(new byte[] {4});
        assertThrows(ZarrFormatException.class, () -> back.checkFillValue(new byte[] {5}));
        // inside a shard: the sub-chunks' pipeline is checked with the fill value it is given
        ChunkPipeline sharded = pipeline(DataType.FLOAT64, 4,
                "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[2],\"codecs\":["
                        + cast("{\"data_type\":\"uint8\"}") + "," + BYTES + "]}}");
        sharded.checkFillValue(doubles(7));
        assertThrows(ZarrFormatException.class, () -> sharded.checkFillValue(doubles(-1)));
        // a value the cast changes is no failure: 3.7 survives as 4
        cast(DataType.FLOAT64, "{\"data_type\":\"int8\"}").checkFillValue(doubles(3.7));
    }

    @Test
    void castValueBelongsBeforeTheArrayToBytesCodec() {
        assertThrows(ZarrFormatException.class,
                () -> pipeline(DataType.FLOAT64, 4, BYTES, cast("{\"data_type\":\"uint8\"}")));
    }

    @Test
    void aCastThatGrowsAChunkPastOneBufferIsRefused() {
        // 300 million int8 elements fit a buffer; as float64 they would take 2.4 GB
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT8, new long[] {300_000_000},
                List.of(Json.parse(cast("{\"data_type\":\"float64\"}")).asObject(), Json.parse(BYTES).asObject())));
    }
}
