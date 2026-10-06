/**
 * The chunk grids and chunk key encodings.
 *
 * <p>{@link com.ebremer.falcon.zarr.chunk.ChunkGrid} provides the grid arithmetic — grid shape, chunk count,
 * each chunk's origin and declared shape, edge-chunk extent, and coordinate&harr;index conversion — for the
 * core {@link com.ebremer.falcon.zarr.chunk.RegularChunkGrid}, which tiles an array into fixed-shape chunks, and
 * the {@link com.ebremer.falcon.zarr.chunk.RectilinearChunkGrid} extension, whose chunk lengths vary along each
 * dimension. {@link com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding} maps chunk coordinates to the
 * array-relative chunk key for the {@code default} and {@code v2} encodings.
 *
 * <p>This package is Zarr-internal and not exported; arrays surface what callers need
 * ({@code ZarrArray.chunkKey}, {@code gridShape}, {@code chunkCount}, {@code chunkSizes}).
 */
package com.ebremer.falcon.zarr.chunk;
