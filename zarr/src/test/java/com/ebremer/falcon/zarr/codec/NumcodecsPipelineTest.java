package com.ebremer.falcon.zarr.codec;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * The numcodecs filters and checksums inside a {@link ChunkPipeline}: where they may stand, the element
 * type each one is given, the decode bounds, and robustness against damaged chunks.
 */
class NumcodecsPipelineTest {

    private static JsonObject spec(String json) {
        return Json.parse(json).asObject();
    }

    private static ChunkPipeline pipe(DataType dataType, long[] chunkShape, String... codecs) {
        return ChunkPipeline.of(dataType, chunkShape, Arrays.stream(codecs).map(NumcodecsPipelineTest::spec).toList());
    }

    private static final String BYTES_LE = "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}";
    private static final String BYTES_BE = "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}}";
    private static final String GZIP = "{\"name\":\"gzip\",\"configuration\":{\"level\":5}}";

    private static String codec(String id, String configuration) {
        return "{\"name\":\"numcodecs." + id + "\",\"configuration\":" + configuration + "}";
    }

    private static byte[] ints(ByteOrder order, int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(order);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    private static byte[] floats(ByteOrder order, float... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(order);
        for (float v : values) {
            b.putFloat(v);
        }
        return b.array();
    }

    /** A v2 array's filters in order, then its compressor: decoding undoes them in reverse. */
    @Test
    void filtersRunInNumcodecsOrder() {
        ChunkPipeline p = pipe(DataType.INT32, new long[] {6}, BYTES_LE,
                codec("delta", "{\"dtype\":\"<i4\",\"astype\":\"<i2\"}"), codec("shuffle", "{\"elementsize\":2}"),
                codec("crc32", "{}"), GZIP);
        byte[] elements = ints(LITTLE_ENDIAN, 100, 101, 103, 106, 110, 115);
        byte[] stored = p.encode(elements, new byte[4]);

        // the same steps by hand: delta to int16, shuffle by 2, crc32 at the start, gzip
        byte[] delta = {100, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0};
        byte[] shuffled = {100, 1, 2, 3, 4, 5, 0, 0, 0, 0, 0, 0};
        assertArrayEquals(delta, Numcodecs.parse("numcodecs.delta",
                spec("{\"dtype\":\"<i4\",\"astype\":\"<i2\"}"), null).encode(elements));
        byte[] unzipped = GzipCodec.parse(spec("{}")).decode(stored, 1 << 20);
        assertArrayEquals(shuffled, Arrays.copyOfRange(unzipped, 4, unzipped.length));
        CRC32 crc = new CRC32();
        crc.update(shuffled);
        assertEquals((int) crc.getValue(), ByteBuffer.wrap(unzipped).order(LITTLE_ENDIAN).getInt()); // at the start
        assertArrayEquals(elements, p.decode(stored));
    }

    /** bitround rounds the floats the stage before gives: the array's, or what a filter turned them into. */
    @Test
    void bitroundIsGivenTheElementsBeforeIt() {
        float[] values = {1.1f, -2.2f, 3.3f, 1e-3f};
        byte[] le = floats(LITTLE_ENDIAN, values);
        ChunkPipeline direct = pipe(DataType.FLOAT32, new long[] {4}, BYTES_LE, codec("bitround", "{\"keepbits\":5}"));
        byte[] rounded = direct.encode(le, new byte[4]);
        assertArrayEquals(rounded, direct.decode(rounded)); // decoding is the identity
        float first = ByteBuffer.wrap(rounded).order(LITTLE_ENDIAN).getFloat();
        assertEquals(1.09375f, first); // 1.1 kept to 5 mantissa bits

        // float64 elements made float32 by astype: bitround works on float32
        ChunkPipeline narrowed = pipe(DataType.FLOAT64, new long[] {4}, BYTES_LE,
                codec("astype", "{\"encode_dtype\":\"<f4\",\"decode_dtype\":\"<f8\"}"),
                codec("bitround", "{\"keepbits\":5}"));
        byte[] doubles = ByteBuffer.allocate(32).order(LITTLE_ENDIAN).putDouble(1.1f).putDouble(-2.2f)
                .putDouble(3.3f).putDouble(1e-3f).array();
        assertArrayEquals(rounded, narrowed.encode(doubles, new byte[8]));

        // integers, plain bytes after shuffle, and big-endian floats cannot be bit-rounded; reading still works
        ChunkPipeline integers = pipe(DataType.INT32, new long[] {4}, BYTES_LE, codec("bitround", "{\"keepbits\":5}"));
        assertThrows(ZarrUnsupportedException.class, () -> integers.encode(ints(LITTLE_ENDIAN, 1, 2, 3, 4), new byte[4]));
        assertArrayEquals(ints(LITTLE_ENDIAN, 1, 2, 3, 4), integers.decode(ints(LITTLE_ENDIAN, 1, 2, 3, 4)));
        ChunkPipeline shuffled = pipe(DataType.FLOAT32, new long[] {4}, BYTES_LE, codec("shuffle", "{}"),
                codec("bitround", "{\"keepbits\":5}"));
        assertThrows(ZarrUnsupportedException.class, () -> shuffled.encode(le, new byte[4]));
        ChunkPipeline big = pipe(DataType.FLOAT32, new long[] {4}, BYTES_BE, codec("bitround", "{\"keepbits\":5}"));
        assertThrows(ZarrUnsupportedException.class, () -> big.encode(floats(BIG_ENDIAN, values), new byte[4]));
    }

    @Test
    void elementTypeFollowsTheStages() {
        DataType f4 = DataType.FLOAT32;
        BytesCodec le = BytesCodec.parse(spec("{\"endian\":\"little\"}"), f4);
        BytesCodec be = BytesCodec.parse(spec("{\"endian\":\"big\"}"), f4);
        assertEquals("<f4", Numcodecs.elementType(f4, le, List.of()).toString());
        assertEquals(">f4", Numcodecs.elementType(f4, be, List.of()).toString());
        assertEquals("|b1", Numcodecs.elementType(DataType.BOOL, le, List.of()).toString());
        assertEquals(null, Numcodecs.elementType(DataType.COMPLEX64, le, List.of()));
        BytesBytesCodec delta = Numcodecs.parse("numcodecs.delta", spec("{\"dtype\":\"<f4\",\"astype\":\"<i2\"}"), null);
        assertEquals("<i2", Numcodecs.elementType(f4, le, List.of(delta)).toString());
        BytesBytesCodec crc = Numcodecs.parse("numcodecs.crc32", spec("{}"), null);
        assertEquals("|u1", Numcodecs.elementType(f4, le, List.of(delta, crc)).toString());
        assertEquals("|u1", Numcodecs.elementType(f4, le, List.of(new Crc32cCodec())).toString());
    }

    /** zarr-python 3 writes the element filters as array->array codecs in Zarr v3; Falcon runs them only after bytes. */
    @Test
    void placementBeforeTheArrayBytesCodec() {
        for (String id : List.of("delta", "fixedscaleoffset", "quantize", "bitround", "astype", "packbits")) {
            ZarrUnsupportedException e = assertThrows(ZarrUnsupportedException.class, () -> pipe(DataType.INT32,
                    new long[] {4}, codec(id, "{\"dtype\":\"<i4\"}"), BYTES_LE));
            assertTrue(e.getMessage().contains("array->array"), e.getMessage());
        }
        for (String id : List.of("shuffle", "crc32", "crc32c", "adler32", "fletcher32", "jenkins_lookup3")) {
            assertThrows(ZarrFormatException.class, () -> pipe(DataType.INT32, new long[] {4}, codec(id, "{}"),
                    BYTES_LE));
        }
        ZarrUnsupportedException unknown = assertThrows(ZarrUnsupportedException.class, () -> pipe(DataType.INT32,
                new long[] {4}, BYTES_LE, codec("categorize", "{\"labels\":[],\"dtype\":\"|O\"}")));
        assertTrue(unknown.getMessage().contains("unknown codec"), unknown.getMessage());
    }

    /** The byte codecs work in a Zarr v3 array, as zarr-python 3 writes them, and in a shard index. */
    @Test
    void byteCodecsInZarrV3() {
        ChunkPipeline p = pipe(DataType.INT32, new long[] {4}, BYTES_LE, codec("shuffle", "{\"elementsize\":4}"),
                codec("crc32", "{\"location\":\"end\"}"), codec("adler32", "{}"), codec("fletcher32", "{}"),
                codec("jenkins_lookup3", "{\"initval\":7}"), codec("crc32c", "{}"));
        byte[] elements = ints(LITTLE_ENDIAN, -7, 993, 1993, 2993);
        byte[] stored = p.encode(elements, new byte[4]);
        assertEquals(16 + 5 * 4, stored.length);
        assertArrayEquals(elements, p.decode(stored));

        ChunkPipeline sharded = pipe(DataType.INT32, new long[] {4, 4}, "{\"name\":\"sharding_indexed\","
                + "\"configuration\":{\"chunk_shape\":[2,2],\"codecs\":[" + BYTES_LE + "],\"index_codecs\":["
                + BYTES_LE + "," + codec("crc32", "{\"location\":\"end\"}") + "]}}");
        byte[] all = new byte[64];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        assertArrayEquals(all, sharded.decode(sharded.encode(all, new byte[4])));
    }

    @Test
    void checksumMismatchIsAFormatError() {
        for (String id : List.of("crc32", "crc32c", "adler32", "fletcher32", "jenkins_lookup3")) {
            ChunkPipeline p = pipe(DataType.UINT8, new long[] {8}, BYTES_LE, codec(id, "{}"));
            byte[] stored = p.encode(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, new byte[1]);
            stored[5] ^= 1;
            ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> p.decode(stored));
            assertTrue(e.getMessage().contains("mismatch"), e.getMessage());
        }
    }

