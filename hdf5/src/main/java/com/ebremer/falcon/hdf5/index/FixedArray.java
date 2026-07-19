package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed Array chunk index (spec Appendix C.C): the index for chunked datasets with fixed dimensions
 * (a known, constant number of chunks). The header ({@code "FAHD"}) points to a single data block
 * ({@code "FADB"}) holding one entry per chunk, addressed by the chunk's row-major linear index.
 *
 * <p>Non-filtered entries are just a chunk address; filtered entries add the stored size and filter
 * mask. Paged data blocks (very large arrays) are a later increment.
 */
public final class FixedArray {

    private static final byte[] FAHD = {'F', 'A', 'H', 'D'};
    private static final byte[] FADB = {'F', 'A', 'D', 'B'};

    private FixedArray() {
    }

    public static List<ChunkRecord> readChunks(FileContext ctx, long headerAddress, int chunkBytes,
                                               long[] datasetDims, int[] chunkDims) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(headerAddress, FAHD)) {
            throw new HdfFormatException("expected fixed array header 'FAHD' at " + headerAddress);
        }
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        int clientId = buf.getUnsignedByte(headerAddress + 5);
        int entrySize = buf.getUnsignedByte(headerAddress + 6);
        long maxEntries = buf.getUnsignedValue(headerAddress + 8, lengths);
        long dataBlock = buf.getAddress(headerAddress + 8 + lengths, offsets);
        if (dataBlock == HdfBuffer.UNDEFINED_ADDRESS) {
            return List.of();
        }
        if (!buf.hasSignature(dataBlock, FADB)) {
            throw new HdfFormatException("expected fixed array data block 'FADB' at " + dataBlock);
        }

        int rank = datasetDims.length;
        int[] chunksPerDim = new int[rank];
        for (int d = 0; d < rank; d++) {
            chunksPerDim[d] = (int) ((datasetDims[d] + chunkDims[d] - 1) / chunkDims[d]);
        }

        long entries = dataBlock + 6 + offsets; // signature, version, client id, heap header address
        List<ChunkRecord> chunks = new ArrayList<>();
        for (int i = 0; i < maxEntries; i++) {
            long e = entries + (long) i * entrySize;
            long address = buf.getAddress(e, offsets);
            if (address == HdfBuffer.UNDEFINED_ADDRESS) {
                continue; // unallocated chunk
            }
            int size;
            int filterMask;
            if (clientId == 1) { // filtered
                size = (int) buf.getUnsignedValue(e + offsets, lengths);
                filterMask = (int) buf.getUnsignedInt(e + offsets + lengths);
            } else if (clientId == 0) {
                size = chunkBytes;
                filterMask = 0;
            } else {
                throw new HdfUnsupportedException("unsupported fixed array client id " + clientId);
            }
            chunks.add(new ChunkRecord(chunkOffset(i, chunksPerDim, chunkDims), address, size, filterMask));
        }
        return chunks;
    }

    /** Maps a row-major linear chunk index to element coordinates. */
    private static long[] chunkOffset(int linear, int[] chunksPerDim, int[] chunkDims) {
        int rank = chunksPerDim.length;
        long[] offset = new long[rank];
        int remaining = linear;
        for (int d = rank - 1; d >= 0; d--) {
            int coord = chunksPerDim[d] == 0 ? 0 : remaining % chunksPerDim[d];
            remaining = chunksPerDim[d] == 0 ? remaining : remaining / chunksPerDim[d];
            offset[d] = (long) coord * chunkDims[d];
        }
        return offset;
    }
}
