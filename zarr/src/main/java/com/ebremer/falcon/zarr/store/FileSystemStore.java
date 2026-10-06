package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A {@link Store} over a directory tree: a key maps to a file at the same relative path under a root,
 * with {@code '/'} as the path separator. This is the canonical on-disk Zarr layout &mdash; the layout
 * zarr-python and other implementations read and write.
 *
 * <p>Keys are validated (see {@link StoreKeys#validate}) and the resolved path is confirmed to stay
 * within the root, so a crafted key cannot escape the store. A key segment must also not end in
 * {@code '.'} or a space, nor contain a {@code '\'}: Windows drops a trailing dot or space and splits at
 * a backslash, so {@code "data./zarr.json"} or {@code "a\b"} would reach the file of another key there.
 * They are refused on every platform, so a store reads the same everywhere, and a listing leaves out a
 * file or directory so named (one made outside Falcon), since no key can reach it.
 *
 * <p>A listing reads only the directory its prefix names, and below it, not the whole tree.
 *
 * <p>{@link #set} writes a temporary file beside the target and renames it into place, so a reader sees
 * either the old value or the new one, never part of a write, and a process that dies mid-write leaves
 * the old value. It does not force the bytes to the disk: after a power loss, a value written just before
 * may be lost. A temporary file left by a process that died mid-write is named
 * {@code .<name>.<random>.tmp}.
 */
public final class FileSystemStore implements Store {

    private static final int REPLACE_ATTEMPTS = 20;

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
            return Optional.of(read(channel, offset, len));
        } catch (IOException e) {
            throw new ZarrException("failed to read range of key '" + key + "'", e);
        }
    }

    @Override
    public Optional<byte[]> getSuffix(String key, long length) {
        int len = MemoryStore.checkedLength(0, length);
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return Optional.of(read(channel, Math.max(0, channel.size() - len), len));
        } catch (IOException e) {
            throw new ZarrException("failed to read the end of key '" + key + "'", e);
        }
    }

    /** Up to {@code len} bytes from {@code offset}, clamped to the file's size. */
    private static byte[] read(FileChannel channel, long offset, int len) throws IOException {
        long size = channel.size();
        if (offset >= size) {
            return new byte[0];
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
            return result;
        }
        byte[] trimmed = new byte[buffer.position()];
        System.arraycopy(result, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public OptionalLong size(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Files.size(path));
        } catch (IOException e) {
            throw new ZarrException("failed to size key '" + key + "'", e);
        }
    }

    @Override
    public List<String> list() {
        return listPrefix("");
    }

    @Override
    public List<String> listPrefix(String prefix) {
        // Walk only the deepest directory the prefix names: "a/b/c" walks a/b and keeps what starts with it.
        String dirKey = prefix.substring(0, prefix.lastIndexOf('/') + 1);
        Path dir = directory(dirKey);
        if (dir == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String key : keysUnder(dir)) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        keys.sort(null);
        return keys;
    }

    @Override
    public List<String> listDir(String prefix) {
        String dirKey = StoreKeys.asDirPrefix(prefix);
        Path dir = directory(dirKey);
        if (dir == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!portableSegment(name)) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException gone) {
                    continue; // deleted while listing
                }
                if (isKey(entry, attributes)) {
                    out.add(dirKey + name);
                } else if (attributes.isDirectory() && holdsAKey(entry)) {
                    out.add(dirKey + name + "/"); // a child prefix: it has keys deeper down
                }
            }
        } catch (NoSuchFileException gone) {
            return List.of(); // the directory was deleted while listing
        } catch (IOException e) {
            throw new ZarrException("failed to list store directory: " + dir, e);
        }
        out.sort(null);
        return out;
    }

    @Override
    public boolean isWritable() {
        return writable;
    }

    @Override
    public void set(String key, byte[] value) {
        requireWritable();
        Path path = resolve(key);
        Path temp = null;
        try {
            Path parent = path.getParent(); // never null: the path is under the root
            Files.createDirectories(parent);
            // Written beside the target, so the rename stays within one file system and is atomic.
            temp = Files.createTempFile(parent, "." + path.getFileName() + ".", ".tmp");
            Files.write(temp, value);
            replace(temp, path);
            temp = null;
        } catch (IOException e) {
            throw new ZarrException("failed to write key '" + key + "'", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // the write already failed; that error is the one to report
                }
            }
        }
    }

    /** Renames {@code temp} over {@code path} in one step. */
    private static void replace(Path temp, Path path) throws IOException {
        // Windows refuses to replace a file while any handle has it open, even a reader's, which holds it
        // for moments: so retry for a while (about 0.75 s in all) before giving up.
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (attempt == REPLACE_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(Math.min(1L << attempt, 50));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
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
            if (!portableSegment(segment)) {
                throw new IllegalArgumentException("key segment '" + segment + "' of '" + key
                        + "' ends in '.' or a space, or holds a '\\', which Windows would read as another key's file");
            }
            path = path.resolve(segment);
        }
        path = path.normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("key escapes the store root: " + key);
        }
        return path;
    }

    /**
     * Whether a key may use {@code segment} on any platform: it must not end in {@code '.'} or a space
     * (Windows drops them, so {@code "data."} is {@code "data"} there), nor hold a {@code '\'} (a Windows
     * separator). {@code "."} and {@code ".."} end in a dot too.
     */
    private static boolean portableSegment(String segment) {
        return !segment.isEmpty() && !segment.endsWith(".") && !segment.endsWith(" ") && segment.indexOf('\\') < 0;
    }

    /**
     * The directory a listing prefix names ({@code ""} for the root, else a prefix ending in {@code '/'}),
     * or {@code null} when no key can lie under it: a segment no key may use, or a path that is missing,
     * not a directory, or a symbolic link (a listing does not follow links into directories).
     */
    private Path directory(String dirKey) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        if (dirKey.isEmpty()) {
            return root;
        }
        String[] segments = dirKey.substring(0, dirKey.length() - 1).split("/", -1);
        Path dir = root;
        try {
            for (String segment : segments) {
                if (!portableSegment(segment)) {
                    return null;
                }
                dir = dir.resolve(segment);
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
            }
            // On a case-insensitive file system a prefix in another case reaches the same directory, but
            // the keys under it are spelled as stored, so none starts with that prefix.
            Path real = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
            int first = real.getNameCount() - segments.length;
            for (int i = 0; i < segments.length; i++) {
                if (first + i < 0 || !real.getName(first + i).toString().equals(segments[i])) {
                    return null;
                }
            }
        } catch (InvalidPathException | IOException e) {
            return null; // a name the file system cannot hold, or a directory deleted while looking
        }
        return dir;
    }

    /** Every key under {@code dir}: the root-relative path of each regular file, with {@code '/'}. */
    private List<String> keysUnder(Path dir) {
        List<String> keys = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attributes) {
                    return d.equals(dir) || portableSegment(d.getFileName().toString())
                            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (portableSegment(file.getFileName().toString()) && isKey(file, attributes)) {
                        keys.add(root.relativize(file).toString().replace(java.io.File.separatorChar, '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                    if (e instanceof NoSuchFileException) {
                        return FileVisitResult.CONTINUE; // deleted while listing
                    }
                    throw e;
                }
            });
        } catch (IOException e) {
            throw new ZarrException("failed to list store: " + dir, e);
        }
        return keys;
    }

    /** Whether some key lies under {@code dir}; stops at the first. */
    private boolean holdsAKey(Path dir) {
        boolean[] found = {false};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attributes) {
                    return d.equals(dir) || portableSegment(d.getFileName().toString())
                            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    found[0] = portableSegment(file.getFileName().toString()) && isKey(file, attributes);
                    return found[0] ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                    if (e instanceof NoSuchFileException) {
                        return FileVisitResult.CONTINUE;
                    }
                    throw e;
                }
            });
        } catch (IOException e) {
            throw new ZarrException("failed to list store: " + dir, e);
        }
        return found[0];
    }

    /** Whether a file is a key: a regular file, or a symbolic link to one (a link to a directory is not entered). */
    private static boolean isKey(Path file, BasicFileAttributes attributes) {
        return attributes.isRegularFile() || (attributes.isSymbolicLink() && Files.isRegularFile(file));
    }
}
