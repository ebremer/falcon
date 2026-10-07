package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Dataset;
import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.Group;
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.hdf5.Hdf5Object;
import com.ebremer.falcon.hdf5.Link;
import com.ebremer.falcon.hdf5.Selection;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An HDF5 file, or a group or dataset of it, written as a Zarr hierarchy: each group a group, each dataset
 * an array, with their attributes. Each element type maps to the Zarr type of the same values:
 * <ul>
 *   <li>integers, IEEE floats, h5py's booleans and complex numbers, bit fields, and h5py's numpy times keep
 *       their bytes and their byte order;</li>
 *   <li>enumerations their integer values; HDF5 times become {@code datetime64[s]};</li>
 *   <li>strings, fixed-length or variable, become variable-length strings, and variable-length sequences of
 *       bytes {@code variable_length_bytes};</li>
 *   <li>opaque elements {@code raw_bytes}, and compounds structs (fixed strings in them
 *       {@code null_terminated_bytes});</li>
 *   <li>an array element type adds its dimensions to the array's;</li>
 *   <li>other integer and float layouts (12 bits of 16, bfloat16, x87 extended) become the plain type their
 *       values fit.</li>
 * </ul>
 * Left out, each with a warning: soft, external, and user-defined links (Zarr has no links), a second link
 * to an object already written, committed datatypes, references, sequences of anything but bytes, null
 * dataspaces, and names Zarr refuses.
 */
final class Hdf5ToZarr {

    private final Context context;
    private final ConvertSettings settings;
    private final Set<Long> written = new HashSet<>();
    int arrays;
    int groups;

    Hdf5ToZarr(Context context, ConvertSettings settings) {
        this.context = context;
        this.settings = settings;
    }

    /** What an HDF5 type can't be in Zarr. */
    static final class Unsupported extends Exception {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    /** How a dataset's elements are read and written. */
    enum Transfer {
        /** Element bytes as they are, in the Zarr array's byte order. */
        RAW,
        /** Compound records repacked into little-endian structs. */
        STRUCT,
        /** Strings. */
        STRINGS,
        /** Sequences of bytes, as byte arrays. */
        BYTE_ROWS,
        /** Integers, as {@code long}s. */
        LONGS,
        /** Floats, as {@code float}s. */
        FLOATS,
        /** Floats, as {@code double}s. */
        DOUBLES
    }

    /**
     * A copy, from an HDF5 record into a packed little-endian struct, of one field's bytes.
     *
     * @param source where the bytes start in the record
     * @param target where they go in the struct
     * @param length how many
     * @param unit   the size of each number in them to reverse, to make it little-endian; 1 for none
     */
    record Copy(int source, int target, int length, int unit) {
    }

    /**
     * A Zarr element type for an HDF5 one, and how to carry the elements over.
     *
     * @param type     the Zarr type
     * @param order    the byte order of the Zarr array (an HDF5 type's own, to keep its bytes)
     * @param transfer how elements are carried
     * @param copies   for {@link Transfer#STRUCT}, the fields' copies
     */
    record Mapping(DataType type, ByteOrder order, Transfer transfer, List<Copy> copies) {
        Mapping(DataType type, ByteOrder order, Transfer transfer) {
            this(type, order, transfer, List.of());
        }
    }

    /**
     * Writes the object at {@code path} of {@code file}, and everything below it, to the root of {@code store}.
     *
     * @param file      the HDF5 file
     * @param path      the group or dataset to write
     * @param store     the Zarr store
     * @param overwrite whether to replace what the store holds
     * @throws Exception if the conversion fails
     */
    void convert(Hdf5File file, String path, Store store, boolean overwrite) throws Exception {
        Hdf5Object start = Hdf5Inspect.resolve(file, path);
        switch (start) {
            case Group group -> {
                ZarrGroup root = Zarr.createGroup(store, AttributeJson.fromHdf5(context, group), overwrite,
                        settings.zarrFormat());
                written.add(group.objectHeaderAddress());
                groups++;
                children(group, group.path(), root);
            }
            case Dataset dataset -> {
                if (!array(dataset, "/", spec -> Zarr.createArray(store, spec, overwrite))) {
                    throw new UsageException(dataset.path() + " cannot be written to Zarr (see the warning)");
                }
            }
            default -> throw new UsageException(start.path() + " is a committed datatype: there is nothing to convert");
        }
    }

