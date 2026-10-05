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
     * Resolves {@code name}, as written in an HDF5 file in {@code baseDirectory} ({@code null} for the
     * working directory), to the file to open: the first allowed candidate that exists, else the first
     * allowed one.
     *
     * @param what what the name is for, for error messages (e.g. "external raw data file")
     * @throws HdfUnsupportedException if the policy refuses the name
     * @throws HdfFormatException if the name is not a valid path
     */
    Path resolve(String name, Path baseDirectory, String what) {
        if (!enabled) {
            throw new HdfUnsupportedException(what + " '" + name + "' is not opened: external file access is disabled");
        }
        Path base = (baseDirectory == null ? Path.of("") : baseDirectory).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        try {
            if (name.isEmpty() || name.indexOf('\0') >= 0) {
                throw new InvalidPathException(name, "empty or contains NUL");
            }
            Path named = Path.of(name);
            if (named.isAbsolute() || named.getRoot() != null) {
                candidates.add(named.toAbsolutePath().normalize());
            } else {
                candidates.add(base.resolve(named).normalize());
                for (Path directory : directories) {
                    candidates.add(directory.resolve(named).normalize());
                }
            }
        } catch (InvalidPathException e) {
            throw new HdfFormatException(what + " name is not a valid path: '" + name + "'", e);
        }
        List<Path> allowed = new ArrayList<>();
        for (Path candidate : candidates) {
            if (unrestricted || isInside(candidate, base) || directories.stream().anyMatch(d -> isInside(candidate, d))) {
                allowed.add(candidate);
            }
        }
        if (allowed.isEmpty()) {
            throw new HdfUnsupportedException(what + " '" + name + "' lies outside " + base
                    + (directories.isEmpty() ? "" : " and " + directories)
                    + "; open the file with ExternalFileAccess.unrestricted() or allowDirectory(...) to read it");
        }
        for (Path candidate : allowed) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return allowed.getFirst();
    }

    private static boolean isInside(Path candidate, Path directory) {
        return candidate.startsWith(directory);
    }
}
