package com.ebremer.falcon.hdf5.write;

import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Writes a fractal heap (spec section III.G) holding a dense set of objects (an object's links or
 * attributes), laid out as libhdf5 lays its own out with its default parameters ({@code H5G_FHEAP_*},
 * {@code H5A_FHEAP_*}): a doubling table four blocks wide, whose first two rows hold 512-byte direct
 * blocks and each later row blocks twice the size, up to 64 KiB; rows beyond hold child indirect blocks,
 * which nest the same table. A heap that fits in one 512-byte block is that block alone; a larger one has a
 * root indirect block, with as many rows as its blocks need.
 *
 * <p>Objects are placed in order, each in the first block from the current one with room for it, as
 * libhdf5 allocates them; a block an object skips because it is too small stays unallocated (an undefined
 * address in its indirect block), as libhdf5 skips it. Every object is managed (none is "huge"): the
 * heap's maximum managed size is raised to the largest object, which must fit in a 64 KiB direct block.
 * Direct blocks are checksummed. The allocation iterator is left after the last block, so libhdf5 can
 * add to the heap; no free-space manager is written (libhdf5 makes one when it needs it).
 */
public final class FractalHeapWriter {

    private FractalHeapWriter() {
    }

    private static final byte[] FRHP = {'F', 'R', 'H', 'P'};
    private static final byte[] FHDB = {'F', 'H', 'D', 'B'};
    private static final byte[] FHIB = {'F', 'H', 'I', 'B'};
    private static final long UNDEFINED = -1L;
    private static final int OFFSETS = 8;
    private static final int LENGTHS = 8;

    /** Blocks per row of the doubling table. */
    public static final int TABLE_WIDTH = 4;
    /** The size of the blocks in the table's first two rows. */
    public static final int START_BLOCK_SIZE = 512;
    /** The largest direct block; rows of larger blocks hold indirect blocks. */
    public static final int MAX_DIRECT_BLOCK_SIZE = 65536;
    /** libhdf5's maximum managed object size, which the header records unless an object is larger. */
    private static final int DEFAULT_MAX_MANAGED_OBJECT = 4096;
    private static final int HEADER_SIZE = 4 + 1 + 2 + 2 + 1 + 4 + LENGTHS + OFFSETS + LENGTHS + OFFSETS
            + 8 * LENGTHS + 2 + LENGTHS + LENGTHS + 2 + 2 + OFFSETS + 2 + 4;
    private static final int START_BITS = Integer.numberOfTrailingZeros(START_BLOCK_SIZE);
    private static final int FIRST_ROW_BITS = START_BITS + Integer.numberOfTrailingZeros(TABLE_WIDTH);
    private static final int MAX_DIRECT_ROWS = Integer.numberOfTrailingZeros(MAX_DIRECT_BLOCK_SIZE) - START_BITS + 2;

    /** A written heap: its header's address, and each object's heap id, in the order given. */
    public record Heap(long headerAddress, List<byte[]> ids) {
    }

    /** The bytes of a direct block before its objects, for a heap of {@code maxHeapBits}. */
    public static int directBlockOverhead(int maxHeapBits) {
        return 4 + 1 + OFFSETS + offsetSize(maxHeapBits) + 4; // signature, version, header, block offset, checksum
    }

    /** The largest object a heap holds: one that fills a 64 KiB direct block. */
    public static int maxObjectSize(int maxHeapBits) {
        return MAX_DIRECT_BLOCK_SIZE - directBlockOverhead(maxHeapBits);
    }

