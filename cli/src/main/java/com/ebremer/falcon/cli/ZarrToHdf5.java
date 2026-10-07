package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.Hdf5Writer;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.Selection;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Zarr hierarchy, or one group or array of it, written as an HDF5 file: each group a group and each array
 * a dataset, with their attributes (see {@link AttributeJson}). Each data type maps to the HDF5 type h5py
 * gives the same numpy dtype, so h5py reads the file back as zarr-python reads the store:
 * <ul>
 *   <li>booleans, integers, and floats keep their bytes and byte order; complex numbers become h5py's
 *       compound of {@code r} and {@code i};</li>
 *   <li>strings (variable-length or {@code fixed_length_utf32}) become variable-length UTF-8 strings,
 *       variable-length bytes sequences of {@code uint8};</li>
 *   <li>{@code null_terminated_bytes} become fixed-length ASCII strings, {@code raw_bytes} and {@code r<N>}
 *       opaque elements, and numpy's times h5py's tagged opaque ({@code NUMPY:<M8[ns]});</li>
 *   <li>structs become compounds of the same layout.</li>
 * </ul>
 * Each dataset is chunked as the array's (inner) chunks and compressed as {@code --compression} says; the
 * fill value carries over for numbers. Blocks that hold only the fill value are not written, so a sparse
 * array stays sparse.
 */
final class ZarrToHdf5 {

    private final Context context;
    private final ConvertSettings settings;
    int arrays;
    int groups;

    ZarrToHdf5(Context context, ConvertSettings settings) {
        this.context = context;
        this.settings = settings;
    }

    /** How an array's elements are read and written. */
    enum Transfer {
        /** Element bytes as the array stores them (its byte order). */
        RAW,
        /** Struct elements, little-endian. */
        STRUCT,
        /** Strings. */
        STRINGS,
        /** Byte strings, as sequences. */
        BYTE_ROWS
    }

    /**
     * The HDF5 type of a Zarr data type, and how to carry the elements over.
     *
     * @param type     the HDF5 type
     * @param transfer how elements are carried
     */
    record Mapping(Datatype type, Transfer transfer) {
    }

    /**
     * Writes the node at {@code path} of {@code store}, and everything below it, to an HDF5 file.
     *
     * @param store    the Zarr store
     * @param path     the group or array to write
     * @param target   the file to write; replaced only once it is complete
     * @param rootName the name of the dataset when the node is an array
     * @throws Exception if the conversion fails; the file is then left as it was
     */
    void convert(Store store, String path, Path target, String rootName) throws Exception {
        ZarrNode node = ZarrInspect.resolve(store, path);
        Hdf5Writer writer = Hdf5Writer.create(target);
        try {
            Hdf5Writer.GroupWriter root = writer.root();
            if (node instanceof ZarrGroup group) {
                AttributeJson.toHdf5(context, "/", group.attributes(), root::attribute);
                groups++;
                children(store, group, root, "/");
            } else if (!dataset(store, node.asArray(), root, rootName, "/" + rootName)) {
                throw new UsageException(ZarrInspect.display(node) + " cannot be written to HDF5 (see the warning)");
            }
            writer.close();
        } catch (Exception | Error e) {
            writer.abort();
            throw e;
        }
    }

    private void children(Store store, ZarrGroup group, Hdf5Writer.GroupWriter target, String path) throws Exception {
        for (String name : group.childNames()) {
            String childPath = Hdf5Inspect.join(path, name);
            ZarrNode child;
            try {
                child = group.child(name).orElse(null);
            } catch (RuntimeException e) {
                context.warn("left out " + childPath + ": " + Errors.describe(e));
                continue;
            }
            if (child instanceof ZarrGroup g) {
                Hdf5Writer.GroupWriter created = target.group(name);
                AttributeJson.toHdf5(context, childPath, g.attributes(), created::attribute);
                groups++;
                children(store, g, created, childPath);
            } else if (child instanceof ZarrArray a) {
                dataset(store, a, target, name, childPath);
            }
        }
    }

