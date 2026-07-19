package com.ebremer.falcon.hdf5.layout;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Parser for the Data Layout message (type 8), spec section IV.A.2.
 *
 * <p>Versions 3 and 4 share the compact ({@code size(2)+data}) and contiguous ({@code address(O)+
 * size(L)}) encodings. Chunked storage differs: version 3 stores a B-tree address and chunk
 * dimensions directly; version 4's chunk index types are read in stage H5 (they accompany new-style
 * files). Versions 1 and 2 are handled for compact/contiguous.
 */
public final class DataLayoutMessage {

    private DataLayoutMessage() {
    }

    public static DataLayout parse(FileContext ctx, long off) {
        HdfBuffer buf = ctx.buffer();
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        int version = buf.getUnsignedByte(off);

        switch (version) {
            case 1:
            case 2: {
                int dimensionality = buf.getUnsignedByte(off + 1);
                int layoutClass = buf.getUnsignedByte(off + 2);
                long p = off + 8; // version, dimensionality, class, reserved(5)
                return switch (layoutClass) {
                    case 0 -> {
                        long dp = p + 4L * dimensionality;
                        int size = (int) buf.getUnsignedInt(dp);
                        yield new DataLayout.Compact(buf.getBytes(dp + 4, size));
                    }
                    case 1 -> new DataLayout.Contiguous(buf.getAddress(p, offsets), -1);
                    default -> throw new HdfUnsupportedException(
                            "chunked data layout message version " + version + " is not yet supported");
                };
            }
            case 3: {
                int layoutClass = buf.getUnsignedByte(off + 1);
                long p = off + 2;
                return switch (layoutClass) {
                    case 0 -> {
                        int size = buf.getUnsignedShort(p);
                        yield new DataLayout.Compact(buf.getBytes(p + 2, size));
                    }
                    case 1 -> new DataLayout.Contiguous(
                            buf.getAddress(p, offsets), buf.getUnsignedValue(p + offsets, lengths));
                    case 2 -> parseChunkedV3(ctx, off);
                    default -> throw new HdfFormatException("unknown data layout class " + layoutClass + " at " + off);
                };
            }
            case 4:
            case 5: {
                int layoutClass = buf.getUnsignedByte(off + 1);
                long p = off + 2;
                return switch (layoutClass) {
                    case 0 -> {
                        int size = buf.getUnsignedShort(p);
                        yield new DataLayout.Compact(buf.getBytes(p + 2, size));
                    }
                    case 1 -> new DataLayout.Contiguous(
                            buf.getAddress(p, offsets), buf.getUnsignedValue(p + offsets, lengths));
                    case 2 -> parseChunkedV4(ctx, off);
                    case 3 -> new DataLayout.Virtual(
                            buf.getAddress(p, offsets), (int) buf.getUnsignedInt(p + offsets));
                    default -> throw new HdfFormatException("unknown data layout class " + layoutClass + " at " + off);
                };
            }
            default:
                throw new HdfFormatException("unsupported data layout message version " + version);
        }
    }

    private static DataLayout parseChunkedV3(FileContext ctx, long off) {
        HdfBuffer buf = ctx.buffer();
        int offsets = ctx.sizeOfOffsets();
        int dimensionality = buf.getUnsignedByte(off + 2); // rank + 1 (last is element size)
        long indexAddress = buf.getAddress(off + 3, offsets);
        long p = off + 3 + offsets;
        int rank = dimensionality - 1;
        int[] chunkDimensions = new int[rank];
        for (int i = 0; i < rank; i++) {
            chunkDimensions[i] = (int) buf.getUnsignedInt(p);
            p += 4;
        }
        int elementSize = (int) buf.getUnsignedInt(p);
        return new DataLayout.Chunked(DataLayout.INDEX_V1_BTREE, indexAddress, chunkDimensions, elementSize);
    }

    /**
     * Version-4/5 chunked layout: {@code flags(1) · dimensionality(1) · dim-size-encoded-length(1) ·
     * chunk dimensions (last is the element size) · index type(1) · index-specific fields}. The
     * chunk index address is extracted per index type.
     */
    private static DataLayout parseChunkedV4(FileContext ctx, long off) {
        HdfBuffer buf = ctx.buffer();
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        int flags = buf.getUnsignedByte(off + 2);
        int dimensionality = buf.getUnsignedByte(off + 3);
        int encodedLength = buf.getUnsignedByte(off + 4);
        long p = off + 5;
        int rank = dimensionality - 1;
        int[] chunkDimensions = new int[rank];
        for (int i = 0; i < rank; i++) {
            chunkDimensions[i] = (int) buf.getUnsignedValue(p, encodedLength);
            p += encodedLength;
        }
        int elementSize = (int) buf.getUnsignedValue(p, encodedLength);
        p += encodedLength;

        int indexType = buf.getUnsignedByte(p);
        p += 1;
        long indexAddress = switch (indexType) {
            case DataLayout.INDEX_SINGLE_CHUNK -> {
                if ((flags & 0x02) != 0) {
                    p += lengths + 4; // filtered single chunk: size + filter mask
                }
                yield buf.getAddress(p, offsets);
            }
            case DataLayout.INDEX_IMPLICIT -> buf.getAddress(p, offsets);
            case DataLayout.INDEX_FIXED_ARRAY -> buf.getAddress(p + 1, offsets); // page bits, then address
            case DataLayout.INDEX_EXTENSIBLE_ARRAY -> buf.getAddress(p + 5, offsets); // 5 param bytes, then address
            case DataLayout.INDEX_V2_BTREE -> buf.getAddress(p + 6, offsets); // node size(4)+split+merge, then address
            default -> throw new HdfFormatException("unknown chunk index type " + indexType + " at " + off);
        };
        return new DataLayout.Chunked(indexType, indexAddress, chunkDimensions, elementSize);
    }
}
