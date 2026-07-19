package com.ebremer.falcon.hdf5.btree;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Version-2 B-tree (spec section III.A.2): the index for dense link and attribute storage (and other
 * fixed-size record sets). The header ({@code "BTHD"}) describes the record size and root node; leaf
 * nodes ({@code "BTLF"}) hold the records.
 *
 * <p>Stage H5 reads single-leaf trees (depth 0), which cover moderately dense groups and attribute
 * sets. Deeper trees with internal nodes ({@code "BTIN"}) are a later increment.
 */
public final class BTreeV2 {

    private static final byte[] BTHD = {'B', 'T', 'H', 'D'};
    private static final byte[] BTLF = {'B', 'T', 'L', 'F'};

    private BTreeV2() {
    }

    /** Returns every record (each {@code recordSize} raw bytes) in the tree at {@code headerAddress}. */
    public static List<byte[]> readRecords(FileContext ctx, long headerAddress) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(headerAddress, BTHD)) {
            throw new HdfFormatException("expected v2 B-tree signature 'BTHD' at " + headerAddress);
        }
        int offsets = ctx.sizeOfOffsets();
        int recordSize = buf.getUnsignedShort(headerAddress + 10);
        int depth = buf.getUnsignedShort(headerAddress + 12);
        long rootNode = buf.getAddress(headerAddress + 16, offsets);
        int rootRecords = buf.getUnsignedShort(headerAddress + 16 + offsets);

        if (depth != 0) {
            throw new HdfUnsupportedException("v2 B-tree depth " + depth + " (internal nodes) is not yet supported");
        }
        if (!buf.hasSignature(rootNode, BTLF)) {
            throw new HdfFormatException("expected v2 B-tree leaf 'BTLF' at " + rootNode);
        }

        List<byte[]> records = new ArrayList<>(rootRecords);
        long p = rootNode + 6; // signature, version, type
        for (int i = 0; i < rootRecords; i++) {
            records.add(buf.getBytes(p, recordSize));
            p += recordSize;
        }
        return records;
    }
}
