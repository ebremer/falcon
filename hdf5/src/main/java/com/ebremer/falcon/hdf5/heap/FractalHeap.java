package com.ebremer.falcon.hdf5.heap;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.checksum.Lookup3;
import com.ebremer.falcon.hdf5.checksum.MetadataChecksum;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.HashSet;
import java.util.Set;

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
 *
 * <p>The header and indirect block are checksum-verified, as is each direct block when the heap's flags
 * say direct blocks are checksummed (the checksum covers the whole block with its checksum field
 * zeroed). A heap object must lie inside its direct block.
 */
public final class FractalHeap {

    private static final byte[] FRHP = {'F', 'R', 'H', 'P'};
    private static final byte[] FHIB = {'F', 'H', 'I', 'B'};
    private static final byte[] FHDB = {'F', 'H', 'D', 'B'};
    private static final int FLAG_DIRECT_BLOCKS_CHECKSUMMED = 0x02;

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
    private final boolean checksummedDirectBlocks;
    private final Set<Long> verifiedBlocks = new HashSet<>();
    private boolean indirectVerified;

    private FractalHeap(FileContext ctx, int idLength, int offsetSize, int lengthSize, long rootBlockAddress,
                        int currentRows, int offsets, int tableWidth, long startBlockSize, int maxDirectRows,
                        boolean filtered, boolean checksummedDirectBlocks) {
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
        this.checksummedDirectBlocks = checksummedDirectBlocks;
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
        int flags = buf.getUnsignedByte(addr + 9);

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
        p += 2;
        if (ioFilterLength > 0) {
            p += lengths + 4 + ioFilterLength; // filtered root direct block size, filter mask, filter info
        }
        MetadataChecksum.verify(buf, addr, p - addr, "fractal heap header");

        if (tableWidth == 0 || startBlockSize <= 0 || Long.bitCount(startBlockSize) != 1
                || maxDirectBlockSize < startBlockSize || Long.bitCount(maxDirectBlockSize) != 1
                || maxHeapBits < 1 || maxHeapBits > 64) {
            throw new HdfFormatException("invalid fractal heap parameters at " + addr);
        }
        int offsetSize = (maxHeapBits + 7) / 8;
        // libhdf5 (H5HF__hdr_finish_init_phase1): the length field is no wider than needed for an offset
        // within the largest direct block.
        int maxDirectOffsetSize = (log2(maxDirectBlockSize) + 7) / 8;
        int lengthSize = Math.min(maxDirectOffsetSize, idLength - 1 - offsetSize);
        if (lengthSize < 1) {
            throw new HdfFormatException("fractal heap id length " + idLength + " is too short at " + addr);
        }
        // Rows 0 and 1 use the starting block size; each later row doubles it, until the maximum direct
        // block size — beyond which rows hold indirect-block pointers instead.
        int maxDirectRows = (log2(maxDirectBlockSize) - log2(startBlockSize)) + 2;
        return new FractalHeap(ctx, idLength, offsetSize, lengthSize, rootBlock, currentRows,
                offsets, tableWidth, startBlockSize, maxDirectRows, ioFilterLength > 0,
                (flags & FLAG_DIRECT_BLOCKS_CHECKSUMMED) != 0);
    }

    /** The file address and length of a managed heap object. */
    public record HeapObject(long address, int length) {
    }

    /** Locates the managed object named by {@code heapId} (its file address and length). */
    public HeapObject locate(byte[] heapId) {
        if (heapId.length < 1 + offsetSize + lengthSize) {
            throw new HdfFormatException("fractal heap id of " + heapId.length + " bytes is too short");
        }
        int type = (heapId[0] >> 4) & 0x03;
        if (type != 0) {
            throw new HdfUnsupportedException("only managed fractal-heap objects are supported (id type " + type + ")");
        }
        if (filtered) {
            throw new HdfUnsupportedException("filtered fractal heaps are not yet supported");
        }
        long offset = readLittleEndian(heapId, 1, offsetSize);
        long length = readLittleEndian(heapId, 1 + offsetSize, lengthSize);
        Block block = currentRows == 0
                ? new Block(rootBlockAddress, 0, startBlockSize)
                : resolveManagedOffset(rootBlockAddress, currentRows, offset);
        long within = offset - block.heapOffset();
        if (within < 0 || length < 0 || length > block.size() - within) {
            throw new HdfFormatException("fractal heap object at offset " + offset + " (" + length
                    + " bytes) does not fit its direct block");
        }
        verifyDirectBlock(block);
        return new HeapObject(block.address() + within, (int) length);
    }

    /** Reads the bytes of the managed object named by {@code heapId}. */
    public byte[] readObject(byte[] heapId) {
        HeapObject object = locate(heapId);
        return ctx.buffer().getBytes(object.address(), object.length());
    }

    /** A direct block: its file address, the heap offset it starts at, and its size. */
    private record Block(long address, long heapOffset, long size) {
    }

    /** Walks an indirect block's doubling table to find the direct block holding a logical offset. */
    private Block resolveManagedOffset(long indirectBlock, int rows, long offset) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(indirectBlock, FHIB)) {
            throw new HdfFormatException("expected fractal heap indirect block 'FHIB' at " + indirectBlock);
        }
        long entries = indirectBlock + 5 + offsets + offsetSize; // signature, version, heap header, block offset
        if (!indirectVerified) {
            MetadataChecksum.verify(buf, indirectBlock, entries - indirectBlock + (long) rows * tableWidth * offsets,
                    "fractal heap indirect block");
            indirectVerified = true;
        }
        long entry = entries;
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
                    return new Block(blockAddress, cursor, blockSize);
                }
                cursor += blockSize;
            }
        }
        throw new HdfFormatException("managed heap offset " + offset + " is out of range");
    }

    /**
     * Checks a direct block's signature and, when the heap checksums direct blocks, its checksum: computed
     * over the whole block with the checksum field (the last field of the block's prefix) taken as zero.
     */
    private void verifyDirectBlock(Block block) {
        if (verifiedBlocks.contains(block.address())) {
            return;
        }
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(block.address(), FHDB)) {
            throw new HdfFormatException("expected fractal heap direct block 'FHDB' at " + block.address());
        }
        if (checksummedDirectBlocks) {
            if (block.size() > Integer.MAX_VALUE) {
                throw new HdfFormatException("fractal heap direct block too large: " + block.size());
            }
            byte[] image = buf.getBytes(block.address(), (int) block.size());
            int checksumAt = 5 + offsets + offsetSize;
            int stored = (image[checksumAt] & 0xff) | (image[checksumAt + 1] & 0xff) << 8
                    | (image[checksumAt + 2] & 0xff) << 16 | (image[checksumAt + 3] & 0xff) << 24;
            for (int i = 0; i < 4; i++) {
                image[checksumAt + i] = 0;
            }
            int computed = Lookup3.hashLittle(image);
            if (stored != computed) {
                throw new HdfFormatException(String.format("fractal heap direct block checksum mismatch at %d:"
                        + " stored=0x%08x computed=0x%08x", block.address(), stored, computed));
            }
        }
        verifiedBlocks.add(block.address());
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
