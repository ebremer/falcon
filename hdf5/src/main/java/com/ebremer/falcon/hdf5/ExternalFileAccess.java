package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which other files an HDF5 file may make Falcon open: the raw-data files a dataset's External File
 * List names, and the source files of virtual datasets. Both are names written inside the file, so an
 * untrusted file could otherwise point Falcon at any local file (an absolute path, or one that climbs
 * out with {@code ..}), or, on Windows, at a UNC path that makes the JVM connect to a remote host.
 *
 * <ul>
 *   <li>{@link #sameDirectory()} (the default): a name is resolved against the HDF5 file's own
 *       directory, and is allowed only if the result stays inside that directory or one of its
 *       subdirectories. Absolute names elsewhere and names that climb out are refused.</li>
 *   <li>{@link #allowDirectory(Path)}: also allows files under another directory, and looks a relative
 *       name up there too (Falcon's equivalent of libhdf5's {@code HDF5_EXTFILE_PREFIX} and
 *       {@code HDF5_VDS_PREFIX}).</li>
 *   <li>{@link #unrestricted()}: libhdf5's behaviour, honouring any name.</li>
 *   <li>{@link #none()}: never opens another file.</li>
 *   <li>{@link #resolvedBy(Resolver)}: an application's {@link Resolver} opens each name, wherever the
 *       files are: for a file read through a {@link RangeReader}, say, whose other files lie next to it in
 *       the same object store.</li>
 * </ul>
 *
 * A refused name fails the read with {@link HdfUnsupportedException} (a virtual dataset does not
 * silently substitute its fill value). Paths are compared after normalization; symbolic links inside an
 * allowed directory are not resolved. A name is read as a path of the HDF5 file's own file system (a zip
 * file system, say, for a file inside one).
 *
 * <p>A file opened from bytes or a {@link RangeReader} has no directory of its own. Then only
 * {@link #allowDirectory(Path) allowed directories} are searched and allowed, and
 * {@link #unrestricted()} resolves relative names against the working directory, as libhdf5 does for a
 * file in memory; a {@link #resolvedBy(Resolver) resolver} reaches files anywhere.
 *
 * <p>A virtual dataset's source is looked for where libhdf5 looks ({@code H5F_prefix_open_file}), among
 * the places the policy allows: an absolute name as written, and then, as when a file was moved with its
 * sources, by its file name alone; a relative name in the HDF5 file's directory. Both then try each
 * allowed directory and, under {@link #unrestricted()}, the working directory. A source found nowhere is
 * missing, and libhdf5 and Falcon read its region as the fill value. But when the name's own location is
 * refused and no allowed candidate exists, the read fails rather than filling: the refused file may be
 * the real source.
 *
 * <pre>{@code
 * Hdf5File.open(path, ExternalFileAccess.sameDirectory().allowDirectory(Path.of("/data/raw")));
 * }</pre>
 */
public final class ExternalFileAccess {

    /** What a file an HDF5 file names is for. */
    public enum Purpose {
        /** A dataset's external raw data (its External File List), read as plain bytes. */
        RAW_DATA,
        /** A virtual dataset's source: another HDF5 file. */
        VIRTUAL_SOURCE
    }

    /**
     * Opens the other files an HDF5 file names, for {@link #resolvedBy(Resolver)}: the application
     * decides where each name leads, and whether it may be opened at all.
     *
     * <pre>{@code
     * // the file and its sources are objects under one prefix of a store
     * ExternalFileAccess access = ExternalFileAccess.resolvedBy((name, purpose) ->
     *         name.contains("..") || name.startsWith("/") ? null : store.reader(prefix + name));
     * try (Hdf5File h5 = Hdf5File.open(store.reader(prefix + "data.h5"), OpenOptions.defaults().externalFileAccess(access))) {
     *     ...
     * }
     * }</pre>
     */
    @FunctionalInterface
    public interface Resolver {
        /**
         * A reader for the file {@code name}, as the HDF5 file writes it, or {@code null} if there is no
         * such file: a virtual dataset then reads that source's region as the fill value, as libhdf5 does
         * for a missing source, and a read of external raw data fails. Names in the files opened through
         * this resolver (a virtual source's own sources) come here too, as their files write them.
         *
         * <p>A reader that is {@link AutoCloseable} is closed once Falcon is done with it: after the read
         * of external raw data, and for a virtual dataset's source when the HDF5 file that opened it closes.
         *
         * @throws HdfUnsupportedException to refuse the name: the read fails, as when another policy refuses
         * @throws IOException if the file cannot be opened: for a virtual dataset's source, the source is
         *         taken to be missing; for external raw data, the read fails
         */
        RangeReader open(String name, Purpose purpose) throws IOException;
    }

    private final boolean enabled;
    private final boolean unrestricted;
    private final List<Path> directories;
    private final Resolver resolver;

    private ExternalFileAccess(boolean enabled, boolean unrestricted, List<Path> directories, Resolver resolver) {
        this.enabled = enabled;
        this.unrestricted = unrestricted;
        this.directories = List.copyOf(directories);
        this.resolver = resolver;
    }

    /** The default: files inside the HDF5 file's own directory (or its subdirectories) only. */
    public static ExternalFileAccess sameDirectory() {
        return new ExternalFileAccess(true, false, List.of(), null);
    }

    /** Any file the HDF5 file names, as libhdf5 allows. Use only for trusted files. */
    public static ExternalFileAccess unrestricted() {
        return new ExternalFileAccess(true, true, List.of(), null);
    }

    /** No other file is ever opened. */
    public static ExternalFileAccess none() {
        return new ExternalFileAccess(false, false, List.of(), null);
    }

    /**
     * Every name is passed to {@code resolver}, which opens it, wherever it is, or refuses it: the policy
     * for files read through a {@link RangeReader}, whose other files are not local paths, and for any
     * application that maps names itself. The resolver alone decides; Falcon looks nowhere else.
     */
    public static ExternalFileAccess resolvedBy(Resolver resolver) {
        return new ExternalFileAccess(true, false, List.of(), Objects.requireNonNull(resolver, "resolver"));
    }

    /**
     * A policy that also allows files under {@code directory}, where relative names are looked up after
     * the HDF5 file's own directory.
     *
     * @throws IllegalStateException for a {@link #resolvedBy(Resolver) resolver}'s policy, which looks in
     *         no directory
     */
    public ExternalFileAccess allowDirectory(Path directory) {
        if (resolver != null) {
            throw new IllegalStateException("a resolver decides alone where files are; it has no directories");
        }
        List<Path> more = new ArrayList<>(directories);
        more.add(directory.toAbsolutePath().normalize());
        return new ExternalFileAccess(true, unrestricted, more, null);
    }

    /** The resolver that opens every name, or null if names are resolved to local paths. */
    Resolver resolver() {
        return resolver;
    }

    /**
     * Reads up to {@code length} bytes at {@code position} of the external raw data file {@code name}, as
     * written in an HDF5 file in the absolute directory {@code baseDirectory} ({@code null} for none), into
     * {@code out[at...]}; bytes past the end of the file stay zero.
     *
     * @throws HdfUnsupportedException if the policy refuses the name
     * @throws IOException if the file cannot be read
     */
    void readRawData(String name, Path baseDirectory, long position, byte[] out, int at, int length) throws IOException {
        if (resolver != null) {
            RangeReader reader = resolver.open(name, Purpose.RAW_DATA);
            if (reader == null) {
                throw new NoSuchFileException(name, null, "the resolver found no such external raw data file");
            }
            try {
                long available = Math.max(0, reader.size() - position);
                int n = (int) Math.min(length, available);
                if (n > 0) {
                    reader.read(position, ByteBuffer.wrap(out, at, n));
                }
            } finally {
                close(reader);
            }
            return;
        }
        Path file = resolve(name, baseDirectory, "external raw data file");
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(position);
            ByteBuffer buffer = ByteBuffer.wrap(out, at, length);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // keep reading until the region is filled or the file ends (a short file leaves zeros)
            }
        }
    }

    /** Closes {@code reader} if it is closeable; a failure to close is ignored, the read being done. */
    static void close(RangeReader reader) {
        if (reader instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // nothing more is read through it
            }
        }
    }

    /**
     * Resolves {@code name}, as written in an HDF5 file in the absolute directory {@code baseDirectory}
     * ({@code null} for a file with no directory of its own), to the file to open: the first allowed
     * candidate that exists, else the first allowed one. An absolute name is its own only candidate; a
     * relative one is tried in {@code baseDirectory} and then each allowed directory.
     *
     * @param what what the name is for, for error messages (e.g. "external raw data file")
     * @throws HdfUnsupportedException if the policy refuses the name
     * @throws HdfFormatException if the name is not a valid path
     */
    Path resolve(String name, Path baseDirectory, String what) {
        Path base = base(baseDirectory, name, what);
        Path named = parse(fileSystem(base), name, what);
        List<Path> candidates = isRooted(named) ? List.of(named.toAbsolutePath().normalize())
                : relativeCandidates(base, name, false, what);
        List<Path> allowed = allowed(candidates, base);
        if (allowed.isEmpty()) {
            throw refused(what, name, base);
        }
        Path found = firstExisting(allowed);
        return found != null ? found : allowed.getFirst();
    }

    /**
     * Resolves a virtual dataset's source file {@code name}, as written in an HDF5 file in the absolute
     * directory {@code baseDirectory} ({@code null} for none), in libhdf5's order (see the class
     * description). Returns the first allowed candidate that exists, or null if the source is missing.
     *
     * @throws HdfUnsupportedException if the policy refuses every candidate, or refuses the name's own
     *         location and no allowed candidate exists
     * @throws HdfFormatException if the name is not a valid path
     */
    Path resolveVirtualSource(String name, Path baseDirectory) {
        String what = "virtual dataset source file";
        Path base = base(baseDirectory, name, what);
        Path named = parse(fileSystem(base), name, what);
        List<Path> candidates = new ArrayList<>();
        if (isRooted(named)) {
            candidates.add(named.toAbsolutePath().normalize());
            if (named.getFileName() != null) {
                candidates.addAll(relativeCandidates(base, named.getFileName().toString(), true, what));
            }
        } else {
            candidates.addAll(relativeCandidates(base, name, true, what));
        }
        List<Path> allowed = allowed(candidates, base);
        Path found = firstExisting(allowed);
        if (allowed.isEmpty() || (found == null && !allowed.contains(candidates.getFirst()))) {
            throw refused(what, name, base);
        }
        return found;
    }

    /**
     * The directory relative names start from: the HDF5 file's own, or, for a file without one, the
     * working directory under {@link #unrestricted()} and none (null) otherwise.
     */
    private Path base(Path baseDirectory, String name, String what) {
        if (!enabled) {
            throw new HdfUnsupportedException(what + " '" + name + "' is not opened: external file access is disabled");
        }
        if (baseDirectory == null) {
            return unrestricted ? Path.of("").toAbsolutePath() : null;
        }
        return baseDirectory.toAbsolutePath().normalize();
    }

    /** The file system a name is read in: the base directory's, or the default one. */
    private static FileSystem fileSystem(Path base) {
        return base != null ? base.getFileSystem() : FileSystems.getDefault();
    }

    private static Path parse(FileSystem fileSystem, String name, String what) {
        try {
            if (name.isEmpty() || name.indexOf('\0') >= 0) {
                throw new InvalidPathException(name, "empty or contains NUL");
            }
            return fileSystem.getPath(name);
        } catch (InvalidPathException e) {
            throw new HdfFormatException(what + " name is not a valid path: '" + name + "'", e);
        }
    }

    /** True for an absolute name, or one with a root (on Windows, {@code \\dir} or {@code C:dir}). */
    private static boolean isRooted(Path named) {
        return named.isAbsolute() || named.getRoot() != null;
    }

    /**
     * {@code name} in the base directory (if any), then each allowed directory, then perhaps the working
     * directory: each read as a path of that directory's own file system.
     */
    private List<Path> relativeCandidates(Path base, String name, boolean workingDirectory, String what) {
        List<Path> candidates = new ArrayList<>();
        if (base != null) {
            candidates.add(base.resolve(parse(base.getFileSystem(), name, what)).normalize());
        }
        for (Path directory : directories) {
            candidates.add(directory.resolve(parse(directory.getFileSystem(), name, what)).normalize());
        }
        if (workingDirectory && unrestricted) {
            candidates.add(Path.of("").toAbsolutePath().resolve(parse(FileSystems.getDefault(), name, what)).normalize());
        }
        return candidates;
    }

    private List<Path> allowed(List<Path> candidates, Path base) {
        List<Path> allowed = new ArrayList<>();
        for (Path candidate : candidates) {
            if (unrestricted || (base != null && isInside(candidate, base))
                    || directories.stream().anyMatch(d -> isInside(candidate, d))) {
                allowed.add(candidate);
            }
        }
        return allowed;
    }

    private static Path firstExisting(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private HdfUnsupportedException refused(String what, String name, Path base) {
        String where = base != null ? "lies outside " + base + (directories.isEmpty() ? "" : " and " + directories)
                : directories.isEmpty() ? "is not opened: the HDF5 file was not opened from a path, so no directory is allowed"
                : "lies outside " + directories;
        return new HdfUnsupportedException(what + " '" + name + "' " + where
                + "; open the file with ExternalFileAccess.unrestricted(), allowDirectory(...) or resolvedBy(...) to read it");
    }

    /** True if {@code candidate} lies in {@code directory}: never across file systems. */
    private static boolean isInside(Path candidate, Path directory) {
        return candidate.getFileSystem().equals(directory.getFileSystem()) && candidate.startsWith(directory);
    }
}
