package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Selections and sizes near the limits of {@code long} (P0 Z8).
 *
 * <p>{@code offset + shape} overflowed, so {@code select([Long.MAX_VALUE], [1])} passed the bounds check:
 * reads returned fill and writes stored a stray chunk key. Element and chunk counts wrapped, so a
 * {@code [2^32, 2^32]} array had {@code size() == 0} and {@code blocks()} yielded nothing.
 */
class BoundsTest {

    private static ZarrArray int8(MemoryStore store, long[] shape, long... chunks) {
        return Zarr.createArray(store, ArraySpec.builder(shape, DataType.INT8).chunkShape(chunks).build());
    }

    @Test
    void selectionsPastTheEndAreRefusedWithoutOverflow() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = int8(store, new long[] {10}, 4);
        assertThrows(IndexOutOfBoundsException.class, () -> a.select(new long[] {Long.MAX_VALUE}, new long[] {1}));
        assertThrows(IndexOutOfBoundsException.class, () -> a.select(new long[] {5}, new long[] {Long.MAX_VALUE}));
        assertThrows(IndexOutOfBoundsException.class, () -> a.select(new long[] {11}, new long[] {0}));
        assertThrows(IndexOutOfBoundsException.class, () -> a.select(new long[] {8}, new long[] {3}));
        assertThrows(IndexOutOfBoundsException.class, () -> a.select(new long[] {-1}, new long[] {1}));
        assertEquals(0, a.select(new long[] {10}, new long[] {0}).elementCount()); // empty, at the end
        assertEquals(List.of("zarr.json"), store.list()); // no stray chunk key
    }

    @Test
    void anArrayNearLongMaxValueReadsAndWritesItsLastChunk() {
        // The last chunk starts at 9223372036854775000; its end, origin + 1000, overflows a long.
        MemoryStore store = new MemoryStore();
        ZarrArray a = int8(store, new long[] {Long.MAX_VALUE}, 1000);
        assertEquals(Long.MAX_VALUE, a.size());
        assertEquals(9223372036854776L, a.chunkCount());

        Selection tail = a.select(new long[] {Long.MAX_VALUE - 1005}, new long[] {1005}); // two chunks
        int[] values = new int[1005];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 100;
        }
        tail.writeInts(values);
        assertArrayEquals(values, tail.readInts());
        assertArrayEquals(new int[] {4, 5, 6}, a.select(new long[] {Long.MAX_VALUE - 1001}, new long[] {3}).readInts());
        assertEquals(List.of("c/9223372036854774", "c/9223372036854775", "zarr.json"), store.list());
    }

    @Test
    void hugeArraysCountElementsAndChunksExactly() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = int8(store, new long[] {1L << 31, 1L << 31}, 1024, 1024);
        assertEquals(1L << 62, a.size()); // was exact, but [2^32, 2^32] wrapped to 0
        assertEquals(1L << 42, a.chunkCount());
        Selection first = a.blocks().findFirst().orElseThrow();
        assertEquals(1024 * 1024, first.elementCount());

        Selection corner = a.select(new long[] {(1L << 31) - 1, (1L << 31) - 1}, new long[] {1, 1});
        corner.writeInts(new int[] {7});
        assertArrayEquals(new int[] {7}, corner.readInts());
        assertTrue(store.exists("c/2097151/2097151"));
    }

    @Test
    void anArrayOfMoreThanLongMaxValueElementsIsRefusedOnOpen() {
        MemoryStore store = new MemoryStore();
        store.set("zarr.json", ("{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[4294967296,4294967296],"
                + "\"data_type\":\"int8\",\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[1,1]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        ZarrUnsupportedException e = assertThrows(ZarrUnsupportedException.class, () -> Zarr.open(store));
        assertTrue(e.getMessage().contains("elements"), e.getMessage());
    }

    @Test
    void anEmptyDimensionMakesTheCountZeroEvenIfTheOthersOverflow() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = int8(store, new long[] {1L << 40, 1L << 40, 0}, 1, 1, 1);
        assertEquals(0, a.size());
        assertEquals(0, a.chunkCount());
        assertEquals(0, a.blocks().count());
        assertEquals(0, a.readInts().length);
    }
}
