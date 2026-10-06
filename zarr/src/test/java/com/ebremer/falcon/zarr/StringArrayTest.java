package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.store.MemoryStore;
import org.junit.jupiter.api.Test;

/** Variable-length UTF-8 {@code string} arrays: creation, round trips, partial writes, and compression. */
class StringArrayTest {

    private static final String[] WORDS = {"alpha", "", "gamma-δ", "中文", "emoji-😀", "x"};

    @Test
    void roundTripsWholeArray() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {6}, DataType.STRING).chunkShape(4).build());
        a.writeStrings(WORDS);
        assertArrayEquals(WORDS, Zarr.openArray(store).readStrings());
    }

    @Test
    void defaultFillIsEmptyString() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {4}, DataType.STRING).chunkShape(2).build());
        assertEquals(new JsonString(""), a.fillValue());
        // Nothing written: every element reads back as the fill value.
        assertArrayEquals(new String[] {"", "", "", ""}, a.readStrings());
    }

    @Test
    void roundTripsTwoDimensionalAcrossChunks() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {3, 4}, DataType.STRING).chunkShape(2, 2).build());
        String[] values = new String[12];
        for (int i = 0; i < 12; i++) {
            values[i] = WORDS[i % WORDS.length];
        }
        a.writeStrings(values);

        ZarrArray reopened = Zarr.openArray(store);
        assertArrayEquals(values, reopened.readStrings());
        // A sub-region that straddles the chunk boundary.
        assertArrayEquals(new String[] {values[5], values[6], values[9], values[10]},
                reopened.select(new long[] {1, 1}, new long[] {2, 2}).readStrings());
    }

    @Test
    void partialWriteReadsRestAsFill() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {6}, DataType.STRING).chunkShape(4)
                        .fillValue(new JsonString("?")).build());
        a.select(new long[] {1}, new long[] {2}).writeStrings(new String[] {"one", "two"});
        assertArrayEquals(new String[] {"?", "one", "two", "?", "?", "?"},
                Zarr.openArray(store).readStrings());
    }

    @Test
    void readModifyWritePreservesNeighbours() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {6}, DataType.STRING).chunkShape(4).build());
        a.writeStrings(WORDS);
        // Overwrite a single element inside the first chunk; the rest of that chunk must survive.
        a.select(new long[] {2}, new long[] {1}).writeStrings(new String[] {"REPLACED"});
        String[] want = WORDS.clone();
        want[2] = "REPLACED";
        assertArrayEquals(want, Zarr.openArray(store).readStrings());
    }

    @Test
    void allFillChunkIsDeleted() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {8}, DataType.STRING).chunkShape(4).build());
        a.writeStrings(new String[] {"a", "b", "c", "d", "e", "f", "g", "h"});
        assertTrue(store.exists("c/0"), "first chunk stored");
        // Rewrite the first chunk as all fill (""): it should be removed from the store.
        a.select(new long[] {0}, new long[] {4}).writeStrings(new String[] {"", "", "", ""});
        assertFalse(store.exists("c/0"), "all-fill chunk deleted");
        assertArrayEquals(new String[] {"", "", "", "", "e", "f", "g", "h"},
                Zarr.openArray(store).readStrings());
    }

    @Test
    void roundTripsWithZstd() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {20}, DataType.STRING).chunkShape(8).zstd().build());
        String[] values = new String[20];
        for (int i = 0; i < 20; i++) {
            values[i] = WORDS[i % WORDS.length] + i;
        }
        a.writeStrings(values);
        assertArrayEquals(values, Zarr.openArray(store).readStrings());
    }

    @Test
    void nullElementsWriteAsEmptyString() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {3}, DataType.STRING).chunkShape(3).build());
        a.writeStrings(new String[] {"a", null, "c"});
        assertArrayEquals(new String[] {"a", "", "c"}, Zarr.openArray(store).readStrings());
    }

    /**
     * A null is stored as "" (as numcodecs stores None). It used to count as the fill value when deciding
     * whether a chunk was all fill: with fill "zz", nulls in a mixed chunk read back as "", but a chunk of
     * nothing but nulls was deleted and read back as "zz" (P0 Z10).
     */
    @Test
    void nullIsTheEmptyStringWhateverTheChunkHolds() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.STRING)
                .chunkShape(2).fillValue(new JsonString("zz")).build());
        a.writeStrings(new String[] {"a", null, null, null});
        assertArrayEquals(new String[] {"a", "", "", ""}, Zarr.openArray(store).readStrings());
        assertTrue(store.exists("c/1")); // "" is not the fill, so the chunk is stored

        ZarrArray b = Zarr.createArray(new MemoryStore(),
                ArraySpec.builder(new long[] {2}, DataType.STRING).chunkShape(2).build());
        b.writeStrings(new String[] {"x", "y"});
        b.writeStrings(new String[] {null, null}); // with fill "", all nulls is all fill
        assertArrayEquals(new String[] {"", ""}, b.readStrings());
    }

    @Test
    void numericReadOnStringArrayIsRejected() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {3}, DataType.STRING).chunkShape(3).build());
        assertThrows(ZarrException.class, a::readDoubles);
    }

    @Test
    void stringReadOnNumericArrayIsRejected() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store,
                ArraySpec.builder(new long[] {3}, DataType.INT32).chunkShape(3).build());
        assertThrows(ZarrException.class, a::readStrings);
    }

    @Test
    void shardingIsRejectedForStrings() {
        assertThrows(IllegalArgumentException.class, () ->
                ArraySpec.builder(new long[] {8}, DataType.STRING).chunkShape(4).sharding(2).build());
    }
}
