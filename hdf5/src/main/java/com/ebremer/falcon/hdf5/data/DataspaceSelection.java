package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A serialized dataspace selection, as stored in region references and virtual-dataset mappings
 * ({@code H5S_SELECT_SERIALIZE}). Every encoding libhdf5 writes is read:
 *
 * <ul>
 *   <li>all / none: {@code type(4) · version(4)=1 · reserved(4) · length(4)=0};</li>
 *   <li>points v1: {@code type(4)=1 · version(4) · reserved(4) · length(4) · rank(4) · count(4) ·
 *       coordinates (u32)}; v2: {@code type · version · encode size(1) · rank(4) · count · coordinates}
 *       (each {@code encode size} bytes wide);</li>
 *   <li>hyperslab v1: {@code type(4)=2 · version(4) · reserved(4) · length(4) · rank(4) · blocks(4)},
 *       then each block's start and (inclusive) end corner (u32); v2: {@code type · version · flags(1) ·
 *       length(4) · rank(4)} then, for a regular selection, start/stride/count/block per dimension (u64);
 *       v3: {@code type · version · flags(1) · encode size(1) · rank(4)}, then either the regular
 *       start/stride/count/block per dimension or a block count and block corners.</li>
 * </ul>
 *
 * Elements are visited as libhdf5 visits them: a hyperslab in row-major order, points in the order
 * listed.
 *
 * <p>A virtual dataset's mapping pairs the elements of two selections in order; {@link #elements} gives
 * them as {@link SelectedElements}. A mapping may be <b>unlimited</b>: a regular hyperslab whose count
 * (or block) is unlimited in one dimension. Such a selection is used {@linkplain #clippedElements clipped}
 * to an extent there, or {@linkplain #blockElements one block at a time} (a printf-style mapping, one
 * source per block).
 */
public final class DataspaceSelection {

    public static final int NONE = 0;
    public static final int POINTS = 1;
    public static final int HYPERSLAB = 2;
    public static final int ALL = 3;

    private static final int REGULAR = 0x01;

    private final int type;
    private final int byteLength;
    // Regular hyperslab: per-dimension start/stride/count/block (count/block may be UNLIMITED).
    private final long[] start;
    private final long[] stride;
    private final long[] count;
    private final long[] block;
    // Irregular hyperslab (block corners, inclusive) or points (one corner per point, in order).
    private final long[][] lows;
    private final long[][] highs;

    /** An unlimited count or block in a regular hyperslab (a virtual dataset's unlimited mapping). */
    public static final long UNLIMITED = -1L;

    private DataspaceSelection(int type, int byteLength, long[] start, long[] stride, long[] count, long[] block,
                               long[][] lows, long[][] highs) {
        this.type = type;
        this.byteLength = byteLength;
        this.start = start;
        this.stride = stride;
        this.count = count;
        this.block = block;
        this.lows = lows;
        this.highs = highs;
    }

    /** The selection type: {@link #NONE}, {@link #POINTS}, {@link #HYPERSLAB}, or {@link #ALL}. */
    public int type() {
        return type;
    }

    /** The bytes the serialized selection occupies. */
    public int byteLength() {
        return byteLength;
    }

    /** True for a regular hyperslab with an unlimited count or block. */
    public boolean isUnlimited() {
        return unlimitedDimension() >= 0;
    }

    /** The dimension in which this regular hyperslab's count or block is unlimited, or -1 (libhdf5 allows one). */
    public int unlimitedDimension() {
        if (count != null) {
            for (int d = 0; d < count.length; d++) {
                if (count[d] == UNLIMITED || block[d] == UNLIMITED) {
                    return d;
                }
            }
        }
        return -1;
    }

    /**
     * How many indices the unlimited dimension selects below {@code limit}: the "slices" of libhdf5's
     * {@code H5S_hyper_get_clip_extent_match}.
     */
    public long selectedBelow(long limit) {
        int d = requireUnlimited();
        if (block[d] == 0 || limit <= start[d]) {
            return 0;
        }
        long span = limit - start[d];
        if (block[d] == UNLIMITED || block[d] == stride[d]) {
            return span; // one run of indices
        }
        return span / stride[d] * block[d] + Math.min(span % stride[d], block[d]);
    }

    /**
     * The extent the unlimited dimension needs for this selection to select {@code slices} indices there:
     * one past the last of them (libhdf5's {@code H5S_hyper_get_clip_extent_match}), or 0 for none.
     */
    public long extentSelecting(long slices) {
        return extentSelecting(slices, false);
    }

    /**
     * As {@link #extentSelecting(long)}; with {@code includeTrailing} (libhdf5's "first missing" view) an
     * extent that ends with a whole block runs on to where the next block would start, and selecting
     * nothing needs the extent up to the selection's start.
     */
    public long extentSelecting(long slices, boolean includeTrailing) {
        int d = requireUnlimited();
        if (slices <= 0 || block[d] == 0) {
            return includeTrailing ? start[d] : 0;
        }
        try {
            if (block[d] == UNLIMITED || block[d] == stride[d]) {
                return Math.addExact(start[d], slices);
            }
            long blocks = slices / block[d];
            long rest = slices % block[d];
            if (rest > 0) {
                return Math.addExact(Math.addExact(start[d], Math.multiplyExact(blocks, stride[d])), rest);
            }
            return includeTrailing
                    ? Math.addExact(start[d], Math.multiplyExact(blocks, stride[d]))
                    : Math.addExact(Math.addExact(start[d], Math.multiplyExact(blocks - 1, stride[d])), block[d]);
        } catch (ArithmeticException e) {
            throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
        }
    }

    /**
     * The selected elements of a space of shape {@code dims}, in iteration order.
     *
     * @throws HdfFormatException if the selection's rank differs from {@code dims} or it reaches outside
     */
    public SelectedElements elements(long[] dims) {
        try {
            return switch (type) {
                case NONE -> new SelectedElements.Listed(new long[0][], dims.length);
                case ALL -> {
                    SelectedElements.Axis[] axes = new SelectedElements.Axis[dims.length];
                    for (int d = 0; d < dims.length; d++) {
                        axes[d] = new SelectedElements.Axis(0, Math.max(1, dims[d]), Math.max(1, dims[d]), dims[d]);
                    }
                    yield new SelectedElements.Product(axes);
                }
                case POINTS -> new SelectedElements.Listed(coordinates(dims), dims.length);
                default -> count != null ? product(dims, -1, null)
                        : new SelectedElements.Listed(coordinates(dims), dims.length);
            };
        } catch (ArithmeticException e) {
            throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
        }
    }

    /**
     * The elements of this unlimited selection clipped to {@code limit} in its unlimited dimension
     * (libhdf5's {@code H5S_hyper_clip_unlim}): those whose index there is below {@code limit}.
     */
    public SelectedElements clippedElements(long[] dims, long limit) {
        int d = requireUnlimited();
        long size = selectedBelow(limit);
        long run = block[d] == UNLIMITED ? Math.max(1, size) : block[d];
        long step = block[d] == UNLIMITED ? run : stride[d];
        SelectedElements.Axis axis = new SelectedElements.Axis(start[d], step, Math.max(1, run), size);
        if (size > 0 && axis.last() >= dims[d]) {
            throw new HdfFormatException("selection reaches index " + axis.last() + " in dimension " + d
                    + " of extent " + dims[d]);
        }
        return product(dims, d, axis);
    }

    /**
     * Block {@code blockIndex} of this printf-style selection's unlimited dimension (libhdf5's
     * {@code H5S_hyper_get_unlim_block}): what a printf-style virtual mapping maps its
     * {@code blockIndex}th source to. In the unlimited dimension the block may run past {@code dims}
     * (another mapping can set the extent short of it); its elements there are not in the space.
     */
    public SelectedElements blockElements(long[] dims, long blockIndex) {
        int d = requirePrintf();
        long first = blockStart(blockIndex);
        if (first < 0 || block[d] < 0) {
            throw new HdfFormatException("printf-style selection block starts at " + first);
        }
        return product(dims, d, new SelectedElements.Axis(first, Math.max(1, block[d]), Math.max(1, block[d]), block[d]));
    }

    /**
     * The elements of this regular hyperslab, checked against {@code dims}; in dimension {@code given}
     * (unless -1) the axis is {@code givenAxis} instead (for an unlimited dimension).
     */
    private SelectedElements product(long[] dims, int given, SelectedElements.Axis givenAxis) {
        int rank = dims.length;
        checkRank(start.length, dims);
        SelectedElements.Axis[] axes = new SelectedElements.Axis[rank];
        try {
            for (int d = 0; d < rank; d++) {
                if (d == given) {
                    axes[d] = givenAxis;
                    continue;
                }
                if (count[d] == UNLIMITED || block[d] == UNLIMITED) {
                    throw new HdfUnsupportedException("unlimited selections are not supported here");
                }
                long n = Math.multiplyExact(count[d], block[d]);
                if (n > dims[d]) { // blocks never overlap, so a dimension cannot select more than its extent
                    throw new HdfFormatException("selection picks " + n + " indices in dimension " + d
                            + " of extent " + dims[d]);
                }
                if (n > 0 && count[d] > 1 && stride[d] < block[d]) {
                    throw new HdfFormatException("selection blocks overlap in dimension " + d + " (stride "
                            + stride[d] + ", block " + block[d] + ")");
                }
                SelectedElements.Axis axis = new SelectedElements.Axis(start[d], count[d] > 1 ? stride[d] : Math.max(1, block[d]),
                        Math.max(1, block[d]), n);
                if (n > 0 && (start[d] < 0 || axis.last() >= dims[d])) {
                    throw new HdfFormatException("selection reaches index " + (start[d] < 0 ? start[d] : axis.last())
                            + " in dimension " + d + " of extent " + dims[d]);
                }
                axes[d] = axis;
            }
        } catch (ArithmeticException e) {
            throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
        }
        return new SelectedElements.Product(axes);
    }

    /** Where block {@code blockIndex} of the unlimited dimension starts in a printf-style mapping. */
    public long blockStart(long blockIndex) {
        int d = requirePrintf();
        try {
            return Math.addExact(start[d], Math.multiplyExact(blockIndex, stride[d]));
        } catch (ArithmeticException e) {
            throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
        }
    }

    /** Where block {@code blockIndex} of the unlimited dimension ends (exclusive) in a printf-style mapping. */
    public long blockEnd(long blockIndex) {
        int d = requirePrintf();
        try {
            return Math.addExact(Math.addExact(start[d], Math.multiplyExact(blockIndex, stride[d])), block[d]);
        } catch (ArithmeticException e) {
            throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
        }
    }

    /**
     * The inclusive upper corner of the selection's bounding box, with -1 in an unlimited dimension; null
     * for an "all" or "none" selection, whose bounds are those of its dataspace.
     */
    public long[] highCorner() {
        if (type == NONE || type == ALL) {
            return null;
        }
        if (count != null) {
            long[] high = new long[count.length];
            try {
                for (int d = 0; d < high.length; d++) {
                    boolean empty = count[d] == 0 || block[d] == 0;
                    high[d] = count[d] == UNLIMITED || block[d] == UNLIMITED || empty ? -1
                            : Math.addExact(start[d], Math.addExact(Math.multiplyExact(count[d] - 1, stride[d]), block[d] - 1));
                }
            } catch (ArithmeticException e) {
                throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
            }
            return high;
        }
        long[] high = null;
        for (long[] corner : type == POINTS ? lows : highs) {
            if (high == null) {
                high = corner.clone();
            }
            for (int d = 0; d < high.length; d++) {
                high[d] = Math.max(high[d], corner[d]);
            }
        }
        return high;
    }

    /** The unlimited dimension of a selection made of unlimited many blocks, as printf-style mappings need. */
    private int requirePrintf() {
        int d = requireUnlimited();
        if (count[d] != UNLIMITED) {
            throw new HdfFormatException("a printf-style virtual mapping needs an unlimited block count");
        }
        return d;
    }

    private int requireUnlimited() {
        int d = unlimitedDimension();
        if (d < 0) {
            throw new IllegalStateException("not an unlimited selection");
        }
        // libhdf5 refuses overlapping blocks, and an unlimited block with more than one of them.
        if ((block[d] == UNLIMITED && count[d] != 1)
                || (count[d] == UNLIMITED && block[d] != UNLIMITED && stride[d] < Math.max(1, block[d]))) {
            throw new HdfFormatException("invalid unlimited selection (start " + start[d] + ", stride "
                    + stride[d] + ", count " + count[d] + ", block " + block[d] + ")");
        }
        return d;
    }

    /** Parses the selection serialized at {@code offset}. */
    public static DataspaceSelection parse(HdfBuffer buf, long offset) {
        require(buf, offset, 16, offset); // every encoding has at least type, version, and 8 more bytes
        int type = (int) buf.getUnsignedInt(offset);
        int version = (int) buf.getUnsignedInt(offset + 4);
        switch (type) {
            case NONE, ALL -> {
                return new DataspaceSelection(type, 16, null, null, null, null, null, null);
            }
            case POINTS -> {
                return parsePoints(buf, offset, version);
            }
            case HYPERSLAB -> {
                return parseHyperslab(buf, offset, version);
            }
            default -> throw new HdfFormatException("unknown dataspace selection type " + type + " at " + offset);
        }
    }

    private static DataspaceSelection parsePoints(HdfBuffer buf, long offset, int version) {
        int encodeSize;
        long p;
        if (version == 1) {
            encodeSize = 4;
            p = offset + 16;                 // type, version, reserved, length
        } else if (version == 2) {
            encodeSize = checkedEncodeSize(buf.getUnsignedByte(offset + 8), offset);
            p = offset + 9;
        } else {
            throw new HdfUnsupportedException("point selection version " + version + " at " + offset);
        }
        require(buf, p, 4 + encodeSize, offset);
        int rank = checkedRank(buf.getUnsignedInt(p), offset);
        long n = buf.getUnsignedValue(p + 4, encodeSize);
        p += 4 + encodeSize;
        require(buf, p, n < 0 ? Long.MAX_VALUE : n * rank * encodeSize, offset); // bounds the allocation below
        long[][] points = new long[Elements.checkedInt(n)][];
        for (int i = 0; i < points.length; i++) {
            long[] point = new long[rank];
            for (int d = 0; d < rank; d++) {
                point[d] = buf.getUnsignedValue(p, encodeSize);
                p += encodeSize;
            }
            points[i] = point;
        }
        return new DataspaceSelection(POINTS, Math.toIntExact(p - offset), null, null, null, null, points, points);
    }

    private static DataspaceSelection parseHyperslab(HdfBuffer buf, long offset, int version) {
        int flags;
        int encodeSize;
        long p;
        switch (version) {
            case 1 -> {
                flags = 0;
                encodeSize = 4;
                p = offset + 16;             // type, version, reserved, length
            }
            case 2 -> {
                flags = buf.getUnsignedByte(offset + 8);
                encodeSize = 8;
                p = offset + 13;             // type, version, flags, length
            }
            case 3 -> {
                flags = buf.getUnsignedByte(offset + 8);
                encodeSize = checkedEncodeSize(buf.getUnsignedByte(offset + 9), offset);
                p = offset + 10;
            }
            default -> throw new HdfUnsupportedException("hyperslab selection version " + version + " at " + offset);
        }
        require(buf, p, 4, offset);
        int rank = checkedRank(buf.getUnsignedInt(p), offset);
        p += 4;
        long unlimited = encodeSize >= 8 ? -1L : (1L << (8 * encodeSize)) - 1;
        if ((flags & REGULAR) != 0) {
            if (version == 1) {
                throw new HdfFormatException("version-1 hyperslab selection flagged regular at " + offset);
            }
            require(buf, p, 4L * rank * encodeSize, offset);
            long[] start = new long[rank];
            long[] stride = new long[rank];
            long[] count = new long[rank];
            long[] block = new long[rank];
            for (int d = 0; d < rank; d++) {
                start[d] = buf.getUnsignedValue(p, encodeSize);
                stride[d] = buf.getUnsignedValue(p + encodeSize, encodeSize);
                count[d] = unlimitedToMarker(buf.getUnsignedValue(p + 2L * encodeSize, encodeSize), unlimited);
                block[d] = unlimitedToMarker(buf.getUnsignedValue(p + 3L * encodeSize, encodeSize), unlimited);
                p += 4L * encodeSize;
            }
            return new DataspaceSelection(HYPERSLAB, Math.toIntExact(p - offset), start, stride, count, block, null, null);
        }
        if (version == 2) {
            throw new HdfUnsupportedException("irregular version-2 hyperslab selection at " + offset);
        }
        require(buf, p, encodeSize, offset);
        long blocks = buf.getUnsignedValue(p, encodeSize);
        p += encodeSize;
        require(buf, p, blocks < 0 ? Long.MAX_VALUE : blocks * 2 * rank * encodeSize, offset);
        long[][] lows = new long[Elements.checkedInt(blocks)][];
        long[][] highs = new long[lows.length][];
        for (int b = 0; b < lows.length; b++) {
            lows[b] = new long[rank];
            highs[b] = new long[rank];
            for (int d = 0; d < rank; d++) {
                lows[b][d] = buf.getUnsignedValue(p, encodeSize);
                p += encodeSize;
            }
            for (int d = 0; d < rank; d++) {
                highs[b][d] = buf.getUnsignedValue(p, encodeSize);
                p += encodeSize;
                if (highs[b][d] < lows[b][d]) {
                    throw new HdfFormatException("hyperslab block ends before it starts at " + offset);
                }
            }
        }
        return new DataspaceSelection(HYPERSLAB, Math.toIntExact(p - offset), null, null, null, null, lows, highs);
    }

    /** Fails unless {@code length} more bytes are present at {@code p}: a selection must fit its buffer. */
    private static void require(HdfBuffer buf, long p, long length, long offset) {
        if (length < 0 || p + length > buf.size() || p + length < p) {
            throw new HdfFormatException("dataspace selection at " + offset + " is truncated or corrupt");
        }
    }

    private static long unlimitedToMarker(long value, long unlimited) {
        return value == unlimited ? UNLIMITED : value;
    }

    private static int checkedEncodeSize(int size, long offset) {
        if (size != 2 && size != 4 && size != 8) {
            throw new HdfFormatException("invalid selection encode size " + size + " at " + offset);
        }
        return size;
    }

    private static int checkedRank(long rank, long offset) {
        if (rank < 1 || rank > 32) {
            throw new HdfFormatException("invalid selection rank " + rank + " at " + offset);
        }
        return (int) rank;
    }

    /**
     * The coordinates of every selected element of a dataspace of shape {@code dims}, in libhdf5's
     * iteration order.
     *
     * @throws HdfFormatException if the selection's rank differs from {@code dims} or it reaches outside
     */
    public long[][] coordinates(long[] dims) {
        try {
            return coordinatesOf(dims);
        } catch (ArithmeticException e) {
            throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
        }
    }

    private long[][] coordinatesOf(long[] dims) {
        int rank = dims.length;
        return switch (type) {
            case NONE -> new long[0][];
            case ALL -> blockCoordinates(new long[rank], minusOne(dims));
            case POINTS -> {
                for (long[] point : lows) {
                    checkInside(point, dims);
                }
                yield lows;
            }
            default -> {
                if (count != null) {
                    yield regularCoordinates(dims);
                }
                List<long[]> all = new ArrayList<>();
                long total = 0;
                long extent = 1;
                for (long d : dims) {
                    extent = Math.multiplyExact(extent, d);
                }
                for (int b = 0; b < lows.length; b++) {
                    checkInside(lows[b], dims);
                    checkInside(highs[b], dims);
                    long size = 1;
                    for (int d = 0; d < rank; d++) {
                        size *= highs[b][d] - lows[b][d] + 1;
                    }
                    total += size;
                    if (total > extent) { // blocks never overlap, so they cannot cover more than the space
                        throw new HdfFormatException("hyperslab blocks cover more elements than their dataspace");
                    }
                    all.addAll(Arrays.asList(blockCoordinates(lows[b], highs[b])));
                }
                all.sort(DataspaceSelection::compareRowMajor);
                yield all.toArray(new long[0][]);
            }
        };
    }

    /**
     * If this selection is one rectangular block of a space of shape {@code dims}, its start and shape
     * ({@code {start, shape}}); otherwise null.
     */
    public long[][] singleBlock(long[] dims) {
        try {
            return singleBlockOf(dims);
        } catch (ArithmeticException e) {
            throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
        }
    }

    private long[][] singleBlockOf(long[] dims) {
        int rank = dims.length;
        if (type == ALL) {
            return new long[][] {new long[rank], dims.clone()};
        }
        if (type != HYPERSLAB) {
            return null;
        }
        if (count != null) {
            checkRank(count.length, dims);
            long[] shape = new long[rank];
            long[] high = new long[rank];
            for (int d = 0; d < rank; d++) {
                // One run of indices per dimension: a single block, or blocks that abut (stride == block).
                if (count[d] == UNLIMITED || block[d] == UNLIMITED || (count[d] != 1 && stride[d] != block[d])) {
                    return null;
                }
                shape[d] = Math.multiplyExact(count[d], block[d]);
                if (shape[d] == 0) {
                    return null;
                }
                high[d] = start[d] + shape[d] - 1;
            }
            checkInside(start, dims);
            checkInside(high, dims);
            return new long[][] {start.clone(), shape};
        }
        if (lows.length != 1) {
            return null;
        }
        checkInside(lows[0], dims);
        checkInside(highs[0], dims);
        long[] shape = new long[rank];
        for (int d = 0; d < rank; d++) {
            shape[d] = highs[0][d] - lows[0][d] + 1;
        }
        return new long[][] {lows[0].clone(), shape};
    }

    /** The coordinates of a regular hyperslab in row-major order. */
    private long[][] regularCoordinates(long[] dims) {
        int rank = dims.length;
        checkRank(start.length, dims);
        long[][] indices = new long[rank][];
        for (int d = 0; d < rank; d++) {
            if (count[d] == UNLIMITED || block[d] == UNLIMITED) {
                throw new HdfUnsupportedException("unlimited selections are not supported here");
            }
            long n = Math.multiplyExact(count[d], block[d]);
            if (n > dims[d]) { // blocks never overlap, so a dimension cannot select more than its extent
                throw new HdfFormatException("selection picks " + n + " indices in dimension " + d
                        + " of extent " + dims[d]);
            }
            long[] index = new long[Elements.checkedInt(n)];
            int k = 0;
            for (long j = 0; j < count[d]; j++) {
                for (long b = 0; b < block[d]; b++) {
                    long coordinate = start[d] + j * stride[d] + b;
                    if (coordinate < 0 || coordinate >= dims[d]) {
                        throw new HdfFormatException("selection reaches index " + coordinate
                                + " in dimension " + d + " of extent " + dims[d]);
                    }
                    index[k++] = coordinate;
                }
            }
            indices[d] = index;
        }
        long total = 1;
        for (long[] index : indices) {
            total = Math.multiplyExact(total, index.length);
        }
        long[][] out = new long[Elements.checkedInt(total)][];
        int[] cursor = new int[rank];
        for (int i = 0; i < out.length; i++) {
            long[] coordinate = new long[rank];
            for (int d = 0; d < rank; d++) {
                coordinate[d] = indices[d][cursor[d]];
            }
            out[i] = coordinate;
            for (int d = rank - 1; d >= 0; d--) {
                if (++cursor[d] < indices[d].length) {
                    break;
                }
                cursor[d] = 0;
            }
        }
        return out;
    }

    private static long[][] blockCoordinates(long[] low, long[] high) {
        int rank = low.length;
        long total = 1;
        for (int d = 0; d < rank; d++) {
            total = Math.multiplyExact(total, high[d] - low[d] + 1);
        }
        long[][] out = new long[Elements.checkedInt(Math.max(0, total))][];
        long[] cursor = low.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] = cursor.clone();
            for (int d = rank - 1; d >= 0; d--) {
                if (++cursor[d] <= high[d]) {
                    break;
                }
                cursor[d] = low[d];
            }
        }
        return out;
    }

    private static long[] minusOne(long[] dims) {
        long[] out = new long[dims.length];
        for (int d = 0; d < dims.length; d++) {
            out[d] = dims[d] - 1;
        }
        return out;
    }

    private static int compareRowMajor(long[] a, long[] b) {
        for (int d = 0; d < a.length; d++) {
            int c = Long.compare(a[d], b[d]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static void checkRank(int rank, long[] dims) {
        if (rank != dims.length) {
            throw new HdfFormatException("selection has rank " + rank + " but its dataspace has rank " + dims.length);
        }
    }

    private static void checkInside(long[] coordinate, long[] dims) {
        checkRank(coordinate.length, dims);
        for (int d = 0; d < dims.length; d++) {
            if (coordinate[d] < 0 || coordinate[d] >= dims[d]) {
                throw new HdfFormatException("selection reaches index " + coordinate[d] + " in dimension "
                        + d + " of extent " + dims[d]);
            }
        }
    }
}
