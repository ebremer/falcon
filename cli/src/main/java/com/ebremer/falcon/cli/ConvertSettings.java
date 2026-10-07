package com.ebremer.falcon.cli;

/**
 * How {@code convert} and {@code copy} write arrays.
 *
 * @param zarrFormat  the Zarr format to write, 2 or 3; 0 to keep the source's (copy)
 * @param compression the compression policy
 * @param autoChunks  whether every array's chunks are chosen anew, rather than kept where the source has them
 * @param threads     how many blocks to copy at once
 * @param quiet       whether to say nothing of each array written
 */
record ConvertSettings(int zarrFormat, Compression.Policy compression, boolean autoChunks, int threads, boolean quiet) {
}
