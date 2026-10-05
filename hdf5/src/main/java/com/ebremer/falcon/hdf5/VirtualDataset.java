package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles a virtual dataset (layout class 3) from its source datasets. The global-heap mapping block
 * holds, per entry, a source file name, source dataset name, and two serialized dataspace selections
 * (source and virtual). For each mapping this gathers the elements the source selection picks out and
 * scatters them into the positions the virtual selection picks out; regions no mapping covers keep the
 * fill value. Source files are resolved relative to the virtual dataset's own file. A source must have
 * the virtual dataset's datatype, possibly in the other byte order (converted here); other type
 * conversions are reported as unsupported.
 *
 * <p>Selections are resolved generally: whole-space (ALL) and regular hyperslabs, including strided and
 * multi-block patterns (each dimension's selected indices are {@code start + j·stride + b} for
 * {@code j} in [0,count) and {@code b} in [0,block)). Unlimited-extent selection patterns and point
 * selections are a later increment.
 */
final class VirtualDataset {

    private static final int SEL_NONE = 0;
    private static final int SEL_HYPERSLAB = 2;
    private static final int SEL_ALL = 3;

    private VirtualDataset() {
    }

    /** Source datasets may themselves be virtual; this bounds that nesting (a VDS can name itself). */
    private static final int MAX_NESTING = 32;
    private static final ThreadLocal<int[]> NESTING = ThreadLocal.withInitial(() -> new int[1]);

    static byte[] assemble(FileContext ctx, DataLayout.Virtual layout, long[] virtualDims,
                           Datatype type, byte[] fill) {
        int[] nesting = NESTING.get();
        if (nesting[0] >= MAX_NESTING) {
            throw new HdfFormatException("virtual dataset sources nest more than " + MAX_NESTING
                    + " levels deep (a virtual dataset that maps itself?)");
        }
        nesting[0]++;
        try {
            return assembleSources(ctx, layout, virtualDims, type, fill);
        } finally {
            nesting[0]--;
        }
    }

    private static byte[] assembleSources(FileContext ctx, DataLayout.Virtual layout, long[] virtualDims,
                                          Datatype type, byte[] fill) {
        if (containsHeapData(type)) {
            // Variable-length and reference elements point into their own file's heaps and objects.
            throw new HdfUnsupportedException("virtual datasets of variable-length or reference data are not supported");
        }
        int elementSize = type.size();
        long elements = 1;
        for (long d : virtualDims) {
            elements *= d;
        }
        byte[] output = new byte[com.ebremer.falcon.hdf5.data.Elements.checkedByteCount(elements, elementSize)];
        tileFill(output, fill, elementSize);

        byte[] block = GlobalHeap.readObject(ctx, layout.globalHeapAddress(), layout.index());
        HdfBuffer buf = HdfBuffer.of(block);
        int lengths = ctx.sizeOfLengths();
        long entries = buf.getUnsignedValue(1, lengths); // version(1), then entry count
        int p = 1 + lengths;

        Path directory = ctx.path() == null ? null : ctx.path().getParent();
        Map<Path, Hdf5File> sources = new HashMap<>();
        try {
            for (long e = 0; e < entries; e++) {
                int fileEnd = zeroFrom(block, p);
                String sourceFile = new String(block, p, fileEnd - p, StandardCharsets.UTF_8);
                p = fileEnd + 1;
                int datasetEnd = zeroFrom(block, p);
                String sourceDataset = new String(block, p, datasetEnd - p, StandardCharsets.UTF_8);
                p = datasetEnd + 1;
                VdsSelection sourceSelection = readSelection(buf, p);
                p += sourceSelection.byteLength;
                VdsSelection virtualSelection = readSelection(buf, p);
                p += virtualSelection.byteLength;

                if (sourceSelection.type == SEL_NONE || virtualSelection.type == SEL_NONE) {
                    continue;
                }
                Path sourcePath = directory == null ? Path.of(sourceFile) : directory.resolve(sourceFile);
                Hdf5File source = sources.computeIfAbsent(sourcePath, VirtualDataset::openOrNull);
                if (source == null) {
                    continue; // missing source file -> leave the fill value in place
                }
                Dataset sourceDataset2 = navigate(source.root(), sourceDataset);
                long[] sourceDims = sourceDataset2.dataspace().dimensions();
                boolean swap = byteSwapNeeded(sourceDataset2.datatype(), type, sourceDataset);

                long[] sourceOffsets = sourceSelection.selectedOffsets(sourceDims);
                long[] virtualOffsets = virtualSelection.selectedOffsets(virtualDims);
                byte[] sourceBytes = sourceDataset2.rawData().toArray(ValueLayout.JAVA_BYTE);
                int n = Math.min(sourceOffsets.length, virtualOffsets.length);
                for (int i = 0; i < n; i++) {
                    int from = (int) (sourceOffsets[i] * elementSize);
                    int to = (int) (virtualOffsets[i] * elementSize);
                    if (swap) {
                        for (int b = 0; b < elementSize; b++) {
                            output[to + b] = sourceBytes[from + elementSize - 1 - b];
                        }
                    } else {
                        System.arraycopy(sourceBytes, from, output, to, elementSize);
                    }
                }
            }
        } finally {
            for (Hdf5File file : sources.values()) {
                if (file != null) {
                    file.close();
                }
            }
        }
        return output;
    }

