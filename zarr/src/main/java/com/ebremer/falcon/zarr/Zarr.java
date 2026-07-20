package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.file.Path;

/**
 * Entry point for reading a Zarr v3 hierarchy.
 *
 * <p>{@link #open(Store)} reads the root {@code zarr.json} and returns the root {@link ZarrNode} &mdash;
 * a {@link ZarrGroup} or a {@link ZarrArray}. From a group, navigate to children with
 * {@link ZarrGroup#child}, {@link ZarrGroup#group}, {@link ZarrGroup#array}, and the listing accessors.
 */
public final class Zarr {

    /** The Zarr storage format version this module targets. */
    public static final int ZARR_FORMAT = 3;

    private Zarr() {
    }

    /**
     * Opens the root node of the hierarchy in {@code store}.
     *
     * @throws ZarrFormatException      if there is no root {@code zarr.json} or it is malformed
     * @throws ZarrUnsupportedException if the store is Zarr v2 or uses an unimplemented feature
     */
    public static ZarrNode open(Store store) {
        if (!store.exists("zarr.json")) {
            if (store.exists(".zgroup") || store.exists(".zarray") || store.exists(".zattrs")) {
                throw new ZarrUnsupportedException(
                        "this looks like a Zarr v2 store; v2 support is planned (see PLAN.md, stage Z8)");
            }
            throw new ZarrFormatException("no root zarr.json found: not a Zarr v3 store");
        }
        return ZarrNode.open(store, "");
    }

    /** Opens the root node of the hierarchy in the store directory at {@code directory} (read-only). */
    public static ZarrNode open(Path directory) {
        return open(FileSystemStore.openReadOnly(directory));
    }

    /**
     * Opens the hierarchy root as a group.
     *
     * @throws IllegalStateException if the root is an array
     */
    public static ZarrGroup openGroup(Store store) {
        return open(store).asGroup();
    }

    /**
     * Opens the hierarchy root as an array.
     *
     * @throws IllegalStateException if the root is a group
     */
    public static ZarrArray openArray(Store store) {
        return open(store).asArray();
    }
}
