package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.ChunkedReader;
import com.ebremer.falcon.hdf5.data.Elements;
import com.ebremer.falcon.hdf5.data.Hyperslab;
import com.ebremer.falcon.hdf5.data.VlenSequences;
import com.ebremer.falcon.hdf5.data.VlenStrings;
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
                long[] dims = VirtualDataset.extent(ctx, virtual, result.dimensions());
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
            case DataLayout.Chunked chunked -> ChunkedReader.storedBytes(ctx, chunked, dataspace().dimensions(),
                    dataspace().maxDimensions(), datatype().size());
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
     * wrapping.
     *
     * @throws HdfUnsupportedException if the datatype is not an integer type or a value does not fit
     */
    public int[] readInts() {
        return Elements.toInts(rawData(), elementCount(), datatype());
    }

    /**
     * Reads every element of an integer dataset as a {@code long}. Values are exact: a {@code uint64}
     * of 2<sup>63</sup> or more throws rather than reading as negative ({@link #read()} returns
     * {@code BigInteger[]} for {@code uint64}).
     *
     * @throws HdfUnsupportedException if the datatype is not an integer type or a value does not fit
     */
    public long[] readLongs() {
        return Elements.toLongs(rawData(), elementCount(), datatype());
    }

    /**
     * Reads every element of a floating-point or integer dataset as a {@code float}, converted as libhdf5
     * converts to {@code H5T_NATIVE_FLOAT}: a wider float, or an integer with more than 24 significant
     * bits, is rounded to the nearest {@code float}.
     *
     * @throws HdfUnsupportedException if the datatype is neither floating-point nor an integer type
     */
    public float[] readFloats() {
        return Elements.toFloats(rawData(), elementCount(), datatype());
    }

    /**
     * Reads every element of a floating-point or integer dataset as a {@code double}, converted as libhdf5
     * converts to {@code H5T_NATIVE_DOUBLE}: integers are exact up to 53 significant bits, and wider ones
     * (large {@code int64} and {@code uint64} values) are rounded to the nearest {@code double}.
     *
     * @throws HdfUnsupportedException if the datatype is neither floating-point nor an integer type
     */
    public double[] readDoubles() {
        return Elements.toDoubles(rawData(), elementCount(), datatype());
    }

    /** Reads every element of a fixed-length or variable-length string datatype. */
    public String[] readStrings() {
        Datatype type = datatype();
        if (type instanceof Datatype.VariableLength vlen && vlen.kind() == Datatype.VlenKind.STRING) {
            return VlenStrings.read(ctx, rawData(), elementCount(), vlen);
        }
        return Elements.toStrings(rawData(), elementCount(), type);
    }

    /** Reads a variable-length sequence (ragged) datatype, one {@code int[]} row per element. */
    public int[][] readVlenInts() {
        return VlenSequences.toInts(ctx, rawData(), elementCount(), requireVlenSequence());
    }

    /** Reads a variable-length sequence (ragged) datatype, one {@code long[]} row per element. */
    public long[][] readVlenLongs() {
        return VlenSequences.toLongs(ctx, rawData(), elementCount(), requireVlenSequence());
    }

    /** Reads a variable-length sequence (ragged) datatype, one {@code double[]} row per element. */
    public double[][] readVlenDoubles() {
        return VlenSequences.toDoubles(ctx, rawData(), elementCount(), requireVlenSequence());
    }

    /** Reads a variable-length sequence (ragged) datatype, one {@code float[]} row per element. */
    public float[][] readVlenFloats() {
        return VlenSequences.toFloats(ctx, rawData(), elementCount(), requireVlenSequence());
    }

    /** Reads a vlen sequence into its most natural boxed 2-D array, by base type. */
    private Object readVlenSequence(Datatype.VariableLength vlen) {
        return switch (vlen.base()) {
            case Datatype.FixedPoint fp -> fp.size() <= 4 && Elements.fitsInt(fp) ? readVlenInts() : readVlenLongs();
            case Datatype.FloatingPoint fp -> readVlenDoubles();
            default -> throw new HdfUnsupportedException(
                    "reading variable-length sequences of " + vlen.base().typeClass() + " is not yet supported: " + path());
        };
    }

    private Datatype.VariableLength requireVlenSequence() {
        if (datatype() instanceof Datatype.VariableLength vlen && vlen.kind() == Datatype.VlenKind.SEQUENCE) {
            return vlen;
        }
        throw new HdfUnsupportedException(
                "readVlen* requires a variable-length sequence datatype: " + path());
    }

    /**
     * Reads an object-reference dataset, resolving each element to the object it points at (a group,
     * dataset, or committed datatype), or {@code null} for a null reference.
     *
     * <p>Revised references (HDF5 1.12's {@code H5R_ref_t}) are read too, whatever each element holds: an
     * object reference resolves to its object, a region reference to its dataset, and an attribute
     * reference to the object the attribute is on.
     *
     * @throws HdfUnsupportedException for a revised reference into another file, which Falcon does not
     *         follow
     */
    public Hdf5Object[] readObjectReferences() {
        Datatype type = datatype();
        boolean revised = isRevisedReference(type);
        if (!revised && !isReference(type, Datatype.ReferenceKind.OBJECT)) {
            throw new HdfUnsupportedException("readObjectReferences requires an object-reference datatype: " + path());
        }
        return resolveObjectReferences(ctx, rawData(), elementCount(), type.size(), revised);
    }

    /**
     * Reads a region-reference dataset, resolving each element to a {@link Selection} of the dataset it
     * points into (or {@code null} for a null reference). Read the selection to get the referenced data.
     *
     * <p>Revised references (HDF5 1.12's {@code H5R_ref_t}) are read too. An element that is an object or
     * attribute reference, or points into another file, becomes a selection that throws when used.
     */
    public Selection[] readRegionReferences() {
        Datatype type = datatype();
        boolean revised = isRevisedReference(type);
        if (!revised && !isReference(type, Datatype.ReferenceKind.DATASET_REGION)) {
            throw new HdfUnsupportedException("readRegionReferences requires a region-reference datatype: " + path());
        }
        return resolveRegionReferences(ctx, rawData(), elementCount(), type.size(), revised);
    }

    /**
     * Reads a dataset of revised attribute references (HDF5 1.12's {@code H5R_ATTR}), resolving each
     * element to the attribute it names, or {@code null} for a null reference.
     *
     * @throws HdfUnsupportedException if the datatype is not a revised reference, or an element is not an
     *         attribute reference or points into another file
     * @throws HdfFormatException if a referenced attribute does not exist
     */
    public Attribute[] readAttributeReferences() {
        Datatype type = datatype();
        if (!isRevisedReference(type)) {
            throw new HdfUnsupportedException("readAttributeReferences requires a revised reference datatype: " + path());
        }
        return resolveAttributeReferences(ctx, rawData(), elementCount(), type.size());
    }

    /** The dataset's element bytes as stored, in the datatype's byte order (chunk filters already undone). */
    public byte[] readRawBytes() {
        return Elements.toRawBytes(rawData(), (long) elementCount() * datatype().size());
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
     * Reads the whole dataset into the most natural Java array: for integers, {@code int[]} when every
     * value of the type fits in an {@code int}, {@code long[]} when it fits in a {@code long} (so
     * {@code uint32} reads as {@code long[]}), and {@code BigInteger[]} for {@code uint64};
     * {@code double[]} for floats; {@code String[]} for strings; {@code Selection[]} for region references,
     * and {@code Hdf5Object[]} for object references and revised references (see
     * {@link #readObjectReferences()}).
     */
    public Object read() {
        Datatype type = datatype();
        return switch (type) {
            case Datatype.FixedPoint fp -> Elements.toNaturalIntegers(rawData(), elementCount(), fp);
            case Datatype.FloatingPoint fp -> readDoubles();
            case Datatype.StringType st -> readStrings();
            case Datatype.VariableLength v when v.kind() == Datatype.VlenKind.STRING -> readStrings();
            case Datatype.VariableLength v -> readVlenSequence(v);
            case Datatype.Reference r when r.kind() == Datatype.ReferenceKind.DATASET_REGION -> readRegionReferences();
            case Datatype.Reference r when r.kind() != Datatype.ReferenceKind.OTHER -> readObjectReferences();
            default -> throw new HdfUnsupportedException(
                    "reading datatype class " + type.typeClass() + " is not yet supported: " + path());
        };
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
                        yield MemorySegment.ofArray(ExternalFileList.parse(ctx, external.bodyOffset())
                                .readData(name -> ctx.externalFileAccess().resolve(name, directory,
                                        "external raw data file"), byteCount));
                    }
                    yield fillSegment(byteCount);
                }
                yield ctx.buffer().segmentSlice(c.address(), byteCount);
            }
            case DataLayout.Chunked chunked -> {
                Dataspace space = dataspace();
                byte[] assembled = ChunkedReader.assemble(ctx, chunked, space.dimensions(), space.maxDimensions(),
                        datatype().size(), filterPipeline(), fillValue());
                yield MemorySegment.ofArray(assembled);
            }
            case DataLayout.Virtual virtual -> {
                byte[] assembled = VirtualDataset.assemble(
                        ctx, virtual, dataspace().dimensions(), datatype(), fillValue());
                yield MemorySegment.ofArray(assembled);
            }
        };
    }

    /**
     * The raw bytes of the hyperslab {@code [offset, offset+count)}, flattened row-major. For chunked
     * datasets only the chunks overlapping the selection are read and de-filtered, and for contiguous
     * data in this file only the selected runs; other layouts extract from the assembled full data.
     */
    MemorySegment selectionData(long[] offset, long[] count) {
        int elementSize = datatype().size();
        long[] dims = dataspace().dimensions();
        DataLayout layout = dataLayout();
        if (layout instanceof DataLayout.Chunked chunked) {
            return MemorySegment.ofArray(ChunkedReader.assembleSelection(ctx, chunked, dims,
                    dataspace().maxDimensions(), elementSize, filterPipeline(), fillValue(), offset, count));
        }
        if (layout instanceof DataLayout.Contiguous c && c.address() != HdfBuffer.UNDEFINED_ADDRESS
                && header().find(MessageType.EXTERNAL_DATA_FILES) == null) {
            long byteCount = byteCount();
            if (c.size() >= 0) {
                requireStoredSize(c.size(), byteCount);
            }
            HdfBuffer block = ctx.buffer().slice(c.address(), byteCount); // bounds-checked; reads nothing yet
            return MemorySegment.ofArray(Hyperslab.extract(block, 0, dims, offset, count, elementSize));
        }
        return MemorySegment.ofArray(Hyperslab.extract(rawData(), dims, offset, count, elementSize));
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
