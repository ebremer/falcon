package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.blosc.BloscEncoder;
import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Writing honours a codec's configuration (P1 I9). Falcon ignored it: zstd frames never carried the
 * checksum a {@code "checksum": true} array asks for, and Blosc always byte-shuffled with automatic blocks,
 * whatever {@code shuffle}, {@code typesize}, {@code blocksize}, or {@code clevel} said. The output was
 * still readable, but not what the metadata describes. The zstd {@code level}, and the zstd level Blosc's
 * {@code clevel} implies, took effect with P2 F12, when the encoder gained levels; Blosc's {@code cname} with
 * P2 F3, when Falcon gained the other internal compressors.
 */
class CodecConfigurationTest {

    private static final JsonObject BYTES = Json.parse("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}").asObject();

    private static ChunkPipeline pipeline(DataType type, int n, String codec) {
        return ChunkPipeline.of(type, new long[] {n}, List.of(BYTES, Json.parse(codec).asObject()));
    }

    /** 8192 float64 values, smooth enough for any shuffle to compress. */
    private static byte[] doubles() {
        ByteBuffer b = ByteBuffer.allocate(8192 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 8192; i++) {
            b.putDouble(i * 0.25);
        }
        return b.array();
    }

    @Test
    void zstdWritesTheChecksumItIsConfiguredFor() {
        byte[] data = doubles();
        for (boolean checksum : new boolean[] {true, false}) {
            ChunkPipeline p = pipeline(DataType.FLOAT64, 8192,
                    "{\"name\":\"zstd\",\"configuration\":{\"level\":3,\"checksum\":" + checksum + "}}");
            byte[] frame = p.encode(data, new byte[8]);
            assertEquals(checksum ? 1 : 0, (frame[4] >> 2) & 1, "frame header's checksum flag");
            assertArrayEquals(data, p.decode(frame));
        }
    }

    private static byte[] blosc(String shuffle, int typesize, int blocksize, int clevel) {
        ChunkPipeline p = pipeline(DataType.FLOAT64, 8192, "{\"name\":\"blosc\",\"configuration\":{\"cname\":\"zstd\","
                + "\"clevel\":" + clevel + ",\"shuffle\":\"" + shuffle + "\",\"typesize\":" + typesize
                + ",\"blocksize\":" + blocksize + "}}");
        byte[] stored = p.encode(doubles(), new byte[8]);
        assertArrayEquals(doubles(), p.decode(stored));
        return stored;
    }

    @Test
    void bloscWritesTheShuffleTypeSizeAndBlockSizeItIsConfiguredFor() {
        byte[] none = blosc("noshuffle", 8, 0, 5);
        assertEquals(0, none[2] & 0x05, "no shuffle flag");
        byte[] bytes = blosc("shuffle", 8, 0, 5);
        assertEquals(0x01, bytes[2] & 0x05, "byte shuffle flag");
        byte[] bits = blosc("bitshuffle", 8, 0, 5);
        assertEquals(0x04, bits[2] & 0x05, "bit shuffle flag");

        assertEquals(4, blosc("shuffle", 4, 0, 5)[3], "type size");
        byte[] blocks = blosc("shuffle", 8, 16384, 5);
        assertEquals(16384, ByteBuffer.wrap(blocks).order(ByteOrder.LITTLE_ENDIAN).getInt(8), "block size");
        assertEquals(0x02, blosc("shuffle", 8, 0, 0)[2] & 0x02, "clevel 0 stores the data as it is");
    }

    /**
     * The {@code cname} names the compressor inside Blosc (F3): zstd was written whatever it said. Each buffer
     * is now the one c-blosc writes (Falcon Core's BloscEncodeVectorsTest checks that byte for byte); here, the
     * header's compressor format and the pipeline's round trip.
     */
    @Test
    void bloscWritesTheCompressorItsCnameNames() {
        String[] cnames = {"blosclz", "lz4", "lz4hc", "snappy", "zlib", "zstd"};
        int[] formats = {0, 1, 1, 2, 3, 4};
        for (int c = 0; c < cnames.length; c++) {
            ChunkPipeline p = pipeline(DataType.FLOAT64, 8192, "{\"name\":\"blosc\",\"configuration\":{\"cname\":\""
                    + cnames[c] + "\",\"clevel\":5,\"shuffle\":\"shuffle\",\"typesize\":8,\"blocksize\":0}}");
            byte[] stored = p.encode(doubles(), new byte[8]);
            assertEquals(formats[c], (stored[2] & 0xff) >>> 5, cnames[c]);
            assertEquals(0, stored[2] & 0x02, cnames[c] + " compresses these doubles");
            assertArrayEquals(BloscEncoder.compress(doubles(), 8, BloscEncoder.SHUFFLE, 0, 5, c), stored, cnames[c]);
            assertArrayEquals(doubles(), p.decode(stored), cnames[c]);
        }
    }

