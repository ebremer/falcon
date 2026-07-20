/**
 * Zarr v3 core data types and the fill-value codec.
 *
 * <p>{@link com.ebremer.falcon.zarr.datatype.DataType} names the core types (bool, the signed and
 * unsigned integers, the floats, complex, and the raw {@code r<N>} family), records each one's element
 * size, and converts a fill value between its {@code zarr.json} JSON form and the element's on-disk
 * bytes. Because Zarr v3 keeps byte order in the {@code bytes} codec rather than the data type, the
 * byte-level operations take an explicit {@link java.nio.ByteOrder}.
 *
 * <p>This package is exported: a data type is part of an array's public description.
 */
package com.ebremer.falcon.zarr.datatype;
