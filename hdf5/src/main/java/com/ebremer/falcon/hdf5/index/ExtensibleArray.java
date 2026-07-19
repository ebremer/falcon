package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Extensible Array chunk index (spec Appendix C.D): the index for chunked datasets with a single
 * unlimited dimension. Elements (chunk entries) are addressed by the chunk's row-major linear index
 * and stored in a tiered structure rooted at the header ({@code "EAHD"}):
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
 * array: an address for unfiltered chunks (client id 0), or address + stored size + filter mask for
 * filtered chunks (client id 1). Paged data blocks (very large chunks) are a later increment.
 */
public final class ExtensibleArray {

    private static final byte[] EAHD = {'E', 'A', 'H', 'D'};
    private static final byte[] EAIB = {'E', 'A', 'I', 'B'};
    private static final byte[] EASB = {'E', 'A', 'S', 'B'};
    private static final byte[] EADB = {'E', 'A', 'D', 'B'};

    private ExtensibleArray() {
    }

    public static List<ChunkRecord> readChunks(FileContext ctx, long headerAddress, int chunkBytes,
                                               long[] datasetDims, int[] chunkDims) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(headerAddress, EAHD)) {
            throw new HdfFormatException("expected extensible array header 'EAHD' at " + headerAddress);
        }
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        int clientId = buf.getUnsignedByte(headerAddress + 5);
        int elemSize = buf.getUnsignedByte(headerAddress + 6);
        int maxBits = buf.getUnsignedByte(headerAddress + 7);
        int idxBlkElmts = buf.getUnsignedByte(headerAddress + 8);
        int dblkMinElmts = buf.getUnsignedByte(headerAddress + 9);
        int sblkMinPtrs = buf.getUnsignedByte(headerAddress + 10);
        int maxPageBits = buf.getUnsignedByte(headerAddress + 11);
        long maxIndexSet = buf.getUnsignedValue(headerAddress + 12 + 4L * lengths, lengths);
        long indexBlock = buf.getAddress(headerAddress + 12 + 6L * lengths, offsets);
        if (indexBlock == HdfBuffer.UNDEFINED_ADDRESS || maxIndexSet == 0) {
            return List.of();
        }
        if (!buf.hasSignature(indexBlock, EAIB)) {
            throw new HdfFormatException("expected extensible array index block 'EAIB' at " + indexBlock);
        }

        int rank = datasetDims.length;
        int[] chunksPerDim = new int[rank];
        for (int d = 0; d < rank; d++) {
            chunksPerDim[d] = (int) ((datasetDims[d] + chunkDims[d] - 1) / chunkDims[d]);
        }

        int nsblks = 1 + (maxBits - log2(dblkMinElmts));
        int pageElmts = 1 << maxPageBits;
        int offsetBytes = (maxBits + 7) / 8; // width of a block's "block offset" field

        long elemBase = indexBlock + 6 + offsets;                          // inline element buffer
        long directRegion = elemBase + (long) idxBlkElmts * elemSize;      // direct data-block addresses
        int ndblkAddrs = 0;
        for (int u = 0; u < nsblks; u++) {
            if (superBlockDataBlocks(u) < sblkMinPtrs) {
                ndblkAddrs += superBlockDataBlocks(u);
            }
        }
        long secondaryRegion = directRegion + (long) ndblkAddrs * offsets; // secondary-block addresses

        List<ChunkRecord> chunks = new ArrayList<>();
        long linear = 0;
        // Inline elements (chunk indices 0 .. idxBlkElmts-1).
        for (int i = 0; i < idxBlkElmts && linear < maxIndexSet; i++, linear++) {
            addRecord(chunks, buf, elemBase + (long) i * elemSize, linear,
                    clientId, offsets, lengths, chunkBytes, chunksPerDim, chunkDims);
        }

        int directCursor = 0;
        int secondaryCursor = 0;
        for (int u = 0; u < nsblks && linear < maxIndexSet; u++) {
            int ndblks = superBlockDataBlocks(u);
            long dblkNelmts = (long) (1 << ((u + 1) / 2)) * dblkMinElmts;
            if (dblkNelmts > pageElmts) {
                throw new HdfUnsupportedException(
                        "paged extensible-array data blocks are implemented in a later increment");
            }
            long[] dblkAddrs = new long[ndblks];
            if (ndblks < sblkMinPtrs) {
                for (int k = 0; k < ndblks; k++) {
                    dblkAddrs[k] = buf.getAddress(directRegion + (long) (directCursor + k) * offsets, offsets);
                }
                directCursor += ndblks;
            } else {
                long secondary = buf.getAddress(secondaryRegion + (long) secondaryCursor * offsets, offsets);
                secondaryCursor++;
                if (secondary == HdfBuffer.UNDEFINED_ADDRESS) {
                    java.util.Arrays.fill(dblkAddrs, HdfBuffer.UNDEFINED_ADDRESS);
                } else {
                    if (!buf.hasSignature(secondary, EASB)) {
                        throw new HdfFormatException("expected extensible array secondary block 'EASB' at " + secondary);
                    }
                    long ptrs = secondary + 6 + offsets + offsetBytes; // sig, ver, client, header addr, block offset
                    for (int k = 0; k < ndblks; k++) {
                        dblkAddrs[k] = buf.getAddress(ptrs + (long) k * offsets, offsets);
                    }
                }
            }
            for (int k = 0; k < ndblks && linear < maxIndexSet; k++) {
                long addr = dblkAddrs[k];
                long dataBase = addr == HdfBuffer.UNDEFINED_ADDRESS ? -1
                        : validateDataBlock(buf, addr) + 6 + offsets + offsetBytes;
                for (long j = 0; j < dblkNelmts && linear < maxIndexSet; j++, linear++) {
                    if (dataBase >= 0) {
                        addRecord(chunks, buf, dataBase + j * elemSize, linear,
                                clientId, offsets, lengths, chunkBytes, chunksPerDim, chunkDims);
                    }
                }
            }
        }
        return chunks;
    }

    private static long validateDataBlock(HdfBuffer buf, long addr) {
        if (!buf.hasSignature(addr, EADB)) {
            throw new HdfFormatException("expected extensible array data block 'EADB' at " + addr);
        }
        return addr;
    }

    /** Number of data blocks in super block {@code u}. */
    private static int superBlockDataBlocks(int u) {
        return 1 << (u / 2);
    }

    private static void addRecord(List<ChunkRecord> out, HdfBuffer buf, long elemAddr, long linear,
                                  int clientId, int offsets, int lengths, int chunkBytes,
                                  int[] chunksPerDim, int[] chunkDims) {
        long address = buf.getAddress(elemAddr, offsets);
        if (address == HdfBuffer.UNDEFINED_ADDRESS) {
            return; // unallocated chunk -> fill value
        }
        int size;
        int filterMask;
        if (clientId == 1) { // filtered
            size = (int) buf.getUnsignedValue(elemAddr + offsets, lengths);
            filterMask = (int) buf.getUnsignedInt(elemAddr + offsets + lengths);
        } else if (clientId == 0) {
            size = chunkBytes;
            filterMask = 0;
        } else {
            throw new HdfUnsupportedException("unsupported extensible array client id " + clientId);
        }
        out.add(new ChunkRecord(chunkOffset(linear, chunksPerDim, chunkDims), address, size, filterMask));
    }

    /** Maps a row-major linear chunk index to element coordinates. */
    private static long[] chunkOffset(long linear, int[] chunksPerDim, int[] chunkDims) {
        int rank = chunksPerDim.length;
        long[] offset = new long[rank];
        long remaining = linear;
        for (int d = rank - 1; d >= 0; d--) {
            long coord = chunksPerDim[d] == 0 ? 0 : remaining % chunksPerDim[d];
            remaining = chunksPerDim[d] == 0 ? remaining : remaining / chunksPerDim[d];
            offset[d] = coord * chunkDims[d];
        }
        return offset;
    }

    /** Floor log2 of a power-of-two value. */
    private static int log2(int powerOfTwo) {
        return Integer.numberOfTrailingZeros(powerOfTwo);
    }
}
