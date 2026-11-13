/**
 * Low-level byte access for HDF5 files.
 *
 * <p>{@link com.ebremer.falcon.hdf5.io.HdfBuffer} is a random-access reader over a
 * {@link java.lang.foreign.MemorySegment} providing the little-endian primitives HDF5 metadata is
 * stored in, plus variable-width unsigned reads for the format's parameterized "size of offsets" /
 * "size of lengths" fields and the all-ones "undefined address" sentinel.
 * {@link com.ebremer.falcon.hdf5.io.MappedHdfFile} memory-maps a file read-only via the Foreign
 * Function &amp; Memory API, so files larger than 2&nbsp;GB are handled without per-mapping limits.
 * {@link com.ebremer.falcon.hdf5.io.ChunkWorkers} runs the filters of one read's or one write's chunks on
 * several threads, the file still read and written by the calling thread alone.
 *
 * <p>Internal package &mdash; not exported.
 */
package com.ebremer.falcon.hdf5.io;
