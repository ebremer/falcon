package com.ebremer.falcon.hdf5;

import java.util.Objects;

/**
 * How {@link Hdf5File#open(java.nio.file.Path, OpenOptions)} reads a file: which other files it may
 * open, and how it sets the extent of virtual datasets with unlimited mappings. Immutable: each setter
 * returns a changed copy.
 *
 * <pre>{@code
 * Hdf5File.open(path, OpenOptions.defaults()
 *         .externalFileAccess(ExternalFileAccess.unrestricted())
 *         .virtualView(OpenOptions.VirtualView.FIRST_MISSING)
 *         .virtualPrintfGap(2));
 * }</pre>
 *
 * The virtual-dataset settings are libhdf5's dataset access properties {@code H5Pset_virtual_view} and
 * {@code H5Pset_virtual_printf_gap}. They apply to every virtual dataset of the file, and to the source
 * files it opens.
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

    private static final OpenOptions DEFAULTS =
            new OpenOptions(ExternalFileAccess.sameDirectory(), VirtualView.LAST_AVAILABLE, 0);

    private final ExternalFileAccess externalFileAccess;
    private final VirtualView virtualView;
    private final long virtualPrintfGap;

    private OpenOptions(ExternalFileAccess externalFileAccess, VirtualView virtualView, long virtualPrintfGap) {
        this.externalFileAccess = externalFileAccess;
        this.virtualView = virtualView;
        this.virtualPrintfGap = virtualPrintfGap;
    }

    /**
     * The defaults: {@link ExternalFileAccess#sameDirectory()}, {@link VirtualView#LAST_AVAILABLE}, and a
     * printf gap of 0, as libhdf5 opens a file by default (apart from the external-file policy).
     */
    public static OpenOptions defaults() {
        return DEFAULTS;
    }

    /** These options with a different policy for other files (see {@link ExternalFileAccess}). */
    public OpenOptions externalFileAccess(ExternalFileAccess access) {
        return new OpenOptions(Objects.requireNonNull(access, "access"), virtualView, virtualPrintfGap);
    }

    /** These options with a different virtual view. */
    public OpenOptions virtualView(VirtualView view) {
        return new OpenOptions(externalFileAccess, Objects.requireNonNull(view, "view"), virtualPrintfGap);
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
        return new OpenOptions(externalFileAccess, virtualView, gap);
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

    @Override
    public String toString() {
        return "OpenOptions[virtualView=" + virtualView + ", virtualPrintfGap=" + virtualPrintfGap + "]";
    }
}
