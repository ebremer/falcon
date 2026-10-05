package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The other files an open file's virtual datasets read, each opened once and kept open until that file
 * closes, as libhdf5 keeps a virtual dataset's sources open: so reading one selection after another does
 * not map the sources again each time. A file that cannot be opened is not remembered, and is tried again
 * on the next read. Sources come from local paths, or from an {@link ExternalFileAccess.Resolver}'s
 * readers, which are closed with them when they are closeable.
 *
 * <p>Thread-safe; a file is opened under the lock, so two reads never open it twice.
 */
final class SourceFiles implements AutoCloseable {

    private final Map<Path, Hdf5File> files = new HashMap<>();
    private final Map<String, Hdf5File> resolved = new HashMap<>(); // by name, through a resolver
    private final List<RangeReader> readers = new ArrayList<>();      // the resolver's, to close
    private boolean closed;

    /** The file at {@code path}, opened with {@code options} if it is not open yet; null if it cannot be. */
    synchronized Hdf5File open(Path path, OpenOptions options) {
        checkOpen();
        Hdf5File file = files.get(path);
        if (file == null) {
            try {
                file = Hdf5File.open(path, options);
            } catch (IOException e) {
                return null; // an unavailable source contributes only the fill value
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
    synchronized Hdf5File open(String name, ExternalFileAccess.Resolver resolver, OpenOptions options) {
        checkOpen();
        Hdf5File file = resolved.get(name);
        if (file == null) {
            RangeReader reader;
            try {
                reader = resolver.open(name, ExternalFileAccess.Purpose.VIRTUAL_SOURCE);
            } catch (IOException e) {
                return null; // an unavailable source contributes only the fill value
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
            resolved.put(name, file);
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