    private void children(Group group, String path, ZarrGroup target) throws Exception {
        for (Link link : Hdf5Inspect.sorted(group.links())) {
            String name = link.name();
            String childPath = Hdf5Inspect.join(path, name);
            switch (link) {
                case Link.Soft s -> context.warn("left out " + childPath + ": a soft link (to " + s.targetPath()
                        + "); Zarr has no links");
                case Link.External e -> context.warn("left out " + childPath + ": an external link (to "
                        + e.fileName() + ":" + e.objectPath() + "); Zarr has no links");
                case Link.UserDefined u -> context.warn("left out " + childPath + ": a user-defined link");
                case Link.Hard h -> {
                    Hdf5Object child;
                    try {
                        child = group.child(name).orElse(null);
                    } catch (RuntimeException e) {
                        context.warn("left out " + childPath + ": " + Errors.describe(e));
                        continue;
                    }
                    if (child == null) {
                        continue;
                    }
                    if (!written.add(child.objectHeaderAddress())) {
                        context.warn("left out " + childPath + ": another link to an object written already");
                        continue;
                    }
                    switch (child) {
                        case Group g -> {
                            ZarrGroup created;
                            try {
                                created = target.createGroup(name, AttributeJson.fromHdf5(context, g));
                            } catch (IllegalArgumentException e) {
                                context.warn("left out " + childPath + ": " + e.getMessage());
                                continue;
                            }
                            groups++;
                            children(g, childPath, created);
                        }
                        case Dataset d -> array(d, childPath, spec -> target.createArray(name, spec));
                        default -> context.warn("left out " + childPath + ": a committed datatype");
                    }
                }
            }
        }
    }

    /** Creates an array from its spec: under a group, or at the store's root. */
    interface Creator {
        ZarrArray create(ArraySpec spec);
    }

    /** Writes one dataset; false (with a warning) if it cannot be. */
    private boolean array(Dataset dataset, String path, Creator creator) throws Exception {
        Dataspace space = dataset.dataspace();
        Datatype type = dataset.datatype();
        if (space.kind() == Dataspace.Kind.NULL) {
            context.warn("left out " + path + ": a null dataspace, which holds no elements");
            return false;
        }
        int[] extra = type instanceof Datatype.Array a ? a.dimensions() : new int[0];
        Datatype element = type instanceof Datatype.Array a ? a.base() : type;
        Mapping mapping;
        try {
            mapping = map(element);
            if (extra.length > 0 && mapping.transfer() != Transfer.RAW) {
                throw new Unsupported("arrays of " + Describe.type(element) + " elements");
            }
        } catch (Unsupported e) {
            context.warn("left out " + path + ": Zarr has no type for " + e.getMessage());
            return false;
        }
        long[] dims = space.dimensions();
        long[] shape = concat(dims, extra);
        ArraySpec.Builder spec = ArraySpec.builder(shape, mapping.type()).zarrFormat(settings.zarrFormat())
                .attributes(AttributeJson.fromHdf5(context, dataset));
        if (mapping.type().hasByteOrder()) {
            spec.endian(mapping.order());
        }
        long[] chunks = null;
        if (shape.length > 0) {
            int elementSize = mapping.type().isVariableLength() ? 16 : mapping.type().byteCount();
            chunks = dataset.chunkShape().filter(c -> !settings.autoChunks()).map(c -> concat(c, extra))
                    .orElseGet(() -> Chunking.ZARR.guess(shape, elementSize));
            chunks = Chunking.clamp(chunks, shape);
            spec.chunkShape(chunks);
        }
        JsonValue fill = fill(dataset, mapping, type);
        if (fill != null) {
            spec.fillValue(fill);
        }
        Compression compression = Compression.choose(settings.compression(), Compression.fromHdf5(dataset.filters()), true);
        compression.applyTo(spec, settings.zarrFormat());
        ZarrArray array;
        try {
            array = creator.create(spec.build());
        } catch (IllegalArgumentException e) {
            context.warn("left out " + path + ": " + e.getMessage());
            return false;
        }
        copy(dataset, mapping, dims, extra, array, chunks);
        arrays++;
        if (!settings.quiet()) {
            context.out.println(path + "  " + Describe.type(mapping.type(), mapping.order()) + " "
                    + Describe.shape(shape) + (chunks == null ? "" : "  chunks " + Describe.shape(chunks)) + "  "
                    + compression.describe());
        }
        return true;
    }

