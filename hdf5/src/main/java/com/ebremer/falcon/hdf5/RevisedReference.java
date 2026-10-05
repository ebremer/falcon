package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.DataspaceSelection;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;

/**
 * One revised reference ({@code H5R_ref_t}, HDF5 1.12+: reference types 2&ndash;4 of datatype version 4)
 * as libhdf5 stores it in a dataset or attribute ({@code H5T__ref_disk_*}). An element is
 * {@code type(1) · flags(1)}, then either the encoded reference itself, for an object reference into the
 * same file, or {@code size(4) · global heap ID} of the heap object that holds it. The encoded reference
 * ({@code H5R__encode}, after its type and flags) is {@code token size(1) · token} (the object's header
 * address), a file name ({@code length(2) · bytes}) if flag 0x01 marks the reference external, and then
 * for a region {@code selection size(4) · rank(4) · serialized selection}, or for an attribute
 * {@code name length(2) · name}. An element with no type and a nil heap ID is a null reference.
 *
 * <p>libhdf5 writes the datatype of every revised reference ({@code H5T_STD_REF}) as an object reference,
 * so each element's own type says what it holds.
 *
 * @param type          {@link #OBJECT}, {@link #REGION}, or {@link #ATTRIBUTE}
 * @param address       the header address of the object pointed at (or into), in its file
 * @param tokenSize     the bytes the address takes: its file's size of offsets
 * @param externalFile  the file the object is in, or null for this file
 * @param region        a region reference's selection, else null
 * @param rank          a region reference's rank, else -1
 * @param attributeName an attribute reference's attribute name, else null
 */
record RevisedReference(int type, long address, int tokenSize, String externalFile, DataspaceSelection region,
                        int rank, String attributeName) {

    static final int OBJECT = 2;    // H5R_OBJECT2
    static final int REGION = 3;    // H5R_DATASET_REGION2
    static final int ATTRIBUTE = 4; // H5R_ATTR

    private static final int EXTERNAL = 0x01; // H5R_IS_EXTERNAL

    /** Decodes the element at {@code base} of {@code data}; null for a null reference. */
    static RevisedReference decode(FileContext ctx, HdfBuffer data, long base, int elementSize) {
        int offsets = ctx.sizeOfOffsets();
        if (elementSize < 2 + 4 + offsets + 4) {
            throw new HdfFormatException("revised reference elements of " + elementSize + " bytes are too small");
        }
        int type = data.getUnsignedByte(base);
        int flags = data.getUnsignedByte(base + 1);
        if (type == 0) {
            long heapAddress = data.getAddress(base + 6, offsets);
            if (heapAddress != 0 && heapAddress != HdfBuffer.UNDEFINED_ADDRESS) {
                throw new HdfFormatException("revised reference without a type at " + base);
            }
            return null;
        }
        if (type < OBJECT || type > ATTRIBUTE) {
            throw new HdfFormatException("invalid revised reference type " + type + " at " + base);
        }
        boolean external = (flags & EXTERNAL) != 0;
        HdfBuffer encoded;
        long p;
        long end;
        if (type == OBJECT && !external) {
            // libhdf5 copies a local object reference into the element directly (H5T__ref_disk_getsize).
            encoded = data;
            p = base + 2;
            end = base + elementSize;
        } else {
            long size = data.getUnsignedInt(base + 2);
            long collection = data.getAddress(base + 6, offsets);
            int index = (int) data.getUnsignedInt(base + 6 + offsets);
            byte[] blob = GlobalHeap.readObject(ctx, collection, index);
            if (size > blob.length) {
                throw new HdfFormatException("revised reference of " + size + " bytes in a heap object of " + blob.length);
            }
            encoded = HdfBuffer.of(blob);
            p = 0;
            end = size;
        }
        int tokenSize = encoded.getUnsignedByte(require(p, 1, end));
        require(p + 1, tokenSize, end);
        if (!external && tokenSize != offsets) {
            throw new HdfFormatException("revised reference token of " + tokenSize + " bytes in a file whose"
                    + " addresses are " + offsets + " bytes");
        }
        if (tokenSize < 1 || tokenSize > 8) {
            throw new HdfFormatException("revised reference token of " + tokenSize + " bytes");
        }
        long address = encoded.getAddress(p + 1, tokenSize); // in the other file, for an external reference
        p += 1 + tokenSize;
        String file = null;
        if (external) {
            int length = encoded.getUnsignedShort(require(p, 2, end));
            file = string(encoded, require(p + 2, length, end), length);
            p += 2 + length;
        }
        return switch (type) {
            case REGION -> {
                long selectionSize = encoded.getUnsignedInt(require(p, 8, end));
                long rank = encoded.getUnsignedInt(p + 4);
                require(p + 8, selectionSize, end);
                DataspaceSelection selection = DataspaceSelection.parse(encoded.slice(p + 8, selectionSize), 0);
                if (rank < 1 || rank > 32) {
                    throw new HdfFormatException("invalid region reference rank " + rank);
                }
                yield new RevisedReference(type, address, tokenSize, file, selection, (int) rank, null);
            }
            case ATTRIBUTE -> {
                int length = encoded.getUnsignedShort(require(p, 2, end));
                String name = string(encoded, require(p + 2, length, end), length);
                yield new RevisedReference(type, address, tokenSize, file, null, -1, name);
            }
            default -> new RevisedReference(type, address, tokenSize, file, null, -1, null);
        };
    }

    /** What this reference is, for messages: "an object reference", and so on. */
    String kind() {
        return switch (type) {
            case REGION -> "a region reference";
            case ATTRIBUTE -> "an attribute reference";
            default -> "an object reference";
        };
    }

    /**
     * The file this reference points into: {@code ctx}'s own, or the other file it names, found and opened
     * as the file's {@link ExternalFileAccess} policy says and kept open until that file closes.
     *
     * @throws HdfUnsupportedException if the policy refuses the other file
     * @throws HdfException if the other file is not found
     * @throws HdfFormatException if the reference's address is not as wide as that file's addresses
     */
    FileContext file(FileContext ctx) {
        if (externalFile == null) {
            return ctx;
        }
        FileContext other = SourceFiles.find(ctx, externalFile, ExternalFileAccess.Purpose.REFERENCE);
        if (other == null) {
            throw new HdfException(kind() + " points into " + externalFile + ", which is not found");
        }
        if (other.sizeOfOffsets() != tokenSize) {
            throw new HdfFormatException(kind() + " into " + externalFile + " holds a " + tokenSize
                    + "-byte address, but that file's addresses are " + other.sizeOfOffsets() + " bytes");
        }
        return other;
    }

    private static long require(long p, long length, long end) {
        if (length < 0 || p + length > end) {
            throw new HdfFormatException("revised reference overruns its " + end + " bytes");
        }
        return p;
    }

    private static String string(HdfBuffer buffer, long p, int length) {
        return new String(buffer.getBytes(p, length), StandardCharsets.UTF_8);
    }
}