    /** A parsed source/virtual selection: whole-space (ALL) or a regular (possibly strided) hyperslab. */
    private static final class VdsSelection {
        final int type;
        final long[] start;
        final long[] stride;
        final long[] count;
        final long[] block;
        final int byteLength;

        VdsSelection(int type, long[] start, long[] stride, long[] count, long[] block, int byteLength) {
            this.type = type;
            this.start = start;
            this.stride = stride;
            this.count = count;
            this.block = block;
            this.byteLength = byteLength;
        }

        /** Flat row-major offsets of the selected elements over a space of shape {@code dims}. */
        long[] selectedOffsets(long[] dims) {
            int rank = dims.length;
            long[][] indices = new long[rank][];
            for (int d = 0; d < rank; d++) {
                if (type == SEL_ALL) {
                    indices[d] = new long[com.ebremer.falcon.hdf5.data.Elements.checkedInt(dims[d])];
                    for (int i = 0; i < indices[d].length; i++) {
                        indices[d][i] = i;
                    }
                } else {
                    indices[d] = new long[com.ebremer.falcon.hdf5.data.Elements.checkedInt(count[d] * block[d])];
                    int k = 0;
                    for (long j = 0; j < count[d]; j++) {
                        for (long b = 0; b < block[d]; b++) {
                            indices[d][k++] = start[d] + j * stride[d] + b;
                        }
                    }
                }
            }
            long[] dimStride = new long[rank];
            long s = 1;
            for (int d = rank - 1; d >= 0; d--) {
                dimStride[d] = s;
                s *= dims[d];
            }
            long total = 1;
            for (long[] index : indices) {
                total *= index.length;
            }
            long[] offsets = new long[com.ebremer.falcon.hdf5.data.Elements.checkedInt(total)];
            int[] cursor = new int[rank];
            for (int i = 0; i < offsets.length; i++) {
                long flat = 0;
                for (int d = 0; d < rank; d++) {
                    flat += indices[d][cursor[d]] * dimStride[d];
                }
                offsets[i] = flat;
                for (int d = rank - 1; d >= 0; d--) {
                    if (++cursor[d] < indices[d].length) {
                        break;
                    }
                    cursor[d] = 0;
                }
            }
            return offsets;
        }
    }

    private static VdsSelection readSelection(HdfBuffer buf, int offset) {
        int type = (int) buf.getUnsignedInt(offset);
        if (type == SEL_ALL || type == SEL_NONE) {
            // type(4), version(4), padding(4), length(4)
            return new VdsSelection(type, null, null, null, null, 16);
        }
        if (type != SEL_HYPERSLAB) {
            throw new HdfUnsupportedException("virtual dataset selection type " + type
                    + " (only hyperslab and all-points selections are supported)");
        }
        int version = (int) buf.getUnsignedInt(offset + 4);
        if (version != 3) {
            throw new HdfUnsupportedException("virtual dataset hyperslab selection version " + version
                    + " is not yet supported");
        }
        int encodeSize = buf.getUnsignedByte(offset + 9); // version(4), flags(1), encode size(1)
        int rank = (int) buf.getUnsignedInt(offset + 10);
        long unlimited = encodeSize >= 8 ? -1L : (1L << (8 * encodeSize)) - 1;
        int q = offset + 14;
        long[] start = new long[rank];
        long[] stride = new long[rank];
        long[] count = new long[rank];
        long[] block = new long[rank];
        for (int d = 0; d < rank; d++) {
            start[d] = buf.getUnsignedValue(q, encodeSize);
            stride[d] = buf.getUnsignedValue(q + encodeSize, encodeSize);
            count[d] = buf.getUnsignedValue(q + 2L * encodeSize, encodeSize);
            block[d] = buf.getUnsignedValue(q + 3L * encodeSize, encodeSize);
            if (count[d] == unlimited || block[d] == unlimited) {
                throw new HdfUnsupportedException("unlimited-extent virtual dataset selections are not yet supported");
            }
            q += 4L * encodeSize; // start, stride, count, block
        }
        return new VdsSelection(SEL_HYPERSLAB, start, stride, count, block, 14 + rank * 4 * encodeSize);
    }

