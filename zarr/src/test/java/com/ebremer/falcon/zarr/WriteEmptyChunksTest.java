package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ZarrArray#withWriteEmptyChunks(boolean)} (P2 F7), zarr-python's {@code write_empty_chunks}: a chunk
 * a write leaves holding only the fill value is stored rather than deleted, and so is each sub-chunk of a
 * shard the write touches. A plain handle still deletes it.
 */
class WriteEmptyChunksTest {

    private static List<String> chunkKeys(MemoryStore store) {
        return store.listPrefix("c/");
    }

    @Test
    void aPlainHandleDeletesAnAllFillChunkAndTheOptionStoresIt() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {6, 4}, DataType.INT32)
                .chunkShape(3, 2).fillValue(-1).build());
        assertFalse(a.writeEmptyChunks());

        int[] fill = new int[24];
        java.util.Arrays.fill(fill, -1);
        a.writeInts(fill);
        assertEquals(List.of(), chunkKeys(store));

        ZarrArray keeping = a.withWriteEmptyChunks(true);
        assertTrue(keeping.writeEmptyChunks());
        keeping.writeInts(fill);
        assertEquals(List.of("c/0/0", "c/0/1", "c/1/0", "c/1/1"), chunkKeys(store));
        assertArrayEquals(fill, Zarr.openArray(store).readInts());

        // A partial write that leaves a chunk all fill keeps it too.
        keeping.select(new long[] {0, 0}, new long[] {1, 1}).writeInts(new int[] {5});
        keeping.select(new long[] {0, 0}, new long[] {1, 1}).writeInts(new int[] {-1});
        assertTrue(store.exists("c/0/0"));

        // The plain handle deletes the stored all-fill chunks it rewrites.
        a.select(new long[] {0, 0}, new long[] {3, 2}).writeInts(new int[] {-1, -1, -1, -1, -1, -1});
        assertFalse(store.exists("c/0/0"));
        assertFalse(a.withWriteEmptyChunks(true).withWriteEmptyChunks(false).writeEmptyChunks());
    }

    /** The {@code (offset, length)} entries of a shard whose index (bytes + crc32c) is at its end. */
    private static long[] shardIndex(byte[] shard, int subChunks) {
        ByteBuffer b = ByteBuffer.wrap(shard, shard.length - 4 - subChunks * 16, subChunks * 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        long[] entries = new long[subChunks * 2];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = b.getLong();
        }
        return entries;
    }

    @Test
    void inAShardEveryTouchedSubChunkIsStored() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4, 4}, DataType.UINT8)
                .chunkShape(4, 4).sharding(2, 2).build()).withWriteEmptyChunks(true);

        // A whole shard of fill: all four sub-chunks stored.
        a.writeInts(new int[16]);
        long[] whole = shardIndex(store.get("c/0/0").orElseThrow(), 4);
        for (long entry : whole) {
            assertNotEquals(-1L, entry);
        }

        // A plain handle's partial write omits the sub-chunk it leaves all fill; the option's keeps it.
        ZarrArray plain = a.withWriteEmptyChunks(false);
        plain.select(new long[] {0, 0}, new long[] {2, 2}).writeInts(new int[4]);
        long[] afterPlain = shardIndex(store.get("c/0/0").orElseThrow(), 4);
        assertEquals(-1L, afterPlain[0]);
        assertEquals(-1L, afterPlain[1]);
        assertNotEquals(-1L, afterPlain[2]); // untouched sub-chunks keep their bytes

        a.select(new long[] {0, 0}, new long[] {2, 2}).writeInts(new int[4]);
        assertNotEquals(-1L, shardIndex(store.get("c/0/0").orElseThrow(), 4)[0]);
        assertArrayEquals(new int[16], Zarr.openArray(store).readInts());
    }

    @Test
    void stringAndByteArraysStoreAllFillChunksToo() {
        MemoryStore store = new MemoryStore();
        ZarrArray strings = Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.STRING)
                .chunkShape(2).build());
        strings.writeStrings(new String[] {"", "", "", ""});
        assertEquals(List.of(), chunkKeys(store));
        strings.withWriteEmptyChunks(true).writeStrings(new String[] {"", "", "", ""});
        assertEquals(List.of("c/0", "c/1"), chunkKeys(store));

        MemoryStore other = new MemoryStore();
        ZarrArray bytes = Zarr.createArray(other, ArraySpec.builder(new long[] {4}, DataType.BYTES)
                .chunkShape(4).sharding(2).build()).withWriteEmptyChunks(true);
        bytes.writeByteArrays(new byte[4][]);
        assertEquals(List.of("c/0"), chunkKeys(other));
        assertArrayEquals(new byte[][] {{}, {}, {}, {}}, bytes.readByteArrays());
    }

    @Test
    void theOptionCarriesOverToOtherHandles() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.INT8).chunkShape(2)
                .build()).withWriteEmptyChunks(true);
        assertTrue(a.withChunkCache(1 << 20).writeEmptyChunks());
        assertTrue(a.resize(6).writeEmptyChunks());
        assertFalse(Zarr.openArray(store).writeEmptyChunks()); // a newly opened handle has the default
    }
}
