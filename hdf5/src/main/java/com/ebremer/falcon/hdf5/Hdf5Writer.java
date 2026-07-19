package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.checksum.Lookup3;
import com.ebremer.falcon.hdf5.write.GrowBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            return addDataset(new DatasetSpec(name, DATATYPE_INT32, shape, intBytes(data), null));
        }

        public DatasetWriter doubleDataset(String name, double[] data, long[] shape) {
            requireElementCount(shape, data.length);
            return addDataset(new DatasetSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data), null));
        }

        /** A variable-length UTF-8 string dataset (values stored in a global heap). */
        public DatasetWriter stringDataset(String name, String[] data, long[] shape) {
            requireElementCount(shape, data.length);
            List<byte[]> bytes = new ArrayList<>();
            for (String s : data) {
                bytes.add(s.getBytes(StandardCharsets.UTF_8));
            }
            return addDataset(new DatasetSpec(name, DATATYPE_VLEN_STRING, shape, null, bytes));
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

    /** Attaches attributes to a dataset. */
    public static final class DatasetWriter {
        private final DatasetSpec spec;

        private DatasetWriter(DatasetSpec spec) {
            this.spec = spec;
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
        buf.align(8);
        long headerAddress = buf.position();

        GrowBuffer messages = new GrowBuffer();
        writeMessage(messages, 1, 0x00, dataspaceBody(dataset.shape));
        writeMessage(messages, 3, 0x01, dataset.datatype);
        writeMessage(messages, 5, 0x01, new byte[] {0x03, 0x0a}); // fill value: default 0
        writeMessage(messages, 8, 0x00, contiguousLayoutBody(dataAddress, data.length));
        for (AttributeSpec attribute : dataset.attributes) {
            writeMessage(messages, 12, 0x00, attributeBody(attribute));
        }
        writeObjectHeader(buf, messages.toByteArray());
        return headerAddress;
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
        final long[] shape;
        final byte[] data;              // inline element bytes, or null for vlen strings
        final List<byte[]> vlenStrings; // vlen-string values, or null
        final List<AttributeSpec> attributes = new ArrayList<>();

        DatasetSpec(String name, byte[] datatype, long[] shape, byte[] data, List<byte[]> vlenStrings) {
            this.name = name;
            this.datatype = datatype;
            this.shape = shape;
            this.data = data;
            this.vlenStrings = vlenStrings;
        }
    }

    private record AttributeSpec(String name, byte[] datatype, long[] shape, byte[] data) {
    }
}
