package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.btree.BTreeV2;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
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
 * this class only decodes the fixed-size chunk records it returns. Records are ordered by their scaled
 * offsets, compared dimension by dimension ({@code H5D__bt2_compare}), so {@link #lookup} finds one chunk
 * by descending the tree (P2 PF5).
 */
public final class ChunkBTreeV2 {

    private static final byte[] BTHD = {'B', 'T', 'H', 'D'};
    private static final int RECORD_NON_FILTERED = 10;
    private static final int RECORD_FILTERED = 11;

    private ChunkBTreeV2() {
    }

    public static List<ChunkRecord> readChunks(FileContext ctx, long headerAddress, int chunkBytes,
                                               long[] datasetDims, int[] chunkDims) {
        RecordFormat format = RecordFormat.of(ctx, headerAddress, datasetDims.length);
        List<byte[]> records = BTreeV2.readRecords(ctx, headerAddress);
        List<ChunkRecord> chunks = new ArrayList<>(records.size());
        for (byte[] bytes : records) {
            ChunkRecord chunk = format.decode(bytes, chunkBytes, chunkDims, headerAddress);
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /** Finds a chunk by descending the tree to the record of its scaled offset (P2 PF5). */
    public static ChunkLookup lookup(FileContext ctx, long headerAddress, int chunkBytes, long[] datasetDims,
                                     int[] chunkDims) {
        RecordFormat format = RecordFormat.of(ctx, headerAddress, datasetDims.length);
        return cell -> {
            for (byte[] bytes : BTreeV2.find(ctx, headerAddress, record -> format.compare(record, cell))) {
                ChunkRecord chunk = format.decode(bytes, chunkBytes, chunkDims, headerAddress);
                if (chunk != null) {
                    return chunk;
                }
            }
            return null;
        };
    }

    /** How a tree's chunk records are laid out: filtered or not, and where the scaled offset starts. */
    private record RecordFormat(boolean filtered, int offsets, int sizeWidth, int scaledBase, int rank) {

        static RecordFormat of(FileContext ctx, long headerAddress, int rank) {
            HdfBuffer buf = ctx.buffer();
            if (!buf.hasSignature(headerAddress, BTHD)) {
                throw new HdfFormatException("expected v2 B-tree header 'BTHD' at " + headerAddress);
            }
            int offsets = ctx.sizeOfOffsets();
            int recordType = buf.getUnsignedByte(headerAddress + 5);
            if (recordType != RECORD_NON_FILTERED && recordType != RECORD_FILTERED) {
                throw new HdfFormatException("not a chunk-index v2 B-tree (record type " + recordType + ")");
            }
            boolean filtered = recordType == RECORD_FILTERED;
            int recordSize = buf.getUnsignedShort(headerAddress + 10);
            int sizeWidth = filtered ? recordSize - offsets - 4 - rank * 8 : 0;
            int expected = filtered ? offsets + sizeWidth + 4 + rank * 8 : offsets + rank * 8;
            if (recordSize != expected || (filtered && (sizeWidth < 1 || sizeWidth > 8))) {
                throw new HdfFormatException("chunk v2 B-tree record size " + recordSize + " does not fit rank "
                        + rank + " at " + headerAddress);
            }
            return new RecordFormat(filtered, offsets, sizeWidth, filtered ? offsets + sizeWidth + 4 : offsets, rank);
        }

        /** The chunk a record describes, or null for a record with no chunk. */
        ChunkRecord decode(byte[] bytes, int chunkBytes, int[] chunkDims, long headerAddress) {
            HdfBuffer record = HdfBuffer.of(bytes);
            long address = record.getAddress(0, offsets);
            if (address == HdfBuffer.UNDEFINED_ADDRESS) {
                return null;
            }
            int size = chunkBytes;
            int filterMask = 0;
            if (filtered) {
                long stored = record.getUnsignedValue(offsets, sizeWidth);
                if (stored < 0 || stored > Integer.MAX_VALUE) {
                    throw new HdfFormatException("invalid stored chunk size " + stored + " in v2 B-tree at " + headerAddress);
                }
                size = (int) stored;
                filterMask = (int) record.getUnsignedInt(offsets + sizeWidth);
            }
            long[] offset = new long[rank];
            for (int d = 0; d < rank; d++) {
                offset[d] = record.getUnsignedValue(scaledBase + (long) d * 8, 8) * chunkDims[d];
            }
            return new ChunkRecord(offset, address, size, filterMask);
        }

        /** Compares a record's scaled offset with {@code cell}, dimension by dimension, as unsigned values. */
        int compare(byte[] record, long[] cell) {
            for (int d = 0; d < rank; d++) {
                long scaled = 0;
                for (int b = 7; b >= 0; b--) {
                    scaled = scaled << 8 | (record[scaledBase + d * 8 + b] & 0xff);
                }
                int c = Long.compareUnsigned(scaled, cell[d]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }
    }
}
