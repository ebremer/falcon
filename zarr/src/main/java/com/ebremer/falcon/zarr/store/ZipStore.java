package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A read-only {@link Store} over a ZIP archive, the single-file form a Zarr store is often distributed
 * in. Each store key is a ZIP entry name (ZIP already uses {@code '/'} separators), so no path
 * translation is needed.
 *
 * <p>ZIP entries are compressed independently and are not randomly addressable inside, so
 * {@link #getRange} reads the whole entry and slices it &mdash; fine for the small metadata reads and
 * whole-chunk reads that dominate, though a sharded array in a ZIP re-reads its shard per sub-chunk.
 *
 * <p>Writing into a ZIP in place is not supported (ZIP has no random update); build a store elsewhere
 * and {@link #pack} it into an archive instead. Remember to {@link #close()} the store.
 */
public final class ZipStore implements Store, AutoCloseable {

    private final ZipFile zip;

    private ZipStore(ZipFile zip) {
        this.zip = zip;
    }

    /** Opens {@code archive} for reading. */
    public static ZipStore openReadOnly(Path archive) {
        try {
            return new ZipStore(new ZipFile(archive.toFile()));
        } catch (IOException e) {
            throw new ZarrException("failed to open ZIP store: " + archive, e);
        }
    }

    /** Packs every key of {@code source} into a new ZIP archive at {@code target}. */
    public static void pack(Store source, Path target) {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String key : source.list()) {
                byte[] value = source.get(key).orElse(null);
                if (value == null) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(key));
                zip.write(value);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new ZarrException("failed to write ZIP store: " + target, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        StoreKeys.validate(key);
        ZipEntry entry = zip.getEntry(key);
        if (entry == null || entry.isDirectory()) {
            return Optional.empty();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new ZarrException("failed to read ZIP entry '" + key + "'", e);
        }
    }

    @Override
    public Optional<byte[]> getRange(String key, long offset, long length) {
        int len = MemoryStore.checkedLength(offset, length);
        return get(key).map(value -> {
            if (offset >= value.length) {
                return new byte[0];
            }
            int from = (int) offset;
            int to = (int) Math.min((long) from + len, value.length);
            byte[] slice = new byte[to - from];
            System.arraycopy(value, from, slice, 0, slice.length);
            return slice;
        });
    }

    @Override
    public boolean exists(String key) {
        StoreKeys.validate(key);
        ZipEntry entry = zip.getEntry(key);
        return entry != null && !entry.isDirectory();
    }

    @Override
    public OptionalLong size(String key) {
        StoreKeys.validate(key);
        ZipEntry entry = zip.getEntry(key);
        if (entry == null || entry.isDirectory()) {
            return OptionalLong.empty();
        }
        long size = entry.getSize();
        return size >= 0 ? OptionalLong.of(size) : OptionalLong.of(get(key).orElseThrow().length);
    }

    @Override
    public List<String> list() {
        return StoreKeys.listPrefix(entryNames(), "");
    }

    @Override
    public List<String> listPrefix(String prefix) {
        return StoreKeys.listPrefix(entryNames(), prefix);
    }

    @Override
    public List<String> listDir(String prefix) {
        return StoreKeys.listDir(entryNames(), prefix);
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Override
    public void set(String key, byte[] value) {
        throw new UnsupportedOperationException("ZIP store is read-only; use ZipStore.pack to build one");
    }

    @Override
    public void delete(String key) {
        throw new UnsupportedOperationException("ZIP store is read-only");
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException e) {
            throw new ZarrException("failed to close ZIP store", e);
        }
    }

    private List<String> entryNames() {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
