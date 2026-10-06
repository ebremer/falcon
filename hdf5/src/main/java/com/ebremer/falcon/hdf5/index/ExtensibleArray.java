package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.checksum.MetadataChecksum;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extensible Array chunk index (spec Appendix C.D): the index for chunked datasets with a single
 * unlimited dimension. Elements (chunk entries) are addressed by the chunk's linear index (see
 * {@link ChunkGrid#forExtensibleArray}) and stored in a tiered structure rooted at the header
 * ({@code "EAHD"}):
 *
 * <ul>
 *   <li>the <b>index block</b> ({@code "EAIB"}) holds the first {@code idxBlkElmts} elements inline,
 *       followed by direct pointers to the data blocks of the low-order "super blocks" and pointers
 *       to secondary blocks for the higher-order ones;</li>
 *   <li>each <b>secondary block</b> ({@code "EASB"}) holds pointers to the data blocks of one super
 *       block;</li>
 *   <li>each <b>data block</b> ({@code "EADB"}) holds a run of elements.</li>
 * </ul>
 *
 * <p>Super block {@code u} contains {@code 2^(u/2)} data blocks of {@code dblkMinElmts * 2^((u+1)/2)}
 * elements each; a super block is addressed directly from the index block when its data-block count is
 * below {@code sblkMinPtrs}, and via a secondary block otherwise. Element encoding matches the fixed
 * array (see {@link FixedArray}). Data blocks larger than one page are <b>paged</b>: each page is a run
 * of elements plus its own checksum, and the owning secondary block carries a page-init bitmap (bit
 * {@code dataBlock * pagesPerBlock + page}, most-significant bit first); pages whose bit is clear were
 * never written, so their chunks are unallocated.
 *
 * <p>{@link #readChunks} reads every element; {@link #lookup} finds one by its index, reading the blocks
 * on its path (P2 PF5), as libhdf5's {@code H5EA_get} does.
 */
public final class ExtensibleArray {

    private static final byte[] EAHD = {'E', 'A', 'H', 'D'};
    private static final byte[] EAIB = {'E', 'A', 'I', 'B'};
    private static final byte[] EASB = {'E', 'A', 'S', 'B'};
    private static final byte[] EADB = {'E', 'A', 'D', 'B'};

    private ExtensibleArray() {
    }

    public static List<ChunkRecord> readChunks(FileContext ctx, long headerAddress, int chunkBytes, ChunkGrid grid) {
        Header header = Header.parse(ctx, headerAddress);
        if (header == null) {
            return List.of();
        }
        HdfBuffer buf = ctx.buffer();
        int elemSize = header.elemSize;
        long maxIndexSet = header.maxIndexSet;
        List<ChunkRecord> chunks = new ArrayList<>();
        long linear = 0;
        // Inline elements (chunk indices 0 .. idxBlkElmts-1).
        for (int i = 0; i < header.idxBlkElmts && linear < maxIndexSet; i++, linear++) {
            header.format.read(buf, header.elemBase + (long) i * elemSize, linear, chunkBytes, grid, chunks);
        }

        int directCursor = 0;
        int secondaryCursor = 0;
        for (int u = 0; u < header.nsblks && linear < maxIndexSet; u++) {
            long ndblks = superBlockDataBlocks(u);
            long dblkNelmts = header.dataBlockElements(u);
            boolean paged = dblkNelmts > header.pageElmts;
            long pagesPerBlock = paged ? dblkNelmts / header.pageElmts : 0;
            long[] dblkAddrs = new long[(int) ndblks];
            long bitmap = -1; // address of the secondary block's page-init bitmap, if paged
            if (ndblks < header.sblkMinPtrs) {
                for (int k = 0; k < ndblks; k++) {
                    dblkAddrs[k] = header.directDataBlock(buf, directCursor + k);
                }
                directCursor += (int) ndblks;
            } else {
                long secondary = buf.getAddress(header.secondaryRegion + (long) secondaryCursor * header.offsets,
                        header.offsets);
                secondaryCursor++;
                if (secondary == HdfBuffer.UNDEFINED_ADDRESS) {
                    // The whole super block is unallocated; skip its elements without visiting them.
                    linear = Math.min(maxIndexSet, linear + ndblks * dblkNelmts);
                    continue;
                }
                long ptrs = header.verifySecondary(buf, secondary, ndblks, paged, pagesPerBlock);
                if (paged) {
                    bitmap = header.bitmapOf(secondary);
                }
                for (int k = 0; k < ndblks; k++) {
                    dblkAddrs[k] = buf.getAddress(ptrs + (long) k * header.offsets, header.offsets);
                }
            }
            for (int k = 0; k < ndblks && linear < maxIndexSet; k++) {
                long addr = dblkAddrs[k];
                if (addr == HdfBuffer.UNDEFINED_ADDRESS) {
                    linear = Math.min(maxIndexSet, linear + dblkNelmts); // unallocated data block
                    continue;
                }
                long base = header.verifyDataBlock(buf, addr, dblkNelmts, paged);
                if (!paged) {
                    long count = Math.min(dblkNelmts, maxIndexSet - linear);
                    for (long j = 0; j < count; j++) {
                        header.format.read(buf, base + j * elemSize, linear + j, chunkBytes, grid, chunks);
                    }
                    linear += dblkNelmts;
                    continue;
                }
                for (long page = 0; page < pagesPerBlock && linear < maxIndexSet; page++) {
                    long count = Math.min(header.pageElmts, maxIndexSet - linear);
                    if (bitmap >= 0 && FixedArray.bitSet(buf, bitmap, k * pagesPerBlock + page)) {
                        long pageAddress = header.verifyPage(buf, base, page);
                        for (long j = 0; j < count; j++) {
                            header.format.read(buf, pageAddress + j * elemSize, linear + j, chunkBytes, grid, chunks);
                        }
                    }
                    linear += header.pageElmts; // a clear bit means the page was never written: all unallocated
                }
            }
        }
        return chunks;
    }

    /**
     * Finds a chunk by its linear index (P2 PF5): in the index block, or in the data block of the super
     * block that holds it, found directly or through its secondary block. Each block (or page) is verified
     * against its checksum the first time it is read.
     */
    public static ChunkLookup lookup(FileContext ctx, long headerAddress, int chunkBytes, ChunkGrid grid) {
        Header header = Header.parse(ctx, headerAddress);
        if (header == null) {
            return cell -> null;
        }
        Set<Long> verified = ConcurrentHashMap.newKeySet(); // blocks and pages verified, by address
        return cell -> {
            HdfBuffer buf = ctx.buffer();
            long linear = grid.linearIndex(cell);
            if (linear < 0 || linear >= header.maxIndexSet) {
                return null;
            }
            long[] offset = grid.offsetOf(cell);
            if (linear < header.idxBlkElmts) {
                return header.format.decode(buf, header.elemBase + linear * header.elemSize, offset, chunkBytes);
            }
            long rest = linear - header.idxBlkElmts;
            int directCursor = 0;
            int secondaryCursor = 0;
            for (int u = 0; u < header.nsblks; u++) {
                long ndblks = superBlockDataBlocks(u);
                long dblkNelmts = header.dataBlockElements(u);
                if (rest >= ndblks * dblkNelmts) {
                    rest -= ndblks * dblkNelmts;
                    if (ndblks < header.sblkMinPtrs) {
                        directCursor += (int) ndblks;
                    } else {
                        secondaryCursor++;
                    }
                    continue;
                }
                int k = (int) (rest / dblkNelmts);
                long within = rest % dblkNelmts;
                boolean paged = dblkNelmts > header.pageElmts;
                long pagesPerBlock = paged ? dblkNelmts / header.pageElmts : 0;
                long dataBlock;
                long bitmap = -1;
                if (ndblks < header.sblkMinPtrs) {
                    dataBlock = header.directDataBlock(buf, directCursor + k);
                } else {
                    long secondary = buf.getAddress(header.secondaryRegion + (long) secondaryCursor * header.offsets,
                            header.offsets);
                    if (secondary == HdfBuffer.UNDEFINED_ADDRESS) {
                        return null; // the whole super block is unallocated
                    }
                    long ptrs = verified.contains(secondary) ? header.pointersOf(secondary, ndblks, paged, pagesPerBlock)
                            : header.verifySecondary(buf, secondary, ndblks, paged, pagesPerBlock);
                    verified.add(secondary);
                    if (paged) {
                        bitmap = header.bitmapOf(secondary);
                    }
                    dataBlock = buf.getAddress(ptrs + (long) k * header.offsets, header.offsets);
                }
                if (dataBlock == HdfBuffer.UNDEFINED_ADDRESS) {
                    return null; // unallocated data block
                }
                long base = verified.contains(dataBlock) ? header.elementsOf(dataBlock)
                        : header.verifyDataBlock(buf, dataBlock, dblkNelmts, paged);
                verified.add(dataBlock);
                if (!paged) {
                    return header.format.decode(buf, base + within * header.elemSize, offset, chunkBytes);
                }
                long page = within / header.pageElmts;
                if (bitmap < 0 || !FixedArray.bitSet(buf, bitmap, k * pagesPerBlock + page)) {
                    return null; // the page was never written
                }
                long pageAddress = header.pageAddress(base, page);
                if (!verified.contains(pageAddress)) {
                    header.verifyPage(buf, base, page);
                    verified.add(pageAddress);
                }
                return header.format.decode(buf, pageAddress + (within % header.pageElmts) * header.elemSize, offset,
                        chunkBytes);
            }
            return null;
        };
    }

    /** An extensible array's parameters, and where its index block's regions are. */
    private record Header(FixedArray.EntryFormat format, int elemSize, int idxBlkElmts, int dblkMinElmts,
                          int sblkMinPtrs, long pageElmts, long maxIndexSet, int nsblks, int offsets, int offsetBytes,
                          long elemBase, long directRegion, long secondaryRegion) {

        /** The header at {@code headerAddress} and its index block, verified; null if nothing was ever stored. */
        static Header parse(FileContext ctx, long headerAddress) {
            HdfBuffer buf = ctx.buffer();
            if (!buf.hasSignature(headerAddress, EAHD)) {
                throw new HdfFormatException("expected extensible array header 'EAHD' at " + headerAddress);
            }
            int offsets = ctx.sizeOfOffsets();
            int lengths = ctx.sizeOfLengths();
            MetadataChecksum.verify(buf, headerAddress, 12L + 6L * lengths + offsets, "extensible array header");
            int clientId = buf.getUnsignedByte(headerAddress + 5);
            int elemSize = buf.getUnsignedByte(headerAddress + 6);
            int maxBits = buf.getUnsignedByte(headerAddress + 7);
            int idxBlkElmts = buf.getUnsignedByte(headerAddress + 8);
            int dblkMinElmts = buf.getUnsignedByte(headerAddress + 9);
            int sblkMinPtrs = buf.getUnsignedByte(headerAddress + 10);
            int maxPageBits = buf.getUnsignedByte(headerAddress + 11);
            long maxIndexSet = buf.getUnsignedValue(headerAddress + 12 + 4L * lengths, lengths);
            long indexBlock = buf.getAddress(headerAddress + 12 + 6L * lengths, offsets);
            FixedArray.EntryFormat format = FixedArray.EntryFormat.of(clientId, elemSize, offsets, headerAddress);
            if (maxBits < 1 || maxBits > 62 || dblkMinElmts == 0 || Integer.bitCount(dblkMinElmts) != 1
                    || log2(dblkMinElmts) > maxBits || sblkMinPtrs == 0 || Integer.bitCount(sblkMinPtrs) != 1
                    || maxPageBits < 1 || maxPageBits > 31) {
                throw new HdfFormatException("invalid extensible array parameters at " + headerAddress);
            }
            if (indexBlock == HdfBuffer.UNDEFINED_ADDRESS || maxIndexSet == 0) {
                return null;
            }
            if (maxIndexSet < 0 || maxIndexSet > (1L << maxBits)) {
                throw new HdfFormatException("extensible array index set " + maxIndexSet + " exceeds its bounds at "
                        + headerAddress);
            }
            if (!buf.hasSignature(indexBlock, EAIB)) {
                throw new HdfFormatException("expected extensible array index block 'EAIB' at " + indexBlock);
            }
            int nsblks = 1 + (maxBits - log2(dblkMinElmts));
            long elemBase = indexBlock + 6 + offsets;                          // inline element buffer
            long directRegion = elemBase + (long) idxBlkElmts * elemSize;      // direct data-block addresses
            int ndblkAddrs = 0;
            int nsblkAddrs = 0;
            for (int u = 0; u < nsblks; u++) {
                if (superBlockDataBlocks(u) < sblkMinPtrs) {
                    ndblkAddrs += (int) superBlockDataBlocks(u);
                } else {
                    nsblkAddrs++;
                }
            }
            long secondaryRegion = directRegion + (long) ndblkAddrs * offsets; // secondary-block addresses
            MetadataChecksum.verify(buf, indexBlock,
                    secondaryRegion + (long) nsblkAddrs * offsets - indexBlock, "extensible array index block");
            return new Header(format, elemSize, idxBlkElmts, dblkMinElmts, sblkMinPtrs, 1L << maxPageBits, maxIndexSet,
                    nsblks, offsets, (maxBits + 7) / 8, elemBase, directRegion, secondaryRegion);
        }

        /** The elements in each data block of super block {@code u}. */
        long dataBlockElements(int u) {
            return (1L << ((u + 1) / 2)) * dblkMinElmts;
        }

        /** The address of the {@code index}th data block the index block points at directly. */
        long directDataBlock(HdfBuffer buf, int index) {
            return buf.getAddress(directRegion + (long) index * offsets, offsets);
        }

        /** Where a secondary block's page-init bitmap is: after its signature, version, client id, header address, and block offset. */
        long bitmapOf(long secondary) {
            return secondary + 6 + offsets + offsetBytes;
        }

        /** Where a secondary block's data-block addresses are (after its page-init bitmap, if its blocks are paged). */
        long pointersOf(long secondary, long ndblks, boolean paged, long pagesPerBlock) {
            return bitmapOf(secondary) + (paged ? ndblks * ((pagesPerBlock + 7) / 8) : 0);
        }

        /** Verifies the secondary block at {@code secondary}, returning where its data-block addresses are. */
        long verifySecondary(HdfBuffer buf, long secondary, long ndblks, boolean paged, long pagesPerBlock) {
            if (ndblks * offsets > buf.size()) {
                throw new HdfFormatException("extensible array secondary block at " + secondary + " is larger than the file");
            }
            if (!buf.hasSignature(secondary, EASB)) {
                throw new HdfFormatException("expected extensible array secondary block 'EASB' at " + secondary);
            }
            long ptrs = pointersOf(secondary, ndblks, paged, pagesPerBlock);
            MetadataChecksum.verify(buf, secondary, ptrs + ndblks * offsets - secondary, "extensible array secondary block");
            return ptrs;
        }

        /** Where a data block's elements (or pages) are: after its signature, version, client id, header address, and block offset. */
        long elementsOf(long dataBlock) {
            return dataBlock + 6 + offsets + offsetBytes;
        }

        /**
         * Verifies the data block at {@code dataBlock}: its elements, or, paged, its prefix (each page has its
         * own checksum). Returns where its elements or pages are.
         */
        long verifyDataBlock(HdfBuffer buf, long dataBlock, long dblkNelmts, boolean paged) {
            if (!buf.hasSignature(dataBlock, EADB)) {
                throw new HdfFormatException("expected extensible array data block 'EADB' at " + dataBlock);
            }
            long base = elementsOf(dataBlock);
            MetadataChecksum.verify(buf, dataBlock, base - dataBlock + (paged ? 0 : dblkNelmts * elemSize),
                    "extensible array data block");
            return base;
        }

        /** Where page {@code page} of a paged data block whose pages start after {@code base}'s checksum is. */
        long pageAddress(long base, long page) {
            return base + 4 + page * (pageElmts * elemSize + 4);
        }

        /** Verifies page {@code page} of a paged data block, returning where its elements are. */
        long verifyPage(HdfBuffer buf, long base, long page) {
            long pageAddress = pageAddress(base, page);
            MetadataChecksum.verify(buf, pageAddress, pageElmts * elemSize, "extensible array data block page");
            return pageAddress;
        }
    }

    /** Number of data blocks in super block {@code u}. */
    private static long superBlockDataBlocks(int u) {
        return 1L << (u / 2);
    }

    /** Floor log2 of a power-of-two value. */
    private static int log2(int powerOfTwo) {
        return Integer.numberOfTrailingZeros(powerOfTwo);
    }
}
