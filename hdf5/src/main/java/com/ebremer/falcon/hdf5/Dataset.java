package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.ChunkIndex;
import com.ebremer.falcon.hdf5.data.ChunkedReader;
import com.ebremer.falcon.hdf5.data.Elements;
import com.ebremer.falcon.hdf5.data.Hyperslab;
import com.ebremer.falcon.hdf5.data.SelectedElements;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.filter.FilterPipelineMessage;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.SharedMessage;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import com.ebremer.falcon.hdf5.layout.DataLayoutMessage;
import com.ebremer.falcon.hdf5.message.DataspaceMessage;
import com.ebremer.falcon.hdf5.message.DatatypeMessage;
import com.ebremer.falcon.hdf5.message.ExternalFileList;
import com.ebremer.falcon.hdf5.message.FillValueMessage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

/**
 * A dataset in the HDF5 hierarchy: a typed, shaped array of elements.
 *
 * <p>Reads return flattened row-major Java arrays ({@link #readInts()}, {@link #readDoubles()}, and so
 * on, or {@link #read()} for the most natural one), whatever the storage: compact, contiguous (in this
 * file or in external files), chunked (through any chunk index, and any filter Falcon decodes), or
 * virtual. {@link #select} and {@link #blocks} read part of a dataset; {@link #layout()},
 * {@link #chunkShape()}, {@link #filters()} and {@link #storageSize()} describe its storage. Elements
 * never written read back as the fill value.
 */
public final class Dataset extends Hdf5Object {

    // Parsed lazily, then cached. Each cache is one volatile field, read once into a local, so a dataset
    // shared between threads is never seen half-initialized (Optional distinguishes "none" from "not yet").
    private volatile Datatype datatype;
    private volatile Dataspace dataspace;
    private volatile DataLayout layout;
    private volatile java.util.Optional<FilterPipeline> filterPipeline;
    private volatile java.util.Optional<byte[]> fillValue;
    private volatile ChunkIndex chunkIndex; // a chunked dataset's index, read once (see chunkIndex())
    private volatile VirtualDataset virtual; // a virtual dataset's mappings and sources, kept for later reads

    private Dataset(FileContext ctx, String name, String path, long objectHeaderAddress) {
        super(ctx, name, path, objectHeaderAddress);
    }

    static Dataset child(FileContext ctx, String name, String parentPath, long objectHeaderAddress) {
        return new Dataset(ctx, name, childPath(parentPath, name), objectHeaderAddress);
    }

    @Override
    public boolean isGroup() {
        return false;
    }

    /** This dataset's element datatype (resolving a committed/shared type if referenced). */
    public Datatype datatype() {
        ctx.checkOpen();
        Datatype result = datatype;
        if (result == null) {
            HeaderMessage message = require(MessageType.DATATYPE, "datatype");
            result = DatatypeMessage.resolve(ctx, message.bodyOffset(), SharedMessage.isShared(message));
            datatype = result;
        }
        return result;
    }

    /**
     * This dataset's shape. A virtual dataset with unlimited mappings takes its extent from its sources,
     * as libhdf5 does when it opens one: so finding it may open the source files (as the file's
     * {@link ExternalFileAccess} policy allows).
     */
    public Dataspace dataspace() {
        ctx.checkOpen();
        Dataspace result = dataspace;
        if (result == null) {
            result = DataspaceMessage.parse(ctx, SharedMessage.resolve(ctx, require(MessageType.DATASPACE, "dataspace")));
            // Only an unlimited dimension can hold an unlimited mapping.
            boolean unlimited = java.util.stream.IntStream.range(0, result.rank()).anyMatch(result::isUnlimited);
            if (unlimited && dataLayout() instanceof DataLayout.Virtual virtual) {
                long[] dims = virtual(virtual).extent(result.dimensions());
                if (!java.util.Arrays.equals(dims, result.dimensions())) {
                    result = new Dataspace(result.version(), result.kind(), dims, result.maxDimensions());
                }
            }
            dataspace = result;
        }
        return result;
    }

    // ---------------------------------------------------------------- storage

