package com.ebremer.falcon.hdf5;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/**
 * Random access to an HDF5 file's bytes wherever they are: an object store or HTTP server that serves
 * byte ranges, a database blob, a channel. {@link Hdf5File#open(RangeReader)} reads through it on demand,
 * so opening a large remote file and reading a small part of it fetches only the metadata and the chunks
 * that part needs.
 *
 * <p>Falcon reads metadata in pages of 64&nbsp;KiB, which it caches, and reads larger ranges (a chunk, a
 * selection of contiguous data) with one call each. Implementations must allow calls from several
 * threads at once, since the file's objects may be read concurrently. Falcon never closes the source:
 * close it, if it needs closing, after closing the {@link Hdf5File}.
 *
 * <pre>{@code
 * try (SeekableByteChannel channel = Files.newByteChannel(path);
 *      Hdf5File h5 = Hdf5File.open(RangeReader.of(channel))) {
 *     ...
 * }
 * }</pre>
 */
public interface RangeReader {

    /** The file's size in bytes. Falcon asks once, when the file is opened. */
    long size() throws IOException;

    /**
     * Reads the bytes starting at {@code position} into {@code destination} until it is full
     * ({@code destination.remaining()} bytes).
     *
     * @throws EOFException if the file ends first
     * @throws IOException  if the bytes cannot be read; Falcon reports it as an
     *                      {@link java.io.UncheckedIOException} from the read that needed them
     */
    void read(long position, ByteBuffer destination) throws IOException;

    /**
     * A reader over {@code channel}, which must stay open while the file is read. Concurrent reads of a
     * {@link FileChannel} use its positional reads; any other channel's reads are serialized, since each
     * moves the channel's position.
     */
    static RangeReader of(SeekableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (channel instanceof FileChannel file) {
            return new RangeReader() {
                @Override
                public long size() throws IOException {
                    return file.size();
                }

                @Override
                public void read(long position, ByteBuffer destination) throws IOException {
                    while (destination.hasRemaining()) {
                        int n = file.read(destination, position);
                        if (n < 0) {
                            throw new EOFException("end of file at " + position);
                        }
                        position += n;
                    }
                }
            };
        }
        return new RangeReader() {
            @Override
            public long size() throws IOException {
                synchronized (channel) {
                    return channel.size();
                }
            }

            @Override
            public void read(long position, ByteBuffer destination) throws IOException {
                synchronized (channel) {
                    channel.position(position);
                    while (destination.hasRemaining()) {
                        if (channel.read(destination) < 0) {
                            throw new EOFException("end of channel at " + channel.position());
                        }
                    }
                }
            }
        };
    }
}
