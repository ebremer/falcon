package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.btree.BTreeV2;
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
 * scaled offset times the chunk dimension. The tree itself (of any depth) is walked by {@link BTreeV2};
 * this class only decodes the fixed-size chunk records it returns.
 */
public final class ChunkBTreeV2 {

    private static final byte[] BTHD = {'B', 'T', 'H', 'D'};
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
        int recordSize = buf.getUnsignedShort(headerAddress + 10);
        int rank = datasetDims.length;
        int sizeWidth = recordType == RECORD_FILTERED ? recordSize - offsets - 4 - rank * 8 : 0;

        List<byte[]> records = BTreeV2.readRecords(ctx, headerAddress);
        List<ChunkRecord> chunks = new ArrayList<>(records.size());
        for (byte[] bytes : records) {
            HdfBuffer record = HdfBuffer.of(bytes);
            long address = record.getAddress(0, offsets);
            if (address == HdfBuffer.UNDEFINED_ADDRESS) {
                continue;
            }
            int size;
            int filterMask;
            long scaledBase;
            if (recordType == RECORD_FILTERED) {
                size = (int) record.getUnsignedValue(offsets, sizeWidth);
                filterMask = (int) record.getUnsignedInt(offsets + sizeWidth);
                scaledBase = offsets + sizeWidth + 4;
            } else {
                size = chunkBytes;
                filterMask = 0;
                scaledBase = offsets;
            }
            long[] offset = new long[rank];
            for (int d = 0; d < rank; d++) {
                long scaled = record.getUnsignedValue(scaledBase + (long) d * 8, 8);
                offset[d] = scaled * chunkDims[d];
            }
            chunks.add(new ChunkRecord(offset, address, size, filterMask));
        }
        return chunks;
    }
}