    /**
     * Writes a heap holding {@code objects} and returns its header's address and the objects' heap ids
     * ({@code idLength} bytes: a type byte, the object's offset in {@code ceil(maxHeapBits / 8)} bytes,
     * its length in the rest).
     *
     * @throws HdfUnsupportedException if an object is larger than {@link #maxObjectSize}, or the heap
     *         would pass {@code 2^maxHeapBits} bytes
     */
    public static Heap write(GrowBuffer buf, List<byte[]> objects, int idLength, int maxHeapBits) {
        int offsetSize = offsetSize(maxHeapBits);
        int lengthSize = Math.min((Integer.numberOfTrailingZeros(MAX_DIRECT_BLOCK_SIZE) + 7) / 8, idLength - 1 - offsetSize);
        int overhead = directBlockOverhead(maxHeapBits);

        // Place the objects: block by block in heap order, each in the first block with room for it.
        TreeMap<Long, Block> blocks = new TreeMap<>();
        List<byte[]> ids = new ArrayList<>();
        int maxManaged = DEFAULT_MAX_MANAGED_OBJECT;
        long blockStart = 0;
        long blockSize = START_BLOCK_SIZE;
        long used = overhead;
        for (byte[] object : objects) {
            if (object.length > maxObjectSize(maxHeapBits)) {
                throw new HdfUnsupportedException("a " + object.length + "-byte object is larger than a fractal heap's"
                        + " largest direct block holds (" + maxObjectSize(maxHeapBits) + " bytes)");
            }
            maxManaged = Math.max(maxManaged, object.length);
            while (used + object.length > blockSize) {
                blockStart += blockSize;
                blockSize = blockSizeAt(blockStart);
                used = overhead;
            }
            if (blockStart + blockSize > 1L << Math.min(62, maxHeapBits)) {
                throw new HdfUnsupportedException("a fractal heap of more than 2^" + maxHeapBits + " bytes");
            }
            Block block = blocks.computeIfAbsent(blockStart, start -> new Block(start, blockSizeAt(start)));
            ids.add(heapId(blockStart + used, object.length, offsetSize, lengthSize));
            block.objects.add(object);
            used += object.length;
        }
        if (blocks.isEmpty()) {
            blocks.put(0L, new Block(0, START_BLOCK_SIZE)); // an empty heap still has its root block
        }

        buf.align(8);
        long header = buf.reserve(HEADER_SIZE);
        long free = 0;
        long allocated = 0;
        for (Block block : blocks.values()) {
            buf.align(8);
            block.address = buf.position();
            buf.bytes(FHDB);
            buf.u8(0);
            buf.u64(header);
            buf.uvar(block.start, offsetSize);
            long checksum = buf.reserve(4);
            long content = overhead;
            for (byte[] object : block.objects) {
                buf.bytes(object);
                content += object.length;
            }
            buf.reserve((int) (block.size - content)); // the block's free space, zeroed
            buf.patchU32(checksum, buf.checksum(block.address, block.address + block.size));
            free += block.size - content;
            allocated += block.size;
        }
        Block last = blocks.lastEntry().getValue();
        long root;
        int rootRows;
        long managedSpace;
        if (blocks.size() == 1 && last.start == 0 && last.size == START_BLOCK_SIZE) {
            root = last.address;
            rootRows = 0;
            managedSpace = START_BLOCK_SIZE;
        } else {
            rootRows = rowOf(last.start) + 1;
            root = writeIndirect(buf, header, offsetSize, 0, rootRows, blocks);
            managedSpace = rowOffset(rootRows);
        }

        GrowBuffer h = new GrowBuffer(header);
        h.bytes(FRHP);
        h.u8(0);
        h.u16(idLength);
        h.u16(0);                         // I/O filter length
        h.u8(0x02);                       // flags: direct blocks are checksummed
        h.u32(maxManaged);
        h.u64(0);                         // next huge object id
        h.u64(UNDEFINED);                 // huge-object v2 B-tree address
        h.u64(free);                      // free space in managed blocks
        h.u64(UNDEFINED);                 // managed-block free-space manager address
        h.u64(managedSpace);              // managed space: the root block's span
        h.u64(allocated);                 // allocated managed space: the direct blocks'
        h.u64(last.start + last.size);    // the allocation iterator: after the last block
        h.u64(objects.size());            // number of managed objects
        h.u64(0);                         // huge object size
        h.u64(0);                         // number of huge objects
        h.u64(0);                         // tiny object size
        h.u64(0);                         // number of tiny objects
        h.u16(TABLE_WIDTH);
        h.u64(START_BLOCK_SIZE);
        h.u64(MAX_DIRECT_BLOCK_SIZE);
        h.u16(maxHeapBits);
        h.u16(1);                         // starting rows in the root indirect block
        h.u64(root);
        h.u16(rootRows);                  // 0: the root is a direct block
        h.u32(h.checksum(header, h.position()));
        buf.patchBytes(header, h.toByteArray());
        return new Heap(header, ids);
    }

