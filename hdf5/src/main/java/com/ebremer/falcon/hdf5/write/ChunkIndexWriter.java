package com.ebremer.falcon.hdf5.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a chunked dataset's chunk index, once its chunks are in the file:
 * <ul>
 *   <li>a <b>fixed array</b> (layout version 4, index type 3), for a dataset whose extent cannot change:
 *       one entry per cell of the chunk grid, in row-major order, a cell never written holding the
 *       undefined address, as libhdf5's fill entry does;</li>
 *   <li>a <b>version-1 B-tree</b> of raw-data chunks (layout version 3), for a dataset that may grow, or
 *       in the earliest format: the index every HDF5 version reads, built bottom-up from the chunks in
 *       order, each node full-size as libhdf5 allocates it ({@code H5B}, node type 1).</li>
 * </ul>
 */
public final class ChunkIndexWriter {

    private ChunkIndexWriter() {
    }

    private static final long UNDEFINED = -1L;

    /** Fixed-array page size, as {@code 2^PAGE_BITS} entries (libhdf5's default page bits). */
    public static final int FA_PAGE_BITS = 10;

    /** The 'K' of chunk B-trees libhdf5 uses unless a file records another ({@code H5F_ISTORE_K}): 64 entries a node. */
    public static final int BTREE_K = 32;

    /** One stored chunk: its position in the chunk grid, and where and how it is stored. */
    public record Entry(long[] scaled, long address, int size, int filterMask) {
    }

    /**
     * Writes a fixed array of {@code cells} entries, the chunks' at their row-major cell numbers (the rest
     * undefined), and returns its header's address.
     *
     * @param entries   the stored chunks, by row-major cell number
     * @param filtered  whether entries record each chunk's stored size and filter mask
     * @param chunkBytes the unfiltered chunk size, which sets the stored-size field's width
     */
    public static long writeFixedArray(GrowBuffer buf, long cells, Map<Long, Entry> entries, boolean filtered,
                                       int chunkBytes) {
        int offsets = 8;
        int clientId = filtered ? 1 : 0;
        // A filtered entry is address + stored chunk size + filter mask; layout version 4 (HDF5 1.10-1.14)
        // sizes the chunk-size field to the unfiltered chunk size plus one byte (H5D__farray_crt_context).
        int sizeWidth = filtered ? chunkSizeWidth(chunkBytes) : 0;
        int entrySize = offsets + (filtered ? sizeWidth + 4 : 0);
        long pageEntries = 1L << FA_PAGE_BITS;
        boolean paged = cells > pageEntries;
        long pages = paged ? (cells + pageEntries - 1) / pageEntries : 0;
        long bitmapBytes = (pages + 7) / 8;
        long dataBlockSize = paged
                ? 6 + offsets + bitmapBytes + 4 + cells * entrySize + 4L * pages
                : 6 + offsets + cells * entrySize + 4;
        buf.align(8);
        long dataBlockAddress = buf.position();
        long headerAddress = (dataBlockAddress + dataBlockSize + 7) & ~7L;

        // Fixed-array data block: signature, version, client id, header address, then either the entries
        // or (paged) the page-init bitmap; the prefix checksum; then (paged) each page and its checksum.
        buf.bytes(new byte[] {'F', 'A', 'D', 'B'});
        buf.u8(0);
        buf.u8(clientId);
        buf.u64(headerAddress);
        if (!paged) {
            for (long i = 0; i < cells; i++) {
                writeEntry(buf, entries.get(i), filtered, sizeWidth);
            }
            buf.u32(buf.checksum(dataBlockAddress, buf.position()));
        } else {
            for (long b = 0; b < bitmapBytes; b++) { // every page is initialized: one bit per page, MSB first
                int bits = (int) Math.min(8, pages - 8 * b);
                buf.u8((0xFF << (8 - bits)) & 0xFF);
            }
            buf.u32(buf.checksum(dataBlockAddress, buf.position()));
            for (long page = 0; page < pages; page++) {
                long pageStart = buf.position();
                for (long i = page * pageEntries; i < Math.min(cells, (page + 1) * pageEntries); i++) {
                    writeEntry(buf, entries.get(i), filtered, sizeWidth);
                }
                buf.u32(buf.checksum(pageStart, buf.position()));
            }
        }

        // Fixed-array header: signature, version, client id, entry size, page bits, max entries,
        // data block address, checksum.
        buf.align(8);
        long headerStart = buf.position();
        buf.bytes(new byte[] {'F', 'A', 'H', 'D'});
        buf.u8(0);
        buf.u8(clientId);
        buf.u8(entrySize);
        buf.u8(FA_PAGE_BITS);
        buf.u64(cells);                // max entries: one per cell of the (fixed) chunk grid
        buf.u64(dataBlockAddress);
        buf.u32(buf.checksum(headerStart, buf.position()));
        return headerStart;
    }

