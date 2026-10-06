package com.ebremer.falcon.zarr.codec;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Byte-range access to one stored chunk. The sharding codec uses this to fetch a shard's index and only
 * the sub-chunks a read actually needs, instead of pulling the whole shard.
 *
 * <p>All three methods report an absent chunk the same way: empty.
 */
public interface ChunkBytes {

    /** The stored size of the chunk, or empty if it is absent. */
    OptionalLong size();

    /** The whole chunk, or empty if it is absent. */
    Optional<byte[]> readAll();

    /** {@code length} bytes of the chunk from {@code offset}, or empty if it is absent. */
    Optional<byte[]> readRange(long offset, long length);

    /**
     * The last {@code length} bytes of the chunk (all of it, if shorter), or empty if it is absent. A shard
     * index stored at the end is read this way, without asking the chunk's size first.
     */
    default Optional<byte[]> readSuffix(long length) {
        OptionalLong size = size();
        if (size.isEmpty()) {
            return Optional.empty();
        }
        long start = Math.max(0, size.getAsLong() - length);
        return readRange(start, size.getAsLong() - start);
    }

    /**
     * The stored bytes of a shard's index, {@code length} bytes at the start or the end of the chunk (fewer
     * if the chunk is shorter), or empty if it is absent. A source may cache them.
     */
    default Optional<byte[]> readShardIndex(boolean atStart, long length) {
        return atStart ? readRange(0, length) : readSuffix(length);
    }

    /** A source over an in-memory chunk, used when the bytes have already been fetched. */
    static ChunkBytes of(byte[] bytes) {
        return new ChunkBytes() {
            @Override
            public OptionalLong size() {
                return OptionalLong.of(bytes.length);
            }

            @Override
            public Optional<byte[]> readAll() {
                return Optional.of(bytes);
            }

            @Override
            public Optional<byte[]> readRange(long offset, long length) {
                if (offset < 0 || length < 0 || offset > bytes.length) {
                    throw new IllegalArgumentException("range out of bounds");
                }
                int from = (int) offset;
                int to = (int) Math.min(offset + length, bytes.length);
                byte[] slice = new byte[to - from];
                System.arraycopy(bytes, from, slice, 0, slice.length);
                return Optional.of(slice);
            }
        };
    }
}
