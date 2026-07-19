/**
 * Falcon &mdash; a pure-JDK HDF5 reader/writer.
 *
 * <p>The module has <strong>no runtime dependencies beyond {@code java.base}</strong>. It uses
 * the Foreign Function &amp; Memory API ({@code java.lang.foreign}) for memory-mapped file access
 * and {@code java.util.zip} for the HDF5 {@code deflate} filter, both of which live in
 * {@code java.base} and therefore require no {@code requires} directive.
 *
 * <p>Only the public API package is exported; all format-level machinery is encapsulated.
 */
module com.ebremer.falcon {
    exports com.ebremer.falcon;
}
