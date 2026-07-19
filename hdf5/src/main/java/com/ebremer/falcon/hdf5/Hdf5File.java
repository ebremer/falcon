package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.MappedHdfFile;
import com.ebremer.falcon.hdf5.superblock.Superblock;
import java.io.IOException;
import java.nio.file.Path;

/**
 * An open, read-only HDF5 file &mdash; the entry point of Falcon's read API.
 *
 * <p>Open a file, walk its hierarchy from {@link #root()}, and {@link #close()} to unmap it (or use
 * try-with-resources). Reads are backed by a memory mapping, so the file must remain available for the
 * lifetime of this object.
 *
 * <pre>{@code
 * try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
 *     for (String name : h5.root().childNames()) { ... }
 * }
 * }</pre>
 */
public final class Hdf5File implements AutoCloseable {

    private final MappedHdfFile mapped;
    private final Superblock superblock;
    private final Group root;

    private Hdf5File(MappedHdfFile mapped, Superblock superblock, Group root) {
        this.mapped = mapped;
        this.superblock = superblock;
        this.root = root;
    }

    /** Opens and memory-maps an HDF5 file for reading. */
    public static Hdf5File open(Path path) throws IOException {
        MappedHdfFile mapped = MappedHdfFile.openReadOnly(path);
        try {
            Superblock superblock = Superblock.parse(mapped.buffer());
            if (superblock.baseAddress() != 0) {
                throw new HdfUnsupportedException(
                        "non-zero base address (user block) is not yet supported: " + superblock.baseAddress());
            }
            FileContext ctx = new FileContext(mapped.buffer(),
                    superblock.sizeOfOffsets(), superblock.sizeOfLengths(), path);
            Group root = Group.root(ctx, superblock.rootObjectHeaderAddress());
            return new Hdf5File(mapped, superblock, root);
        } catch (RuntimeException e) {
            mapped.close();
            throw e;
        }
    }

    /** The root group ({@code "/"}). */
    public Group root() {
        return root;
    }

    /** The file's path. */
    public Path path() {
        return mapped.path();
    }

    /** The HDF5 superblock format version (0–3) of this file. */
    public int superblockVersion() {
        return superblock.version();
    }

    @Override
    public void close() {
        mapped.close();
    }
}
