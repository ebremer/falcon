package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.ChunkedReader;
import com.ebremer.falcon.hdf5.data.Elements;
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
import com.ebremer.falcon.hdf5.message.FillValueMessage;
import java.lang.foreign.MemorySegment;

/**
 * A dataset in the HDF5 hierarchy: a typed, shaped array of elements.
 *
 * <p>Stage H3 reads <b>contiguous</b> and <b>compact</b> storage for atomic datatypes (integers,
 * floats, and fixed-length strings), returning flattened row-major arrays. Chunked storage arrives in
 * stage H4. An unallocated contiguous dataset reads back its fill value.
 */
public final class Dataset extends Hdf5Object {

    private Datatype datatype;
    private Dataspace dataspace;
    private DataLayout layout;
    private FilterPipeline filterPipeline;
    private boolean filterPipelineResolved;
    private byte[] fillValue;
    private boolean fillValueResolved;

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
        if (datatype == null) {
            HeaderMessage message = require(MessageType.DATATYPE, "datatype");
            datatype = DatatypeMessage.resolve(ctx, message.bodyOffset(), SharedMessage.isShared(message));
        }
        return datatype;
    }

    /** This dataset's shape. */
    public Dataspace dataspace() {
        if (dataspace == null) {
            dataspace = DataspaceMessage.parse(ctx, require(MessageType.DATASPACE, "dataspace").bodyOffset());
        }
        return dataspace;
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

    /** The dataset's raw storage bytes (decoded from the layout; not yet de-filtered). */
    public byte[] readRawBytes() {
        return Elements.toRawBytes(rawData(), (long) elementCount() * datatype().size());
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
            default -> throw new HdfUnsupportedException(
                    "reading datatype class " + type.typeClass() + " is not yet supported: " + path());
        };
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
            if (offset[d] < 0 || count[d] < 0 || offset[d] + count[d] > dims[d]) {
                throw new IllegalArgumentException("selection out of bounds in dimension " + d
                        + ": offset=" + offset[d] + " count=" + count[d] + " dim=" + dims[d]);
            }
        }
        return new Selection(this, offset, count);
    }

    // --------------------------------------------------------------- internals

    private DataLayout layout() {
        if (layout == null) {
            layout = DataLayoutMessage.parse(ctx, require(MessageType.DATA_LAYOUT, "data layout").bodyOffset());
        }
        return layout;
    }

    private byte[] fillValue() {
        if (!fillValueResolved) {
            HeaderMessage message = header().find(MessageType.FILL_VALUE);
            if (message == null) {
                message = header().find(MessageType.FILL_VALUE_OLD);
            }
            fillValue = message == null ? null
                    : FillValueMessage.parse(ctx.buffer(), message.bodyOffset(), message.type());
            fillValueResolved = true;
        }
        return fillValue;
    }

    private FilterPipeline filterPipeline() {
        if (!filterPipelineResolved) {
            HeaderMessage message = header().find(MessageType.FILTER_PIPELINE);
            filterPipeline = message == null ? null
                    : FilterPipelineMessage.parse(ctx.buffer(), message.bodyOffset());
            filterPipelineResolved = true;
        }
        return filterPipeline;
    }

    MemorySegment rawData() {
        return switch (layout()) {
            case DataLayout.Compact c -> MemorySegment.ofArray(c.data());
            case DataLayout.Contiguous c -> {
                long byteCount = (long) elementCount() * datatype().size();
                if (c.address() == HdfBuffer.UNDEFINED_ADDRESS) {
                    yield fillSegment(byteCount);
                }
                yield ctx.buffer().segment().asSlice(c.address(), byteCount);
            }
            case DataLayout.Chunked chunked -> {
                long[] dims = dataspace().dimensions();
                byte[] assembled = ChunkedReader.assemble(
                        ctx, chunked, dims, datatype().size(), filterPipeline(), fillValue());
                yield MemorySegment.ofArray(assembled);
            }
        };
    }

    /** Builds a byte segment of the fill value tiled to cover the whole (unallocated) dataset. */
    private MemorySegment fillSegment(long byteCount) {
        int elementSize = datatype().size();
        byte[] fill = fillValue();
        byte[] raw = new byte[Math.toIntExact(byteCount)];
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
