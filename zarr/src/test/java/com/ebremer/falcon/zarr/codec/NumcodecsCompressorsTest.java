package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.lz4.Lz4;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * numcodecs' {@code Zlib} and {@code LZ4} compressors under the names zarr-python 3 writes in v3 metadata,
 * {@code numcodecs.zlib} and {@code numcodecs.lz4} (P2 F3/F4): their formats (a zlib stream; a
 * little-endian {@code uint32} length and an LZ4 block), their configurations, bounded decoding, and
 * writing into an array whose metadata names them. zarr-python's own arrays are read in ConformanceTest,
 * and Falcon's written ones are read by zarr-python in {@code check_zarr_writer.py}.
 */
class NumcodecsCompressorsTest {

    private static final JsonObject BYTES = Json.parse("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}").asObject();

    private static ChunkPipeline pipeline(int n, String codec) {
        return ChunkPipeline.of(DataType.INT32, new long[] {n}, List.of(BYTES, Json.parse(codec).asObject()));
    }

    private static byte[] ints(int n) {
        ByteBuffer b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            b.putInt(i / 7);
        }
        return b.array();
    }

    private static byte[] zlib(byte[] data, int level) {
        Deflater deflater = new Deflater(level);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    @Test
    void zlibChunksAreZlibStreamsAtTheConfiguredLevel() {
        byte[] data = ints(5000);
        for (int level : new int[] {-1, 0, 1, 6, 9}) {
            ChunkPipeline p = pipeline(5000, "{\"name\":\"numcodecs.zlib\",\"configuration\":{\"level\":" + level + "}}");
            byte[] stored = p.encode(data, new byte[4]);
            assertArrayEquals(zlib(data, level), stored, "level " + level);
            assertArrayEquals(data, p.decode(stored), "level " + level);
        }
        // numcodecs' default level is 1, and zarr-python writes no configuration for it
        assertArrayEquals(zlib(data, 1), pipeline(5000, "{\"name\":\"numcodecs.zlib\"}").encode(data, new byte[4]));
        assertArrayEquals(zlib(data, 1),
                pipeline(5000, "{\"name\":\"numcodecs.zlib\",\"configuration\":{}}").encode(data, new byte[4]));
    }

    @Test
    void zlibDecodingIsBoundedAndChecked() {
        ZlibCodec codec = ZlibCodec.parse(Json.parse("{}").asObject());
        byte[] data = ints(1000);
        byte[] stream = codec.encode(data);
        // bytes after the stream are ignored, as numcodecs (Python's zlib) ignores them
        byte[] trailing = Arrays.copyOf(stream, stream.length + 5);
        assertArrayEquals(data, codec.decode(trailing, data.length));
        assertThrows(ZarrFormatException.class, () -> codec.decode(Arrays.copyOf(stream, stream.length - 3), 4000),
                "a truncated stream");
        assertThrows(ZarrFormatException.class, () -> codec.decode(new byte[] {1, 2, 3, 4}, 4000), "not zlib");
        // a bomb: 4 MB of zeros in a few KB, where the chunk holds 4000 bytes
        byte[] bomb = codec.encode(new byte[4 << 20]);
        assertThrows(ZarrFormatException.class, () -> codec.decode(bomb, 4000));
        for (String bad : new String[] {"{\"level\":10}", "{\"level\":-2}", "{\"level\":1.5}", "{\"level\":\"1\"}"}) {
            assertThrows(ZarrFormatException.class,
                    () -> pipeline(16, "{\"name\":\"numcodecs.zlib\",\"configuration\":" + bad + "}"), bad);
        }
    }

    @Test
    void lz4ChunksAreALengthAndALiblz4Block() {
        byte[] data = ints(5000);
        for (int acceleration : new int[] {1, 4, 100}) {
            ChunkPipeline p = pipeline(5000,
                    "{\"name\":\"numcodecs.lz4\",\"configuration\":{\"acceleration\":" + acceleration + "}}");
            byte[] stored = p.encode(data, new byte[4]);
            assertEquals(data.length, ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
            assertArrayEquals(Lz4.compress(data, 0, data.length, acceleration),
                    Arrays.copyOfRange(stored, 4, stored.length), "acceleration " + acceleration);
            assertArrayEquals(data, p.decode(stored));
        }
        // numcodecs' default acceleration is 1; below 1, liblz4 takes 1
        byte[] one = pipeline(5000, "{\"name\":\"numcodecs.lz4\"}").encode(data, new byte[4]);
        assertArrayEquals(one, pipeline(5000,
                "{\"name\":\"numcodecs.lz4\",\"configuration\":{\"acceleration\":0}}").encode(data, new byte[4]));
        assertArrayEquals(one, pipeline(5000,
                "{\"name\":\"numcodecs.lz4\",\"configuration\":{\"acceleration\":1}}").encode(data, new byte[4]));
        // an empty chunk: a zero length and an empty block
        Lz4Codec codec = Lz4Codec.parse(Json.parse("{}").asObject());
        assertArrayEquals(new byte[] {0, 0, 0, 0, 0}, codec.encode(new byte[0]));
        assertArrayEquals(new byte[0], codec.decode(new byte[] {0, 0, 0, 0, 0}, 0));
    }

    @Test
    void lz4DecodingIsBoundedAndChecked() {
        Lz4Codec codec = Lz4Codec.parse(Json.parse("{}").asObject());
        byte[] data = ints(1000);
        byte[] stored = codec.encode(data);
        assertArrayEquals(data, codec.decode(stored, data.length));
        // a length over the chunk's size fails before anything is allocated
        byte[] huge = stored.clone();
        huge[3] = (byte) 0x7f;
        assertThrows(ZarrFormatException.class, () -> codec.decode(huge, 4000));
        byte[] unsigned = stored.clone();
        unsigned[3] = (byte) 0xff; // a uint32 over 2 GiB, not a negative int
        assertThrows(ZarrFormatException.class, () -> codec.decode(unsigned, 4000));
        assertThrows(ZarrFormatException.class, () -> codec.decode(new byte[] {1, 0}, 4000), "shorter than its length");
        assertThrows(ZarrFormatException.class, () -> codec.decode(Arrays.copyOf(stored, stored.length - 2), 4000),
                "a truncated block");
        byte[] wrong = stored.clone();
        wrong[0]++; // one byte more claimed than the block holds
        assertThrows(ZarrFormatException.class, () -> codec.decode(wrong, 5000));
        assertThrows(ZarrFormatException.class,
                () -> pipeline(16, "{\"name\":\"numcodecs.lz4\",\"configuration\":{\"acceleration\":2.5}}"));
    }

    /** Both are bytes&rarr;bytes codecs, as in zarr-python: before the array&rarr;bytes codec they are refused. */
    @Test
    void theyComeAfterTheArrayToBytesCodec() {
        for (String name : new String[] {"numcodecs.zlib", "numcodecs.lz4"}) {
            assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT32, new long[] {4},
                    List.of(Json.parse("{\"name\":\"" + name + "\"}").asObject(), BYTES)), name);
            BytesBytesCodec codec = name.endsWith("zlib") ? ZlibCodec.parse(Json.parse("{}").asObject())
                    : Lz4Codec.parse(Json.parse("{}").asObject());
            assertEquals(name, codec.name());
            assertThrows(ZarrUnsupportedException.class, () -> codec.encodedSize(10),
                    name + " has no fixed size for a shard index");
        }
    }

    /** Writing into an array whose metadata names them (zarr-python made it, say), sharded too, and reading back. */
    @Test
    void anArrayWithThemWritesAndReads() {
        String shard = "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[2,2],\"codecs\":["
                + BYTES.toJson() + ",{\"name\":\"numcodecs.lz4\",\"configuration\":{\"acceleration\":3}}],"
                + "\"index_codecs\":[" + BYTES.toJson() + ",{\"name\":\"crc32c\"}],\"index_location\":\"end\"}}";
        String[] codecs = {
            "[" + BYTES.toJson() + ",{\"name\":\"numcodecs.zlib\",\"configuration\":{\"level\":5}}]",
            "[" + BYTES.toJson() + ",{\"name\":\"numcodecs.lz4\",\"configuration\":{}}]",
            "[" + shard + ",{\"name\":\"numcodecs.zlib\"}]"};
        for (String chain : codecs) {
            MemoryStore store = new MemoryStore();
            String meta = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[9,7],\"data_type\":\"int32\","
                    + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[4,4]}},"
                    + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":-1,\"codecs\":" + chain
                    + ",\"attributes\":{}}";
            store.set("zarr.json", meta.getBytes(StandardCharsets.UTF_8));
            ZarrArray a = Zarr.open(store).asArray();
            int[] values = new int[63];
            Random random = new Random(3);
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextInt(20);
            }
            a.writeInts(values);
            assertArrayEquals(values, Zarr.open(store).asArray().readInts(), chain);
            assertTrue(store.get("c/0/0").isPresent(), chain);
        }
    }
}