    /** A checksum after a variable-length codec, whose chunk has no size bound but a Java array's. */
    @Test
    void checksumAfterStrings() {
        ChunkPipeline p = pipe(DataType.STRING, new long[] {3}, "{\"name\":\"vlen-utf8\"}",
                codec("crc32", "{}"), codec("delta", "{\"dtype\":\"|u1\"}"), GZIP);
        String[] values = {"a", "", "zarr"};
        assertArrayEquals(values, p.decodeStrings(p.encodeStrings(values), 3));
    }

    /** H1: a stage cannot decode to more than the chunk it belongs to allows. */
    @Test
    void decodingIsBoundedByTheChunk() {
        ChunkPipeline bits = pipe(DataType.BOOL, new long[] {8}, BYTES_LE, codec("packbits", "{}"));
        byte[] claim = new byte[1 + 1000]; // 8000 bools for an 8-element chunk
        ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> bits.decode(claim));
        assertTrue(e.getMessage().contains("more than"), e.getMessage());

        ChunkPipeline widened = pipe(DataType.FLOAT64, new long[] {4}, BYTES_LE,
                codec("astype", "{\"encode_dtype\":\"|i1\",\"decode_dtype\":\"<f8\"}"));
        assertThrows(ZarrFormatException.class, () -> widened.decode(new byte[100])); // 100 doubles, not 4
        assertArrayEquals(ByteBuffer.allocate(32).order(LITTLE_ENDIAN).putDouble(1).putDouble(-2).putDouble(3)
                .putDouble(127).array(), widened.decode(new byte[] {1, -2, 3, 127}));
    }

    @Test
    void malformedAndUnsupportedConfigurations() {
        assertThrows(ZarrFormatException.class, () -> Numcodecs.parse("numcodecs.delta", spec("{}"), null));
        assertThrows(ZarrUnsupportedException.class,
                () -> Numcodecs.parse("numcodecs.delta", spec("{\"dtype\":\"<c8\"}"), null));
        assertThrows(ZarrUnsupportedException.class,
                () -> Numcodecs.parse("numcodecs.delta", spec("{\"dtype\":\"|b1\"}"), null));
        assertThrows(ZarrUnsupportedException.class,
                () -> Numcodecs.parse("numcodecs.astype", spec("{\"encode_dtype\":\"<U4\",\"decode_dtype\":\"<i4\"}"), null));
        assertThrows(ZarrUnsupportedException.class,
                () -> Numcodecs.parse("numcodecs.delta", spec("{\"dtype\":\"|i4\"}"), null)); // '|' only for a byte
        assertThrows(ZarrFormatException.class,
                () -> Numcodecs.parse("numcodecs.crc32", spec("{\"location\":\"middle\"}"), null));
        assertThrows(ZarrFormatException.class,
                () -> Numcodecs.parse("numcodecs.quantize", spec("{\"digits\":2,\"dtype\":\"<i4\"}"), null));
        assertThrows(ZarrFormatException.class,
                () -> Numcodecs.parse("numcodecs.bitround", spec("{\"keepbits\":-1}"), null));
        assertThrows(ZarrFormatException.class, () -> Numcodecs.parse("numcodecs.bitround", spec("{}"), null));
        assertThrows(ZarrUnsupportedException.class,
                () -> Numcodecs.parse("numcodecs.jenkins_lookup3", spec("{\"prefix\":[1,2]}"), null));
        assertThrows(ZarrFormatException.class,
                () -> Numcodecs.parse("numcodecs.fixedscaleoffset", spec("{\"scale\":1,\"dtype\":\"<f8\"}"), null));
        // shuffle defaults to numcodecs' 4-byte elements
        byte[] shuffled = Numcodecs.parse("numcodecs.shuffle", spec("{}"), null).encode(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertArrayEquals(new byte[] {1, 5, 2, 6, 3, 7, 4, 8}, shuffled);
    }

    /** The fixed-size stages state their encoded size exactly (a shard index needs it). */
    @Test
    void encodedSizes() {
        assertEquals(12, Numcodecs.parse("numcodecs.crc32", spec("{}"), null).encodedSize(8));
        assertEquals(2, Numcodecs.parse("numcodecs.packbits", spec("{}"), null).encodedSize(8));
        assertEquals(3, Numcodecs.parse("numcodecs.packbits", spec("{}"), null).encodedSize(9));
        assertEquals(1, Numcodecs.parse("numcodecs.packbits", spec("{}"), null).encodedSize(0));
        assertEquals(6, Numcodecs.parse("numcodecs.delta", spec("{\"dtype\":\"<i4\",\"astype\":\"<i2\"}"), null)
                .encodedSize(12));
        assertEquals(24, Numcodecs.parse("numcodecs.astype",
                spec("{\"encode_dtype\":\"<f8\",\"decode_dtype\":\"<i2\"}"), null).encodedSize(6));
    }

    /** Damaged chunks fail as ZarrExceptions, never anything else, and never decode past their bound. */
    @Test
    void damagedChunksFailCleanly() {
        String[] configs = {
            codec("delta", "{\"dtype\":\"<i4\",\"astype\":\"<i2\"}"),
            codec("fixedscaleoffset", "{\"offset\":1000,\"scale\":10,\"dtype\":\"<f8\",\"astype\":\"|u1\"}"),
            codec("quantize", "{\"digits\":2,\"dtype\":\"<f8\",\"astype\":\"<f4\"}"),
            codec("bitround", "{\"keepbits\":3}"),
            codec("astype", "{\"encode_dtype\":\"<f2\",\"decode_dtype\":\"<f8\"}"),
            codec("shuffle", "{\"elementsize\":8}"),
            codec("crc32", "{}"), codec("crc32c", "{}"), codec("adler32", "{\"location\":\"end\"}"),
            codec("fletcher32", "{}"), codec("jenkins_lookup3", "{\"initval\":3}")};
        Random random = new Random(20261006);
        byte[] elements = new byte[64];
        for (int i = 0; i < 8; i++) {
            ByteBuffer.wrap(elements).order(LITTLE_ENDIAN).putDouble(i * 8, 1000 + i * 0.37);
        }
        for (String config : configs) {
            ChunkPipeline p = pipe(DataType.FLOAT64, new long[] {8}, BYTES_LE, config, GZIP);
            byte[] stored;
            try {
                stored = p.encode(elements, new byte[8]);
            } catch (ZarrException e) {
                stored = ChunkPipeline.of(DataType.UINT8, new long[] {64}, List.of(spec(BYTES_LE), spec(GZIP)))
                        .encode(elements, new byte[1]);
            }
            ChunkPipeline bare = pipe(DataType.FLOAT64, new long[] {8}, BYTES_LE, config);
            byte[] inner = ChunkPipeline.of(DataType.UINT8, new long[] {64}, List.of(spec(BYTES_LE))).encode(elements,
                    new byte[1]);
            for (int trial = 0; trial < 300; trial++) {
                byte[] damaged = damage(random, trial % 2 == 0 ? stored : inner);
                ChunkPipeline target = trial % 2 == 0 ? p : bare;
                try {
                    byte[] out = target.decode(damaged);
                    assertEquals(64, out.length);
                } catch (ZarrException expected) {
                    // malformed input is reported, not thrown as something else
                } catch (RuntimeException | OutOfMemoryError e) {
                    fail(config + " trial " + trial + ": " + e);
                }
            }
        }
    }

    private static byte[] damage(Random random, byte[] data) {
        byte[] out;
        switch (random.nextInt(4)) {
            case 0 -> out = Arrays.copyOf(data, random.nextInt(data.length + 1)); // truncated
            case 1 -> {
                out = Arrays.copyOf(data, data.length + 1 + random.nextInt(200)); // grown
                for (int i = data.length; i < out.length; i++) {
                    out[i] = (byte) random.nextInt();
                }
            }
            default -> {
                out = data.clone();
                for (int flips = 1 + random.nextInt(4); flips > 0 && out.length > 0; flips--) {
                    out[random.nextInt(out.length)] ^= (byte) (1 << random.nextInt(8));
                }
            }
        }
        return out;
    }
}
