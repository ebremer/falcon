package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.DataspaceSelection;
import com.ebremer.falcon.hdf5.data.SelectedElements;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p><b>Reading part of it.</b> A read asks for a box of the virtual dataset, or for its elements a strided
 * or point selection picks. Mappings that do not reach them are skipped without opening their sources. For
 * the others, the virtual elements wanted are paired with their source elements by position (arithmetic,
 * for the regular selections nearly every mapping uses), and only those source elements are read, through
 * the source's own selection reads (a chunked source reads just the chunks that hold them): the box that
 * bounds them, when they fill much of it, else the elements themselves (P2 PF7). Source files stay open,
 * and source datasets found are kept, so a later read does not look them up again.
 *
 * <p><b>Writing through it</b> (P2 WF11): {@link #parts} gives a write the same pairs, which
 * {@link Hdf5Writer} writes into the sources.
 */
final class VirtualDataset {

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

    private final FileContext ctx;
    private final DataLayout.Virtual layout;
    private volatile List<Mapping> mappings;                                   // parsed once
    private final Map<String, Dataset> sources = new ConcurrentHashMap<>();     // found sources, by file and name
    private final Map<Integer, List<Dataset>> printf = new ConcurrentHashMap<>(); // a printf mapping's sources

    private VirtualDataset(FileContext ctx, DataLayout.Virtual layout) {
        this.ctx = ctx;
        this.layout = layout;
    }

    /**
     * The virtual dataset whose layout is {@code layout}. It keeps its mapping list, and the source
     * datasets it finds, for later reads; their files stay open until this file closes.
     */
    static VirtualDataset of(FileContext ctx, DataLayout.Virtual layout) {
        return new VirtualDataset(ctx, layout);
    }

    /**
     * The extent of the virtual dataset whose stored extent is {@code storedDims}: the stored one, except
     * in a dimension in which a mapping is unlimited, which the sources set (the largest extent any such
     * mapping reaches, and at least what the other mappings cover there).
     */
    long[] extent(long[] storedDims) {
        return nested(() -> computeExtent(storedDims));
    }

    /**
     * The virtual dataset's elements in the box {@code [offset, offset + count)} of its extent
     * {@code virtualDims}, row-major: only the mappings that reach the box are looked at, and from each
     * source only the part those elements come from is read.
     */
    byte[] read(long[] virtualDims, Datatype type, byte[] fill, long[] offset, long[] count) {
        return nested(() -> readBox(virtualDims, type, fill, offset, count));
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

    private List<Mapping> mappings() {
        List<Mapping> result = mappings;
        if (result == null) {
            result = List.copyOf(mappings(ctx, layout));
            mappings = result;
        }
        return result;
    }

    private long[] computeExtent(long[] storedDims) {
        List<Mapping> all = mappings();
        if (all.stream().noneMatch(m -> !m.isEmpty() && m.virtual().isUnlimited())) {
            return storedDims;
        }
        boolean firstMissing = ctx.options().virtualView() == OpenOptions.VirtualView.FIRST_MISSING;
        int rank = storedDims.length;
        long[] unlimited = new long[rank];
        Arrays.fill(unlimited, -1); // -1: no unlimited mapping in this dimension
        long[] covered = new long[rank];
        for (int i = 0; i < all.size(); i++) {
            Mapping mapping = all.get(i);
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
                long reach = mapping.isPrintf() ? printfExtent(i, mapping, firstMissing)
                        : unlimitedExtent(mapping, firstMissing);
                // "Last available" takes the furthest-reaching mapping, "first missing" the shortest.
                unlimited[u] = unlimited[u] < 0 ? reach
                        : firstMissing ? Math.min(unlimited[u], reach) : Math.max(unlimited[u], reach);
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
    private long unlimitedExtent(Mapping mapping, boolean firstMissing) {
        Dataset source = find(expand(mapping.fileName(), -1), expand(mapping.datasetName(), -1), false);
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
    private long printfExtent(int index, Mapping mapping, boolean firstMissing) {
        List<Dataset> found = printfSources(index, mapping);
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
     * printf gap allows (libhdf5's {@code first_missing} loop). Searched once, as the extent depends on it.
     */
    private List<Dataset> printfSources(int index, Mapping mapping) {
        return printf.computeIfAbsent(index, i -> {
            long gap = ctx.options().virtualPrintfGap();
            List<Dataset> found = new ArrayList<>();
            long next = 0; // one past the last source found
            for (long block = 0; block < MAX_PRINTF_SOURCES && block - next <= gap; block++) {
                // Past the first source, a refused name ends the search like a missing one: an absolute
                // name whose files were moved resolves by file name, and the names after the last one never do.
                Dataset source = find(expand(mapping.fileName(), block), expand(mapping.datasetName(), block),
                        block > 0);
                if (source != null) {
                    while (found.size() < block) {
                        found.add(null);
                    }
                    found.add(source);
                    next = block + 1;
                }
            }
            return Collections.unmodifiableList(found);
        });
    }

    /**
     * One mapping's share of a read or write (or, for a printf-style mapping, one source's): the source
     * dataset, the virtual elements the mapping fills ({@code target}), and the source elements they take
     * ({@code selected}): the <i>i</i>th of {@code target} pairs with the <i>i</i>th of {@code selected}, for
     * the first {@link #pairs()} of them.
     */
    record Part(Dataset source, SelectedElements target, SelectedElements selected, String sourceName) {

        long pairs() {
            return Math.min(target.count(), selected.count());
        }
    }

    /**
     * The parts of the mappings that may reach the box {@code [offset, offset + count)} of the virtual
     * dataset of extent {@code virtualDims}, for the sources found: mappings that miss the box are skipped
     * before their sources are looked for, and a missing source has no part (its elements keep the fill value,
     * as in libhdf5).
     */
    List<Part> parts(long[] virtualDims, long[] offset, long[] count) {
        return nested(() -> findParts(virtualDims, offset, count));
    }

    private List<Part> findParts(long[] virtualDims, long[] offset, long[] count) {
        List<Part> parts = new ArrayList<>();
        List<Mapping> all = mappings();
        for (int i = 0; i < all.size(); i++) {
            Mapping mapping = all.get(i);
            if (mapping.isEmpty()) {
                continue;
            }
            DataspaceSelection virtual = mapping.virtual();
            int u = virtual.unlimitedDimension();
            if (u >= 0 && mapping.isPrintf()) {
                requireRank(virtual.highCorner().length, virtualDims.length);
                List<Dataset> found = printfSources(i, mapping);
                for (int block = 0; block < found.size() && virtual.blockStart(block) < virtualDims[u]; block++) {
                    Dataset source = found.get(block);
                    if (source == null) {
                        continue; // a source the printf gap skipped leaves the fill value
                    }
                    SelectedElements target = virtual.blockElements(virtualDims, block);
                    if (target.mayIntersect(offset, count)) {
                        parts.add(new Part(source, target, mapping.source().elements(source.dataspace().dimensions()),
                                mapping.datasetName()));
                    }
                }
                continue;
            }
            // The virtual elements this mapping can reach (for an unlimited one, at most to the extent): a
            // mapping that misses the box is skipped before its source is looked for.
            SelectedElements reach = u >= 0 ? virtual.clippedElements(virtualDims, virtualDims[u])
                    : virtual.elements(virtualDims);
            if (!reach.mayIntersect(offset, count)) {
                continue;
            }
            String datasetName = expand(mapping.datasetName(), -1);
            Dataset source = find(expand(mapping.fileName(), -1), datasetName, false);
            if (source == null) {
                continue; // a missing source leaves the fill value in place, as in libhdf5
            }
            long[] sourceDims = source.dataspace().dimensions();
            SelectedElements target;
            SelectedElements selected;
            if (u >= 0) {
                // Clip the virtual selection to the extent (which the "first missing" view may set short
                // of this mapping's reach), and the source selection to match it.
                int su = requireUnlimitedSource(mapping, sourceDims.length);
                long reached = virtual.extentSelecting(mapping.source().selectedBelow(sourceDims[su]));
                long virtualClip = Math.min(reached, virtualDims[u]);
                long sourceClip = Math.min(sourceDims[su],
                        mapping.source().extentSelecting(virtual.selectedBelow(virtualClip)));
                selected = mapping.source().clippedElements(sourceDims, sourceClip);
                target = virtual.clippedElements(virtualDims, virtualClip);
            } else if (mapping.source().isUnlimited()) {
                throw new HdfFormatException("virtual dataset mapping has an unlimited source selection"
                        + " but a limited virtual one");
            } else {
                selected = mapping.source().elements(sourceDims);
                target = reach;
            }
            parts.add(new Part(source, target, selected, datasetName));
        }
        return parts;
    }

    private byte[] readBox(long[] virtualDims, Datatype type, byte[] fill, long[] offset, long[] count) {
        requireFixedSize(type);
        int elementSize = type.size();
        long elements = 1;
        for (long c : count) {
            elements = Math.multiplyExact(elements, c);
        }
        byte[] output = new byte[com.ebremer.falcon.hdf5.data.Elements.checkedByteCount(elements, elementSize)];
        tileFill(output, fill, elementSize);
        if (elements == 0) {
            return output;
        }
        long[] outStride = strides(count);
        for (Part part : findParts(virtualDims, offset, count)) {
            long n = part.pairs();
            // The virtual elements inside the box, each with its place in the box.
            transfer(output, part, type, visitor -> part.target().forEachInBox(offset, count, (position, coordinates) -> {
                if (position < n) {
                    long to = 0;
                    for (int d = 0; d < coordinates.length; d++) {
                        to += (coordinates[d] - offset[d]) * outStride[d];
                    }
                    visitor.visit(position, to);
                }
            }));
        }
        return output;
    }

    /**
     * The virtual dataset's elements that {@code elements} selects, in its order (P2 PF7): each mapping's
     * part of them is read from its source, as the source elements they pair with, and nothing else.
     */
    byte[] gather(long[] virtualDims, Datatype type, byte[] fill, SelectedElements elements) {
        return nested(() -> gatherElements(virtualDims, type, fill, elements));
    }

    private byte[] gatherElements(long[] virtualDims, Datatype type, byte[] fill, SelectedElements elements) {
        requireFixedSize(type);
        int elementSize = type.size();
        byte[] output = new byte[com.ebremer.falcon.hdf5.data.Elements.checkedByteCount(elements.count(), elementSize)];
        tileFill(output, fill, elementSize);
        if (elements.count() == 0) {
            return output;
        }
        int rank = virtualDims.length;
        long[] low = elements.lowCorner();
        long[] high = elements.highCorner();
        long[] box = new long[rank];
        for (int d = 0; d < rank; d++) {
            box[d] = high[d] - low[d] + 1;
        }
        for (Part part : findParts(virtualDims, low, box)) {
            long n = part.pairs();
            // The elements asked for inside the box that bounds both them and the mapping's virtual elements,
            // each paired with its place among those.
            long[] targetLow = part.target().lowCorner();
            long[] targetHigh = part.target().highCorner();
            long[] from = new long[rank];
            long[] span = new long[rank];
            boolean empty = part.target().count() == 0;
            for (int d = 0; d < rank && !empty; d++) {
                from[d] = Math.max(low[d], targetLow[d]);
                span[d] = Math.min(high[d], targetHigh[d]) - from[d] + 1;
                empty = span[d] <= 0;
            }
            if (empty) {
                continue;
            }
            transfer(output, part, type, visitor -> elements.forEachInBox(from, span, (position, coordinates) -> {
                long p = part.target().positionOf(coordinates);
                if (p >= 0 && p < n) {
                    visitor.visit(p, position);
                }
            }));
        }
        return output;
    }

    /** Visits pairs of a part's target positions and output elements; replayable, the same pairs each time. */
    @FunctionalInterface
    private interface Pairs {
        void forEach(PairVisitor visitor);
    }

    /** One pair: the target (and so source) element at {@code position}, for output element {@code to}. */
    @FunctionalInterface
    private interface PairVisitor {
        void visit(long position, long to);
    }

    /** The most source elements read at once when a part's elements are scattered: a bound on memory. */
    private static final int BATCH = 1 << 16;

    /**
     * Copies into {@code output} the source elements the pairs name, read as cheaply as the source elements'
     * layout allows (P2 PF7): the box that bounds them, when they fill a quarter of it or more (a mapping of
     * the same shape, or a little strided); else every element the source selection picks, when all are
     * wanted; else the elements themselves, in batches, so a source strided far apart, or a mapping whose
     * virtual and source shapes differ, reads only what it needs (for chunked data, only the chunks that hold
     * them).
     */
    private static void transfer(byte[] output, Part part, Datatype type, Pairs pairs) {
        SelectedElements selected = part.selected();
        int elementSize = type.size();
        boolean swap = byteSwapNeeded(part.source().datatype(), type, part.sourceName());
        int sourceRank = selected.rank();
        long[] low = new long[sourceRank];
        long[] high = new long[sourceRank];
        Arrays.fill(low, Long.MAX_VALUE);
        Arrays.fill(high, Long.MIN_VALUE);
        long[] at = new long[sourceRank];
        long[] wanted = new long[1];
        pairs.forEach((position, to) -> {
            selected.coordinates(position, at);
            for (int d = 0; d < sourceRank; d++) {
                low[d] = Math.min(low[d], at[d]);
                high[d] = Math.max(high[d], at[d]);
            }
            wanted[0]++;
        });
        if (wanted[0] == 0) {
            return;
        }
        long[] shape = new long[sourceRank];
        long volume = 1;
        for (int d = 0; d < sourceRank; d++) {
            shape[d] = high[d] - low[d] + 1;
            volume = volume > Long.MAX_VALUE / shape[d] ? Long.MAX_VALUE : volume * shape[d];
        }
        if (volume / 4 <= wanted[0]) {
            MemorySegment bytes = part.source().selectionData(low, shape);
            long[] sourceStride = strides(shape);
            pairs.forEach((position, to) -> {
                selected.coordinates(position, at);
                long from = 0;
                for (int d = 0; d < sourceRank; d++) {
                    from += (at[d] - low[d]) * sourceStride[d];
                }
                put(bytes, from, output, to, elementSize, swap);
            });
            return;
        }
        long n = part.pairs();
        if (n == selected.count() && wanted[0] >= n) {
            MemorySegment bytes = part.source().selectedData(selected); // every element the selection picks, in order
            pairs.forEach((position, to) -> put(bytes, position, output, to, elementSize, swap));
            return;
        }
        int batch = (int) Math.min(BATCH, wanted[0]);
        long[] positions = new long[batch];
        long[] targets = new long[batch];
        int[] held = new int[1];
        Runnable flush = () -> {
            long[][] points = new long[held[0]][sourceRank];
            for (int i = 0; i < held[0]; i++) {
                selected.coordinates(positions[i], points[i]);
            }
            MemorySegment bytes = part.source().selectedData(SelectedElements.points(points, sourceRank));
            for (int i = 0; i < held[0]; i++) {
                put(bytes, i, output, targets[i], elementSize, swap);
            }
            held[0] = 0;
        };
        pairs.forEach((position, to) -> {
            positions[held[0]] = position;
            targets[held[0]] = to;
            if (++held[0] == batch) {
                flush.run();
            }
        });
        if (held[0] > 0) {
            flush.run();
        }
    }

    /** Copies source element {@code element} of {@code from} to output element {@code to}, byte-reversed if {@code swap}. */
    private static void put(MemorySegment from, long element, byte[] output, long to, int elementSize, boolean swap) {
        int out = (int) (to * elementSize);
        if (swap) {
            for (int b = 0; b < elementSize; b++) {
                output[out + b] = from.get(ValueLayout.JAVA_BYTE, element * elementSize + elementSize - 1 - b);
            }
        } else {
            MemorySegment.copy(from, ValueLayout.JAVA_BYTE, element * elementSize, output, out, elementSize);
        }
    }

    /** Variable-length and reference elements point into their own file's heaps and objects. */
    static void requireFixedSize(Datatype type) {
        if (containsHeapData(type)) {
            throw new HdfUnsupportedException("virtual datasets of variable-length or reference data are not supported");
        }
    }

    private static long[] strides(long[] shape) {
        long[] stride = new long[shape.length];
        long s = 1;
        for (int d = shape.length - 1; d >= 0; d--) {
            stride[d] = s;
            s *= shape[d];
        }
        return stride;
    }

    /**
     * The source dataset, or null if its file or the dataset itself is missing. A name the policy refuses
     * fails, unless {@code refusalIsMissing}. A source found is kept for later reads; one missing is
     * looked for again next time, as libhdf5 does.
     */
    private Dataset find(String fileName, String datasetName, boolean refusalIsMissing) {
        String key = fileName + '\0' + datasetName;
        Dataset cached = sources.get(key);
        if (cached != null) {
            return cached;
        }
        Group root;
        if (fileName.equals(".")) {
            root = Group.root(ctx, ctx.rootAddress()); // the source is in this same file
        } else {
            FileContext file;
            try {
                file = SourceFiles.find(ctx, fileName, ExternalFileAccess.Purpose.VIRTUAL_SOURCE);
            } catch (HdfUnsupportedException e) {
                if (refusalIsMissing) {
                    return null;
                }
                throw e;
            }
            if (file == null) {
                return null;
            }
            root = Group.root(file, file.rootAddress());
        }
        Dataset found = root.child(datasetName).orElse(null) instanceof Dataset dataset ? dataset : null;
        if (found != null) {
            Dataset raced = sources.putIfAbsent(key, found);
            return raced != null ? raced : found;
        }
        return null;
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

    /**
     * Whether a source element must be byte-reversed to match the virtual dataset's datatype: false if
     * the types are the same, true if they are the same atomic type in the other byte order. libhdf5
     * converts any other difference (size, sign, class); Falcon reports it instead of copying the source
     * bytes as if they were the virtual type.
     */
    static boolean byteSwapNeeded(Datatype source, Datatype target, String sourceName) {
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
