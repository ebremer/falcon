package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.checksum.Fletcher32;
import com.ebremer.falcon.hdf5.checksum.Lookup3;
import com.ebremer.falcon.hdf5.filter.Filters;
import com.ebremer.falcon.hdf5.filter.ScaleOffset;
import com.ebremer.falcon.hdf5.filter.Szip;
import com.ebremer.falcon.hdf5.write.GrowBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // A single-level group B-tree of full symbol-table nodes: the earliest format's limit per group.
    private static final int MAX_LEGACY_CHILDREN = 2 * GROUP_LEAF_K * 2 * GROUP_INTERNAL_K;

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
    private static final int HEAP_MAX_MANAGED_OBJECT = 4096;     // libhdf5's default; raised for a larger object
    // Object-header message bodies: a version-2 header stores the size in 16 bits, and a version-1
    // header pads each body to 8 bytes within that limit.
    private static final int MAX_MESSAGE_BODY = 65528;
    private static final int MAX_COMPACT_DATA = MAX_MESSAGE_BODY - 4; // the compact layout message's own fields
    // An attribute message must also fit, as one managed object, in a dense-storage heap's direct block.
    private static final int MAX_ATTRIBUTE_MESSAGE = HEAP_MAX_DIRECT_BLOCK - (4 + 1 + 8 + 5 + 4);
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
    private final GroupWriter rootWriter;
    // Variable-length data lives in global-heap collections shared by every dataset, written last; each
    // vlen id's collection address is patched in once the collections are placed.
    private final List<List<byte[]>> heapCollections = new ArrayList<>();
    private int lastCollectionBytes;
    private final List<HeapIdPatch> heapIdPatches = new ArrayList<>();
    private final Map<String, Long> objectAddresses = new HashMap<>();     // absolute path -> object header address
    private final List<PendingReference> pendingReferences = new ArrayList<>();
    private long legacyRootBtree = UNDEFINED;  // root group's symbol-table B-tree / local heap (legacy superblock)
    private long legacyRootHeap = UNDEFINED;
    private final Lifecycle lifecycle = new Lifecycle();

    /** Whether the writer (and every group and dataset handle it gave out) still accepts additions. */
    private static final class Lifecycle {
        private boolean closed;

        void check() {
            if (closed) {
                throw new HdfClosedException("the HDF5 writer is closed");
            }
        }
    }

    /** On-disk format version: {@link #EARLIEST} writes the original (v0 superblock, symbol-table
     * groups, v1 object headers); {@link #LATEST} the modern checksummed format. */
    public enum Format {
        EARLIEST,
        LATEST
    }

    private Hdf5Writer(Path path, Format format) {
        this.path = path;
        this.legacy = format == Format.EARLIEST;
        this.rootWriter = new GroupWriter(root, legacy, lifecycle);
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

    /**
     * Writes the file and closes the writer. The file is written beside {@code path} under a temporary
     * name and then moved into place, so {@code path} ends up either as the complete new file or as it
     * was. If writing fails, nothing is left behind and the writer stays open, so the cause (such as a
     * reference to an object never added) can be fixed and {@code close()} called again. Once the file
     * is written, or after {@link #abort()}, further calls do nothing, and adding to the writer throws
     * {@link HdfClosedException}.
     *
     * <p>{@code close()} cannot tell that the code building the file failed: inside
     * try-with-resources, call {@link #abort()} on failure to avoid writing what was added so far.
     */
    @Override
    public void close() throws IOException {
        if (lifecycle.closed) {
            return;
        }
        byte[] image;
        try {
            image = build();
        } catch (HdfException | IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new HdfException("could not write " + path + ": " + e, e);
        }
        Path target = path.toAbsolutePath();
        Path temp = target.resolveSibling("." + target.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
        try {
            Files.write(temp, image, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        lifecycle.closed = true;
    }

    /**
     * Closes the writer without writing anything: a file already at {@code path} is left as it was.
     * Use it when building the file failed part-way:
     *
     * <pre>{@code
     * Hdf5Writer w = Hdf5Writer.create(path);
     * try {
     *     ... build ...
     *     w.close();
     * } catch (RuntimeException | IOException e) {
     *     w.abort();
     *     throw e;
     * }
     * }</pre>
     */
    public void abort() {
        lifecycle.closed = true;
    }

    /** True until the file is written or the writer aborted. */
    public boolean isOpen() {
        return !lifecycle.closed;
    }

    /** Serializes the whole file. Repeatable: a failed attempt leaves no state behind. */
    private byte[] build() {
        objectAddresses.clear();
        pendingReferences.clear();
        heapCollections.clear();
        heapIdPatches.clear();
        lastCollectionBytes = 0;
        GrowBuffer buf = new GrowBuffer();
        buf.reserve(legacy ? LEGACY_SUPERBLOCK_SIZE : SUPERBLOCK_SIZE);
        GroupResult rootResult = writeGroup(buf, root, "");
        long rootAddress = rootResult.headerAddress();
        legacyRootBtree = rootResult.btreeAddress();
        legacyRootHeap = rootResult.heapAddress();
        objectAddresses.put("/", rootAddress);
        writeGlobalHeaps(buf);
        resolveReferences(buf);
        long endOfFile = buf.position();
        buf.patchBytes(0, legacy
                ? superblockV0(rootAddress, endOfFile)
                : superblock(rootAddress, endOfFile));
        return buf.toByteArray();
    }

    // --------------------------------------------------------------- API handles

    /** Builds a group: datasets, subgroups, and attributes. */
    public static final class GroupWriter {
        private final GroupSpec spec;
        private final boolean legacy;
        private final Lifecycle lifecycle;

        private GroupWriter(GroupSpec spec, boolean legacy, Lifecycle lifecycle) {
            this.spec = spec;
            this.legacy = legacy;
            this.lifecycle = lifecycle;
        }

        public DatasetWriter intDataset(String name, int[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT32, 4, shape, null, intBytes(data), null));
        }

        public DatasetWriter doubleDataset(String name, double[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT64, 8, shape, null, doubleBytes(data), null));
        }

        /** A signed 8-bit integer dataset. */
        public DatasetWriter byteDataset(String name, byte[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT8, 1, shape, null, data.clone(), null));
        }

        /** A signed 16-bit integer dataset. */
        public DatasetWriter shortDataset(String name, short[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT16, 2, shape, null, shortBytes(data), null));
        }

        /** A signed 64-bit integer dataset. */
        public DatasetWriter longDataset(String name, long[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_INT64, 8, shape, null, longBytes(data), null));
        }

        /** A 32-bit floating-point dataset. */
        public DatasetWriter floatDataset(String name, float[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT32, 4, shape, null, float32Bytes(data), null));
        }

        /**
         * A fixed-length string dataset. Each element is stored, UTF-8 encoded, in {@code length} bytes
         * (the longest string's byte length if not given), null-padded. The datatype's character set is
         * UTF-8 if any string is non-ASCII, else ASCII.
         */
        public DatasetWriter fixedStringDataset(String name, String[] data, long[] shape) {
            lifecycle.check();
            int length = 1;
            for (String s : data) {
                length = Math.max(length, s.getBytes(StandardCharsets.UTF_8).length);
            }
            return fixedStringDataset(name, data, shape, length);
        }

        /**
         * A fixed-length string dataset with an explicit per-element byte {@code length}. A string whose
         * UTF-8 encoding is longer is truncated at the last whole character that fits.
         */
        public DatasetWriter fixedStringDataset(String name, String[] data, long[] shape, int length) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            if (length < 1) {
                throw new IllegalArgumentException("fixed-length strings need at least 1 byte, not " + length);
            }
            byte[] bytes = new byte[Math.multiplyExact(data.length, length)];
            boolean utf8 = false;
            for (int i = 0; i < data.length; i++) {
                byte[] s = data[i].getBytes(StandardCharsets.UTF_8);
                int n = Math.min(s.length, length);
                while (n < s.length && n > 0 && (s[n] & 0xC0) == 0x80) {
                    n--; // never split a multi-byte character
                }
                System.arraycopy(s, 0, bytes, i * length, n);
                for (int b = 0; b < n; b++) {
                    utf8 |= s[b] < 0;
                }
            }
            return addDataset(new DatasetSpec(name, fixedStringDatatype(length, utf8), length, shape, null, bytes, null));
        }

        /** A chunked {@code int32} dataset (fixed-array index). */
        public DatasetWriter intChunkedDataset(String name, int[] data, long[] shape, long[] chunkShape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            requireChunkShape(shape, chunkShape, 4);
            return addDataset(new DatasetSpec(name, DATATYPE_INT32, 4, shape, chunkShape.clone(), intBytes(data), null));
        }

        /** A chunked {@code float64} dataset (fixed-array index). */
        public DatasetWriter doubleChunkedDataset(String name, double[] data, long[] shape, long[] chunkShape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            requireChunkShape(shape, chunkShape, 8);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT64, 8, shape, chunkShape.clone(), doubleBytes(data), null));
        }

        /** A variable-length UTF-8 string dataset (values stored in a global heap). */
        public DatasetWriter stringDataset(String name, String[] data, long[] shape) {
            lifecycle.check();
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
            lifecycle.check();
            long count = elementCount(shape);
            int recordSize = 0;
            int[] offsets = new int[fields.length];
            Set<String> names = new HashSet<>();
            for (int i = 0; i < fields.length; i++) {
                if (!names.add(fields[i].name)) {
                    throw new IllegalArgumentException("duplicate compound field name \"" + fields[i].name + "\"");
                }
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
            byte[] datatype = compoundDatatype(fields, offsets, recordSize, legacy);
            requireMessageSize(datatype.length, "the compound datatype of '" + name + "'");
            return addDataset(new DatasetSpec(name, datatype, recordSize, shape, null, data, null));
        }

        /** An enumerated dataset over a 32-bit base type: each value must be one of {@code type}'s codes. */
        public DatasetWriter enumDataset(String name, long[] shape, EnumType type, int[] values) {
            lifecycle.check();
            requireElementCount(shape, values.length);
            byte[] datatype = enumDatatype(type, legacy);
            requireMessageSize(datatype.length, "the enum datatype of '" + name + "'");
            return addDataset(new DatasetSpec(name, datatype, 4, shape, null, intBytes(values), null));
        }

        /**
         * A dataset whose every element is a fixed-shape {@code float32} array. {@code data} holds all
         * elements' sub-arrays concatenated row-major (element count &times; {@code prod(arrayDims)} values).
         */
        public DatasetWriter float32ArrayDataset(String name, long[] shape, int[] arrayDims, float[] data) {
            lifecycle.check();
            int perElement = product(arrayDims);
            requireArrayData(shape, perElement, data.length);
            return addDataset(new DatasetSpec(name, arrayDatatype(arrayDims, 4, DATATYPE_FLOAT32, legacy),
                    perElement * 4, shape, null, float32Bytes(data), null));
        }

        /** A dataset whose every element is a fixed-shape {@code int32} array (see {@link #float32ArrayDataset}). */
        public DatasetWriter int32ArrayDataset(String name, long[] shape, int[] arrayDims, int[] data) {
            lifecycle.check();
            int perElement = product(arrayDims);
            requireArrayData(shape, perElement, data.length);
            return addDataset(new DatasetSpec(name, arrayDatatype(arrayDims, 4, DATATYPE_INT32, legacy),
                    perElement * 4, shape, null, intBytes(data), null));
        }

        /** A native complex-number dataset (128-bit: {@code float64} real and imaginary parts). */
        public DatasetWriter complexDataset(String name, long[] shape, double[] real, double[] imaginary) {
            lifecycle.check();
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
            lifecycle.check();
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
            lifecycle.check();
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
            lifecycle.check();
            requireElementCount(shape, targets.length);
            DatasetSpec spec = new DatasetSpec(name, DATATYPE_OBJECT_REFERENCE, 8, shape, null,
                    new byte[targets.length * 8], null);
            spec.referenceTargets = java.util.Arrays.asList(targets);
            return addDataset(spec);
        }

        /**
         * A subgroup. Link names (of groups and datasets) must be non-empty, unique within their group,
         * and not {@code "."}, and may not contain {@code '/'} or NUL.
         */
        public GroupWriter group(String name) {
            lifecycle.check();
            claimLinkName(spec, name, legacy);
            GroupSpec child = new GroupSpec();
            child.name = name;
            spec.groups.add(child);
            return new GroupWriter(child, legacy, lifecycle);
        }

        public GroupWriter intAttribute(String name, int[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            addAttribute(spec.attributes, spec.attributeNames, new AttributeSpec(name, DATATYPE_INT32, shape, intBytes(data)), legacy);
            return this;
        }

        public GroupWriter doubleAttribute(String name, double[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            addAttribute(spec.attributes, spec.attributeNames, new AttributeSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data)), legacy);
            return this;
        }

        private DatasetWriter addDataset(DatasetSpec dataset) {
            claimLinkName(spec, dataset.name, legacy);
            spec.datasets.add(dataset);
            return new DatasetWriter(dataset, legacy, lifecycle);
        }

        private void requireChunkShape(long[] shape, long[] chunkShape, int elementSize) {
            if (legacy) {
                throw new HdfUnsupportedException("chunked datasets are not written in the earliest format");
            }
            if (chunkShape == null || chunkShape.length != shape.length) {
                throw new IllegalArgumentException("chunk shape " + java.util.Arrays.toString(chunkShape)
                        + " must have the dataset's rank " + shape.length);
            }
            if (shape.length == 0) {
                throw new IllegalArgumentException("a scalar dataset cannot be chunked");
            }
            long bytes = elementSize;
            for (long c : chunkShape) {
                if (c < 1) {
                    throw new IllegalArgumentException("chunk dimensions must be at least 1: "
                            + java.util.Arrays.toString(chunkShape));
                }
                bytes = c > Integer.MAX_VALUE ? Long.MAX_VALUE : Math.min(Long.MAX_VALUE / 2, bytes * c);
            }
            if (bytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("a chunk of " + java.util.Arrays.toString(chunkShape)
                        + " elements exceeds 2 GiB");
            }
        }
    }

    /** Validates a link name and reserves it in {@code group}; see {@link GroupWriter#group}. */
    private static void claimLinkName(GroupSpec group, String name, boolean legacy) {
        requireName(name, "link");
        if (legacy && group.linkNames.size() >= MAX_LEGACY_CHILDREN) {
            throw new HdfUnsupportedException("a group with more than " + MAX_LEGACY_CHILDREN
                    + " children is not written in the earliest format (it would need a multi-level B-tree)");
        }
        if (name.equals(".") || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("a link name may not be \".\" or contain '/': \"" + name + "\"");
        }
        if (!group.linkNames.add(name)) {
            throw new IllegalArgumentException("this group already has a link named \"" + name + "\"");
        }
    }

    private static void requireName(String name, String what) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(what + " name must not be empty");
        }
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " name must not contain NUL: \"" + name.replace('\0', '?') + "\"");
        }
    }

    /**
     * Adds an attribute after checking its name (non-empty, no NUL, unique on its object) and its size:
     * its header message must stay under 64 KiB, the limit of an object-header message and of a managed
     * object in dense storage.
     */
    private static void addAttribute(List<AttributeSpec> attributes, Set<String> names, AttributeSpec attribute,
                                     boolean legacy) {
        requireName(attribute.name(), "attribute");
        int size = attributeBody(attribute, legacy).length;
        if (size > MAX_ATTRIBUTE_MESSAGE) {
            throw new IllegalArgumentException("attribute '" + attribute.name() + "' needs a " + size
                    + "-byte header message; at most " + MAX_ATTRIBUTE_MESSAGE + " bytes fit (store large values in a dataset)");
        }
        if (!names.add(attribute.name())) {
            throw new IllegalArgumentException("duplicate attribute name \"" + attribute.name() + "\"");
        }
        attributes.add(attribute);
    }

    private static void requireMessageSize(int size, String what) {
        if (size > MAX_MESSAGE_BODY) {
            throw new IllegalArgumentException(what + " needs a " + size + "-byte header message; at most "
                    + MAX_MESSAGE_BODY + " bytes fit");
        }
    }

    /**
     * Attaches attributes and (for chunked datasets) filters to a dataset. Filters form a pipeline
     * applied to each chunk in the order they are added (and reversed on read), as in libhdf5; each may
     * be added once.
     */
    public static final class DatasetWriter {
        private final DatasetSpec spec;
        private final boolean legacy;
        private final Lifecycle lifecycle;

        private DatasetWriter(DatasetSpec spec, boolean legacy, Lifecycle lifecycle) {
            this.spec = spec;
            this.legacy = legacy;
            this.lifecycle = lifecycle;
        }

        /** Compresses each chunk with deflate (gzip) at the given level (0&ndash;9). Chunked datasets only. */
        public DatasetWriter deflate(int level) {
            lifecycle.check();
            requireChunked();
            if (level < 0 || level > 9) {
                throw new IllegalArgumentException("deflate level must be 0-9, not " + level);
            }
            addFilter(Filters.DEFLATE, level);
            return this;
        }

        /** Byte-shuffles each chunk (grouping like-position bytes) to improve compression. Chunked only. */
        public DatasetWriter shuffle() {
            lifecycle.check();
            requireChunked();
            addFilter(Filters.SHUFFLE, 0);
            return this;
        }

        /** Appends a Fletcher-32 checksum to each stored chunk. Chunked datasets only. */
        public DatasetWriter fletcher32() {
            lifecycle.check();
            requireChunked();
            addFilter(Filters.FLETCHER32, 0);
            return this;
        }

        /**
         * Losslessly compresses integer chunks with the scale-offset filter, exactly as libhdf5 does with
         * automatic minbits: elements equal to the fill value get a reserved code, the rest are stored
         * as their offset from the chunk minimum in as few bits as the range needs. Chunked integer
         * datasets only, as the first filter.
         */
        public DatasetWriter scaleOffset() {
            lifecycle.check();
            requireChunked();
            requireInteger("scaleOffset");
            requireFirst("scaleOffset");
            addFilter(Filters.SCALEOFFSET, 0);
            return this;
        }

        /**
         * Stores each element in only its {@code precision} low bits with the n-bit filter (an unsigned
         * integer datatype of that precision). Chunked integer datasets only, as the first filter; every
         * value (and the fill value, if set) must be non-negative and fit in {@code precision} bits.
         *
         * @throws IllegalArgumentException if a value does not fit
         */
        public DatasetWriter nbit(int precision) {
            lifecycle.check();
            requireChunked();
            requireInteger("nbit");
            requireFirst("nbit");
            if (precision < 1 || precision > spec.elementSize * 8) {
                throw new IllegalArgumentException("n-bit precision must be 1-" + spec.elementSize * 8 + ", not " + precision);
            }
            int size = spec.elementSize;
            for (int i = 0; i < spec.data.length / size; i++) {
                long value = littleEndianSigned(spec.data, i * size, size);
                if (!fitsUnsigned(value, precision)) {
                    throw new IllegalArgumentException("n-bit(" + precision + ") stores unsigned " + precision
                            + "-bit values, but element " + i + " is " + value);
                }
            }
            if (spec.fillValue != null && !fitsUnsigned(littleEndianSigned(spec.fillValue, 0, size), precision)) {
                throw new IllegalArgumentException("the fill value does not fit n-bit(" + precision + ")");
            }
            spec.nbitPrecision = precision;
            addFilter(Filters.NBIT, precision);
            return this;
        }

        /**
         * Compresses chunks with the szip filter in the form libhdf5 + libaec store it (entropy coding,
         * 8 pixels per block; Falcon's pure-Java CCSDS encoder does not apply nearest-neighbour
         * preprocessing). Chunked integer or floating-point datasets; only {@link #shuffle()} may come
         * before it. A chunk that szip cannot shrink is stored unfiltered, as libhdf5 does.
         */
        public DatasetWriter szip() {
            lifecycle.check();
            requireChunked();
            int typeClass = spec.datatype[0] & 0x0F;
            if (typeClass != 0 && typeClass != 1) {
                throw new IllegalStateException("szip requires an integer or floating-point dataset");
            }
            for (FilterSpec filter : spec.filters) {
                if (filter.id() != Filters.SHUFFLE) {
                    throw new IllegalStateException("szip must come before every filter except shuffle");
                }
            }
            if (elementCount(spec.chunkShape) < SZIP_PIXELS_PER_BLOCK) {
                throw new IllegalStateException("szip needs at least " + SZIP_PIXELS_PER_BLOCK + " elements per chunk");
            }
            addFilter(Filters.SZIP, 0);
            return this;
        }

        private void requireChunked() {
            if (spec.chunkShape == null) {
                throw new IllegalStateException("filters require a chunked dataset");
            }
        }

        private void requireInteger(String filter) {
            if ((spec.datatype[0] & 0x0F) != 0) {
                throw new IllegalStateException(filter + " requires an integer dataset");
            }
        }

        private void requireFirst(String filter) {
            if (!spec.filters.isEmpty()) {
                throw new IllegalStateException(filter + " must be the first filter in the pipeline");
            }
        }

        private void addFilter(int id, int parameter) {
            for (FilterSpec filter : spec.filters) {
                if (filter.id() == id) {
                    throw new IllegalStateException("filter " + id + " is already in the pipeline");
                }
            }
            if (spec.nbitPrecision >= 0 && id == Filters.SCALEOFFSET) {
                throw new IllegalStateException("scaleOffset cannot follow nbit");
            }
            spec.filters.add(new FilterSpec(id, parameter));
        }

        /**
         * Stores the element data inline in the object header (compact layout) rather than in a separate
         * block. For small contiguous datasets only; the data must be at most 65524 bytes (an object-header
         * message holds under 64 KiB).
         */
        public DatasetWriter compact() {
            lifecycle.check();
            if (spec.chunkShape != null || spec.data == null || spec.vlenStrings != null
                    || spec.referenceTargets != null) {
                throw new IllegalStateException("compact layout requires a plain contiguous dataset");
            }
            if (spec.data.length > MAX_COMPACT_DATA) {
                throw new IllegalStateException("compact layout data must be at most " + MAX_COMPACT_DATA
                        + " bytes, not " + spec.data.length);
            }
            spec.compact = true;
            return this;
        }

        /**
         * Sets the fill value (for unallocated or unwritten elements), converted to the dataset's type: an
         * integer dataset stores it exactly (it must be in range), a floating-point one as the nearest
         * value. Integer, enum, and floating-point datasets only.
         *
         * @throws IllegalArgumentException if the value is out of range for an integer dataset
         * @throws IllegalStateException if the dataset's type has no numeric fill value
         */
        public DatasetWriter fillValue(long value) {
            lifecycle.check();
            switch (typeClass()) {
                case 0, 8 -> spec.fillValue = integerFill(value);
                case 1 -> spec.fillValue = floatFill(value);
                default -> throw noNumericFill();
            }
            return this;
        }

        /**
         * Sets the fill value from a floating-point value, converted to the dataset's type: a
         * floating-point dataset stores it (rounded to float32 if that is the type), an integer one only a
         * whole number in range.
         *
         * @throws IllegalArgumentException if an integer dataset is given a fraction or an out-of-range value
         * @throws IllegalStateException if the dataset's type has no numeric fill value
         */
        public DatasetWriter fillValue(double value) {
            lifecycle.check();
            switch (typeClass()) {
                case 0, 8 -> {
                    if (value != Math.rint(value) || Math.abs(value) >= 0x1p63) {
                        throw new IllegalArgumentException("fill value " + value + " is not an integer in range");
                    }
                    spec.fillValue = integerFill((long) value);
                }
                case 1 -> spec.fillValue = floatFill(value);
                default -> throw noNumericFill();
            }
            return this;
        }

        private int typeClass() {
            return spec.datatype[0] & 0x0F;
        }

        private IllegalStateException noNumericFill() {
            return new IllegalStateException("a numeric fill value needs an integer, enum, or floating-point dataset; '"
                    + spec.name + "' has datatype class " + typeClass());
        }

        /** {@code value} in the dataset's integer encoding, if it is representable. */
        private byte[] integerFill(long value) {
            int size = spec.elementSize;
            boolean fits = spec.nbitPrecision >= 0 ? fitsUnsigned(value, spec.nbitPrecision)
                    : size >= 8 || (value >= -(1L << (8 * size - 1)) && value < 1L << (8 * size - 1));
            if (!fits) {
                throw new IllegalArgumentException("fill value " + value + " does not fit the "
                        + (spec.nbitPrecision >= 0 ? spec.nbitPrecision + "-bit unsigned" : 8 * size + "-bit")
                        + " integer type of '" + spec.name + "'");
            }
            byte[] fill = new byte[size];
            for (int b = 0; b < size; b++) {
                fill[b] = (byte) (value >>> (8 * b));
            }
            return fill;
        }

        private byte[] floatFill(double value) {
            return spec.elementSize == 8 ? doubleBytes(new double[] {value}) : float32Bytes(new float[] {(float) value});
        }

        public DatasetWriter intAttribute(String name, int[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            addAttribute(spec.attributes, spec.attributeNames, new AttributeSpec(name, DATATYPE_INT32, shape, intBytes(data)), legacy);
            return this;
        }

        public DatasetWriter doubleAttribute(String name, double[] data, long[] shape) {
            lifecycle.check();
            requireElementCount(shape, data.length);
            addAttribute(spec.attributes, spec.attributeNames, new AttributeSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data)), legacy);
            return this;
        }
    }

    /** The two's-complement value of a little-endian integer of {@code size} bytes. */
    private static long littleEndianSigned(byte[] data, int offset, int size) {
        long value = 0;
        for (int b = 0; b < size; b++) {
            value |= (long) (data[offset + b] & 0xff) << (8 * b);
        }
        return size >= 8 ? value : value << (64 - 8 * size) >> (64 - 8 * size);
    }

    private static boolean fitsUnsigned(long value, int precision) {
        return value >= 0 && (precision >= 63 || value < 1L << precision);
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

        /** An {@code int32} field. Field names must be non-empty, without NUL, and unique in the record. */
        public static CompoundField int32(String name, int[] values) {
            requireName(name, "compound field");
            return new CompoundField(name, DATATYPE_INT32, 4, intBytes(values), values.length);
        }

        /** A {@code float64} field. */
        public static CompoundField float64(String name, double[] values) {
            requireName(name, "compound field");
            return new CompoundField(name, DATATYPE_FLOAT64, 8, doubleBytes(values), values.length);
        }
    }

    /** An enumeration type over a 32-bit base: an ordered list of {@code name -> code} members. */
    public static final class EnumType {
        private final List<String> names = new ArrayList<>();
        private final List<Integer> values = new ArrayList<>();

        /**
         * Adds a member; returns {@code this} for chaining. Names must be non-empty and without NUL, and
         * names and values unique, as libhdf5 requires.
         */
        public EnumType add(String name, int value) {
            requireName(name, "enum member");
            if (names.contains(name) || values.contains(value)) {
                throw new IllegalArgumentException("duplicate enum member " + name + " = " + value);
            }
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
        byte[] layout;
        if (dataset.compact) {
            layout = compactLayoutBody(dataset.data); // data stored inline in the header, no data block
        } else if (dataset.chunkShape != null) {
            layout = writeChunkedStorage(buf, dataset);
        } else {
            byte[] data = dataset.data;
            HeapSlot[] slots = null;
            if (dataset.vlenStrings != null) {
                slots = new HeapSlot[dataset.vlenStrings.size()];
                for (int i = 0; i < slots.length; i++) {
                    slots[i] = addHeapObject(dataset.vlenStrings.get(i));
                }
                data = vlenIds(dataset.vlenStrings, dataset.vlenElementCounts, slots);
            }
            if (data.length == 0) {
                // An empty dataset has no storage: libhdf5 rejects a defined address with size 0.
                layout = contiguousLayoutBody(UNDEFINED, 0);
            } else {
                buf.align(8);
                int dataAddress = buf.position();
                if (dataset.referenceTargets != null) {
                    recordReferences(dataset.referenceTargets, dataAddress);
                }
                for (int i = 0; slots != null && i < slots.length; i++) {
                    heapIdPatches.add(new HeapIdPatch(dataAddress + i * 16 + 4, slots[i].collection()));
                }
                buf.bytes(data);
                layout = contiguousLayoutBody(dataAddress, data.length);
            }
        }
        // Dense attribute structures are written before the object header so it can reference them. The
        // earliest format has no dense storage: its version-1 headers hold every attribute.
        byte[] attributeInfo = !legacy && dataset.attributes.size() > MAX_COMPACT
                ? writeDenseAttributes(buf, dataset.attributes) : null;
        buf.align(8);
        long headerAddress = buf.position();

        byte[] datatype = dataset.nbitPrecision >= 0
                ? nbitDatatype(dataset.elementSize, dataset.nbitPrecision) : dataset.datatype;
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(1, 0x00, dataspaceBody(dataset.shape, legacy)));
        messages.add(new Message(3, 0x01, datatype));
        messages.add(new Message(5, 0x01, fillValueBody(dataset.fillValue, legacy)));
        messages.add(new Message(8, 0x00, layout));
        if (!dataset.filters.isEmpty()) {
            messages.add(new Message(11, 0x00, filterPipelineBody(dataset)));
        }
        if (attributeInfo != null) {
            messages.add(new Message(21, 0x00, attributeInfo));
        } else {
            for (AttributeSpec attribute : dataset.attributes) {
                messages.add(new Message(12, 0x00, attributeBody(attribute, legacy)));
            }
        }
        writeObjectHeader(buf, messages, 1);
        return headerAddress;
    }

    /** Fixed-array page size, as {@code 2^FA_PAGE_BITS} entries (libhdf5's default page bits). */
    private static final int FA_PAGE_BITS = 10;
    /** Pixels per szip block: libaec decodes block sizes 8, 16, 32 and 64. */
    private static final int SZIP_PIXELS_PER_BLOCK = 8;
    // Filter flags in the pipeline message: libhdf5 marks every filter optional except fletcher32.
    private static final int FILTER_OPTIONAL = 1;
    private static final int FILTER_MANDATORY = 0;

    /** One encoded chunk: its stored bytes, and the mask of filters that were skipped for it. */
    private record EncodedChunk(byte[] bytes, int filterMask) {
    }

    /**
     * Writes chunked storage: each chunk's (fill-padded, filtered) data, then a fixed-array index
     * listing chunk addresses in row-major order &mdash; a {@code "FADB"} data block (paged, with a
     * page-init bitmap and per-page checksums, beyond 1024 chunks) and its {@code "FAHD"} header.
     * Returns the version-4 chunked data-layout message body, which HDF5 1.10 and later read.
     */
    private static byte[] writeChunkedStorage(GrowBuffer buf, DatasetSpec dataset) {
        List<byte[]> chunks = splitChunks(dataset);
        if (chunks.isEmpty()) {
            // An empty dataset has no chunks, so no index is allocated (libhdf5's own form).
            return chunkedLayoutBody(dataset.chunkShape, dataset.elementSize, UNDEFINED);
        }
        boolean filtered = !dataset.filters.isEmpty();
        int chunkBytes = Math.toIntExact(elementCount(dataset.chunkShape) * dataset.elementSize);
        long[] chunkAddresses = new long[chunks.size()];
        int[] chunkSizes = new int[chunks.size()];
        int[] filterMasks = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            EncodedChunk encoded = encodeChunk(chunks.get(i), dataset);
            buf.align(8);
            chunkAddresses[i] = buf.position();
            chunkSizes[i] = encoded.bytes().length;
            filterMasks[i] = encoded.filterMask();
            buf.bytes(encoded.bytes());
        }

        int offsets = 8;
        int clientId = filtered ? 1 : 0;
        // A filtered entry is address + stored chunk size + filter mask; layout version 4 (HDF5 1.10-1.14)
        // sizes the chunk-size field to the unfiltered chunk size plus one byte (H5D__farray_crt_context).
        int sizeWidth = filtered ? chunkSizeWidth(chunkBytes) : 0;
        int entrySize = offsets + (filtered ? sizeWidth + 4 : 0);
        int count = chunks.size();
        int pageEntries = 1 << FA_PAGE_BITS;
        boolean paged = count > pageEntries;
        int pages = paged ? (count + pageEntries - 1) / pageEntries : 0;
        int bitmapBytes = (pages + 7) / 8;
        long dataBlockSize = paged
                ? 6 + offsets + bitmapBytes + 4 + (long) count * entrySize + 4L * pages
                : 6 + offsets + (long) count * entrySize + 4;
        buf.align(8);
        int dataBlockAddress = buf.position();
        long headerAddress = align8(dataBlockAddress + dataBlockSize);

        // Fixed-array data block: signature, version, client id, header address, then either the entries
        // or (paged) the page-init bitmap; the prefix checksum; then (paged) each page and its checksum.
        buf.bytes(new byte[] {'F', 'A', 'D', 'B'});
        buf.u8(0);
        buf.u8(clientId);
        buf.u64(headerAddress);
        if (!paged) {
            for (int i = 0; i < count; i++) {
                writeEntry(buf, chunkAddresses[i], chunkSizes[i], filterMasks[i], filtered, sizeWidth);
            }
            buf.u32(buf.checksum(dataBlockAddress, buf.position()));
        } else {
            for (int b = 0; b < bitmapBytes; b++) { // every page is written: one bit per page, MSB first
                int bits = Math.min(8, pages - 8 * b);
                buf.u8((0xFF << (8 - bits)) & 0xFF);
            }
            buf.u32(buf.checksum(dataBlockAddress, buf.position()));
            for (int page = 0; page < pages; page++) {
                int pageStart = buf.position();
                for (int i = page * pageEntries; i < Math.min(count, (page + 1) * pageEntries); i++) {
                    writeEntry(buf, chunkAddresses[i], chunkSizes[i], filterMasks[i], filtered, sizeWidth);
                }
                buf.u32(buf.checksum(pageStart, buf.position()));
            }
        }

        // Fixed-array header: signature, version, client id, entry size, page bits, max entries,
        // data block address, checksum.
        buf.align(8);
        int headerStart = buf.position();
        buf.bytes(new byte[] {'F', 'A', 'H', 'D'});
        buf.u8(0);
        buf.u8(clientId);
        buf.u8(entrySize);
        buf.u8(FA_PAGE_BITS);
        buf.u64(count);                // max entries: one per chunk of the (fixed) chunk grid
        buf.u64(dataBlockAddress);
        buf.u32(buf.checksum(headerStart, buf.position()));

        return chunkedLayoutBody(dataset.chunkShape, dataset.elementSize, headerAddress);
    }

    private static void writeEntry(GrowBuffer buf, long address, int size, int filterMask, boolean filtered,
                                   int sizeWidth) {
        buf.u64(address);
        if (filtered) {
            buf.uvar(size, sizeWidth);
            buf.u32(filterMask);
        }
    }

    /**
     * Bytes for a filtered chunk's stored size in layout version 4: enough for the unfiltered chunk
     * size plus one more byte, in case a filter grows the chunk ({@code 1 + (log2(size) + 8) / 8}).
     */
    private static int chunkSizeWidth(int chunkBytes) {
        int log2 = 31 - Integer.numberOfLeadingZeros(Math.max(1, chunkBytes));
        return Math.min(8, 1 + (log2 + 8) / 8);
    }

    /**
     * Runs a chunk through the dataset's filters in pipeline order. A filter that cannot help a chunk
     * (szip on incompressible data) is skipped for it, recorded in the chunk's filter mask, as libhdf5
     * does for optional filters.
     */
    private static EncodedChunk encodeChunk(byte[] chunk, DatasetSpec dataset) {
        byte[] block = chunk;
        int mask = 0;
        for (int i = 0; i < dataset.filters.size(); i++) {
            FilterSpec filter = dataset.filters.get(i);
            byte[] next = switch (filter.id()) {
                case Filters.SHUFFLE -> shuffle(block, dataset.elementSize);
                case Filters.DEFLATE -> deflate(block, filter.parameter());
                case Filters.FLETCHER32 -> appendFletcher32(block);
                case Filters.NBIT -> filter.parameter() == dataset.elementSize * 8
                        ? block // full precision: libhdf5 flags "no compression needed" and stores it as is
                        : nbitEncode(block, dataset.elementSize, filter.parameter());
                case Filters.SCALEOFFSET -> ScaleOffset.encodeInteger(block, dataset.elementSize, true,
                        fillBits(dataset));
                case Filters.SZIP -> Szip.encode(block, szipClientData(dataset));
                default -> throw new IllegalStateException("unknown filter " + filter.id());
            };
            if (next == null) {
                mask |= 1 << i;
            } else {
                block = next;
            }
        }
        return new EncodedChunk(block, mask);
    }

    /** The dataset's fill value bits (its custom fill value, or the default 0), little-endian. */
    private static long fillBits(DatasetSpec dataset) {
        long bits = 0;
        if (dataset.fillValue != null) {
            for (int b = 0; b < Math.min(8, dataset.fillValue.length); b++) {
                bits |= (long) (dataset.fillValue[b] & 0xff) << (8 * b);
            }
        }
        return bits;
    }

    private static int[] szipClientData(DatasetSpec dataset) {
        return Szip.clientData(Szip.EC, SZIP_PIXELS_PER_BLOCK, dataset.elementSize * 8, false, dataset.chunkShape);
    }

    /**
     * The filter-pipeline message (version 2), listing the filters in the order they are applied on
     * write, each with the flags and client data libhdf5 stores for it.
     */
    private static byte[] filterPipelineBody(DatasetSpec dataset) {
        GrowBuffer b = new GrowBuffer();
        b.u8(2); // version
        b.u8(dataset.filters.size());
        int chunkElements = Math.toIntExact(elementCount(dataset.chunkShape));
        for (FilterSpec filter : dataset.filters) {
            switch (filter.id()) {
                case Filters.DEFLATE -> writeFilter(b, Filters.DEFLATE, FILTER_OPTIONAL, filter.parameter());
                case Filters.SHUFFLE -> writeFilter(b, Filters.SHUFFLE, FILTER_OPTIONAL, dataset.elementSize);
                case Filters.FLETCHER32 -> writeFilter(b, Filters.FLETCHER32, FILTER_MANDATORY);
                // n-bit client data: total, "no compression needed", nelmts, ATOMIC, size, byte order (0=LE),
                // precision, offset.
                case Filters.NBIT -> writeFilter(b, Filters.NBIT, FILTER_OPTIONAL, 8,
                        filter.parameter() == dataset.elementSize * 8 ? 1 : 0, chunkElements, 1,
                        dataset.elementSize, 0, filter.parameter(), 0);
                case Filters.SCALEOFFSET -> writeFilter(b, Filters.SCALEOFFSET, FILTER_OPTIONAL,
                        ScaleOffset.integerClientData(chunkElements, dataset.elementSize, true, false,
                                fillBits(dataset)));
                case Filters.SZIP -> writeFilter(b, Filters.SZIP, FILTER_OPTIONAL, szipClientData(dataset));
                default -> throw new IllegalStateException("unknown filter " + filter.id());
            }
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
        int checksum = Fletcher32.checksum(data, data.length);
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

    /** The version-4 chunked data-layout message: chunk dimensions and a fixed-array chunk index. */
    private static byte[] chunkedLayoutBody(long[] chunkShape, int elementSize, long fixedArrayHeaderAddress) {
        int rank = chunkShape.length;
        long maxDim = elementSize;
        for (long c : chunkShape) {
            maxDim = Math.max(maxDim, c);
        }
        int encodedLength = (63 - Long.numberOfLeadingZeros(maxDim)) / 8 + 1;
        GrowBuffer b = new GrowBuffer();
        b.u8(4);                     // version 4 (HDF5 1.10+; version 5 is HDF5 2.0-only)
        b.u8(2);                     // layout class: chunked
        b.u8(0);                     // flags
        b.u8(rank + 1);              // dimensionality (chunk dims + element size)
        b.u8(encodedLength);
        for (long c : chunkShape) {
            b.uvar(c, encodedLength);
        }
        b.uvar(elementSize, encodedLength);
        b.u8(3);                     // index type: fixed array
        b.u8(FA_PAGE_BITS);          // page bits
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
                messages.add(new Message(12, 0x00, attributeBody(attribute, false)));
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
            checkMessageSize(message);
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

    /** A message body that would overflow its header's 16-bit size field would be silently truncated. */
    private static void checkMessageSize(Message message) {
        if (message.body().length > MAX_MESSAGE_BODY) {
            throw new HdfUnsupportedException("object-header message (type " + message.type() + ") of "
                    + message.body().length + " bytes exceeds the " + MAX_MESSAGE_BODY + "-byte limit");
        }
    }

    /** Version-1 object header: a 12-byte prefix padded to 16, then 8-byte-aligned messages. */
    private static void writeObjectHeaderV1(GrowBuffer buf, List<Message> messages, int referenceCount) {
        int chunk0 = 0;
        for (Message message : messages) {
            checkMessageSize(message);
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
    private static final int GLOBAL_HEAP_MAX_OBJECTS = 0xFFFF; // object indices are 16-bit; 0 is free space
    private static final int GLOBAL_HEAP_TARGET_SIZE = 1 << 20; // start a new collection beyond this

    /** Where a variable-length element's data lives: a collection (by number) and its 1-based index. */
    private record HeapSlot(int collection, int index) {
    }

    /** A vlen id whose collection address (8 bytes at {@code offset}) is patched once collections are placed. */
    private record HeapIdPatch(int offset, int collection) {
    }

    /**
     * Places one variable-length element in the current global-heap collection, starting a new one when
     * it holds 65,535 objects (indices are 16-bit) or would grow past {@link #GLOBAL_HEAP_TARGET_SIZE}.
     * Collections are shared by every dataset in the file, as libhdf5 shares them.
     */
    private HeapSlot addHeapObject(byte[] object) {
        int footprint = 16 + align8(object.length);
        List<byte[]> current = heapCollections.isEmpty() ? null : heapCollections.getLast();
        if (current == null || current.size() == GLOBAL_HEAP_MAX_OBJECTS
                || (!current.isEmpty() && (long) lastCollectionBytes + footprint > GLOBAL_HEAP_TARGET_SIZE)) {
            current = new ArrayList<>();
            heapCollections.add(current);
            lastCollectionBytes = 0;
        }
        current.add(object);
        lastCollectionBytes += footprint;
        return new HeapSlot(heapCollections.size() - 1, current.size());
    }

    /** Writes every global-heap collection and patches the vlen ids that point into them. */
    private void writeGlobalHeaps(GrowBuffer buf) {
        long[] addresses = new long[heapCollections.size()];
        for (int c = 0; c < addresses.length; c++) {
            buf.align(8);
            addresses[c] = buf.position();
            writeGlobalHeap(buf, heapCollections.get(c));
        }
        for (HeapIdPatch patch : heapIdPatches) {
            buf.patchU64(patch.offset(), addresses[patch.collection()]);
        }
    }

    /** Writes a global-heap collection holding {@code objects} at indices 1, 2, ... */
    private static void writeGlobalHeap(GrowBuffer buf, List<byte[]> objects) {
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
        for (int i = 0; i < objects.size(); i++) {
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
        int maxManaged = HEAP_MAX_MANAGED_OBJECT;
        for (byte[] object : objects) {
            objectBytes += object.length;
            maxManaged = Math.max(maxManaged, object.length); // a larger object stays managed, not "huge"
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
        buf.u32(maxManaged);
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

    /**
     * Writes a version-2 B-tree with a single leaf holding {@code records} (already sorted); returns its
     * header. The node size is a header field, so the leaf is sized to hold every record (at least the
     * library's default 512 bytes): a fixed 512-byte leaf holds only 45 link or 29 attribute records.
     */
    private static long writeV2BTree(GrowBuffer buf, int type, int recordSize, List<byte[]> records) {
        if (records.size() > 0xFFFF) {
            throw new HdfUnsupportedException("too many dense-storage records for a single B-tree node: " + records.size());
        }
        int needed = 4 + 1 + 1 + records.size() * recordSize + 4; // prefix, records, checksum
        int nodeSize = Math.max(BT2_NODE_SIZE, Integer.highestOneBit(needed - 1) << 1);
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
        // ...then the node is padded out to the node size it is allocated at on disk.
        while (buf.position() - leaf < nodeSize) {
            buf.u8(0);
        }

        buf.align(8);
        int header = buf.position();
        buf.bytes(BTHD_SIGNATURE);
        buf.u8(0);
        buf.u8(type);
        buf.u32(nodeSize);
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
            objects.add(attributeBody(attribute, false));
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

    private static byte[] vlenIds(List<byte[]> payloads, int[] elementCounts, HeapSlot[] slots) {
        GrowBuffer b = new GrowBuffer();
        for (int i = 0; i < payloads.size(); i++) {
            // A vlen ID's length field is the string's byte length or the sequence's element count.
            b.u32(elementCounts != null ? elementCounts[i] : payloads.get(i).length);
            b.u64(0);                     // collection address, patched when the collections are written
            b.u32(slots[i].index());
        }
        return b.toByteArray();
    }

    /**
     * The Attribute message: version 3 (with a character-set field, UTF-8 for a non-ASCII name), or for
     * the earliest format version 1, whose name, datatype, and dataspace are each padded to 8 bytes.
     */
    private static byte[] attributeBody(AttributeSpec attribute, boolean legacy) {
        byte[] name = (attribute.name + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] dataspace = dataspaceBody(attribute.shape, legacy);
        GrowBuffer b = new GrowBuffer();
        b.u8(legacy ? 1 : 3);
        b.u8(0x00);
        b.u16(name.length);
        b.u16(attribute.datatype.length);
        b.u16(dataspace.length);
        if (legacy) {
            padded(b, name);
            padded(b, attribute.datatype);
            padded(b, dataspace);
        } else {
            b.u8(isAscii(name) ? 0 : 1);
            b.bytes(name);
            b.bytes(attribute.datatype);
            b.bytes(dataspace);
        }
        b.bytes(attribute.data);
        return b.toByteArray();
    }

    /** Appends {@code bytes} zero-padded to a multiple of 8. */
    private static void padded(GrowBuffer b, byte[] bytes) {
        b.bytes(bytes);
        for (int i = bytes.length; i < align8(bytes.length); i++) {
            b.u8(0);
        }
    }

    private static boolean isAscii(byte[] bytes) {
        for (byte x : bytes) {
            if (x < 0) {
                return false;
            }
        }
        return true;
    }

    /** The Dataspace message: version 2, or version 1 (with its reserved bytes) for the earliest format. */
    private static byte[] dataspaceBody(long[] shape, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        if (legacy) {
            b.u8(1);
            b.u8(shape.length);
            b.u8(0x00);   // flags: no maximum dimensions (they equal the current ones)
            b.u8(0);      // reserved
            b.u32(0);     // reserved
        } else {
            b.u8(2);
            b.u8(shape.length);
            b.u8(0x00);
            b.u8(shape.length == 0 ? 0 : 1);
        }
        for (long dimension : shape) {
            b.u64(dimension);
        }
        return b.toByteArray();
    }

    /**
     * The Fill Value message body: the default (reads back as zero) or a defined custom value. Version 3,
     * or for the earliest format version 2, written as libhdf5 writes it (late allocation, fill written
     * if set; the default fill is "defined" with no value).
     */
    private static byte[] fillValueBody(byte[] fill, boolean legacy) {
        if (legacy) {
            GrowBuffer b = new GrowBuffer();
            b.u8(2);        // version
            b.u8(2);        // space allocation time: late
            b.u8(2);        // fill value write time: if set
            b.u8(1);        // fill value defined
            b.u32(fill == null ? 0 : fill.length);
            if (fill != null) {
                b.bytes(fill);
            }
            return b.toByteArray();
        }
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

    /** Compact data-layout message: the element data stored inline (version 3, class 0). */
    private static byte[] compactLayoutBody(byte[] data) {
        GrowBuffer b = new GrowBuffer();
        b.u8(3);       // version
        b.u8(0);       // layout class: compact
        b.u16(data.length);
        b.bytes(data);
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

    /**
     * A hard-link message. The name's length field is 1, 2, or 4 bytes as its length needs (flag bits
     * 0-1), and a non-ASCII name carries the UTF-8 character set (flag bit 4).
     */
    private static byte[] linkBody(String name, long targetHeaderAddress) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int widthCode = nameBytes.length <= 0xFF ? 0 : nameBytes.length <= 0xFFFF ? 1 : 2;
        boolean utf8 = !isAscii(nameBytes);
        GrowBuffer b = new GrowBuffer();
        b.u8(1);
        b.u8(widthCode | (utf8 ? 0x10 : 0));
        if (utf8) {
            b.u8(1);  // character set: UTF-8
        }
        b.uvar(nameBytes.length, 1 << widthCode);
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
        int perNode = 2 * GROUP_LEAF_K;      // max symbols per symbol-table node
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

        // Symbol-table nodes: name-sorted entries distributed across nodes of <= perNode symbols each,
        // each padded to its fixed allocated size.
        List<SymbolChild> sorted = new ArrayList<>(children);
        // libhdf5 orders and looks up names with strcmp: by UTF-8 bytes, not by Java's UTF-16 order.
        sorted.sort((a, b) -> java.util.Arrays.compareUnsigned(
                a.name().getBytes(StandardCharsets.UTF_8), b.name().getBytes(StandardCharsets.UTF_8)));
        int nodeCount = Math.max(1, (sorted.size() + perNode - 1) / perNode);
        int snodSize = 8 + perNode * SYMBOL_ENTRY_SIZE;
        int[] snodAddresses = new int[nodeCount];
        int[] snodMaxNameOffset = new int[nodeCount]; // heap offset of each node's greatest-by-name entry
        for (int n = 0; n < nodeCount; n++) {
            int from = n * perNode;
            int to = Math.min(from + perNode, sorted.size());
            buf.align(8);
            snodAddresses[n] = buf.position();
            buf.bytes(SNOD_SIGNATURE);
            buf.u8(1);            // version
            buf.u8(0);            // reserved
            buf.u16(to - from);
            for (int e = from; e < to; e++) {
                SymbolChild child = sorted.get(e);
                buf.u64(nameOffsets.get(child.name()));  // link name offset
                buf.u64(child.headerAddress());          // object header address
                buf.u32(child.cacheType());
                buf.u32(0);                              // reserved
                buf.u64(child.cacheType() == 1 ? child.btree() : 0); // scratch pad: B-tree + heap for a group
                buf.u64(child.cacheType() == 1 ? child.heap() : 0);
            }
            while (buf.position() - snodAddresses[n] < snodSize) {
                buf.u8(0);
            }
            snodMaxNameOffset[n] = to == 0 ? 0 : nameOffsets.get(sorted.get(to - 1).name());
        }

        // Group version-1 B-tree: a single leaf node with one entry per symbol-table node. Keys are the
        // heap offset of the greatest name to the left of each pointer (key 0 = the empty-name offset).
        buf.align(8);
        int btreeAddress = buf.position();
        buf.bytes(TREE_SIGNATURE);
        buf.u8(0);            // node type: group
        buf.u8(0);            // node level: leaf
        buf.u16(nodeCount);   // entries used
        buf.u64(UNDEFINED);   // left sibling
        buf.u64(UNDEFINED);   // right sibling
        buf.u64(0);           // key 0
        for (int n = 0; n < nodeCount; n++) {
            buf.u64(snodAddresses[n]);      // child n
            buf.u64(snodMaxNameOffset[n]);  // key n+1
        }
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
            messages.add(new Message(12, 0x00, attributeBody(attribute, true)));
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

    /**
     * Builds a compound (class 6) datatype message: members packed at the given offsets, names UTF-8.
     * Version 3 (HDF5 1.8+), or for the earliest format version 1 (padded names and the legacy member
     * dimension block).
     */
    private static byte[] compoundDatatype(CompoundField[] fields, int[] offsets, int recordSize, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        b.u8(legacy ? 0x16 : 0x36);
        b.u8(fields.length & 0xFF);
        b.u8((fields.length >>> 8) & 0xFF);
        b.u8(0);
        b.u32(recordSize);
        int offsetWidth = byteWidthFor(recordSize);
        for (int i = 0; i < fields.length; i++) {
            byte[] name = (fields[i].name + "\0").getBytes(StandardCharsets.UTF_8); // null-terminated name
            if (legacy) {
                padded(b, name);
                b.u32(offsets[i]);
                b.u8(0);      // dimensionality (a scalar member)
                b.u8(0);
                b.u8(0);
                b.u8(0);      // reserved (3)
                b.u32(0);     // dimension permutation
                b.u32(0);     // reserved
                for (int d = 0; d < 4; d++) {
                    b.u32(0); // dimension sizes
                }
            } else {
                b.bytes(name);
                b.uvar(offsets[i], offsetWidth);
            }
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

    /**
     * Builds an array (class 10) datatype message with the given element shape and base: version 3
     * (HDF5 1.8+), or for the earliest format version 2, the first that has the array class.
     */
    private static byte[] arrayDatatype(int[] arrayDims, int baseSize, byte[] base, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        b.u8(legacy ? 0x2A : 0x3A);
        b.u8(0);
        b.u8(0);
        b.u8(0);
        b.u32(product(arrayDims) * baseSize);
        b.u8(arrayDims.length); // rank
        if (legacy) {
            b.u8(0);
            b.u8(0);
            b.u8(0);            // reserved (3)
        }
        for (int dimension : arrayDims) {
            b.u32(dimension);
        }
        if (legacy) {
            for (int d = 0; d < arrayDims.length; d++) {
                b.u32(d);       // permutation index (the identity)
            }
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

    /**
     * Builds an enumerated (class 8) datatype message over a 32-bit base type, member names UTF-8:
     * version 3 (HDF5 1.8+), or for the earliest format version 1 (names padded to 8 bytes).
     */
    private static byte[] enumDatatype(EnumType type, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        int members = type.names.size();
        b.u8(legacy ? 0x18 : 0x38);
        b.u8(members & 0xFF);
        b.u8((members >>> 8) & 0xFF);
        b.u8(0);
        b.u32(4); // size = base type size
        b.bytes(DATATYPE_INT32);
        for (String name : type.names) {
            byte[] bytes = (name + "\0").getBytes(StandardCharsets.UTF_8);
            if (legacy) {
                padded(b, bytes);
            } else {
                b.bytes(bytes);
            }
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

    /** A fixed-length string (class 3) datatype message: null-padded, ASCII or UTF-8, of the given byte size. */
    private static byte[] fixedStringDatatype(int size, boolean utf8) {
        GrowBuffer b = new GrowBuffer();
        b.u8(0x13); // version 1, class 3 (string)
        b.u8(utf8 ? 0x11 : 0x01); // bit field: null-pad; character set ASCII or UTF-8 (bits 4-7)
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
        final Set<String> linkNames = new HashSet<>();      // groups and datasets share one namespace
        final Set<String> attributeNames = new HashSet<>();
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
        boolean compact;                 // store the element data inline in the object header
        final List<AttributeSpec> attributes = new ArrayList<>();
        final Set<String> attributeNames = new HashSet<>();
        final List<FilterSpec> filters = new ArrayList<>(); // the chunk filter pipeline, in write order
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

    /** One filter of a dataset's pipeline: its id and its parameter (deflate level, n-bit precision). */
    private record FilterSpec(int id, int parameter) {
    }
}
