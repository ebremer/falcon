package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.ExternalFileAccess;
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.hdf5.OpenOptions;
import com.ebremer.falcon.hdf5.RangeReader;
import com.ebremer.falcon.s3.S3RangeReader;
import com.ebremer.falcon.s3.S3Store;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.HttpStore;
import com.ebremer.falcon.zarr.store.Store;
import com.ebremer.falcon.zarr.store.ZipStore;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Opens what a command names: an HDF5 file or a Zarr store, local or remote, told apart by what it holds.
 * <ul>
 *   <li><b>Local:</b> a directory is a Zarr store; a file is HDF5 if it carries HDF5's signature (at its start,
 *       or after a user block), and a Zarr store in a ZIP archive if it is one. A Zarr metadata file
 *       ({@code zarr.json}, {@code .zarray}, ...) names the store of its directory.</li>
 *   <li><b>{@code s3://bucket/key}:</b> an object with HDF5's signature is read a range at a time; anything
 *       else (a prefix) is a Zarr store.</li>
 *   <li><b>{@code http(s)://}:</b> likewise: a file served in ranges with HDF5's signature is HDF5, and
 *       anything else a Zarr store read over HTTP.</li>
 * </ul>
 */
final class Sources {

    /** HDF5's format signature, at offset 0 or 512, 1024, 2048, ... after a user block. */
    private static final byte[] HDF5_SIGNATURE = {(byte) 0x89, 'H', 'D', 'F', '\r', '\n', 0x1a, '\n'};
    /** The offsets a remote file's signature is looked for at: the first request covers them all. */
    private static final long[] REMOTE_OFFSETS = {0, 512, 1024, 2048, 4096};
    private static final Set<String> METADATA_FILES = Set.of("zarr.json", ".zarray", ".zgroup", ".zattrs", ".zmetadata");

    private Sources() {
    }

    /** An HDF5 file or a Zarr store, opened to be read, and closed with what it was opened through. */
    sealed interface Source extends AutoCloseable permits Hdf5Source, ZarrSource {

        /** {@return where the source is, as the command named it} */
        String location();

        @Override
        void close() throws IOException;
    }

    /**
     * An HDF5 file.
     *
     * @param location where it is
     * @param file     the open file
     * @param reader   what it is read through, to close after it, or null for a local file
     */
    record Hdf5Source(String location, Hdf5File file, AutoCloseable reader) implements Source {
        @Override
        public void close() throws IOException {
            try {
                file.close();
            } finally {
                closeAll(reader);
            }
        }
    }

    /**
     * A Zarr store.
     *
     * @param location where it is
     * @param store    the store, read-only
     */
    record ZarrSource(String location, Store store) implements Source {
        @Override
        public void close() throws IOException {
            closeAll(store);
        }
    }

    /**
     * Opens the HDF5 file or Zarr store at {@code location}.
     *
     * @param context  the command's context, for the S3 client and HTTP headers
     * @param location a local path, or an {@code s3://} or {@code http(s)://} URL
     * @return the source, to close
     * @throws IOException if there is nothing there, or it is neither format
     */
    static Source open(Context context, String location) throws IOException {
        if (isS3(location)) {
            return openS3(context, location);
        }
        if (isHttp(location)) {
            return openHttp(context, location);
        }
        return openLocal(location);
    }

    /**
     * Opens the Zarr store at {@code location}, which must be one.
     *
     * @param context  the command's context
     * @param location where the store is
     * @return the store's source, to close
     * @throws IOException    if there is nothing there
     * @throws UsageException if it is an HDF5 file
     */
    static ZarrSource openZarr(Context context, String location) throws IOException {
        Source source = open(context, location);
        if (source instanceof ZarrSource zarr) {
            return zarr;
        }
        source.close();
        throw new UsageException(location + " is an HDF5 file, not a Zarr store: use 'falcon convert'");
    }

    static boolean isS3(String location) {
        return location.regionMatches(true, 0, "s3://", 0, 5);
    }

    static boolean isHttp(String location) {
        return location.regionMatches(true, 0, "http://", 0, 7) || location.regionMatches(true, 0, "https://", 0, 8);
    }

    private static Source openLocal(String location) throws IOException {
        Path path = Path.of(location);
        if (Files.isDirectory(path)) {
            return new ZarrSource(location, FileSystemStore.openReadOnly(path));
        }
        if (!Files.exists(path)) {
            throw new NoSuchFileException(location);
        }
        Path name = path.getFileName();
        if (name != null && METADATA_FILES.contains(name.toString())) {
            return new ZarrSource(location, FileSystemStore.openReadOnly(path.toAbsolutePath().getParent()));
        }
        boolean hdf5;
        boolean zip;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            hdf5 = hasHdf5Signature(channel);
            zip = isZip(channel);
        }
        if (hdf5) {
            return new Hdf5Source(location, Hdf5File.open(path), null);
        }
        if (zip) {
            return new ZarrSource(location, ZipStore.openReadOnly(path));
        }
        throw new IOException(location + " is neither an HDF5 file, a ZIP archive of a Zarr store, nor a directory");
    }

    private static boolean hasHdf5Signature(FileChannel channel) throws IOException {
        long size = channel.size();
        for (long offset = 0; offset <= size - HDF5_SIGNATURE.length; offset = offset == 0 ? 512 : offset * 2) {
            ByteBuffer buffer = ByteBuffer.allocate(HDF5_SIGNATURE.length);
            while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) >= 0) {
                // read until full or at the end
            }
            if (Arrays.equals(buffer.array(), HDF5_SIGNATURE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isZip(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        channel.read(buffer, 0);
        byte[] head = buffer.array();
        return buffer.position() == 4 && head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6));
    }

    private static Source openS3(Context context, String location) throws IOException {
        if (!location.endsWith("/") && location.indexOf('/', 5) > 0) {
            S3RangeReader reader;
            try {
                reader = S3RangeReader.open(context.s3(), location);
            } catch (FileNotFoundException e) {
                reader = null; // no such object: a prefix
            }
            if (reader != null) {
                if (hasHdf5Signature(reader)) {
                    OpenOptions options = remoteOptions()
                            .externalFileAccess(ExternalFileAccess.resolvedBy(reader.siblings()));
                    return new Hdf5Source(location, Hdf5File.open(reader, options), null);
                }
                throw new IOException(location + " is an object, but not an HDF5 file; a Zarr store in S3 is a "
                        + "prefix (a ZIP archive must be copied locally to be read)");
            }
        }
        return new ZarrSource(location, S3Store.fromUrl(context.s3(), location).readOnly().build());
    }

    private static Source openHttp(Context context, String location) throws IOException {
        Map<String, String> headers = context.options.httpHeaders();
        if (!location.endsWith("/")) {
            HttpRangeReader reader = null;
            boolean hdf5 = false;
            try {
                reader = HttpRangeReader.open(location, headers);
                hdf5 = hasHdf5Signature(reader);
            } catch (IOException e) {
                // not a file served in ranges: a Zarr store's base URL
            }
            if (hdf5) {
                try {
                    return new Hdf5Source(location, Hdf5File.open(reader, remoteOptions()), reader);
                } catch (IOException | RuntimeException e) {
                    closeAll(reader);
                    throw e;
                }
            }
            closeAll(reader);
        }
        HttpStore.Builder store = HttpStore.builder(location).directoryListing(context.options.httpListing);
        headers.forEach(store::header);
        return new ZarrSource(location, store.build());
    }

    private static boolean hasHdf5Signature(RangeReader reader) throws IOException {
        long size = reader.size();
        long last = REMOTE_OFFSETS[REMOTE_OFFSETS.length - 1] + HDF5_SIGNATURE.length;
        ByteBuffer head = ByteBuffer.allocate((int) Math.min(size, last));
        reader.read(0, head);
        byte[] bytes = head.array();
        for (long offset : REMOTE_OFFSETS) {
            if (offset + HDF5_SIGNATURE.length <= bytes.length && Arrays.equals(bytes, (int) offset,
                    (int) offset + HDF5_SIGNATURE.length, HDF5_SIGNATURE, 0, HDF5_SIGNATURE.length)) {
                return true;
            }
        }
        return false;
    }

    private static OpenOptions remoteOptions() {
        return OpenOptions.defaults().readerPageSize(256 << 10).readerCacheSize(64L << 20);
    }

    /**
     * A Zarr store to write, and how to finish it.
     *
     * @param location where it is
     * @param store    the store, writable
     */
    record ZarrTarget(String location, Store store) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            closeAll(store);
        }
    }

    /**
     * Opens the Zarr store to write at {@code location}: a directory, a {@code .zip} archive, or an
     * {@code s3://} prefix.
     *
     * @param context   the command's context
     * @param location  where to write
     * @param overwrite whether a ZIP archive there may be replaced (a store's own nodes are replaced only
     *                  with this too, when they are created)
     * @return the store, to close when written
     * @throws IOException    if the archive exists and may not be replaced, or a file is in the way
     * @throws UsageException if the location is an HTTP URL
     */
    static ZarrTarget zarrTarget(Context context, String location, boolean overwrite) throws IOException {
        if (isHttp(location)) {
            throw new UsageException("an http(s):// URL cannot be written: write to a local path or an s3:// URL");
        }
        if (isS3(location)) {
            return new ZarrTarget(location, S3Store.fromUrl(context.s3(), location).build());
        }
        Path path = Path.of(location);
        if (location.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            if (Files.exists(path) && !overwrite) {
                throw new FileAlreadyExistsException(location, null, "pass --overwrite to replace it");
            }
            createParent(path);
            return new ZarrTarget(location, ZipStore.create(path));
        }
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new FileAlreadyExistsException(location, null, "a file, where a Zarr store's directory would go");
        }
        return new ZarrTarget(location, FileSystemStore.open(path));
    }

    /**
     * The local path to write an HDF5 file to.
     *
     * @param location  where to write
     * @param overwrite whether a file there may be replaced
     * @return the path, its directory created
     * @throws IOException    if a file is there and may not be replaced
     * @throws UsageException if the location is a URL
     */
    static Path hdf5Target(String location, boolean overwrite) throws IOException {
        if (isHttp(location) || isS3(location)) {
            throw new UsageException("an HDF5 file is written to a local path; upload it afterwards");
        }
        Path path = Path.of(location);
        if (Files.isDirectory(path)) {
            throw new FileAlreadyExistsException(location, null, "a directory, where the HDF5 file would go");
        }
        if (Files.exists(path) && !overwrite) {
            throw new FileAlreadyExistsException(location, null, "pass --overwrite to replace it");
        }
        createParent(path);
        return path;
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    static void closeAll(Object resource) throws IOException {
        if (resource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (IOException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
        }
    }
}
