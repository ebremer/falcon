package com.ebremer.falcon.hdf5;

import java.util.Objects;

/**
 * How {@link Hdf5File#open(java.nio.file.Path, OpenOptions)} reads a file: which other files it may
 * open, how it sets the extent of virtual datasets with unlimited mappings, how much it caches, and how
 * many threads decode a read's chunks. Immutable: each setter returns a changed copy.
 *
 * <pre>{@code
 * Hdf5File.open(path, OpenOptions.defaults()
 *         .externalFileAccess(ExternalFileAccess.unrestricted())
 *         .virtualView(OpenOptions.VirtualView.FIRST_MISSING)
 *         .virtualPrintfGap(2)
 *         .chunkCacheSize(256L << 20)
 *         .objectCacheSize(64L << 20));
 * }</pre>
 *
 * The virtual-dataset settings are libhdf5's dataset access properties {@code H5Pset_virtual_view} and
 * {@code H5Pset_virtual_printf_gap}. They, the cache sizes, and the decoding threads apply to every dataset
 * of the file, and to the source files its virtual datasets open.
 */
public final class OpenOptions {

    /** How the extent of a virtual dataset with unlimited mappings is set (libhdf5's virtual view). */
    public enum VirtualView {
        /**
         * The default: as far as the furthest-reaching unlimited mapping, so a shorter mapping leaves the
         * fill value at its end.
         */
        LAST_AVAILABLE,
        /** Only as far as every unlimited mapping reaches, so no mapping leaves a gap at the end. */
        FIRST_MISSING
    }

    /** The default size of the decoded-chunk cache: 16 MiB. */
    public static final long DEFAULT_CHUNK_CACHE_SIZE = 16L << 20;
    /** The default page size for a file read through a {@link RangeReader}: 64 KiB. */
    public static final int DEFAULT_READER_PAGE_SIZE = 64 << 10;
    /** The default page cache for a file read through a {@link RangeReader}: 16 MiB. */
    public static final long DEFAULT_READER_CACHE_SIZE = 16L << 20;
    /** The default size of the cache of objects' metadata: 16 MiB. */
    public static final long DEFAULT_OBJECT_CACHE_SIZE = 16L << 20;
    /**
     * The default number of threads that decode one read's chunks: the processors the Java virtual machine
     * had when this class was loaded ({@link Runtime#availableProcessors()}).
     */
    public static final int DEFAULT_DECODE_THREADS = Runtime.getRuntime().availableProcessors();
    /** The default number of requests one read makes of a {@link RangeReader} at once: 8. */
    public static final int DEFAULT_READER_REQUESTS = 8;
    /** The smallest page size: 512 bytes, libhdf5's smallest file-space page. */
    private static final int MIN_READER_PAGE_SIZE = 512;
    /** The largest page size: 1 GiB. */
    private static final int MAX_READER_PAGE_SIZE = 1 << 30;

    private static final OpenOptions DEFAULTS = new OpenOptions(ExternalFileAccess.sameDirectory(),
            VirtualView.LAST_AVAILABLE, 0, DEFAULT_CHUNK_CACHE_SIZE, DEFAULT_READER_PAGE_SIZE, DEFAULT_READER_CACHE_SIZE,
            DEFAULT_OBJECT_CACHE_SIZE, DEFAULT_DECODE_THREADS, DEFAULT_READER_REQUESTS);

    private final ExternalFileAccess externalFileAccess;
    private final VirtualView virtualView;
    private final long virtualPrintfGap;
    private final long chunkCacheSize;
    private final int readerPageSize;
    private final long readerCacheSize;
    private final long objectCacheSize;
    private final int decodeThreads;
    private final int readerRequests;

