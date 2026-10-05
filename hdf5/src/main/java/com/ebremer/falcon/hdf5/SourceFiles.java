package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.io.FileContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The other HDF5 files an open file reads (its virtual datasets' sources, the files its external links
 * lead to, the files its references point into), each opened once and kept open until that file closes,
 * as libhdf5 keeps a virtual dataset's sources open: so reading one selection after another, or following
 * a link again, does not map the file again each time. A file that cannot be opened is not remembered,
 * and is tried again next time. Files come from local paths, or from an
 * {@link ExternalFileAccess.Resolver}'s readers, which are closed with them when they are closeable.
 *
 * <p>Thread-safe; a file is opened under the lock, so two reads never open it twice.
 */
final class SourceFiles implements AutoCloseable {

    private final Map<Path, Hdf5File> files = new HashMap<>();
    private final Map<String, Hdf5File> resolved = new HashMap<>(); // by purpose and name, through a resolver
    private final List<RangeReader> readers = new ArrayList<>();      // the resolver's, to close
    private boolean closed;

    /**
     * The HDF5 file {@code name} that the file of {@code ctx} names, for {@code purpose}: found as its
     * {@link ExternalFileAccess} policy says and kept open until that file closes, or {@code ctx} itself
     * if the name leads back to the same file. Null if there is no such file, or it cannot be opened.
     *
     * @throws HdfUnsupportedException if the policy refuses the name
     * @throws HdfFormatException if the name is not a valid path
     */
    static FileContext find(FileContext ctx, String name, ExternalFileAccess.Purpose purpose) {
        ExternalFileAccess access = ctx.externalFileAccess();
        SourceFiles files = ctx.resource(SourceFiles.class, SourceFiles::new);
        Hdf5File file;
        if (access.resolver() != null) {
            file = files.open(name, purpose, access.resolver(), ctx.options());
        } else {
            Path path = access.resolveHdf5File(name, ctx.directory(), describe(purpose));
            if (path == null) {
                return null;
            }
            if (isSameFile(path, ctx.path())) {
                return ctx; // libhdf5 too uses the file already open
            }
            file = files.open(path, ctx.options());
        }
        return file == null ? null : file.context();
    }

    /** What a file opened for {@code purpose} is, for messages. */
    static String describe(ExternalFileAccess.Purpose purpose) {
        return switch (purpose) {
            case RAW_DATA -> "external raw data file";
            case VIRTUAL_SOURCE -> "virtual dataset source file";
            case EXTERNAL_LINK -> "external link's file";
            case REFERENCE -> "file a reference points into";
        };
    }

    private static boolean isSameFile(Path path, Path own) {
        if (own == null) {
            return false;
        }
        try {
            return Files.isSameFile(path, own);
        } catch (IOException | RuntimeException e) {
            return false; // not comparable: open it as another file
        }
    }

    /** The file at {@code path}, opened with {@code options} if it is not open yet; null if it cannot be. */
    synchronized Hdf5File open(Path path, OpenOptions options) {
        checkOpen();
        Hdf5File file = files.get(path);
        if (file == null) {
            try {
                file = Hdf5File.open(path, options);
            } catch (IOException e) {
                return null; // an unavailable file is missing: a virtual source then reads as the fill value
            }
            files.put(path, file);
        }
        return file;
    }

    /**
     * The file {@code resolver} opens for {@code name}, opened with {@code options} if it is not open yet;
     * null if the resolver finds no such file or it cannot be opened.
     *
     * @throws HdfUnsupportedException if the resolver refuses the name
     */
    synchronized Hdf5File open(String name, ExternalFileAccess.Purpose purpose, ExternalFileAccess.Resolver resolver,
                               OpenOptions options) {
        checkOpen();
        String key = purpose + "\0" + name;
        Hdf5File file = resolved.get(key);
        if (file == null) {
            RangeReader reader;
            try {
                reader = resolver.open(name, purpose);
            } catch (IOException e) {
                return null; // an unavailable file is missing
            }
            if (reader == null) {
                return null;
            }
            try {
                file = Hdf5File.open(reader, options);
            } catch (IOException e) {
                ExternalFileAccess.close(reader);
                return null;
            } catch (RuntimeException e) {
                ExternalFileAccess.close(reader);
                throw e;
            }
            resolved.put(key, file);
            readers.add(reader);
        }
        return file;
    }

    private void checkOpen() {
        if (closed) {
            throw new HdfClosedException("the HDF5 file is closed");
        }
    }

    /** Closes every file opened, and the resolver's readers. */
    @Override
    public synchronized void close() {
        closed = true;
        for (Hdf5File file : files.values()) {
            file.close();
        }
        for (Hdf5File file : resolved.values()) {
            file.close();
        }
        for (RangeReader reader : readers) {
            ExternalFileAccess.close(reader);
        }
        files.clear();
        resolved.clear();
        readers.clear();
    }
}
