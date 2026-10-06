/**
 * Checksums both formats use: Jenkins' {@code lookup3} ({@link com.ebremer.falcon.core.checksum.Lookup3}),
 * HDF5's metadata checksum and numcodecs' {@code jenkins_lookup3}, and Fletcher-32
 * ({@link com.ebremer.falcon.core.checksum.Fletcher32}), HDF5's {@code fletcher32} filter and numcodecs'
 * {@code fletcher32}. CRC-32, CRC-32C, and Adler-32 come from {@code java.util.zip}.
 */
package com.ebremer.falcon.core.checksum;
