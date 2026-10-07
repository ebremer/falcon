package com.ebremer.falcon.hdf5.io;

import com.ebremer.falcon.hdf5.HdfException;
import com.ebremer.falcon.hdf5.HdfFormatException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * A random-access, little-endian reader over a file's bytes: a {@link MemorySegment} (a mapped file, or
 * bytes in memory), or a {@link PagedSource} that reads them on demand.
 *
 * <p>HDF5 stores all format metadata little-endian, so every multi-byte accessor here reads
 * little-endian (using <em>unaligned</em> layouts, since on-disk fields are not aligned). The class
 * offers both <em>absolute</em> accessors ({@code getX(offset)}) and a movable <em>cursor</em>
 * ({@code readX()} / {@link #position()}), plus variable-width unsigned reads for the format's
 * parameterized address ("size of offsets") and length ("size of lengths") fields.
 *
 * <p>Not thread-safe for cursor use: the absolute accessors are stateless, but {@code readX()} and
 * {@link #position(long)} mutate the shared cursor. Wrap or {@link #slice} per thread if needed.
 */
public final class HdfBuffer {

    /**
     * Canonical value returned for an HDF5 "undefined address" (all bits set for the address width).
     * Real file addresses are always non-negative and well below this, so {@code == UNDEFINED_ADDRESS}
     * is a safe test.
     */
    public static final long UNDEFINED_ADDRESS = -1L;

    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MemorySegment segment; // the bytes, when all are mapped or in memory; else null
    private final PagedSource paged;     // otherwise, where they are read from on demand
    private final long base;             // where this view starts in paged
    private final long size;
    private long position;

    /** Wraps a memory segment; the cursor starts at 0. */
    public HdfBuffer(MemorySegment segment) {
        this(segment, null, 0, segment.byteSize());
    }

    /** Reads the bytes of {@code source} as they are needed; the cursor starts at 0. */
    public HdfBuffer(PagedSource source) {
        this(null, source, 0, source.size());
    }

    private HdfBuffer(MemorySegment segment, PagedSource paged, long base, long size) {
        this.segment = segment;
        this.paged = paged;
        this.base = base;
        this.size = size;
    }

    /** Wraps a byte array (handy for tests and small in-memory metadata blocks). */
    public static HdfBuffer of(byte[] bytes) {
        return new HdfBuffer(MemorySegment.ofArray(bytes));
    }

    /** The number of readable bytes. */
    public long size() {
        return size;
    }

    // ------------------------------------------------------------------ cursor

    public long position() {
        return position;
    }

    public HdfBuffer position(long newPosition) {
        if (newPosition < 0 || newPosition > size) {
            throw new HdfFormatException("position out of bounds: " + newPosition + " (size " + size + ")");
        }
        this.position = newPosition;
        return this;
    }

    public HdfBuffer skip(long n) {
        return position(position + n);
    }

    // -------------------------------------------------------- absolute reads

    public byte getByte(long off) {
        checkRange(off, 1);
        return segment != null ? segment.get(ValueLayout.JAVA_BYTE, off) : paged.get(base + off);
    }

    public int getUnsignedByte(long off) {
        return getByte(off) & 0xff;
    }

    public short getShort(long off) {
        checkRange(off, 2);
        return segment != null ? segment.get(LE_SHORT, off) : paged.read(base + off, 2).get(LE_SHORT, 0);
    }

    public int getUnsignedShort(long off) {
        return getShort(off) & 0xffff;
    }

    public int getInt(long off) {
        checkRange(off, 4);
        return segment != null ? segment.get(LE_INT, off) : paged.read(base + off, 4).get(LE_INT, 0);
    }

    public long getUnsignedInt(long off) {
        return getInt(off) & 0xffff_ffffL;
    }

    public long getLong(long off) {
        checkRange(off, 8);
        return segment != null ? segment.get(LE_LONG, off) : paged.read(base + off, 8).get(LE_LONG, 0);
    }

    /**
     * Reads a little-endian unsigned integer {@code width} bytes wide ({@code 1..8}). For widths
     * {@code 1..7} the result is a non-negative long; for width 8 the raw 64-bit value is returned
     * (which may be negative if the top bit is set).
     */
    public long getUnsignedValue(long off, int width) {
        switch (width) {
            case 1: return getUnsignedByte(off);
            case 2: return getUnsignedShort(off);
            case 4: return getUnsignedInt(off);
            case 8: return getLong(off);
            default:
                if (width < 1 || width > 8) {
                    throw new HdfException("unsupported integer width: " + width);
                }
                long v = 0;
                for (int b = 0; b < width; b++) {
                    v |= (long) (getByte(off + b) & 0xff) << (8 * b);
                }
                return v;
        }
    }

    /**
     * Reads a file address {@code width} bytes wide, mapping the all-ones "undefined address" pattern
     * to {@link #UNDEFINED_ADDRESS}.
     */
    public long getAddress(long off, int width) {
        long v = getUnsignedValue(off, width);
        return isAllOnes(v, width) ? UNDEFINED_ADDRESS : v;
    }

    /** True if {@code value} has all bits set within a {@code width}-byte field. */
    public static boolean isAllOnes(long value, int width) {
        if (width >= 8) {
            return value == -1L;
        }
        long mask = (1L << (8 * width)) - 1L;
        return (value & mask) == mask;
    }

    /**
     * Copies {@code len} bytes starting at {@code off} into a fresh array, checking the range first: a corrupt
     * length (a link name's, up to 2 GB) fails as out of bounds instead of allocating it.
     */
    public byte[] getBytes(long off, int len) {
        checkRange(off, len);
        byte[] out = new byte[len];
        copyTo(off, out, 0, len);
        return out;
    }

    /** Copies {@code len} bytes starting at {@code off} into {@code destination} from {@code destinationOffset}. */
    public void copyTo(long off, byte[] destination, int destinationOffset, int len) {
        checkRange(off, len);
        if (segment != null) {
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, off, destination, destinationOffset, len);
        } else {
            paged.copy(base + off, destination, destinationOffset, len);
        }
    }

    /** True if the {@code sig.length} bytes at {@code off} equal {@code sig}. */
    public boolean hasSignature(long off, byte[] sig) {
        if (off < 0 || off > size - sig.length) {
            return false;
        }
        MemorySegment bytes = segmentSlice(off, sig.length);
        for (int k = 0; k < sig.length; k++) {
            if (bytes.get(ValueLayout.JAVA_BYTE, k) != sig[k]) {
                return false;
            }
        }
        return true;
    }

    /** A view over {@code len} bytes starting at {@code off}; shares storage, has its own cursor. */
    public HdfBuffer slice(long off, long len) {
        checkRange(off, len);
        return segment != null ? new HdfBuffer(segment.asSlice(off, len)) : new HdfBuffer(null, paged, base + off, len);
    }

    /**
     * The {@code len} bytes at {@code off} as a segment that starts there (throws
     * {@link HdfFormatException} if out of range): a view of the mapped or in-memory bytes, or the bytes
     * read from a paged source.
     */
    public MemorySegment segmentSlice(long off, long len) {
        checkRange(off, len);
        return segment != null ? segment.asSlice(off, len) : paged.read(base + off, len);
    }

    // ---------------------------------------------------------- cursor reads

    public byte readByte() {
        byte v = getByte(position);
        position += 1;
        return v;
    }

    public int readUnsignedByte() {
        return readByte() & 0xff;
    }

    public short readShort() {
        short v = getShort(position);
        position += 2;
        return v;
    }

    public int readUnsignedShort() {
        return readShort() & 0xffff;
    }

    public int readInt() {
        int v = getInt(position);
        position += 4;
        return v;
    }

    public long readUnsignedInt() {
        return readInt() & 0xffff_ffffL;
    }

    public long readLong() {
        long v = getLong(position);
        position += 8;
        return v;
    }

    public long readUnsignedValue(int width) {
        long v = getUnsignedValue(position, width);
        position += width;
        return v;
    }

    public long readAddress(int width) {
        long v = getAddress(position, width);
        position += width;
        return v;
    }

    public byte[] readBytes(int len) {
        byte[] v = getBytes(position, len);
        position += len;
        return v;
    }

    private void checkRange(long off, long len) {
        // Overflow-safe: never compute off+len (a corrupt address near Long.MAX would wrap negative).
        if (off < 0 || len < 0 || off > size || len > size - off) {
            throw new HdfFormatException(
                    "read out of bounds: offset=" + off + " length=" + len + " size=" + size);
        }
    }
}
