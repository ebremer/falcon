/**
 * Falcon's HDF5 reader/writer &mdash; pure-JDK, zero-dependency.
 *
 * <p>This package hosts the public API. Internal machinery (superblock, B-trees, heaps, object
 * headers, header messages, datatypes, dataspaces, data layout, and filters) lives in sub-packages
 * of {@code com.ebremer.falcon.hdf5} and is not exported.
 *
 * <p>See {@code hdf5/PLAN.md} for the phased roadmap, and {@code CLAUDE.md} for
 * project conventions.
 *
 * @see <a href="https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html">HDF5 File
 *      Format Specification, Version 4.0</a>
 */
package com.ebremer.falcon.hdf5;
