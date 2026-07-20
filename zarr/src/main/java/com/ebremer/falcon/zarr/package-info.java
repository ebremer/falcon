/**
 * Public API for Falcon's Zarr reader/writer (Zarr v3 core specification).
 *
 * <p>{@link com.ebremer.falcon.zarr.Zarr#open} reads a hierarchy from a
 * {@link com.ebremer.falcon.zarr.store.Store} and returns its root
 * {@link com.ebremer.falcon.zarr.ZarrNode} &mdash; a {@link com.ebremer.falcon.zarr.ZarrGroup} or a
 * {@link com.ebremer.falcon.zarr.ZarrArray}. Groups navigate to their children; arrays describe their
 * shape, data type, chunk shape, fill value, and codecs. Reading element data arrives in a later stage
 * (see {@code zarr/PLAN.md}).
 */
package com.ebremer.falcon.zarr;
