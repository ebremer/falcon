package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code variable_length_bytes} data type and its {@code vlen-bytes} codec (P2 F5): byte strings of any
 * length, as zarr-python 3 writes for {@code dtype=bytes}. zarr-python's own arrays are read in
 * {@link DataFixturesTest}; what Falcon writes, zarr-python reads ({@code check_zarr_writer.py}).
 */
class BytesArrayTest {

    /** Byte strings of 0 to 8 bytes, zeros and high bytes included. */
    static byte[] value(int i) {
        byte[] b = new byte[i % 9];
        for (int j = 0; j < b.length; j++) {
            b[j] = (byte) (i * 31 + j * 7);
        }
        return b;
    }

    static byte[][] values(int n) {
        byte[][] out = new byte[n][];
        for (int i = 0; i < n; i++) {
            out[i] = value(i);
        }
        return out;
    }

    private static ZarrArray create(MemoryStore store, long[] shape, long... chunks) {
        return Zarr.createArray(store, ArraySpec.builder(shape, DataType.BYTES).chunkShape(chunks).build());
    }

    @Test
    void theDataTypeIsNamedAsZarrPythonNamesIt() {
        assertEquals("variable_length_bytes", DataType.BYTES.name());
        assertEquals(DataTypeKind.BYTES, DataType.BYTES.kind());
        assertTrue(DataType.BYTES.isVariableLength());
        assertEquals(DataType.BYTES, DataType.of("variable_length_bytes"));
        assertEquals(DataType.BYTES, DataType.of("bytes")); // zarr-python reads the short name too
    }

