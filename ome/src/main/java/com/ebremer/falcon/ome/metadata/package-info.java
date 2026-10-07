/**
 * The OME-Zarr metadata model: immutable records for what an OME-Zarr group's attributes say, the same for
 * every version where the versions agree.
 *
 * <p>An image is a {@link com.ebremer.falcon.ome.metadata.Multiscale}: its
 * {@link com.ebremer.falcon.ome.metadata.Axis axes}, its levels
 * ({@link com.ebremer.falcon.ome.metadata.Dataset}s), and the
 * {@link com.ebremer.falcon.ome.metadata.Transformation}s that map their array coordinates to physical ones,
 * with (0.6) named {@link com.ebremer.falcon.ome.metadata.CoordinateSystem}s. Beside it may be the
 * {@link com.ebremer.falcon.ome.metadata.Omero} rendering metadata, and a label image's
 * {@link com.ebremer.falcon.ome.metadata.ImageLabel}. Plates and wells are
 * {@link com.ebremer.falcon.ome.metadata.PlateMetadata} and
 * {@link com.ebremer.falcon.ome.metadata.WellMetadata}; a 0.6 scene is
 * {@link com.ebremer.falcon.ome.metadata.SceneMetadata}.
 *
 * <p>Optional fields are null when the metadata omits them; lists are empty, never null.
 */
package com.ebremer.falcon.ome.metadata;