    private static void writeEntry(GrowBuffer buf, Entry entry, boolean filtered, int sizeWidth) {
        buf.u64(entry == null ? UNDEFINED : entry.address());
        if (filtered) {
            buf.uvar(entry == null ? 0 : entry.size(), sizeWidth);
            buf.u32(entry == null ? 0 : entry.filterMask());
        }
    }

    /**
     * Bytes for a filtered chunk's stored size in layout version 4: enough for the unfiltered chunk
     * size plus one more byte, in case a filter grows the chunk ({@code 1 + (log2(size) + 8) / 8}).
     */
    public static int chunkSizeWidth(int chunkBytes) {
        int log2 = 31 - Integer.numberOfLeadingZeros(Math.max(1, chunkBytes));
        return Math.min(8, 1 + (log2 + 8) / 8);
    }

    /**
     * Writes a version-1 B-tree indexing {@code entries} (sorted by grid position, row-major) and returns
     * its root's address. Each key is a chunk's stored size, filter mask, and element offset in every
     * dimension plus a trailing 0; key <i>i</i> of a node is its child <i>i</i>'s first chunk, and its last
     * key the next node's first, or, after the last chunk, that chunk's grid position plus one in every
     * dimension, as libhdf5 sets a new node's right key ({@code H5D__btree_new_node}).
     */
    public static long writeBTreeV1(GrowBuffer buf, List<Entry> entries, long[] chunkDims) {
        int rank = chunkDims.length;
        int keySize = 4 + 4 + 8 * (rank + 1);
        int perNode = 2 * BTREE_K;
        int nodeSize = 8 + 16 + perNode * 8 + (perNode + 1) * keySize;
        Entry last = entries.getLast();
        long[] beyond = new long[rank];
        for (int d = 0; d < rank; d++) {
            beyond[d] = last.scaled()[d] + 1;
        }
        byte[] finalKey = key(0, 0, beyond, chunkDims);

        // Level 0: the chunks; each higher level: the nodes below it, keyed by their first keys.
        List<byte[]> keys = new ArrayList<>();
        List<Long> children = new ArrayList<>();
        for (Entry entry : entries) {
            keys.add(key(entry.size(), entry.filterMask(), entry.scaled(), chunkDims));
            children.add(entry.address());
        }
        int level = 0;
        while (true) {
            int nodes = (children.size() + perNode - 1) / perNode;
            buf.align(8);
            long first = buf.position();
            List<byte[]> parentKeys = new ArrayList<>();
            List<Long> parentChildren = new ArrayList<>();
            for (int n = 0; n < nodes; n++) {
                int from = n * perNode;
                int to = Math.min(children.size(), from + perNode);
                long at = first + (long) n * nodeSize;
                buf.bytes(new byte[] {'T', 'R', 'E', 'E'});
                buf.u8(1);                                   // node type: raw data chunks
                buf.u8(level);
                buf.u16(to - from);                          // entries used
                buf.u64(n == 0 ? UNDEFINED : at - nodeSize); // left sibling
                buf.u64(n == nodes - 1 ? UNDEFINED : at + nodeSize); // right sibling
                for (int i = from; i < to; i++) {
                    buf.bytes(keys.get(i));
                    buf.u64(children.get(i));
                }
                buf.bytes(to < keys.size() ? keys.get(to) : finalKey);
                while (buf.position() - at < nodeSize) {
                    buf.u8(0);                               // the node is allocated full-size
                }
                parentKeys.add(keys.get(from));
                parentChildren.add(at);
            }
            if (nodes == 1) {
                return first;
            }
            keys = parentKeys;
            children = parentChildren;
            level++;
        }
    }

    private static byte[] key(int size, int filterMask, long[] scaled, long[] chunkDims) {
        GrowBuffer k = new GrowBuffer();
        k.u32(size);
        k.u32(filterMask);
        for (int d = 0; d < scaled.length; d++) {
            k.u64(scaled[d] * chunkDims[d]);
        }
        k.u64(0); // the element-offset dimension
        return k.toByteArray();
    }
}
