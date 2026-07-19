package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.lang.foreign.MemorySegment;

/**
 * Decodes variable-length <em>sequence</em> data (ragged arrays): a class-9 datatype whose kind is
 * {@code SEQUENCE}. Each element is a "global heap ID" — {@code length(4) · collection address(O) ·
 * object index(4)} — where {@code length} is the number of base-type elements in that row; the base
 * elements live contiguously in the referenced global-heap object. An empty row has length 0 and no
 * heap reference.
 *
 * <p>Each row is decoded through {@link Elements} against the sequence's base datatype, so byte order
 * and precision are honoured exactly as for a regular dataset of the base type.
 */
public final class VlenSequences {

    private VlenSequences() {
    }

    /** Each row as an {@code int[]} (fixed-point base type up to 4 bytes). */
    public static int[][] toInts(FileContext ctx, MemorySegment data, int count, Datatype.VariableLength vlen) {
        Datatype base = vlen.base();
        int[][] out = new int[count][];
        for (int i = 0; i < count; i++) {
            byte[] row = rawRow(ctx, data, i, vlen);
            out[i] = Elements.toInts(MemorySegment.ofArray(row), row.length / base.size(), base);
        }
        return out;
    }

    /** Each row as a {@code long[]} (fixed-point base type up to 8 bytes). */
    public static long[][] toLongs(FileContext ctx, MemorySegment data, int count, Datatype.VariableLength vlen) {
        Datatype base = vlen.base();
        long[][] out = new long[count][];
        for (int i = 0; i < count; i++) {
            byte[] row = rawRow(ctx, data, i, vlen);
            out[i] = Elements.toLongs(MemorySegment.ofArray(row), row.length / base.size(), base);
        }
        return out;
    }

    /** Each row as a {@code double[]} (floating-point base type). */
    public static double[][] toDoubles(FileContext ctx, MemorySegment data, int count, Datatype.VariableLength vlen) {
        Datatype base = vlen.base();
        double[][] out = new double[count][];
        for (int i = 0; i < count; i++) {
            byte[] row = rawRow(ctx, data, i, vlen);
            out[i] = Elements.toDoubles(MemorySegment.ofArray(row), row.length / base.size(), base);
        }
        return out;
    }

    /** Each row as a {@code float[]} (floating-point base type). */
    public static float[][] toFloats(FileContext ctx, MemorySegment data, int count, Datatype.VariableLength vlen) {
        Datatype base = vlen.base();
        float[][] out = new float[count][];
        for (int i = 0; i < count; i++) {
            byte[] row = rawRow(ctx, data, i, vlen);
            out[i] = Elements.toFloats(MemorySegment.ofArray(row), row.length / base.size(), base);
        }
        return out;
    }

    /** Reads the raw global-heap bytes backing sequence element {@code i} (empty rows yield no bytes). */
    private static byte[] rawRow(FileContext ctx, MemorySegment data, int i, Datatype.VariableLength vlen) {
        HdfBuffer buf = new HdfBuffer(data);
        int offsets = ctx.sizeOfOffsets();
        long p = (long) i * vlen.size();
        long length = buf.getUnsignedInt(p);
        if (length == 0) {
            return new byte[0]; // empty sequence: collection address is undefined, do not dereference
        }
        long collection = buf.getAddress(p + 4, offsets);
        int index = (int) buf.getUnsignedInt(p + 4 + offsets);
        return GlobalHeap.readObject(ctx, collection, index);
    }
}
