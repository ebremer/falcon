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

    /**
     * A view of the {@code length} bytes of this chunk from {@code offset}: how a sub-chunk that is itself a
     * shard is read in part (nested sharding), each read of the view one range read of this source. A read
     * is cut off at the view's end; a view past the end of the chunk reads short (the caller finds it
     * truncated), and one of an absent chunk reads empty.
     *
     * @throws IllegalArgumentException if {@code offset} or {@code length} is negative
     */
    default ChunkBytes slice(long offset, long length) {
        if (offset < 0 || length < 0 || length > Long.MAX_VALUE - offset) {
            throw new IllegalArgumentException("invalid slice " + offset + "+" + length);
        }
        ChunkBytes parent = this;
        return new ChunkBytes() {
            @Override
            public OptionalLong size() {
                return OptionalLong.of(length);
            }

            @Override
            public Optional<byte[]> readAll() {
                return readRange(0, length);
            }

            @Override
            public Optional<byte[]> readRange(long from, long count) {
                if (from < 0 || count < 0) {
                    throw new IllegalArgumentException("range out of bounds");
                }
                if (from >= length) {
                    return Optional.of(new byte[0]); // the view is of a chunk the caller found present
                }
                return parent.readRange(offset + from, Math.min(count, length - from));
            }

            @Override
            public Optional<byte[]> readSuffix(long count) {
                long start = Math.max(0, length - count);
                return readRange(start, length - start);
            }
        };
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
                if (offset < 0 || length < 0) {
                    throw new IllegalArgumentException("range out of bounds");
                }
                if (offset >= bytes.length) {
                    return Optional.of(new byte[0]); // past the end, as Store.getRange reads it
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
