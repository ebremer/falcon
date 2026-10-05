package com.ebremer.falcon.hdf5;

import java.util.Objects;

/**
 * How {@link Hdf5File#open(java.nio.file.Path, OpenOptions)} reads a file: which other files it may
 * open, how it sets the extent of virtual datasets with unlimited mappings, and how much it caches.
 * Immutable: each setter returns a changed copy.
 *
 * <pre>{@code
 * Hdf5File.open(path, OpenOptions.defaults()
 *         .externalFileAccess(ExternalFileAccess.unrestricted())
 *         .virtualView(OpenOptions.VirtualView.FIRST_MISSING)
 *         .virtualPrintfGap(2)
 *         .chunkCacheSize(256L << 20));
 * }</pre>
 *
 * The virtual-dataset settings are libhdf5's dataset access properties {@code H5Pset_virtual_view} and
 * {@code H5Pset_virtual_printf_gap}. They, and the cache sizes, apply to every dataset of the file, and to
 * the source files its virtual datasets open.
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
    /** The smallest page size: 512 bytes, libhdf5's smallest file-space page. */
    private static final int MIN_READER_PAGE_SIZE = 512;
    /** The largest page size: 1 GiB. */
    private static final int MAX_READER_PAGE_SIZE = 1 << 30;

    private static final OpenOptions DEFAULTS = new OpenOptions(ExternalFileAccess.sameDirectory(),
            VirtualView.LAST_AVAILABLE, 0, DEFAULT_CHUNK_CACHE_SIZE, DEFAULT_READER_PAGE_SIZE, DEFAULT_READER_CACHE_SIZE);

    private final ExternalFileAccess externalFileAccess;
    private final VirtualView virtualView;
    private final long virtualPrintfGap;
    private final long chunkCacheSize;
    private final int readerPageSize;
    private final long readerCacheSize;

    private OpenOptions(ExternalFileAccess externalFileAccess, VirtualView virtualView, long virtualPrintfGap,
                        long chunkCacheSize, int readerPageSize, long readerCacheSize) {
        this.externalFileAccess = externalFileAccess;
        this.virtualView = virtualView;
        this.virtualPrintfGap = virtualPrintfGap;
        this.chunkCacheSize = chunkCacheSize;
        this.readerPageSize = readerPageSize;
        this.readerCacheSize = readerCacheSize;
    }

    /**
     * The defaults: {@link ExternalFileAccess#sameDirectory()}, {@link VirtualView#LAST_AVAILABLE}, a printf
     * gap of 0, a 16 MiB decoded-chunk cache, and, for a file read through a {@link RangeReader}, 64 KiB
     * pages and a 16 MiB page cache. The virtual-dataset settings are libhdf5's defaults.
     */
    public static OpenOptions defaults() {
        return DEFAULTS;
    }

    /** These options with a different policy for other files (see {@link ExternalFileAccess}). */
    public OpenOptions externalFileAccess(ExternalFileAccess access) {
        return new OpenOptions(Objects.requireNonNull(access, "access"), virtualView, virtualPrintfGap,
                chunkCacheSize, readerPageSize, readerCacheSize);
    }

    /** These options with a different virtual view. */
    public OpenOptions virtualView(VirtualView view) {
        return new OpenOptions(externalFileAccess, Objects.requireNonNull(view, "view"), virtualPrintfGap,
                chunkCacheSize, readerPageSize, readerCacheSize);
    }

    /**
     * These options with a different printf gap: how many missing sources in a row a printf-style
     * virtual mapping may skip while looking for later ones. 0 stops at the first missing source; skipped
     * sources read as the fill value.
     *
     * @throws IllegalArgumentException if {@code gap} is negative
     */
    public OpenOptions virtualPrintfGap(long gap) {
        if (gap < 0) {
            throw new IllegalArgumentException("printf gap must not be negative: " + gap);
        }
        return new OpenOptions(externalFileAccess, virtualView, gap, chunkCacheSize, readerPageSize, readerCacheSize);
    }

    /**
     * These options with a different decoded-chunk cache: up to {@code bytes} of chunks, decoded through
     * their filters, are kept for reads that come back to them (as libhdf5's chunk cache,
     * {@code rdcc_nbytes}, does), least recently used first out. One cache serves every dataset of the
     * file. 0 turns it off; a chunk larger than the cache is not kept. Unfiltered chunks are not cached,
     * since reading them again costs no decoding.
     *
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public OpenOptions chunkCacheSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("chunk cache size must not be negative: " + bytes);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, bytes, readerPageSize, readerCacheSize);
    }

    /**
     * These options with a different page size for a file read through a {@link RangeReader}: Falcon
     * reads metadata in pages of this many bytes, so one request fetches the metadata around what it
     * needs. Larger pages mean fewer requests to a high-latency store, each fetching more. A read of a
     * page or more (most chunks) goes to the reader directly, uncached.
     *
     * @throws IllegalArgumentException if {@code bytes} is not between 512 and 2<sup>30</sup>
     */
    public OpenOptions readerPageSize(int bytes) {
        if (bytes < MIN_READER_PAGE_SIZE || bytes > MAX_READER_PAGE_SIZE) {
            throw new IllegalArgumentException("reader page size must be between " + MIN_READER_PAGE_SIZE + " and "
                    + MAX_READER_PAGE_SIZE + ": " + bytes);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, chunkCacheSize, bytes, readerCacheSize);
    }

    /**
     * These options with a different page cache for a file read through a {@link RangeReader}: up to
     * {@code bytes} of pages are kept, least recently used first out, and at least one page however small
     * the cache.
     *
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public OpenOptions readerCacheSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("reader cache size must not be negative: " + bytes);
        }
        return new OpenOptions(externalFileAccess, virtualView, virtualPrintfGap, chunkCacheSize, readerPageSize, bytes);
    }

    /** Which other files the file may make Falcon open. */
    public ExternalFileAccess externalFileAccess() {
        return externalFileAccess;
    }

    /** How the extent of a virtual dataset with unlimited mappings is set. */
    public VirtualView virtualView() {
        return virtualView;
    }

    /** How many missing sources a printf-style mapping may skip. */
    public long virtualPrintfGap() {
        return virtualPrintfGap;
    }

    /** The most decoded chunks the file keeps, in bytes. */
    public long chunkCacheSize() {
        return chunkCacheSize;
    }

    /** The page size, in bytes, for a file read through a {@link RangeReader}. */
    public int readerPageSize() {
        return readerPageSize;
    }

    /** The most pages a file read through a {@link RangeReader} keeps, in bytes. */
    public long readerCacheSize() {
        return readerCacheSize;
    }

    @Override
    public String toString() {
        return "OpenOptions[virtualView=" + virtualView + ", virtualPrintfGap=" + virtualPrintfGap
                + ", chunkCacheSize=" + chunkCacheSize + ", readerPageSize=" + readerPageSize
                + ", readerCacheSize=" + readerCacheSize + "]";
    }
}
