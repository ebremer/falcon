package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.bzip2.Bzip2Encoder;
import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * numcodecs' {@code BZ2} and {@code ZFPY} under the names zarr-python 3 gives them, {@code numcodecs.bz2} (a
 * bytes&rarr;bytes codec) and {@code numcodecs.zfpy} (an array&rarr;bytes codec in Zarr v3, a bytes&rarr;bytes one
 * where a Zarr v2 compressor is translated): bz2's level, its streams one after another as Python's
 * {@code bz2.decompress} reads them, and its bounds; zfpy's checks of a stream against the array, and its refusal
 * to encode. zarr-python's own arrays are read in Bz2ZfpyFixtureTest.
 */
class Bz2ZfpyCodecTest {

    private static final JsonObject BYTES = codec("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}");
    private static final JsonObject BYTES_BIG = codec("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}}");
    private static final JsonObject ZFPY = codec("{\"name\":\"numcodecs.zfpy\",\"configuration\":{\"mode\":4,"
            + "\"tolerance\":-1,\"rate\":-1,\"precision\":-1,\"compression_kwargs\":{\"tolerance\":-1}}}");

    private static JsonObject codec(String json) {
        return Json.parse(json).asObject();
    }

    private static ChunkPipeline bz2(int n, String configuration) {
        return ChunkPipeline.of(DataType.INT32, new long[] {n},
                List.of(BYTES, codec("{\"name\":\"numcodecs.bz2\"" + configuration + "}")));
    }

