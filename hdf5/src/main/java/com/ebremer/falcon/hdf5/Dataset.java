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
 * <p>Stage H3 reads <b>contiguous</b> and <b>compact</b> storage for atomic datatypes (integers,
 * floats, and fixed-length strings), returning flattened row-major arrays. Chunked storage arrives in
 * stage H4. An unallocated contiguous dataset reads back its fill value.
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
        Datatype result = datatype;
        if (result == null) {
            HeaderMessage message = require(MessageType.DATATYPE, "datatype");
            result = DatatypeMessage.resolve(ctx, message.bodyOffset(), SharedMessage.isShared(message));
            datatype = result;
        }
        return result;
    }

    /** This dataset's shape. */
    public Dataspace dataspace() {
        Dataspace result = dataspace;
        if (result == null) {
            result = DataspaceMessage.parse(ctx, require(MessageType.DATASPACE, "dataspace").bodyOffset());
            dataspace = result;
        }
        return result;
    }

    // ------------------------------------------------------------------ reads

    /** Reads every element as {@code int} (fixed-point datatypes up to 4 bytes). */
    public int[] readInts() {
        return Elements.toInts(rawData(), elementCount(), datatype());
    }

    /** Reads every element as {@code long} (fixed-point datatypes up to 8 bytes). */
    public long[] readLongs() {
        return Elements.toLongs(rawData(), elementCount(), datatype());
    }

    /** Reads every element as {@code float} (floating-point datatypes). */
    public float[] readFloats() {
        return Elements.toFloats(rawData(), elementCount(), datatype());
    }

    /** Reads every element as {@code double} (floating-point datatypes). */
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
            case Datatype.FixedPoint fp -> fp.size() <= 4 ? readVlenInts() : readVlenLongs();
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
     */
    public Hdf5Object[] readObjectReferences() {
        Datatype type = datatype();
        if (!(type instanceof Datatype.Reference ref) || ref.kind() != Datatype.ReferenceKind.OBJECT) {
            throw new HdfUnsupportedException("readObjectReferences requires an object-reference datatype: " + path());
        }
        return resolveObjectReferences(ctx, rawData(), elementCount(), type.size());
    }

    /**
     * Reads a region-reference dataset, resolving each element to a {@link Selection} of the dataset it
     * points into (or {@code null} for a null reference). Read the selection to get the referenced data.
     */
    public Selection[] readRegionReferences() {
        Datatype type = datatype();
        if (!(type instanceof Datatype.Reference ref) || ref.kind() != Datatype.ReferenceKind.DATASET_REGION) {
            throw new HdfUnsupportedException("readRegionReferences requires a region-reference datatype: " + path());
        }
        return resolveRegionReferences(ctx, rawData(), elementCount(), type.size());
    }

    /** The dataset's raw storage bytes (decoded from the layout; not yet de-filtered). */
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
     * Reads the whole dataset into the most natural Java array: {@code int[]}/{@code long[]} for
     * integers, {@code double[]} for floats, {@code String[]} for fixed-length strings.
     */
    public Object read() {
        Datatype type = datatype();
        return switch (type) {
            case Datatype.FixedPoint fp -> fp.size() <= 4 ? readInts() : readLongs();
            case Datatype.FloatingPoint fp -> readDoubles();
            case Datatype.StringType st -> readStrings();
            case Datatype.VariableLength v when v.kind() == Datatype.VlenKind.STRING -> readStrings();
            case Datatype.VariableLength v -> readVlenSequence(v);
            case Datatype.Reference r when r.kind() == Datatype.ReferenceKind.OBJECT -> readObjectReferences();
            case Datatype.Reference r when r.kind() == Datatype.ReferenceKind.DATASET_REGION -> readRegionReferences();
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

    /** Reads a single-element floating-point dataset as a {@code double}. */
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

    private DataLayout layout() {
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
            result = java.util.Optional.ofNullable(message == null ? null
                    : FillValueMessage.parse(ctx.buffer(), message.bodyOffset(), message.type()));
            fillValue = result;
        }
        return result.orElse(null);
    }

    private FilterPipeline filterPipeline() {
        java.util.Optional<FilterPipeline> result = filterPipeline;
        if (result == null) {
            HeaderMessage message = header().find(MessageType.FILTER_PIPELINE);
            result = java.util.Optional.ofNullable(message == null ? null
                    : FilterPipelineMessage.parse(ctx.buffer(), message.bodyOffset()));
            filterPipeline = result;
        }
        return result.orElse(null);
    }

    MemorySegment rawData() {
        return switch (layout()) {
            case DataLayout.Compact c -> MemorySegment.ofArray(c.data());
            case DataLayout.Contiguous c -> {
                long byteCount = (long) elementCount() * datatype().size();
                if (c.address() == HdfBuffer.UNDEFINED_ADDRESS) {
                    HeaderMessage external = header().find(MessageType.EXTERNAL_DATA_FILES);
                    if (external != null) {
                        Path directory = ctx.path() == null ? null : ctx.path().getParent();
                        yield MemorySegment.ofArray(ExternalFileList.parse(ctx, external.bodyOffset())
                                .readData(directory, byteCount));
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
                        ctx, virtual, dataspace().dimensions(), datatype().size(), fillValue());
                yield MemorySegment.ofArray(assembled);
            }
        };
    }

    /**
     * The raw bytes of the hyperslab {@code [offset, offset+count)}, flattened row-major. For chunked
     * datasets only the chunks overlapping the selection are read and de-filtered; other layouts extract
     * from the (zero-copy or already-assembled) full data.
     */
    MemorySegment selectionData(long[] offset, long[] count) {
        int elementSize = datatype().size();
        long[] dims = dataspace().dimensions();
        if (layout() instanceof DataLayout.Chunked chunked) {
            return MemorySegment.ofArray(ChunkedReader.assembleSelection(ctx, chunked, dims,
                    dataspace().maxDimensions(), elementSize, filterPipeline(), fillValue(), offset, count));
        }
        return MemorySegment.ofArray(Hyperslab.extract(rawData(), dims, offset, count, elementSize));
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
