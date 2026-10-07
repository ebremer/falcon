/**
 * OME-Zarr 0.4, 0.5, and 0.6 on Falcon's Zarr module: read, validated, and written.
 *
 * <p>{@link com.ebremer.falcon.ome.OmeZarr#open} opens a group of any Zarr store and tells what it is; views
 * read each kind: a {@link com.ebremer.falcon.ome.MultiscaleImage} (an image's levels, axes, pixel sizes,
 * channels, and label images), a {@link com.ebremer.falcon.ome.Plate} and its
 * {@link com.ebremer.falcon.ome.Well}s, an {@link com.ebremer.falcon.ome.ImageCollection} (bioformats2raw), and a
 * {@link com.ebremer.falcon.ome.Scene} (0.6), which composes the transformations between coordinate systems.
 * {@link com.ebremer.falcon.ome.OmeMetadata} reads and writes one group's metadata as its
 * {@link com.ebremer.falcon.ome.OmeVersion} lays it out.
 *
 * <p>{@link com.ebremer.falcon.ome.OmeValidator} checks metadata, and the hierarchy below it, against the
 * specification. {@link com.ebremer.falcon.ome.MultiscaleImageWriter} writes images and label images from a
 * {@link com.ebremer.falcon.ome.PixelSource}, building their smaller levels
 * ({@link com.ebremer.falcon.ome.Downsampling}); {@code OmeZarr} creates plates, wells, and scenes.
 *
 * <p>Metadata that cannot be read is an {@link com.ebremer.falcon.ome.OmeFormatException}, a version Falcon
 * does not read a {@link com.ebremer.falcon.zarr.ZarrUnsupportedException}; what is wrong with the call is the
 * JDK's own exceptions, as in the zarr module.
 */
package com.ebremer.falcon.ome;
