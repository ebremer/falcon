package com.ebremer.falcon.hdf5.heap;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Global heap (spec section III.E): shared storage for variable-length data, referenced by a
 * "global heap ID" (a collection address plus an object index).
 *
 * <p>A collection is {@code "GCOL"} (4) · version (1) · reserved (3) · collection size (L), followed
 * by objects, each {@code index(2) · reference count(2) · reserved(4) · size(L) · data} (data padded
 * to a multiple of 8). Object index 0 marks free space.
 */
public final class GlobalHeap {

    private static final byte[] GCOL = {'G', 'C', 'O', 'L'};

    private GlobalHeap() {
    }

    /** Reads the bytes of global-heap object {@code index} within the collection at {@code address}. */
    public static byte[] readObject(FileContext ctx, long address, int index) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(address, GCOL)) {
            throw new HdfFormatException("expected global heap signature 'GCOL' at " + address);
        }
        int lengths = ctx.sizeOfLengths();
        long collectionSize = buf.getUnsignedValue(address + 8, lengths);
        if (collectionSize < 8 + lengths || collectionSize > buf.size() - address) {
            throw new HdfFormatException("invalid global heap collection size " + collectionSize + " at " + address);
        }
        long p = address + 8 + lengths;
        long end = address + collectionSize;
        while (p + 8 + lengths <= end) {
            int objectIndex = buf.getUnsignedShort(p);
            long objectSize = buf.getUnsignedValue(p + 8, lengths);
            if (objectIndex == 0) {
                break; // free space
            }
            long data = p + 8 + lengths;
            if (objectSize < 0 || objectSize > end - data) {
                throw new HdfFormatException("global heap object " + objectIndex + " at " + p
                        + " has invalid size " + objectSize);
            }
            if (objectIndex == index) {
                return buf.getBytes(data, (int) objectSize);
            }
            p = data + ((objectSize + 7) & ~7L);
        }
        throw new HdfFormatException("global heap object " + index + " not found at " + address);
    }
}
