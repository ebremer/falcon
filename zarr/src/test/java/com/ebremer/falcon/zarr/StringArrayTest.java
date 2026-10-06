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

    /**
     * Sharded string arrays (P1 I2): zarr-python writes them with {@code vlen-utf8} inside the shard and no
     * outer string codec, which made both reads and writes throw a raw IllegalStateException. Falcon now
     * reads and writes them, and a partial read fetches only the sub-chunks it needs.
     */
    @Test
    void shardedStringsRoundTrip() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4, 6}, DataType.STRING)
                .chunkShape(4, 6).sharding(2, 3).zstd().fillValue(new JsonString("-")).build());
        String[] values = new String[24];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 7 == 0 ? "-" : WORDS[i % WORDS.length] + i;
        }
        a.writeStrings(values);
        ZarrArray reopened = Zarr.openArray(store);
        assertArrayEquals(values, reopened.readStrings());
        assertArrayEquals(new String[] {values[9], values[10], values[15], values[16]},
                reopened.select(new long[] {1, 3}, new long[] {2, 2}).readStrings());

        reopened.select(new long[] {2, 0}, new long[] {2, 3}).writeStrings(new String[] {"-", "-", "-", "-", "-", "-"});
        String[] expected = values.clone();
        for (int r = 2; r < 4; r++) {
            for (int c = 0; c < 3; c++) {
                expected[r * 6 + c] = "-";
            }
        }
        assertArrayEquals(expected, Zarr.openArray(store).readStrings());
    }

    /**
     * {@code transpose} before {@code vlen-utf8} (P1 I4), the ordering zarr-python writes, was refused as a
     * format error, while an invalid transpose after it was accepted and ignored. The first now works and
     * the second is refused.
     */
    @Test
    void transposeBeforeVlenUtf8RoundTripsAndAfterItIsRefused() {
        String codecs = "[{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},"
                + "{\"name\":\"vlen-utf8\"},{\"name\":\"gzip\"}]";
        MemoryStore store = new MemoryStore();
        store.set("zarr.json", stringArrayJson("[3,4]", "[2,4]", codecs).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] values = new String[12];
        for (int i = 0; i < values.length; i++) {
            values[i] = "v" + i;
        }
        Zarr.openArray(store).writeStrings(values);
        assertArrayEquals(values, Zarr.openArray(store).readStrings());
        assertArrayEquals(new String[] {"v5", "v6", "v9", "v10"},
                Zarr.openArray(store).select(new long[] {1, 1}, new long[] {2, 2}).readStrings());

        MemoryStore bad = new MemoryStore();
        bad.set("zarr.json", stringArrayJson("[3,4]", "[2,4]",
                "[{\"name\":\"vlen-utf8\"},{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}}]")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(ZarrFormatException.class, () -> Zarr.openArray(bad).readStrings());
    }

    private static String stringArrayJson(String shape, String chunks, String codecs) {
        return "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":" + shape + ",\"data_type\":\"string\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + chunks + "}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":\"\",\"codecs\":" + codecs + "}";
    }
}
