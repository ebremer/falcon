package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Writing honours a codec's configuration (P1 I9). Falcon ignored it: zstd frames never carried the
 * checksum a {@code "checksum": true} array asks for, and Blosc always byte-shuffled with automatic blocks,
 * whatever {@code shuffle}, {@code typesize}, {@code blocksize}, or {@code clevel} said. The output was
 * still readable, but not what the metadata describes.
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

    @Test
    void aBloscConfigurationWithoutFieldsUsesTheDefaults() {
        // v2 metadata records no Blosc configuration: byte shuffle by element size, automatic blocks.
        ChunkPipeline p = pipeline(DataType.FLOAT64, 8192, "{\"name\":\"blosc\"}");
        byte[] stored = p.encode(doubles(), new byte[8]);
        assertEquals(0x01, stored[2] & 0x05);
        assertEquals(8, stored[3]);
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
}