    @Test
    void createWritesTheVlenBytesCodecAndAnEmptyFill() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = create(store, new long[] {4}, 2);
        assertEquals(List.of("vlen-bytes"), a.codecNames());
        assertEquals(new JsonString(""), a.fillValue());
        assertEquals("variable_length_bytes", Json.parse(store.get("zarr.json").orElseThrow()).asObject()
                .get("data_type").asString());
        byte[][] none = a.readByteArrays();
        assertEquals(4, none.length);
        for (byte[] b : none) {
            assertArrayEquals(new byte[0], b);
        }
    }

    @Test
    void roundTripsAcrossChunksInTwoDimensions() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = create(store, new long[] {7, 5}, 3, 2);
        byte[][] v = values(35);
        a.writeByteArrays(v);
        ZarrArray reopened = Zarr.openArray(store);
        assertArrayEquals(v, reopened.readByteArrays());
        // A region straddling chunk boundaries.
        assertArrayEquals(new byte[][] {v[6], v[7], v[8], v[11], v[12], v[13]},
                reopened.select(new long[] {1, 1}, new long[] {2, 3}).readByteArrays());
    }

    @Test
    void aNullIsWrittenAsEmpty() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = create(store, new long[] {3}, 3);
        a.writeByteArrays(new byte[][] {{1, 2}, null, {3}});
        assertArrayEquals(new byte[][] {{1, 2}, {}, {3}}, a.readByteArrays());
    }

    @Test
    void aFillValueIsBase64AndPartialWritesKeepTheRest() {
        MemoryStore store = new MemoryStore();
        byte[] fill = {0, (byte) 0xff, '?'};
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {6}, DataType.BYTES).chunkShape(4)
                .fillValue(new JsonString(Base64.getEncoder().encodeToString(fill))).build());
        a.select(new long[] {1}, new long[] {2}).writeByteArrays(new byte[][] {{9}, {}});
        assertArrayEquals(new byte[][] {fill, {9}, {}, fill, fill, fill}, Zarr.openArray(store).readByteArrays());
        // Writing the fill value back over every element deletes the chunk.
        a.select(new long[] {0}, new long[] {4}).writeByteArrays(new byte[][] {fill, fill, fill, fill});
        assertFalse(store.exists("c/0"));
    }

    @Test
    void eachReturnedByteArrayIsTheCallersOwn() {
        MemoryStore store = new MemoryStore();
        byte[] fill = {7, 7};
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {4}, DataType.BYTES).chunkShape(2)
                .fillValue(new JsonString(Base64.getEncoder().encodeToString(fill))).build());
        byte[][] first = a.readByteArrays();
        assertNotSame(first[0], first[1]); // fill elements are not one shared array
        first[0][0] = 42;
        assertArrayEquals(fill, first[1]);
        assertArrayEquals(fill, a.readByteArrays()[0]);
    }

    @Test
    void shardedCompressedAndChecksummedRoundTrip() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {9, 6}, DataType.BYTES)
                .chunkShape(6, 4).sharding(3, 2).zstd().crc32c().build());
        byte[][] v = values(54);
        a.writeByteArrays(v);
        assertArrayEquals(v, Zarr.openArray(store).readByteArrays());
        assertArrayEquals(new byte[][] {v[20], v[21], v[26], v[27]},
                Zarr.openArray(store).select(new long[] {3, 2}, new long[] {2, 2}).readByteArrays());
    }

    @Test
    void transposeBeforeVlenBytesRoundTrips() {
        MemoryStore store = new MemoryStore();
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[3,4],\"data_type\":\"bytes\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[3,4]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":\"\",\"codecs\":["
                + "{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},{\"name\":\"vlen-bytes\"}]}";
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
        ZarrArray a = Zarr.openArray(store);
        assertEquals(DataType.BYTES, a.dataType()); // the short name opens
        byte[][] v = values(12);
        a.writeByteArrays(v);
        assertArrayEquals(v, Zarr.openArray(store).readByteArrays());
    }

    @Test
    void theWrongAccessorIsRefused() {
        MemoryStore store = new MemoryStore();
        ZarrArray bytes = create(store, new long[] {2}, 2);
        assertThrows(ZarrException.class, bytes::readStrings);
        assertThrows(ZarrException.class, () -> bytes.writeStrings(new String[] {"a", "b"}));
        ZarrException e = assertThrows(ZarrException.class, bytes::readDoubles);
        assertTrue(e.getMessage().contains("readByteArrays"), e.getMessage());
        assertThrows(ZarrException.class, () -> bytes.writeInts(new int[] {1, 2}));

        ZarrArray strings = Zarr.createArray(new MemoryStore(),
                ArraySpec.builder(new long[] {2}, DataType.STRING).build());
        assertThrows(ZarrException.class, strings::readByteArrays);
        ZarrArray ints = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {2}, DataType.INT32).build());
        assertThrows(ZarrException.class, ints::readByteArrays);
        assertThrows(IllegalArgumentException.class, () -> bytes.writeByteArrays(new byte[3][]));
    }

    @Test
    void mismatchedCodecsAndFillValuesAreFormatErrors() {
        String base = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[2],\"data_type\":\"%s\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[2]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":%s,\"codecs\":[{\"name\":\"%s\"}]}";
        for (String[] c : new String[][] {
            {"variable_length_bytes", "\"\"", "vlen-utf8"},  // the string codec on bytes
            {"string", "\"\"", "vlen-bytes"},                // the bytes codec on strings
            {"variable_length_bytes", "\"\"", "bytes"},      // a fixed-size codec
            {"variable_length_bytes", "\"not base64!\"", "vlen-bytes"},
            {"variable_length_bytes", "0", "vlen-bytes"},
        }) {
            MemoryStore store = new MemoryStore();
            store.set("zarr.json", String.format(base, c[0], c[1], c[2]).getBytes(StandardCharsets.UTF_8));
            assertThrows(ZarrFormatException.class, () -> {
                ZarrArray a = Zarr.openArray(store);
                if (a.dataType() == DataType.STRING) {
                    a.readStrings();
                } else {
                    a.readByteArrays();
                }
            }, String.join(" ", c));
        }
    }

    @Test
    void aCorruptChunkIsAFormatError() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = create(store, new long[] {2}, 2);
        store.set("c/0", new byte[] {2, 0, 0, 0, 5, 0, 0, 0, 1}); // two elements claimed, the first overruns
        assertThrows(ZarrFormatException.class, a::readByteArrays);
    }
}
