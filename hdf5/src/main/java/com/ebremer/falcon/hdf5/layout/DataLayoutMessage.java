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
        int rank = checkedRank(dimensionality, off);
        int[] chunkDimensions = new int[rank];
        for (int i = 0; i < rank; i++) {
            chunkDimensions[i] = checkedDimension(buf.getUnsignedInt(p), off);
            p += 4;
        }
        int elementSize = checkedDimension(buf.getUnsignedInt(p), off);
        return new DataLayout.Chunked(DataLayout.INDEX_V1_BTREE, indexAddress, chunkDimensions, elementSize);
    }

    /**
     * Version-4/5 chunked layout: {@code flags(1) · dimensionality(1) · dim-size-encoded-length(1) ·
     * chunk dimensions (last is the element size) · index type(1) · index-specific fields}. The
     * chunk index address is extracted per index type. A filtered single-chunk index (flag bit 1)
     * stores the chunk's filtered size ("size of lengths") and filter mask (4) before its address.
     */
    private static DataLayout parseChunkedV4(FileContext ctx, long off) {
        HdfBuffer buf = ctx.buffer();
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        int flags = buf.getUnsignedByte(off + 2);
        int dimensionality = buf.getUnsignedByte(off + 3);
        int encodedLength = buf.getUnsignedByte(off + 4);
        if (encodedLength < 1 || encodedLength > 8) {
            throw new HdfFormatException("invalid chunk dimension encoded length " + encodedLength + " at " + off);
        }
        long p = off + 5;
        int rank = checkedRank(dimensionality, off);
        int[] chunkDimensions = new int[rank];
        for (int i = 0; i < rank; i++) {
            chunkDimensions[i] = checkedDimension(buf.getUnsignedValue(p, encodedLength), off);
            p += encodedLength;
        }
        int elementSize = checkedDimension(buf.getUnsignedValue(p, encodedLength), off);
        p += encodedLength;

        int indexType = buf.getUnsignedByte(p);
        p += 1;
        long singleChunkSize = -1;
        int singleChunkFilterMask = 0;
        long indexAddress = switch (indexType) {
            case DataLayout.INDEX_SINGLE_CHUNK -> {
                if ((flags & DataLayout.FLAG_SINGLE_INDEX_WITH_FILTER) != 0) {
                    singleChunkSize = buf.getUnsignedValue(p, lengths);
                    singleChunkFilterMask = buf.getInt(p + lengths);
                    p += lengths + 4;
                }
                yield buf.getAddress(p, offsets);
            }
            case DataLayout.INDEX_IMPLICIT -> buf.getAddress(p, offsets);
            case DataLayout.INDEX_FIXED_ARRAY -> buf.getAddress(p + 1, offsets); // page bits, then address
            case DataLayout.INDEX_EXTENSIBLE_ARRAY -> buf.getAddress(p + 5, offsets); // 5 param bytes, then address
            case DataLayout.INDEX_V2_BTREE -> buf.getAddress(p + 6, offsets); // node size(4)+split+merge, then address
            default -> throw new HdfFormatException("unknown chunk index type " + indexType + " at " + off);
        };
        return new DataLayout.Chunked(indexType, indexAddress, chunkDimensions, elementSize,
                flags, singleChunkSize, singleChunkFilterMask);
    }

    /** The chunk rank from a stored dimensionality (rank + 1): HDF5 allows 1..32 dimensions. */
    private static int checkedRank(int dimensionality, long off) {
        if (dimensionality < 2 || dimensionality > 33) {
            throw new HdfFormatException("invalid chunked layout dimensionality " + dimensionality + " at " + off);
        }
        return dimensionality - 1;
    }

    /** A chunk dimension (or element size): positive and small enough for a Java array index. */
    private static int checkedDimension(long value, long off) {
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new HdfFormatException("invalid chunk dimension " + value + " in layout at " + off);
        }
        return (int) value;
    }
}