    private OpenOptions(ExternalFileAccess externalFileAccess, VirtualView virtualView, long virtualPrintfGap,
                        long chunkCacheSize, int readerPageSize, long readerCacheSize, long objectCacheSize,
                        int decodeThreads, int readerRequests) {
        this.externalFileAccess = externalFileAccess;
        this.virtualView = virtualView;
        this.virtualPrintfGap = virtualPrintfGap;
        this.chunkCacheSize = chunkCacheSize;
        this.readerPageSize = readerPageSize;
        this.readerCacheSize = readerCacheSize;
        this.objectCacheSize = objectCacheSize;
        this.decodeThreads = decodeThreads;
        this.readerRequests = readerRequests;
    }

    /**
     * The defaults: {@link ExternalFileAccess#sameDirectory()}, {@link VirtualView#LAST_AVAILABLE}, a printf
     * gap of 0, a 16 MiB decoded-chunk cache, a 16 MiB object cache, a decoding thread for each processor,
     * and, for a file read through a {@link RangeReader}, 64 KiB pages, a 16 MiB page cache, and 8 requests
     * at once. The virtual-dataset settings are libhdf5's defaults.
     *
     * @return the default options
     */
    public static OpenOptions defaults() {
        return DEFAULTS;
    }

    /**
     * These options with a different policy for other files (see {@link ExternalFileAccess}).
     *
     * @param access which other files the file may make Falcon open (default
     *               {@link ExternalFileAccess#sameDirectory()}); not null
     * @return a copy of these options with that policy
     */
    public OpenOptions externalFileAccess(ExternalFileAccess access) {
        return new OpenOptions(Objects.requireNonNull(access, "access"), virtualView, virtualPrintfGap,
                chunkCacheSize, readerPageSize, readerCacheSize, objectCacheSize, decodeThreads, readerRequests);
    }

    /**
     * These options with a different virtual view.
     *
     * @param view how far a virtual dataset with unlimited mappings extends (default
     *             {@link VirtualView#LAST_AVAILABLE}); not null
     * @return a copy of these options with that view
     */
    public OpenOptions virtualView(VirtualView view) {
        return new OpenOptions(externalFileAccess, Objects.requireNonNull(view, "view"), virtualPrintfGap,
                chunkCacheSize, readerPageSize, readerCacheSize, objectCacheSize, decodeThreads, readerRequests);
    }

    /**
     * These options with a different printf gap: how many missing sources in a row a printf-style
     * virtual mapping may skip while looking for later ones. 0 stops at the first missing source; skipped
     * sources read as the fill value.
     *
     * @param gap the most missing sources in a row to skip (default 0)
     * @return a copy of these options with that gap
     * @throws IllegalArgumentException if {@code gap} is negative
     */
    public OpenOptions virtualPrintfGap(long gap) {
        if (gap < 0) {
            throw new IllegalArgumentException("printf gap must not be negative: " + gap);
        }
        return new OpenOptions(externalFileAccess, virtualView, gap, chunkCacheSize, readerPageSize, readerCacheSize,
                objectCacheSize, decodeThreads, readerRequests);
    }

    /**
     * These options with a different decoded-chunk cache: up to {@code bytes} of chunks, decoded through
     * their filters, are kept for reads that come back to them (as libhdf5's chunk cache,
     * {@code rdcc_nbytes}, does), least recently used first out. One cache serves every dataset of the
     * file. 0 turns it off; a chunk larger than the cache is not kept. Unfiltered chunks are not cached,
     * since reading them again costs no decoding.
     *
     * @param bytes the cache's size in bytes (default {@link #DEFAULT_CHUNK_CACHE_SIZE}, 16 MiB); 0 for none
     * @return a copy of these options with that cache size
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public OpenOptions chunkCacheSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("chunk cache size must not be negative: " + bytes);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, bytes, readerPageSize, readerCacheSize,
                objectCacheSize, decodeThreads, readerRequests);
    }

    /**
     * These options with a different page size for a file read through a {@link RangeReader}: Falcon
     * reads metadata in pages of this many bytes, so one request fetches the metadata around what it
     * needs. Larger pages mean fewer requests to a high-latency store, each fetching more. A read of a
     * page or more (most chunks, and several chunks read together: see {@link #readerRequests}) goes to the
     * reader directly, uncached.
     *
     * @param bytes the page size in bytes (default {@link #DEFAULT_READER_PAGE_SIZE}, 64 KiB)
     * @return a copy of these options with that page size
     * @throws IllegalArgumentException if {@code bytes} is not between 512 and 2<sup>30</sup>
     */
    public OpenOptions readerPageSize(int bytes) {
        if (bytes < MIN_READER_PAGE_SIZE || bytes > MAX_READER_PAGE_SIZE) {
            throw new IllegalArgumentException("reader page size must be between " + MIN_READER_PAGE_SIZE + " and "
                    + MAX_READER_PAGE_SIZE + ": " + bytes);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, chunkCacheSize, bytes, readerCacheSize,
                objectCacheSize, decodeThreads, readerRequests);
    }