    /** Writes one array; false (with a warning) if it cannot be. */
    private boolean dataset(Store store, ZarrArray array, Hdf5Writer.GroupWriter target, String name, String path)
            throws Exception {
        ZarrMeta meta = ZarrMeta.of(store, array);
        ByteOrder order = meta.byteOrder();
        Mapping mapping;
        try {
            mapping = map(array.dataType(), order);
        } catch (Hdf5ToZarr.Unsupported e) {
            context.warn("left out " + path + ": HDF5 has no type for " + e.getMessage());
            return false;
        }
        long[] shape = array.shape();
        boolean empty = Arrays.stream(shape).anyMatch(n -> n == 0);
        Hdf5Writer.DatasetWriter dataset = target.createDataset(name, mapping.type(), shape);
        long[] chunks = null;
        Compression compression = Compression.NONE;
        if (shape.length > 0 && !empty) {
            int elementSize = mapping.type().size();
            long[] inner = array.isRectilinear() || settings.autoChunks() ? null : array.innerChunkShape();
            if (inner != null && Blocks.elements(Chunking.clamp(inner, shape)) * elementSize <= (1L << 30)) {
                chunks = Chunking.clamp(inner, shape);
            } else {
                chunks = Chunking.clamp(Chunking.HDF5.guess(shape, elementSize), shape);
            }
            dataset.chunked(chunks);
            compression = Compression.choose(settings.compression(), Compression.fromZarr(meta.codecs()), false);
            try {
                compression.applyTo(dataset);
            } catch (IllegalArgumentException | IllegalStateException e) {
                context.warn(path + ": " + compression.describe() + " refused (" + e.getMessage() + "); written with "
                        + Compression.HDF5_DEFAULT.describe());
                compression = Compression.HDF5_DEFAULT;
                compression.applyTo(dataset);
            }
        }
        byte[] fill = fill(array, mapping, order, dataset, path);
        AttributeJson.toHdf5(context, path, array.attributes(), dataset::attribute);
        copy(array, mapping, dataset, shape, chunks, fill);
        arrays++;
        if (!settings.quiet()) {
            context.out.println(path + "  " + Describe.type(mapping.type()) + " " + Describe.shape(shape)
                    + (chunks == null ? "" : "  chunks " + Describe.shape(chunks)) + "  " + compression.describe());
        }
        return true;
    }

