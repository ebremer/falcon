package com.ebremer.falcon.hdf5;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * </ul>
 *
 * A refused name fails the read with {@link HdfUnsupportedException} (a virtual dataset does not
 * silently substitute its fill value). Paths are compared after normalization; symbolic links inside an
 * allowed directory are not resolved.
 *
 * <p>A file opened from bytes or a {@link RangeReader} has no directory of its own. Then only
 * {@link #allowDirectory(Path) allowed directories} are searched and allowed, and
 * {@link #unrestricted()} resolves relative names against the working directory, as libhdf5 does for a
 * file in memory.
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

    private final boolean enabled;
    private final boolean unrestricted;
    private final List<Path> directories;

    private ExternalFileAccess(boolean enabled, boolean unrestricted, List<Path> directories) {
        this.enabled = enabled;
        this.unrestricted = unrestricted;
        this.directories = List.copyOf(directories);
    }

    /** The default: files inside the HDF5 file's own directory (or its subdirectories) only. */
    public static ExternalFileAccess sameDirectory() {
        return new ExternalFileAccess(true, false, List.of());
    }

    /** Any file the HDF5 file names, as libhdf5 allows. Use only for trusted files. */
    public static ExternalFileAccess unrestricted() {
        return new ExternalFileAccess(true, true, List.of());
    }

    /** No other file is ever opened. */
    public static ExternalFileAccess none() {
        return new ExternalFileAccess(false, false, List.of());
    }

    /**
     * A policy that also allows files under {@code directory}, where relative names are looked up after
     * the HDF5 file's own directory.
     */
    public ExternalFileAccess allowDirectory(Path directory) {
        List<Path> more = new ArrayList<>(directories);
        more.add(directory.toAbsolutePath().normalize());
        return new ExternalFileAccess(true, unrestricted, more);
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
        Path named = parse(name, what);
        List<Path> candidates = isRooted(named) ? List.of(named.toAbsolutePath().normalize())
                : relativeCandidates(base, named, false);
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
        Path named = parse(name, what);
        List<Path> candidates = new ArrayList<>();
        if (isRooted(named)) {
            candidates.add(named.toAbsolutePath().normalize());
            if (named.getFileName() != null) {
                candidates.addAll(relativeCandidates(base, named.getFileName(), true));
            }
        } else {
            candidates.addAll(relativeCandidates(base, named, true));
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

    private static Path parse(String name, String what) {
        try {
            if (name.isEmpty() || name.indexOf('\0') >= 0) {
                throw new InvalidPathException(name, "empty or contains NUL");
            }
            return Path.of(name);
        } catch (InvalidPathException e) {
            throw new HdfFormatException(what + " name is not a valid path: '" + name + "'", e);
        }
    }

    /** True for an absolute name, or one with a root (on Windows, {@code \\dir} or {@code C:dir}). */
    private static boolean isRooted(Path named) {
        return named.isAbsolute() || named.getRoot() != null;
    }

    /** {@code named} in the base directory (if any), then each allowed directory, then perhaps the working directory. */
    private List<Path> relativeCandidates(Path base, Path named, boolean workingDirectory) {
        List<Path> candidates = new ArrayList<>();
        if (base != null) {
            candidates.add(base.resolve(named).normalize());
        }
        for (Path directory : directories) {
            candidates.add(directory.resolve(named).normalize());
        }
        if (workingDirectory && unrestricted) {
            candidates.add(Path.of("").toAbsolutePath().resolve(named).normalize());
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
                + "; open the file with ExternalFileAccess.unrestricted() or allowDirectory(...) to read it");
    }

    private static boolean isInside(Path candidate, Path directory) {
        return candidate.startsWith(directory);
    }
}