    /** The fill value in Zarr's JSON, for the types that keep their bytes; null for the default. */
    private static JsonValue fill(Dataset dataset, Mapping mapping, Datatype type) {
        if (mapping.transfer() != Transfer.RAW && mapping.transfer() != Transfer.STRUCT) {
            return null;
        }
        byte[] bytes = dataset.fillValueBytes().orElse(null);
        if (bytes == null) {
            return null;
        }
        try {
            if (mapping.transfer() == Transfer.STRUCT) {
                return mapping.type().encodeFillValue(repack(bytes, type.size(), mapping.type().byteCount(),
                        mapping.copies()), ByteOrder.LITTLE_ENDIAN);
            }
            // an array element type's fill is a whole array: each of its elements is the first's
            return mapping.type().encodeFillValue(Arrays.copyOf(bytes, mapping.type().byteCount()), mapping.order());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- types --------------------------------------------------------------------------------------------

    /**
     * {@return the Zarr type of an HDF5 element type, and how to carry its elements}
     *
     * @param type the HDF5 type
     * @throws Unsupported if Zarr has no such type
     */
    static Mapping map(Datatype type) throws Unsupported {
        return switch (type) {
            case Datatype.FixedPoint fp when Describe.standardInteger(fp) ->
                    new Mapping(integer(fp.size(), fp.signed()), fp.byteOrder(), Transfer.RAW);
            case Datatype.FixedPoint fp ->
                    new Mapping(integer(wider(fp.size()), fp.signed()), ByteOrder.LITTLE_ENDIAN, Transfer.LONGS);
            case Datatype.FloatingPoint fp when Describe.standardFloat(fp) ->
                    new Mapping(floating(fp.size()), fp.byteOrder(), Transfer.RAW);
            case Datatype.FloatingPoint fp when fp.size() <= 4 ->
                    new Mapping(DataType.FLOAT32, ByteOrder.LITTLE_ENDIAN, Transfer.FLOATS);
            case Datatype.FloatingPoint fp -> new Mapping(DataType.FLOAT64, ByteOrder.LITTLE_ENDIAN, Transfer.DOUBLES);
            case Datatype.Enumeration e when Hdf5Values.isBool(e) ->
                    new Mapping(DataType.BOOL, ByteOrder.LITTLE_ENDIAN, Transfer.RAW);
            case Datatype.Enumeration e -> map(e.base());
            case Datatype.BitField b when b.bitOffset() == 0 && b.bitPrecision() == 8 * b.size()
                    && (b.size() == 1 || b.size() == 2 || b.size() == 4 || b.size() == 8) ->
                    new Mapping(integer(b.size(), false), b.byteOrder(), Transfer.RAW);
            case Datatype.BitField b ->
                    new Mapping(integer(wider(b.size()), false), ByteOrder.LITTLE_ENDIAN, Transfer.LONGS);
            case Datatype.Time t when t.size() == 8 && t.bitPrecision() == 64 ->
                    new Mapping(DataType.datetime64("s", 1), t.byteOrder(), Transfer.RAW);
            case Datatype.Time t -> new Mapping(DataType.datetime64("s", 1), ByteOrder.LITTLE_ENDIAN, Transfer.LONGS);
            case Datatype.StringType s -> new Mapping(DataType.STRING, ByteOrder.LITTLE_ENDIAN, Transfer.STRINGS);
            case Datatype.VariableLength v when v.kind() == Datatype.VlenKind.STRING ->
                    new Mapping(DataType.STRING, ByteOrder.LITTLE_ENDIAN, Transfer.STRINGS);
            case Datatype.VariableLength v when v.base() instanceof Datatype.FixedPoint fp && fp.size() == 1 ->
                    new Mapping(DataType.BYTES, ByteOrder.LITTLE_ENDIAN, Transfer.BYTE_ROWS);
            case Datatype.Opaque o when NumpyTime.of(o) != null ->
                    new Mapping(NumpyTime.of(o).zarr(), NumpyTime.of(o).order(), Transfer.RAW);
            case Datatype.Opaque o -> new Mapping(DataType.rawBytes(o.size()), ByteOrder.LITTLE_ENDIAN, Transfer.RAW);
            case Datatype.Complex c when c.base() instanceof Datatype.FloatingPoint f && Describe.standardFloat(f)
                    && f.size() >= 4 -> new Mapping(f.size() == 4 ? DataType.COMPLEX64 : DataType.COMPLEX128,
                    f.byteOrder(), Transfer.RAW);
            case Datatype.Compound c when Hdf5Values.complexParts(c) != null
                    && Describe.standardFloat(Hdf5Values.complexParts(c)) && Hdf5Values.complexParts(c).size() >= 4 ->
                    new Mapping(Hdf5Values.complexParts(c).size() == 4 ? DataType.COMPLEX64 : DataType.COMPLEX128,
                            Hdf5Values.complexParts(c).byteOrder(), Transfer.RAW);
            case Datatype.Compound c -> {
                List<Copy> copies = new ArrayList<>();
                DataType struct = struct(c, 0, 0, copies);
                yield new Mapping(struct, ByteOrder.LITTLE_ENDIAN, Transfer.STRUCT, List.copyOf(copies));
            }
            default -> throw new Unsupported(Describe.type(type));
        };
    }

    /** A compound as a packed struct, its fields' copies added to {@code copies}. */
    private static DataType struct(Datatype.Compound compound, int source, int target, List<Copy> copies)
            throws Unsupported {
        List<DataType.Field> fields = new ArrayList<>();
        int at = target;
        for (Datatype.Compound.Member member : compound.members()) {
            DataType field = field(member.type(), source + member.offset(), at, copies);
            fields.add(new DataType.Field(member.name(), field));
            at += field.byteCount();
        }
        if (fields.isEmpty()) {
            throw new Unsupported("a compound with no members");
        }
        return DataType.struct(fields);
    }

    /** A compound member as a struct field: its type, its copy added to {@code copies}. */
    private static DataType field(Datatype type, int source, int target, List<Copy> copies) throws Unsupported {
        switch (type) {
            case Datatype.Compound c when Hdf5Values.complexParts(c) == null -> {
                return struct(c, source, target, copies);
            }
            case Datatype.StringType s -> {
                copies.add(new Copy(source, target, s.size(), 1));
                return DataType.nullTerminatedBytes(s.size());
            }
            default -> {
                Mapping m = map(type);
                if (m.transfer() != Transfer.RAW) {
                    throw new Unsupported("compounds of " + Describe.type(type) + " members");
                }
                int size = m.type().byteCount();
                int unit = 1;
                if (m.order() == ByteOrder.BIG_ENDIAN) {
                    unit = m.type().kind() == com.ebremer.falcon.zarr.datatype.DataTypeKind.COMPLEX ? size / 2 : size;
                }
                copies.add(new Copy(source, target, size, unit));
                return m.type();
            }
        }
    }

    private static DataType integer(int size, boolean signed) {
        return switch (size) {
            case 1 -> signed ? DataType.INT8 : DataType.UINT8;
            case 2 -> signed ? DataType.INT16 : DataType.UINT16;
            case 4 -> signed ? DataType.INT32 : DataType.UINT32;
            default -> signed ? DataType.INT64 : DataType.UINT64;
        };
    }

    private static int wider(int size) {
        return size <= 1 ? 1 : size <= 2 ? 2 : size <= 4 ? 4 : 8;
    }

    private static DataType floating(int size) {
        return switch (size) {
            case 2 -> DataType.FLOAT16;
            case 4 -> DataType.FLOAT32;
            default -> DataType.FLOAT64;
        };
    }

    /** HDF5 records repacked into little-endian structs. */
    static byte[] repack(byte[] records, int recordSize, int structSize, List<Copy> copies) {
        int n = records.length / recordSize;
        byte[] out = new byte[n * structSize];
        for (int e = 0; e < n; e++) {
            int from = e * recordSize;
            int to = e * structSize;
            for (Copy c : copies) {
                if (c.unit() <= 1) {
                    System.arraycopy(records, from + c.source(), out, to + c.target(), c.length());
                } else {
                    for (int u = 0; u < c.length(); u += c.unit()) {
                        for (int b = 0; b < c.unit(); b++) {
                            out[to + c.target() + u + b] = records[from + c.source() + u + c.unit() - 1 - b];
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---- data ---------------------------------------------------------------------------------------------

    private void copy(Dataset dataset, Mapping mapping, long[] dims, int[] extra, ZarrArray array, long[] chunks)
            throws Exception {
        long[] extraDims = Arrays.stream(extra).asLongStream().toArray();
        int elementSize = (mapping.type().isVariableLength() ? 64 : mapping.type().byteCount())
                * (int) Math.max(1, Blocks.elements(extraDims));
        long[] unit = chunks == null ? new long[0] : Arrays.copyOf(chunks, dims.length);
        Datatype type = dataset.datatype();
        Blocks.copy(dims, unit, elementSize, settings.threads(), false,
                (offset, count) -> read(dims.length == 0 ? null : dataset.select(offset, count), dataset, mapping, type),
                (offset, count, data) -> write(dims.length == 0 ? array.selectAll()
                        : array.select(concat(offset, new long[extra.length]), concat(count, extraDims)), mapping, data));
    }

    private static Object read(Selection selection, Dataset dataset, Mapping mapping, Datatype type) {
        return switch (mapping.transfer()) {
            case RAW -> selection == null ? dataset.readRawBytes() : selection.readRawBytes();
            case STRUCT -> repack(selection == null ? dataset.readRawBytes() : selection.readRawBytes(), type.size(),
                    mapping.type().byteCount(), mapping.copies());
            case STRINGS -> selection == null ? dataset.readStrings() : selection.readStrings();
            case LONGS -> selection == null ? dataset.readLongs() : selection.readLongs();
            case FLOATS -> selection == null ? dataset.readFloats() : selection.readFloats();
            case DOUBLES -> selection == null ? dataset.readDoubles() : selection.readDoubles();
            case BYTE_ROWS -> {
                int[][] rows = selection == null ? dataset.readVlenInts() : selection.readVlenInts();
                byte[][] bytes = new byte[rows.length][];
                for (int i = 0; i < rows.length; i++) {
                    if (rows[i] != null) {
                        bytes[i] = new byte[rows[i].length];
                        for (int j = 0; j < rows[i].length; j++) {
                            bytes[i][j] = (byte) rows[i][j];
                        }
                    }
                }
                yield bytes;
            }
        };
    }

    private static void write(com.ebremer.falcon.zarr.Selection selection, Mapping mapping, Object data) {
        switch (mapping.transfer()) {
            case RAW, STRUCT -> selection.writeRawBytes((byte[]) data);
            case STRINGS -> selection.writeStrings((String[]) data);
            case LONGS -> selection.writeLongs((long[]) data);
            case FLOATS -> selection.writeFloats((float[]) data);
            case DOUBLES -> selection.writeDoubles((double[]) data);
            case BYTE_ROWS -> selection.writeByteArrays((byte[][]) data);
        }
    }

    static long[] concat(long[] a, long[] b) {
        long[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static long[] concat(long[] a, int[] b) {
        return concat(a, Arrays.stream(b).asLongStream().toArray());
    }
}
