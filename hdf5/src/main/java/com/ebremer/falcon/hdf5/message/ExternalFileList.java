package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.HdfException;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.heap.LocalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;

/**
 * External File List message (type 7, spec section IV.A.2.h): a <b>contiguous</b> dataset whose raw
 * data lives in one or more external raw files rather than inside the HDF5 file. The stored data is the
 * concatenation, in slot order, of a byte region from each external file.
 *
 * <p>Body layout: {@code version(1) · reserved(3) · allocated slots(2) · used slots(2) · heap
 * address(O)}, then per used slot {@code name offset in heap(L) · offset in file(L) · size(L)}. The
 * file names are null-terminated strings in the {@linkplain LocalHeap local heap} at the heap address,
 * and are resolved by the caller (relative to the HDF5 file's own directory, under its external-file
 * policy). A slot's size may be {@code H5F_UNLIMITED} (all ones): the rest of that file.
 *
 * <p>External storage is signalled on a dataset by a contiguous Data Layout message with an
 * {@linkplain HdfBuffer#UNDEFINED_ADDRESS undefined} address alongside this message.
 */
public final class ExternalFileList {

    private final String[] names;
    private final long[] fileOffsets;
    private final long[] sizes;

    private ExternalFileList(String[] names, long[] fileOffsets, long[] sizes) {
        this.names = names;
        this.fileOffsets = fileOffsets;
        this.sizes = sizes;
    }

    /** Parses the External File List message body at {@code bodyOffset}. */
    public static ExternalFileList parse(FileContext ctx, long bodyOffset) {
        HdfBuffer buf = ctx.buffer();
        int version = buf.getUnsignedByte(bodyOffset);
        if (version != 1) {
            throw new HdfFormatException("unsupported external file list version " + version + " at " + bodyOffset);
        }
        int used = buf.getUnsignedShort(bodyOffset + 6); // version(1), reserved(3), allocated slots(2)
        long heapAddress = buf.getAddress(bodyOffset + 8, ctx.sizeOfOffsets());
        LocalHeap heap = LocalHeap.parse(ctx, heapAddress);
        int lengths = ctx.sizeOfLengths();
        long p = bodyOffset + 8 + ctx.sizeOfOffsets();
        String[] names = new String[used];
        long[] fileOffsets = new long[used];
        long[] sizes = new long[used];
        for (int i = 0; i < used; i++) {
            long nameOffset = buf.getUnsignedValue(p, lengths);
            fileOffsets[i] = buf.getUnsignedValue(p + lengths, lengths);
            sizes[i] = buf.getUnsignedValue(p + 2L * lengths, lengths);
            names[i] = heap.name(ctx, nameOffset);
            if (fileOffsets[i] < 0 || (sizes[i] < 0 && sizes[i] != UNLIMITED)) {
                throw new HdfFormatException("external file slot " + i + " has an invalid offset or size at " + bodyOffset);
            }
            p += 3L * lengths;
        }
        return new ExternalFileList(names, fileOffsets, sizes);
    }

    /** A slot size of all ones: the slot extends to the end of its file. */
    private static final long UNLIMITED = -1L;

    /**
     * Assembles the dataset's raw bytes by reading each slot's region from its external file and
     * concatenating them, stopping once {@code byteCount} bytes have been gathered. {@code resolver} maps
     * each stored file name to the file to read (and refuses names its policy forbids). A slot's stored
     * size may exceed what is needed for the final block, so only the required prefix is taken. If an
     * external file is shorter than its slot promises, the missing bytes stay zero.
     */
    public byte[] readData(Function<String, Path> resolver, long byteCount) {
        byte[] out = new byte[com.ebremer.falcon.hdf5.data.Elements.checkedInt(byteCount)];
        int pos = 0;
        for (int i = 0; i < names.length && pos < out.length; i++) {
            Path file = resolver.apply(names[i]);
            int want = sizes[i] == UNLIMITED ? out.length - pos : (int) Math.min(sizes[i], out.length - pos);
            readInto(file, fileOffsets[i], out, pos, want);
            pos += want;
        }
        return out;
    }

    private static void readInto(Path file, long fileOffset, byte[] out, int destPos, int length) {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(fileOffset);
            ByteBuffer bb = ByteBuffer.wrap(out, destPos, length);
            while (bb.hasRemaining() && channel.read(bb) >= 0) {
                // keep reading until the region is filled or the file ends (short files leave zeros)
            }
        } catch (IOException e) {
            throw new HdfException("failed to read external data file " + file, e);
        }
    }
}
