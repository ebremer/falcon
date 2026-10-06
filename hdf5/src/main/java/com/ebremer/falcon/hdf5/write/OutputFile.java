package com.ebremer.falcon.hdf5.write;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The file an {@code Hdf5Writer} writes: a temporary file beside the target, into which raw data is
 * streamed at long file offsets as it is written, and then the metadata, before it is moved into place.
 * Space is allocated from the end; bytes already written can be written again in place (the positional
 * patches of references and variable-length ids) and read back (a chunk written again).
 *
 * <p>Or an existing file, being changed ({@link #openExisting}): new space is allocated after its end,
 * and its positions are HDF5 addresses, relative to its superblock (after a user block, if it has one).
 *
 * <p>The file is opened sparse, so a region allocated and never written takes no disk space where the
 * file system supports it. An I/O failure is an {@link UncheckedIOException}: the writer's methods that
 * stream data do not declare {@link IOException}.
 */
public final class OutputFile {

    /** The most bytes {@link #fill} writes at a time. */
    private static final int FILL_BLOCK = 1 << 20;

    private final Path target;
    private final Path temp;            // null for an existing file, changed in place
    private final FileChannel channel;
    private final long base;            // the file offset of address 0
    private final long originalSize;    // an existing file's size, restored by discard()
    private long end;

    private OutputFile(Path target, Path temp, FileChannel channel, long base, long originalSize, long end) {
        this.target = target;
        this.temp = temp;
        this.channel = channel;
        this.base = base;
        this.originalSize = originalSize;
        this.end = end;
    }

    /** Creates the temporary file for {@code target}, its first {@code reserved} bytes kept for the superblock. */
    public static OutputFile create(Path target, long reserved) {
        return create(target, reserved, new byte[0]);
    }

    /**
     * Creates the temporary file for {@code target}, beginning with {@code userBlock} (a user block, or
     * nothing): its addresses start after it, and their first {@code reserved} bytes are kept for the
     * superblock.
     */
    public static OutputFile create(Path target, long reserved, byte[] userBlock) {
        Path absolute = target.toAbsolutePath();
        Path temp = absolute.resolveSibling("." + absolute.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
        try {
            FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.SPARSE);
            OutputFile file = new OutputFile(absolute, temp, channel, userBlock.length, 0, reserved);
            file.write(-userBlock.length, userBlock);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + temp, e);
        }
    }

    /**
     * Opens the existing file {@code path} to change it in place: its addresses start at file offset
     * {@code base}, and new space is allocated from address {@code endOfFile}.
     */
    public static OutputFile openExisting(Path path, long base, long endOfFile) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            return new OutputFile(path.toAbsolutePath(), null, channel, base, channel.size(), endOfFile);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private Path file() {
        return temp != null ? temp : target;
    }

    /** The end of the space allocated so far. */
    public long end() {
        return end;
    }

    /** Allocates {@code size} bytes at the end, 8-byte aligned, and returns their offset. */
    public long allocate(long size) {
        long at = (end + 7) & ~7L;
        end = Math.addExact(at, size);
        return at;
    }

    /** Writes {@code length} bytes of {@code bytes} from {@code offset} at file offset {@code position}. */
    public void write(long position, byte[] bytes, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
        long at = base + position;
        try {
            while (buffer.hasRemaining()) {
                at += channel.write(buffer, at);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file(), e);
        }
    }

    public void write(long position, byte[] bytes) {
        write(position, bytes, 0, bytes.length);
    }

    /** Writes the little-endian 8-byte {@code value} at {@code position}. */
    public void writeU64(long position, long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (value >>> (8 * i));
        }
        write(position, bytes);
    }

    /** Writes {@code element} over and over across {@code length} bytes at {@code position}. */
    public void fill(long position, long length, byte[] element) {
        int perBlock = Math.max(1, FILL_BLOCK / element.length);
        byte[] block = new byte[perBlock * element.length];
        for (int i = 0; i < perBlock; i++) {
            System.arraycopy(element, 0, block, i * element.length, element.length);
        }
        for (long done = 0; done < length; ) {
            int n = (int) Math.min(block.length, length - done);
            write(position + done, block, 0, n);
            done += n;
        }
    }

    /** The {@code length} bytes at {@code position}. */
    public byte[] read(long position, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, base + position + buffer.position()) < 0) {
                    throw new IOException("the file ends at " + (position + buffer.position()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file(), e);
        }
        return buffer.array();
    }

    /**
     * Flushes the file to disk, closes it, and moves it into place (atomically where the file system can),
     * replacing any file there.
     */
    public void commit() throws IOException {
        if (channel.size() < base + end) {
            write(end - 1, new byte[1]); // space allocated but never written (alignment): the file must reach its end
        }
        channel.truncate(base + end);
        channel.force(true);
        channel.close();
        if (temp == null) {
            return; // an existing file, changed in place
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ the journal (P2 WF10)

    private static final byte[] JOURNAL = {'F', 'a', 'l', 'c', 'o', 'n', 'J', '1'};
    private static final int TRAILER = 4 + 8 + 8; // checksum, body length, signature

    /**
     * Writes {@code writes} (each, bytes at an address) as a redo journal after the end of the space allocated,
     * and flushes the file to disk: written over the file once it is on disk, they can be redone if that is
     * interrupted ({@link #recover}). {@link #commit} cuts it off.
     *
     * <p>The journal: its signature, the number of writes, each write's file offset (absolute: past a user
     * block too), length and bytes; then a trailer at the file's very end, by which it is found: the lookup3
     * checksum of all that, its length, and the signature again.
     */
    public void writeJournal(List<ObjectHeaderEditor.Patch> writes) {
        GrowBuffer body = new GrowBuffer();
        body.bytes(JOURNAL);
        body.u32(writes.size());
        for (ObjectHeaderEditor.Patch write : writes) {
            body.u64(base + write.address());
            body.u32(write.bytes().length);
            body.bytes(write.bytes());
        }
        byte[] journal = body.toByteArray();
        GrowBuffer trailer = new GrowBuffer();
        trailer.u32(com.ebremer.falcon.hdf5.checksum.Lookup3.hashLittle(journal, 0, journal.length, 0));
        trailer.u64(journal.length);
        trailer.bytes(JOURNAL);
        write(end, journal);
        write(end + journal.length, trailer.toByteArray());
        force();
    }

    /**
     * Redoes a journal an interrupted change left at the end of the file at {@code path}, if there is one:
     * its writes, then the file cut back to where the journal begins (the changed file's end), each flushed
     * to disk. A journal cut short, or that fails its checksum, was never relied on, and is cut off.
     *
     * @return true if a journal was redone
     */
    public static boolean recover(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (!hasJournal(channel)) {
                return false;
            }
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = channel.size();
            ByteBuffer trailer = readFully(channel, size - TRAILER, TRAILER);
            int checksum = trailer.getInt(0);
            long length = trailer.getLong(4);
            long start = size - TRAILER - length;
            ByteBuffer journal = readFully(channel, start, (int) length);
            byte[] bytes = journal.array();
            if (com.ebremer.falcon.hdf5.checksum.Lookup3.hashLittle(bytes, 0, bytes.length, 0) != checksum
                    || !java.util.Arrays.equals(bytes, 0, 8, JOURNAL, 0, 8)) {
                return false; // never complete: nothing was written over the file
            }
            int count = journal.getInt(8);
            int p = 12;
            for (int i = 0; i < count; i++) {
                long at = journal.getLong(p);
                int n = journal.getInt(p + 8);
                ByteBuffer write = ByteBuffer.wrap(bytes, p + 12, n);
                while (write.hasRemaining()) {
                    at += channel.write(write, at);
                }
                p += 12 + n;
            }
            channel.force(true);
            channel.truncate(start);
            channel.force(true);
            return true;
        }
    }

    /** True if the file ends with a journal's trailer (its signature, and a length within the file). */
    private static boolean hasJournal(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size < TRAILER + JOURNAL.length + 4) {
            return false;
        }
        ByteBuffer trailer = readFully(channel, size - TRAILER, TRAILER);
        long length = trailer.getLong(4);
        return java.util.Arrays.equals(trailer.array(), 12, 20, JOURNAL, 0, 8)
                && length >= JOURNAL.length + 4 && length <= size - TRAILER && length < Integer.MAX_VALUE;
    }

    private static ByteBuffer readFully(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new IOException("the file ends at " + (position + buffer.position()));
            }
        }
        return buffer.flip();
    }

    /**
     * Closes an existing file being changed without cutting off what was added: its journal stays, to be
     * redone when the file is next opened (after a failure while it was written over).
     */
    public void closeKeepingJournal() {
        try {
            channel.close();
        } catch (IOException e) {
            // the journal is on disk already
        }
    }

    /**
     * Flushes what has been written so far to disk: before an existing file's structures are changed to
     * point at it.
     */
    public void force() {
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file(), e);
        }
    }

    /**
     * Closes and deletes the temporary file, leaving the target as it was; or, for an existing file, cuts
     * what was added after its end (what was written within it stays).
     */
    public void discard() {
        if (temp == null) {
            try (channel) {
                channel.truncate(originalSize);
            } catch (IOException e) {
                // best effort: the added space is past the file's recorded end, which readers ignore
            }
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            // deleting it is what matters
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            // best effort: a temporary file in the target's directory is left behind
        }
    }
}
