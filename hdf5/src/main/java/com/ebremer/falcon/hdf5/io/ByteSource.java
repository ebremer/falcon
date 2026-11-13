package com.ebremer.falcon.hdf5.io;

import java.lang.foreign.MemorySegment;

/**
 * Where an {@link HdfBuffer} reads a file's bytes when they are not one segment: a {@link PagedSource},
 * which reads them on demand, or a {@link RedirectedSource}, which reads some of them from elsewhere in the
 * file. The buffer has checked that each range it asks for lies within {@link #size()}.
 */
abstract sealed class ByteSource permits PagedSource, RedirectedSource {

    /** The number of bytes. */
    abstract long size();

    /** The byte at {@code position}. */
    abstract byte get(long position);

    /** The {@code length} bytes at {@code position}, as a segment that starts there: a view, or a copy. */
    abstract MemorySegment read(long position, long length);

    /** Copies {@code length} bytes at {@code position} into {@code destination} from {@code offset}. */
    abstract void copy(long position, byte[] destination, int offset, int length);

    /**
     * Copies as {@link #copy} does, for one request of several a read makes together (see {@link RangeBatch}):
     * the bytes come from cached pages only where that costs the store no more than one read.
     */
    abstract void copyRequest(long position, byte[] destination, int offset, int length);

    /** The source that reads these bytes on demand, each read a request of a store; null if none does. */
    abstract PagedSource paged();
}
