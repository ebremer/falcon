package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The other files an open file's virtual datasets read, each opened once and kept open until that file
 * closes, as libhdf5 keeps a virtual dataset's sources open: so reading one selection after another does
 * not map the sources again each time. A file that cannot be opened is not remembered, and is tried again
 * on the next read.
 *
 * <p>Thread-safe; a file is opened under the lock, so two reads never open it twice.
 */
final class SourceFiles implements AutoCloseable {

    private final Map<Path, Hdf5File> files = new HashMap<>();
    private boolean closed;

    /** The file at {@code path}, opened with {@code options} if it is not open yet; null if it cannot be. */
    synchronized Hdf5File open(Path path, OpenOptions options) {
        if (closed) {
            throw new HdfClosedException("the HDF5 file is closed");
        }
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

    /** Closes every file opened. */
    @Override
    public synchronized void close() {
        closed = true;
        for (Hdf5File file : files.values()) {
            file.close();
        }
        files.clear();
    }
}
