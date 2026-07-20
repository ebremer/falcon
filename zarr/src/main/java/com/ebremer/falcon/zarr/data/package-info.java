/**
 * The array read path: assembling a selection from chunks, and interpreting decoded bytes as typed arrays.
 *
 * <p>{@link com.ebremer.falcon.zarr.data.ChunkAssembler} reads a hyperslab by touching only the chunks
 * that overlap it, decoding each through the codec pipeline (or filling an absent chunk) and copying the
 * intersection into a flat, C-order output buffer. {@link com.ebremer.falcon.zarr.data.Elements} turns
 * that buffer into {@code int[]}/{@code long[]}/{@code float[]}/{@code double[]}.
 *
 * <p>This package is Zarr-internal and not exported; {@code ZarrArray} and {@code Selection} drive it.
 */
package com.ebremer.falcon.zarr.data;
