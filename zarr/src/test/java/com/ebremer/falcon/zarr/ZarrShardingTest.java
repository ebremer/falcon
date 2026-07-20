package com.ebremer.falcon.zarr;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.CRC32C;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class ZarrShardingTest {

    private static final String INNER_BYTES = "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]";
    private static final String INDEX_CODECS =
            "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},{\"name\":\"crc32c\"}]";

    private static String shardedJson(String shape, String chunk, String sub, String fill,
                                      String innerCodecs, String indexLocation) {
        return "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":" + shape + ",\"data_type\":\"int32\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + chunk + "}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},"
                + "\"fill_value\":" + fill + ","
                + "\"codecs\":[{\"name\":\"sharding_indexed\",\"configuration\":{"
                + "\"chunk_shape\":" + sub + ","
                + "\"codecs\":" + innerCodecs + ","
                + "\"index_codecs\":" + INDEX_CODECS + ","
                + "\"index_location\":\"" + indexLocation + "\"}}]}";
    }

    private static void putRoot(Store store, String json) {
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] int32(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(LITTLE_ENDIAN);
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

    /**
     * Builds a shard from its encoded sub-chunk payloads ({@code null} marks an empty sub-chunk), with the
     * uint64 (offset, length) index encoded little-endian and followed by its CRC-32C.
     */
    private static byte[] buildShard(boolean indexAtStart, byte[]... subChunks) {
        int n = subChunks.length;
        int indexSize = n * 16 + 4;
        long[] offsets = new long[n];
        long[] lengths = new long[n];
        int cursor = indexAtStart ? indexSize : 0;
        int dataBytes = 0;
        for (int i = 0; i < n; i++) {
            if (subChunks[i] == null) {
                offsets[i] = -1L; // all ones: empty
                lengths[i] = -1L;
            } else {
                offsets[i] = cursor;
                lengths[i] = subChunks[i].length;
                cursor += subChunks[i].length;
                dataBytes += subChunks[i].length;
            }
        }
        byte[] shard = new byte[dataBytes + indexSize];
        for (int i = 0; i < n; i++) {
            if (subChunks[i] != null) {
                System.arraycopy(subChunks[i], 0, shard, (int) offsets[i], subChunks[i].length);
            }
        }
        ByteBuffer index = ByteBuffer.allocate(n * 16).order(LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            index.putLong(offsets[i]);
            index.putLong(lengths[i]);
        }
        byte[] indexBytes = index.array();
        int indexPos = indexAtStart ? 0 : dataBytes;
        System.arraycopy(indexBytes, 0, shard, indexPos, indexBytes.length);
        CRC32C crc = new CRC32C();
        crc.update(indexBytes);
        long v = crc.getValue();
        int c = indexPos + indexBytes.length;
        shard[c] = (byte) v;
        shard[c + 1] = (byte) (v >>> 8);
        shard[c + 2] = (byte) (v >>> 16);
        shard[c + 3] = (byte) (v >>> 24);
        return shard;
    }

    // ---- basic sharded reads ----------------------------------------------------------------------

    @Test
    void readsShardedArrayWithIndexAtEnd() {
        MemoryStore store = new MemoryStore();
        putRoot(store, shardedJson("[8]", "[8]", "[4]", "0", INNER_BYTES, "end"));
        store.set("c/0", buildShard(false, int32(0, 1, 2, 3), int32(4, 5, 6, 7)));
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, Zarr.openArray(store).readInts());
    }

    @Test
    void readsShardedArrayWithIndexAtStart() {
        MemoryStore store = new MemoryStore();
        putRoot(store, shardedJson("[8]", "[8]", "[4]", "0", INNER_BYTES, "start"));
        store.set("c/0", buildShard(true, int32(0, 1, 2, 3), int32(4, 5, 6, 7)));
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, Zarr.openArray(store).readInts());
    }

    @Test
    void emptySubChunkReadsAsFill() {
        MemoryStore store = new MemoryStore();
        putRoot(store, shardedJson("[8]", "[8]", "[4]", "99", INNER_BYTES, "end"));
        store.set("c/0", buildShard(false, int32(0, 1, 2, 3), null)); // second sub-chunk empty
        assertArrayEquals(new int[] {0, 1, 2, 3, 99, 99, 99, 99}, Zarr.openArray(store).readInts());
    }

    @Test
    void missingShardReadsAsFill() {
        MemoryStore store = new MemoryStore();
        putRoot(store, shardedJson("[8]", "[8]", "[4]", "5", INNER_BYTES, "end"));
        assertArrayEquals(new int[] {5, 5, 5, 5, 5, 5, 5, 5}, Zarr.openArray(store).readInts());
    }

    @Test
    void innerCodecsAreAppliedPerSubChunk() {
        MemoryStore store = new MemoryStore();
        String inner = "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},"
                + "{\"name\":\"gzip\",\"configuration\":{\"level\":5}}]";
        putRoot(store, shardedJson("[8]", "[8]", "[4]", "0", inner, "end"));
        store.set("c/0", buildShard(false, gzip(int32(0, 1, 2, 3)), gzip(int32(4, 5, 6, 7))));
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, Zarr.openArray(store).readInts());
    }

    @Test
    void readsTwoDimensionalShard() {
        MemoryStore store = new MemoryStore();
        putRoot(store, shardedJson("[4,4]", "[4,4]", "[2,2]", "0", INNER_BYTES, "end"));
        // sub-chunks in C order over the 2x2 sub-grid
        store.set("c/0/0", buildShard(false,
                int32(0, 1, 4, 5), int32(2, 3, 6, 7), int32(8, 9, 12, 13), int32(10, 11, 14, 15)));

        int[] expected = new int[16];
        for (int i = 0; i < 16; i++) {
            expected[i] = i;
        }
        ZarrArray a = Zarr.openArray(store);
        assertArrayEquals(expected, a.readInts());
        assertArrayEquals(new int[] {5, 6, 9, 10}, a.select(new long[] {1, 1}, new long[] {2, 2}).readInts());
    }

    @Test
    void multipleShardsAcrossTheGrid() {
        MemoryStore store = new MemoryStore();
        putRoot(store, shardedJson("[16]", "[8]", "[4]", "0", INNER_BYTES, "end")); // 2 shards
        store.set("c/0", buildShard(false, int32(0, 1, 2, 3), int32(4, 5, 6, 7)));
        store.set("c/1", buildShard(false, int32(8, 9, 10, 11), int32(12, 13, 14, 15)));
        int[] expected = new int[16];
        for (int i = 0; i < 16; i++) {
            expected[i] = i;
        }
        assertArrayEquals(expected, Zarr.openArray(store).readInts());
    }

    // ---- byte-range efficiency --------------------------------------------------------------------

    @Test
    void selectionFetchesOnlyTheNeededSubChunks() {
        RangeRecordingStore store = new RangeRecordingStore();
        putRoot(store, shardedJson("[8]", "[8]", "[4]", "0", INNER_BYTES, "end"));
        // 32 bytes of data then a 36-byte index: sub-chunk 0 at 0+16, sub-chunk 1 at 16+16.
        store.set("c/0", buildShard(false, int32(0, 1, 2, 3), int32(4, 5, 6, 7)));

        ZarrArray a = Zarr.openArray(store);
        store.reads.clear();
        assertArrayEquals(new int[] {0, 1, 2, 3}, a.select(new long[] {0}, new long[] {4}).readInts());

        assertTrue(store.reads.contains("c/0:32+36"), "should read the shard index: " + store.reads);
        assertTrue(store.reads.contains("c/0:0+16"), "should read sub-chunk 0: " + store.reads);
        assertFalse(store.reads.contains("c/0:16+16"), "must not read sub-chunk 1: " + store.reads);
        assertFalse(store.reads.contains("c/0:all"), "must not read the whole shard: " + store.reads);
    }

    // ---- validation -------------------------------------------------------------------------------

    @Test
    void subChunkShapeMustDivideTheOuterChunk() {
        MemoryStore store = new MemoryStore();
        putRoot(store, shardedJson("[8]", "[8]", "[3]", "0", INNER_BYTES, "end"));
        ZarrArray a = Zarr.openArray(store); // opening is fine; the pipeline is built lazily
        assertThrows(ZarrFormatException.class, a::readInts);
    }

    @Test
    void corruptShardIndexIsRejected() {
        MemoryStore store = new MemoryStore();
        putRoot(store, shardedJson("[8]", "[8]", "[4]", "0", INNER_BYTES, "end"));
        byte[] shard = buildShard(false, int32(0, 1, 2, 3), int32(4, 5, 6, 7));
        shard[shard.length - 6] ^= 0xff; // corrupt the index, not its checksum
        store.set("c/0", shard);
        assertThrows(ZarrFormatException.class, () -> Zarr.openArray(store).readInts());
    }

    /** Records every read as {@code key:all} or {@code key:offset+length}. */
    static final class RangeRecordingStore implements Store {
        private final MemoryStore delegate = new MemoryStore();
        final List<String> reads = new ArrayList<>();

        @Override
        public Optional<byte[]> get(String key) {
            reads.add(key + ":all");
            return delegate.get(key);
        }

        @Override
        public Optional<byte[]> getRange(String key, long offset, long length) {
            reads.add(key + ":" + offset + "+" + length);
            return delegate.getRange(key, offset, length);
        }

        @Override
        public OptionalLong size(String key) {
            return delegate.size(key);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public List<String> list() {
            return delegate.list();
        }

        @Override
        public List<String> listPrefix(String prefix) {
            return delegate.listPrefix(prefix);
        }

        @Override
        public List<String> listDir(String prefix) {
            return delegate.listDir(prefix);
        }

        @Override
        public boolean isWritable() {
            return delegate.isWritable();
        }

        @Override
        public void set(String key, byte[] value) {
            delegate.set(key, value);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }
    }
}
