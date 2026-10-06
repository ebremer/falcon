package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * A {@link Store} over a ZIP archive, the single-file form a Zarr store is often distributed in. Each store
 * key is a ZIP entry name (ZIP already uses {@code '/'} separators), so no path translation is needed.
 *
 * <p>Three ways to open one, as zarr-python's {@code ZipStore} has three modes:
 * <ul>
 *   <li>{@link #openReadOnly} reads an existing archive (mode {@code "r"});</li>
 *   <li>{@link #create} starts a new, empty archive, replacing any file at the path (mode {@code "w"});</li>
 *   <li>{@link #open} adds to an existing archive, or starts one if there is none (mode {@code "a"}).</li>
 * </ul>
 *
 * <p><b>Reading.</b> {@link #getRange} on a {@code STORED} (uncompressed) entry reads just the range from the
 * archive, so a sharded array in a ZIP reads only the sub-chunks it needs; that is why Zarr archives store
 * their entries uncompressed (the chunks are compressed already), and why this store writes them so. A
 * {@code DEFLATED} entry is not randomly addressable: a range is inflated from the entry's start, and
 * inflating stops at the range's end. A whole value read with {@link #get} is checked against its CRC-32.
 * When an archive holds two entries of the same name (zarr-python adds a second when a key is written
 * again), the later one is the key's value, as Python's {@code zipfile} reads it. Entries that are
 * directories, or whose names are not store keys, are not listed and cannot be read; they are kept when
 * the archive is rewritten.
 *
 * <p><b>Writing.</b> {@link #set} appends the value to the file at once, as a {@code STORED} entry; the
 * central directory, the archive's index, is written by {@link #close()}, with Zip64 records when the
 * archive needs them (65,535 entries or more, or more than 4 GiB). Everything written reads back before
 * then. Writing a key again appends a new entry, and the directory names only the newest; {@link #delete}
 * drops the key from the directory. Either way the old bytes stay in the file as dead space: to compact an
 * archive, {@link #pack} it into a new one.
 *
 * <p><b>Close it.</b> Until {@link #close()} runs, the file has no central directory, so a process that
 * dies before closing leaves an archive nothing can read; adding to an existing archive writes over its old
 * directory, so that archive is lost too. zarr-python's {@code ZipStore} behaves the same. A store opened to
 * add to an archive and closed without changes leaves the file as it was.
 *
 * <p>It may be used from several threads at once: writes run one at a time, reads alongside them. A thread
 * interrupted while it reads or writes fails with {@link ZarrException}; the others carry on (the JDK closes
 * a file's channel when a thread using it is interrupted, so the store opens the file again). Every
 * operation but {@link #close()} fails with {@link IllegalStateException} once the store is closed.
 */
public final class ZipStore implements Store, AutoCloseable {

    // Signatures and fixed sizes: PKWARE APPNOTE 6.3.10, sections 4.3.7, 4.3.12, and 4.3.14-4.3.16.
    private static final int LOCAL_HEADER = 0x04034b50;
    private static final int CENTRAL_HEADER = 0x02014b50;
    private static final int END_RECORD = 0x06054b50;
    private static final int ZIP64_END_RECORD = 0x06064b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;
    private static final int LOCAL_HEADER_SIZE = 30;
    private static final int CENTRAL_HEADER_SIZE = 46;
    private static final int END_RECORD_SIZE = 22;
    private static final int ZIP64_END_RECORD_SIZE = 56;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int MAX_COMMENT = 0xFFFF;
    /** The Zip64 extended information extra field's header ID (APPNOTE 4.5.3). */
    private static final int ZIP64_EXTRA = 0x0001;
    /** A 4-byte field holding this means "the value is in the Zip64 extra field or record". */
    private static final long MARK_32 = 0xFFFFFFFFL;
    /** A 2-byte count holding this means "the value is in the Zip64 end of central directory record". */
    private static final int MARK_16 = 0xFFFF;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;
    private static final int FLAG_ENCRYPTED = 0x1;
    private static final int FLAG_UTF8 = 0x800;     // general purpose bit 11: the name is UTF-8 (APPNOTE 4.4.4)
    private static final int VERSION = 20;          // 2.0: the version a reader needs for these entries
    private static final int VERSION_ZIP64 = 45;    // 4.5: ... and for Zip64 (APPNOTE 4.4.3.2)
    private static final int MADE_BY_UNIX = 3 << 8; // "version made by" UNIX: the external attributes hold a mode
    private static final long REGULAR_FILE = 0100644L << 16; // -rw-r--r--, in the external attributes' high half
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;
    private static final int WRITE_BUFFER = 1 << 16;
    private static final int MAX_ATTEMPTS = 8; // tries at a read or write whose file other threads' interrupts close

    private final Path archive;
    private volatile FileChannel channel; // opened again if an interrupted thread's read or write closes it
    private final boolean writable;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>(); // by key, the newest of each name
    private final List<Entry> others;  // directories, and names no key can reach: kept, never read
    private final byte[] comment;      // the archive's comment, kept when it is rewritten
    private final Object writeLock = new Object();
    private long end;                  // writable: where the next entry goes (under writeLock)
    private boolean modified;          // writable: whether close() must write a central directory
    private volatile boolean closed;
    /**
     * Offsets and sizes at or past this are written in Zip64 form: ZIP's own limit, 4 GiB, unless a test
     * lowers it to write Zip64 records without writing gigabytes.
     */
    long zip64Threshold = MARK_32;

    private ZipStore(Path archive, FileChannel channel, boolean writable, CentralDirectory directory) {
        this.archive = archive;
        this.channel = channel;
        this.writable = writable;
        this.others = new ArrayList<>(directory.others);
        this.comment = directory.comment;
        this.end = directory.start;
        this.modified = directory.isNew;
        for (Entry entry : directory.keyed) {
            entries.put(entry.name, entry); // in directory order, so the later entry of a name wins
        }
    }

    /**
     * Opens an existing archive for reading.
     *
     * @param archive the ZIP file
     * @return a read-only store over the archive's entries
     * @throws ZarrException       if the file cannot be read
     * @throws ZarrFormatException if it is not a ZIP archive, or its central directory is damaged
     */
    public static ZipStore openReadOnly(Path archive) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(archive, StandardOpenOption.READ);
            return new ZipStore(archive, channel, false, CentralDirectory.read(channel, archive));
        } catch (IOException e) {
            closeQuietly(channel);
            throw new ZarrException("failed to open ZIP store: " + archive, e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    /**
     * Starts a new, empty archive at {@code archive}, replacing any file there, as zarr-python's
     * {@code ZipStore} does in mode {@code "w"}. {@link #close()} writes the archive's central directory.
     *
     * @param archive the ZIP file to write
     * @return a writable store over the new archive
     * @throws ZarrException if the file cannot be created
     */
    public static ZipStore create(Path archive) {
        try {
            FileChannel channel = FileChannel.open(archive, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE);
            return new ZipStore(archive, channel, true, CentralDirectory.empty());
        } catch (IOException e) {
            throw new ZarrException("failed to create ZIP store: " + archive, e);
        }
    }

    /**
     * Opens an archive to read and add to, as zarr-python's {@code ZipStore} does in mode {@code "a"}: new
     * entries go where the archive's central directory was, and {@link #close()} writes a new one. A missing
     * or empty file starts a new archive.
     *
     * @param archive the ZIP file
     * @return a writable store over the archive's entries
     * @throws ZarrException       if the file cannot be read or written
     * @throws ZarrFormatException if the file is neither empty nor a ZIP archive, or its central directory
     *                             is damaged
     */
    public static ZipStore open(Path archive) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(archive, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            CentralDirectory directory = channel.size() == 0 ? CentralDirectory.empty()
                    : CentralDirectory.read(channel, archive);
            return new ZipStore(archive, channel, true, directory);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new ZarrException("failed to open ZIP store: " + archive, e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    /**
     * Packs every key of {@code source} into a new ZIP archive at {@code target}, replacing any file there.
     * Entries are {@code STORED}, uncompressed, as zarr-python's {@code ZipStore} writes them: chunks are
     * compressed by their codecs already, and a stored entry can be read a range at a time. Packing a
     * {@code ZipStore} into a new archive leaves out its dead space (values written over, or deleted).
     *
     * @param source the store to copy; it must be able to list its keys
     * @param target the ZIP file to write, replacing any file there
     * @throws IllegalArgumentException      if {@code source} is a {@code ZipStore} over {@code target} itself
     * @throws UnsupportedOperationException if {@code source} cannot list its keys
     * @throws ZarrException                 if the archive cannot be written
     */
    public static void pack(Store source, Path target) {
        if (source instanceof ZipStore zip && sameFile(zip.archive, target)) {
            throw new IllegalArgumentException("cannot pack a ZIP store into its own archive: " + target);
        }
        try (ZipStore zip = create(target)) {
            for (String key : source.list()) {
                byte[] value = source.get(key).orElse(null);
                if (value != null) {
                    zip.set(key, value);
                }
            }
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        StoreKeys.validate(key);
        Entry entry = entry(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.size > MAX_ARRAY) {
            throw new ZarrException("ZIP entry '" + key + "' holds " + entry.size
                    + " bytes, more than one array holds; read it a range at a time");
        }
        byte[] value = read(entry, 0, (int) entry.size);
        if (value.length != entry.size) {
            throw new ZarrFormatException("ZIP entry '" + key + "' holds " + value.length + " bytes, not the "
                    + entry.size + " its directory says");
        }
        CRC32 crc = new CRC32();
        crc.update(value);
        if (crc.getValue() != entry.crc) {
            throw new ZarrFormatException("ZIP entry '" + key + "' fails its CRC-32 check");
        }
        return Optional.of(value);
    }

    @Override
    public Optional<byte[]> getRange(String key, long offset, long length) {
        int len = MemoryStore.checkedLength(offset, length);
        StoreKeys.validate(key);
        Entry entry = entry(key);
        return entry == null ? Optional.empty() : Optional.of(read(entry, offset, len));
    }

    @Override
    public Optional<byte[]> getSuffix(String key, long length) {
        int len = MemoryStore.checkedLength(0, length);
        StoreKeys.validate(key);
        Entry entry = entry(key);
        return entry == null ? Optional.empty() : Optional.of(read(entry, Math.max(0, entry.size - len), len));
    }

    @Override
    public boolean exists(String key) {
        StoreKeys.validate(key);
        return entry(key) != null;
    }

    @Override
    public OptionalLong size(String key) {
        StoreKeys.validate(key);
        Entry entry = entry(key);
        return entry == null ? OptionalLong.empty() : OptionalLong.of(entry.size);
    }

    @Override
    public List<String> list() {
        return StoreKeys.listPrefix(keys(), "");
    }

    @Override
    public List<String> listPrefix(String prefix) {
        return StoreKeys.listPrefix(keys(), prefix);
    }

    @Override
    public List<String> listDir(String prefix) {
        return StoreKeys.listDir(keys(), prefix);
    }

    /**
     * Whether the store can be written: true when opened with {@link #create} or {@link #open}, false
     * with {@link #openReadOnly}.
     *
     * @return whether {@link #set} and {@link #delete} are allowed
     */
    @Override
    public boolean isWritable() {
        return writable;
    }

    /**
     * Appends {@code value} to the archive as a {@code STORED} entry named {@code key}. A key already
     * present names the new entry from now on; the old one's bytes stay as dead space.
     *
     * @param key   the key
     * @param value the value
     * @throws UnsupportedOperationException if the store was opened read-only
     * @throws IllegalStateException         if the store is closed
     * @throws ZarrException                 if the file cannot be written
     */
    @Override
    public void set(String key, byte[] value) {
        StoreKeys.validate(key);
        Objects.requireNonNull(value, "value");
        checkWritable();
        byte[] name = key.getBytes(StandardCharsets.UTF_8);
        int flags = name.length == key.length() ? 0 : FLAG_UTF8; // only ASCII is as long in UTF-8
        CRC32 crc = new CRC32();
        crc.update(value);
        int[] dos = dosDateTime(LocalDateTime.now());
        // The local file header (APPNOTE 4.3.7). A value is an array, under 4 GiB: it never needs Zip64 here.
        ByteBuffer header = ByteBuffer.allocate(LOCAL_HEADER_SIZE + name.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(LOCAL_HEADER).putShort((short) VERSION).putShort((short) flags).putShort((short) STORED)
                .putShort((short) dos[0]).putShort((short) dos[1]).putInt((int) crc.getValue())
                .putInt(value.length).putInt(value.length).putShort((short) name.length).putShort((short) 0)
                .put(name).flip();
        synchronized (writeLock) {
            ensureOpen();
            long offset = end;
            try {
                writeFully(header, offset);
                writeFully(ByteBuffer.wrap(value), offset + header.capacity());
            } catch (IOException e) {
                throw failure("failed to write ZIP entry '" + key + "'", e);
            }
            end = offset + header.capacity() + value.length;
            Entry entry = new Entry(key, name, flags, STORED, crc.getValue(), value.length, value.length, dos[0],
                    dos[1], MADE_BY_UNIX | VERSION, 0, REGULAR_FILE, new byte[0], new byte[0], offset);
            entry.dataOffset = offset + header.capacity();
            entries.put(key, entry);
            modified = true;
        }
    }

    /**
     * Drops {@code key} from the archive's central directory; its bytes stay in the file as dead space. A
     * no-op if the key is absent.
     *
     * @param key the key
     * @throws UnsupportedOperationException if the store was opened read-only
     * @throws IllegalStateException         if the store is closed
     */
    @Override
    public void delete(String key) {
        StoreKeys.validate(key);
        checkWritable();
        synchronized (writeLock) {
            ensureOpen();
            if (entries.remove(key) != null) {
                modified = true;
            }
        }
    }

    /**
     * Writes the central directory, if the store is writable and anything changed, and closes the file. A
     * second call does nothing.
     *
     * @throws ZarrException if the central directory cannot be written, or the file closed
     */
    @Override
    public void close() {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            try {
                if (writable && modified) {
                    writeCentralDirectory();
                }
            } catch (IOException e) {
                throw new ZarrException("failed to write the ZIP archive's central directory: " + archive, e);
            } finally {
                closed = true;
                try {
                    channel.close(); // the current channel: writing may have opened the file again
                } catch (IOException e) {
                    throw new ZarrException("failed to close ZIP store: " + archive, e);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "ZipStore(" + archive + (writable ? ")" : ", read-only)");
    }

    /** The entry named {@code key}, or null. */
    private Entry entry(String key) {
        ensureOpen();
        return entries.get(key);
    }

    private List<String> keys() {
        ensureOpen();
        return List.copyOf(entries.keySet());
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ZIP store is closed: " + archive);
        }
    }

    private void checkWritable() {
        if (!writable) {
            throw new UnsupportedOperationException(
                    "ZIP store is read-only; open it with ZipStore.open to add to it");
        }
    }

    /** An I/O failure, or the store's having been closed under a read or write. */
    private RuntimeException failure(String message, IOException e) {
        if (closed) {
            return new IllegalStateException("ZIP store is closed: " + archive, e);
        }
        return new ZarrException(message, e);
    }

    /**
     * Up to {@code len} bytes of {@code entry}'s value from {@code offset}, clamped to its size. A stored
     * entry is read at the range; a deflated one is inflated from its start up to the range's end.
     */
    private byte[] read(Entry entry, long offset, int len) {
        if ((entry.flags & FLAG_ENCRYPTED) != 0) {
            throw new ZarrUnsupportedException("ZIP entry '" + entry.name + "' is encrypted");
        }
        if (entry.method != STORED && entry.method != DEFLATED) {
            throw new ZarrUnsupportedException("ZIP entry '" + entry.name + "' is compressed with method "
                    + entry.method + "; only STORED (0) and DEFLATED (8) are supported");
        }
        if (offset >= entry.size || len == 0) {
            return new byte[0];
        }
        int n = (int) Math.min(len, entry.size - offset);
        try {
            long data = dataOffset(entry);
            if (entry.method == STORED) {
                ByteBuffer out = ByteBuffer.allocate(n);
                readFully(out, data + offset, entry.name);
                return out.array();
            }
            Inflater inflater = new Inflater(true);
            try (InputStream in = new InflaterInputStream(new ChannelInput(data, entry.compressedSize), inflater)) {
                if (!HttpIo.skip(in, offset)) {
                    throw new ZarrFormatException("ZIP entry '" + entry.name + "' inflates to fewer than "
                            + offset + " bytes, though its directory says " + entry.size);
                }
                return in.readNBytes(n);
            } finally {
                inflater.end(); // a caller's Inflater is not ended by the stream's close()
            }
        } catch (ZipException e) {
            throw new ZarrFormatException("ZIP entry '" + entry.name + "' is not valid deflate data", e);
        } catch (IOException e) {
            throw failure("failed to read ZIP entry '" + entry.name + "'", e);
        }
    }

    /**
     * Where {@code entry}'s data begins: just past its local header (APPNOTE 4.3.7), whose name must be the
     * central directory's, and whose data must end within the file.
     */
    private long dataOffset(Entry entry) throws IOException {
        long data = entry.dataOffset;
        if (data >= 0) {
            return data;
        }
        ByteBuffer header = ByteBuffer.allocate(LOCAL_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(header, entry.headerOffset, entry.name);
        if (header.getInt(0) != LOCAL_HEADER) {
            throw new ZarrFormatException("ZIP entry '" + entry.name + "' has no local header at byte "
                    + entry.headerOffset);
        }
        int nameLength = Short.toUnsignedInt(header.getShort(26));
        int extraLength = Short.toUnsignedInt(header.getShort(28));
        ByteBuffer name = ByteBuffer.allocate(nameLength);
        readFully(name, entry.headerOffset + LOCAL_HEADER_SIZE, entry.name);
        if (!Arrays.equals(name.array(), entry.nameBytes)) {
            throw new ZarrFormatException("ZIP entry '" + entry.name + "' is named differently in its local header");
        }
        data = entry.headerOffset + LOCAL_HEADER_SIZE + nameLength + extraLength;
        if (data + entry.compressedSize > fileSize()) {
            throw new ZarrFormatException("ZIP entry '" + entry.name + "' runs past the end of the archive");
        }
        entry.dataOffset = data;
        return data;
    }

    /** Fills {@code buffer} from {@code position}; the file must hold that many bytes there. */
    private void readFully(ByteBuffer buffer, long position, String name) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int n = readSome(buffer, at);
            if (n < 0) {
                throw new ZarrFormatException("ZIP entry '" + name + "' runs past the end of the archive");
            }
            at += n;
        }
    }

    private void writeFully(ByteBuffer buffer, long position) throws IOException {
        long at = position;
        for (int attempt = 1; buffer.hasRemaining(); ) {
            FileChannel c = channel;
            try {
                at += c.write(buffer, at);
            } catch (ClosedChannelException e) {
                recover(c, e, attempt++);
            }
        }
    }

    /** Reads into {@code buffer} from {@code position}: the bytes read, or -1 at the end of the file. */
    private int readSome(ByteBuffer buffer, long position) throws IOException {
        for (int attempt = 1; ; attempt++) {
            FileChannel c = channel;
            try {
                return c.read(buffer, position);
            } catch (ClosedChannelException e) {
                recover(c, e, attempt);
            }
        }
    }

    private long fileSize() throws IOException {
        for (int attempt = 1; ; attempt++) {
            FileChannel c = channel;
            try {
                return c.size();
            } catch (ClosedChannelException e) {
                recover(c, e, attempt);
            }
        }
    }

    /**
     * After {@code failed} was closed under a read or write: unless the store was closed, opens the file
     * again for the threads to carry on with. This thread fails if it was the one interrupted (the JDK
     * closes a channel when a thread using it is interrupted); otherwise it tries again, a few times.
     */
    private void recover(FileChannel failed, ClosedChannelException e, int attempt) throws IOException {
        synchronized (writeLock) {
            if (closed) {
                throw new IllegalStateException("ZIP store is closed: " + archive, e);
            }
            if (channel == failed) {
                channel = writable ? FileChannel.open(archive, StandardOpenOption.READ, StandardOpenOption.WRITE)
                        : FileChannel.open(archive, StandardOpenOption.READ);
            }
        }
        if (e instanceof ClosedByInterruptException) {
            throw new ZarrException("interrupted while using ZIP store: " + archive, e);
        }
        if (attempt >= MAX_ATTEMPTS) {
            throw new ZarrException("ZIP store's file was closed under " + attempt + " tries in a row: " + archive, e);
        }
        // Closed by another thread's interrupt, before this read or write or during it: try again.
    }

    /**
     * Writes the central directory (APPNOTE 4.3.12) after the entries, then the end of central directory
     * record (4.3.16), preceded by the Zip64 record and locator (4.3.14, 4.3.15) when a count, size, or
     * offset does not fit the record's fields; and cuts the file there.
     */
    private void writeCentralDirectory() throws IOException {
        List<Entry> all = new ArrayList<>(entries.values());
        all.addAll(others);
        all.sort(Comparator.comparingLong(e -> e.headerOffset));
        long start = end;
        long at = start;
        ByteBuffer buffer = ByteBuffer.allocate(WRITE_BUFFER).order(ByteOrder.LITTLE_ENDIAN);
        for (Entry e : all) {
            boolean bigSize = e.size >= zip64Threshold;
            boolean bigCompressed = e.compressedSize >= zip64Threshold;
            boolean bigOffset = e.headerOffset >= zip64Threshold;
            int zip64Length = (bigSize ? 8 : 0) + (bigCompressed ? 8 : 0) + (bigOffset ? 8 : 0);
            int extraLength = e.extra.length + (zip64Length > 0 ? 4 + zip64Length : 0);
            if (extraLength > 0xFFFF) {
                throw new ZarrException("ZIP entry '" + e.name + "' has too many extra fields to rewrite");
            }
            int recordLength = CENTRAL_HEADER_SIZE + e.nameBytes.length + extraLength + e.comment.length;
            if (buffer.remaining() < recordLength) {
                at = flush(buffer, at);
                if (buffer.capacity() < recordLength) {
                    buffer = ByteBuffer.allocate(recordLength).order(ByteOrder.LITTLE_ENDIAN);
                }
            }
            int needed = zip64Length > 0 ? VERSION_ZIP64 : VERSION;
            buffer.putInt(CENTRAL_HEADER)
                    .putShort((short) ((e.versionMadeBy & 0xFF00) | Math.max(needed, e.versionMadeBy & 0xFF)))
                    .putShort((short) needed).putShort((short) e.flags).putShort((short) e.method)
                    .putShort((short) e.dosTime).putShort((short) e.dosDate).putInt((int) e.crc)
                    .putInt((int) (bigCompressed ? MARK_32 : e.compressedSize))
                    .putInt((int) (bigSize ? MARK_32 : e.size))
                    .putShort((short) e.nameBytes.length).putShort((short) extraLength)
                    .putShort((short) e.comment.length).putShort((short) 0)
                    .putShort((short) e.internalAttributes).putInt((int) e.externalAttributes)
                    .putInt((int) (bigOffset ? MARK_32 : e.headerOffset)).put(e.nameBytes);
            if (zip64Length > 0) { // the Zip64 extra field: only the values marked above, in this order (4.5.3)
                buffer.putShort((short) ZIP64_EXTRA).putShort((short) zip64Length);
                if (bigSize) {
                    buffer.putLong(e.size);
                }
                if (bigCompressed) {
                    buffer.putLong(e.compressedSize);
                }
                if (bigOffset) {
                    buffer.putLong(e.headerOffset);
                }
            }
            buffer.put(e.extra).put(e.comment);
        }
        at = flush(buffer, at);
        long size = at - start;
        long count = all.size();
        if (count >= MARK_16 || size >= zip64Threshold || start >= zip64Threshold) {
            buffer.putInt(ZIP64_END_RECORD).putLong(ZIP64_END_RECORD_SIZE - 12)
                    .putShort((short) (MADE_BY_UNIX | VERSION_ZIP64)).putShort((short) VERSION_ZIP64)
                    .putInt(0).putInt(0).putLong(count).putLong(count).putLong(size).putLong(start);
            buffer.putInt(ZIP64_LOCATOR).putInt(0).putLong(at).putInt(1);
        }
        int count16 = (int) Math.min(count, MARK_16);
        buffer.putInt(END_RECORD).putShort((short) 0).putShort((short) 0)
                .putShort((short) count16).putShort((short) count16)
                .putInt((int) (size >= zip64Threshold ? MARK_32 : size))
                .putInt((int) (start >= zip64Threshold ? MARK_32 : start))
                .putShort((short) comment.length).put(comment);
        at = flush(buffer, at);
        channel.truncate(at); // under writeLock, after the writes above: the channel is the current one
    }

    private long flush(ByteBuffer buffer, long at) throws IOException {
        buffer.flip();
        long next = at + buffer.remaining();
        writeFully(buffer, at);
        buffer.clear();
        return next;
    }

    /** {@code {time, date}} in MS-DOS form (APPNOTE 4.4.6); 1980, the earliest it holds, for earlier times. */
    static int[] dosDateTime(LocalDateTime t) {
        if (t.getYear() < 1980) {
            return new int[] {0, (1 << 5) | 1}; // 1980-01-01 00:00
        }
        int time = (t.getHour() << 11) | (t.getMinute() << 5) | (t.getSecond() / 2);
        int date = (Math.min(t.getYear() - 1980, 127) << 9) | (t.getMonthValue() << 5) | t.getDayOfMonth();
        return new int[] {time, date};
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // already failing
            }
        }
    }

    /** One entry of the central directory. */
    private static final class Entry {
        final String name;
        final byte[] nameBytes;
        final int flags;
        final int method;
        final long crc;
        final long compressedSize;
        final long size;
        final int dosTime;
        final int dosDate;
        final int versionMadeBy;
        final int internalAttributes;
        final long externalAttributes;
        final byte[] extra;      // the extra fields but Zip64's, which is rewritten as needed
        final byte[] comment;
        final long headerOffset; // the local header's offset in the file
        volatile long dataOffset = -1; // where the data begins, once known

        Entry(String name, byte[] nameBytes, int flags, int method, long crc, long compressedSize, long size,
              int dosTime, int dosDate, int versionMadeBy, int internalAttributes, long externalAttributes,
              byte[] extra, byte[] comment, long headerOffset) {
            this.name = name;
            this.nameBytes = nameBytes;
            this.flags = flags;
            this.method = method;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.size = size;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.versionMadeBy = versionMadeBy;
            this.internalAttributes = internalAttributes;
            this.externalAttributes = externalAttributes;
            this.extra = extra;
            this.comment = comment;
            this.headerOffset = headerOffset;
        }
    }

    /** An archive's central directory as read: its entries, its comment, and where it began. */
    private static final class CentralDirectory {
        final List<Entry> keyed = new ArrayList<>(); // entries whose names are store keys, in directory order
        final List<Entry> others = new ArrayList<>();
        byte[] comment = new byte[0];
        long start;    // where the central directory began: where a writer adds entries
        boolean isNew; // no archive yet: the writer must write a directory even if nothing is added

        static CentralDirectory empty() {
            CentralDirectory directory = new CentralDirectory();
            directory.isNew = true;
            return directory;
        }

        /**
         * Reads the central directory, found from the end of central directory record and, when present,
         * the Zip64 record and locator before it (APPNOTE 4.3.14-4.3.16). As Python's {@code zipfile} does,
         * an archive with bytes before it (a self-extractor) is read by the difference between where its
         * directory is and where the record says it is.
         */
        static CentralDirectory read(FileChannel channel, Path archive) throws IOException {
            long fileSize = channel.size();
            int tailLength = (int) Math.min(fileSize, END_RECORD_SIZE + MAX_COMMENT);
            ByteBuffer tail = ByteBuffer.allocate(tailLength).order(ByteOrder.LITTLE_ENDIAN);
            readAt(channel, tail, fileSize - tailLength, archive);
            int record = findEndRecord(tail);
            if (record < 0) {
                throw new ZarrFormatException("not a ZIP archive (no end of central directory record): " + archive);
            }
            long recordAt = fileSize - tailLength + record;
            int disk = Short.toUnsignedInt(tail.getShort(record + 4));
            int directoryDisk = Short.toUnsignedInt(tail.getShort(record + 6));
            if ((disk != 0 && disk != MARK_16) || (directoryDisk != 0 && directoryDisk != MARK_16)) {
                throw new ZarrUnsupportedException("ZIP archive spans several disks: " + archive);
            }
            long size = Integer.toUnsignedLong(tail.getInt(record + 12));
            long offset = Integer.toUnsignedLong(tail.getInt(record + 16));
            int commentLength = Math.min(Short.toUnsignedInt(tail.getShort(record + 20)),
                    tailLength - record - END_RECORD_SIZE);
            CentralDirectory directory = new CentralDirectory();
            directory.comment = Arrays.copyOfRange(tail.array(), record + END_RECORD_SIZE,
                    record + END_RECORD_SIZE + commentLength);
            long directoryEnd = recordAt;
            if (recordAt >= ZIP64_LOCATOR_SIZE) {
                ByteBuffer locator = ByteBuffer.allocate(ZIP64_LOCATOR_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                readAt(channel, locator, recordAt - ZIP64_LOCATOR_SIZE, archive);
                if (locator.getInt(0) == ZIP64_LOCATOR) {
                    long zip64At = zip64Record(channel, archive, recordAt - ZIP64_LOCATOR_SIZE, locator.getLong(8));
                    ByteBuffer zip64 = ByteBuffer.allocate(ZIP64_END_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                    readAt(channel, zip64, zip64At, archive);
                    size = zip64.getLong(40);
                    offset = zip64.getLong(48);
                    directoryEnd = zip64At;
                }
            }
            long start = directoryEnd - size;
            if (size < 0 || offset < 0 || start < 0) {
                throw new ZarrFormatException("ZIP archive's central directory is out of bounds: " + archive);
            }
            if (size > MAX_ARRAY) {
                throw new ZarrUnsupportedException("ZIP archive's central directory is over 2 GB: " + archive);
            }
            directory.start = start;
            ByteBuffer cd = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
            readAt(channel, cd, start, archive);
            directory.parse(cd, start - offset, start, archive); // start - offset: bytes before the archive
            return directory;
        }

        /**
         * Where the Zip64 end of central directory record is: just before its locator, or, failing that (a
         * record with extensible data), where the locator says.
         */
        private static long zip64Record(FileChannel channel, Path archive, long locatorAt, long recorded)
                throws IOException {
            ByteBuffer signature = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            long expected = locatorAt - ZIP64_END_RECORD_SIZE;
            if (expected >= 0) {
                readAt(channel, signature, expected, archive);
                if (signature.getInt(0) == ZIP64_END_RECORD) {
                    return expected;
                }
            }
            if (recorded >= 0 && recorded <= locatorAt - ZIP64_END_RECORD_SIZE) {
                signature.clear();
                readAt(channel, signature, recorded, archive);
                if (signature.getInt(0) == ZIP64_END_RECORD) {
                    return recorded;
                }
            }
            throw new ZarrFormatException("ZIP archive has a Zip64 locator but no Zip64 end record: " + archive);
        }

        /**
         * The end of central directory record's position in {@code tail}, or -1: the last signature whose
         * comment ends the file, else the last whose comment fits before the end.
         */
        private static int findEndRecord(ByteBuffer tail) {
            int fallback = -1;
            for (int i = tail.capacity() - END_RECORD_SIZE; i >= 0; i--) {
                if (tail.getInt(i) == END_RECORD) {
                    int end = i + END_RECORD_SIZE + Short.toUnsignedInt(tail.getShort(i + 20));
                    if (end == tail.capacity()) {
                        return i;
                    }
                    if (end < tail.capacity() && fallback < 0) {
                        fallback = i;
                    }
                }
            }
            return fallback;
        }

        /** Parses the central directory's file headers (APPNOTE 4.3.12), with Zip64 values from 4.5.3. */
        private void parse(ByteBuffer cd, long shift, long start, Path archive) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            byte[] bytes = cd.array();
            int p = 0;
            int limit = cd.capacity();
            while (p + 4 <= limit && cd.getInt(p) == CENTRAL_HEADER) {
                if (p + CENTRAL_HEADER_SIZE > limit) {
                    throw damaged(archive);
                }
                int versionMadeBy = Short.toUnsignedInt(cd.getShort(p + 4));
                int flags = Short.toUnsignedInt(cd.getShort(p + 8));
                int method = Short.toUnsignedInt(cd.getShort(p + 10));
                int dosTime = Short.toUnsignedInt(cd.getShort(p + 12));
                int dosDate = Short.toUnsignedInt(cd.getShort(p + 14));
                long crc = Integer.toUnsignedLong(cd.getInt(p + 16));
                long compressedSize = Integer.toUnsignedLong(cd.getInt(p + 20));
                long size = Integer.toUnsignedLong(cd.getInt(p + 24));
                int nameLength = Short.toUnsignedInt(cd.getShort(p + 28));
                int extraLength = Short.toUnsignedInt(cd.getShort(p + 30));
                int commentLength = Short.toUnsignedInt(cd.getShort(p + 32));
                int internal = Short.toUnsignedInt(cd.getShort(p + 36));
                long external = Integer.toUnsignedLong(cd.getInt(p + 38));
                long headerOffset = Integer.toUnsignedLong(cd.getInt(p + 42));
                int nameAt = p + CENTRAL_HEADER_SIZE;
                int extraAt = nameAt + nameLength;
                int extraEnd = extraAt + extraLength;
                int next = extraEnd + commentLength;
                if (next > limit) {
                    throw damaged(archive);
                }
                // The extra fields: Zip64's values, each present only when its field above holds the mark
                // (4.5.3), are read; the others are kept to be written back.
                ByteBuffer kept = ByteBuffer.allocate(extraLength);
                for (int q = extraAt; q < extraEnd; ) {
                    if (q + 4 > extraEnd) {
                        kept.put(bytes, q, extraEnd - q); // trailing padding: keep it as it is
                        break;
                    }
                    int id = Short.toUnsignedInt(cd.getShort(q));
                    int length = Short.toUnsignedInt(cd.getShort(q + 2));
                    if (q + 4 + length > extraEnd) {
                        throw damaged(archive);
                    }
                    if (id == ZIP64_EXTRA) {
                        int v = q + 4;
                        int vEnd = v + length;
                        if (size == MARK_32) {
                            size = zip64Value(cd, v, vEnd, archive);
                            v += 8;
                        }
                        if (compressedSize == MARK_32) {
                            compressedSize = zip64Value(cd, v, vEnd, archive);
                            v += 8;
                        }
                        if (headerOffset == MARK_32) {
                            headerOffset = zip64Value(cd, v, vEnd, archive);
                        }
                    } else {
                        kept.put(bytes, q, 4 + length);
                    }
                    q += 4 + length;
                }
                long at = headerOffset + shift;
                if (size < 0 || compressedSize < 0 || headerOffset < 0 || at < 0 || at >= start) {
                    throw new ZarrFormatException("ZIP archive has an entry outside it: " + archive);
                }
                byte[] nameBytes = Arrays.copyOfRange(bytes, nameAt, extraAt);
                String name = null;
                try {
                    name = decoder.decode(ByteBuffer.wrap(nameBytes)).toString();
                } catch (CharacterCodingException e) {
                    // not UTF-8: no key names it
                }
                Entry entry = new Entry(name, nameBytes, flags, method, crc, compressedSize, size, dosTime,
                        dosDate, versionMadeBy, internal, external, Arrays.copyOf(kept.array(), kept.position()),
                        Arrays.copyOfRange(bytes, extraEnd, next), at);
                if (name != null && isKey(name)) {
                    keyed.add(entry);
                } else {
                    others.add(entry);
                }
                p = next;
            }
            if (p != limit) {
                throw damaged(archive);
            }
        }

        private static long zip64Value(ByteBuffer cd, int at, int end, Path archive) {
            if (at + 8 > end) {
                throw new ZarrFormatException("ZIP archive's Zip64 extra field is too short: " + archive);
            }
            return cd.getLong(at);
        }

        private static boolean isKey(String name) {
            try {
                StoreKeys.validate(name);
                return true;
            } catch (IllegalArgumentException e) {
                return false; // a directory ("a/"), or a name such as "/a" or "a/../b"
            }
        }

        private static ZarrFormatException damaged(Path archive) {
            return new ZarrFormatException("ZIP archive's central directory is damaged: " + archive);
        }

        private static void readAt(FileChannel channel, ByteBuffer buffer, long position, Path archive)
                throws IOException {
            long at = position;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, at);
                if (n < 0) {
                    throw new ZarrFormatException("ZIP archive is truncated: " + archive);
                }
                at += n;
            }
        }
    }

    /**
     * An entry's compressed data, read from the file a block at a time, then one byte more: an
     * {@link Inflater} without the zlib wrapper may need it to see the end of the data, and
     * {@code java.util.zip.ZipFile} gives it one too.
     */
    private final class ChannelInput extends InputStream {
        private long position;
        private long remaining;
        private boolean padded;

        ChannelInput(long position, long length) {
            this.position = position;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (remaining == 0) {
                if (padded) {
                    return -1;
                }
                padded = true;
                b[off] = 0;
                return 1;
            }
            int n = readSome(ByteBuffer.wrap(b, off, (int) Math.min(len, remaining)), position);
            if (n < 0) {
                throw new ZarrFormatException("ZIP entry runs past the end of the archive: " + archive);
            }
            position += n;
            remaining -= n;
            return n;
        }
    }
}
