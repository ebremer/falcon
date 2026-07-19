package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.checksum.Lookup3;
import com.ebremer.falcon.hdf5.write.GrowBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final long UNDEFINED = -1L;
    private static final int SUPERBLOCK_SIZE = 48;

    private static final byte[] DATATYPE_INT32 = {0x10, 0x08, 0, 0, 4, 0, 0, 0, 0, 0, 0x20, 0};
    private static final byte[] DATATYPE_FLOAT64 = {
        0x11, 0x20, 0x3f, 0, 8, 0, 0, 0, 0, 0, 0x40, 0, 0x34, 0x0b, 0, 0x34, (byte) 0xff, 0x03, 0, 0
    };
    // Variable-length UTF-8 string (class 9): 16-byte global-heap id, string base. Verbatim from h5py.
    private static final byte[] DATATYPE_VLEN_STRING = {
        0x19, 0x01, 0x01, 0, 0x10, 0, 0, 0, 0x10, 0, 0, 0, 0x01, 0, 0, 0, 0, 0, 0x08, 0
    };

    private final Path path;
    private final GroupSpec root = new GroupSpec();
    private final GroupWriter rootWriter = new GroupWriter(root);
    private boolean written;

    private Hdf5Writer(Path path) {
        this.path = path;
    }

    /** Begins writing a new HDF5 file at {@code path} (written on {@link #close()}). */
    public static Hdf5Writer create(Path path) {
        return new Hdf5Writer(path);
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

    public DatasetWriter intChunkedDataset(String name, int[] data, long[] shape, long[] chunkShape) {
        return rootWriter.intChunkedDataset(name, data, shape, chunkShape);
    }

    public DatasetWriter doubleChunkedDataset(String name, double[] data, long[] shape, long[] chunkShape) {
        return rootWriter.doubleChunkedDataset(name, data, shape, chunkShape);
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
        buf.reserve(SUPERBLOCK_SIZE);
        long rootAddress = writeGroup(buf, root);
        long endOfFile = buf.position();
        buf.patchBytes(0, superblock(rootAddress, endOfFile));
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
            if (spec.chunkShape == null) {
                throw new IllegalStateException("deflate requires a chunked dataset");
            }
            spec.deflateLevel = level;
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

    // --------------------------------------------------------------- serialization

    private static long writeGroup(GrowBuffer buf, GroupSpec group) {
        Map<String, Long> children = new LinkedHashMap<>();
        for (GroupSpec subgroup : group.groups) {
            children.put(subgroup.name, writeGroup(buf, subgroup));
        }
        for (DatasetSpec dataset : group.datasets) {
            children.put(dataset.name, writeDataset(buf, dataset));
        }
        buf.align(8);
        long headerAddress = buf.position();
        writeGroupHeader(buf, children, group.attributes);
        return headerAddress;
    }

    private static long writeDataset(GrowBuffer buf, DatasetSpec dataset) {
        byte[] layout;
        if (dataset.chunkShape != null) {
            layout = writeChunkedStorage(buf, dataset);
        } else {
            byte[] data = dataset.data;
            if (dataset.vlenStrings != null) {
                buf.align(8);
                long collection = buf.position();
                int[] indices = writeGlobalHeap(buf, dataset.vlenStrings);
                data = vlenIds(dataset.vlenStrings, collection, indices);
            }
            buf.align(8);
            long dataAddress = buf.position();
            buf.bytes(data);
            layout = contiguousLayoutBody(dataAddress, data.length);
        }
        buf.align(8);
        long headerAddress = buf.position();

        GrowBuffer messages = new GrowBuffer();
        writeMessage(messages, 1, 0x00, dataspaceBody(dataset.shape));
        writeMessage(messages, 3, 0x01, dataset.datatype);
        writeMessage(messages, 5, 0x01, new byte[] {0x03, 0x0a}); // fill value: default 0
        writeMessage(messages, 8, 0x00, layout);
        if (dataset.deflateLevel >= 0) {
            writeMessage(messages, 11, 0x00, deflatePipelineBody(dataset.deflateLevel));
        }
        for (AttributeSpec attribute : dataset.attributes) {
            writeMessage(messages, 12, 0x00, attributeBody(attribute));
        }
        writeObjectHeader(buf, messages.toByteArray());
        return headerAddress;
    }

    /**
     * Writes chunked storage: each chunk's (fill-padded) data block, then a fixed-array index (a
     * {@code "FADB"} data block listing chunk addresses in row-major order, and its {@code "FAHD"}
     * header). Returns the version-4 chunked data-layout message body.
     */
    private static byte[] writeChunkedStorage(GrowBuffer buf, DatasetSpec dataset) {
        List<byte[]> chunks = splitChunks(dataset);
        boolean filtered = dataset.deflateLevel >= 0;
        long[] chunkAddresses = new long[chunks.size()];
        int[] chunkSizes = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            byte[] block = filtered ? deflate(chunks.get(i), dataset.deflateLevel) : chunks.get(i);
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

    private static byte[] deflatePipelineBody(int level) {
        GrowBuffer b = new GrowBuffer();
        b.u8(2);       // filter pipeline message version
        b.u8(1);       // number of filters
        b.u16(1);      // filter identifier: deflate
        b.u16(1);      // flags
        b.u16(1);      // number of client-data values
        b.u32(level);  // client data: compression level
        return b.toByteArray();
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

    private static void writeGroupHeader(GrowBuffer buf, Map<String, Long> children,
                                         List<AttributeSpec> attributes) {
        GrowBuffer messages = new GrowBuffer();
        writeMessage(messages, 2, 0x00, linkInfoBody());
        writeMessage(messages, 10, 0x01, new byte[] {0, 0});
        for (Map.Entry<String, Long> child : children.entrySet()) {
            writeMessage(messages, 6, 0x00, linkBody(child.getKey(), child.getValue()));
        }
        for (AttributeSpec attribute : attributes) {
            writeMessage(messages, 12, 0x00, attributeBody(attribute));
        }
        writeObjectHeader(buf, messages.toByteArray());
    }

    private static void writeObjectHeader(GrowBuffer buf, byte[] messages) {
        int sizeBits = messages.length <= 0xFF ? 0 : messages.length <= 0xFFFF ? 1 : 2;
        int start = buf.position();
        buf.bytes(OHDR_SIGNATURE);
        buf.u8(2);
        buf.u8(sizeBits);
        buf.uvar(messages.length, 1 << sizeBits);
        buf.bytes(messages);
        buf.u32(buf.checksum(start, buf.position()));
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

    private static byte[] vlenIds(List<byte[]> strings, long collection, int[] indices) {
        GrowBuffer b = new GrowBuffer();
        for (int i = 0; i < strings.size(); i++) {
            b.u32(strings.get(i).length); // byte length of the string
            b.u64(collection);
            b.u32(indices[i]);
        }
        return b.toByteArray();
    }

    private static void writeMessage(GrowBuffer buf, int type, int flags, byte[] body) {
        buf.u8(type);
        buf.u16(body.length);
        buf.u8(flags);
        buf.bytes(body);
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
        long count = 1;
        for (long d : shape) {
            count *= d;
        }
        if (count != length) {
            throw new IllegalArgumentException("shape implies " + count + " elements but data has " + length);
        }
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
        final byte[] data;              // inline element bytes, or null for vlen strings
        final List<byte[]> vlenStrings; // vlen-string values, or null
        final List<AttributeSpec> attributes = new ArrayList<>();
        int deflateLevel = -1;          // -1 = no compression

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
