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
 * Writes a minimal, valid HDF5 file in the modern format: a version-3 superblock, version-2
 * (checksummed) object headers, and a root group whose children are recorded as compact link messages.
 * Datasets use contiguous storage.
 *
 * <pre>{@code
 * try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
 *     w.intDataset("counts", new int[] {1, 2, 3}, new long[] {3});
 * }
 * }</pre>
 *
 * <p>This is the foundation of Falcon's write path (stage H7): root group + contiguous atomic datasets.
 * Chunked storage, filters, groups, and the broader type set arrive in later write stages.
 */
public final class Hdf5Writer implements AutoCloseable {

    private static final byte[] HDF5_SIGNATURE = {(byte) 0x89, 'H', 'D', 'F', '\r', '\n', 0x1a, '\n'};
    private static final byte[] OHDR_SIGNATURE = {'O', 'H', 'D', 'R'};
    private static final long UNDEFINED = -1L; // all-ones address
    private static final int SUPERBLOCK_SIZE = 48;
    // Fixed-point, version 1, class 0: 4-byte signed little-endian integer (bit precision 32).
    private static final byte[] DATATYPE_INT32 = {0x10, 0x08, 0, 0, 4, 0, 0, 0, 0, 0, 0x20, 0};
    // Floating-point, version 1, class 1: 8-byte little-endian IEEE double (exp bias 1023).
    private static final byte[] DATATYPE_FLOAT64 = {
        0x11, 0x20, 0x3f, 0, 8, 0, 0, 0, 0, 0, 0x40, 0, 0x34, 0x0b, 0, 0x34, (byte) 0xff, 0x03, 0, 0
    };

    private final Path path;
    private final List<DatasetSpec> datasets = new ArrayList<>();
    private boolean written;

    private Hdf5Writer(Path path) {
        this.path = path;
    }

    /** Begins writing a new HDF5 file at {@code path} (overwritten on {@link #close()}). */
    public static Hdf5Writer create(Path path) {
        return new Hdf5Writer(path);
    }

    /** Adds a contiguous {@code int32} dataset of the given row-major shape. */
    public Hdf5Writer intDataset(String name, int[] data, long[] shape) {
        requireElementCount(shape, data.length);
        datasets.add(new DatasetSpec(name, DATATYPE_INT32, shape, intBytes(data), new ArrayList<>()));
        return this;
    }

    /**
     * Attaches an {@code int32} attribute (of the given row-major shape) to the most recently added
     * dataset. Use an empty {@code shape} for a scalar attribute.
     */
    public Hdf5Writer intAttribute(String name, int[] data, long[] shape) {
        if (datasets.isEmpty()) {
            throw new IllegalStateException("add a dataset before attaching an attribute");
        }
        requireElementCount(shape, data.length);
        datasets.get(datasets.size() - 1).attributes()
                .add(new AttributeSpec(name, DATATYPE_INT32, shape, intBytes(data)));
        return this;
    }

