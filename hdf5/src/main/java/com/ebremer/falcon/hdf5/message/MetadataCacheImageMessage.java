package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Metadata Cache Image message (type 24, spec section IV.A.3.y): in the superblock extension of a file
 * closed with a metadata cache image, the address and size of the image block (see
 * {@code MetadataCacheImage}).
 *
 * <p>Body: {@code version(1)=0 · image block address(O) · image block size(L)}.
 *
 * @param address the image block's address
 * @param size    the image block's size in bytes
 */
public record MetadataCacheImageMessage(long address, long size) {

    /** Parses the message's body, with the superblock's size of offsets and size of lengths. */
    public static MetadataCacheImageMessage parse(HdfBuffer body, int sizeOfOffsets, int sizeOfLengths) {
        if (body.size() < 1 + sizeOfOffsets + sizeOfLengths || body.getUnsignedByte(0) != 0) {
            throw new HdfFormatException("unsupported metadata cache image message");
        }
        return new MetadataCacheImageMessage(body.getAddress(1, sizeOfOffsets),
                body.getUnsignedValue(1 + sizeOfOffsets, sizeOfLengths));
    }
}
