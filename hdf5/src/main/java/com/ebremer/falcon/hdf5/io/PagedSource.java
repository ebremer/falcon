package com.ebremer.falcon.hdf5.io;

import com.ebremer.falcon.hdf5.RangeReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A file's bytes read on demand from a {@link RangeReader}, for files that are not mapped: small reads
 * (the format's metadata) come from cached pages, and large ones (a chunk, a run of contiguous data) go to
 * the reader directly. The page size and how many pages are kept are {@code OpenOptions.readerPageSize}
 * and {@code readerCacheSize} (64 KiB and 16 MiB by default). Pages a caller says it is about to read are
 * read ahead of it, several at once ({@link #ahead}: P2 PF12).
 *
 * <p>Thread-safe: the page cache is synchronized, and a page two threads miss at once is simply read
 * twice. The reader is called from the thread that reads, and, by a read of several ranges at once (see
 * {@link RangeBatch}) or of pages ahead, from virtual threads beside it. An {@link IOException} from the
 * reader surfaces as {@link UncheckedIOException}, since the format parsers above read without checked
 * exceptions.
 */
public final class PagedSource extends ByteSource {

    /** The largest read that goes into a heap array; larger ones are allocated off-heap. */
    private static final long MAX_ARRAY = Integer.MAX_VALUE - 8;
    /** The most one call to the reader is asked for (a {@link ByteBuffer} is int-indexed). */
    private static final int MAX_REQUEST = 1 << 30;
    /** A read ahead fills no more than this fraction of the pages kept, so what it reads is there when read. */
    private static final int AHEAD_FRACTION = 4;

    private final RangeReader reader;
    private final long size;
    private final int pageSize;   // bytes per cached page
    private final long maxPages;  // pages kept, at least one
    private final int requests;   // the most requests one read makes at once (see RangeBatch)
    private final LinkedHashMap<Long, MemorySegment> pages = new LinkedHashMap<>(16, 0.75f, true); // LRU
    private volatile boolean closed;

    private PagedSource(RangeReader reader, long size, int pageSize, long cacheBytes, int requests) {
        this.reader = reader;
        this.size = size;
        this.pageSize = pageSize;
        this.maxPages = Math.max(1, cacheBytes / pageSize);
        this.requests = requests;
    }

    /**
     * A source over {@code reader}, whose size is read now, reading pages of {@code pageSize} bytes and
     * keeping up to {@code cacheBytes} of them (at least one), each read making one request at a time.
     */
    public static PagedSource open(RangeReader reader, int pageSize, long cacheBytes) throws IOException {
        return open(reader, pageSize, cacheBytes, 1);
    }

    /**
     * As above, a read of several ranges (a read's chunks: see {@link RangeBatch}) making up to
     * {@code requests} requests of the reader at once.
     */
    public static PagedSource open(RangeReader reader, int pageSize, long cacheBytes, int requests) throws IOException {
        if (pageSize < 1) {
            throw new IllegalArgumentException("page size must be positive: " + pageSize);
        }
        if (requests < 1) {
            throw new IllegalArgumentException("requests at once must be at least 1: " + requests);
        }
        long size = reader.size();
        if (size < 0) {
            throw new IOException("the range reader reports a negative size: " + size);
        }
        return new PagedSource(reader, size, pageSize, cacheBytes, requests);
    }

    @Override
    PagedSource paged() {
        return this;
    }

    @Override
    MemorySegment stored(long position, long length) {
        return null; // read on demand: no bytes to view
    }

    /** The bytes in a page. */
    int pageSize() {
        return pageSize;
    }

    /** The most requests one read makes of the reader at once. */
    int requests() {
        return requests;
    }

    /** The file's size in bytes. */
    @Override
    public long size() {
        return size;
    }

    /**
     * The {@code length} bytes at {@code position}, as a segment that starts there: a view of a cached
     * page when they lie in one, else a copy. The caller has checked the range lies within the file.
     */
    @Override
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
    @Override
    void copy(long position, byte[] destination, int offset, int length) {
        MemorySegment target = MemorySegment.ofArray(destination).asSlice(offset, length);
        if (length >= pageSize) {
            fetch(position, target);
        } else {
            copy(position, target, length);
        }
    }

    /**
     * Copies as {@link #copy} does, but never in two reads of the store where one will do: bytes short of a
     * page come from the cached pages if at most one of those has to be read first, and else, lying across
     * two pages neither of which is cached, are read as they are, in one request.
     */
    @Override
    void copyRequest(long position, byte[] destination, int offset, int length) {
        MemorySegment target = MemorySegment.ofArray(destination).asSlice(offset, length);
        if (length >= pageSize || uncached(position, length) > 1) {
            fetch(position, target);
        } else {
            copy(position, target, length);
        }
    }

    /** How many of the pages that hold the {@code length} bytes at {@code position} are not cached. */
    private int uncached(long position, long length) {
        if (length == 0) {
            return 0;
        }
        long last = (position + length - 1) / pageSize;
        int uncached = 0;
        synchronized (pages) {
            for (long index = position / pageSize; index <= last; index++) {
                if (!pages.containsKey(index)) { // not a use of the page: its place in the cache is kept
                    uncached++;
                }
            }
        }
        return uncached;
    }

    /**
     * Reads into the cache the pages that hold the {@code length} bytes at each of
     * {@code positions[from..to)} and are not there: pages next to each other, or with one between them, in
     * one request of at most {@link RangeBatch#MAX_REQUEST} bytes, and up to {@link #requests} requests at
     * once. A range is taken if its pages, with those of the ranges before it, are no more than a quarter of
     * what the cache keeps (the first is cut to that). A request that fails reads nothing, and no further
     * one is started: the pages are read, and the failure thrown, by the read that needs them.
     */
    @Override
    int ahead(long[] positions, int from, int to, long length) {
        long room = maxPages / AHEAD_FRACTION;
        if (room < 1 || length <= 0 || closed) {
            return to - from; // nothing read ahead would be kept: every range is read in its turn
        }
        TreeSet<Long> wanted = new TreeSet<>();
        int taken = 0;
        synchronized (pages) {
            List<Long> missing = new ArrayList<>();
            for (int i = from; i < to; i++, taken++) {
                long position = positions[i];
                if (position < 0 || position >= size) {
                    continue; // none of it is in the file, which its read will say
                }
                long last = (position + Math.min(length, size - position) - 1) / pageSize;
                missing.clear();
                for (long index = position / pageSize; index <= last && missing.size() <= room; index++) {
                    if (!pages.containsKey(index) && !wanted.contains(index)) { // not a use of the page
                        missing.add(index);
                    }
                }
                if (wanted.size() + missing.size() > room) {
                    if (!wanted.isEmpty()) {
                        break; // the next read ahead's first
                    }
                    missing.subList((int) room, missing.size()).clear();
                }
                wanted.addAll(missing);
            }
        }
        if (wanted.isEmpty()) {
            return taken;
        }
        long most = Math.max(1, RangeBatch.MAX_REQUEST / pageSize); // the pages of one request
        List<long[]> runs = new ArrayList<>(); // each the first and the last page of a request
        long[] run = null;
        for (long index : wanted) {
            if (run == null || index - run[1] > 2 || index - run[0] >= most) {
                run = new long[] {index, index};
                runs.add(run);
            } else {
                run[1] = index;
            }
        }
        RangeBatch.atOnce(requests, runs.size(), request -> load(runs.get(request)[0], runs.get(request)[1]));
        return taken;
    }

    /** Reads pages {@code first} to {@code last} in one request, into the cache; false if it failed. */
    private boolean load(long first, long last) {
        try {
            long start = first * pageSize;
            byte[] bytes = new byte[(int) (Math.min(size, (last + 1) * pageSize) - start)];
            fetch(start, MemorySegment.ofArray(bytes));
            if (first == last) {
                keep(first, MemorySegment.ofArray(bytes));
                return true;
            }
            for (long index = first; index <= last; index++) { // each page its own bytes, to be dropped alone
                int at = (int) ((index - first) * pageSize);
                keep(index, MemorySegment.ofArray(Arrays.copyOfRange(bytes, at, Math.min(bytes.length, at + pageSize))));
            }
            return true;
        } catch (RuntimeException | Error unread) {
            return false; // a hint: the read that needs these pages reads them, and fails if it must
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
        return keep(index, page);
    }

    /** Puts page {@code index} in the cache, dropping the pages longest unused beyond those kept; the page there. */
    private MemorySegment keep(long index, MemorySegment page) {
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
    @Override
    byte get(long position) {
        long index = position / pageSize;
        return page(index).get(ValueLayout.JAVA_BYTE, position - index * pageSize);
    }
}