    /** Adds a contiguous {@code float64} dataset of the given row-major shape. */
    public Hdf5Writer doubleDataset(String name, double[] data, long[] shape) {
        requireElementCount(shape, data.length);
        datasets.add(new DatasetSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data), new ArrayList<>()));
        return this;
    }

    /** Attaches a {@code float64} attribute to the most recently added dataset. */
    public Hdf5Writer doubleAttribute(String name, double[] data, long[] shape) {
        if (datasets.isEmpty()) {
            throw new IllegalStateException("add a dataset before attaching an attribute");
        }
        requireElementCount(shape, data.length);
        datasets.get(datasets.size() - 1).attributes()
                .add(new AttributeSpec(name, DATATYPE_FLOAT64, shape, doubleBytes(data)));
        return this;
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

    /** Serializes and writes the file. */
    @Override
    public void close() throws IOException {
        if (written) {
            return;
        }
        written = true;

        GrowBuffer buf = new GrowBuffer();
        buf.reserve(SUPERBLOCK_SIZE); // superblock is filled in last, once addresses are known

        Map<String, Long> children = new LinkedHashMap<>();
        for (DatasetSpec dataset : datasets) {
            buf.align(8);
            long dataAddress = buf.position();
            buf.bytes(dataset.data);
            buf.align(8);
            long headerAddress = buf.position();
            writeDatasetHeader(buf, dataset, dataAddress);
            children.put(dataset.name, headerAddress);
        }

        buf.align(8);
        long rootAddress = buf.position();
        writeGroupHeader(buf, children);
        long endOfFile = buf.position();

        buf.patchBytes(0, superblock(rootAddress, endOfFile));
        Files.write(path, buf.toByteArray());
    }

    // ------------------------------------------------------------- serialization

    private static void writeDatasetHeader(GrowBuffer buf, DatasetSpec dataset, long dataAddress) {
        GrowBuffer messages = new GrowBuffer();
        writeMessage(messages, 1, 0x00, dataspaceBody(dataset.shape));
        writeMessage(messages, 3, 0x01, dataset.datatype);
        writeMessage(messages, 5, 0x01, new byte[] {0x03, 0x0a}); // fill value v3, value not stored (default 0)
        writeMessage(messages, 8, 0x00, contiguousLayoutBody(dataAddress, dataset.data.length));
        for (AttributeSpec attribute : dataset.attributes) {
            writeMessage(messages, 12, 0x00, attributeBody(attribute));
        }
        writeObjectHeader(buf, messages.toByteArray());
    }

    private static byte[] attributeBody(AttributeSpec attribute) {
        byte[] name = (attribute.name + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] dataspace = dataspaceBody(attribute.shape);
        GrowBuffer b = new GrowBuffer();
        b.u8(3);                       // version 3 (compact, character-set aware)
        b.u8(0x00);                    // flags: datatype and dataspace stored inline
        b.u16(name.length);
        b.u16(attribute.datatype.length);
        b.u16(dataspace.length);
        b.u8(0);                       // name character set: ASCII
        b.bytes(name);
        b.bytes(attribute.datatype);
        b.bytes(dataspace);
        b.bytes(attribute.data);
        return b.toByteArray();
    }

    private static void writeGroupHeader(GrowBuffer buf, Map<String, Long> children) {
        GrowBuffer messages = new GrowBuffer();
        writeMessage(messages, 2, 0x00, linkInfoBody());
        writeMessage(messages, 10, 0x01, new byte[] {0, 0}); // group info: version 0, no flags
        for (Map.Entry<String, Long> child : children.entrySet()) {
            writeMessage(messages, 6, 0x00, linkBody(child.getKey(), child.getValue()));
        }
        writeObjectHeader(buf, messages.toByteArray());
    }

    /** Writes a version-2 object header wrapping {@code messages}, with a trailing lookup3 checksum. */
    private static void writeObjectHeader(GrowBuffer buf, byte[] messages) {
        int sizeBits = messages.length <= 0xFF ? 0 : messages.length <= 0xFFFF ? 1 : 2;
        int start = buf.position();
        buf.bytes(OHDR_SIGNATURE);
        buf.u8(2);        // version
        buf.u8(sizeBits); // flags: size-of-chunk-0 field width; no times, no creation order
        buf.uvar(messages.length, 1 << sizeBits);
        buf.bytes(messages);
        buf.u32(buf.checksum(start, buf.position()));
    }

    private static void writeMessage(GrowBuffer buf, int type, int flags, byte[] body) {
        buf.u8(type);
        buf.u16(body.length);
        buf.u8(flags);
        buf.bytes(body);
    }

    private static byte[] dataspaceBody(long[] shape) {
        GrowBuffer b = new GrowBuffer();
        b.u8(2);                              // version
        b.u8(shape.length);                   // dimensionality
        b.u8(0x00);                           // flags: no maximum dimensions
        b.u8(shape.length == 0 ? 0 : 1);      // type: 0 = scalar, 1 = simple
        for (long dimension : shape) {
            b.u64(dimension);
        }
        return b.toByteArray();
    }

    private static byte[] contiguousLayoutBody(long address, long size) {
        GrowBuffer b = new GrowBuffer();
        b.u8(3); // version
        b.u8(1); // layout class: contiguous
        b.u64(address);
        b.u64(size);
        return b.toByteArray();
    }

    private static byte[] linkInfoBody() {
        GrowBuffer b = new GrowBuffer();
        b.u8(0);             // version
        b.u8(0);             // flags
        b.u64(UNDEFINED);    // fractal heap address (none: links are compact)
        b.u64(UNDEFINED);    // name-index v2 B-tree address (none)
        return b.toByteArray();
    }

    private static byte[] linkBody(String name, long targetHeaderAddress) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xFF) {
            throw new IllegalArgumentException("link name too long: " + name);
        }
        GrowBuffer b = new GrowBuffer();
        b.u8(1);                 // version
        b.u8(0x00);              // flags: 1-byte name length, hard link
        b.u8(nameBytes.length);  // length of link name
        b.bytes(nameBytes);
        b.u64(targetHeaderAddress);
        return b.toByteArray();
    }

    private static byte[] superblock(long rootAddress, long endOfFile) {
        GrowBuffer sb = new GrowBuffer();
        sb.bytes(HDF5_SIGNATURE);
        sb.u8(3);              // superblock version
        sb.u8(8);              // size of offsets
        sb.u8(8);              // size of lengths
        sb.u8(0);              // file consistency flags
        sb.u64(0);             // base address
        sb.u64(UNDEFINED);     // superblock extension address (none)
        sb.u64(endOfFile);     // end of file address
        sb.u64(rootAddress);   // root group object header address
        sb.u32(Lookup3.hashLittle(sb.toByteArray())); // checksum over the preceding 44 bytes
        return sb.toByteArray();
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

    private record DatasetSpec(String name, byte[] datatype, long[] shape, byte[] data,
                               List<AttributeSpec> attributes) {
    }

    private record AttributeSpec(String name, byte[] datatype, long[] shape, byte[] data) {
    }
}
