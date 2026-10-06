/**
 * Parsing of {@code zarr.json} node metadata.
 *
 * <p>{@link com.ebremer.falcon.zarr.metadata.Metadata#parse} turns a document into a
 * {@link com.ebremer.falcon.zarr.metadata.NodeMetadata} &mdash; either
 * {@link com.ebremer.falcon.zarr.metadata.GroupMetadata} or
 * {@link com.ebremer.falcon.zarr.metadata.ArrayMetadata}. Parsing validates structure and reports
 * precise, path-qualified {@link com.ebremer.falcon.zarr.ZarrFormatException}s; recognized-but-
 * unimplemented features (a chunk grid other than {@code regular} and {@code rectilinear}, an object data type,
 * a storage transformer that must be understood, {@code zarr_format} 2) become
 * {@link com.ebremer.falcon.zarr.ZarrUnsupportedException}. Semantic
 * interpretation of data types, fill values, and codecs is layered on in later stages.
 *
 * <p>This package is Zarr-internal and not exported; the public hierarchy
 * ({@link com.ebremer.falcon.zarr.ZarrArray} and friends) surfaces what callers need.
 */
package com.ebremer.falcon.zarr.metadata;
