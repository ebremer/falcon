package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.DataspaceSelection;
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
import java.util.ArrayList;
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
 * <p>The mapping block is read in both versions libhdf5 writes: version 0 (each entry's file name, with
 * {@code "."} for this same file) and version 1 (HDF5 2.0: per-entry flags mark a same-file source, or a
 * file or dataset name shared with an earlier entry by index). Selections are read in every encoding
 * (see {@link DataspaceSelection}); unlimited-extent mappings are a later increment. Source files are
 * opened only as the file's {@link ExternalFileAccess} policy allows; a refused one fails the read.
 */
final class VirtualDataset {

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
        int version = buf.getUnsignedByte(0);
        if (version > 1) {
            throw new HdfUnsupportedException("virtual dataset mapping block version " + version);
        }
        long entries = buf.getUnsignedValue(1, lengths); // version(1), then entry count
        if (entries < 0 || entries > block.length) {
            throw new HdfFormatException("virtual dataset mapping count " + entries + " is invalid");
        }
        int p = 1 + lengths;

        Path directory = ctx.path() == null ? null : ctx.path().getParent();
        Map<Path, Hdf5File> sources = new HashMap<>();
        List<String> fileNames = new ArrayList<>();
        List<String> datasetNames = new ArrayList<>();
        try {
            for (int e = 0; e < entries; e++) {
                // Version 1 prefixes each entry with flags: the source is this file (no name stored), or its
                // file / dataset name is an earlier entry's (stored as that entry's index).
                int flags = version >= 1 ? buf.getUnsignedByte(p++) : 0;
                String sourceFile;
                if ((flags & SAME_FILE) != 0) {
                    sourceFile = ".";
                } else if ((flags & SHARED_FILE_NAME) != 0) {
                    sourceFile = earlier(fileNames, buf.getUnsignedValue(p, lengths), e);
                    p += lengths;
                } else {
                    int fileEnd = zeroFrom(block, p);
                    sourceFile = new String(block, p, fileEnd - p, StandardCharsets.UTF_8);
                    p = fileEnd + 1;
                }
                String sourceDataset;
                if ((flags & SHARED_DATASET_NAME) != 0) {
                    sourceDataset = earlier(datasetNames, buf.getUnsignedValue(p, lengths), e);
                    p += lengths;
                } else {
                    int datasetEnd = zeroFrom(block, p);
                    sourceDataset = new String(block, p, datasetEnd - p, StandardCharsets.UTF_8);
                    p = datasetEnd + 1;
                }
                fileNames.add(sourceFile);
                datasetNames.add(sourceDataset);
                DataspaceSelection sourceSelection = DataspaceSelection.parse(buf, p);
                p += sourceSelection.byteLength();
                DataspaceSelection virtualSelection = DataspaceSelection.parse(buf, p);
                p += virtualSelection.byteLength();

                if (sourceSelection.type() == DataspaceSelection.NONE || virtualSelection.type() == DataspaceSelection.NONE) {
                    continue;
                }
                if (sourceSelection.isUnlimited() || virtualSelection.isUnlimited()) {
                    throw new HdfUnsupportedException("unlimited-extent virtual dataset selections are not yet supported");
                }
                Group root;
                if (sourceFile.equals(".")) {
                    root = Group.root(ctx, ctx.rootAddress()); // the source is in this same file
                } else {
                    Path sourcePath = ctx.externalFileAccess().resolve(sourceFile, directory, "virtual dataset source file");
                    Hdf5File source = sources.computeIfAbsent(sourcePath,
                            path -> openOrNull(path, ctx.externalFileAccess()));
                    if (source == null) {
                        continue; // a missing source file leaves the fill value in place, as in libhdf5
                    }
                    root = source.root();
                }
                Dataset sourceDataset2 = navigate(root, sourceDataset);
                long[] sourceDims = sourceDataset2.dataspace().dimensions();
                boolean swap = byteSwapNeeded(sourceDataset2.datatype(), type, sourceDataset);

                long[] sourceOffsets = sourceSelection.offsets(sourceDims);
                long[] virtualOffsets = virtualSelection.offsets(virtualDims);
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

    // Mapping-entry flags of a version-1 mapping block (HDF5 2.0).
    private static final int SHARED_FILE_NAME = 0x01;
    private static final int SHARED_DATASET_NAME = 0x02;
    private static final int SAME_FILE = 0x04;

    /** The name an entry shares with the earlier entry {@code index}. */
    private static String earlier(List<String> names, long index, int entry) {
        if (index < 0 || index >= entry) {
            throw new HdfFormatException("virtual dataset mapping " + entry + " refers to mapping " + index);
        }
        return names.get((int) index);
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

    private static Hdf5File openOrNull(Path path, ExternalFileAccess access) {
        try {
            return Hdf5File.open(path, access);
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