    /** A direct block: where in the heap it starts, its size, its objects, and (once written) its address. */
    private static final class Block {
        final long start;
        final long size;
        final List<byte[]> objects = new ArrayList<>();
        long address;

        Block(long start, long size) {
            this.start = start;
            this.size = size;
        }
    }

    /**
     * Writes the indirect block of {@code rows} rows covering the heap from {@code start}, after its
     * children (direct blocks are written already; child indirect blocks with no block under them are not
     * written), and returns its address, or undefined if no block lies under it.
     */
    private static long writeIndirect(GrowBuffer buf, long header, int offsetSize, long start, int rows,
                                      TreeMap<Long, Block> blocks) {
        if (blocks.subMap(start, start + rowOffset(rows)).isEmpty()) {
            return UNDEFINED;
        }
        long[] entries = new long[rows * TABLE_WIDTH];
        for (int row = 0; row < rows; row++) {
            long size = rowBlockSize(row);
            for (int col = 0; col < TABLE_WIDTH; col++) {
                long child = start + rowOffset(row) + col * size;
                if (row < MAX_DIRECT_ROWS) {
                    Block block = blocks.get(child);
                    entries[row * TABLE_WIDTH + col] = block == null ? UNDEFINED : block.address;
                } else {
                    int childRows = Long.numberOfTrailingZeros(size) - FIRST_ROW_BITS + 1;
                    entries[row * TABLE_WIDTH + col] = writeIndirect(buf, header, offsetSize, child, childRows, blocks);
                }
            }
        }
        buf.align(8);
        long address = buf.position();
        buf.bytes(FHIB);
        buf.u8(0);
        buf.u64(header);
        buf.uvar(start, offsetSize);
        for (long entry : entries) {
            buf.u64(entry);
        }
        buf.u32(buf.checksum(address, buf.position()));
        return address;
    }

    /** The size of the direct block that starts at heap offset {@code start} (a block boundary). */
    static long blockSizeAt(long start) {
        long offset = start;
        while (true) {
            int row = rowOf(offset);
            long size = rowBlockSize(row);
            if (row < MAX_DIRECT_ROWS) {
                return size;
            }
            // Within a child indirect block, which repeats the table from its own start.
            offset = (offset - rowOffset(row)) % size;
        }
    }

    /** The row of the table holding heap offset {@code offset}, within a block starting at 0. */
    private static int rowOf(long offset) {
        int row = 0;
        while (rowOffset(row + 1) <= offset) {
            row++;
        }
        return row;
    }

    /** Where row {@code row} starts: rows 0 and 1 hold the starting size, each later one twice the last. */
    private static long rowOffset(int row) {
        return row == 0 ? 0 : ((long) START_BLOCK_SIZE * TABLE_WIDTH) << (row - 1);
    }

    private static long rowBlockSize(int row) {
        return row <= 1 ? START_BLOCK_SIZE : (long) START_BLOCK_SIZE << (row - 1);
    }

    private static int offsetSize(int maxHeapBits) {
        return (maxHeapBits + 7) / 8;
    }

    /** A managed object's heap id: the type byte (0), its offset, and its length. */
    private static byte[] heapId(long offset, int length, int offsetSize, int lengthSize) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0);
        b.uvar(offset, offsetSize);
        b.uvar(length, lengthSize);
        return b.toByteArray();
    }
}
