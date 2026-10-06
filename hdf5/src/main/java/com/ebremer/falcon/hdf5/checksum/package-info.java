/**
 * HDF5's metadata checksum: version-2+ superblocks, version-2 object headers and B-tree nodes, fractal-heap
 * blocks, and the other checksummed structures carry Jenkins' {@code lookup3} hash (initial value 0) of
 * their bytes, verified by {@link com.ebremer.falcon.hdf5.checksum.MetadataChecksum}. The hash itself, and
 * the Fletcher-32 of the {@code fletcher32} filter, are Falcon Core's ({@code com.ebremer.falcon.core.checksum}),
 * shared with Zarr's numcodecs checksums.
 *
 * <p>Internal package &mdash; not exported.
 */
package com.ebremer.falcon.hdf5.checksum;
