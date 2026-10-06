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
        Path absolute = target.toAbsolutePath();
        Path temp = absolute.resolveSibling("." + absolute.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
        try {
            FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.SPARSE);
            return new OutputFile(absolute, temp, channel, 0, 0, reserved);
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
