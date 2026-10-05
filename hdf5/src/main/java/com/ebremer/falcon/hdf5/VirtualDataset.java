package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.DataspaceSelection;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Assembles a virtual dataset (layout class 3) from its source datasets. The global-heap mapping block
 * holds, per entry, a source file name, source dataset name, and two serialized dataspace selections
 * (source and virtual). For each mapping this gathers the elements the source selection picks out and
 * scatters them into the positions the virtual selection picks out; regions no mapping covers keep the
 * fill value. A source must have the virtual dataset's datatype, possibly in the other byte order
 * (converted here); other type conversions are reported as unsupported.
 *
 * <p>The mapping block is read in both versions libhdf5 writes: version 0 (each entry's file name, with
 * {@code "."} for this same file) and version 1 (HDF5 2.0: per-entry flags mark a same-file source, or a
 * file or dataset name shared with an earlier entry by index). Selections are read in every encoding
 * (see {@link DataspaceSelection}). In names, {@code %%} stands for {@code %}.
 *
 * <p>A mapping may be <b>unlimited</b> in one dimension, letting the virtual dataset grow with its
 * sources; libhdf5 then sets that dimension's extent from the sources when the dataset is opened
 * ({@code H5D__virtual_set_extent_unlim}), and {@link #extent} does the same:
 * <ul>
 *   <li>if both selections are unlimited, the source selection is clipped to the source's current extent
 *       and the virtual one to the extent that selects as many indices;</li>
 *   <li>a <b>printf-style</b> mapping names its sources with {@code %b}, replaced by 0, 1, 2, &hellip;:
 *       source <i>b</i> fills block <i>b</i> of the unlimited virtual selection. The search stops once
 *       more sources in a row are missing than the printf gap allows ({@link OpenOptions#virtualPrintfGap});
 *       the sources found set the extent, and those skipped read as the fill value.</li>
 * </ul>
 * In the {@linkplain OpenOptions.VirtualView#LAST_AVAILABLE last available} view (the default) the extent
 * is the furthest any unlimited mapping reaches, to the end of its last block; in the
 * {@linkplain OpenOptions.VirtualView#FIRST_MISSING first missing} view it is the shortest, up to where
 * the next block would start (for a printf-style mapping, the block of its first missing source, whatever
 * the gap), and mappings that reach further are cut there.
 *
 * <p>Source files are found where libhdf5 looks for them, as the file's {@link ExternalFileAccess} policy
 * allows (see there); a refused one fails the read. A missing source file or dataset leaves the fill
 * value, as in libhdf5.
 */
final class VirtualDataset {

    private VirtualDataset() {
    }

    /** Source datasets may themselves be virtual; this bounds that nesting (a VDS can name itself). */
    private static final int MAX_NESTING = 32;
    private static final ThreadLocal<int[]> NESTING = ThreadLocal.withInitial(() -> new int[1]);

    /** Printf-style sources probed per mapping at most: a guard, far beyond any real dataset. */
    private static final long MAX_PRINTF_SOURCES = 1L << 20;

    // Mapping-entry flags of a version-1 mapping block (HDF5 2.0).
    private static final int SHARED_FILE_NAME = 0x01;
    private static final int SHARED_DATASET_NAME = 0x02;
    private static final int SAME_FILE = 0x04;

    /** One mapping entry: source names (printf patterns if they contain {@code %b}) and selections. */
    private record Mapping(String fileName, String datasetName, DataspaceSelection source,
                           DataspaceSelection virtual) {

        boolean isEmpty() {
            return source.type() == DataspaceSelection.NONE || virtual.type() == DataspaceSelection.NONE;
        }

        boolean isPrintf() {
            return hasBlockSpecifier(fileName) || hasBlockSpecifier(datasetName);
        }
    }

    /**
     * The extent of the virtual dataset whose stored extent is {@code storedDims}: the stored one, except
     * in a dimension in which a mapping is unlimited, which the sources set (the largest extent any such
     * mapping reaches, and at least what the other mappings cover there).
     */
    static long[] extent(FileContext ctx, DataLayout.Virtual layout, long[] storedDims) {
        return nested(() -> computeExtent(ctx, layout, storedDims));
    }

    static byte[] assemble(FileContext ctx, DataLayout.Virtual layout, long[] virtualDims,
                           Datatype type, byte[] fill) {
        return nested(() -> assembleSources(ctx, layout, virtualDims, type, fill));
    }

    private static <T> T nested(Supplier<T> work) {
        int[] nesting = NESTING.get();
        if (nesting[0] >= MAX_NESTING) {
            throw new HdfFormatException("virtual dataset sources nest more than " + MAX_NESTING
                    + " levels deep (a virtual dataset that maps itself?)");
        }
        nesting[0]++;
        try {
            return work.get();
        } finally {
            nesting[0]--;
        }
    }

    private static long[] computeExtent(FileContext ctx, DataLayout.Virtual layout, long[] storedDims) {
        List<Mapping> mappings = mappings(ctx, layout);
        if (mappings.stream().noneMatch(m -> !m.isEmpty() && m.virtual().isUnlimited())) {
            return storedDims;
        }
        boolean firstMissing = ctx.options().virtualView() == OpenOptions.VirtualView.FIRST_MISSING;
        int rank = storedDims.length;
        long[] unlimited = new long[rank];
        Arrays.fill(unlimited, -1); // -1: no unlimited mapping in this dimension
        long[] covered = new long[rank];
        try (Sources sources = new Sources(ctx)) {
            for (Mapping mapping : mappings) {
                if (mapping.isEmpty()) {
                    continue;
                }
                int u = mapping.virtual().unlimitedDimension();
                long[] high = mapping.virtual().highCorner();
                if (high != null) {
                    requireRank(high.length, rank);
                    for (int d = 0; d < rank; d++) {
                        if (d != u) {
                            covered[d] = Math.max(covered[d], high[d] + 1);
                        }
                    }
                }
                if (u >= 0) {
                    long reach = mapping.isPrintf() ? printfExtent(sources, mapping, firstMissing)
                            : unlimitedExtent(sources, mapping, firstMissing);
                    // "Last available" takes the furthest-reaching mapping, "first missing" the shortest.
                    unlimited[u] = unlimited[u] < 0 ? reach
                            : firstMissing ? Math.min(unlimited[u], reach) : Math.max(unlimited[u], reach);
                }
            }
        }
        long[] dims = storedDims.clone();
        for (int d = 0; d < rank; d++) {
            if (unlimited[d] >= 0) {
                dims[d] = Math.max(unlimited[d], covered[d]);
            }
        }
        return dims;
    }

    /**
     * How far an unlimited, non-printf mapping reaches: as many indices as its source holds (in the
     * "first missing" view, on to where its next block would start). A missing source reaches nowhere.
     */
    private static long unlimitedExtent(Sources sources, Mapping mapping, boolean firstMissing) {
        Dataset source = sources.find(expand(mapping.fileName(), -1), expand(mapping.datasetName(), -1), false);
        if (source == null) {
            return 0;
        }
        long[] sourceDims = source.dataspace().dimensions();
        int su = requireUnlimitedSource(mapping, sourceDims.length);
        return mapping.virtual().extentSelecting(mapping.source().selectedBelow(sourceDims[su]), firstMissing);
    }

    /**
     * How far a printf-style mapping reaches: to the end of the block of its last source found; in the
     * "first missing" view, to the start of the block of its first missing source, whatever the printf
     * gap (as libhdf5 2.0 and 1.14 read it).
     */
    private static long printfExtent(Sources sources, Mapping mapping, boolean firstMissing) {
        List<Dataset> found = printfSources(sources, mapping);
        if (found.isEmpty()) {
            return 0;
        }
        if (firstMissing) {
            int missing = found.indexOf(null);
            return mapping.virtual().blockStart(missing < 0 ? found.size() : missing);
        }
        return mapping.virtual().blockEnd(found.size() - 1);
    }

    /**
     * A printf-style mapping's sources, from block 0 up to the last one found, with null for those
     * missing: the search goes on past a missing source only while no more are missing in a row than the
     * printf gap allows (libhdf5's {@code first_missing} loop).
     */
    private static List<Dataset> printfSources(Sources sources, Mapping mapping) {
        long gap = sources.ctx.options().virtualPrintfGap();
        List<Dataset> found = new ArrayList<>();
        long next = 0; // one past the last source found
        for (long block = 0; block < MAX_PRINTF_SOURCES && block - next <= gap; block++) {
            // Past the first source, a refused name ends the search like a missing one: an absolute name
            // whose files were moved resolves by file name, and the names after the last one never do.
            Dataset source = sources.find(expand(mapping.fileName(), block), expand(mapping.datasetName(), block),
                    block > 0);
            if (source != null) {
                while (found.size() < block) {
                    found.add(null);
                }
                found.add(source);
                next = block + 1;
            }
        }
        return found;
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

        try (Sources sources = new Sources(ctx)) {
            for (Mapping mapping : mappings(ctx, layout)) {
                if (mapping.isEmpty()) {
                    continue;
                }
                DataspaceSelection virtual = mapping.virtual();
                int u = virtual.unlimitedDimension();
                if (u >= 0 && mapping.isPrintf()) {
                    requireRank(virtual.highCorner().length, virtualDims.length);
                    List<Dataset> found = printfSources(sources, mapping);
                    for (int block = 0; block < found.size() && virtual.blockStart(block) < virtualDims[u]; block++) {
                        Dataset source = found.get(block);
                        if (source != null) { // a source the printf gap skipped leaves the fill value
                            copy(output, source, mapping.source().offsets(source.dataspace().dimensions()),
                                    blockOffsetsWithin(virtual, virtualDims, u, block), type, mapping.datasetName());
                        }
                    }
                    continue;
                }
                String datasetName = expand(mapping.datasetName(), -1);
                Dataset source = sources.find(expand(mapping.fileName(), -1), datasetName, false);
                if (source == null) {
                    continue; // a missing source leaves the fill value in place, as in libhdf5
                }
                long[] sourceDims = source.dataspace().dimensions();
                long[] sourceOffsets;
                long[] virtualOffsets;
                if (u >= 0) {
                    // Clip the virtual selection to the extent (which the "first missing" view may set
                    // short of this mapping's reach), and the source selection to match it.
                    int su = requireUnlimitedSource(mapping, sourceDims.length);
                    long reach = virtual.extentSelecting(mapping.source().selectedBelow(sourceDims[su]));
                    long virtualClip = Math.min(reach, virtualDims[u]);
                    long sourceClip = Math.min(sourceDims[su],
                            mapping.source().extentSelecting(virtual.selectedBelow(virtualClip)));
                    sourceOffsets = mapping.source().clippedOffsets(sourceDims, sourceClip);
                    virtualOffsets = virtual.clippedOffsets(virtualDims, virtualClip);
                } else if (mapping.source().isUnlimited()) {
                    throw new HdfFormatException("virtual dataset mapping has an unlimited source selection"
                            + " but a limited virtual one");
                } else {
                    sourceOffsets = mapping.source().offsets(sourceDims);
                    virtualOffsets = virtual.offsets(virtualDims);
                }
                copy(output, source, sourceOffsets, virtualOffsets, type, datasetName);
            }
        }
        return output;
    }

    /**
     * The flat offsets of block {@code block} of a printf-style selection, in iteration order, with -1 for
     * elements beyond the extent: in the "first missing" view another mapping can cut a block short.
     */
    private static long[] blockOffsetsWithin(DataspaceSelection virtual, long[] dims, int u, int block) {
        long end = virtual.blockEnd(block);
        if (end <= dims[u]) {
            return virtual.blockOffsets(dims, block);
        }
        long[] whole = dims.clone();
        whole[u] = end;
        long[][] coordinates = virtual.blockCoordinates(whole, block);
        long[] offsets = new long[coordinates.length];
        for (int i = 0; i < offsets.length; i++) {
            long[] c = coordinates[i];
            long flat = -1;
            if (c[u] < dims[u]) {
                flat = 0;
                for (int d = 0; d < dims.length; d++) {
                    flat = flat * dims[d] + c[d];
                }
            }
            offsets[i] = flat;
        }
        return offsets;
    }

    /**
     * Copies the source elements at {@code sourceOffsets} to the virtual ones at {@code virtualOffsets},
     * pairing them in order; a negative virtual offset is skipped.
     */
    private static void copy(byte[] output, Dataset source, long[] sourceOffsets, long[] virtualOffsets,
                             Datatype type, String sourceName) {
        int elementSize = type.size();
        boolean swap = byteSwapNeeded(source.datatype(), type, sourceName);
        byte[] sourceBytes = source.rawData().toArray(ValueLayout.JAVA_BYTE);
        int n = Math.min(sourceOffsets.length, virtualOffsets.length);
        for (int i = 0; i < n; i++) {
            if (virtualOffsets[i] < 0) {
                continue;
            }
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

    /** Reads the mapping block from the global heap. */
    private static List<Mapping> mappings(FileContext ctx, DataLayout.Virtual layout) {
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
        List<String> fileNames = new ArrayList<>();
        List<String> datasetNames = new ArrayList<>();
        List<Mapping> mappings = new ArrayList<>();
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
            mappings.add(new Mapping(sourceFile, sourceDataset, sourceSelection, virtualSelection));
        }
        return mappings;
    }

    /** The name an entry shares with the earlier entry {@code index}. */
    private static String earlier(List<String> names, long index, int entry) {
        if (index < 0 || index >= entry) {
            throw new HdfFormatException("virtual dataset mapping " + entry + " refers to mapping " + index);
        }
        return names.get((int) index);
    }

    /** True if a source name contains the printf block specifier {@code %b}. */
    private static boolean hasBlockSpecifier(String name) {
        for (int i = 0; i < name.length() - 1; i++) {
            if (name.charAt(i) == '%') {
                if (name.charAt(i + 1) == 'b') {
                    return true;
                }
                i++; // skip the character after '%', so "%%b" is a literal "%b"
            }
        }
        return false;
    }

    /**
     * A source name with {@code %%} unescaped and each {@code %b} replaced by {@code block}, as libhdf5's
     * {@code H5D__virtual_build_source_name} does ({@code block} is -1 for a name without {@code %b}).
     */
    static String expand(String name, long block) {
        if (name.indexOf('%') < 0) {
            return name;
        }
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            char next = i + 1 < name.length() ? name.charAt(i + 1) : '\0';
            if (next == '%') {
                out.append('%');
            } else if (next == 'b' && block >= 0) {
                out.append(block);
            } else {
                throw new HdfFormatException("invalid format specifier in virtual dataset source name '" + name + "'");
            }
            i++;
        }
        return out.toString();
    }

    private static int requireUnlimitedSource(Mapping mapping, int sourceRank) {
        int su = mapping.source().unlimitedDimension();
        if (su < 0) {
            throw new HdfFormatException("virtual dataset mapping is unlimited, but neither printf-style nor"
                    + " unlimited in its source");
        }
        requireRank(mapping.source().highCorner().length, sourceRank);
        return su;
    }

    private static void requireRank(int rank, int expected) {
        if (rank != expected) {
            throw new HdfFormatException("virtual dataset selection of rank " + rank + " in a dataspace of rank " + expected);
        }
    }

    /** Opens source datasets for one read, each source file once, and closes them at the end. */
    private static final class Sources implements AutoCloseable {
        final FileContext ctx;
        private final Path directory;
        private final Map<Path, Optional<Hdf5File>> files = new HashMap<>();

        Sources(FileContext ctx) {
            this.ctx = ctx;
            this.directory = ctx.path() == null ? null : ctx.path().getParent();
        }

        /**
         * The source dataset, or null if its file or the dataset itself is missing. A name the policy
         * refuses fails, unless {@code refusalIsMissing}.
         */
        Dataset find(String fileName, String datasetName, boolean refusalIsMissing) {
            Group root;
            if (fileName.equals(".")) {
                root = Group.root(ctx, ctx.rootAddress()); // the source is in this same file
            } else {
                Path path;
                try {
                    path = ctx.externalFileAccess().resolveVirtualSource(fileName, directory);
                } catch (HdfUnsupportedException e) {
                    if (refusalIsMissing) {
                        return null;
                    }
                    throw e;
                }
                if (path == null) {
                    return null;
                }
                Optional<Hdf5File> file = files.computeIfAbsent(path,
                        p -> Optional.ofNullable(openOrNull(p, ctx.options())));
                if (file.isEmpty()) {
                    return null;
                }
                root = file.get().root();
            }
            return navigate(root, datasetName);
        }

        @Override
        public void close() {
            for (Optional<Hdf5File> file : files.values()) {
                file.ifPresent(Hdf5File::close);
            }
        }
    }

    /** The dataset at {@code path} from {@code root}, or null if there is none (libhdf5 then fills). */
    private static Dataset navigate(Group root, String path) {
        Hdf5Object current = root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!(current instanceof Group group)) {
                return null;
            }
            Optional<Hdf5Object> child = group.child(part);
            if (child.isEmpty()) {
                return null;
            }
            current = child.get();
        }
        return current instanceof Dataset dataset ? dataset : null;
    }

    private static Hdf5File openOrNull(Path path, OpenOptions options) {
        try {
            return Hdf5File.open(path, options);
        } catch (IOException e) {
            return null; // an unavailable source contributes only the fill value
        }
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
            case Datatype.FloatingPoint t when t.vaxOrder() -> null; // not a plain byte reversal
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
