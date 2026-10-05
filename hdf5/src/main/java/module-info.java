/**
 * Falcon HDF5 &mdash; a pure-JDK HDF5 reader/writer.
 *
 * <p>The module has <strong>no runtime dependencies beyond {@code java.base}</strong>. It uses the
 * Foreign Function &amp; Memory API ({@code java.lang.foreign}) for memory-mapped file access and
 * {@code java.util.zip} for the HDF5 {@code deflate} filter, both of which live in {@code java.base}
 * and therefore require no {@code requires} directive. Every other built-in filter (including
 * {@code szip}, implemented from scratch as CCSDS&nbsp;121.0 extended-Rice coding) and the Jenkins lookup3
 * checksum are hand-written. The third-party filters (LZF, Blosc, LZ4, bitshuffle, Zstandard) use the
 * pure-Java codecs of Falcon Core ({@code com.ebremer.falcon.core}), which the Zarr module shares and
 * which itself depends on nothing beyond {@code java.base}.
 *
 * <p>Only the public API package is exported; all format-level machinery is encapsulated.
 */
@SuppressWarnings("module") // "hdf5" ends in a digit, which javac's module lint flags; the name is the format's
module com.ebremer.falcon.hdf5 {
    requires com.ebremer.falcon.core;

    exports com.ebremer.falcon.hdf5;
    exports com.ebremer.falcon.hdf5.datatype;
}
