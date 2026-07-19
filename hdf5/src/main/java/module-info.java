/**
 * Falcon HDF5 &mdash; a pure-JDK HDF5 reader/writer.
 *
 * <p>The module has <strong>no runtime dependencies beyond {@code java.base}</strong>. It uses the
 * Foreign Function &amp; Memory API ({@code java.lang.foreign}) for memory-mapped file access and
 * {@code java.util.zip} for the HDF5 {@code deflate} filter, both of which live in {@code java.base}
 * and therefore require no {@code requires} directive. Every other filter (including {@code szip},
 * implemented from scratch as CCSDS&nbsp;121.0 extended-Rice coding) and the Jenkins lookup3 checksum
 * are hand-written.
 *
 * <p>Only the public API package is exported; all format-level machinery is encapsulated.
 */
module com.ebremer.falcon.hdf5 {
    exports com.ebremer.falcon.hdf5;
}
