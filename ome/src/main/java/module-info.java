/**
 * Falcon OME-Zarr &mdash; the bioimaging conventions of the
 * <a href="https://ngff.openmicroscopy.org/">OME-Zarr specification</a> (OME-NGFF), versions 0.4, 0.5,
 * and 0.6, on Falcon's Zarr module.
 *
 * <p>OME-Zarr is Zarr: groups and arrays whose attributes describe multiscale images (resolution pyramids
 * with axes and coordinate transformations), label images, high-content screening plates and wells,
 * bioformats2raw collections, and (0.6) scenes. This module reads that metadata into typed records,
 * validates it against the specification, and writes it, building an image's downsampled levels itself.
 * Like the zarr module, it depends on nothing beyond {@code java.base}.
 *
 * <p>The API is in {@code com.ebremer.falcon.ome}; the metadata model it reads and writes is in
 * {@code com.ebremer.falcon.ome.metadata}.
 */
module com.ebremer.falcon.ome {
    requires transitive com.ebremer.falcon.zarr;

    exports com.ebremer.falcon.ome;
    exports com.ebremer.falcon.ome.metadata;
}
