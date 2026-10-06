/**
 * Falcon Core &mdash; code shared by Falcon's format modules: pure-Java decoders (and, for zstd, Blosc
 * with each of its internal compressors, LZ4, LZF, bitshuffle, and bzip2, encoders) for the compression
 * formats both HDF5 filters and Zarr codecs use.
 *
 * <ul>
 *   <li>{@code compress.zstd} &mdash; Zstandard (RFC 8878);</li>
 *   <li>{@code compress.blosc} &mdash; the Blosc container, with BloscLZ, Snappy, and byte shuffle (it
 *       also uses LZ4, zlib, zstd, and bitshuffle), and c-blosc2's chunks, frames, and b2nd arrays;</li>
 *   <li>{@code compress.bitshuffle} &mdash; bitshuffle, alone or with LZ4 or zstd;</li>
 *   <li>{@code compress.lz4} &mdash; the LZ4 block format;</li>
 *   <li>{@code compress.bzip2} &mdash; bzip2 (libbzip2);</li>
 *   <li>{@code compress.lzf} &mdash; LZF (liblzf);</li>
 *   <li>{@code compress.sz} &mdash; SZ 2, the error-bounded lossy compressor (decoding only);</li>
 *   <li>{@code compress.zfp} &mdash; zfp's lossy and lossless floating-point and integer compression (decoding
 *       only).</li>
 * </ul>
 *
 * <p>Like the format modules, it has <strong>no dependencies beyond {@code java.base}</strong> (zlib
 * comes from {@code java.util.zip}). Its packages are exported only to Falcon's own modules: they are
 * shared implementation, not public API.
 */
// The modules exported to are built after this one, so javac cannot see them here ("module not found").
@SuppressWarnings("module")
module com.ebremer.falcon.core {
    exports com.ebremer.falcon.core.compress to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.core.compress.bitshuffle to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.core.compress.blosc to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.core.compress.bzip2 to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.core.compress.lz4 to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.core.compress.lzf to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.core.compress.sz to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.core.compress.zfp to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.core.compress.zstd to com.ebremer.falcon.hdf5, com.ebremer.falcon.zarr;
}
