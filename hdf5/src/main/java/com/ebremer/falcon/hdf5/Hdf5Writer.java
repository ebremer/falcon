package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.checksum.Lookup3;
import com.ebremer.falcon.hdf5.filter.Aec;
import com.ebremer.falcon.hdf5.filter.Filters;
import com.ebremer.falcon.hdf5.write.GrowBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Writes a valid HDF5 file in the modern format: a version-3 (checksummed) superblock, version-2
 * (checksummed) object headers, and groups whose children are recorded as compact link messages.
 * Supports a nested group tree, contiguous {@code int32} / {@code float64} / string datasets, and
 * attributes on groups and datasets. Fixed-length strings are stored inline; variable-length strings
 * live in a global heap.
 *
 * <pre>{@code
 * try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
 *     w.intDataset("counts", new int[] {1, 2, 3}, new long[] {3}).intAttribute("scale", new int[] {2}, new long[] {});
 *     GroupWriter g = w.group("run");
 *     g.doubleDataset("signal", new double[] {1.5, 2.5}, new long[] {2});
 *     g.stringDataset("labels", new String[] {"a", "bb"}, new long[] {2});
 * }
 * }</pre>
 */
public final class Hdf5Writer implements AutoCloseable {

    private static final byte[] HDF5_SIGNATURE = {(byte) 0x89, 'H', 'D', 'F', '\r', '\n', 0x1a, '\n'};
    private static final byte[] OHDR_SIGNATURE = {'O', 'H', 'D', 'R'};
    private static final byte[] GCOL_SIGNATURE = {'G', 'C', 'O', 'L'};
    private static final byte[] FRHP_SIGNATURE = {'F', 'R', 'H', 'P'};
    private static final byte[] FHDB_SIGNATURE = {'F', 'H', 'D', 'B'};
    private static final byte[] BTHD_SIGNATURE = {'B', 'T', 'H', 'D'};
    private static final byte[] BTLF_SIGNATURE = {'B', 'T', 'L', 'F'};
    private static final byte[] TREE_SIGNATURE = {'T', 'R', 'E', 'E'};
    private static final byte[] SNOD_SIGNATURE = {'S', 'N', 'O', 'D'};
    private static final byte[] HEAP_SIGNATURE = {'H', 'E', 'A', 'P'};
    private static final long UNDEFINED = -1L;
    private static final int SUPERBLOCK_SIZE = 48;
    private static final int LEGACY_SUPERBLOCK_SIZE = 96; // v0 superblock with 8-byte offsets/lengths
    private static final int GROUP_INTERNAL_K = 16;       // v1 B-tree entries per node = 2 * K
    private static final int GROUP_LEAF_K = 4;            // symbol-table node symbols = 2 * K
    private static final int SYMBOL_ENTRY_SIZE = 2 * 8 + 4 + 4 + 16; // name off, header, cache type, rsv, scratch

    // Above this many links/attributes, the writer switches from compact header messages to dense
    // storage (a fractal heap indexed by a version-2 B-tree), matching the library's default threshold.
    private static final int MAX_COMPACT = 8;
    // Fractal-heap id layout, matching h5py's per-use choices. A managed id is
    // {@code type byte(1) + offset + length}; the offset width is ceil(maxHeapBits/8).
    private record HeapParams(int idLength, int offsetSize, int lengthSize, int maxBits) {
    }
    private static final HeapParams ATTR_HEAP = new HeapParams(8, 5, 2, 40); // ids: type + 5-byte off + 2-byte len
    private static final HeapParams LINK_HEAP = new HeapParams(7, 4, 2, 32); // ids: type + 4-byte off + 2-byte len
    private static final int HEAP_TABLE_WIDTH = 4;
    private static final int HEAP_MAX_DIRECT_BLOCK = 65536;
    private static final int HEAP_MAX_MANAGED_OBJECT = 4096;
    // v2 B-tree record types for dense storage.
    private static final int BT2_ATTR_NAME = 8;    // 17-byte record: heap id(8) + flags(1) + corder(4) + hash(4)
    private static final int BT2_LINK_NAME = 5;    // 11-byte record: hash(4) + heap id(7)
    private static final int BT2_NODE_SIZE = 512;

    private static final byte[] DATATYPE_INT8 = {0x10, 0x08, 0, 0, 1, 0, 0, 0, 0, 0, 0x08, 0};
    private static final byte[] DATATYPE_INT16 = {0x10, 0x08, 0, 0, 2, 0, 0, 0, 0, 0, 0x10, 0};
    private static final byte[] DATATYPE_INT32 = {0x10, 0x08, 0, 0, 4, 0, 0, 0, 0, 0, 0x20, 0};
    private static final byte[] DATATYPE_INT64 = {0x10, 0x08, 0, 0, 8, 0, 0, 0, 0, 0, 0x40, 0};
    private static final byte[] DATATYPE_FLOAT32 = {
        0x11, 0x20, 0x1f, 0, 4, 0, 0, 0, 0, 0, 0x20, 0, 0x17, 0x08, 0, 0x17, 0x7f, 0, 0, 0
    };
    private static final byte[] DATATYPE_FLOAT64 = {
        0x11, 0x20, 0x3f, 0, 8, 0, 0, 0, 0, 0, 0x40, 0, 0x34, 0x0b, 0, 0x34, (byte) 0xff, 0x03, 0, 0
    };
    // Variable-length UTF-8 string (class 9): 16-byte global-heap id, string base. Verbatim from h5py.
    private static final byte[] DATATYPE_VLEN_STRING = {
        0x19, 0x01, 0x01, 0, 0x10, 0, 0, 0, 0x10, 0, 0, 0, 0x01, 0, 0, 0, 0, 0, 0x08, 0
    };
    // Object reference (class 7, kind 0): an 8-byte object-header address. Verbatim from h5py.
    private static final byte[] DATATYPE_OBJECT_REFERENCE = {0x17, 0, 0, 0, 8, 0, 0, 0};

    private final Path path;
    private final boolean legacy;
    private final GroupSpec root = new GroupSpec();
    private final GroupWriter rootWriter = new GroupWriter(root);
    private final Map<String, Long> objectAddresses = new HashMap<>();     // absolute path -> object header address
    private final List<PendingReference> pendingReferences = new ArrayList<>();
    private long legacyRootBtree = UNDEFINED;  // root group's symbol-table B-tree / local heap (legacy superblock)
    private long legacyRootHeap = UNDEFINED;
    private boolean written;

    /** On-disk format version: {@link #EARLIEST} writes the original (v0 superblock, symbol-table
     * groups, v1 object headers); {@link #LATEST} the modern checksummed format. */
    public enum Format {
        EARLIEST,
        LATEST
    }

    private Hdf5Writer(Path path, Format format) {
        this.path = path;
        this.legacy = format == Format.EARLIEST;
    }

    /** Begins writing a new HDF5 file at {@code path} in the modern format (written on {@link #close()}). */
    public static Hdf5Writer create(Path path) {
        return new Hdf5Writer(path, Format.LATEST);
    }

    /** Begins writing a new HDF5 file at {@code path} in the given on-disk {@link Format}. */
    public static Hdf5Writer create(Path path, Format format) {
        return new Hdf5Writer(path, format);
    }

    /** The root group writer, on which the same operations are available as any subgroup. */
    public GroupWriter root() {
        return rootWriter;
    }

    // Convenience delegates to the root group.
    public DatasetWriter intDataset(String name, int[] data, long[] shape) {
        return rootWriter.intDataset(name, data, shape);
    }

    public DatasetWriter doubleDataset(String name, double[] data, long[] shape) {
        return rootWriter.doubleDataset(name, data, shape);
    }

    public DatasetWriter stringDataset(String name, String[] data, long[] shape) {
        return rootWriter.stringDataset(name, data, shape);
    }

    public DatasetWriter byteDataset(String name, byte[] data, long[] shape) {
        return rootWriter.byteDataset(name, data, shape);
    }

    public DatasetWriter shortDataset(String name, short[] data, long[] shape) {
        return rootWriter.shortDataset(name, data, shape);
    }

    public DatasetWriter longDataset(String name, long[] data, long[] shape) {
        return rootWriter.longDataset(name, data, shape);
    }

    public DatasetWriter floatDataset(String name, float[] data, long[] shape) {
        return rootWriter.floatDataset(name, data, shape);
    }

    public DatasetWriter fixedStringDataset(String name, String[] data, long[] shape) {
        return rootWriter.fixedStringDataset(name, data, shape);
    }

    public DatasetWriter intChunkedDataset(String name, int[] data, long[] shape, long[] chunkShape) {
        return rootWriter.intChunkedDataset(name, data, shape, chunkShape);
    }

    public DatasetWriter doubleChunkedDataset(String name, double[] data, long[] shape, long[] chunkShape) {
        return rootWriter.doubleChunkedDataset(name, data, shape, chunkShape);
    }

    public DatasetWriter compoundDataset(String name, long[] shape, CompoundField... fields) {
        return rootWriter.compoundDataset(name, shape, fields);
    }

    public DatasetWriter enumDataset(String name, long[] shape, EnumType type, int[] values) {
        return rootWriter.enumDataset(name, shape, type, values);
    }

    public DatasetWriter referenceDataset(String name, long[] shape, String[] targets) {
        return rootWriter.referenceDataset(name, shape, targets);
    }

    public DatasetWriter float32ArrayDataset(String name, long[] shape, int[] arrayDims, float[] data) {
        return rootWriter.float32ArrayDataset(name, shape, arrayDims, data);
    }

