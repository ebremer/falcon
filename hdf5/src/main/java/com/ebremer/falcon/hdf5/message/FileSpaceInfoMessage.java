package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.FileSpaceInfo;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.superblock.FreeSpaceManager;
import java.util.HashSet;
import java.util.Set;

/**
 * Parser for the File Space Info message (type 23, spec section IV.A.2.w), which lives in the
 * superblock extension and records a file's free-space management strategy.
 *
 * <p>Version-1 body: {@code version(1) · strategy(1) · persisting free space(1) · section
 * threshold(L) · file-space page size(L) · page-end metadata threshold(2) · end-of-file address(O)},
 * followed, when free space is persisted, by an array of free-space-manager header addresses. Falcon
 * follows each defined address to its {@link FreeSpaceManager} header and sums the tracked free space
 * and section counts.
 *
 * <p>Version 0 (HDF5 1.10.0) is {@code version(1) · strategy(1) · section threshold(L)}, followed for the
 * "all, persisting" strategy by six free-space-manager addresses. Like libhdf5 ({@code H5O__fsinfo_decode})
 * Falcon maps it onto version 1: the old strategies 1&ndash;4 (all persisting, all, aggregators with the
 * driver, driver only) become FSM_AGGR with and without persisting free space, AGGR, and NONE; the page
 * size and page-end threshold take their defaults (4096 and 0); the threshold is kept only for the two
 * "all" strategies; and no end-of-file address is recorded.
 */
public final class FileSpaceInfoMessage {

    private FileSpaceInfoMessage() {
    }

    // libhdf5's defaults for what a version-0 message does not record.
    private static final long DEFAULT_THRESHOLD = 1;     // H5F_FREE_SPACE_THRESHOLD_DEF
    private static final long DEFAULT_PAGE_SIZE = 4096;  // H5F_FILE_SPACE_PAGE_SIZE_DEF
    private static final int DEFAULT_PAGE_END_THRESHOLD = 0; // H5F_FILE_SPACE_PGEND_META_THRES

    public static FileSpaceInfo parse(FileContext ctx, long bodyOffset, int bodySize) {
        HdfBuffer buf = ctx.buffer();
        int version = buf.getUnsignedByte(bodyOffset);
        if (version == 0) {
            return parseVersion0(ctx, bodyOffset, bodySize);
        }
        if (version != 1) {
            throw new HdfFormatException("unsupported file space info message version " + version);
        }
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        FileSpaceInfo.Strategy strategy = FileSpaceInfo.Strategy.fromCode(buf.getUnsignedByte(bodyOffset + 1));
        boolean persist = buf.getUnsignedByte(bodyOffset + 2) != 0;
        long p = bodyOffset + 3;
        long threshold = buf.getUnsignedValue(p, lengths);
        p += lengths;
        long pageSize = buf.getUnsignedValue(p, lengths);
        p += lengths;
        int pageEndMetadataThreshold = buf.getUnsignedShort(p);
        p += 2;
        long endOfFile = buf.getAddress(p, offsets);
        p += offsets;

        // When persisting, an array of free-space-manager header addresses fills the message tail.
        long[] tracked = persist ? managers(ctx, p, bodyOffset + bodySize, Integer.MAX_VALUE) : new long[2];
        return new FileSpaceInfo(strategy, persist, threshold, pageSize, pageEndMetadataThreshold,
                endOfFile, tracked[0], tracked[1]);
    }

    private static FileSpaceInfo parseVersion0(FileContext ctx, long bodyOffset, int bodySize) {
        HdfBuffer buf = ctx.buffer();
        int lengths = ctx.sizeOfLengths();
        if (bodySize < 2 + lengths) {
            throw new HdfFormatException("version-0 file space info message of " + bodySize + " bytes is too short");
        }
        int oldStrategy = buf.getUnsignedByte(bodyOffset + 1);
        long threshold = buf.getUnsignedValue(bodyOffset + 2, lengths);
        long p = bodyOffset + 2 + lengths;
        return switch (oldStrategy) {
            case 1 -> { // H5F_FILE_SPACE_ALL_PERSIST: then the six managers' addresses
                long[] tracked = managers(ctx, p, bodyOffset + bodySize, 6);
                yield new FileSpaceInfo(FileSpaceInfo.Strategy.FSM_AGGR, true, threshold, DEFAULT_PAGE_SIZE,
                        DEFAULT_PAGE_END_THRESHOLD, HdfBuffer.UNDEFINED_ADDRESS, tracked[0], tracked[1]);
            }
            case 2 -> version0(FileSpaceInfo.Strategy.FSM_AGGR, threshold);   // H5F_FILE_SPACE_ALL
            case 3 -> version0(FileSpaceInfo.Strategy.AGGR, DEFAULT_THRESHOLD); // H5F_FILE_SPACE_AGGR_VFD
            case 4 -> version0(FileSpaceInfo.Strategy.NONE, DEFAULT_THRESHOLD); // H5F_FILE_SPACE_VFD
            default -> throw new HdfFormatException("invalid version-0 file-space strategy " + oldStrategy);
        };
    }

    private static FileSpaceInfo version0(FileSpaceInfo.Strategy strategy, long threshold) {
        return new FileSpaceInfo(strategy, false, threshold, DEFAULT_PAGE_SIZE, DEFAULT_PAGE_END_THRESHOLD,
                HdfBuffer.UNDEFINED_ADDRESS, 0, 0);
    }

    /**
     * Follows up to {@code max} free-space-manager addresses from {@code p} to {@code end}; returns the
     * total free space and section count they track.
     */
    private static long[] managers(FileContext ctx, long p, long end, int max) {
        HdfBuffer buf = ctx.buffer();
        int offsets = ctx.sizeOfOffsets();
        long totalFreeSpace = 0;
        long sectionCount = 0;
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < max && p + offsets <= end; i++, p += offsets) {
            long address = buf.getAddress(p, offsets);
            if (address != HdfBuffer.UNDEFINED_ADDRESS && seen.add(address)) {
                FreeSpaceManager manager = FreeSpaceManager.parse(ctx, address);
                totalFreeSpace += manager.totalSpaceTracked();
                sectionCount += manager.sectionCount();
            }
        }
        return new long[] {totalFreeSpace, sectionCount};
    }
}