    /**
     * Whether a source element must be byte-reversed to match the virtual dataset's datatype: false if
     * the types are the same, true if they are the same atomic type in the other byte order. libhdf5
     * converts any other difference (size, sign, class); Falcon reports it instead of copying the source
     * bytes as if they were the virtual type.
     */
    private static boolean byteSwapNeeded(Datatype source, Datatype target, String sourceName) {
        if (sameType(source, target)) {
            return false;
        }
        Datatype normalized = withOrder(source, ByteOrder.LITTLE_ENDIAN);
        if (normalized != null && source.size() > 1 && normalized.equals(withOrder(target, ByteOrder.LITTLE_ENDIAN))) {
            return true;
        }
        throw new HdfUnsupportedException("virtual dataset source " + sourceName + " has datatype " + source
                + ", the virtual dataset " + target + " (type conversion is not supported)");
    }

    /** Structural datatype equality (array dimensions compared by value, nested types recursively). */
    static boolean sameType(Datatype a, Datatype b) {
        return switch (a) {
            case Datatype.Array x when b instanceof Datatype.Array y -> x.size() == y.size()
                    && Arrays.equals(x.dimensions(), y.dimensions()) && sameType(x.base(), y.base());
            case Datatype.Compound x when b instanceof Datatype.Compound y -> x.size() == y.size()
                    && x.members().size() == y.members().size() && sameMembers(x.members(), y.members());
            case Datatype.Enumeration x when b instanceof Datatype.Enumeration y -> x.size() == y.size()
                    && x.members().equals(y.members()) && sameType(x.base(), y.base());
            case Datatype.VariableLength x when b instanceof Datatype.VariableLength y -> x.size() == y.size()
                    && x.kind() == y.kind() && x.padding() == y.padding() && x.characterSet() == y.characterSet()
                    && sameType(x.base(), y.base());
            case Datatype.Complex x when b instanceof Datatype.Complex y -> x.size() == y.size() && sameType(x.base(), y.base());
            default -> a.equals(b);
        };
    }

    private static boolean sameMembers(List<Datatype.Compound.Member> a, List<Datatype.Compound.Member> b) {
        for (int i = 0; i < a.size(); i++) {
            Datatype.Compound.Member x = a.get(i);
            Datatype.Compound.Member y = b.get(i);
            if (!x.name().equals(y.name()) || x.offset() != y.offset() || !sameType(x.type(), y.type())) {
                return false;
            }
        }
        return true;
    }

    /** {@code type} in byte order {@code order} if it is an ordered atomic type, else null. */
    private static Datatype withOrder(Datatype type, ByteOrder order) {
        return switch (type) {
            case Datatype.FixedPoint t -> new Datatype.FixedPoint(t.size(), order, t.signed(), t.bitOffset(), t.bitPrecision());
            case Datatype.FloatingPoint t -> new Datatype.FloatingPoint(t.size(), order, t.bitOffset(), t.bitPrecision(),
                    t.exponentLocation(), t.exponentSize(), t.mantissaLocation(), t.mantissaSize(), t.exponentBias(),
                    t.signLocation(), t.normalization());
            case Datatype.BitField t -> new Datatype.BitField(t.size(), order, t.bitOffset(), t.bitPrecision());
            case Datatype.Time t -> new Datatype.Time(t.size(), order, t.bitPrecision());
            default -> null;
        };
    }

    /** True if elements of {@code type} hold global-heap IDs or object addresses. */
    private static boolean containsHeapData(Datatype type) {
        return switch (type) {
            case Datatype.VariableLength v -> true;
            case Datatype.Reference r -> true;
            case Datatype.Array a -> containsHeapData(a.base());
            case Datatype.Compound c -> c.members().stream().anyMatch(m -> containsHeapData(m.type()));
            default -> false;
        };
    }

    private static Dataset navigate(Group root, String path) {
        Hdf5Object current = root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!(current instanceof Group group)) {
                throw new HdfFormatException("virtual dataset source path traverses a non-group: " + path);
            }
            current = group.child(part).orElseThrow(
                    () -> new HdfFormatException("virtual dataset source not found: " + path));
        }
        if (!(current instanceof Dataset dataset)) {
            throw new HdfFormatException("virtual dataset source is not a dataset: " + path);
        }
        return dataset;
    }

    private static Hdf5File openOrNull(Path path) {
        try {
            return Hdf5File.open(path);
        } catch (IOException e) {
            return null; // an unavailable source contributes only the fill value
        }
    }

    private static int zeroFrom(byte[] data, int from) {
        int i = from;
        while (i < data.length && data[i] != 0) {
            i++;
        }
        return i;
    }

    private static void tileFill(byte[] output, byte[] fill, int elementSize) {
        if (fill == null || fill.length == 0) {
            return;
        }
        for (int off = 0; off + elementSize <= output.length; off += elementSize) {
            System.arraycopy(fill, 0, output, off, Math.min(elementSize, fill.length));
        }
    }
}
