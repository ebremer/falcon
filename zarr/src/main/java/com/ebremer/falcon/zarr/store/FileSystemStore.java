package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link Store} over a directory tree: a key maps to a file at the same relative path under a root,
 * with {@code '/'} as the path separator. This is the canonical on-disk Zarr layout &mdash; the layout
 * zarr-python and other implementations read and write.
 *
 * <p>Keys are validated (see {@link StoreKeys#validate}) and the resolved path is confirmed to stay
 * within the root, so a crafted key cannot escape the store.
 */
public final class FileSystemStore implements Store {

    private final Path root;
    private final boolean writable;

    private FileSystemStore(Path root, boolean writable) {
        this.root = root.toAbsolutePath().normalize();
        this.writable = writable;
    }

    /** Opens a writable store rooted at {@code root}, creating the directory if necessary. */
    public static FileSystemStore open(Path root) {
        FileSystemStore store = new FileSystemStore(root, true);
        try {
            Files.createDirectories(store.root);
        } catch (IOException e) {
            throw new ZarrException("failed to create store directory: " + store.root, e);
        }
        return store;
    }

    /** Opens a read-only store rooted at {@code root}. {@link #set}/{@link #delete} will be rejected. */
    public static FileSystemStore openReadOnly(Path root) {
        return new FileSystemStore(root, false);
    }

    /** The store's root directory. */
    public Path root() {
        return root;
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new ZarrException("failed to read key '" + key + "'", e);
        }
    }

    @Override
    public Optional<byte[]> getRange(String key, long offset, long length) {
        int len = MemoryStore.checkedLength(offset, length);
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (offset >= size) {
                return Optional.of(new byte[0]);
            }
            int toRead = (int) Math.min((long) len, size - offset);
            ByteBuffer buffer = ByteBuffer.allocate(toRead);
            long position = offset;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, position);
                if (n < 0) {
                    break;
                }
                position += n;
            }
            byte[] result = buffer.array();
            if (buffer.position() == toRead) {
                return Optional.of(result);
            }
            byte[] trimmed = new byte[buffer.position()];
            System.arraycopy(result, 0, trimmed, 0, trimmed.length);
            return Optional.of(trimmed);
        } catch (IOException e) {
            throw new ZarrException("failed to read range of key '" + key + "'", e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public List<String> list() {
        return listPrefix("");
    }

    @Override
    public List<String> listPrefix(String prefix) {
        return StoreKeys.listPrefix(allKeys(), prefix);
    }

    @Override
    public List<String> listDir(String prefix) {
        return StoreKeys.listDir(allKeys(), prefix);
    }

    @Override
    public boolean isWritable() {
        return writable;
    }

    @Override
    public void set(String key, byte[] value) {
        requireWritable();
        Path path = resolve(key);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, value);
        } catch (IOException e) {
            throw new ZarrException("failed to write key '" + key + "'", e);
        }
    }

    @Override
    public void delete(String key) {
        requireWritable();
        Path path = resolve(key);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new ZarrException("failed to delete key '" + key + "'", e);
        }
    }

    private void requireWritable() {
        if (!writable) {
            throw new UnsupportedOperationException("store is read-only: " + root);
        }
    }

    /** Resolves a validated key to an absolute path, confirming it stays under the root. */
    private Path resolve(String key) {
        StoreKeys.validate(key);
        Path path = root;
        for (String segment : key.split("/", -1)) {
            path = path.resolve(segment);
        }
        path = path.normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("key escapes the store root: " + key);
        }
        return path;
    }

    /** Every key in the store: the relative path of every regular file under the root, using '/'. */
    private List<String> allKeys() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace(java.io.File.separatorChar, '/'))
                    .toList();
        } catch (IOException e) {
            throw new ZarrException("failed to list store: " + root, e);
        }
    }
}
