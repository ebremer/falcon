package com.ebremer.falcon.zarr.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Corrupt chunks that claim far more output than the chunk can hold (P1 H1), and malformed lengths and
 * shard indexes that used to escape as raw runtime exceptions (P1 H2).
 *
 * <p>The pipeline knows each chunk's decoded size but never passed it on, so a 1.5 MB gzip chunk could
 * inflate without bound, 8 KB of zstd RLE blocks could ask for 256 MB, and a 16-byte Blosc header could
 * ask for 2 GB. Each now fails as soon as the output would exceed what the chunk holds. Run under a small
 * heap with the other corrupt-input tests (the zarr POM's {@code fuzz} execution).
 */
class DecodeBoundsTest {

    private static JsonObject spec(String json) {
        return Json.parse(json).asObject();
    }

    private static final JsonObject BYTES = spec("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}");

    /** A pipeline for a 16-element int32 chunk (64 bytes) with the given bytes-to-bytes codecs. */
    private static ChunkPipeline int32Chunk(String... byteCodecs) {
        List<JsonObject> codecs = new java.util.ArrayList<>(List.of(BYTES));
        for (String c : byteCodecs) {
            codecs.add(spec(c));
        }
        return ChunkPipeline.of(DataType.INT32, new long[] {16}, codecs);
    }

    private static void assertRefused(ChunkPipeline pipeline, byte[] stored, String expected) {
        ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> pipeline.decode(stored));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    /** {@code n} zero bytes, gzipped without ever holding them in memory. */
    private static byte[] gzipOfZeros(long n) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bos, 1 << 16)) {
            byte[] zeros = new byte[1 << 16];
            for (long left = n; left > 0; left -= zeros.length) {
                g.write(zeros, 0, (int) Math.min(zeros.length, left));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    // ---- H1: bound decompression ----------------------------------------------------------------------

    @Test
    void aGzipBombStopsAtTheChunkSize() {
        // 256 MB of zeros in ~250 KB; the chunk holds 64 bytes. Under -Xmx128m the old readAllBytes ran out
        // of memory.
        assertRefused(int32Chunk("{\"name\":\"gzip\"}"), gzipOfZeros(256L << 20), "more than the 64 bytes");
    }

    @Test
    void zstdRleBlocksStopAtTheChunkSize() {
        // A frame with no content size and 2,000 RLE blocks of 128 KiB each: 8 KB that decode to 256 MB.
        ByteBuffer frame = ByteBuffer.allocate(6 + 2000 * 4).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(0xFD2FB528);
        frame.put((byte) 0x00);      // no content size, not single-segment, no checksum
        frame.put((byte) (7 << 3));  // window descriptor: 2^17 bytes
        for (int i = 0; i < 2000; i++) {
            int header = (131072 << 3) | (1 << 1) | (i == 1999 ? 1 : 0); // RLE block, 128 KiB
            frame.put((byte) header).put((byte) (header >>> 8)).put((byte) (header >>> 16));
            frame.put((byte) 7);
        }
        assertRefused(int32Chunk("{\"name\":\"zstd\"}"), frame.array(), "zstd decode failed");
    }

    @Test
    void aBloscHeaderClaimingTwoGigabytesIsRefusedBeforeAllocating() {
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 2).put((byte) 1).put((byte) 0x02).put((byte) 4); // version, versionlz, memcpy, typesize
        header.putInt(0x7FFFFFF0).putInt(0x7FFFFFF0).putInt(16);           // nbytes, blocksize, cbytes
        assertRefused(int32Chunk("{\"name\":\"blosc\",\"configuration\":{\"cname\":\"zstd\",\"clevel\":5,"
                + "\"shuffle\":\"shuffle\",\"typesize\":4,\"blocksize\":0}}"), header.array(), "claims 2147483632 bytes");
    }

    @Test
    void aBombInsideAShardStopsAtTheSubChunkSize() {
        ChunkPipeline pipeline = ChunkPipeline.of(DataType.INT32, new long[] {16}, List.of(spec(
                "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[8],"
                        + "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},{\"name\":\"gzip\"}],"
                        + "\"index_codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]}}")));
        byte[] bomb = gzipOfZeros(64L << 20);
        ByteBuffer shard = ByteBuffer.allocate(bomb.length + 32).order(ByteOrder.LITTLE_ENDIAN);
        shard.put(bomb);
        shard.putLong(0).putLong(bomb.length).putLong(-1).putLong(-1); // sub-chunk 0 is the bomb; 1 is empty
        assertRefused(pipeline, shard.array(), "more than the 32 bytes");
    }

    @Test
    void aChainOfCompressorsBoundsEveryStage() {
        // gzip inside gzip: the outer stage may produce at most the bound of the inner one's encoded size.
        byte[] inner = gzipOfZeros(64L << 20);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bos)) {
            g.write(inner);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertRefused(int32Chunk("{\"name\":\"gzip\"}", "{\"name\":\"gzip\"}"), bos.toByteArray(), "more than");
    }

    // ---- H2: lengths and shard indexes that escaped as raw exceptions -------------------------------

    @Test
    void aVlenLengthThatOverflowsIsAFormatError() {
        ChunkPipeline pipeline = ChunkPipeline.of(DataType.STRING, new long[] {1},
                List.of(spec("{\"name\":\"vlen-utf8\"}")));
        // One element whose length, added to its offset (8), overflowed int: StringIndexOutOfBoundsException.
        byte[] stored = {1, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f};
        assertThrows(ZarrFormatException.class, () -> pipeline.decodeStrings(stored, 1));
    }

    /** A 2-sub-chunk shard of int32 with a plain (no checksum) index, so entries can be written directly. */
    private static final ChunkPipeline SHARD = ChunkPipeline.of(DataType.INT32, new long[] {4}, List.of(spec(
            "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[2],"
                    + "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}],"
                    + "\"index_codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]}}")));

    private static byte[] shardWithIndex(long offset0, long length0, long offset1, long length1) {
        ByteBuffer shard = ByteBuffer.allocate(16 + 32).order(ByteOrder.LITTLE_ENDIAN);
        shard.putInt(1).putInt(2).putInt(3).putInt(4); // 16 bytes of sub-chunk data
        shard.putLong(offset0).putLong(length0).putLong(offset1).putLong(length1);
        return shard.array();
    }

    @Test
    void shardIndexEntriesAreChecked() {
        assertArrayEquals(new byte[] {1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0},
                SHARD.decode(shardWithIndex(0, 8, 8, 8)));
        // An offset of 2^63 or more read as negative and reached readRange as an IllegalArgumentException.
        assertRefused(SHARD, shardWithIndex(Long.MIN_VALUE, 8, -1, -1), "not a valid byte range");
        // Offset + length overflowed.
        assertRefused(SHARD, shardWithIndex(Long.MAX_VALUE - 4, 8, -1, -1), "not a valid byte range");
        // Only one field all-ones: neither empty nor a range.
        assertRefused(SHARD, shardWithIndex(-1, 8, -1, -1), "not a valid byte range");
        assertRefused(SHARD, shardWithIndex(0, -1, -1, -1), "not a valid byte range");
        // A range past the end of the shard.
        assertRefused(SHARD, shardWithIndex(0, 8, 8, 4096), "truncated");
        // A shard shorter than its index.
        assertRefused(SHARD, new byte[] {1, 2, 3}, "smaller than its 32-byte index");
    }
}