    private static byte[] ints(int n) {
        ByteBuffer b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            b.putInt(i / 5 - 300);
        }
        return b.array();
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) {
            length += p.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    // ---- numcodecs.bz2 ----------------------------------------------------------------------------------

    @Test
    void bz2ChunksAreLibbzip2StreamsAtTheConfiguredLevel() {
        byte[] data = ints(60000); // 240 kB: three blocks at level 1
        for (int level = 1; level <= 9; level += 4) {
            ChunkPipeline p = bz2(60000, ",\"configuration\":{\"level\":" + level + "}");
            byte[] stored = p.encode(data, new byte[4]);
            assertArrayEquals(Bzip2Encoder.compress(data, level), stored, "level " + level);
            assertEquals('0' + level, stored[3]);
            assertArrayEquals(data, p.decode(stored), "level " + level);
        }
        // numcodecs' default level is 1, and zarr-python writes an empty configuration for it
        assertArrayEquals(Bzip2Encoder.compress(data, 1), bz2(60000, "").encode(data, new byte[4]));
        assertArrayEquals(Bzip2Encoder.compress(data, 1), bz2(60000, ",\"configuration\":{}").encode(data, new byte[4]));
        for (String bad : new String[] {"0", "10", "-1", "\"9\"", "1.5"}) {
            assertThrows(ZarrFormatException.class, () -> bz2(10, ",\"configuration\":{\"level\":" + bad + "}"), bad);
        }
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.INT32, new long[] {4},
                List.of(codec("{\"name\":\"numcodecs.bz2\"}"), BYTES)));
    }

    @Test
    void bz2ReadsStreamsOneAfterAnotherAsPythonDoes() {
        byte[] data = ints(3000);
        ChunkPipeline p = bz2(3000, "");
        byte[] a = Bzip2Encoder.compress(Arrays.copyOf(data, 5000), 9);
        byte[] b = Bzip2Encoder.compress(Arrays.copyOfRange(data, 5000, 12000), 1);
        assertArrayEquals(data, p.decode(concat(a, b)));
        assertArrayEquals(data, p.decode(concat(a, Bzip2Encoder.compress(new byte[0], 5), b)));
        // bytes after the streams that are not a stream are ignored, as Python ignores them
        assertArrayEquals(data, p.decode(concat(a, b, new byte[] {0, 'j', 'u', 'n', 'k'})));
        assertArrayEquals(data, p.decode(concat(a, b, "BZh0".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        // so is a corrupt stream after the first, which leaves the chunk short of its elements
        byte[] corrupt = b.clone();
        corrupt[10] ^= 0x10;
        ZarrFormatException shortChunk = assertThrows(ZarrFormatException.class, () -> p.decode(concat(a, corrupt)));
        assertTrue(shortChunk.getMessage().contains("bytes codec"), shortChunk.getMessage());
        // but a stream cut short is an error, as are a first stream that is not one and no bytes at all
        ZarrFormatException cut = assertThrows(ZarrFormatException.class,
                () -> p.decode(concat(a, Arrays.copyOf(b, b.length - 1))));
        assertTrue(cut.getMessage().startsWith("numcodecs.bz2 decode failed"), cut.getMessage());
        assertThrows(ZarrFormatException.class, () -> p.decode(concat("BZh".getBytes(), a)));
        assertThrows(ZarrFormatException.class, () -> p.decode(new byte[0])); // no bytes: no elements
    }

    @Test
    void bz2DecodingIsBounded() {
        // the streams decode to more than the chunk holds: refused as soon as the output passes it
        byte[] big = Bzip2Encoder.compress(new byte[1 << 20], 9);
        ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> bz2(16, "").decode(big));
        assertTrue(e.getMessage().contains("decodes to more than 64 bytes"), e.getMessage());
        byte[] two = concat(Bzip2Encoder.compress(ints(16), 1), Bzip2Encoder.compress(new byte[1], 1));
        assertThrows(ZarrFormatException.class, () -> bz2(16, "").decode(two));
        Bz2Codec codec = Bz2Codec.parse(codec("{}"));
        assertEquals("numcodecs.bz2", codec.name());
        assertThrows(ZarrUnsupportedException.class, () -> codec.encodedSize(10)); // no shard index through it
        assertTrue(codec.maxEncodedSize(1000) >= 1000 * 101 / 100 + 600);
    }

    @Test
    void anArraySpecRecordsItsBz2Level() {
        ArraySpec spec = ArraySpec.builder(new long[] {10}, DataType.INT16).bz2(7).crc32c().build();
        assertEquals("[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},"
                + "{\"name\":\"numcodecs.bz2\",\"configuration\":{\"level\":7}},{\"name\":\"crc32c\"}]",
                spec.toJson().get("codecs").toJson());
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {10}, DataType.INT16).bz2(0));
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {10}, DataType.INT16).bz2(10));
        // under a shard, it compresses each sub-chunk
        ArraySpec sharded = ArraySpec.builder(new long[] {10}, DataType.INT16).bz2(1).sharding(5).build();
        assertTrue(sharded.toJson().get("codecs").toJson().contains("\"codecs\":[{\"name\":\"bytes\","
                + "\"configuration\":{\"endian\":\"little\"}},{\"name\":\"numcodecs.bz2\",\"configuration\":{\"level\":1}}]"));
    }

    // ---- numcodecs.zfpy ---------------------------------------------------------------------------------

    /** The first chunk zarr-python wrote of a 37 x 23 float64 array in 16 x 10 chunks, zfpy's defaults (reversible). */
    private static byte[] zfpChunk() {
        try {
            return Files.readAllBytes(Path.of(Bz2ZfpyCodecTest.class.getResource(
                    "/fixtures/numcodecs_zfpy_f8_reversible_2d/c/0/0").toURI()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChunkPipeline zfpy(DataType type, long... shape) {
        return ChunkPipeline.of(type, shape, List.of(ZFPY));
    }

    @Test
    void zfpyChecksTheStreamAgainstTheArray() {
        byte[] stream = zfpChunk();
        byte[] decoded = zfpy(DataType.FLOAT64, 16, 10).decode(stream);
        assertEquals(16 * 10 * 8, decoded.length);
        assertEquals(java.nio.ByteOrder.LITTLE_ENDIAN, zfpy(DataType.FLOAT64, 16, 10).elementOrder());
        ZarrFormatException type = assertThrows(ZarrFormatException.class, () -> zfpy(DataType.FLOAT32, 16, 10).decode(stream));
        assertTrue(type.getMessage().contains("zfp float64 values, but the array's data type is 'float32'"),
                type.getMessage());
        ZarrFormatException other = assertThrows(ZarrFormatException.class, () -> zfpy(DataType.UINT16, 16, 10).decode(stream));
        assertTrue(other.getMessage().contains("zfpy does not compress"), other.getMessage());
        assertThrows(ZarrFormatException.class, () -> zfpy(DataType.INT64, 16, 10).decode(stream));
        // the shape is the chunk's (zfp's x last), not merely as many elements
        ZarrFormatException shape = assertThrows(ZarrFormatException.class, () -> zfpy(DataType.FLOAT64, 10, 16).decode(stream));
        assertTrue(shape.getMessage().contains("of shape [16, 10], but the chunk is [10, 16]"), shape.getMessage());
        assertThrows(ZarrFormatException.class, () -> zfpy(DataType.FLOAT64, 160).decode(stream));
        assertThrows(ZarrFormatException.class, () -> zfpy(DataType.FLOAT64, 16, 10, 1).decode(stream));
        // a transpose before it hands it the transposed chunk
        ChunkPipeline transposed = ChunkPipeline.of(DataType.FLOAT64, new long[] {10, 16},
                List.of(codec("{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}}"), ZFPY));
        byte[] back = transposed.decode(stream);
        assertEquals(Arrays.toString(Arrays.copyOfRange(decoded, 8, 16)), Arrays.toString(Arrays.copyOfRange(back, 16 * 8, 17 * 8)));
    }

    @Test
    void malformedZfpStreamsAreRefused() {
        byte[] stream = zfpChunk();
        ChunkPipeline p = zfpy(DataType.FLOAT64, 16, 10);
        byte[] magic = stream.clone();
        magic[0] = 'Z';
        assertThrows(ZarrFormatException.class, () -> p.decode(magic));
        byte[] version = stream.clone();
        version[3] = 4; // zfp's codec version
        assertThrows(ZarrUnsupportedException.class, () -> p.decode(version));
        assertThrows(ZarrFormatException.class, () -> p.decode(Arrays.copyOf(stream, 8)));
        assertThrows(ZarrFormatException.class, () -> p.decode(Arrays.copyOf(stream, stream.length / 2)));
        assertThrows(ZarrFormatException.class, () -> p.decode(new byte[0]));
        // the configuration only steers the encoder, but must be numbers
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.FLOAT64, new long[] {4},
                List.of(codec("{\"name\":\"numcodecs.zfpy\",\"configuration\":{\"mode\":\"rate\"}}"))));
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.FLOAT64, new long[] {4},
                List.of(codec("{\"name\":\"numcodecs.zfpy\",\"configuration\":{\"tolerance\":[1]}}"))));
        // variable-length elements have their own array->bytes codec
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.STRING, new long[] {4}, List.of(ZFPY)));
        assertThrows(ZarrFormatException.class, () -> ChunkPipeline.of(DataType.FLOAT64, new long[] {4},
                List.of(ZFPY, BYTES)));
    }

    @Test
    void zfpyNeverEncodes() {
        ChunkPipeline p = zfpy(DataType.FLOAT64, 16, 10);
        ZarrUnsupportedException e = assertThrows(ZarrUnsupportedException.class, () -> p.encode(new byte[1280], new byte[8]));
        assertTrue(e.getMessage().contains("read-only"), e.getMessage());
        assertThrows(ZarrUnsupportedException.class, p::checkEncodable);
        String shard = "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[8,5],\"codecs\":["
                + "{\"name\":\"numcodecs.zfpy\"}],\"index_codecs\":[{\"name\":\"bytes\",\"configuration\":"
                + "{\"endian\":\"little\"}},{\"name\":\"crc32c\"}]}}";
        ChunkPipeline sharded = ChunkPipeline.of(DataType.FLOAT64, new long[] {16, 10}, List.of(codec(shard)));
        assertThrows(ZarrUnsupportedException.class, sharded::checkEncodable);
        ChunkPipeline v2 = ChunkPipeline.of(DataType.FLOAT64, new long[] {16, 10}, List.of(BYTES, ZFPY));
        assertThrows(ZarrUnsupportedException.class, v2::checkEncodable);
        assertThrows(ZarrUnsupportedException.class, () -> v2.encode(new byte[1280], new byte[8]));
        bz2(4, "").checkEncodable(); // every other codec encodes
        // a bound for a codec after it: every block at the most bits a zfp header allows
        ChunkPipeline crc = ChunkPipeline.of(DataType.FLOAT64, new long[] {16, 10},
                List.of(ZFPY, codec("{\"name\":\"crc32c\"}")));
        assertTrue(crc.maxEncodedLength() >= 4L * 3 * 2048 * 8 / 8);
        assertEquals(16 * 10 * 8, crc.decode(concat(zfpChunk(), crc32c(zfpChunk()))).length);
    }

    private static byte[] crc32c(byte[] data) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(data);
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) crc.getValue()).array();
    }

    /**
     * A Zarr v2 {@code zfpy} compressor follows the {@code bytes} codec: the stream must hold the elements numcodecs
     * gave zfpy, little-endian int32, int64, float32, or float64, in any shape of as many elements.
     */
    @Test
    void theV2CompressorChecksTheElementsItIsHanded() {
        byte[] stream = zfpChunk();
        byte[] decoded = zfpy(DataType.FLOAT64, 16, 10).decode(stream);
        assertArrayEquals(decoded, ChunkPipeline.of(DataType.FLOAT64, new long[] {16, 10}, List.of(BYTES, ZFPY)).decode(stream));
        // zarr-python only reshapes what zfpy decodes: a chunk of 160 or 10 x 16 reads the same elements
        assertArrayEquals(decoded, ChunkPipeline.of(DataType.FLOAT64, new long[] {160}, List.of(BYTES, ZFPY)).decode(stream));
        assertArrayEquals(decoded, ChunkPipeline.of(DataType.FLOAT64, new long[] {10, 16}, List.of(BYTES, ZFPY)).decode(stream));
        // but not more or fewer of them
        assertThrows(ZarrFormatException.class,
                () -> ChunkPipeline.of(DataType.FLOAT64, new long[] {100}, List.of(BYTES, ZFPY)).decode(stream));
        assertThrows(ZarrFormatException.class,
                () -> ChunkPipeline.of(DataType.FLOAT64, new long[] {200}, List.of(BYTES, ZFPY)).decode(stream));
        // elements zfpy does not compress: big-endian, other types, bytes after another codec
        for (List<JsonObject> codecs : List.of(List.of(BYTES_BIG, ZFPY),
                List.of(BYTES, codec("{\"name\":\"numcodecs.shuffle\",\"configuration\":{\"elementsize\":8}}"), ZFPY))) {
            ZarrFormatException e = assertThrows(ZarrFormatException.class,
                    () -> ChunkPipeline.of(DataType.FLOAT64, new long[] {16, 10}, codecs).decode(stream));
            assertTrue(e.getMessage().contains("which zfpy does not compress"), e.getMessage());
        }
        assertThrows(ZarrFormatException.class,
                () -> ChunkPipeline.of(DataType.UINT64, new long[] {16, 10}, List.of(BYTES, ZFPY)).decode(stream));
        ZarrFormatException type = assertThrows(ZarrFormatException.class,
                () -> ChunkPipeline.of(DataType.INT64, new long[] {16, 10}, List.of(BYTES, ZFPY)).decode(stream));
        assertTrue(type.getMessage().contains("zfp float64 values, but the elements are '<i8'"), type.getMessage());
        // a filter before it decides its elements: delta's astype
        ChunkPipeline delta = ChunkPipeline.of(DataType.INT64, new long[] {16, 10}, List.of(BYTES,
                codec("{\"name\":\"numcodecs.delta\",\"configuration\":{\"dtype\":\"<i8\",\"astype\":\"<f8\"}}"), ZFPY));
        assertEquals(16 * 10 * 8, delta.decode(stream).length);
    }
}
