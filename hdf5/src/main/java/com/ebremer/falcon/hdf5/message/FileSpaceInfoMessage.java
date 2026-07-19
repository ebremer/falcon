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
 */
public final class FileSpaceInfoMessage {

    private FileSpaceInfoMessage() {
    }

    public static FileSpaceInfo parse(FileContext ctx, long bodyOffset, int bodySize) {
        HdfBuffer buf = ctx.buffer();
        int version = buf.getUnsignedByte(bodyOffset);
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
        long totalFreeSpace = 0;
        long sectionCount = 0;
        long end = bodyOffset + bodySize;
        Set<Long> seen = new HashSet<>();
        while (persist && p + offsets <= end) {
            long address = buf.getAddress(p, offsets);
            p += offsets;
            if (address != HdfBuffer.UNDEFINED_ADDRESS && seen.add(address)) {
                FreeSpaceManager manager = FreeSpaceManager.parse(ctx, address);
                totalFreeSpace += manager.totalSpaceTracked();
                sectionCount += manager.sectionCount();
            }
        }
        return new FileSpaceInfo(strategy, persist, threshold, pageSize, pageEndMetadataThreshold,
                endOfFile, totalFreeSpace, sectionCount);
    }
}
