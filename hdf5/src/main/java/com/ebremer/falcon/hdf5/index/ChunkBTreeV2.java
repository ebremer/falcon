package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Version-2 B-tree chunk index (spec Appendix C.E): the index for chunked datasets with more than one
 * unlimited dimension, where a chunk's position cannot be reduced to a single linear key. The B-tree
 * header ({@code "BTHD"}) carries a record type identifying the chunk record layout:
 *
 * <ul>
 *   <li><b>type 10</b> — non-filtered chunks: {@code address + rank * uint64 scaled offset};</li>
 *   <li><b>type 11</b> — filtered chunks: {@code address + chunk size + filter mask + rank * uint64
 *       scaled offset}.</li>
 * </ul>
 *
 * <p>Each record's scaled offset is the chunk's coordinate in the chunk grid; the element offset is the
 * scaled offset times the chunk dimension. Only depth-0 trees (a single {@code "BTLF"} leaf) are read
 * here; deep trees with internal ({@code "BTIN"}) nodes are a later increment.
 */
public final class ChunkBTreeV2 {

    private static final byte[] BTHD = {'B', 'T', 'H', 'D'};
    private static final byte[] BTLF = {'B', 'T', 'L', 'F'};
    private static final int RECORD_NON_FILTERED = 10;
    private static final int RECORD_FILTERED = 11;

    private ChunkBTreeV2() {
    }

    public static List<ChunkRecord> readChunks(FileContext ctx, long headerAddress, int chunkBytes,
                                               long[] datasetDims, int[] chunkDims) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(headerAddress, BTHD)) {
            throw new HdfFormatException("expected v2 B-tree header 'BTHD' at " + headerAddress);
        }
        int offsets = ctx.sizeOfOffsets();
        int recordType = buf.getUnsignedByte(headerAddress + 5);
        if (recordType != RECORD_NON_FILTERED && recordType != RECORD_FILTERED) {
            throw new HdfFormatException("not a chunk-index v2 B-tree (record type " + recordType + ")");
        }
        int recordSize = (int) buf.getUnsignedValue(headerAddress + 10, 2);
        int depth = (int) buf.getUnsignedValue(headerAddress + 12, 2);
        long rootNode = buf.getAddress(headerAddress + 16, offsets);
        int rootRecords = (int) buf.getUnsignedValue(headerAddress + 16 + offsets, 2);
        if (rootNode == HdfBuffer.UNDEFINED_ADDRESS || rootRecords == 0) {
            return List.of();
        }
        if (depth != 0) {
            throw new HdfUnsupportedException(
                    "v2 B-tree chunk index with internal nodes (depth " + depth + ") is a later increment");
        }

        int rank = datasetDims.length;
        List<ChunkRecord> chunks = new ArrayList<>(rootRecords);
        readLeaf(ctx, rootNode, rootRecords, recordType, recordSize, rank, chunkBytes, chunkDims, chunks);
        return chunks;
    }

    private static void readLeaf(FileContext ctx, long node, int records, int recordType, int recordSize,
                                 int rank, int chunkBytes, int[] chunkDims, List<ChunkRecord> out) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(node, BTLF)) {
            throw new HdfFormatException("expected v2 B-tree leaf 'BTLF' at " + node);
        }
        int offsets = ctx.sizeOfOffsets();
        long base = node + 6; // signature (4), version (1), type (1)
        int sizeWidth = recordType == RECORD_FILTERED ? recordSize - offsets - 4 - rank * 8 : 0;
        for (int i = 0; i < records; i++) {
            long r = base + (long) i * recordSize;
            long address = buf.getAddress(r, offsets);
            int size;
            int filterMask;
            long scaledBase;
            if (recordType == RECORD_FILTERED) {
                size = (int) buf.getUnsignedValue(r + offsets, sizeWidth);
                filterMask = (int) buf.getUnsignedInt(r + offsets + sizeWidth);
                scaledBase = r + offsets + sizeWidth + 4;
            } else {
                size = chunkBytes;
                filterMask = 0;
                scaledBase = r + offsets;
            }
            long[] offset = new long[rank];
            for (int d = 0; d < rank; d++) {
                long scaled = buf.getUnsignedValue(scaledBase + (long) d * 8, 8);
                offset[d] = scaled * chunkDims[d];
            }
            if (address != HdfBuffer.UNDEFINED_ADDRESS) {
                out.add(new ChunkRecord(offset, address, size, filterMask));
            }
        }
    }
}
