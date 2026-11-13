package com.ebremer.falcon.hdf5.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.RangeReader;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * A buffer with ranges read from elsewhere in it ({@link HdfBuffer#redirected}), as a file with a metadata
 * cache image is read: every accessor must give the bytes a copy of the file with the ranges written in
 * place would, whether the file's bytes are a segment or read through pages.
 */
class RedirectedBufferTest {

    private static final int SIZE = 4096;

    // Ranges that touch (100-150, 150-160), one of a single byte, one at the file's start and one at its end.
    private static final long[] ADDRESSES = {0, 100, 150, 400, 1000, 4090};
    private static final long[] LENGTHS = {8, 50, 10, 1, 300, 6};
    private static final long[] SOURCES = {3000, 3100, 3500, 3999, 2000, 2500};

    private static byte[] stored() {
        byte[] bytes = new byte[SIZE];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    /** The stored bytes with each range copied to its address: what the redirected buffer must read as. */
    private static byte[] expected() {
        byte[] stored = stored();
        byte[] bytes = stored.clone();
        for (int i = 0; i < ADDRESSES.length; i++) {
            System.arraycopy(stored, (int) SOURCES[i], bytes, (int) ADDRESSES[i], (int) LENGTHS[i]);
        }
        return bytes;
    }

    /** The redirected buffer over the stored bytes: a segment for a page size of 0, else read in such pages. */
    private static HdfBuffer redirected(int pageSize) throws IOException {
        byte[] stored = stored();
        HdfBuffer file = pageSize == 0 ? HdfBuffer.of(stored) : new HdfBuffer(PagedSource.open(new RangeReader() {
            @Override
            public long size() {
                return stored.length;
            }

            @Override
            public void read(long position, ByteBuffer destination) {
                destination.put(stored, (int) position, destination.remaining());
            }
        }, pageSize, 4L * pageSize));
        return file.redirected(ADDRESSES, LENGTHS, SOURCES);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7, 64, 4096, 1 << 16})
    void everyReadIsAsIfTheRangesWereInPlace(int pageSize) throws IOException {
        HdfBuffer buffer = redirected(pageSize);
        byte[] expected = expected();
        ByteBuffer le = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(SIZE, buffer.size());
        assertArrayEquals(expected, buffer.getBytes(0, SIZE), "the whole file");
        for (int at = 0; at < SIZE; at++) {
            assertEquals(expected[at], buffer.getByte(at), "byte " + at);
        }
        // Every read that starts within 12 bytes of an edge of a range, of every length that crosses it.
        for (int i = 0; i < ADDRESSES.length; i++) {
            for (long edge : new long[] {ADDRESSES[i], ADDRESSES[i] + LENGTHS[i]}) {
                for (int at = (int) Math.max(0, edge - 12); at <= Math.min(SIZE - 8, edge + 12); at++) {
                    assertEquals(le.getShort(at), buffer.getShort(at), "short at " + at);
                    assertEquals(le.getInt(at), buffer.getInt(at), "int at " + at);
                    assertEquals(le.getLong(at), buffer.getLong(at), "long at " + at);
                    for (int width = 1; width <= 8; width++) {
                        long value = 0;
                        for (int b = width - 1; b >= 0; b--) {
                            value = value << 8 | expected[at + b] & 0xff;
                        }
                        assertEquals(value, buffer.getUnsignedValue(at, width), width + " bytes at " + at);
                    }
                    for (int length : new int[] {0, 1, 5, 13, 60, 400}) {
                        int n = Math.min(length, SIZE - at);
                        byte[] want = Arrays.copyOfRange(expected, at, at + n);
                        assertArrayEquals(want, buffer.getBytes(at, n), n + " bytes at " + at);
                        assertArrayEquals(want, buffer.segmentSlice(at, n).toArray(ValueLayout.JAVA_BYTE), "segment at " + at);
                        byte[] into = new byte[n + 4];
                        buffer.copyTo(at, into, 2, n);
                        assertArrayEquals(want, Arrays.copyOfRange(into, 2, 2 + n), "copy at " + at);
                        assertEquals(0, into[0] | into[1] | into[n + 2] | into[n + 3], "copy at " + at + " stays in its range");
                        HdfBuffer view = buffer.slice(at, n);
                        assertEquals(n, view.size());
                        assertArrayEquals(want, view.getBytes(0, n), "slice at " + at);
                        if (n > 2) { // a view of a view, and its cursor
                            assertArrayEquals(Arrays.copyOfRange(want, 1, n - 1), view.slice(1, n - 2).readBytes(n - 2));
                        }
                    }
                }
            }
        }
        Random random = new Random(7);
        for (int k = 0; k < 2000; k++) {
            int at = random.nextInt(SIZE);
            int n = random.nextInt(SIZE - at + 1);
            assertArrayEquals(Arrays.copyOfRange(expected, at, at + n), buffer.getBytes(at, n), n + " bytes at " + at);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 64})
    void signaturesAreFoundWhereTheRangesPutThem(int pageSize) throws IOException {
        HdfBuffer buffer = redirected(pageSize);
        byte[] expected = expected();
        assertTrue(buffer.hasSignature(100, Arrays.copyOfRange(expected, 100, 104)));
        assertTrue(buffer.hasSignature(148, Arrays.copyOfRange(expected, 148, 152)), "across two ranges");
        assertTrue(buffer.hasSignature(SIZE - 4, Arrays.copyOfRange(expected, SIZE - 4, SIZE)));
        assertFalse(buffer.hasSignature(100, Arrays.copyOfRange(stored(), 100, 104)), "the bytes stored there are hidden");
        assertFalse(buffer.hasSignature(SIZE - 3, new byte[4]), "past the end");
        assertFalse(buffer.hasSignature(-1, new byte[4]));
    }

    @Test
    void readsOutsideTheFileFailAsTheyDid() throws IOException {
        HdfBuffer buffer = redirected(0);
        assertThrows(HdfFormatException.class, () -> buffer.getByte(SIZE));
        assertThrows(HdfFormatException.class, () -> buffer.getBytes(SIZE - 3, 4));
        assertThrows(HdfFormatException.class, () -> buffer.getLong(-1));
        assertThrows(HdfFormatException.class, () -> buffer.segmentSlice(4000, Long.MAX_VALUE));
    }

    /** A read that lies in one range, or in none, is a view of the stored bytes: nothing is copied. */
    @Test
    void readsWithinARangeAreViewsOfTheStoredBytes() {
        byte[] stored = stored();
        MemorySegment whole = MemorySegment.ofArray(stored);
        HdfBuffer buffer = new HdfBuffer(whole).redirected(ADDRESSES, LENGTHS, SOURCES);
        assertSame(stored, buffer.segmentSlice(1000, 300).heapBase().orElseThrow(), "all of a range");
        assertEquals(2000, buffer.segmentSlice(1000, 300).address() - whole.address());
        assertSame(stored, buffer.segmentSlice(500, 400).heapBase().orElseThrow(), "between two ranges");
        assertEquals(500, buffer.segmentSlice(500, 400).address() - whole.address());
        assertEquals(2010, buffer.segmentSlice(1010, 20).address() - whole.address(), "inside a range");
        assertFalse(buffer.segmentSlice(990, 20).heapBase().filter(base -> base == stored).isPresent(), "across an edge: a copy");
    }

    /** The bytes stored at an address, whatever a range puts there: where a dataset's own data is read in place. */
    @Test
    void storedSlicesAreTheFilesOwnBytes() throws IOException {
        byte[] stored = stored();
        MemorySegment whole = MemorySegment.ofArray(stored);
        HdfBuffer buffer = new HdfBuffer(whole).redirected(ADDRESSES, LENGTHS, SOURCES);
        assertArrayEquals(stored, buffer.storedSlice(0, SIZE).toArray(ValueLayout.JAVA_BYTE), "the whole file, as stored");
        // In a range, across its edges, and between two: always a view, of the bytes that lie there.
        for (long[] range : new long[][] {{1000, 300}, {1010, 20}, {990, 20}, {1290, 20}, {500, 400}, {95, 70}, {0, 8}, {4090, 6}}) {
            MemorySegment slice = buffer.storedSlice(range[0], range[1]);
            assertSame(stored, slice.heapBase().orElseThrow(), "a view, at " + range[0]);
            assertEquals(range[0], slice.address() - whole.address(), "of the bytes at " + range[0]);
            assertEquals(range[1], slice.byteSize());
        }
        assertFalse(Arrays.equals(buffer.storedSlice(1000, 300).toArray(ValueLayout.JAVA_BYTE), buffer.getBytes(1000, 300)),
                "the range's bytes are read from elsewhere");
        // A view of part of the buffer is of the bytes stored in that part.
        HdfBuffer part = buffer.slice(900, 600);
        assertEquals(1000, part.storedSlice(100, 300).address() - whole.address());
        assertEquals(1000, new HdfBuffer(whole).slice(900, 600).storedSlice(100, 300).address() - whole.address(), "not redirected");
        assertThrows(HdfFormatException.class, () -> buffer.storedSlice(SIZE - 3, 4));
        assertThrows(HdfFormatException.class, () -> buffer.storedSlice(-1, 4));
        assertThrows(HdfFormatException.class, () -> part.storedSlice(590, 20));
        // Read on demand, there are no bytes to view; a range outside the file is refused first.
        for (int pageSize : new int[] {7, 4096}) {
            HdfBuffer paged = redirected(pageSize);
            assertNull(paged.storedSlice(1000, 300), "read in pages of " + pageSize);
            assertNull(paged.slice(900, 600).storedSlice(100, 300));
            assertThrows(HdfFormatException.class, () -> paged.storedSlice(SIZE - 3, 4));
        }
    }

    @Test
    void rangesMustBeAscendingApartAndInTheFile() {
        HdfBuffer file = HdfBuffer.of(new byte[100]);
        assertThrows(IllegalArgumentException.class, () -> file.redirected(new long[] {10, 5}, new long[] {2, 2}, new long[] {50, 60}));
        assertThrows(IllegalArgumentException.class, () -> file.redirected(new long[] {10, 11}, new long[] {2, 2}, new long[] {50, 60}));
        assertThrows(IllegalArgumentException.class, () -> file.redirected(new long[] {10}, new long[] {0}, new long[] {50}));
        assertThrows(IllegalArgumentException.class, () -> file.redirected(new long[] {99}, new long[] {2}, new long[] {50}));
        assertThrows(IllegalArgumentException.class, () -> file.redirected(new long[] {10}, new long[] {2}, new long[] {99}));
        assertThrows(IllegalArgumentException.class, () -> file.redirected(new long[] {10}, new long[] {2}, new long[] {-1}));
        assertThrows(IllegalArgumentException.class, () -> file.redirected(new long[] {-4}, new long[] {2}, new long[] {50}));
        assertThrows(IllegalArgumentException.class,
                () -> file.redirected(new long[] {10}, new long[] {Long.MAX_VALUE}, new long[] {50}));
        assertThrows(IllegalArgumentException.class, () -> file.redirected(new long[] {10, 20}, new long[] {2}, new long[] {50}));
        assertEquals(100, file.redirected(new long[0], new long[0], new long[0]).size(), "no range at all");
        assertEquals(100, file.redirected(new long[] {0, 50}, new long[] {50, 50}, new long[] {50, 0}).size());
    }
}