    /**
     * Sets a numeric array's fill value on the dataset, and {@return the bytes of the element unwritten
     * elements read as} (the fill set, else zeros), or null if no block can be skipped.
     */
    private byte[] fill(ZarrArray array, Mapping mapping, ByteOrder order, Hdf5Writer.DatasetWriter dataset,
                        String path) {
        if (mapping.transfer() != Transfer.RAW && mapping.transfer() != Transfer.STRUCT) {
            return null;
        }
        int size = mapping.type().size();
        byte[] zeros = new byte[size];
        DataType type = array.dataType();
        byte[] bytes;
        try {
            bytes = array.fillValueBytes(mapping.transfer() == Transfer.STRUCT ? ByteOrder.LITTLE_ENDIAN : order);
        } catch (RuntimeException e) {
            return zeros;
        }
        if (Arrays.equals(bytes, zeros)) {
            return zeros;
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
        try {
            switch (type.kind()) {
                case INT -> dataset.fillValue(switch (size) {
                    case 1 -> b.get(0);
                    case 2 -> b.getShort(0);
                    case 4 -> b.getInt(0);
                    default -> b.getLong(0);
                });
                case UINT -> {
                    long v = switch (size) {
                        case 1 -> b.get(0) & 0xff;
                        case 2 -> b.getShort(0) & 0xffff;
                        case 4 -> b.getInt(0) & 0xffffffffL;
                        default -> b.getLong(0);
                    };
                    if (v < 0) {
                        return null; // a uint64 fill beyond a long: left at 0, so no block is skipped
                    }
                    dataset.fillValue(v);
                }
                case FLOAT -> dataset.fillValue(switch (size) {
                    case 2 -> (double) Float.float16ToFloat(b.getShort(0));
                    case 4 -> (double) b.getFloat(0);
                    default -> b.getDouble(0);
                });
                case BOOL -> dataset.fillValue(bytes[0] != 0 ? 1 : 0);
                default -> {
                    return null; // other types keep HDF5's default fill, zeros, which is not the array's
                }
            }
            return bytes;
        } catch (IllegalArgumentException | IllegalStateException e) {
            context.warn(path + ": its fill value " + array.fillValue().toJson() + " was not kept ("
                    + e.getMessage() + ")");
            return null;
        }
    }

    // ---- types --------------------------------------------------------------------------------------------

    /**
     * {@return the HDF5 type of a Zarr data type, and how to carry its elements}
     *
     * @param type  the Zarr type
     * @param order the byte order of the array's elements
     * @throws Hdf5ToZarr.Unsupported if HDF5 has no such type
     */
    static Mapping map(DataType type, ByteOrder order) throws Hdf5ToZarr.Unsupported {
        return switch (type.kind()) {
            case STRING, FIXED_STRING -> new Mapping(Datatype.variableString(), Transfer.STRINGS);
            case BYTES -> new Mapping(Datatype.sequenceOf(Datatype.uint8()), Transfer.BYTE_ROWS);
            case STRUCT -> new Mapping(element(type, ByteOrder.LITTLE_ENDIAN), Transfer.STRUCT);
            default -> new Mapping(element(type, order), Transfer.RAW);
        };
    }

    /** The HDF5 type whose elements have the bytes of the Zarr type's, in {@code order}. */
    private static Datatype element(DataType type, ByteOrder order) throws Hdf5ToZarr.Unsupported {
        int n = type.byteCount();
        return switch (type.kind()) {
            case BOOL -> Datatype.bool();
            case INT -> new Datatype.FixedPoint(n, order, true, 0, 8 * n);
            case UINT -> new Datatype.FixedPoint(n, order, false, 0, 8 * n);
            case FLOAT -> switch (n) {
                case 2 -> Datatype.float16().withByteOrder(order);
                case 4 -> Datatype.float32().withByteOrder(order);
                default -> Datatype.float64().withByteOrder(order);
            };
            case COMPLEX -> {
                Datatype part = (n == 8 ? Datatype.float32() : Datatype.float64()).withByteOrder(order);
                Map<String, Datatype> parts = new LinkedHashMap<>();
                parts.put("r", part);
                parts.put("i", part);
                yield Datatype.compound(parts);
            }
            case FIXED_BYTES -> new Datatype.StringType(n, Datatype.StringPadding.NULL_PAD, Datatype.CharacterSet.ASCII);
            case RAW_BYTES, RAW -> Datatype.opaque(n, "");
            case DATETIME, TIMEDELTA -> NumpyTime.of(type, order).hdf5();
            case STRUCT -> {
                Map<String, Datatype> members = new LinkedHashMap<>();
                for (DataType.Field field : type.fields()) {
                    members.put(field.name(), element(field.type(), ByteOrder.LITTLE_ENDIAN));
                }
                yield Datatype.compound(members);
            }
            case FIXED_STRING -> throw new Hdf5ToZarr.Unsupported("fixed_length_utf32 struct fields");
            case STRING, BYTES -> throw new Hdf5ToZarr.Unsupported("variable-length struct fields");
        };
    }

    // ---- data ---------------------------------------------------------------------------------------------

    private void copy(ZarrArray array, Mapping mapping, Hdf5Writer.DatasetWriter dataset, long[] shape, long[] chunks,
                      byte[] fill) throws Exception {
        ZarrArray cached = array.withChunkCache(256L << 20);
        // blocks of the array's own chunks (shards), when the dataset's chunks are their sub-chunks
        boolean subChunks = chunks != null && !settings.autoChunks() && !array.isRectilinear()
                && Arrays.equals(Chunking.clamp(array.innerChunkShape(), shape), chunks);
        long[] unit = chunks == null ? new long[0] : subChunks ? Chunking.clamp(array.chunkShape(), shape) : chunks;
        int elementSize = mapping.transfer() == Transfer.RAW || mapping.transfer() == Transfer.STRUCT
                ? mapping.type().size() : 64;
        Blocks.copy(shape, unit, elementSize, settings.threads(), true,
                (offset, count) -> read(shape.length == 0 ? cached.selectAll() : cached.select(offset, count), mapping),
                (offset, count, data) -> {
                    if (fill != null && data instanceof byte[] bytes && allFill(bytes, fill)) {
                        return; // the dataset reads unwritten elements as the fill
                    }
                    switch (mapping.transfer()) {
                        case RAW, STRUCT -> dataset.writeRaw(offset, count, (byte[]) data);
                        default -> dataset.write(offset, count, data);
                    }
                });
    }

    private static Object read(Selection selection, Mapping mapping) {
        return switch (mapping.transfer()) {
            case RAW -> selection.readRawBytes();
            case STRUCT -> {
                byte[][] elements = selection.readByteArrays();
                int size = mapping.type().size();
                byte[] out = new byte[elements.length * size];
                for (int i = 0; i < elements.length; i++) {
                    System.arraycopy(elements[i], 0, out, i * size, size);
                }
                yield out;
            }
            case STRINGS -> selection.readStrings();
            case BYTE_ROWS -> { // as unsigned values: the writer reads a byte[] as signed, which uint8 refuses
                byte[][] rows = selection.readByteArrays();
                int[][] values = new int[rows.length][];
                for (int i = 0; i < rows.length; i++) {
                    byte[] row = rows[i] == null ? new byte[0] : rows[i];
                    values[i] = new int[row.length];
                    for (int j = 0; j < row.length; j++) {
                        values[i][j] = row[j] & 0xff;
                    }
                }
                yield values;
            }
        };
    }

    private static boolean allFill(byte[] bytes, byte[] fill) {
        int n = fill.length;
        for (int i = 0; i < bytes.length; i += n) {
            if (!Arrays.equals(bytes, i, i + n, fill, 0, n)) {
                return false;
            }
        }
        return true;
    }
}