    @Test
    void aBloscConfigurationWithoutFieldsUsesTheDefaults() {
        // v2 metadata records no Blosc configuration: byte shuffle by element size, automatic blocks.
        ChunkPipeline p = pipeline(DataType.FLOAT64, 8192, "{\"name\":\"blosc\"}");
        byte[] stored = p.encode(doubles(), new byte[8]);
        assertEquals(0x01, stored[2] & 0x05);
        assertEquals(8, stored[3]);
        assertEquals(4, (stored[2] & 0xff) >>> 5, "zstd, zarr-python's default cname");
        assertArrayEquals(doubles(), p.decode(stored));
    }

    @Test
    void invalidConfigurationsAreRefused() {
        for (String config : new String[] {
            "{\"cname\":\"brotli\"}", "{\"clevel\":12}", "{\"shuffle\":\"sideways\"}", "{\"typesize\":0}",
            "{\"blocksize\":-1}", "{\"shuffle\":1}"}) {
            assertThrows(ZarrFormatException.class,
                    () -> pipeline(DataType.INT32, 16, "{\"name\":\"blosc\",\"configuration\":" + config + "}"), config);
        }
        assertThrows(ZarrFormatException.class,
                () -> pipeline(DataType.INT32, 16, "{\"name\":\"zstd\",\"configuration\":{\"checksum\":\"yes\"}}"));
        assertThrows(ZarrFormatException.class,
                () -> pipeline(DataType.INT32, 16, "{\"name\":\"zstd\",\"configuration\":{\"level\":1.5}}"));
    }

