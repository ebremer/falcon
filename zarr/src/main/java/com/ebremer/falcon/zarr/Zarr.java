package com.ebremer.falcon.zarr;

/**
 * Falcon's Zarr reader/writer, implementing the Zarr v3 core specification.
 *
 * <p>Scaffolding for Falcon Phase 2. The store, hierarchy ({@code ZarrGroup} / {@code ZarrArray}),
 * metadata, data-type, chunk-grid, and codec machinery are built out per {@code zarr/PLAN.md}
 * (stages Z0&ndash;Z9). This class currently just pins the targeted format version.
 */
public final class Zarr {

    /** The Zarr storage format version this module targets. */
    public static final int ZARR_FORMAT = 3;

    private Zarr() {
        // No instances.
    }
}
