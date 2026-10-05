package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A read-only view of the default file system whose files cannot be memory-mapped: its provider has no
 * {@code newFileChannel}, as a zip or in-memory file system's channels cannot map. A test stand-in, built
 * on {@code java.base} alone.
 */
final class UnmappableFileSystem extends FileSystem {

    static final UnmappableFileSystem INSTANCE = new UnmappableFileSystem();

    private final Provider provider = new Provider();

    private UnmappableFileSystem() {
    }

    /** {@code path} (of the default file system) seen through this one. */
    static Path wrap(Path path) {
        return path == null ? null : new UPath(INSTANCE, path);
    }

    static Path unwrap(Path path) {
        if (path instanceof UPath u) {
            return u.delegate;
        }
        throw new ProviderMismatchException();
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return Path.of("").getFileSystem().getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return () -> java.util.stream.StreamSupport.stream(Path.of("").getFileSystem().getRootDirectories().spliterator(), false)
                .map(UnmappableFileSystem::wrap).iterator();
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return List.of();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        return wrap(Path.of(first, more));
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }

    /** Reads through to the default provider; {@code newFileChannel} keeps the default, unsupported. */
    private static final class Provider extends FileSystemProvider {
        @Override
        public String getScheme() {
            return "unmappable";
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            return INSTANCE;
        }

        @Override
        public Path getPath(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
                throws IOException {
            return Files.newByteChannel(unwrap(path), options, attrs);
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSameFile(Path path, Path path2) throws IOException {
            return Files.isSameFile(unwrap(path), unwrap(path2));
        }

        @Override
        public boolean isHidden(Path path) throws IOException {
            return Files.isHidden(unwrap(path));
        }

        @Override
        public FileStore getFileStore(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) throws IOException {
            Path inner = unwrap(path);
            inner.getFileSystem().provider().checkAccess(inner, modes);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            return null;
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
                throws IOException {
            return Files.readAttributes(unwrap(path), type, options);
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
            return Files.readAttributes(unwrap(path), attributes, options);
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
    }

    /** A default-file-system path seen through the unmappable file system. */
    private record UPath(UnmappableFileSystem fs, Path delegate) implements Path {

        @Override
        public FileSystem getFileSystem() {
            return fs;
        }

        @Override
        public boolean isAbsolute() {
            return delegate.isAbsolute();
        }

        @Override
        public Path getRoot() {
            return wrap(delegate.getRoot());
        }

        @Override
        public Path getFileName() {
            return wrap(delegate.getFileName());
        }

        @Override
        public Path getParent() {
            return wrap(delegate.getParent());
        }

        @Override
        public int getNameCount() {
            return delegate.getNameCount();
        }

        @Override
        public Path getName(int index) {
            return wrap(delegate.getName(index));
        }

        @Override
        public Path subpath(int beginIndex, int endIndex) {
            return wrap(delegate.subpath(beginIndex, endIndex));
        }

        @Override
        public boolean startsWith(Path other) {
            return other instanceof UPath u && delegate.startsWith(u.delegate);
        }

        @Override
        public boolean endsWith(Path other) {
            return other instanceof UPath u && delegate.endsWith(u.delegate);
        }

        @Override
        public Path normalize() {
            return wrap(delegate.normalize());
        }

        @Override
        public Path resolve(Path other) {
            return wrap(delegate.resolve(unwrap(other)));
        }

        @Override
        public Path relativize(Path other) {
            return wrap(delegate.relativize(unwrap(other)));
        }

        @Override
        public URI toUri() {
            return URI.create("unmappable:" + delegate.toUri().getPath());
        }

        @Override
        public Path toAbsolutePath() {
            return wrap(delegate.toAbsolutePath());
        }

        @Override
        public Path toRealPath(LinkOption... options) throws IOException {
            return wrap(delegate.toRealPath(options));
        }

        @Override
        public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int compareTo(Path other) {
            return delegate.compareTo(unwrap(other));
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
