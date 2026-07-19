/**
 * Checksums used by the HDF5 file format.
 *
 * <p>HDF5 protects version-2+ superblocks, version-2 B-tree nodes, fractal-heap blocks, and
 * checksummed chunks with the <strong>Jenkins lookup3</strong> hash (initial value 0); see
 * {@link com.ebremer.falcon.hdf5.checksum.Lookup3}. The Fletcher-32 checksum used by the
 * {@code fletcher32} filter is added alongside it when the filter pipeline lands (stage H4).
 *
 * <p>Internal package &mdash; not exported.
 */
package com.ebremer.falcon.hdf5.checksum;
