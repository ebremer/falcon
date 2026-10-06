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
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A read-only {@link Store} over a ZIP archive, the single-file form a Zarr store is often distributed
 * in. Each store key is a ZIP entry name (ZIP already uses {@code '/'} separators), so no path
 * translation is needed.
 *
 * <p>{@link #getRange} on a {@code STORED} (uncompressed) entry reads just the range from the archive,
 * so a sharded array in a ZIP reads only the sub-chunks it needs; that is why Zarr archives store their
 * entries uncompressed (the chunks are compressed already), and why {@link #pack} does too. A
 * {@code DEFLATED} entry is not randomly addressable: a range is inflated from the entry's start, and
 * inflating stops at the range's end.
 *
 * <p>Writing into a ZIP in place is not supported (ZIP has no random update); build a store elsewhere
 * and {@link #pack} it into an archive instead. Remember to {@link #close()} the store. It may be read
 * from several threads at once.
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

    /**
     * Packs every key of {@code source} into a new ZIP archive at {@code target}. Entries are
     * {@code STORED}, uncompressed, as zarr-python's {@code ZipStore} writes them: chunks are compressed by
     * their codecs already, and a stored entry can be read a range at a time.
     */
    public static void pack(Store source, Path target) {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String key : source.list()) {
                byte[] value = source.get(key).orElse(null);
                if (value == null) {
                    continue;
                }
                CRC32 crc = new CRC32();
                crc.update(value);
                ZipEntry entry = new ZipEntry(key);
                entry.setMethod(ZipEntry.STORED); // a stored entry's size and CRC go in its header, first
                entry.setSize(value.length);
                entry.setCompressedSize(value.length);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
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
        StoreKeys.validate(key);
        ZipEntry entry = zip.getEntry(key);
        if (entry == null || entry.isDirectory()) {
            return Optional.empty();
        }
        return Optional.of(read(entry, offset, len));
    }

    @Override
    public Optional<byte[]> getSuffix(String key, long length) {
        int len = MemoryStore.checkedLength(0, length);
        StoreKeys.validate(key);
        ZipEntry entry = zip.getEntry(key);
        if (entry == null || entry.isDirectory()) {
            return Optional.empty();
        }
        long size = entry.getSize(); // known: an archive's central directory records it
        if (size < 0) {
            byte[] all = read(entry, 0, Integer.MAX_VALUE);
            return Optional.of(java.util.Arrays.copyOfRange(all, Math.max(0, all.length - len), all.length));
        }
        return Optional.of(read(entry, Math.max(0, size - len), len));
    }

    /**
     * Up to {@code len} bytes of {@code entry} from {@code offset}, clamped to its size. The entry's stream
     * skips a stored entry's bytes without reading them, and inflates a deflated one only up to the end.
     */
    private byte[] read(ZipEntry entry, long offset, int len) {
        long size = entry.getSize();
        if (size >= 0 && offset >= size) {
            return new byte[0];
        }
        try (InputStream in = zip.getInputStream(entry)) {
            long skipped = 0;
            while (skipped < offset) {
                long n = in.skip(offset - skipped);
                if (n <= 0) {
                    if (in.read() < 0) {
                        return new byte[0]; // ended before the offset
                    }
                    n = 1;
                }
                skipped += n;
            }
            int toRead = size >= 0 ? (int) Math.min((long) len, size - offset) : len;
            return in.readNBytes(toRead);
        } catch (IOException e) {
            throw new ZarrException("failed to read ZIP entry '" + entry.getName() + "'", e);
        }
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
