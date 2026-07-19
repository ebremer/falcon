package com.ebremer.falcon.hdf5.group;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Symbol table node (spec section III.B): a leaf of a group's version-1 B-tree, holding a run of
 * {@link SymbolTableEntry symbol-table entries}.
 *
 * <p>Header layout: {@code "SNOD"} (4) · version (1) · reserved (1) · number of symbols (2), then the
 * entries. Each entry is {@code linkNameOffset(O) · objectHeaderAddress(O) · cacheType(4) ·
 * reserved(4) · scratchPad(16)}.
 */
public final class SymbolTableNode {

    private static final byte[] SNOD = {'S', 'N', 'O', 'D'};

    private SymbolTableNode() {
        // Static parser only.
    }

    public static List<SymbolTableEntry> parse(FileContext ctx, long addr) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(addr, SNOD)) {
            throw new HdfFormatException("expected symbol-table node signature 'SNOD' at " + addr);
        }
        int count = buf.getUnsignedShort(addr + 6);
        int offsets = ctx.sizeOfOffsets();
        long entryBase = addr + 8;
        int entrySize = 2 * offsets + 4 + 4 + 16;
        List<SymbolTableEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long e = entryBase + (long) i * entrySize;
            long nameOffset = buf.getUnsignedValue(e, offsets);
            long objectHeader = buf.getAddress(e + offsets, offsets);
            int cacheType = buf.getInt(e + 2L * offsets);
            entries.add(new SymbolTableEntry(nameOffset, objectHeader, cacheType));
        }
        return entries;
    }
}
