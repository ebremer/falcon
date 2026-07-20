package com.ebremer.falcon.zarr;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class ZarrReadTest {

    private static final String BYTES_LE = "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]";

    private static String arrayJson(String shape, String dtype, String chunks, String fill, String codecs) {
        return "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":" + shape
                + ",\"data_type\":\"" + dtype + "\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + chunks + "}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},"
                + "\"fill_value\":" + fill + ",\"codecs\":" + codecs + "}";
    }

    private static void putRoot(Store store, String json) {
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] int32(ByteOrder order, int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(order);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    private static byte[] int16(ByteOrder order, int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 2).order(order);
        for (int v : values) {
            b.putShort((short) v);
        }
        return b.array();
    }

    private static byte[] float64(ByteOrder order, double... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 8).order(order);
        for (double v : values) {
            b.putDouble(v);
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

    // ---- 1-D, single and multiple chunks ----------------------------------------------------------

    @Test
    void readsWholeSingleChunkArray() {
        MemoryStore store = new MemoryStore();
        putRoot(store, arrayJson("[5]", "int32", "[5]", "0", BYTES_LE));
        store.set("c/0", int32(LITTLE_ENDIAN, 10, 20, 30, 40, 50));

        ZarrArray a = Zarr.openArray(store);
        assertArrayEquals(new int[] {10, 20, 30, 40, 50}, a.readInts());
        assertArrayEquals(new long[] {10, 20, 30, 40, 50}, a.readLongs());
        assertArrayEquals(new double[] {10, 20, 30, 40, 50}, a.readDoubles());
    }

    @Test
    void readsAcrossChunksAndSelections() {
        MemoryStore store = new MemoryStore();
        putRoot(store, arrayJson("[10]", "int32", "[4]", "0", BYTES_LE)); // grid = 3
        store.set("c/0", int32(LITTLE_ENDIAN, 0, 1, 2, 3));
        store.set("c/1", int32(LITTLE_ENDIAN, 4, 5, 6, 7));
        store.set("c/2", int32(LITTLE_ENDIAN, 8, 9, 0, 0)); // edge chunk: 2 valid + 2 overhang fill

        ZarrArray a = Zarr.openArray(store);
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, a.readInts());
        // a slab spanning chunk 0 and chunk 1
        assertArrayEquals(new int[] {3, 4, 5, 6}, a.select(new long[] {3}, new long[] {4}).readInts());
        assertArrayEquals(new int[] {2, 3, 4}, a.select(new long[] {2}, new long[] {3}).readInts());
        assertArrayEquals(new int[] {8, 9}, a.select(new long[] {8}, new long[] {2}).readInts());
    }

    @Test
    void missingChunksReadAsFill() {
        MemoryStore store = new MemoryStore();
        putRoot(store, arrayJson("[10]", "int32", "[4]", "7", BYTES_LE));
        store.set("c/0", int32(LITTLE_ENDIAN, 0, 1, 2, 3)); // chunks 1 and 2 absent -> fill 7
        ZarrArray a = Zarr.openArray(store);
        assertArrayEquals(new int[] {0, 1, 2, 3, 7, 7, 7, 7, 7, 7}, a.readInts());
    }

    // ---- 2-D ------------------------------------------------------------------------------------

    @Test
    void reads2dArrayAndCrossChunkSelection() {
        MemoryStore store = new MemoryStore();
        putRoot(store, arrayJson("[4,6]", "int32", "[2,3]", "0", BYTES_LE)); // grid 2x2
        store.set("c/0/0", int32(LITTLE_ENDIAN, 0, 1, 2, 6, 7, 8));
        store.set("c/0/1", int32(LITTLE_ENDIAN, 3, 4, 5, 9, 10, 11));
        store.set("c/1/0", int32(LITTLE_ENDIAN, 12, 13, 14, 18, 19, 20));
        store.set("c/1/1", int32(LITTLE_ENDIAN, 15, 16, 17, 21, 22, 23));

        ZarrArray a = Zarr.openArray(store);
        int[] all = a.readInts();
        int[] expected = new int[24];
        for (int i = 0; i < 24; i++) {
            expected[i] = i;
        }
        assertArrayEquals(expected, all);

        // rows 1..2, cols 2..4 -> touches all four chunks
        int[] slab = a.select(new long[] {1, 2}, new long[] {2, 3}).readInts();
        assertArrayEquals(new int[] {8, 9, 10, 14, 15, 16}, slab);
    }

    // ---- codecs and endianness ------------------------------------------------------------------

    @Test
    void readsGzipCompressedChunks() {
        MemoryStore store = new MemoryStore();
        String codecs = "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},"
                + "{\"name\":\"gzip\",\"configuration\":{\"level\":5}}]";
        putRoot(store, arrayJson("[6]", "int32", "[6]", "0", codecs));
        store.set("c/0", gzip(int32(LITTLE_ENDIAN, 1, 2, 3, 4, 5, 6)));

        ZarrArray a = Zarr.openArray(store);
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, a.readInts());
    }

    @Test
    void readsBigEndianAndFloats() {
        MemoryStore be = new MemoryStore();
        String codecs = "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"big\"}}]";
        putRoot(be, arrayJson("[3]", "int16", "[3]", "0", codecs));
        be.set("c/0", int16(BIG_ENDIAN, 1000, -2, 32767));
        assertArrayEquals(new int[] {1000, -2, 32767}, Zarr.openArray(be).readInts());

        MemoryStore f = new MemoryStore();
        putRoot(f, arrayJson("[4]", "float64", "[2]", "0", BYTES_LE));
        f.set("c/0", float64(LITTLE_ENDIAN, 1.5, 2.5));
        f.set("c/1", float64(LITTLE_ENDIAN, 3.5, 4.5));
        ZarrArray fa = Zarr.openArray(f);
        assertArrayEquals(new double[] {1.5, 2.5, 3.5, 4.5}, fa.readDoubles());
        assertArrayEquals(new double[] {2.5, 3.5}, fa.select(new long[] {1}, new long[] {2}).readDoubles());
    }

    // ---- scalar ---------------------------------------------------------------------------------

    @Test
    void readsScalarArray() {
        MemoryStore store = new MemoryStore();
        putRoot(store, arrayJson("[]", "int32", "[]", "0", BYTES_LE));
        store.set("c", int32(LITTLE_ENDIAN, 42));
        ZarrArray a = Zarr.openArray(store);
        assertEquals(1, a.size());
        assertArrayEquals(new int[] {42}, a.readInts());
    }

    // ---- touch-only-overlapping-chunks ----------------------------------------------------------

    @Test
    void selectionReadsOnlyOverlappingChunks() {
        RecordingStore store = new RecordingStore();
        putRoot(store, arrayJson("[10]", "int32", "[4]", "0", BYTES_LE));
        store.set("c/0", int32(LITTLE_ENDIAN, 0, 1, 2, 3));
        store.set("c/1", int32(LITTLE_ENDIAN, 4, 5, 6, 7));
        store.set("c/2", int32(LITTLE_ENDIAN, 8, 9, 0, 0));

        ZarrArray a = Zarr.openArray(store);
        store.gets.clear(); // ignore the metadata fetch
        int[] slab = a.select(new long[] {3}, new long[] {4}).readInts(); // indices 3..6 -> chunks 0,1
        assertArrayEquals(new int[] {3, 4, 5, 6}, slab);
        assertEquals(List.of("c/0", "c/1"), store.gets); // chunk 2 was never fetched
    }

    // ---- errors ---------------------------------------------------------------------------------

    @Test
    void selectionValidation() {
        MemoryStore store = new MemoryStore();
        putRoot(store, arrayJson("[10]", "int32", "[4]", "0", BYTES_LE));
        ZarrArray a = Zarr.openArray(store);
        assertThrows(IllegalArgumentException.class, () -> a.select(new long[] {0, 0}, new long[] {1, 1}));
        assertThrows(IndexOutOfBoundsException.class, () -> a.select(new long[] {8}, new long[] {5}));
        assertThrows(IndexOutOfBoundsException.class, () -> a.select(new long[] {-1}, new long[] {2}));
    }

    @Test
    void wrongTypedReaderIsRejected() {
        MemoryStore store = new MemoryStore();
        putRoot(store, arrayJson("[2]", "float64", "[2]", "0", BYTES_LE));
        store.set("c/0", float64(LITTLE_ENDIAN, 1.0, 2.0));
        ZarrArray a = Zarr.openArray(store);
        assertThrows(ZarrException.class, a::readInts); // float64 is not an integer type
    }

    /** A store that records the keys fetched via {@link #get}, to prove which chunks a read touches. */
    static final class RecordingStore implements Store {
        private final MemoryStore delegate = new MemoryStore();
        final List<String> gets = new ArrayList<>();

        @Override
        public Optional<byte[]> get(String key) {
            gets.add(key);
            return delegate.get(key);
        }

        @Override
        public Optional<byte[]> getRange(String key, long offset, long length) {
            gets.add(key);
            return delegate.getRange(key, offset, length);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public java.util.OptionalLong size(String key) {
            return delegate.size(key);
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