    /**
     * These options with a different page cache for a file read through a {@link RangeReader}: up to
     * {@code bytes} of pages are kept, least recently used first out, and at least one page however small
     * the cache.
     *
     * @param bytes the cache's size in bytes (default {@link #DEFAULT_READER_CACHE_SIZE}, 16 MiB)
     * @return a copy of these options with that cache size
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public OpenOptions readerCacheSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("reader cache size must not be negative: " + bytes);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, chunkCacheSize, readerPageSize, bytes,
                objectCacheSize, decodeThreads, readerRequests);
    }

    /**
     * These options with a different object cache: up to {@code bytes} (as estimated) of what the file's
     * objects have read of themselves (their headers, attributes, and links, and a dataset's datatype,
     * shape, layout, and chunk index) are kept for every handle of the same object, least recently used
     * first out (P2 PF6). So {@code group.dataset("x")}, asked again, reads nothing again. An object counts a
     * few hundred bytes, plus its parts: a chunk index read whole counts some 32 bytes a chunk. A handle
     * keeps what it has read, kept here or not. 0 shares nothing: each handle keeps its own.
     *
     * @param bytes the cache's size in bytes, as estimated (default {@link #DEFAULT_OBJECT_CACHE_SIZE},
     *              16 MiB); 0 to share nothing
     * @return a copy of these options with that cache size
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public OpenOptions objectCacheSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("object cache size must not be negative: " + bytes);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, chunkCacheSize, readerPageSize,
                readerCacheSize, bytes, decodeThreads, readerRequests);
    }

    /**
     * These options with a different number of threads decoding a read's chunks (P2 PF9). A read that
     * covers several filtered chunks reverses their filters (inflates them, say) on up to this many threads
     * at once: the reading thread, and threads of the common {@link java.util.concurrent.ForkJoinPool}, so
     * never more than that pool has, and one. The file is read as it is on one thread (see
     * {@link #readerRequests} for a file read through a {@link RangeReader}), and the read returns the same
     * bytes, or fails with the same exception, whatever the number. The chunks being decoded at once are
     * held to an eighth of the heap, so large
     * chunks are decoded fewer at a time. 1 decodes on the reading thread alone, as does a read of one
     * chunk, of less than 64 KiB of chunks (decoded sooner than handed to another thread), or of unfiltered
     * chunks, which have nothing to decode.
     *
     * @param threads the most threads decoding one read's chunks at once, at least 1 (default
     *                {@link #DEFAULT_DECODE_THREADS}, one for each processor); 1 for the reading thread alone
     * @return a copy of these options with that many decoding threads
     * @throws IllegalArgumentException if {@code threads} is less than 1
     */
    public OpenOptions decodeThreads(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("decoding threads must be at least 1: " + threads);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, chunkCacheSize, readerPageSize,
                readerCacheSize, objectCacheSize, threads, readerRequests);
    }

    /**
     * These options with a different number of requests made at once of a {@link RangeReader} (P2 PF11). A
     * read of several chunks of a file read through one does not ask for them one at a time. It puts the
     * chunks' stored bytes into as few requests as their places in the file allow: chunks next to each
     * other, or no more than a page apart ({@link #readerPageSize}; the bytes between are read and dropped),
     * come in one request of up to 8 MiB. And it makes up to this many requests at once: the reading
     * thread makes one after another, and virtual threads make the others beside it, so the reader is called
     * from threads of Falcon's own. The read returns the same bytes, or fails with the same exception,
     * whatever the number. The chunks read ahead of their decoding are held to a sixteenth of the heap. 1
     * still puts chunks together, and makes the requests one after another from the reading thread alone.
     * A request of less than a page comes from the cached pages, as any small read does.
     *
     * <p>The same number of requests reads a chunk index ahead (P2 PF12). The nodes of a version 1 B-tree,
     * which is the index of a file libhdf5 writes by default (a NetCDF-4 file's, say), lie apart in the
     * file, each in a page of its own: a read that needs several of them (all of them, for a whole read)
     * asks for their pages together instead of a page after another, no more at a time than a quarter of
     * the pages kept ({@link #readerCacheSize}).
     *
     * @param requests the most requests one read has in flight at once, at least 1 (default
     *                 {@link #DEFAULT_READER_REQUESTS}, 8); 1 for one at a time
     * @return a copy of these options with that many requests at once
     * @throws IllegalArgumentException if {@code requests} is less than 1
     */
    public OpenOptions readerRequests(int requests) {
        if (requests < 1) {
            throw new IllegalArgumentException("reader requests must be at least 1: " + requests);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, chunkCacheSize, readerPageSize,
                readerCacheSize, objectCacheSize, decodeThreads, requests);
    }

    /**
     * Which other files the file may make Falcon open.
     *
     * @return the policy for other files
     */
    public ExternalFileAccess externalFileAccess() {
        return externalFileAccess;
    }

    /**
     * How the extent of a virtual dataset with unlimited mappings is set.
     *
     * @return the virtual view
     */
    public VirtualView virtualView() {
        return virtualView;
    }

    /**
     * How many missing sources a printf-style mapping may skip.
     *
     * @return the printf gap
     */
    public long virtualPrintfGap() {
        return virtualPrintfGap;
    }

    /**
     * The most decoded chunks the file keeps, in bytes.
     *
     * @return the decoded-chunk cache's size in bytes; 0 if it is off
     */
    public long chunkCacheSize() {
        return chunkCacheSize;
    }

    /**
     * The page size, in bytes, for a file read through a {@link RangeReader}.
     *
     * @return the page size in bytes
     */
    public int readerPageSize() {
        return readerPageSize;
    }

    /**
     * The most pages a file read through a {@link RangeReader} keeps, in bytes.
     *
     * @return the page cache's size in bytes
     */
    public long readerCacheSize() {
        return readerCacheSize;
    }

    /**
     * The most metadata of the file's objects kept for their handles, in bytes (as estimated).
     *
     * @return the object cache's size in bytes; 0 if handles share nothing
     */
    public long objectCacheSize() {
        return objectCacheSize;
    }

    /**
     * The most threads that decode one read's chunks at once.
     *
     * @return the decoding threads; 1 if the reading thread decodes alone
     */
    public int decodeThreads() {
        return decodeThreads;
    }

    /**
     * The most requests one read makes of a {@link RangeReader} at once.
     *
     * @return the requests at once; 1 if they are made one after another
     */
    public int readerRequests() {
        return readerRequests;
    }

    @Override
    public String toString() {
        return "OpenOptions[virtualView=" + virtualView + ", virtualPrintfGap=" + virtualPrintfGap
                + ", chunkCacheSize=" + chunkCacheSize + ", readerPageSize=" + readerPageSize
                + ", readerCacheSize=" + readerCacheSize + ", objectCacheSize=" + objectCacheSize
                + ", decodeThreads=" + decodeThreads + ", readerRequests=" + readerRequests + "]";
    }
}