    public DatasetWriter int32ArrayDataset(String name, long[] shape, int[] arrayDims, int[] data) {
        return rootWriter.int32ArrayDataset(name, shape, arrayDims, data);
    }

    public DatasetWriter complexDataset(String name, long[] shape, double[] real, double[] imaginary) {
        return rootWriter.complexDataset(name, shape, real, imaginary);
    }

    public DatasetWriter intSequenceDataset(String name, long[] shape, int[][] rows) {
        return rootWriter.intSequenceDataset(name, shape, rows);
    }

    public DatasetWriter doubleSequenceDataset(String name, long[] shape, double[][] rows) {
        return rootWriter.doubleSequenceDataset(name, shape, rows);
    }

    public GroupWriter group(String name) {
        return rootWriter.group(name);
    }

    @Override
    public void close() throws IOException {
        if (written) {
            return;
        }
        written = true;
        GrowBuffer buf = new GrowBuffer();
        buf.reserve(legacy ? LEGACY_SUPERBLOCK_SIZE : SUPERBLOCK_SIZE);
        GroupResult rootResult = writeGroup(buf, root, "");
        long rootAddress = rootResult.headerAddress();
        legacyRootBtree = rootResult.btreeAddress();
        legacyRootHeap = rootResult.heapAddress();
        objectAddresses.put("/", rootAddress);
        resolveReferences(buf);
        long endOfFile = buf.position();
        buf.patchBytes(0, legacy
                ? superblockV0(rootAddress, endOfFile)
                : superblock(rootAddress, endOfFile));
        Files.write(path, buf.toByteArray());
    }

    // --------------------------------------------------------------- API handles

    /** Builds a group: datasets, subgroups, and attributes. */
    public static final class GroupWriter {
        private final GroupSpec spec;

        private GroupWriter(GroupSpec spec) {
            this.spec = spec;
        }

