package com.ebremer.falcon.hdf5.layout;

/**
 * One stored chunk located by a chunk index.
 *
 * @param offset      the chunk's element coordinates (per dataset dimension)
 * @param address     file address of the (possibly filtered) chunk data
 * @param size        stored size of the chunk in bytes (post-filter)
 * @param filterMask  bit {@code i} set means filter {@code i} was <em>not</em> applied to this chunk
 */
public record ChunkRecord(long[] offset, long address, int size, int filterMask) {
}
