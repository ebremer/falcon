package com.ebremer.falcon.zarr.codec;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class ChunkPipelineTest {

    private static JsonObject spec(String json) {
        return Json.parse(json).asObject();
    }

    private static ChunkPipeline pipe(DataType dataType, long[] chunkShape, JsonObject... codecs) {
        return ChunkPipeline.of(dataType, chunkShape, List.of(codecs));
    }

    private static final JsonObject BYTES_LE = spec("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}");
    private static final JsonObject BYTES_BE = spec("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}}");
    private static final JsonObject GZIP = spec("{\"name\":\"gzip\",\"configuration\":{\"level\":5}}");
    private static final JsonObject CRC32C_CODEC = spec("{\"name\":\"crc32c\"}");

    private static byte[] ints(ByteOrder order, int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(order);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bos)) {
            g.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    private static byte[] withCrc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        long v = crc.getValue();
        byte[] out = Arrays.copyOf(data, data.length + 4);
        out[data.length] = (byte) v;
        out[data.length + 1] = (byte) (v >>> 8);
        out[data.length + 2] = (byte) (v >>> 16);
        out[data.length + 3] = (byte) (v >>> 24);
        return out;
    }

    // ---- bytes codec ------------------------------------------------------------------------------

    @Test
    void bytesCodecPassesElementsThroughAndReportsOrder() {
        ChunkPipeline le = pipe(DataType.INT32, new long[] {3}, BYTES_LE);
        byte[] stored = ints(LITTLE_ENDIAN, 1, 2, 3);
        assertArrayEquals(stored, le.decode(stored));
        assertEquals(LITTLE_ENDIAN, le.elementOrder());

        ChunkPipeline be = pipe(DataType.INT32, new long[] {3}, BYTES_BE);
        byte[] storedBe = ints(BIG_ENDIAN, 1, 2, 3);
        assertArrayEquals(storedBe, be.decode(storedBe));
        assertEquals(BIG_ENDIAN, be.elementOrder());
    }

    @Test
    void bytesCodecRejectsWrongLength() {
        ChunkPipeline p = pipe(DataType.INT32, new long[] {3}, BYTES_LE);
        assertThrows(ZarrFormatException.class, () -> p.decode(new byte[11])); // expected 12
    }

    @Test
    void bytesCodecRejectsBadEndian() {
        assertThrows(ZarrFormatException.class, () -> pipe(DataType.INT32, new long[] {3},
                spec("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"middle\"}}")));
    }

    // ---- gzip / crc32c ----------------------------------------------------------------------------

    @Test
    void gzipRoundTrip() {
        byte[] elements = ints(LITTLE_ENDIAN, 100, 200, 300, 400);
        ChunkPipeline p = pipe(DataType.INT32, new long[] {4}, BYTES_LE, GZIP);
        assertArrayEquals(elements, p.decode(gzip(elements)));
    }

    @Test
    void crc32cVerifiesAndStrips() {
        byte[] elements = ints(LITTLE_ENDIAN, 7, 8, 9);
        ChunkPipeline p = pipe(DataType.INT32, new long[] {3}, BYTES_LE, CRC32C_CODEC);
        assertArrayEquals(elements, p.decode(withCrc32c(elements)));
    }

    @Test
    void crc32cRejectsCorruptChunk() {
        byte[] elements = ints(LITTLE_ENDIAN, 7, 8, 9);
        byte[] stored = withCrc32c(elements);
        stored[0] ^= 0x01; // flip a data bit
        ChunkPipeline p = pipe(DataType.INT32, new long[] {3}, BYTES_LE, CRC32C_CODEC);
        ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> p.decode(stored));
        assertEquals(true, e.getMessage().contains("crc32c"));
    }

    @Test
    void gzipThenCrc32cChain() {
        // pipeline order [bytes, gzip, crc32c] -> stored = crc32c(gzip(elements)); decode reverses.
        byte[] elements = ints(LITTLE_ENDIAN, 11, 22, 33, 44, 55);
        ChunkPipeline p = pipe(DataType.INT32, new long[] {5}, BYTES_LE, GZIP, CRC32C_CODEC);
        assertArrayEquals(elements, p.decode(withCrc32c(gzip(elements))));
    }

    // ---- transpose --------------------------------------------------------------------------------

    @Test
    void transposeDecodesToLogicalOrder() {
        // Logical [2,3] = [10,11,12, 13,14,15]. transpose order [1,0] stores it as [3,2]:
        // [10,13, 11,14, 12,15]. Decoding must recover the logical C-order bytes.
        JsonObject transpose = spec("{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}}");
        ChunkPipeline p = pipe(DataType.INT8, new long[] {2, 3}, transpose, BYTES_LE);
        byte[] stored = {10, 13, 11, 14, 12, 15};
        assertArrayEquals(new byte[] {10, 11, 12, 13, 14, 15}, p.decode(stored));
    }

    @Test
    void transposeThreeDimensional() {
        // Logical shape [2,2,2] with values 0..7 in C order; transpose order [2,1,0] reverses axes.
        JsonObject transpose = spec("{\"name\":\"transpose\",\"configuration\":{\"order\":[2,1,0]}}");
        ChunkPipeline p = pipe(DataType.INT8, new long[] {2, 2, 2}, transpose, BYTES_LE);
        // Encoded E[a,b,c] = L[c,b,a]; E in C order:
        // (0,0,0)=L0 (0,0,1)=L4 (0,1,0)=L2 (0,1,1)=L6 (1,0,0)=L1 (1,0,1)=L5 (1,1,0)=L3 (1,1,1)=L7
        byte[] stored = {0, 4, 2, 6, 1, 5, 3, 7};
        assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7}, p.decode(stored));
    }

    @Test
    void transposeRejectsBadOrder() {
        assertThrows(ZarrFormatException.class, () -> pipe(DataType.INT8, new long[] {2, 3},
                spec("{\"name\":\"transpose\",\"configuration\":{\"order\":[0,0]}}"), BYTES_LE));
        assertThrows(ZarrFormatException.class, () -> pipe(DataType.INT8, new long[] {2, 3},
                spec("{\"name\":\"transpose\",\"configuration\":{\"order\":[0]}}"), BYTES_LE));
    }

    // ---- pipeline structure -----------------------------------------------------------------------

    @Test
    void requiresExactlyOneArrayToBytesCodec() {
        assertThrows(ZarrFormatException.class, () -> pipe(DataType.INT32, new long[] {3})); // none
        assertThrows(ZarrFormatException.class,
                () -> pipe(DataType.INT32, new long[] {3}, GZIP)); // no array->bytes
        assertThrows(ZarrFormatException.class,
                () -> pipe(DataType.INT32, new long[] {3}, BYTES_LE, BYTES_LE)); // two
    }

    @Test
    void enforcesCodecOrdering() {
        // bytes->bytes before array->bytes
        assertThrows(ZarrFormatException.class,
                () -> pipe(DataType.INT32, new long[] {3}, CRC32C_CODEC, BYTES_LE));
        // array->array after array->bytes
        assertThrows(ZarrFormatException.class, () -> pipe(DataType.INT8, new long[] {2, 3}, BYTES_LE,
                spec("{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}}")));
    }

    @Test
    void unsupportedCodecsAreReported() {
        assertThrows(ZarrUnsupportedException.class,
                () -> pipe(DataType.INT32, new long[] {3}, BYTES_LE, spec("{\"name\":\"zstd\"}")));
        assertThrows(ZarrUnsupportedException.class,
                () -> pipe(DataType.INT32, new long[] {3}, BYTES_LE, spec("{\"name\":\"blosc\"}")));
        assertThrows(ZarrUnsupportedException.class,
                () -> pipe(DataType.INT32, new long[] {3}, BYTES_LE, spec("{\"name\":\"mystery\"}")));
    }

    @Test
    void shardingCodecRequiresItsConfiguration() {
        // sharding_indexed is implemented (Z6), so a bare spec is malformed, not unsupported.
        assertThrows(ZarrFormatException.class,
                () -> pipe(DataType.INT32, new long[] {4}, spec("{\"name\":\"sharding_indexed\"}")));
    }
}