    /** Text-like bytes, which every level compresses, and higher levels further. */
    private static byte[] words() {
        Random random = new Random(9);
        StringBuilder sb = new StringBuilder();
        String[] w = {"zarr", "chunk", "shard", "float", "array", "index", "codec", "level", "the", "of"};
        while (sb.length() < 300_000) {
            sb.append(w[random.nextInt(w.length)]).append(random.nextInt(50)).append(' ');
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static ChunkPipeline zstd(String configuration, int n) {
        return ChunkPipeline.of(DataType.UINT8, new long[] {n},
                List.of(Json.parse("{\"name\":\"bytes\"}").asObject(),
                        Json.parse("{\"name\":\"zstd\",\"configuration\":" + configuration + "}").asObject()));
    }

    /** The zstd {@code level} takes effect (F12): it had none, since the encoder had one level. */
    @Test
    void zstdWritesAtItsConfiguredLevel() {
        byte[] data = words();
        byte[] one = zstd("{\"level\":1,\"checksum\":false}", data.length).encode(data, new byte[1]);
        byte[] nineteen = zstd("{\"level\":19,\"checksum\":false}", data.length).encode(data, new byte[1]);
        assertTrue(nineteen.length < one.length, nineteen.length + " at level 19, " + one.length + " at level 1");
        assertArrayEquals(ZstdEncoder.compress(data, 19, false), nineteen);
        // Level 0, or none, is the default, as for libzstd.
        byte[] defaulted = zstd("{\"checksum\":false}", data.length).encode(data, new byte[1]);
        assertArrayEquals(ZstdEncoder.compress(data, ZstdEncoder.DEFAULT_LEVEL, false), defaulted);
        assertArrayEquals(defaulted, zstd("{\"level\":0,\"checksum\":false}", data.length).encode(data, new byte[1]));
        for (byte[] frame : new byte[][] {one, nineteen, defaulted}) {
            assertArrayEquals(data, zstd("{}", data.length).decode(frame));
        }
    }

    /** Blosc's {@code clevel} picks the zstd level inside it as c-blosc does (F12). */
    @Test
    void bloscClevelSetsTheZstdLevel() {
        byte[] data = words();
        ChunkPipeline low = ChunkPipeline.of(DataType.UINT8, new long[] {data.length}, List.of(
                Json.parse("{\"name\":\"bytes\"}").asObject(),
                Json.parse("{\"name\":\"blosc\",\"configuration\":{\"cname\":\"zstd\",\"clevel\":1,"
                        + "\"shuffle\":\"noshuffle\",\"typesize\":1,\"blocksize\":0}}").asObject()));
        ChunkPipeline high = ChunkPipeline.of(DataType.UINT8, new long[] {data.length}, List.of(
                Json.parse("{\"name\":\"bytes\"}").asObject(),
                Json.parse("{\"name\":\"blosc\",\"configuration\":{\"cname\":\"zstd\",\"clevel\":9,"
                        + "\"shuffle\":\"noshuffle\",\"typesize\":1,\"blocksize\":0}}").asObject()));
        byte[] a = low.encode(data, new byte[1]);
        byte[] b = high.encode(data, new byte[1]);
        assertTrue(b.length < a.length, b.length + " at clevel 9, " + a.length + " at clevel 1");
        assertArrayEquals(data, low.decode(b));
    }

    /**
     * {@code ArraySpec.blosc(cname, clevel, shuffle)} records the three (F3), and the array's chunks are written
     * with them; {@code blosc()} keeps zstd at clevel 5 with the byte shuffle.
     */
    @Test
    void anArraySpecRecordsItsBloscSettings() {
        JsonObject lz4 = ArraySpec.builder(new long[] {64}, DataType.FLOAT64).blosc("lz4hc", 7, "bitshuffle").build()
                .toJson().get("codecs").asArray().get(1).asObject().get("configuration").asObject();
        assertEquals("lz4hc", lz4.get("cname").asString());
        assertEquals(7, lz4.get("clevel").asNumber().intValue());
        assertEquals("bitshuffle", lz4.get("shuffle").asString());
        assertEquals(8, lz4.get("typesize").asNumber().intValue());
        JsonObject plain = ArraySpec.builder(new long[] {64}, DataType.FLOAT64).blosc().build()
                .toJson().get("codecs").asArray().get(1).asObject().get("configuration").asObject();
        assertEquals("zstd", plain.get("cname").asString());
        assertEquals("shuffle", plain.get("shuffle").asString());

        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {8192}, DataType.FLOAT64)
                .blosc("zlib", 9, "noshuffle").build());
        ByteBuffer values = ByteBuffer.wrap(doubles()).order(ByteOrder.LITTLE_ENDIAN);
        double[] expected = new double[8192];
        values.asDoubleBuffer().get(expected);
        a.writeDoubles(expected);
        byte[] chunk = store.get("c/0").orElseThrow();
        assertEquals(3, (chunk[2] & 0xff) >>> 5, "zlib's format");
        assertEquals(0, chunk[2] & 0x05, "no shuffle");
        assertArrayEquals(BloscEncoder.compress(doubles(), 8, BloscEncoder.NOSHUFFLE, 0, 9, BloscEncoder.ZLIB), chunk);
        assertArrayEquals(expected, Zarr.openArray(store).readDoubles());

        ArraySpec.Builder b = ArraySpec.builder(new long[] {1}, DataType.INT8);
        assertThrows(IllegalArgumentException.class, () -> b.blosc("LZ4", 5, "shuffle"));
        assertThrows(IllegalArgumentException.class, () -> b.blosc("lz4", 10, "shuffle"));
        assertThrows(IllegalArgumentException.class, () -> b.blosc("lz4", -1, "shuffle"));
        assertThrows(IllegalArgumentException.class, () -> b.blosc("lz4", 5, "byteshuffle"));
    }

    /** {@code ArraySpec.zstd(level)} records the level; libzstd's range is checked. */
    @Test
    void anArraySpecRecordsItsZstdLevel() {
        ArraySpec spec = ArraySpec.builder(new long[] {10}, DataType.INT32).zstd(7).build();
        assertEquals(7, spec.toJson().get("codecs").asArray().get(1).asObject().get("configuration").asObject()
                .get("level").asNumber().intValue());
        assertEquals(0, ArraySpec.builder(new long[] {10}, DataType.INT32).zstd().build().toJson().get("codecs")
                .asArray().get(1).asObject().get("configuration").asObject().get("level").asNumber().intValue());
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {1}, DataType.INT8).zstd(23));
        assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {1}, DataType.INT8).zstd(-131073));
    }
}
