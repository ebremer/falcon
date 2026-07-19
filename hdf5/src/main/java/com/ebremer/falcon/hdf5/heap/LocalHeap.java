package com.ebremer.falcon.hdf5.heap;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Local heap (spec section III.D): a group's name store. Link names are null-terminated strings held
 * in a separate data segment; a symbol-table entry references a name by its byte offset into it.
 *
 * <p>Header layout: {@code "HEAP"} (4) · version (1) · reserved (3) · data-segment size (L) ·
 * free-list head offset (L) · data-segment address (O).
 */
public final class LocalHeap {

    private static final byte[] HEAP = {'H', 'E', 'A', 'P'};

    private final long dataSegmentAddress;
    private final long dataSegmentSize;

    private LocalHeap(long dataSegmentAddress, long dataSegmentSize) {
        this.dataSegmentAddress = dataSegmentAddress;
        this.dataSegmentSize = dataSegmentSize;
    }

    public static LocalHeap parse(FileContext ctx, long addr) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(addr, HEAP)) {
            throw new HdfFormatException("expected local heap signature 'HEAP' at " + addr);
        }
        int lengths = ctx.sizeOfLengths();
        long dataSize = buf.getUnsignedValue(addr + 8, lengths);
        long dataAddr = buf.getAddress(addr + 8 + 2L * lengths, ctx.sizeOfOffsets());
        return new LocalHeap(dataAddr, dataSize);
    }

    /** Reads the null-terminated name at {@code offset} bytes into the heap's data segment. */
    public String name(FileContext ctx, long offset) {
        HdfBuffer buf = ctx.buffer();
        long start = dataSegmentAddress + offset;
        long limit = dataSegmentAddress + dataSegmentSize;
        long p = start;
        while (p < limit && buf.getByte(p) != 0) {
            p++;
        }
        byte[] bytes = buf.getBytes(start, (int) (p - start));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