        public DatasetWriter intDataset(String name, int[] data, long[] shape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT32, 4, shape, null, intBytes(data), null));
        }

        public DatasetWriter doubleDataset(String name, double[] data, long[] shape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT64, 8, shape, null, doubleBytes(data), null));
        }

        /** A signed 8-bit integer dataset. */
        public DatasetWriter byteDataset(String name, byte[] data, long[] shape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT8, 1, shape, null, data.clone(), null));
        }

        /** A signed 16-bit integer dataset. */
        public DatasetWriter shortDataset(String name, short[] data, long[] shape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT16, 2, shape, null, shortBytes(data), null));
        }

        /** A signed 64-bit integer dataset. */
        public DatasetWriter longDataset(String name, long[] data, long[] shape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT64, 8, shape, null, longBytes(data), null));
        }

        /** A 32-bit floating-point dataset. */
        public DatasetWriter floatDataset(String name, float[] data, long[] shape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT32, 4, shape, null, float32Bytes(data), null));
        }

        /**
         * A fixed-length string dataset. Each element is stored in {@code length} bytes (the longest
         * string's byte length if not given), null-padded; longer strings are truncated.
         */
        public DatasetWriter fixedStringDataset(String name, String[] data, long[] shape) {
            int length = 1;
            for (String s : data) {
                length = Math.max(length, s.getBytes(StandardCharsets.US_ASCII).length);
            }
            return fixedStringDataset(name, data, shape, length);
        }

        /** A fixed-length string dataset with an explicit per-element byte {@code length}. */
        public DatasetWriter fixedStringDataset(String name, String[] data, long[] shape, int length) {
            requireElementCount(shape, data.length);
            byte[] bytes = new byte[data.length * length];
            for (int i = 0; i < data.length; i++) {
                byte[] s = data[i].getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(s, 0, bytes, i * length, Math.min(s.length, length));
            }
            return addDataset(new DatasetSpec(name, fixedStringDatatype(length), length, shape, null, bytes, null));
        }

        /** A chunked {@code int32} dataset (fixed-array index). */
        public DatasetWriter intChunkedDataset(String name, int[] data, long[] shape, long[] chunkShape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT32, 4, shape, chunkShape, intBytes(data), null));
        }

        /** A chunked {@code float64} dataset (fixed-array index). */
        public DatasetWriter doubleChunkedDataset(String name, double[] data, long[] shape, long[] chunkShape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT64, 8, shape, chunkShape, doubleBytes(data), null));
        }

        /** A variable-length UTF-8 string dataset (values stored in a global heap). */
        public DatasetWriter stringDataset(String name, String[] data, long[] shape) {
            requireElementCount(shape, data.length);
            List<byte[]> bytes = new ArrayList<>();
            for (String s : data) {
                bytes.add(s.getBytes(StandardCharsets.UTF_8));
            }
            return addDataset(new DatasetSpec(name, DATATYPE_VLEN_STRING, 16, shape, null, null, bytes));
        }

        /**
         * A compound (record) dataset. Each {@link CompoundField} supplies one named, typed column;
         * fields are packed in order (no alignment gaps) and every column must have one value per
         * element.
         */
        public DatasetWriter compoundDataset(String name, long[] shape, CompoundField... fields) {
            long count = elementCount(shape);
            int recordSize = 0;
            int[] offsets = new int[fields.length];
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].count != count) {
                    throw new IllegalArgumentException("compound field '" + fields[i].name + "' has "
                            + fields[i].count + " values but the shape implies " + count);
                }
                offsets[i] = recordSize;
                recordSize += fields[i].size;
            }
            byte[] data = new byte[Math.toIntExact(count * recordSize)];
            for (int r = 0; r < count; r++) {
                for (int i = 0; i < fields.length; i++) {
                    System.arraycopy(fields[i].column, r * fields[i].size,
                            data, r * recordSize + offsets[i], fields[i].size);
                }
            }
            return addDataset(new DatasetSpec(name, compoundDatatype(fields, offsets, recordSize),
                    recordSize, shape, null, data, null));
        }

        /** An enumerated dataset over a 32-bit base type: each value must be one of {@code type}'s codes. */
        public DatasetWriter enumDataset(String name, long[] shape, EnumType type, int[] values) {
            requireElementCount(shape, values.length);
            return addDataset(new DatasetSpec(name, enumDatatype(type), 4, shape, null, intBytes(values), null));
        }

        /**
         * A dataset whose every element is a fixed-shape {@code float32} array. {@code data} holds all
         * elements' sub-arrays concatenated row-major (element count &times; {@code prod(arrayDims)} values).
         */
        public DatasetWriter float32ArrayDataset(String name, long[] shape, int[] arrayDims, float[] data) {
            int perElement = product(arrayDims);
            requireArrayData(shape, perElement, data.length);
            return addDataset(new DatasetSpec(name, arrayDatatype(arrayDims, 4, DATATYPE_FLOAT32),
                    perElement * 4, shape, null, float32Bytes(data), null));
        }

        /** A dataset whose every element is a fixed-shape {@code int32} array (see {@link #float32ArrayDataset}). */
        public DatasetWriter int32ArrayDataset(String name, long[] shape, int[] arrayDims, int[] data) {
            int perElement = product(arrayDims);
            requireArrayData(shape, perElement, data.length);
            return addDataset(new DatasetSpec(name, arrayDatatype(arrayDims, 4, DATATYPE_INT32),
                    perElement * 4, shape, null, intBytes(data), null));
        }

        /** A native complex-number dataset (128-bit: {@code float64} real and imaginary parts). */
        public DatasetWriter complexDataset(String name, long[] shape, double[] real, double[] imaginary) {
            requireElementCount(shape, real.length);
            if (imaginary.length != real.length) {
                throw new IllegalArgumentException("real and imaginary parts differ in length");
            }
            byte[] data = new byte[real.length * 16];
            for (int i = 0; i < real.length; i++) {
                putDoubleLittleEndian(data, i * 16, real[i]);
                putDoubleLittleEndian(data, i * 16 + 8, imaginary[i]);
            }
            return addDataset(new DatasetSpec(name, complex128Datatype(), 16, shape, null, data, null));
        }

        /** A variable-length {@code int32} sequence (ragged array) dataset; {@code rows[i]} is element i. */
        public DatasetWriter intSequenceDataset(String name, long[] shape, int[][] rows) {
            requireElementCount(shape, rows.length);
            List<byte[]> payloads = new ArrayList<>();
            int[] counts = new int[rows.length];
            for (int i = 0; i < rows.length; i++) {
                payloads.add(intBytes(rows[i]));
                counts[i] = rows[i].length;
            }
            return addVlenSequence(name, shape, DATATYPE_INT32, payloads, counts);
        }

        /** A variable-length {@code float64} sequence (ragged array) dataset; {@code rows[i]} is element i. */
        public DatasetWriter doubleSequenceDataset(String name, long[] shape, double[][] rows) {
            requireElementCount(shape, rows.length);
            List<byte[]> payloads = new ArrayList<>();
            int[] counts = new int[rows.length];
            for (int i = 0; i < rows.length; i++) {
                payloads.add(doubleBytes(rows[i]));
                counts[i] = rows[i].length;
            }
            return addVlenSequence(name, shape, DATATYPE_FLOAT64, payloads, counts);
        }

        private DatasetWriter addVlenSequence(String name, long[] shape, byte[] base,
                                              List<byte[]> payloads, int[] counts) {
            DatasetSpec spec = new DatasetSpec(name, vlenSequenceDatatype(base), 16, shape, null, null, payloads);
            spec.vlenElementCounts = counts;
            return addDataset(spec);
        }

        /**
         * An object-reference dataset. Each target is an absolute path ({@code "/name"} or
         * {@code "/group/name"}) to another object in this file, or {@code null} for a null reference.
         * Targets may be defined before or after this dataset; addresses are resolved when the file is
         * written (an unresolved target path is an error).
         */
        public DatasetWriter referenceDataset(String name, long[] shape, String[] targets) {
            requireElementCount(shape, targets.length);
            DatasetSpec spec = new DatasetSpec(name, DATATYPE_OBJECT_REFERENCE, 8, shape, null,
                    new byte[targets.length * 8], null);
            spec.referenceTargets = java.util.Arrays.asList(targets);
            return addDataset(spec);
        }

        public GroupWriter group(String name) {
            GroupSpec child = new GroupSpec();
            child.name = name;
            spec.groups.add(child);
            return new GroupWriter(child);
        }

        public GroupWriter intAttribute(String name, int[] data, long[] shape) {
            requireElementCount(shape, data.length);
            spec.attributes.add(new AttributeSpec(name, DATATYPE_INT32, shape, intBytes(data)));
            return this;
        }

        public GroupWriter doubleAttribute(String name, double[] data, long[] shape) {
            requireElementCount(shape, data.length);
            spec.attributes.add(new AttributeSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data)));
            return this;
        }

        private DatasetWriter addDataset(DatasetSpec dataset) {
            spec.datasets.add(dataset);
            return new DatasetWriter(dataset);
        }
    }

    /** Attaches attributes and (for chunked datasets) filters to a dataset. */
    public static final class DatasetWriter {
        private final DatasetSpec spec;

        private DatasetWriter(DatasetSpec spec) {
            this.spec = spec;
        }

        /** Compresses each chunk with deflate (gzip) at the given level (0&ndash;9). Chunked datasets only. */
        public DatasetWriter deflate(int level) {
            requireChunked();
            spec.deflateLevel = level;
            return this;
        }

        /** Byte-shuffles each chunk (grouping like-position bytes) to improve compression. Chunked only. */
        public DatasetWriter shuffle() {
            requireChunked();
            spec.shuffle = true;
            return this;
        }

        /** Appends a Fletcher-32 checksum to each stored chunk. Chunked datasets only. */
        public DatasetWriter fletcher32() {
            requireChunked();
            spec.fletcher32 = true;
            return this;
        }

        /**
         * Losslessly compresses integer chunks with the scale-offset filter (subtract the minimum, then
         * bit-pack). Chunked {@code int32} datasets only, and not combined with other filters.
         */
        public DatasetWriter scaleOffset() {
            requireChunked();
            spec.scaleOffset = true;
            return this;
        }

        /**
         * Stores each element in only its {@code precision} low bits with the n-bit filter (an unsigned
         * integer datatype of that precision). Chunked {@code int32} datasets only; values must be
         * non-negative and fit in {@code precision} bits.
         */
        public DatasetWriter nbit(int precision) {
            requireChunked();
            spec.nbitPrecision = precision;
            return this;
        }

        /**
         * Compresses integer chunks with the szip filter (pure-Java CCSDS extended-Rice encoder, no
         * preprocessing). Chunked {@code int32} datasets only. Note: this environment's h5py has szip
         * disabled, so verify round-trips with Falcon (or libaec), not h5py.
         */
        public DatasetWriter szip() {
            requireChunked();
            spec.szip = true;
            return this;
        }

        private void requireChunked() {
            if (spec.chunkShape == null) {
                throw new IllegalStateException("filters require a chunked dataset");
            }
        }

        /** Sets the fill value (for unallocated/unwritten elements) from an integer, sized to the datatype. */
        public DatasetWriter fillValue(long value) {
            byte[] fill = new byte[spec.elementSize];
            for (int b = 0; b < fill.length; b++) {
                fill[b] = (byte) (value >>> (8 * b));
            }
            spec.fillValue = fill;
            return this;
        }

        /** Sets the fill value from a floating-point value (float32 or float64 per the datatype size). */
        public DatasetWriter fillValue(double value) {
            if (spec.elementSize == 8) {
                spec.fillValue = doubleBytes(new double[] {value});
            } else {
                spec.fillValue = float32Bytes(new float[] {(float) value});
            }
            return this;
        }

        public DatasetWriter intAttribute(String name, int[] data, long[] shape) {
            requireElementCount(shape, data.length);
            spec.attributes.add(new AttributeSpec(name, DATATYPE_INT32, shape, intBytes(data)));
            return this;
        }

        public DatasetWriter doubleAttribute(String name, double[] data, long[] shape) {
            requireElementCount(shape, data.length);
            spec.attributes.add(new AttributeSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data)));
            return this;
        }
    }

    /** One named, typed column of a {@link GroupWriter#compoundDataset compound dataset}. */
    public static final class CompoundField {
        private final String name;
        private final byte[] datatype;
        private final int size;
        private final byte[] column;
        private final int count;

        private CompoundField(String name, byte[] datatype, int size, byte[] column, int count) {
            this.name = name;
            this.datatype = datatype;
            this.size = size;
            this.column = column;
            this.count = count;
        }

        /** An {@code int32} field. */
        public static CompoundField int32(String name, int[] values) {
            return new CompoundField(name, DATATYPE_INT32, 4, intBytes(values), values.length);
        }

        /** A {@code float64} field. */
        public static CompoundField float64(String name, double[] values) {
            return new CompoundField(name, DATATYPE_FLOAT64, 8, doubleBytes(values), values.length);
        }
    }

    /** An enumeration type over a 32-bit base: an ordered list of {@code name -> code} members. */
    public static final class EnumType {
        private final List<String> names = new ArrayList<>();
        private final List<Integer> values = new ArrayList<>();

        /** Adds a member; returns {@code this} for chaining. */
        public EnumType add(String name, int value) {
            names.add(name);
            values.add(value);
            return this;
        }
    }

    /** Starts building an {@link EnumType}. */
    public static EnumType enumType() {
        return new EnumType();
    }

    // --------------------------------------------------------------- serialization

    /** A written group: its object-header address, and (legacy only) its symbol-table B-tree and heap. */
    private record GroupResult(long headerAddress, long btreeAddress, long heapAddress) {
    }

    /** One child of a legacy (symbol-table) group. */
    private record SymbolChild(String name, long headerAddress, int cacheType, long btree, long heap) {
    }

    private GroupResult writeGroup(GrowBuffer buf, GroupSpec group, String groupPath) {
        Map<String, Long> children = new LinkedHashMap<>();
        List<SymbolChild> symbolChildren = legacy ? new ArrayList<>() : null;
        for (GroupSpec subgroup : group.groups) {
            GroupResult child = writeGroup(buf, subgroup, groupPath + "/" + subgroup.name);
            children.put(subgroup.name, child.headerAddress());
            objectAddresses.put(groupPath + "/" + subgroup.name, child.headerAddress());
            if (legacy) {
                symbolChildren.add(new SymbolChild(subgroup.name, child.headerAddress(), 1,
                        child.btreeAddress(), child.heapAddress()));
            }
        }
        for (DatasetSpec dataset : group.datasets) {
            long address = writeDataset(buf, dataset);
            children.put(dataset.name, address);
            objectAddresses.put(groupPath + "/" + dataset.name, address);
            if (legacy) {
                symbolChildren.add(new SymbolChild(dataset.name, address, 0, UNDEFINED, UNDEFINED));
            }
        }
        if (legacy) {
            return writeSymbolTableGroup(buf, symbolChildren, group.attributes);
        }
        byte[] linkInfo = children.size() > MAX_COMPACT ? writeDenseLinks(buf, children) : null;
        byte[] attributeInfo = group.attributes.size() > MAX_COMPACT
                ? writeDenseAttributes(buf, group.attributes) : null;
        buf.align(8);
        long headerAddress = buf.position();
        writeGroupHeader(buf, children, group.attributes, linkInfo, attributeInfo);
        return new GroupResult(headerAddress, UNDEFINED, UNDEFINED);
    }

    /** Patches each pending object reference with the resolved header address of its target object. */
    private void resolveReferences(GrowBuffer buf) {
        for (PendingReference reference : pendingReferences) {
            Long address = objectAddresses.get(reference.targetPath);
            if (address == null) {
                throw new IllegalArgumentException("reference target does not exist: " + reference.targetPath);
            }
            buf.patchU64(reference.offset, address);
        }
    }

    /** Queues each non-null target for address patching; a null target keeps the all-zeros placeholder
     * (the encoding h5py uses for a null object reference). */
    private void recordReferences(List<String> targets, int dataAddress) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i) != null) {
                pendingReferences.add(new PendingReference(dataAddress + i * 8, targets.get(i)));
            }
        }
    }

    private record PendingReference(int offset, String targetPath) {
    }

    private long writeDataset(GrowBuffer buf, DatasetSpec dataset) {
        if (legacy && (dataset.chunkShape != null || dataset.attributes.size() > MAX_COMPACT)) {
            throw new HdfUnsupportedException(
                    "chunked/filtered and dense-attribute datasets are not written in the earliest format");
        }
        byte[] layout;
        if (dataset.chunkShape != null) {
            layout = writeChunkedStorage(buf, dataset);
        } else {
            byte[] data = dataset.data;
            if (dataset.vlenStrings != null) {
                buf.align(8);
                long collection = buf.position();
                int[] indices = writeGlobalHeap(buf, dataset.vlenStrings);
                data = vlenIds(dataset.vlenStrings, dataset.vlenElementCounts, collection, indices);
            }
            buf.align(8);
            long dataAddress = buf.position();
            if (dataset.referenceTargets != null) {
                recordReferences(dataset.referenceTargets, (int) dataAddress);
            }
            buf.bytes(data);
            layout = contiguousLayoutBody(dataAddress, data.length);
        }
        // Dense attribute structures are written before the object header so it can reference them.
        byte[] attributeInfo = dataset.attributes.size() > MAX_COMPACT
                ? writeDenseAttributes(buf, dataset.attributes) : null;
        buf.align(8);
        long headerAddress = buf.position();

        byte[] datatype = dataset.nbitPrecision >= 0
                ? nbitDatatype(dataset.elementSize, dataset.nbitPrecision) : dataset.datatype;
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(1, 0x00, dataspaceBody(dataset.shape)));
        messages.add(new Message(3, 0x01, datatype));
        messages.add(new Message(5, 0x01, fillValueBody(dataset.fillValue)));
        messages.add(new Message(8, 0x00, layout));
        if (dataset.shuffle || dataset.deflateLevel >= 0 || dataset.fletcher32 || dataset.scaleOffset
                || dataset.nbitPrecision >= 0 || dataset.szip) {
            messages.add(new Message(11, 0x00, filterPipelineBody(dataset)));
        }
        if (attributeInfo != null) {
            messages.add(new Message(21, 0x00, attributeInfo));
        } else {
            for (AttributeSpec attribute : dataset.attributes) {
                messages.add(new Message(12, 0x00, attributeBody(attribute)));
            }
        }
        writeObjectHeader(buf, messages, 1);
        return headerAddress;
    }

    /**
     * Writes chunked storage: each chunk's (fill-padded) data block, then a fixed-array index (a
     * {@code "FADB"} data block listing chunk addresses in row-major order, and its {@code "FAHD"}
     * header). Returns the version-4 chunked data-layout message body.
     */
    private static byte[] writeChunkedStorage(GrowBuffer buf, DatasetSpec dataset) {
        List<byte[]> chunks = splitChunks(dataset);
        boolean filtered = dataset.shuffle || dataset.deflateLevel >= 0 || dataset.fletcher32
                || dataset.scaleOffset || dataset.nbitPrecision >= 0 || dataset.szip;
        long[] chunkAddresses = new long[chunks.size()];
        int[] chunkSizes = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            byte[] block;
            if (dataset.scaleOffset) {
                block = scaleOffsetEncode(chunks.get(i), dataset.elementSize);
            } else if (dataset.szip) {
                block = szipEncode(chunks.get(i), dataset.elementSize);
            } else if (dataset.nbitPrecision >= 0) {
                block = nbitEncode(chunks.get(i), dataset.elementSize, dataset.nbitPrecision);
            } else {
                block = applyFilters(chunks.get(i), dataset);
            }
            buf.align(8);
            chunkAddresses[i] = buf.position();
            chunkSizes[i] = block.length;
            buf.bytes(block);
        }

        int offsets = 8;
        int lengths = 8;
        int clientId = filtered ? 1 : 0;
        int entrySize = filtered ? offsets + lengths + 4 : offsets; // filtered: address + stored size + mask
        int dataBlockSize = 6 + offsets + chunks.size() * entrySize + 4;
        buf.align(8);
        long dataBlockAddress = buf.position();
        long headerAddress = align8(dataBlockAddress + dataBlockSize);

        // Fixed-array data block: signature, version, client id, heap header address, entries, checksum.
        buf.bytes(new byte[] {'F', 'A', 'D', 'B'});
        buf.u8(0);
        buf.u8(clientId);
        buf.u64(headerAddress);
        for (int i = 0; i < chunks.size(); i++) {
            buf.u64(chunkAddresses[i]);
            if (filtered) {
                buf.u64(chunkSizes[i]);
                buf.u32(0); // filter mask: all filters applied
            }
        }
        buf.u32(buf.checksum((int) dataBlockAddress, buf.position()));

        // Fixed-array header: signature, version, client id, entry size, page bits, max entries,
        // data block address, checksum.
        buf.align(8);
        int headerStart = buf.position();
        buf.bytes(new byte[] {'F', 'A', 'H', 'D'});
        buf.u8(0);
        buf.u8(clientId);
        buf.u8(entrySize);
        buf.u8(10);                    // page bits (data block is not paged for these sizes)
        buf.u64(chunks.size());        // max entries
        buf.u64(dataBlockAddress);
        buf.u32(buf.checksum(headerStart, buf.position()));

        return chunkedLayoutBody(dataset.chunkShape, dataset.elementSize, headerAddress, filtered);
    }

    /** Applies the dataset's filter chain to a chunk, in write order: shuffle, deflate, fletcher32. */
    private static byte[] applyFilters(byte[] chunk, DatasetSpec dataset) {
        byte[] block = chunk;
        if (dataset.shuffle) {
            block = shuffle(block, dataset.elementSize);
        }
        if (dataset.deflateLevel >= 0) {
            block = deflate(block, dataset.deflateLevel);
        }
        if (dataset.fletcher32) {
            block = appendFletcher32(block);
        }
        return block;
    }

    /** The filter-pipeline message, listing the filters in the order they are applied on write. */
    private static byte[] filterPipelineBody(DatasetSpec dataset) {
        GrowBuffer b = new GrowBuffer();
        b.u8(2); // version
        if (dataset.szip) {
            b.u8(1);
            // szip client data: option mask (EC + LSB, no NN/MSB), pixels-per-block, bits-per-pixel,
            // pixels-per-scanline (= block size, so one block per reference-sample interval).
            writeFilter(b, Filters.SZIP, 0, 0x0c, 8, dataset.elementSize * 8, 8);
            return b.toByteArray();
        }
        if (dataset.nbitPrecision >= 0) {
            b.u8(1);
            int elements = 1;
            for (long c : dataset.chunkShape) {
                elements *= (int) c;
            }
            // n-bit client data: total, flag, nelmts, ATOMIC, size, byte order (0=LE), precision, offset.
            writeFilter(b, Filters.NBIT, 0,
                    8, 0, elements, 1, dataset.elementSize, 0, dataset.nbitPrecision, 0);
            return b.toByteArray();
        }
        if (dataset.scaleOffset) {
            b.u8(1);
            int elements = 1;
            for (long c : dataset.chunkShape) {
                elements *= (int) c;
            }
            // scale-offset client data for a signed little-endian int (size, sign, order, fill available).
            writeFilter(b, Filters.SCALEOFFSET, 1,
                    2, 0, elements, 0, dataset.elementSize, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            return b.toByteArray();
        }
        int count = (dataset.shuffle ? 1 : 0) + (dataset.deflateLevel >= 0 ? 1 : 0) + (dataset.fletcher32 ? 1 : 0);
        b.u8(count);
        if (dataset.shuffle) {
            writeFilter(b, Filters.SHUFFLE, 1, dataset.elementSize);
        }
        if (dataset.deflateLevel >= 0) {
            writeFilter(b, Filters.DEFLATE, 1, dataset.deflateLevel);
        }
        if (dataset.fletcher32) {
            writeFilter(b, Filters.FLETCHER32, 0);
        }
        return b.toByteArray();
    }

    /**
     * Integer scale-offset encode: a 21-byte header ({@code minbits}, minimum value, fill value) then
     * each element packed as {@code minbits} bits (value minus the minimum, MSB-first). {@code minbits}
     * reserves the all-ones code (the fill marker), so no real value ever encodes to it.
     */
    private static byte[] scaleOffsetEncode(byte[] chunk, int elementSize) {
        int elements = chunk.length / elementSize;
        long[] values = new long[elements];
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < elements; i++) {
            long v = signedLittleEndian(chunk, i * elementSize, elementSize);
            values[i] = v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        long range = max - min;
        int minBits = 0;
        if (range != 0) {
            minBits = 64 - Long.numberOfLeadingZeros(range);
            if (range == (1L << minBits) - 1) {
                minBits++; // keep the all-ones code free for the fill marker
            }
        }

        GrowBuffer b = new GrowBuffer();
        b.u32(minBits);
        b.u8(8);       // width of the minimum-value field
        b.u64(min);    // minimum value
        b.u64(0);      // fill value
        if (minBits > 0) {
            int packedBytes = (elements * minBits + 7) / 8;
            byte[] packed = new byte[packedBytes];
            int bit = 0;
            for (long value : values) {
                long code = value - min;
                for (int k = minBits - 1; k >= 0; k--) {
                    packed[bit >> 3] |= (int) ((code >> k) & 1) << (7 - (bit & 7));
                    bit++;
                }
            }
            b.bytes(packed);
        }
        return b.toByteArray();
    }

    /** An unsigned little-endian integer datatype of {@code precision} significant bits (for n-bit). */
    private static byte[] nbitDatatype(int size, int precision) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x10); // version 1, class 0 (fixed point)
        b.u8(0);    // class bit field: little-endian, unsigned
        b.u8(0);
        b.u8(0);
        b.u32(size);
        b.u16(0);         // bit offset
        b.u16(precision); // bit precision
        return b.toByteArray();
    }

    /** N-bit encode: pack each element's low {@code precision} bits, MSB-first, from the chunk start. */
    private static byte[] nbitEncode(byte[] chunk, int elementSize, int precision) {
        int elements = chunk.length / elementSize;
        byte[] out = new byte[(elements * precision + 7) / 8];
        long mask = precision >= 64 ? -1L : (1L << precision) - 1;
        int bit = 0;
        for (int i = 0; i < elements; i++) {
            long value = 0;
            for (int b = 0; b < elementSize; b++) {
                value |= (long) (chunk[i * elementSize + b] & 0xff) << (8 * b);
            }
            long significant = value & mask;
            for (int k = precision - 1; k >= 0; k--) {
                out[bit >> 3] |= (int) ((significant >> k) & 1) << (7 - (bit & 7));
                bit++;
            }
        }
        return out;
    }

    /** Szip encode: read each element as an unsigned sample and run the AEC entropy coder (block size 8). */
    private static byte[] szipEncode(byte[] chunk, int elementSize) {
        int elements = chunk.length / elementSize;
        long[] samples = new long[elements];
        for (int i = 0; i < elements; i++) {
            long v = 0;
            for (int b = 0; b < elementSize; b++) {
                v |= (long) (chunk[i * elementSize + b] & 0xff) << (8 * b);
            }
            samples[i] = v;
        }
        return Aec.encode(samples, elementSize * 8, 8);
    }

    private static long signedLittleEndian(byte[] data, int offset, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            v |= (long) (data[offset + i] & 0xff) << (8 * i);
        }
        if (size < 8) {
            long signBit = 1L << (size * 8 - 1);
            if ((v & signBit) != 0) {
                v |= -(1L << (size * 8));
            }
        }
        return v;
    }

    private static void writeFilter(GrowBuffer b, int id, int flags, int... clientData) {
        b.u16(id);
        b.u16(flags);
        b.u16(clientData.length);
        for (int value : clientData) {
            b.u32(value);
        }
    }

    /** Groups the {@code j}-th byte of every element together (the shuffle filter's forward transform). */
    private static byte[] shuffle(byte[] data, int elementSize) {
        if (elementSize <= 1) {
            return data;
        }
        int elements = data.length / elementSize;
        byte[] out = new byte[data.length];
        int p = 0;
        for (int b = 0; b < elementSize; b++) {
            for (int i = 0; i < elements; i++) {
                out[p++] = data[i * elementSize + b];
            }
        }
        return out;
    }

    /** Appends the 4-byte (little-endian) Fletcher-32 checksum HDF5 uses. */
    private static byte[] appendFletcher32(byte[] data) {
        long sum1 = 0;
        long sum2 = 0;
        int words = data.length / 2;
        int i = 0;
        while (words > 0) {
            int batch = Math.min(words, 360);
            words -= batch;
            do {
                int word = ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
                sum1 += word;
                sum2 += sum1;
                i += 2;
            } while (--batch > 0);
            sum1 = (sum1 & 0xffff) + (sum1 >>> 16);
            sum2 = (sum2 & 0xffff) + (sum2 >>> 16);
        }
        if ((data.length & 1) != 0) {
            sum1 += (data[i] & 0xff) << 8;
            sum2 += sum1;
            sum1 = (sum1 & 0xffff) + (sum1 >>> 16);
            sum2 = (sum2 & 0xffff) + (sum2 >>> 16);
        }
        sum1 = (sum1 & 0xffff) + (sum1 >>> 16);
        sum2 = (sum2 & 0xffff) + (sum2 >>> 16);
        long checksum = (sum2 << 16) | sum1;
        byte[] out = java.util.Arrays.copyOf(data, data.length + 4);
        out[data.length] = (byte) checksum;
        out[data.length + 1] = (byte) (checksum >>> 8);
        out[data.length + 2] = (byte) (checksum >>> 16);
        out[data.length + 3] = (byte) (checksum >>> 24);
        return out;
    }

    private static byte[] deflate(byte[] data, int level) {
        Deflater deflater = new Deflater(level);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] scratch = new byte[1024];
        while (!deflater.finished()) {
            out.write(scratch, 0, deflater.deflate(scratch));
        }
        deflater.end();
        return out.toByteArray();
    }

    /** Splits a dataset's row-major data into full-size, fill-padded chunks in row-major chunk order. */
    private static List<byte[]> splitChunks(DatasetSpec dataset) {
        int rank = dataset.shape.length;
        int elementSize = dataset.elementSize;
        int[] grid = new int[rank];
        int chunkElements = 1;
        for (int d = 0; d < rank; d++) {
            grid[d] = (int) ((dataset.shape[d] + dataset.chunkShape[d] - 1) / dataset.chunkShape[d]);
            chunkElements *= (int) dataset.chunkShape[d];
        }
        long[] datasetStride = rowMajorStride(dataset.shape);
        long[] chunkStride = rowMajorStride(dataset.chunkShape);

        int chunkCount = 1;
        for (int g : grid) {
            chunkCount *= g;
        }
        List<byte[]> chunks = new ArrayList<>(chunkCount);
        int[] gridCoord = new int[rank];
        for (int c = 0; c < chunkCount; c++) {
            byte[] chunk = new byte[chunkElements * elementSize];
            int[] local = new int[rank];
            for (int e = 0; e < chunkElements; e++) {
                boolean inBounds = true;
                long globalIndex = 0;
                for (int d = 0; d < rank; d++) {
                    long global = gridCoord[d] * dataset.chunkShape[d] + local[d];
                    if (global >= dataset.shape[d]) {
                        inBounds = false;
                        break;
                    }
                    globalIndex += global * datasetStride[d];
                }
                if (inBounds) {
                    System.arraycopy(dataset.data, (int) (globalIndex * elementSize),
                            chunk, e * elementSize, elementSize);
                }
                increment(local, dataset.chunkShape);
            }
            chunks.add(chunk);
            increment(gridCoord, grid);
        }
        return chunks;
    }

    private static byte[] chunkedLayoutBody(long[] chunkShape, int elementSize, long fixedArrayHeaderAddress,
                                            boolean filtered) {
        int rank = chunkShape.length;
        long maxDim = elementSize;
        for (long c : chunkShape) {
            maxDim = Math.max(maxDim, c);
        }
        int encodedLength = (63 - Long.numberOfLeadingZeros(maxDim)) / 8 + 1;
        GrowBuffer b = new GrowBuffer();
        // Filtered fixed-array entries use an 8-byte stored-size field, which the library expects for
        // layout version 5; unfiltered chunks use version 4.
        b.u8(filtered ? 5 : 4);      // version
        b.u8(2);                     // layout class: chunked
        b.u8(0);                     // flags
        b.u8(rank + 1);              // dimensionality (chunk dims + element size)
        b.u8(encodedLength);
        for (long c : chunkShape) {
            b.uvar(c, encodedLength);
        }
        b.uvar(elementSize, encodedLength);
        b.u8(3);                     // index type: fixed array
        b.u8(10);                    // page bits
        b.u64(fixedArrayHeaderAddress);
        return b.toByteArray();
    }

    private static long[] rowMajorStride(long[] dims) {
        long[] stride = new long[dims.length];
        long s = 1;
        for (int i = dims.length - 1; i >= 0; i--) {
            stride[i] = s;
            s *= dims[i];
        }
        return stride;
    }

    private static void increment(int[] coord, long[] extent) {
        for (int d = coord.length - 1; d >= 0; d--) {
            if (++coord[d] < extent[d]) {
                return;
            }
            coord[d] = 0;
        }
    }

    private static void increment(int[] coord, int[] extent) {
        for (int d = coord.length - 1; d >= 0; d--) {
            if (++coord[d] < extent[d]) {
                return;
            }
            coord[d] = 0;
        }
    }

    private static int align8(long n) {
        return (int) ((n + 7) & ~7L);
    }

    private void writeGroupHeader(GrowBuffer buf, Map<String, Long> children,
                                  List<AttributeSpec> attributes, byte[] linkInfo, byte[] attributeInfo) {
        List<Message> messages = new ArrayList<>();
        // Links: a Link Info message pointing at dense storage, or an empty one plus compact Link messages.
        messages.add(new Message(2, 0x00, linkInfo != null ? linkInfo : linkInfoBody()));
        messages.add(new Message(10, 0x01, new byte[] {0, 0}));
        if (linkInfo == null) {
            for (Map.Entry<String, Long> child : children.entrySet()) {
                messages.add(new Message(6, 0x00, linkBody(child.getKey(), child.getValue())));
            }
        }
        if (attributeInfo != null) {
            messages.add(new Message(21, 0x00, attributeInfo));
        } else {
            for (AttributeSpec attribute : attributes) {
                messages.add(new Message(12, 0x00, attributeBody(attribute)));
            }
        }
        writeObjectHeader(buf, messages, 1);
    }

    /** One object-header message (type, flags, and body), framed by the version-specific header writer. */
    private record Message(int type, int flags, byte[] body) {
    }

    /** Writes an object header around {@code messages}: version-2 (checksummed) or version-1 by format. */
    private void writeObjectHeader(GrowBuffer buf, List<Message> messages, int referenceCount) {
        if (legacy) {
            writeObjectHeaderV1(buf, messages, referenceCount);
        } else {
            writeObjectHeaderV2(buf, messages);
        }
    }

    private static void writeObjectHeaderV2(GrowBuffer buf, List<Message> messages) {
        GrowBuffer framed = new GrowBuffer();
        for (Message message : messages) {
            framed.u8(message.type());
            framed.u16(message.body().length);
            framed.u8(message.flags());
            framed.bytes(message.body());
        }
        byte[] body = framed.toByteArray();
        int sizeBits = body.length <= 0xFF ? 0 : body.length <= 0xFFFF ? 1 : 2;
        int start = buf.position();
        buf.bytes(OHDR_SIGNATURE);
        buf.u8(2);
        buf.u8(sizeBits);
        buf.uvar(body.length, 1 << sizeBits);
        buf.bytes(body);
        buf.u32(buf.checksum(start, buf.position()));
    }

    /** Version-1 object header: a 12-byte prefix padded to 16, then 8-byte-aligned messages. */
    private static void writeObjectHeaderV1(GrowBuffer buf, List<Message> messages, int referenceCount) {
        int chunk0 = 0;
        for (Message message : messages) {
            chunk0 += 8 + align8(message.body().length); // 8-byte message header + padded body
        }
        buf.u8(1);                 // version
        buf.u8(0);                 // reserved
        buf.u16(messages.size());  // total number of messages
        buf.u32(referenceCount);
        buf.u32(chunk0);           // size of chunk 0's message data
        buf.u32(0);                // pad the 12-byte prefix to 16 bytes
        for (Message message : messages) {
            int padded = align8(message.body().length);
            buf.u16(message.type());
            buf.u16(padded);
            buf.u8(message.flags());
            buf.u8(0);
            buf.u8(0);
            buf.u8(0);             // reserved (3)
            buf.bytes(message.body());
            for (int i = message.body().length; i < padded; i++) {
                buf.u8(0);         // pad the body to an 8-byte boundary
            }
        }
    }

    private static final int GLOBAL_HEAP_MIN_SIZE = 4096; // HDF5 requires collections to be at least this large

    /** Writes a global-heap collection holding {@code objects}; returns their 1-based indices. */
    private static int[] writeGlobalHeap(GrowBuffer buf, List<byte[]> objects) {
        int start = buf.position();
        int usedExtents = 0;
        for (byte[] object : objects) {
            usedExtents += 16 + align8(object.length); // object header + padded data
        }
        int total = Math.max(16 + usedExtents + 16, GLOBAL_HEAP_MIN_SIZE);
        int freeExtent = total - 16 - usedExtents; // the trailing free-space object's extent

        buf.bytes(GCOL_SIGNATURE);
        buf.u8(1);
        buf.u8(0);
        buf.u8(0);
        buf.u8(0);
        buf.u64(total);
        int[] indices = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            indices[i] = i + 1;
            buf.u16(i + 1);   // object index (1-based; 0 marks free space)
            buf.u16(1);       // reference count
            buf.u32(0);       // reserved
            buf.u64(objects.get(i).length);
            buf.bytes(objects.get(i));
            while ((buf.position() - start) % 8 != 0) {
                buf.u8(0);    // pad object data to an 8-byte boundary
            }
        }
        buf.u16(0);           // free-space object: index 0
        buf.u16(0);
        buf.u32(0);
        buf.u64(freeExtent);  // its extent (this header plus the remaining free bytes)
        while (buf.position() - start < total) {
            buf.u8(0);        // materialize the free space
        }
        return indices;
    }

    /** A written fractal heap: its header address and the fixed-width heap id of each stored object. */
    private record FractalHeapResult(long headerAddress, List<byte[]> ids) {
    }

    /**
     * Writes a fractal heap holding {@code objects} in a single checksummed direct block (the form the
     * library uses for a modest dense set), and returns its header address and each object's managed
     * heap id. Larger sets that would need indirect blocks are rejected.
     */
    private static FractalHeapResult writeFractalHeap(GrowBuffer buf, List<byte[]> objects, HeapParams params) {
        int directHeader = 4 + 1 + 8 + params.offsetSize() + 4; // FHDB: sig, version, heap header, block offset, checksum
        int objectBytes = 0;
        for (byte[] object : objects) {
            objectBytes += object.length;
        }
        int used = directHeader + objectBytes;
        int blockSize = Math.max(512, Integer.highestOneBit(used - 1) << 1); // smallest power of two >= used
        if (blockSize > HEAP_MAX_DIRECT_BLOCK) {
            throw new HdfUnsupportedException("dense storage set too large for a single fractal-heap block");
        }

        List<byte[]> ids = new ArrayList<>();
        int offset = directHeader;
        for (byte[] object : objects) {
            ids.add(heapId(offset, object.length, params));
            offset += object.length;
        }

        buf.align(8);
        int directBlock = buf.position();
        buf.bytes(FHDB_SIGNATURE);
        buf.u8(0);
        int heapHeaderPatch = buf.position();
        buf.u64(0);                       // heap header address (patched once the header is written)
        buf.uvar(0, params.offsetSize()); // block offset
        int checksumPatch = buf.position();
        buf.u32(0);                       // whole-block checksum (patched below)
        for (byte[] object : objects) {
            buf.bytes(object);
        }
        while (buf.position() - directBlock < blockSize) {
            buf.u8(0);                    // zero-fill the remainder of the direct block
        }

        buf.align(8);
        int headerAddress = buf.position();
        buf.bytes(FRHP_SIGNATURE);
        buf.u8(0);
        buf.u16(params.idLength());
        buf.u16(0);                       // I/O filter length
        buf.u8(0x02);                     // flags: direct blocks are checksummed
        buf.u32(HEAP_MAX_MANAGED_OBJECT);
        buf.u64(0);                       // next huge object id
        buf.u64(UNDEFINED);               // huge-object v2 B-tree address
        buf.u64((long) blockSize - used); // free space in managed blocks
        buf.u64(UNDEFINED);               // managed-block free-space manager address
        buf.u64(blockSize);               // managed space
        buf.u64(blockSize);               // allocated managed space
        buf.u64(used);                    // managed-space iterator offset
        buf.u64(objects.size());          // number of managed objects
        buf.u64(0);                       // huge object size
        buf.u64(0);                       // number of huge objects
        buf.u64(0);                       // tiny object size
        buf.u64(0);                       // number of tiny objects
        buf.u16(HEAP_TABLE_WIDTH);
        buf.u64(blockSize);               // starting block size (== the single direct block)
        buf.u64(HEAP_MAX_DIRECT_BLOCK);   // maximum direct block size
        buf.u16(params.maxBits());        // maximum heap size (bits)
        buf.u16(1);                       // starting rows in the root indirect block
        buf.u64(directBlock);             // root block address
        buf.u16(0);                       // current rows (0 => the root is a single direct block)
        buf.u32(buf.checksum(headerAddress, buf.position()));

        buf.patchU64(heapHeaderPatch, headerAddress);
        buf.patchU32(checksumPatch, buf.checksum(directBlock, directBlock + blockSize));
        return new FractalHeapResult(headerAddress, ids);
    }

    /** A managed heap id: {@code type/version byte(0) · offset · length}. */
    private static byte[] heapId(int offset, int length, HeapParams params) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0); // managed object (type 0)
        b.uvar(offset, params.offsetSize());
        b.uvar(length, params.lengthSize());
        return b.toByteArray();
    }

    /** Writes a version-2 B-tree with a single leaf holding {@code records} (already sorted); returns its header. */
    private static long writeV2BTree(GrowBuffer buf, int type, int recordSize, List<byte[]> records) {
        buf.align(8);
        int leaf = buf.position();
        buf.bytes(BTLF_SIGNATURE);
        buf.u8(0);
        buf.u8(type);
        for (byte[] record : records) {
            buf.bytes(record);
        }
        // The checksum sits immediately after the records (covering the prefix + records)...
        buf.u32(buf.checksum(leaf, buf.position()));
        // ...then the node is padded out to the fixed node size it is allocated at on disk.
        while (buf.position() - leaf < BT2_NODE_SIZE) {
            buf.u8(0);
        }

        buf.align(8);
        int header = buf.position();
        buf.bytes(BTHD_SIGNATURE);
        buf.u8(0);
        buf.u8(type);
        buf.u32(BT2_NODE_SIZE);
        buf.u16(recordSize);
        buf.u16(0);            // depth (single leaf)
        buf.u8(100);           // split percent
        buf.u8(40);            // merge percent
        buf.u64(leaf);         // root node address
        buf.u16(records.size()); // records in the root node
        buf.u64(records.size()); // total records in the tree
        buf.u32(buf.checksum(header, buf.position()));
        return header;
    }

    /**
     * Writes an object's attributes densely (fractal heap of attribute messages + a name-indexed v2
     * B-tree) and returns the Attribute Info (message 21) body pointing at them.
     */
    private static byte[] writeDenseAttributes(GrowBuffer buf, List<AttributeSpec> attributes) {
        List<byte[]> objects = new ArrayList<>();
        for (AttributeSpec attribute : attributes) {
            objects.add(attributeBody(attribute));
        }
        FractalHeapResult heap = writeFractalHeap(buf, objects, ATTR_HEAP);

        record Hashed(int hash, byte[] record) {
        }
        List<Hashed> hashed = new ArrayList<>();
        for (int i = 0; i < attributes.size(); i++) {
            int hash = Lookup3.hashLittle(attributes.get(i).name().getBytes(StandardCharsets.UTF_8));
            GrowBuffer r = new GrowBuffer();
            r.bytes(heap.ids().get(i));  // heap id (8)
            r.u8(0);                     // message flags
            r.u32(0x0000FFFF);           // creation order (untracked)
            r.u32(hash);                 // name hash
            hashed.add(new Hashed(hash, r.toByteArray()));
        }
        hashed.sort((x, y) -> Integer.compareUnsigned(x.hash(), y.hash())); // v2 B-tree orders by hash
        List<byte[]> records = new ArrayList<>();
        for (Hashed h : hashed) {
            records.add(h.record());
        }
        long btree = writeV2BTree(buf, BT2_ATTR_NAME, 17, records);

        GrowBuffer b = new GrowBuffer();
        b.u8(0);       // version
        b.u8(0);       // flags: no creation-order index
        b.u64(heap.headerAddress());
        b.u64(btree);
        return b.toByteArray();
    }

    /**
     * Writes a group's links densely (fractal heap of Link messages + a name-indexed v2 B-tree, type 5)
     * and returns the Link Info (message 2) body pointing at them.
     */
    private static byte[] writeDenseLinks(GrowBuffer buf, Map<String, Long> children) {
        List<byte[]> objects = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Long> child : children.entrySet()) {
            objects.add(linkBody(child.getKey(), child.getValue()));
            names.add(child.getKey());
        }
        FractalHeapResult heap = writeFractalHeap(buf, objects, LINK_HEAP);

        record Hashed(int hash, byte[] record) {
        }
        List<Hashed> hashed = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            int hash = Lookup3.hashLittle(names.get(i).getBytes(StandardCharsets.UTF_8));
            GrowBuffer r = new GrowBuffer();
            r.u32(hash);                 // name hash (type-5 record leads with the hash)
            r.bytes(heap.ids().get(i));  // heap id
            hashed.add(new Hashed(hash, r.toByteArray()));
        }
        hashed.sort((x, y) -> Integer.compareUnsigned(x.hash(), y.hash()));
        List<byte[]> records = new ArrayList<>();
        for (Hashed h : hashed) {
            records.add(h.record());
        }
        long btree = writeV2BTree(buf, BT2_LINK_NAME, 4 + LINK_HEAP.idLength(), records);

        GrowBuffer b = new GrowBuffer();
        b.u8(0);       // version
        b.u8(0);       // flags: no creation-order tracking
        b.u64(heap.headerAddress());
        b.u64(btree);
        return b.toByteArray();
    }

    private static byte[] vlenIds(List<byte[]> payloads, int[] elementCounts, long collection, int[] indices) {
        GrowBuffer b = new GrowBuffer();
        for (int i = 0; i < payloads.size(); i++) {
            // A vlen ID's length field is the string's byte length or the sequence's element count.
            b.u32(elementCounts != null ? elementCounts[i] : payloads.get(i).length);
            b.u64(collection);
            b.u32(indices[i]);
        }
        return b.toByteArray();
    }

    private static byte[] attributeBody(AttributeSpec attribute) {
        byte[] name = (attribute.name + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] dataspace = dataspaceBody(attribute.shape);
        GrowBuffer b = new GrowBuffer();
        b.u8(3);
        b.u8(0x00);
        b.u16(name.length);
        b.u16(attribute.datatype.length);
        b.u16(dataspace.length);
        b.u8(0);
        b.bytes(name);
        b.bytes(attribute.datatype);
        b.bytes(dataspace);
        b.bytes(attribute.data);
        return b.toByteArray();
    }

    private static byte[] dataspaceBody(long[] shape) {
        GrowBuffer b = new GrowBuffer();
        b.u8(2);
        b.u8(shape.length);
        b.u8(0x00);
        b.u8(shape.length == 0 ? 0 : 1);
        for (long dimension : shape) {
            b.u64(dimension);
        }
        return b.toByteArray();
    }

    /** The Fill Value message body: the default (version 3, undefined) or a defined custom value. */
    private static byte[] fillValueBody(byte[] fill) {
        if (fill == null) {
            return new byte[] {0x03, 0x0a}; // version 3; fill value not defined -> reads back as zero
        }
        GrowBuffer b = new GrowBuffer();
        b.u8(3);        // version
        b.u8(0x2a);     // flags: alloc/fill time + fill-value-defined (bit 5)
        b.u32(fill.length);
        b.bytes(fill);
        return b.toByteArray();
    }

    private static byte[] contiguousLayoutBody(long address, long size) {
        GrowBuffer b = new GrowBuffer();
        b.u8(3);
        b.u8(1);
        b.u64(address);
        b.u64(size);
        return b.toByteArray();
    }

    private static byte[] linkInfoBody() {
        GrowBuffer b = new GrowBuffer();
        b.u8(0);
        b.u8(0);
        b.u64(UNDEFINED);
        b.u64(UNDEFINED);
        return b.toByteArray();
    }

    private static byte[] linkBody(String name, long targetHeaderAddress) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xFF) {
            throw new IllegalArgumentException("link name too long: " + name);
        }
        GrowBuffer b = new GrowBuffer();
        b.u8(1);
        b.u8(0x00);
        b.u8(nameBytes.length);
        b.bytes(nameBytes);
        b.u64(targetHeaderAddress);
        return b.toByteArray();
    }

    /**
     * Writes a legacy (symbol-table) group: a local heap of link names, a version-1 group B-tree with a
     * single symbol-table node (entries sorted by name), and a version-1 object header carrying the
     * Symbol Table message. Returns the group's header plus its B-tree and heap (for the parent's
     * scratch-pad cache and the superblock's root entry).
     */
    private GroupResult writeSymbolTableGroup(GrowBuffer buf, List<SymbolChild> children,
                                              List<AttributeSpec> attributes) {
        if (children.size() > 2 * GROUP_LEAF_K) {
            throw new HdfUnsupportedException("a group with more than " + (2 * GROUP_LEAF_K)
                    + " children is not written in the earliest format");
        }
        // Local heap: 8 reserved bytes (offset 0 = the empty name), then each name, null-terminated and
        // padded to an 8-byte boundary.
        Map<String, Integer> nameOffsets = new LinkedHashMap<>();
        int dataSize = 8;
        for (SymbolChild child : children) {
            nameOffsets.put(child.name(), dataSize);
            dataSize += align8(child.name().getBytes(StandardCharsets.UTF_8).length + 1);
        }
        buf.align(8);
        int heapDataAddress = buf.position();
        for (int i = 0; i < 8; i++) {
            buf.u8(0);
        }
        for (SymbolChild child : children) {
            byte[] name = child.name().getBytes(StandardCharsets.UTF_8);
            buf.bytes(name);
            for (int i = name.length; i < align8(name.length + 1); i++) {
                buf.u8(0);
            }
        }
        buf.align(8);
        int heapHeaderAddress = buf.position();
        buf.bytes(HEAP_SIGNATURE);
        buf.u8(0);            // version
        buf.u8(0);
        buf.u8(0);
        buf.u8(0);            // reserved (3)
        buf.u64(dataSize);            // data segment size
        buf.u64(1);                  // free-list head offset (1 = no free blocks)
        buf.u64(heapDataAddress);    // data segment address

        // Symbol-table node: entries sorted by name, padded to its fixed allocated size.
        List<SymbolChild> sorted = new ArrayList<>(children);
        sorted.sort((a, b) -> a.name().compareTo(b.name()));
        buf.align(8);
        int snodAddress = buf.position();
        buf.bytes(SNOD_SIGNATURE);
        buf.u8(1);            // version
        buf.u8(0);            // reserved
        buf.u16(sorted.size());
        for (SymbolChild child : sorted) {
            buf.u64(nameOffsets.get(child.name()));  // link name offset
            buf.u64(child.headerAddress());          // object header address
            buf.u32(child.cacheType());
            buf.u32(0);                              // reserved
            buf.u64(child.cacheType() == 1 ? child.btree() : 0); // scratch pad: B-tree + heap for a group
            buf.u64(child.cacheType() == 1 ? child.heap() : 0);
        }
        int snodSize = 8 + 2 * GROUP_LEAF_K * SYMBOL_ENTRY_SIZE;
        while (buf.position() - snodAddress < snodSize) {
            buf.u8(0);
        }

        // Group version-1 B-tree: a single leaf node with one entry pointing at the symbol-table node.
        buf.align(8);
        int btreeAddress = buf.position();
        buf.bytes(TREE_SIGNATURE);
        buf.u8(0);            // node type: group
        buf.u8(0);            // node level: leaf
        buf.u16(1);           // entries used
        buf.u64(UNDEFINED);   // left sibling
        buf.u64(UNDEFINED);   // right sibling
        buf.u64(0);           // key 0: heap offset before the first name
        buf.u64(snodAddress); // child 0: the symbol-table node
        buf.u64(sorted.isEmpty() ? 0 : nameOffsets.get(sorted.get(sorted.size() - 1).name())); // key 1
        int btreeSize = 8 + 2 * 8 + (2 * GROUP_INTERNAL_K + 1) * 8 + 2 * GROUP_INTERNAL_K * 8;
        while (buf.position() - btreeAddress < btreeSize) {
            buf.u8(0);
        }

        // Version-1 object header: a Symbol Table message plus any attributes.
        buf.align(8);
        int headerAddress = buf.position();
        GrowBuffer symbolTable = new GrowBuffer();
        symbolTable.u64(btreeAddress);
        symbolTable.u64(heapHeaderAddress);
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(17, 0x00, symbolTable.toByteArray()));
        for (AttributeSpec attribute : attributes) {
            messages.add(new Message(12, 0x00, attributeBody(attribute)));
        }
        writeObjectHeader(buf, messages, 1);
        return new GroupResult(headerAddress, btreeAddress, heapHeaderAddress);
    }

    /** The original version-0 superblock: the root group is reached through a symbol-table entry. */
    private byte[] superblockV0(long rootAddress, long endOfFile) {
        GrowBuffer sb = new GrowBuffer();
        sb.bytes(HDF5_SIGNATURE);
        sb.u8(0);   // superblock version
        sb.u8(0);   // free-space storage version
        sb.u8(0);   // root group symbol-table entry version
        sb.u8(0);   // reserved
        sb.u8(0);   // shared header message format version
        sb.u8(8);   // size of offsets
        sb.u8(8);   // size of lengths
        sb.u8(0);   // reserved
        sb.u16(GROUP_LEAF_K);
        sb.u16(GROUP_INTERNAL_K);
        sb.u32(0);  // file consistency flags
        sb.u64(0);          // base address
        sb.u64(UNDEFINED);  // free-space info address
        sb.u64(endOfFile);  // end-of-file address
        sb.u64(UNDEFINED);  // driver info block address
        sb.u64(0);              // root symbol-table entry: link name offset
        sb.u64(rootAddress);    // root symbol-table entry: object header address
        sb.u32(1);              // cache type: group
        sb.u32(0);              // reserved
        sb.u64(legacyRootBtree);// scratch pad: root B-tree
        sb.u64(legacyRootHeap); // scratch pad: root local heap
        return sb.toByteArray();
    }

    private static byte[] superblock(long rootAddress, long endOfFile) {
        GrowBuffer sb = new GrowBuffer();
        sb.bytes(HDF5_SIGNATURE);
        sb.u8(3);
        sb.u8(8);
        sb.u8(8);
        sb.u8(0);
        sb.u64(0);
        sb.u64(UNDEFINED);
        sb.u64(endOfFile);
        sb.u64(rootAddress);
        sb.u32(Lookup3.hashLittle(sb.toByteArray()));
        return sb.toByteArray();
    }

    private static void requireElementCount(long[] shape, int length) {
        if (elementCount(shape) != length) {
            throw new IllegalArgumentException(
                    "shape implies " + elementCount(shape) + " elements but data has " + length);
        }
    }

    private static long elementCount(long[] shape) {
        long count = 1;
        for (long d : shape) {
            count *= d;
        }
        return count;
    }

    /** Minimum number of bytes needed to hold an unsigned value up to {@code size} (member offsets). */
    private static int byteWidthFor(int size) {
        int bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, size));
        return Math.max(1, (bits + 7) / 8);
    }

    /** Builds a compound (class 6, version 5) datatype message: members packed at the given offsets. */
    private static byte[] compoundDatatype(CompoundField[] fields, int[] offsets, int recordSize) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x56); // version 5, class 6 (compound)
        b.u8(fields.length & 0xFF);
        b.u8((fields.length >>> 8) & 0xFF);
        b.u8(0);
        b.u32(recordSize);
        int offsetWidth = byteWidthFor(recordSize);
        for (int i = 0; i < fields.length; i++) {
            b.bytes((fields[i].name + "\0").getBytes(StandardCharsets.US_ASCII)); // null-terminated name
            b.uvar(offsets[i], offsetWidth);
            b.bytes(fields[i].datatype);
        }
        return b.toByteArray();
    }

    /** Builds a variable-length sequence (class 9, version 1) datatype message over the given base. */
    private static byte[] vlenSequenceDatatype(byte[] base) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x19); // version 1, class 9 (variable-length)
        b.u8(0x00); // bit field: vlen type 0 = sequence (1 would be string)
        b.u8(0x00);
        b.u8(0x00);
        b.u32(16); // size = the 16-byte global-heap id
        b.bytes(base);
        return b.toByteArray();
    }

    /** Builds an array (class 10, version 5) datatype message with the given element shape and base. */
    private static byte[] arrayDatatype(int[] arrayDims, int baseSize, byte[] base) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x5A); // version 5, class 10 (array)
        b.u8(0);
        b.u8(0);
        b.u8(0);
        b.u32(product(arrayDims) * baseSize);
        b.u8(arrayDims.length); // rank
        for (int dimension : arrayDims) {
            b.u32(dimension);
        }
        b.bytes(base);
        return b.toByteArray();
    }

    /** Builds a native complex (class 11, version 5) datatype message over a {@code float64} base. */
    private static byte[] complex128Datatype() {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x5B); // version 5, class 11 (complex)
        b.u8(0x01);
        b.u8(0);
        b.u8(0);
        b.u32(16);
        b.bytes(DATATYPE_FLOAT64);
        return b.toByteArray();
    }

    private static int product(int[] values) {
        int p = 1;
        for (int v : values) {
            p *= v;
        }
        return p;
    }

    private static void requireArrayData(long[] shape, int perElement, int dataLength) {
        long expected = elementCount(shape) * perElement;
        if (dataLength != expected) {
            throw new IllegalArgumentException(
                    "array dataset expects " + expected + " values but data has " + dataLength);
        }
    }

    private static void putDoubleLittleEndian(byte[] out, int offset, double value) {
        long v = Double.doubleToLongBits(value);
        for (int b = 0; b < 8; b++) {
            out[offset + b] = (byte) (v >>> (8 * b));
        }
    }

    private static byte[] float32Bytes(float[] data) {
        byte[] out = new byte[data.length * 4];
        for (int i = 0; i < data.length; i++) {
            int v = Float.floatToIntBits(data[i]);
            out[i * 4] = (byte) v;
            out[i * 4 + 1] = (byte) (v >>> 8);
            out[i * 4 + 2] = (byte) (v >>> 16);
            out[i * 4 + 3] = (byte) (v >>> 24);
        }
        return out;
    }

    /** Builds an enumerated (class 8, version 5) datatype message over a 32-bit base type. */
    private static byte[] enumDatatype(EnumType type) {
        GrowBuffer b = new GrowBuffer();
        int members = type.names.size();
        b.u8(0x58); // version 5, class 8 (enumerated)
        b.u8(members & 0xFF);
        b.u8((members >>> 8) & 0xFF);
        b.u8(0);
        b.u32(4); // size = base type size
        b.bytes(DATATYPE_INT32);
        for (String name : type.names) {
            b.bytes((name + "\0").getBytes(StandardCharsets.US_ASCII));
        }
        for (int value : type.values) {
            b.u32(value);
        }
        return b.toByteArray();
    }

    private static int align8(int n) {
        return (n + 7) & ~7;
    }

    private static byte[] intBytes(int[] data) {
        byte[] out = new byte[data.length * 4];
        for (int i = 0; i < data.length; i++) {
            int v = data[i];
            out[i * 4] = (byte) v;
            out[i * 4 + 1] = (byte) (v >>> 8);
            out[i * 4 + 2] = (byte) (v >>> 16);
            out[i * 4 + 3] = (byte) (v >>> 24);
        }
        return out;
    }

    private static byte[] shortBytes(short[] data) {
        byte[] out = new byte[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = (byte) data[i];
            out[i * 2 + 1] = (byte) (data[i] >>> 8);
        }
        return out;
    }

    private static byte[] longBytes(long[] data) {
        byte[] out = new byte[data.length * 8];
        for (int i = 0; i < data.length; i++) {
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (data[i] >>> (8 * b));
            }
        }
        return out;
    }

    /** A fixed-length string (class 3) datatype message: null-padded, ASCII, of the given byte size. */
    private static byte[] fixedStringDatatype(int size) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x13); // version 1, class 3 (string)
        b.u8(0x01); // bit field: null-pad, ASCII
        b.u8(0);
        b.u8(0);
        b.u32(size);
        return b.toByteArray();
    }

    private static byte[] doubleBytes(double[] data) {
        byte[] out = new byte[data.length * 8];
        for (int i = 0; i < data.length; i++) {
            long v = Double.doubleToLongBits(data[i]);
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (v >>> (8 * b));
            }
        }
        return out;
    }

    // --------------------------------------------------------------- spec tree

    private static final class GroupSpec {
        String name = "";
        final List<GroupSpec> groups = new ArrayList<>();
        final List<DatasetSpec> datasets = new ArrayList<>();
        final List<AttributeSpec> attributes = new ArrayList<>();
    }

    private static final class DatasetSpec {
        final String name;
        final byte[] datatype;
        final int elementSize;
        final long[] shape;
        final long[] chunkShape;        // null for contiguous storage
        final byte[] data;              // inline element bytes, or null for vlen strings/sequences
        final List<byte[]> vlenStrings; // vlen payloads (string bytes or sequence element bytes), or null
        int[] vlenElementCounts;        // per-element sequence lengths (element counts); null for strings
        List<String> referenceTargets;  // object-reference target paths, or null
        byte[] fillValue;                // custom fill value (datatype-order bytes), or null for the default 0
        final List<AttributeSpec> attributes = new ArrayList<>();
        int deflateLevel = -1;          // -1 = no compression
        boolean shuffle;
        boolean fletcher32;
        boolean scaleOffset;
        boolean szip;
        int nbitPrecision = -1;         // -1 = no n-bit filter

        DatasetSpec(String name, byte[] datatype, int elementSize, long[] shape, long[] chunkShape,
                    byte[] data, List<byte[]> vlenStrings) {
            this.name = name;
            this.datatype = datatype;
            this.elementSize = elementSize;
            this.shape = shape;
            this.chunkShape = chunkShape;
            this.data = data;
            this.vlenStrings = vlenStrings;
        }
    }

    private record AttributeSpec(String name, byte[] datatype, long[] shape, byte[] data) {
    }
}