    /** How a dataset's raw data is stored (libhdf5's {@code H5D_layout_t}). */
    public enum Layout {
        /** In the object header itself, for small datasets. */
        COMPACT,
        /** One block in the file (or in external raw data files), or none yet if never written. */
        CONTIGUOUS,
        /** Fixed-shape chunks, each stored (and filtered) separately and found through an index. */
        CHUNKED,
        /** Assembled from selections of other datasets, in this file or others. */
        VIRTUAL
    }

    /** How this dataset's raw data is stored. */
    public Layout layout() {
        ctx.checkOpen();
        return switch (dataLayout()) {
            case DataLayout.Compact c -> Layout.COMPACT;
            case DataLayout.Contiguous c -> Layout.CONTIGUOUS;
            case DataLayout.Chunked c -> Layout.CHUNKED;
            case DataLayout.Virtual v -> Layout.VIRTUAL;
        };
    }

    /** The shape of each chunk, in elements per dimension, if the dataset is {@linkplain Layout#CHUNKED chunked}. */
    public java.util.Optional<long[]> chunkShape() {
        ctx.checkOpen();
        if (!(dataLayout() instanceof DataLayout.Chunked chunked)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(java.util.Arrays.stream(chunked.chunkDimensions())
                .mapToLong(Integer::toUnsignedLong).toArray());
    }

    /**
     * The filters this dataset's chunks pass through on writing, in that order (Falcon undoes them in
     * reverse on reading). Empty if there are none. A filter Falcon cannot decode is listed too; reading
     * the data then throws {@link HdfUnsupportedException}.
     */
    public java.util.List<Filter> filters() {
        ctx.checkOpen();
        FilterPipeline pipeline = filterPipeline();
        if (pipeline == null) {
            return java.util.List.of();
        }
        return pipeline.filters().stream()
                .map(f -> new Filter(f.id(), f.name() != null ? f.name() : builtInFilterName(f.id()),
                        (f.flags() & FILTER_FLAG_OPTIONAL) != 0, f.clientData()))
                .toList();
    }

    /**
     * The bytes this dataset's raw data takes in the file, as libhdf5's {@code H5Dget_storage_size}
     * reports: for chunked data, the stored (filtered) size of every chunk written; for contiguous data,
     * its size once allocated (also when it lives in external files); for compact data, its size; and 0
     * for a virtual dataset, whose data lives in its sources. For chunked data this reads the whole
     * chunk index.
     */
    public long storageSize() {
        ctx.checkOpen();
        return switch (dataLayout()) {
            case DataLayout.Compact c -> c.data().length;
            case DataLayout.Contiguous c -> {
                if (c.address() == HdfBuffer.UNDEFINED_ADDRESS && header().find(MessageType.EXTERNAL_DATA_FILES) == null) {
                    yield 0; // never written
                }
                // Layout messages before version 3 do not record the size; libhdf5 computes it.
                yield c.size() >= 0 ? c.size() : byteCount();
            }
            case DataLayout.Chunked chunked -> chunkIndex(chunked).storedBytes();
            case DataLayout.Virtual v -> 0;
        };
    }

    /** {@code H5Z_FLAG_OPTIONAL}: a chunk may skip the filter. */
    private static final int FILTER_FLAG_OPTIONAL = 0x0001;

    /** libhdf5's name for one of its built-in filters, or empty. */
    private static String builtInFilterName(int id) {
        return switch (id) {
            case Filter.DEFLATE -> "deflate";
            case Filter.SHUFFLE -> "shuffle";
            case Filter.FLETCHER32 -> "fletcher32";
            case Filter.SZIP -> "szip";
            case Filter.NBIT -> "nbit";
            case Filter.SCALEOFFSET -> "scaleoffset";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ reads

    /**
     * Reads every element of an integer dataset as an {@code int}. Values are exact: one that does not
     * fit (a {@code uint32} above {@link Integer#MAX_VALUE}, a large {@code int64}) throws rather than
     * wrapping. An enumeration reads as the integers its elements hold, a bit field as the unsigned integer
     * of its bits, a time value as its seconds since 1970, and an array type as its base elements, every
     * element's in turn.
     *
     * @throws HdfUnsupportedException if the datatype is not an integer type or a value does not fit
     */
    public int[] readInts() {
        return reader().ints();
    }

    /**
     * Reads every element of an integer dataset as a {@code long}. Values are exact: a {@code uint64}
     * of 2<sup>63</sup> or more throws rather than reading as negative ({@link #read()} returns
     * {@code BigInteger[]} for {@code uint64}). Enumerations, bit fields and arrays read as for
     * {@link #readInts()}.
     *
     * @throws HdfUnsupportedException if the datatype is not an integer type or a value does not fit
     */
    public long[] readLongs() {
        return reader().longs();
    }

    /**
     * Reads every element of a floating-point or integer dataset as a {@code float}, converted as libhdf5
     * converts to {@code H5T_NATIVE_FLOAT}: a wider float, or an integer with more than 24 significant
     * bits, is rounded to the nearest {@code float}, and a complex number gives its real part.
     * Enumerations, bit fields and arrays read as for {@link #readInts()}.
     *
     * @throws HdfUnsupportedException if the datatype is neither floating-point, complex, nor an integer type
     */
    public float[] readFloats() {
        return reader().floats();
    }

    /**
     * Reads every element of a floating-point or integer dataset as a {@code double}, converted as libhdf5
     * converts to {@code H5T_NATIVE_DOUBLE}: integers are exact up to 53 significant bits, and wider ones
     * (large {@code int64} and {@code uint64} values) are rounded to the nearest {@code double}; a complex
     * number gives its real part ({@link #readComplexDoubles()} reads both). Enumerations, bit fields and
     * arrays read as for {@link #readInts()}.
     *
     * @throws HdfUnsupportedException if the datatype is neither floating-point, complex, nor an integer type
     */
    public double[] readDoubles() {
        return reader().doubles();
    }

    /**
     * Reads every element as a complex number, returning interleaved (real, imaginary) pairs, as C99 and
     * numpy store them: element <i>i</i>'s real part at {@code 2i} and its imaginary part at {@code 2i+1}.
     * Reads HDF5 2.0's complex type, h5py's complex compound (floating-point members {@code r} and
     * {@code i}), and real numbers, whose imaginary part is 0 (as libhdf5 converts them).
     *
     * @throws HdfUnsupportedException if the datatype is none of those
     */
    public double[] readComplexDoubles() {
        return reader().complexDoubles();
    }

    /** Reads every element as a complex number, as {@link #readComplexDoubles()}, each part a {@code float}. */
    public float[] readComplexFloats() {
        return reader().complexFloats();
    }

    /**
     * Reads every element of a fixed-length or variable-length string datatype; for an enumeration, each
     * element's member name ({@code null} for a value no member has).
     */
    public String[] readStrings() {
        return reader().strings();
    }

    /** Reads a variable-length sequence (ragged) datatype, one {@code int[]} row per element. */
    public int[][] readVlenInts() {
        return reader().vlenInts();
    }

    /** Reads a variable-length sequence (ragged) datatype, one {@code long[]} row per element. */
    public long[][] readVlenLongs() {
        return reader().vlenLongs();
    }

    /** Reads a variable-length sequence (ragged) datatype, one {@code double[]} row per element. */
    public double[][] readVlenDoubles() {
        return reader().vlenDoubles();
    }

    /** Reads a variable-length sequence (ragged) datatype, one {@code float[]} row per element. */
    public float[][] readVlenFloats() {
        return reader().vlenFloats();
    }

    /**
     * Reads an object-reference dataset, resolving each element to the object it points at (a group,
     * dataset, or committed datatype), or {@code null} for a null reference. Each object's
     * {@link Hdf5Object#path() path} is found when first asked for, as libhdf5 finds one.
     *
     * <p>Revised references (HDF5 1.12's {@code H5R_ref_t}) are read too, whatever each element holds: an
     * object reference resolves to its object, a region reference to its dataset, and an attribute
     * reference to the object the attribute is on. A revised reference may point into another file, which
     * is found and opened as the file's {@link ExternalFileAccess} policy allows, and stays open until this
     * file closes; the object read is that file's.
     *
     * @throws HdfUnsupportedException for a reference into a file the policy refuses
     * @throws HdfException for a reference into a file that is not found
     */
    public Hdf5Object[] readObjectReferences() {
        return reader().objectReferences();
    }

    /**
     * Reads a region-reference dataset, resolving each element to a {@link Selection} of the dataset it
     * points into (or {@code null} for a null reference). Read the selection to get the referenced data.
     *
     * <p>Revised references (HDF5 1.12's {@code H5R_ref_t}) are read too, into other files as for
     * {@link #readObjectReferences()}. An element that is an object or attribute reference, or points into
     * a file that is refused or not found, becomes a selection that throws when used.
     */
    public Selection[] readRegionReferences() {
        return reader().regionReferences();
    }

    /**
     * Reads a dataset of revised attribute references (HDF5 1.12's {@code H5R_ATTR}), resolving each
     * element to the attribute it names, or {@code null} for a null reference.
     *
     * @throws HdfUnsupportedException if the datatype is not a revised reference, an element is not an
     *         attribute reference, or one points into a file the {@link ExternalFileAccess} policy refuses
     * @throws HdfException if an element points into a file that is not found
     * @throws HdfFormatException if a referenced attribute does not exist
     */
    public Attribute[] readAttributeReferences() {
        return reader().attributeReferences();
    }

    /** The dataset's element bytes as stored, in the datatype's byte order (chunk filters already undone). */
    public byte[] readRawBytes() {
        return reader().rawBytes();
    }

    /**
     * This dataset's fill value as raw datatype-order bytes, if one is explicitly defined. Unallocated
     * or unwritten elements read back as this value; an empty result means the default (all-zero).
     */
    public java.util.Optional<byte[]> fillValueBytes() {
        byte[] fill = fillValue();
        return fill == null ? java.util.Optional.empty() : java.util.Optional.of(fill.clone());
    }

    /**
     * Reads the whole dataset into the most natural Java value:
     * <ul>
     *   <li>integers: {@code int[]} when every value of the type fits in an {@code int}, {@code long[]}
     *       when it fits in a {@code long} (so {@code uint32} reads as {@code long[]}), and
     *       {@code BigInteger[]} for {@code uint64}; bit fields likewise, as unsigned integers;</li>
     *   <li>{@code double[]} for floats, and for complex numbers their (real, imaginary) pairs (see
     *       {@link #readComplexDoubles()});</li>
     *   <li>{@code String[]} for strings, and for enumerations their member names;</li>
     *   <li>for a compound, a {@code Map<String, Object>} from each member's name, in member order, to its
     *       values read the same way (see {@link #member(String)});</li>
     *   <li>for an array type, its base elements' values, every element's in turn;</li>
     *   <li>{@code byte[][]} for opaque data, one array per element;</li>
     *   <li>{@code java.time.Instant[]} for time values (Unix seconds);</li>
     *   <li>variable-length sequences as {@code int[][]}, {@code long[][]} or {@code double[][]} rows, or
     *       for other base types {@code Object[]} rows each read the same way;</li>
     *   <li>{@code Selection[]} for region references, and {@code Hdf5Object[]} for object references and
     *       revised references (see {@link #readObjectReferences()}).</li>
     * </ul>
     */
    public Object read() {
        return reader().natural();
    }

    /**
     * The member {@code name} of every element of a compound dataset: a {@link Selection} of the whole
     * dataset that reads only that member, like a dataset of the member's type.
     *
     * <pre>{@code
     * double[] temperature = dataset.member("temperature").readDoubles();
     * }</pre>
     *
     * @throws IllegalArgumentException if the dataset is not of a compound datatype
     * @throws java.util.NoSuchElementException if it has no member of that name
     */
    public Selection member(String name) {
        long[] dims = dataspace().dimensions();
        if (dataspace().kind() == Dataspace.Kind.NULL) { // no element at all
            return Selection.of(this, SelectedElements.points(new long[0][], dims.length), new long[] {0}).member(name);
        }
        return new Selection(this, new long[dims.length], dims).member(name);
    }

    /** Reads a single-element (scalar or 1-element) integer dataset as an {@code int}. */
    public int readInt() {
        requireSingleElement("readInt");
        return readInts()[0];
    }

    /** Reads a single-element integer dataset as a {@code long}. */
    public long readLong() {
        requireSingleElement("readLong");
        return readLongs()[0];
    }

    /** Reads a single-element floating-point or integer dataset as a {@code double} (see {@link #readDoubles()}). */
    public double readDouble() {
        requireSingleElement("readDouble");
        return readDoubles()[0];
    }

    /** Reads a single-element string dataset. */
    public String readString() {
        requireSingleElement("readString");
        return readStrings()[0];
    }

    private void requireSingleElement(String op) {
        long n = dataspace().elementCount();
        if (n != 1) {
            throw new HdfUnsupportedException(op + " requires a single-element dataset, but " + path() + " has " + n);
        }
    }

    /**
     * Selects a rectangular hyperslab: {@code offset} and {@code count} give the start and size in
     * each dimension. Reading the returned {@link Selection} yields only those elements.
     */
    public Selection select(long[] offset, long[] count) {
        long[] dims = dataspace().dimensions();
        if (offset.length != dims.length || count.length != dims.length) {
            throw new IllegalArgumentException(
                    "selection rank " + offset.length + " does not match dataset rank " + dims.length);
        }
        for (int d = 0; d < dims.length; d++) {
            if (offset[d] < 0 || count[d] < 0 || offset[d] > dims[d] || count[d] > dims[d] - offset[d]) {
                throw new IllegalArgumentException("selection out of bounds in dimension " + d
                        + ": offset=" + offset[d] + " count=" + count[d] + " dim=" + dims[d]);
            }
        }
        return new Selection(this, offset, count);
    }

    /**
     * Selects a regular hyperslab, as libhdf5's {@code H5Sselect_hyperslab} does: in each dimension
     * <i>d</i>, {@code count[d]} blocks of {@code block[d]} indices, the first block at {@code start[d]} and
     * each {@code stride[d]} after the last. A {@code null} stride or block is 1 in every dimension, so
     * {@code select(start, stride, count, null)} takes every {@code stride[d]}th index. The selection reads
     * flattened row-major in the shape of its indices, {@code count[d] * block[d]} in each dimension, and
     * reads only the chunks (or the runs of contiguous data) that hold them.
     *
     * <pre>{@code
     * // every other row of the first 100, and in each the columns 0-3 and 10-13
     * Selection s = dataset.select(new long[] {0, 0}, new long[] {2, 10}, new long[] {50, 2}, new long[] {1, 4});
     * float[] values = s.readFloats(); // 50 x 8 values
     * }</pre>
     *
     * @throws IllegalArgumentException if an argument's rank differs from the dataset's, a start or count
     *         is negative, a stride or block is not positive, blocks overlap (a stride below the block, with
     *         more than one block), or the selection reaches outside the dataset
     */
    public Selection select(long[] start, long[] stride, long[] count, long[] block) {
        long[] dims = dataspace().dimensions();
        int rank = dims.length;
        stride = stride != null ? stride : ones(rank);
        block = block != null ? block : ones(rank);
        if (start.length != rank || stride.length != rank || count.length != rank || block.length != rank) {
            throw new IllegalArgumentException("selection rank does not match dataset rank " + rank);
        }
        long[] shape = new long[rank];
        long[] corner = new long[rank];
        boolean oneBlock = true;
        for (int d = 0; d < rank; d++) {
            String where = " in dimension " + d + ": start=" + start[d] + " stride=" + stride[d] + " count="
                    + count[d] + " block=" + block[d] + " dim=" + dims[d];
            if (start[d] < 0 || count[d] < 0 || stride[d] < 1 || block[d] < 1) {
                throw new IllegalArgumentException("invalid selection" + where);
            }
            if (count[d] > 1 && stride[d] < block[d]) {
                throw new IllegalArgumentException("selection blocks overlap (stride below block)" + where);
            }
            try {
                shape[d] = Math.multiplyExact(count[d], block[d]);
                if (count[d] > 0 && Math.addExact(Math.addExact(start[d], Math.multiplyExact(count[d] - 1, stride[d])),
                        block[d] - 1) >= dims[d]) {
                    throw new IllegalArgumentException("selection out of bounds" + where);
                }
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("selection out of bounds" + where, e);
            }
            corner[d] = Math.min(start[d], dims[d]);
            oneBlock &= count[d] <= 1 || stride[d] == block[d];
        }
        if (oneBlock) {
            return new Selection(this, corner, shape); // one block: read as a rectangular selection
        }
        return Selection.of(this, SelectedElements.hyperslab(start, stride, count, block), shape);
    }

    /**
     * Selects single elements, as libhdf5's {@code H5Sselect_elements} does: {@code coordinates[i]} holds
     * the <i>i</i>th element's coordinates. The selection reads as a flat array, in the order given (a point
     * may be listed more than once), and reads only the chunks the points fall in, each once.
     *
     * <pre>{@code
     * double[] three = dataset.selectPoints(new long[][] {{0, 0}, {512, 7}, {9000, 3}}).readDoubles();
     * }</pre>
     *
     * @throws IllegalArgumentException if a point's rank differs from the dataset's, or it lies outside
     */
    public Selection selectPoints(long[][] coordinates) {
        long[] dims = dataspace().dimensions();
        for (int i = 0; i < coordinates.length; i++) {
            long[] point = coordinates[i];
            if (point.length != dims.length) {
                throw new IllegalArgumentException("point " + i + " has rank " + point.length + ", the dataset " + dims.length);
            }
            for (int d = 0; d < dims.length; d++) {
                if (point[d] < 0 || point[d] >= dims[d]) {
                    throw new IllegalArgumentException("point " + i + " " + java.util.Arrays.toString(point)
                            + " lies outside the dataset's extent " + java.util.Arrays.toString(dims));
                }
            }
        }
        return Selection.of(this, SelectedElements.points(coordinates, dims.length), new long[] {coordinates.length});
    }

    private static long[] ones(int rank) {
        long[] ones = new long[rank];
        java.util.Arrays.fill(ones, 1);
        return ones;
    }

    /**
     * Streams the dataset as blocks of at most {@code blockRows} along the first dimension, each a
     * {@link Selection} spanning the full extent of the remaining dimensions. Reading each block touches
     * only the chunks it overlaps, so a large dataset can be processed block-by-block without
     * materializing it whole. A scalar dataset yields a single (whole) block.
     */
    public java.util.stream.Stream<Selection> blocks(long blockRows) {
        if (blockRows <= 0) {
            throw new IllegalArgumentException("blockRows must be positive: " + blockRows);
        }
        if (dataspace().kind() == Dataspace.Kind.NULL) {
            return java.util.stream.Stream.empty(); // a null dataspace holds no elements
        }
        long[] dims = dataspace().dimensions();
        if (dims.length == 0) {
            return java.util.stream.Stream.of(select(new long[0], new long[0]));
        }
        long dim0 = dims[0];
        long blockCount = dim0 == 0 ? 0 : (dim0 - 1) / blockRows + 1;
        return java.util.stream.LongStream.range(0, blockCount).mapToObj(b -> {
            long start = b * blockRows;
            long[] offset = new long[dims.length];
            offset[0] = start;
            long[] shape = dims.clone();
            shape[0] = Math.min(blockRows, dim0 - start);
            return select(offset, shape);
        });
    }

    // --------------------------------------------------------------- internals

    private DataLayout dataLayout() {
        DataLayout result = layout;
        if (result == null) {
            result = DataLayoutMessage.parse(ctx, require(MessageType.DATA_LAYOUT, "data layout").bodyOffset());
            layout = result;
        }
        return result;
    }

    /**
     * The chunk index, read on the first read that needs it and then kept, so that later selections (each
     * block of {@link #blocks}, say) look their chunks up instead of walking the index again.
     */
    private ChunkIndex chunkIndex(DataLayout.Chunked chunked) {
        ChunkIndex result = chunkIndex;
        if (result == null) {
            Dataspace space = dataspace();
            result = ChunkedReader.readIndex(ctx, chunked, space.dimensions(), space.maxDimensions(), datatype().size());
            chunkIndex = result;
        }
        return result;
    }

    /** The virtual dataset behind this dataset's layout, made on first use and then kept. */
    private VirtualDataset virtual(DataLayout.Virtual layout) {
        VirtualDataset result = virtual;
        if (result == null) {
            result = VirtualDataset.of(ctx, layout);
            virtual = result;
        }
        return result;
    }

    private byte[] fillValue() {
        java.util.Optional<byte[]> result = fillValue;
        if (result == null) {
            HeaderMessage message = header().find(MessageType.FILL_VALUE);
            if (message == null) {
                message = header().find(MessageType.FILL_VALUE_OLD);
            }
            message = message == null ? null : SharedMessage.resolve(ctx, message);
            result = java.util.Optional.ofNullable(message == null ? null
                    : FillValueMessage.parse(message.buffer(), message.bodyOffset(), message.type()));
            fillValue = result;
        }
        return result.orElse(null);
    }

    private FilterPipeline filterPipeline() {
        java.util.Optional<FilterPipeline> result = filterPipeline;
        if (result == null) {
            HeaderMessage message = header().find(MessageType.FILTER_PIPELINE);
            message = message == null ? null : SharedMessage.resolve(ctx, message);
            result = java.util.Optional.ofNullable(message == null ? null
                    : FilterPipelineMessage.parse(message.buffer(), message.bodyOffset()));
            filterPipeline = result;
        }
        return result.orElse(null);
    }

    MemorySegment rawData() {
        return switch (dataLayout()) {
            case DataLayout.Compact c -> {
                requireStoredSize(c.data().length, (long) elementCount() * datatype().size());
                yield MemorySegment.ofArray(c.data());
            }
            case DataLayout.Contiguous c -> {
                long byteCount = (long) elementCount() * datatype().size();
                if (c.size() >= 0 && header().find(MessageType.EXTERNAL_DATA_FILES) == null) {
                    requireStoredSize(c.size(), byteCount); // layout version 3+ records the size
                }
                if (c.address() == HdfBuffer.UNDEFINED_ADDRESS) {
                    HeaderMessage external = header().find(MessageType.EXTERNAL_DATA_FILES);
                    if (external != null) {
                        Path directory = ctx.directory();
                        ExternalFileAccess access = ctx.externalFileAccess();
                        yield MemorySegment.ofArray(ExternalFileList.parse(ctx, external.bodyOffset())
                                .readData((name, position, out, at, length) ->
                                        access.readRawData(name, directory, position, out, at, length), byteCount));
                    }
                    yield fillSegment(byteCount);
                }
                yield ctx.buffer().segmentSlice(c.address(), byteCount);
            }
            case DataLayout.Chunked chunked -> MemorySegment.ofArray(ChunkedReader.assemble(ctx, chunked,
                    chunkIndex(chunked), dataspace().dimensions(), datatype().size(), filterPipeline(), fillValue()));
            case DataLayout.Virtual virtual -> {
                long[] dims = dataspace().dimensions();
                yield MemorySegment.ofArray(virtual(virtual).read(dims, datatype(), fillValue(), new long[dims.length], dims));
            }
        };
    }

    /**
     * The raw bytes of the hyperslab {@code [offset, offset+count)}, flattened row-major. For chunked
     * datasets only the chunks overlapping the selection are read and de-filtered, for contiguous data in
     * this file only the selected runs, and for virtual datasets only the parts of the sources the
     * selection maps to; other layouts extract from the assembled full data.
     */
    MemorySegment selectionData(long[] offset, long[] count) {
        int elementSize = datatype().size();
        long[] dims = dataspace().dimensions();
        DataLayout layout = dataLayout();
        if (layout instanceof DataLayout.Chunked chunked) {
            return MemorySegment.ofArray(ChunkedReader.assembleSelection(ctx, chunked, chunkIndex(chunked), dims,
                    elementSize, filterPipeline(), fillValue(), offset, count));
        }
        if (layout instanceof DataLayout.Virtual v) {
            return MemorySegment.ofArray(virtual(v).read(dims, datatype(), fillValue(), offset, count));
        }
        HdfBuffer block = contiguousBlock(layout);
        if (block != null) {
            return MemorySegment.ofArray(Hyperslab.extract(block, 0, dims, offset, count, elementSize));
        }
        if (isUnwrittenContiguous(layout)) {
            long n = 1;
            for (long c : count) {
                n = Math.multiplyExact(n, c);
            }
            return fillSegment(Elements.checkedByteCount(n, elementSize)); // only the fill value: no need to tile it all
        }
        return MemorySegment.ofArray(Hyperslab.extract(rawData(), dims, offset, count, elementSize));
    }

    /**
     * The raw bytes of {@code elements}, in their order: for chunked data only the chunks that hold them
     * are read, and for contiguous data in this file only their runs; other layouts read the box that
     * bounds them and take the elements from it.
     */
    MemorySegment selectedData(SelectedElements elements) {
        int elementSize = datatype().size();
        long[] dims = dataspace().dimensions();
        if (elements.count() == 0) {
            return MemorySegment.ofArray(new byte[0]);
        }
        DataLayout layout = dataLayout();
        if (layout instanceof DataLayout.Chunked chunked) {
            return MemorySegment.ofArray(ChunkedReader.gather(ctx, chunked, chunkIndex(chunked), dims, elementSize,
                    filterPipeline(), fillValue(), elements));
        }
        HdfBuffer block = contiguousBlock(layout);
        if (block != null) {
            return MemorySegment.ofArray(elements.gather(block::copyTo, new long[dims.length], dims, elementSize));
        }
        long[] low = elements.lowCorner();
        long[] high = elements.highCorner();
        long[] box = new long[dims.length];
        for (int d = 0; d < box.length; d++) {
            box[d] = high[d] - low[d] + 1;
        }
        MemorySegment boxData = selectionData(low, box);
        return MemorySegment.ofArray(elements.gather((from, out, at, length) ->
                MemorySegment.copy(boxData, ValueLayout.JAVA_BYTE, from, out, at, length), low, box, elementSize));
    }

    /**
     * The block of contiguous data stored in this file (bounds-checked, nothing read yet), or null for any
     * other layout, external raw data, or contiguous data never written.
     */
    private HdfBuffer contiguousBlock(DataLayout layout) {
        if (layout instanceof DataLayout.Contiguous c && c.address() != HdfBuffer.UNDEFINED_ADDRESS
                && header().find(MessageType.EXTERNAL_DATA_FILES) == null) {
            long byteCount = byteCount();
            if (c.size() >= 0) {
                requireStoredSize(c.size(), byteCount);
            }
            return ctx.buffer().slice(c.address(), byteCount);
        }
        return null;
    }

    /** True for contiguous data never written (and not external), which reads as the fill value. */
    private boolean isUnwrittenContiguous(DataLayout layout) {
        if (layout instanceof DataLayout.Contiguous c && c.address() == HdfBuffer.UNDEFINED_ADDRESS
                && header().find(MessageType.EXTERNAL_DATA_FILES) == null) {
            if (c.size() >= 0) {
                requireStoredSize(c.size(), byteCount());
            }
            return true;
        }
        return false;
    }

    /** The elements' reader: the dataset's datatype, every element, and its data read only when needed. */
    private ElementReader reader() {
        return new ElementReader(ctx, datatype(), elementCount(), this::rawData, label());
    }

    /**
     * The storage size the layout records must be what the dataspace and datatype imply, as libhdf5
     * checks: otherwise a corrupt dimension could make a read allocate gigabytes of fill or copy past
     * the data.
     */
    private void requireStoredSize(long stored, long needed) {
        if (stored != needed) {
            throw new HdfFormatException("dataset " + path() + " stores " + stored + " bytes but its dataspace and"
                    + " datatype need " + needed + " (invalid dataset size, likely file corruption)");
        }
    }

    /** Builds a byte segment of the fill value tiled to cover the whole (unallocated) dataset. */
    private MemorySegment fillSegment(long byteCount) {
        int elementSize = datatype().size();
        byte[] fill = fillValue();
        byte[] raw = new byte[Elements.checkedInt(byteCount)];
        if (fill != null && fill.length > 0) {
            for (int off = 0; off + elementSize <= raw.length; off += elementSize) {
                System.arraycopy(fill, 0, raw, off, Math.min(elementSize, fill.length));
            }
        }
        return MemorySegment.ofArray(raw);
    }

    /** The bytes every element takes together, as the dataspace and datatype say. */
    private long byteCount() {
        long n = dataspace().elementCount();
        int size = datatype().size();
        if (size != 0 && n > Long.MAX_VALUE / size) {
            throw new HdfFormatException("dataset " + path() + " is too large: " + n + " elements of " + size + " bytes");
        }
        return n * size;
    }

    private int elementCount() {
        long n = dataspace().elementCount();
        if (n > Integer.MAX_VALUE) {
            throw new HdfUnsupportedException("dataset has too many elements for a Java array: " + n);
        }
        return (int) n;
    }

    private HeaderMessage require(int messageType, String label) {
        HeaderMessage message = header().find(messageType);
        if (message == null) {
            throw new HdfFormatException("dataset " + path() + " has no " + label + " message");
        }
        return message;
    }
}
