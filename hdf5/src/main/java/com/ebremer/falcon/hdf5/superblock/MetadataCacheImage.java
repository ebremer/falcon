package com.ebremer.falcon.hdf5.superblock;

import com.ebremer.falcon.hdf5.HdfException;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.checksum.MetadataChecksum;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.message.MetadataCacheImageMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * The metadata cache image block (spec section III.J, signature {@code "MDCI"}): what libhdf5's metadata
 * cache held when a file was closed with {@code H5Pset_mdc_image_config}, written as one block. Each entry
 * is the bytes of one piece of metadata (an object header, a B-tree node, a heap block) as they belong at
 * its own address, where libhdf5 has not written them: it reads them from the image when it next opens the
 * file, and writes them out only when it opens it for writing. So the file's metadata is read from the
 * image's entries, at their addresses, and the rest of the file from where it is.
 *
 * <p>Block layout: {@code "MDCI"}(4) · version(1)=0 · flags(1) · image data length(L) · number of
 * entries(4), the entries, then checksum(4), the lookup3 hash of all before it. The image data length is the
 * block's whole size, which the Metadata Cache Image message also gives.
 *
 * <p>Entry layout: {@code type(1) · flags(1) · ring(1) · age(1) · flush dependency child count(2) · dirty
 * child count(2) · parent count(2) · LRU rank(4) · entry address(O) · entry length(L)}, then that many
 * parent addresses(O), then the entry's image. Only the address, the length, and the image matter to a
 * reader; the rest tells libhdf5 how to put its cache back together.
 *
 * <p>libhdf5 leaves the superblock and its extension out of the image, so the message that points at the
 * block is read from where it is.
 */
public final class MetadataCacheImage {

    private static final byte[] MDCI = {'M', 'D', 'C', 'I'};
    private static final int FLAG_RESIZE_STATUS = 0x01;

    private MetadataCacheImage() {
    }

    /**
     * The bytes of a file as its metadata cache image, if it has one, makes them: {@code file} itself if
     * its superblock extension holds no Metadata Cache Image message, and otherwise {@code file} with every
     * entry of the image read at the entry's address.
     *
     * <p>An extension that cannot be read is left to whoever asks for it to report: the file opens as it is.
     *
     * @param file          the file's bytes, from its superblock on
     * @param sizeOfOffsets the superblock's size of offsets
     * @param sizeOfLengths the superblock's size of lengths
     * @param extension     the superblock extension's address, or undefined
     * @throws HdfFormatException if the message or the image block is corrupt
     * @throws HdfUnsupportedException if the image holds the cache's resize status, which libhdf5 does not
     *         write, nor read
     */
    public static HdfBuffer apply(HdfBuffer file, int sizeOfOffsets, int sizeOfLengths, long extension) {
        if (extension == HdfBuffer.UNDEFINED_ADDRESS) {
            return file;
        }
        HeaderMessage message;
        try {
            message = ObjectHeader.parse(new FileContext(file, sizeOfOffsets, sizeOfLengths), extension)
                    .find(MessageType.METADATA_CACHE_IMAGE);
        } catch (HdfException e) {
            return file;
        }
        if (message == null) {
            return file;
        }
        MetadataCacheImageMessage image = MetadataCacheImageMessage.parse(message.body(), sizeOfOffsets, sizeOfLengths);
        return read(file, sizeOfOffsets, sizeOfLengths, image.address(), image.size());
    }

    /** {@code file} with the entries of the image block of {@code size} bytes at {@code address} in place. */
    static HdfBuffer read(HdfBuffer file, int sizeOfOffsets, int sizeOfLengths, long address, long size) {
        int header = 4 + 1 + 1 + sizeOfLengths + 4;
        int entryHeader = 1 + 1 + 1 + 1 + 2 + 2 + 2 + 4 + sizeOfOffsets + sizeOfLengths;
        if (address < 0 || size < header + 4 || address > file.size() || size > file.size() - address) {
            throw new HdfFormatException("metadata cache image of " + size + " bytes at " + describe(address)
                    + " lies outside the " + file.size() + "-byte file");
        }
        if (!file.hasSignature(address, MDCI)) {
            throw new HdfFormatException("expected metadata cache image signature 'MDCI' at " + address);
        }
        int version = file.getUnsignedByte(address + 4);
        if (version != 0) {
            throw new HdfFormatException("unsupported metadata cache image version " + version + " at " + address);
        }
        if ((file.getUnsignedByte(address + 5) & FLAG_RESIZE_STATUS) != 0) {
            throw new HdfUnsupportedException("the metadata cache image at " + address
                    + " holds the cache's resize status, which libhdf5 neither writes nor reads");
        }
        long dataLength = file.getUnsignedValue(address + 6, sizeOfLengths);
        if (dataLength != size) {
            throw new HdfFormatException("metadata cache image at " + address + " says it is " + dataLength
                    + " bytes, but its message says " + size);
        }
        long end = address + size - 4; // where the entries end and the checksum is
        MetadataChecksum.verify(file, address, size - 4, "metadata cache image");
        long count = file.getUnsignedInt(address + 6 + sizeOfLengths);
        if (count == 0 || count > (size - header - 4) / (entryHeader + 1)) {
            throw new HdfFormatException("metadata cache image at " + address + " says it holds " + count
                    + " entries, in " + size + " bytes");
        }
        List<long[]> entries = new ArrayList<>(); // each: address, length, where its image is
        long p = address + header;
        for (long i = 0; i < count; i++) {
            if (entryHeader > end - p) {
                throw new HdfFormatException("metadata cache image entry " + i + " at " + p + " runs past the image's end");
            }
            long parents = file.getUnsignedShort(p + 8);
            long entryAddress = file.getAddress(p + 14, sizeOfOffsets);
            long length = file.getUnsignedValue(p + 14 + sizeOfOffsets, sizeOfLengths);
            p += entryHeader + parents * sizeOfOffsets;
            if (length <= 0) {
                throw new HdfFormatException("metadata cache image entry " + i + " (for " + describe(entryAddress)
                        + ") is " + length + " bytes long");
            }
            if (p > end || length > end - p) {
                throw new HdfFormatException("metadata cache image entry " + i + " (" + length + " bytes for "
                        + describe(entryAddress) + ", after " + parents + " parent addresses) runs past the image's end");
            }
            if (entryAddress < 0 || entryAddress > file.size() || length > file.size() - entryAddress) {
                throw new HdfFormatException("metadata cache image entry " + i + " (" + length + " bytes for "
                        + describe(entryAddress) + ") lies outside the " + file.size() + "-byte file");
            }
            entries.add(new long[] {entryAddress, length, p});
            p += length;
        }
        entries.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] addresses = new long[entries.size()];
        long[] lengths = new long[entries.size()];
        long[] sources = new long[entries.size()];
        for (int i = 0; i < addresses.length; i++) {
            long[] entry = entries.get(i);
            if (i > 0 && entry[0] < addresses[i - 1] + lengths[i - 1]) {
                throw new HdfFormatException("metadata cache image entries for address " + addresses[i - 1] + " ("
                        + lengths[i - 1] + " bytes) and address " + entry[0] + " overlap");
            }
            addresses[i] = entry[0];
            lengths[i] = entry[1];
            sources[i] = entry[2];
        }
        return file.redirected(addresses, lengths, sources);
    }

    private static String describe(long address) {
        return address == HdfBuffer.UNDEFINED_ADDRESS ? "an undefined address" : "address " + address;
    }
}
