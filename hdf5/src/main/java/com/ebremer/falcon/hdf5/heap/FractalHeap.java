package com.ebremer.falcon.hdf5.heap;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Fractal heap (spec section III.G): dense storage for many small objects (e.g. a large group's links
 * or an object's attributes), referenced by "heap IDs".
 *
 * <p>Stage H5 reads heaps whose root is a single <b>direct block</b> (the common case for moderately
 * dense groups). A managed heap ID carries the object's absolute offset within the direct block and
 * its length. Heaps large enough to need <b>indirect blocks</b> (the doubling table) and huge/tiny
 * objects are a later increment.
 */
public final class FractalHeap {

    private static final byte[] FRHP = {'F', 'R', 'H', 'P'};

    private final FileContext ctx;
    private final int idLength;
    private final int offsetSize;
    private final int lengthSize;
    private final long rootBlockAddress;
    private final int currentRows;

    private FractalHeap(FileContext ctx, int idLength, int offsetSize, int lengthSize,
                        long rootBlockAddress, int currentRows) {
        this.ctx = ctx;
        this.idLength = idLength;
        this.offsetSize = offsetSize;
        this.lengthSize = lengthSize;
        this.rootBlockAddress = rootBlockAddress;
        this.currentRows = currentRows;
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
        p += 2;             // table width
        p += lengths;       // starting block size
        p += lengths;       // maximum direct block size
        int maxHeapBits = buf.getUnsignedShort(p);
        p += 2;
        p += 2;             // starting number of rows in the root indirect block
        long rootBlock = buf.getAddress(p, offsets);
        p += offsets;
        int currentRows = buf.getUnsignedShort(p);

        int offsetSize = (maxHeapBits + 7) / 8;
        int lengthSize = idLength - 1 - offsetSize;
        return new FractalHeap(ctx, idLength, offsetSize, lengthSize, rootBlock, currentRows);
    }

    /** Reads the bytes of the managed object named by {@code heapId}. */
    public byte[] readObject(byte[] heapId) {
        int type = (heapId[0] >> 4) & 0x03;
        if (type != 0) {
            throw new HdfUnsupportedException("only managed fractal-heap objects are supported (id type " + type + ")");
        }
        if (currentRows != 0) {
            throw new HdfUnsupportedException(
                    "fractal heaps with indirect blocks (very large groups/attribute sets) are not yet supported");
        }
        long offset = readLittleEndian(heapId, 1, offsetSize);
        int length = (int) readLittleEndian(heapId, 1 + offsetSize, lengthSize);
        return ctx.buffer().getBytes(rootBlockAddress + offset, length);
    }

    private static long readLittleEndian(byte[] b, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v |= (long) (b[off + i] & 0xff) << (8 * i);
        }
        return v;
    }
}
