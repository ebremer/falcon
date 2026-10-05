package com.ebremer.falcon.hdf5.heap;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.btree.BTreeV2;
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
 * <p>A heap ID's type (bits 4&ndash;5 of its first byte) says where the object is:
 * <ul>
 *   <li><b>managed</b> (0): the ID carries the object's logical offset within the heap's managed address
 *       space and its length. When the heap is small its root is a single <b>direct block</b>
 *       ({@code "FHDB"}) and the logical offset is a direct offset into it. When it grows, the root
 *       becomes an <b>indirect block</b> ({@code "FHIB"}) holding a "doubling table": rows 0 and 1 hold
 *       blocks of the starting size and each later row doubles it; rows beyond the maximum direct-block
 *       size point at child indirect blocks, which nest the same way. A logical offset is mapped through
 *       the tables to its direct block.</li>
 *   <li><b>huge</b> (1): an object larger than the heap's maximum managed size, stored on its own in the
 *       file. A long enough ID holds its address and length directly; otherwise the ID holds a key into
 *       the heap's huge-object v2 B-tree (record type 1), which gives them.</li>
 *   <li><b>tiny</b> (2): an object so small it is stored inside the ID itself.</li>
 * </ul>
 * Filtered (compressed) heaps are not supported; libhdf5 creates none for groups or attributes.
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

    private static final int MAX_NESTING = 16; // indirect-block depth; a 64-bit heap needs far fewer

    private final FileContext ctx;
    private final int idLength;
    private final long hugeBTreeAddress;
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
    private final Set<Long> verifiedIndirectBlocks = new HashSet<>();

    private FractalHeap(FileContext ctx, int idLength, long hugeBTreeAddress, int offsetSize, int lengthSize,
                        long rootBlockAddress, int currentRows, int offsets, int tableWidth, long startBlockSize,
                        int maxDirectRows, boolean filtered, boolean checksummedDirectBlocks) {
        this.ctx = ctx;
        this.idLength = idLength;
        this.hugeBTreeAddress = hugeBTreeAddress;
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
        long hugeBTree = buf.getAddress(p, offsets);
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
        return new FractalHeap(ctx, idLength, hugeBTree, offsetSize, lengthSize, rootBlock, currentRows,
                offsets, tableWidth, startBlockSize, maxDirectRows, ioFilterLength > 0,
                (flags & FLAG_DIRECT_BLOCKS_CHECKSUMMED) != 0);
    }

    /** The file address and length of a heap object stored in the file (managed or huge). */
    public record HeapObject(long address, int length) {
    }

    private static final int MANAGED = 0;
    private static final int HUGE = 1;
    private static final int TINY = 2;

    /**
     * Locates the object named by {@code heapId} in the file (its address and length). A tiny object
     * lives in the ID itself, not the file; read it with {@link #readObject(byte[])}.
     */
    public HeapObject locate(byte[] heapId) {
        if (heapId.length < 1) {
            throw new HdfFormatException("empty fractal heap id");
        }
        if ((heapId[0] & 0xC0) != 0) {
            throw new HdfFormatException("unsupported fractal heap id version " + ((heapId[0] & 0xC0) >> 6));
        }
        int type = (heapId[0] >> 4) & 0x03;
        if (filtered) {
            throw new HdfUnsupportedException("filtered (compressed) fractal heaps are not supported");
        }
        return switch (type) {
            case MANAGED -> locateManaged(heapId);
            case HUGE -> locateHuge(heapId);
            case TINY -> throw new HdfUnsupportedException("a tiny fractal-heap object has no file address");
            default -> throw new HdfFormatException("invalid fractal heap id type " + type);
        };
    }

    /** Reads the bytes of the object named by {@code heapId} (managed, huge, or tiny). */
    public byte[] readObject(byte[] heapId) {
        if (heapId.length >= 1 && ((heapId[0] >> 4) & 0x03) == TINY && (heapId[0] & 0xC0) == 0) {
            // Tiny: the length (minus one) is in the low 4 bits, or 12 bits across two bytes when the ID
            // is longer than 18 bytes ("extended"); the object's bytes follow.
            boolean extended = idLength > 18;
            int length = (extended ? ((heapId[0] & 0x0F) << 8 | (heapId[1] & 0xFF)) : heapId[0] & 0x0F) + 1;
            int start = extended ? 2 : 1;
            if (start + length > heapId.length) {
                throw new HdfFormatException("tiny fractal heap object of " + length + " bytes overruns its id");
            }
            return java.util.Arrays.copyOfRange(heapId, start, start + length);
        }
        HeapObject object = locate(heapId);
        return ctx.buffer().getBytes(object.address(), object.length());
    }

    private HeapObject locateManaged(byte[] heapId) {
        if (heapId.length < 1 + offsetSize + lengthSize) {
            throw new HdfFormatException("fractal heap id of " + heapId.length + " bytes is too short");
        }
        long offset = readLittleEndian(heapId, 1, offsetSize);
        long length = readLittleEndian(heapId, 1 + offsetSize, lengthSize);
        Block block = currentRows == 0
                ? new Block(rootBlockAddress, 0, startBlockSize)
                : resolveManagedOffset(rootBlockAddress, currentRows, 0, offset, 0);
        long within = offset - block.heapOffset();
        if (within < 0 || length < 0 || length > block.size() - within) {
            throw new HdfFormatException("fractal heap object at offset " + offset + " (" + length
                    + " bytes) does not fit its direct block");
        }
        verifyDirectBlock(block);
        return new HeapObject(block.address() + within, (int) length);
    }

    /**
     * A huge object: its address and length are in the ID when it is long enough to hold them (libhdf5's
     * "directly accessed" huge objects); otherwise the ID holds a key looked up in the huge-object B-tree,
     * whose type-1 records are {@code address(O) · length(L) · key(L)}.
     */
    private HeapObject locateHuge(byte[] heapId) {
        int lengths = ctx.sizeOfLengths();
        long address;
        long length;
        if (idLength - 1 >= offsets + lengths) {
            address = readLittleEndian(heapId, 1, offsets);
            length = readLittleEndian(heapId, 1 + offsets, lengths);
        } else {
            int keySize = Math.min(idLength - 1, 8);
            long key = readLittleEndian(heapId, 1, keySize);
            if (hugeBTreeAddress == HdfBuffer.UNDEFINED_ADDRESS) {
                throw new HdfFormatException("huge fractal heap object " + key + " but the heap has no huge-object index");
            }
            address = HdfBuffer.UNDEFINED_ADDRESS;
            length = -1;
            for (byte[] record : BTreeV2.readRecords(ctx, hugeBTreeAddress)) {
                if (record.length < offsets + 2 * lengths) {
                    throw new HdfUnsupportedException("huge-object index records of " + record.length
                            + " bytes (a filtered heap?) are not supported");
                }
                if (readLittleEndian(record, offsets + lengths, Math.min(lengths, 8)) == key) {
                    address = readLittleEndian(record, 0, offsets);
                    length = readLittleEndian(record, offsets, lengths);
                    break;
                }
            }
            if (length < 0) {
                throw new HdfFormatException("huge fractal heap object " + key + " is not in the heap's index");
            }
        }
        if (length < 0 || length > Integer.MAX_VALUE || address < 0 || address > ctx.buffer().size() - length) {
            throw new HdfFormatException("huge fractal heap object (" + length + " bytes at " + address
                    + ") lies outside the file");
        }
        return new HeapObject(address, (int) length);
    }

    /** A direct block: its file address, the heap offset it starts at, and its size. */
    private record Block(long address, long heapOffset, long size) {
    }

    /**
     * Walks an indirect block's doubling table to find the direct block holding a logical offset. The
     * block has {@code rows} rows and starts at heap offset {@code base}; its first rows (up to the
     * maximum direct-block size) point at direct blocks, the rest at child indirect blocks, each of which
     * covers its row's block size with {@code log2(size) - log2(start size * width) + 1} rows of its own.
     */
    private Block resolveManagedOffset(long indirectBlock, int rows, long base, long offset, int depth) {
        if (depth > MAX_NESTING) {
            throw new HdfFormatException("fractal heap indirect blocks nest too deeply at " + indirectBlock);
        }
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(indirectBlock, FHIB)) {
            throw new HdfFormatException("expected fractal heap indirect block 'FHIB' at " + indirectBlock);
        }
        long entries = indirectBlock + 5 + offsets + offsetSize; // signature, version, heap header, block offset
        int directRows = Math.min(rows, maxDirectRows);
        int indirectRows = rows - directRows;
        if (verifiedIndirectBlocks.add(indirectBlock)) {
            long stored = readLittleEndian(buf.getBytes(entries - offsetSize, offsetSize), 0, offsetSize);
            if (stored != base) {
                throw new HdfFormatException("fractal heap indirect block at " + indirectBlock + " claims heap offset "
                        + stored + ", expected " + base);
            }
            long entryBytes = (long) (directRows + indirectRows) * tableWidth * offsets;
            MetadataChecksum.verify(buf, indirectBlock, entries - indirectBlock + entryBytes, "fractal heap indirect block");
        }
        long entry = entries;
        long cursor = base;
        for (int row = 0; row < rows; row++) {
            long blockSize = rowBlockSize(row);
            for (int col = 0; col < tableWidth; col++) {
                long blockAddress = buf.getAddress(entry, offsets);
                entry += offsets; // an unfiltered block pointer is just an address
                if (offset < cursor + blockSize) {
                    if (blockAddress == HdfBuffer.UNDEFINED_ADDRESS) {
                        throw new HdfFormatException("unallocated fractal heap block for heap offset " + offset);
                    }
                    if (row < maxDirectRows) {
                        return new Block(blockAddress, cursor, blockSize);
                    }
                    int childRows = log2(blockSize) - log2(startBlockSize) - log2(tableWidth) + 1;
                    return resolveManagedOffset(blockAddress, childRows, cursor, offset, depth + 1);
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
