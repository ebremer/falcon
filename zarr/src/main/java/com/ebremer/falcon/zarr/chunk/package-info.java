/**
 * The regular chunk grid and chunk key encodings.
 *
 * <p>{@link com.ebremer.falcon.zarr.chunk.RegularChunkGrid} tiles an array into fixed-shape chunks and
 * provides the grid arithmetic — grid shape, chunk count, chunk origin, edge-chunk extent, and
 * coordinate&harr;index conversion. {@link com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding} maps chunk
 * coordinates to the array-relative chunk key for the {@code default} and {@code v2} encodings.
 *
 * <p>This package is Zarr-internal and not exported; arrays surface what callers need
 * ({@code ZarrArray.chunkKey}, {@code gridShape}, {@code chunkCount}).
 */
package com.ebremer.falcon.zarr.chunk;
