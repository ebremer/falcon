package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.checksum.MetadataChecksum;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;

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
 */
public final class ExtensibleArray {

    private static final byte[] EAHD = {'E', 'A', 'H', 'D'};
    private static final byte[] EAIB = {'E', 'A', 'I', 'B'};
    private static final byte[] EASB = {'E', 'A', 'S', 'B'};
    private static final byte[] EADB = {'E', 'A', 'D', 'B'};

    private ExtensibleArray() {
    }

    public static List<ChunkRecord> readChunks(FileContext ctx, long headerAddress, int chunkBytes, ChunkGrid grid) {
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
            return List.of();
        }
        if (maxIndexSet < 0 || maxIndexSet > (1L << maxBits)) {
            throw new HdfFormatException("extensible array index set " + maxIndexSet + " exceeds its bounds at " + headerAddress);
        }
        if (!buf.hasSignature(indexBlock, EAIB)) {
            throw new HdfFormatException("expected extensible array index block 'EAIB' at " + indexBlock);
        }

        int nsblks = 1 + (maxBits - log2(dblkMinElmts));
        long pageElmts = 1L << maxPageBits;
        int offsetBytes = (maxBits + 7) / 8; // width of a block's "block offset" field

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

        List<ChunkRecord> chunks = new ArrayList<>();
        long linear = 0;
        // Inline elements (chunk indices 0 .. idxBlkElmts-1).
        for (int i = 0; i < idxBlkElmts && linear < maxIndexSet; i++, linear++) {
            format.read(buf, elemBase + (long) i * elemSize, linear, chunkBytes, grid, chunks);
        }

        int directCursor = 0;
        int secondaryCursor = 0;
        for (int u = 0; u < nsblks && linear < maxIndexSet; u++) {
            long ndblks = superBlockDataBlocks(u);
            long dblkNelmts = (1L << ((u + 1) / 2)) * dblkMinElmts;
            boolean paged = dblkNelmts > pageElmts;
            long pagesPerBlock = paged ? dblkNelmts / pageElmts : 0;
            if (ndblks * offsets > buf.size()) {
                throw new HdfFormatException("extensible array super block " + u + " is larger than the file");
            }
            long[] dblkAddrs = new long[(int) ndblks];
            long bitmap = -1; // address of the secondary block's page-init bitmap, if paged
            if (ndblks < sblkMinPtrs) {
                for (int k = 0; k < ndblks; k++) {
                    dblkAddrs[k] = buf.getAddress(directRegion + (long) (directCursor + k) * offsets, offsets);
                }
                directCursor += (int) ndblks;
            } else {
                long secondary = buf.getAddress(secondaryRegion + (long) secondaryCursor * offsets, offsets);
                secondaryCursor++;
                if (secondary == HdfBuffer.UNDEFINED_ADDRESS) {
                    // The whole super block is unallocated; skip its elements without visiting them.
                    linear = Math.min(maxIndexSet, linear + ndblks * dblkNelmts);
                    continue;
                }
                if (!buf.hasSignature(secondary, EASB)) {
                    throw new HdfFormatException("expected extensible array secondary block 'EASB' at " + secondary);
                }
                long ptrs = secondary + 6 + offsets + offsetBytes; // sig, ver, client, header addr, block offset
                if (paged) {
                    // Page-init bitmap: ceil(pages-per-block / 8) bytes per data block, then the addresses.
                    bitmap = ptrs;
                    ptrs += ndblks * ((pagesPerBlock + 7) / 8);
                }
                MetadataChecksum.verify(buf, secondary, ptrs + ndblks * offsets - secondary,
                        "extensible array secondary block");
                for (int k = 0; k < ndblks; k++) {
                    dblkAddrs[k] = buf.getAddress(ptrs + (long) k * offsets, offsets);
                }
            }
            for (int k = 0; k < ndblks && linear < maxIndexSet; k++) {
                long addr = dblkAddrs[k];
                if (addr == HdfBuffer.UNDEFINED_ADDRESS) {
                    linear = Math.min(maxIndexSet, linear + dblkNelmts); // unallocated data block
                    continue;
                }
                if (!buf.hasSignature(addr, EADB)) {
                    throw new HdfFormatException("expected extensible array data block 'EADB' at " + addr);
                }
                long base = addr + 6 + offsets + offsetBytes; // signature, version, client id, header, block offset
                if (!paged) {
                    long count = Math.min(dblkNelmts, maxIndexSet - linear);
                    MetadataChecksum.verify(buf, addr, base + dblkNelmts * elemSize - addr, "extensible array data block");
                    for (long j = 0; j < count; j++) {
                        format.read(buf, base + j * elemSize, linear + j, chunkBytes, grid, chunks);
                    }
                    linear += dblkNelmts;
                    continue;
                }
                MetadataChecksum.verify(buf, addr, base - addr, "extensible array data block");
                long pageStart = base + 4;
                long pageStride = pageElmts * elemSize + 4;
                for (long page = 0; page < pagesPerBlock && linear < maxIndexSet; page++) {
                    long count = Math.min(pageElmts, maxIndexSet - linear);
                    if (bitmap >= 0 && FixedArray.bitSet(buf, bitmap, k * pagesPerBlock + page)) {
                        long pageAddress = pageStart + page * pageStride;
                        MetadataChecksum.verify(buf, pageAddress, pageElmts * elemSize, "extensible array data block page");
                        for (long j = 0; j < count; j++) {
                            format.read(buf, pageAddress + j * elemSize, linear + j, chunkBytes, grid, chunks);
                        }
                    }
                    linear += pageElmts; // a clear bit means the page was never written: all unallocated
                }
            }
        }
        return chunks;
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
