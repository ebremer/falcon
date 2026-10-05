package com.ebremer.falcon.hdf5.io;

import com.ebremer.falcon.hdf5.RangeReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A file's bytes read on demand from a {@link RangeReader}, for files that are not mapped: small reads
 * (the format's metadata) come from cached pages, and large ones (a chunk, a run of contiguous data) go to
 * the reader directly. The page size and how many pages are kept are {@code OpenOptions.readerPageSize}
 * and {@code readerCacheSize} (64 KiB and 16 MiB by default).
 *
 * <p>Thread-safe: the page cache is synchronized, and a page two threads miss at once is simply read
 * twice. An {@link IOException} from the reader surfaces as {@link UncheckedIOException}, since the
 * format parsers above read without checked exceptions.
 */
public final class PagedSource {

    /** The largest read that goes into a heap array; larger ones are allocated off-heap. */
    private static final long MAX_ARRAY = Integer.MAX_VALUE - 8;
    /** The most one call to the reader is asked for (a {@link ByteBuffer} is int-indexed). */
    private static final int MAX_REQUEST = 1 << 30;

    private final RangeReader reader;
    private final long size;
    private final int pageSize;   // bytes per cached page
    private final long maxPages;  // pages kept, at least one
    private final LinkedHashMap<Long, MemorySegment> pages = new LinkedHashMap<>(16, 0.75f, true); // LRU
    private volatile boolean closed;

    private PagedSource(RangeReader reader, long size, int pageSize, long cacheBytes) {
        this.reader = reader;
        this.size = size;
        this.pageSize = pageSize;
        this.maxPages = Math.max(1, cacheBytes / pageSize);
    }

    /**
     * A source over {@code reader}, whose size is read now, reading pages of {@code pageSize} bytes and
     * keeping up to {@code cacheBytes} of them (at least one).
     */
    public static PagedSource open(RangeReader reader, int pageSize, long cacheBytes) throws IOException {
        if (pageSize < 1) {
            throw new IllegalArgumentException("page size must be positive: " + pageSize);
        }
        long size = reader.size();
        if (size < 0) {
            throw new IOException("the range reader reports a negative size: " + size);
        }
        return new PagedSource(reader, size, pageSize, cacheBytes);
    }

    /** The file's size in bytes. */
    public long size() {
        return size;
    }

    /**
     * The {@code length} bytes at {@code position}, as a segment that starts there: a view of a cached
     * page when they lie in one, else a copy. The caller has checked the range lies within the file.
     */
    MemorySegment read(long position, long length) {
        long first = position / pageSize;
        if (length > 0 && first == (position + length - 1) / pageSize) {
            return page(first).asSlice(position - first * pageSize, length);
        }
        MemorySegment out = length > MAX_ARRAY ? Arena.ofAuto().allocate(length) : MemorySegment.ofArray(new byte[(int) length]);
        if (length >= pageSize) {
            fetch(position, out);
        } else if (length > 0) {
            copy(position, out, length);
        }
        return out;
    }

    /** Copies {@code length} bytes at {@code position} into {@code destination} from {@code offset}. */
    void copy(long position, byte[] destination, int offset, int length) {
        MemorySegment target = MemorySegment.ofArray(destination).asSlice(offset, length);
        if (length >= pageSize) {
            fetch(position, target);
        } else {
            copy(position, target, length);
        }
    }

    /** Fills {@code target} from the cached pages covering {@code [position, position + length)}. */
    private void copy(long position, MemorySegment target, long length) {
        long done = 0;
        while (done < length) {
            long at = position + done;
            long index = at / pageSize;
            long inPage = at - index * pageSize;
            MemorySegment page = page(index);
            long n = Math.min(length - done, page.byteSize() - inPage);
            MemorySegment.copy(page, inPage, target, done, n);
            done += n;
        }
    }

    /** Drops the cached pages; later reads fail. The reader is the caller's to close. */
    public void close() {
        closed = true;
        synchronized (pages) {
            pages.clear();
        }
    }

    private MemorySegment page(long index) {
        synchronized (pages) {
            MemorySegment cached = pages.get(index);
            if (cached != null) {
                return cached;
            }
        }
        long start = index * pageSize;
        MemorySegment page = MemorySegment.ofArray(new byte[(int) Math.min(pageSize, size - start)]);
        fetch(start, page);
        synchronized (pages) {
            MemorySegment raced = pages.putIfAbsent(index, page);
            if (raced != null) {
                return raced;
            }
            Iterator<Map.Entry<Long, MemorySegment>> eldest = pages.entrySet().iterator();
            while (pages.size() > maxPages) {
                eldest.next();
                eldest.remove();
            }
        }
        return page;
    }

    /** Reads {@code target.byteSize()} bytes at {@code position} from the reader. */
    private void fetch(long position, MemorySegment target) {
        if (closed) {
            throw new IllegalStateException("the HDF5 file is closed");
        }
        long length = target.byteSize();
        try {
            for (long done = 0; done < length; ) {
                int n = (int) Math.min(MAX_REQUEST, length - done);
                ByteBuffer buffer = target.asSlice(done, n).asByteBuffer();
                reader.read(position + done, buffer);
                if (buffer.hasRemaining()) {
                    throw new EOFException("the range reader returned " + buffer.position() + " of " + n
                            + " bytes at " + (position + done));
                }
                done += n;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + length + " bytes at " + position + " of the HDF5 file", e);
        }
    }

    /** The byte at {@code position} (used for single-byte reads, which are frequent in parsing). */
    byte get(long position) {
        long index = position / pageSize;
        return page(index).get(ValueLayout.JAVA_BYTE, position - index * pageSize);
    }
}
