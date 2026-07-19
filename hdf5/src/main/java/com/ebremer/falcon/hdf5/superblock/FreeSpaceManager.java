package com.ebremer.falcon.hdf5.superblock;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * A free-space manager header (spec section III.H, signature {@code "FSHD"}): the accounting record for
 * one class of reclaimable free space in a file that persists its free space. Falcon reads the header
 * &mdash; the total tracked free space and the section count &mdash; which is what a reader needs to
 * report a file's free space; the per-section serialized list ({@code "FSSE"}), a variable-bit-width
 * encoding of individual section geometry, is not decoded (it has no bearing on reading stored data).
 *
 * <p>Header layout: {@code "FSHD"}(4) · version(1) · client id(1) · total space tracked(L) · total
 * section count(L) · serialized section count(L) · unserialized section count(L) · section class
 * count(2) · shrink percent(2) · expand percent(2) · address-space size in bits(2) · maximum section
 * size(L) · serialized section list address(O) · list bytes used(L) · list bytes allocated(L) ·
 * checksum(4).
 */
public final class FreeSpaceManager {

    private static final byte[] FSHD = {'F', 'S', 'H', 'D'};

    private final long totalSpaceTracked;
    private final long sectionCount;

    private FreeSpaceManager(long totalSpaceTracked, long sectionCount) {
        this.totalSpaceTracked = totalSpaceTracked;
        this.sectionCount = sectionCount;
    }

    /** Total number of bytes of free space this manager tracks. */
    public long totalSpaceTracked() {
        return totalSpaceTracked;
    }

    /** Number of free-space sections this manager tracks. */
    public long sectionCount() {
        return sectionCount;
    }

    /** Parses the free-space manager header at file address {@code addr}. */
    public static FreeSpaceManager parse(FileContext ctx, long addr) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(addr, FSHD)) {
            throw new HdfFormatException("expected free-space manager header 'FSHD' at " + addr);
        }
        int lengths = ctx.sizeOfLengths();
        long p = addr + 6; // signature(4), version(1), client id(1)
        long totalSpace = buf.getUnsignedValue(p, lengths);
        long totalSections = buf.getUnsignedValue(p + lengths, lengths);
        return new FreeSpaceManager(totalSpace, totalSections);
    }
}
