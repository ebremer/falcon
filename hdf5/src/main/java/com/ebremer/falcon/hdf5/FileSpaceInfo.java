package com.ebremer.falcon.hdf5;

/**
 * A file's free-space management settings, from the File Space Info message (type 23) in the superblock
 * extension. Present only on files written with a non-default file-space strategy (HDF5 1.10.1+); most
 * files have none (see {@link Hdf5File#fileSpaceInfo()}).
 *
 * @param strategy                 how the library allocates and reclaims file space
 * @param persistingFreeSpace      whether free space is tracked persistently across sessions
 * @param freeSpaceSectionThreshold smallest free-space section (in bytes) the managers track
 * @param fileSpacePageSize        page size for paged allocation, in bytes
 * @param pageEndMetadataThreshold minimum free bytes at a page end before a page is left partly filled
 * @param endOfFileAddress         the recorded end-of-file address (undefined for the NONE strategy)
 * @param totalFreeSpace           total reclaimable free space, summed over the free-space managers
 * @param freeSectionCount         number of tracked free-space sections
 */
public record FileSpaceInfo(Strategy strategy, boolean persistingFreeSpace, long freeSpaceSectionThreshold,
                            long fileSpacePageSize, int pageEndMetadataThreshold, long endOfFileAddress,
                            long totalFreeSpace, long freeSectionCount) {

    /** File-space allocation strategy (values ordered to match the on-disk encoding). */
    public enum Strategy {
        /** Free-space managers plus aggregators (the library default). */
        FSM_AGGR,
        /** Paged aggregation: space is allocated in fixed-size pages. */
        PAGE,
        /** Aggregators only, without persistent free-space managers. */
        AGGR,
        /** No free-space tracking: freed space is never reclaimed. */
        NONE;

        /**
         * Maps the on-disk strategy code (0–3) to a {@link Strategy}.
         *
         * @param code the strategy as the File Space Info message stores it
         * @return the strategy
         * @throws HdfFormatException if {@code code} is not 0–3
         */
        public static Strategy fromCode(int code) {
            Strategy[] values = values();
            if (code < 0 || code >= values.length) {
                throw new HdfFormatException("unknown file-space strategy " + code);
            }
            return values[code];
        }
    }
}
