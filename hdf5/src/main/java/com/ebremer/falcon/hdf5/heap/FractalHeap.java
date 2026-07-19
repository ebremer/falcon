package com.ebremer.falcon.hdf5.heap;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Fractal heap (spec section III.G): dense storage for many small objects (e.g. a large group's links
 * or an object's attributes), referenced by "heap IDs".
 *
 * <p>A managed heap ID carries the object's logical offset within the heap's managed address space and
 * its length. When the heap is small its root is a single <b>direct block</b> ({@code "FHDB"}) and the
 * logical offset is a direct offset into it. When it grows, the root becomes an <b>indirect block</b>
 * ({@code "FHIB"}) holding a "doubling table" of direct-block pointers: rows 0 and 1 hold blocks of the
 * starting size, and each later row doubles it. This reader maps a logical offset through that table to
 * the containing direct block. Nested indirect blocks (only for truly huge heaps), filtered heaps, and
 * huge/tiny object ids remain a later increment.
 */
public final class FractalHeap {

    private static final byte[] FRHP = {'F', 'R', 'H', 'P'};
    private static final byte[] FHIB = {'F', 'H', 'I', 'B'};

    private final FileContext ctx;
    private final int idLength;
    private final int offsetSize;
    private final int lengthSize;
    private final long rootBlockAddress;
    private final int currentRows;
    private final int offsets;
    private final int tableWidth;
    private final long startBlockSize;
    private final int maxDirectRows;
    private final boolean filtered;

    private FractalHeap(FileContext ctx, int idLength, int offsetSize, int lengthSize, long rootBlockAddress,
                        int currentRows, int offsets, int tableWidth, long startBlockSize, int maxDirectRows,
                        boolean filtered) {
        this.ctx = ctx;
        this.idLength = idLength;
        this.offsetSize = offsetSize;
        this.lengthSize = lengthSize;
        this.rootBlockAddress = rootBlockAddress;
        this.currentRows = currentRows;
        this.offsets = offsets;
        this.tableWidth = tableWidth;
        this.startBlockSize = startBlockSize;
        this.maxDirectRows = maxDirectRows;
        this.filtered = filtered;
    }

    /** The heap ID length in bytes (heap IDs appear as fixed-width fields in v2 B-tree records). */
    public int idLength() {
        return idLength;
    }

    public static FractalHeap parse(FileContext ctx, long addr) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(addr, FRHP)) {
            throw new HdfFormatException("expected fractal heap signature 'FRHP' at " + addr);
        }
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        int idLength = buf.getUnsignedShort(addr + 5);
        int ioFilterLength = buf.getUnsignedShort(addr + 7);

        long p = addr + 10; // signature, version, heap-id length(2), io-filter length(2), flags(1)
        p += 4;             // maximum managed object size
        p += lengths;       // next huge object id
        p += offsets;       // v2 B-tree address of huge objects
        p += lengths;       // free space in managed blocks
        p += offsets;       // managed block free-space manager address
        p += lengths;       // managed space
        p += lengths;       // allocated managed space
        p += lengths;       // direct-block iterator offset
        p += lengths;       // number of managed objects
        p += lengths;       // huge object size
        p += lengths;       // number of huge objects
        p += lengths;       // tiny object size
        p += lengths;       // number of tiny objects
        int tableWidth = buf.getUnsignedShort(p);
        p += 2;
        long startBlockSize = buf.getUnsignedValue(p, lengths);
        p += lengths;
        long maxDirectBlockSize = buf.getUnsignedValue(p, lengths);
        p += lengths;
        int maxHeapBits = buf.getUnsignedShort(p);
        p += 2;
        p += 2;             // starting number of rows in the root indirect block
        long rootBlock = buf.getAddress(p, offsets);
        p += offsets;
        int currentRows = buf.getUnsignedShort(p);

        int offsetSize = (maxHeapBits + 7) / 8;
        int lengthSize = idLength - 1 - offsetSize;
        // Rows 0 and 1 use the starting block size; each later row doubles it, until the maximum direct
        // block size — beyond which rows hold indirect-block pointers instead.
        int maxDirectRows = (log2(maxDirectBlockSize) - log2(startBlockSize)) + 2;
        return new FractalHeap(ctx, idLength, offsetSize, lengthSize, rootBlock, currentRows,
                offsets, tableWidth, startBlockSize, maxDirectRows, ioFilterLength > 0);
    }

    /** The file address and length of a managed heap object. */
    public record HeapObject(long address, int length) {
    }

    /** Locates the managed object named by {@code heapId} (its file address and length). */
    public HeapObject locate(byte[] heapId) {
        int type = (heapId[0] >> 4) & 0x03;
        if (type != 0) {
            throw new HdfUnsupportedException("only managed fractal-heap objects are supported (id type " + type + ")");
        }
        if (filtered) {
            throw new HdfUnsupportedException("filtered fractal heaps are not yet supported");
        }
        long offset = readLittleEndian(heapId, 1, offsetSize);
        int length = (int) readLittleEndian(heapId, 1 + offsetSize, lengthSize);
        long address = currentRows == 0
                ? rootBlockAddress + offset
                : resolveManagedOffset(rootBlockAddress, currentRows, offset);
        return new HeapObject(address, length);
    }

    /** Reads the bytes of the managed object named by {@code heapId}. */
    public byte[] readObject(byte[] heapId) {
        HeapObject object = locate(heapId);
        return ctx.buffer().getBytes(object.address(), object.length());
    }

    /** Walks an indirect block's doubling table to map a logical offset to a physical object address. */
    private long resolveManagedOffset(long indirectBlock, int rows, long offset) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(indirectBlock, FHIB)) {
            throw new HdfFormatException("expected fractal heap indirect block 'FHIB' at " + indirectBlock);
        }
        long entry = indirectBlock + 5 + offsets + offsetSize; // signature, version, heap header, block offset
        long cursor = 0;
        for (int row = 0; row < rows; row++) {
            long blockSize = rowBlockSize(row);
            for (int col = 0; col < tableWidth; col++) {
                if (row >= maxDirectRows) {
                    throw new HdfUnsupportedException(
                            "nested indirect fractal-heap blocks (very large heaps) are not yet supported");
                }
                long blockAddress = buf.getAddress(entry, offsets);
                entry += offsets; // unfiltered direct-block pointer is just an address
                if (offset < cursor + blockSize) {
                    if (blockAddress == HdfBuffer.UNDEFINED_ADDRESS) {
                        throw new HdfFormatException("unallocated direct block for heap offset " + offset);
                    }
                    return blockAddress + (offset - cursor);
                }
                cursor += blockSize;
            }
        }
        throw new HdfFormatException("managed heap offset " + offset + " is out of range");
    }

    private long rowBlockSize(int row) {
        return row <= 1 ? startBlockSize : startBlockSize << (row - 1);
    }

    private static int log2(long value) {
        return 63 - Long.numberOfLeadingZeros(value);
    }

    private static long readLittleEndian(byte[] b, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v |= (long) (b[off + i] & 0xff) << (8 * i);
        }
        return v;
    }
}
