package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.btree.BTreeV2;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.heap.FractalHeap;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.message.AttributeInfoMessage;
import com.ebremer.falcon.hdf5.message.AttributeMessage;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * An object in the HDF5 hierarchy &mdash; a {@link Group} or a {@link Dataset} &mdash; identified by
 * its name, its absolute path, and the file address of its object header.
 */
public abstract sealed class Hdf5Object permits Group, Dataset, CommittedDatatype {

    final FileContext ctx;
    private final String name;
    private final String path;
    private final long objectHeaderAddress;
    private ObjectHeader header; // parsed lazily, then cached

    Hdf5Object(FileContext ctx, String name, String path, long objectHeaderAddress) {
        this.ctx = ctx;
        this.name = name;
        this.path = path;
        this.objectHeaderAddress = objectHeaderAddress;
    }

    /** This object's header, parsed on first use. */
    ObjectHeader header() {
        if (header == null) {
            header = ObjectHeader.parse(ctx, objectHeaderAddress);
        }
        return header;
    }

    /** The object's local link name ({@code ""} for the root group). */
    public String name() {
        return name;
    }

    /** The object's absolute path from the root (e.g. {@code /alpha/beta}). */
    public String path() {
        return path;
    }

    /** The file address of this object's header. */
    public long objectHeaderAddress() {
        return objectHeaderAddress;
    }

    /** This object's attributes (compact header messages and/or dense fractal-heap storage). */
    public List<Attribute> attributes() {
        ObjectHeader header = header();
        List<Attribute> out = new ArrayList<>();
        for (HeaderMessage message : header.messages()) {
            if (message.type() == MessageType.ATTRIBUTE) {
                out.add(AttributeMessage.parse(ctx, message));
            }
        }
        HeaderMessage attributeInfo = header.find(MessageType.ATTRIBUTE_INFO);
        if (attributeInfo != null) {
            long fractalHeap = AttributeInfoMessage.fractalHeapAddress(ctx, attributeInfo);
            if (fractalHeap != HdfBuffer.UNDEFINED_ADDRESS) {
                FractalHeap heap = FractalHeap.parse(ctx, fractalHeap);
                for (byte[] record : BTreeV2.readRecords(ctx, AttributeInfoMessage.nameBTreeAddress(ctx, attributeInfo))) {
                    // attribute-name-index record (type 8): the heap ID comes first.
                    FractalHeap.HeapObject object = heap.locate(Arrays.copyOfRange(record, 0, heap.idLength()));
                    out.add(AttributeMessage.parse(ctx, object.address(), object.length()));
                }
            }
        }
        return out;
    }

    /** The attribute with the given name, if present. */
    public Optional<Attribute> attribute(String name) {
        for (Attribute attribute : attributes()) {
            if (attribute.name().equals(name)) {
                return Optional.of(attribute);
            }
        }
        return Optional.empty();
    }

    public abstract boolean isGroup();

    /** The number of hard links to this object (at least 1). */
    public int referenceCount() {
        return header().referenceCount();
    }

    /** This object's modification time, if the file tracks object times. */
    public Optional<Instant> modificationTime() {
        var seconds = header().modificationTimeSeconds();
        return seconds.isPresent() ? Optional.of(Instant.ofEpochSecond(seconds.getAsLong())) : Optional.empty();
    }

    /** This object's comment (object-comment message), if it has one. */
    public Optional<String> comment() {
        HeaderMessage message = header().find(MessageType.OBJECT_COMMENT);
        if (message == null) {
            return Optional.empty();
        }
        byte[] bytes = message.body().getBytes(0, message.bodySize());
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        return Optional.of(new String(bytes, 0, length, StandardCharsets.UTF_8));
    }

    /**
     * Builds the object at {@code objectHeaderAddress}, classifying it from its header as a group, a
     * dataset, or a committed datatype. Shared by group traversal and object-reference resolution.
     */
    static Hdf5Object classify(FileContext ctx, String name, String parentPath, long objectHeaderAddress) {
        ObjectHeader header = ObjectHeader.parse(ctx, objectHeaderAddress);
        boolean isGroup = header.contains(MessageType.SYMBOL_TABLE)
                || header.contains(MessageType.LINK_INFO)
                || header.contains(MessageType.GROUP_INFO)
                || header.contains(MessageType.LINK);
        if (isGroup) {
            return Group.child(ctx, name, parentPath, objectHeaderAddress);
        }
        // A committed (named) datatype has a datatype message but no dataspace.
        if (header.contains(MessageType.DATATYPE) && !header.contains(MessageType.DATASPACE)) {
            return CommittedDatatype.child(ctx, name, parentPath, objectHeaderAddress);
        }
        return Dataset.child(ctx, name, parentPath, objectHeaderAddress);
    }

    /**
     * Resolves an object-reference buffer: each {@code stride}-byte element is a target object-header
     * address, resolved to the object it points at (or {@code null} for a null reference). Resolved
     * objects carry no reconstructed name/path; identify them via {@link #objectHeaderAddress()}.
     */
    static Hdf5Object[] resolveObjectReferences(FileContext ctx, MemorySegment data, int count, int stride) {
        HdfBuffer buffer = new HdfBuffer(data);
        int offsets = ctx.sizeOfOffsets();
        Hdf5Object[] out = new Hdf5Object[count];
        for (int i = 0; i < count; i++) {
            long address = buffer.getAddress((long) i * stride, offsets);
            // A null object reference is stored as an all-zero (address 0, where the superblock lives,
            // never an object) or all-ones (undefined) address.
            out[i] = (address == HdfBuffer.UNDEFINED_ADDRESS || address == 0)
                    ? null : classify(ctx, "", "", address);
        }
        return out;
    }

    /**
     * Resolves a region-reference buffer: each {@code stride}-byte element is a global-heap ID whose
     * object holds a target dataset's address followed by a serialized dataspace selection. Returns a
     * {@link Selection} of the referenced dataset per element (or {@code null} for a null reference).
     */
    static Selection[] resolveRegionReferences(FileContext ctx, MemorySegment data, int count, int stride) {
        HdfBuffer buffer = new HdfBuffer(data);
        int offsets = ctx.sizeOfOffsets();
        Selection[] out = new Selection[count];
        for (int i = 0; i < count; i++) {
            long base = (long) i * stride;
            long collection = buffer.getAddress(base, offsets);
            if (collection == HdfBuffer.UNDEFINED_ADDRESS) {
                continue; // null reference
            }
            int index = (int) buffer.getUnsignedInt(base + offsets);
            out[i] = parseRegion(ctx, GlobalHeap.readObject(ctx, collection, index), offsets);
        }
        return out;
    }

    /** Parses a serialized region reference (dataset address + dataspace selection) into a selection. */
    private static Selection parseRegion(FileContext ctx, byte[] object, int offsets) {
        HdfBuffer body = HdfBuffer.of(object);
        long datasetHeader = body.getAddress(0, offsets);
        if (!(classify(ctx, "", "", datasetHeader) instanceof Dataset dataset)) {
            throw new HdfFormatException("region reference does not point at a dataset");
        }
        long[] dims = dataset.dataspace().dimensions();
        int rank = dims.length;
        int type = (int) body.getUnsignedInt(offsets);
        if (type == 3) { // H5S_SEL_ALL: the whole dataset
            return dataset.select(new long[rank], dims);
        }
        if (type != 2) { // H5S_SEL_HYPERSLABS
            throw new HdfUnsupportedException("region reference selection type " + type
                    + " (only hyperslab and all-points selections are supported)");
        }
        long p = offsets + 4L; // past the selection type
        int version = (int) body.getUnsignedInt(p);
        if (version != 3) {
            throw new HdfUnsupportedException("region reference hyperslab selection version " + version
                    + " is not yet supported");
        }
        int encodeSize = body.getUnsignedByte(p + 5); // version(4), flags(1), encode size(1)
        int selectionRank = (int) body.getUnsignedInt(p + 6);
        long q = p + 10;
        long[] offset = new long[selectionRank];
        long[] shape = new long[selectionRank];
        for (int d = 0; d < selectionRank; d++) {
            long start = body.getUnsignedValue(q, encodeSize);
            long blockCount = body.getUnsignedValue(q + 2L * encodeSize, encodeSize);
            long block = body.getUnsignedValue(q + 3L * encodeSize, encodeSize);
            q += 4L * encodeSize; // start, stride, count, block
            if (blockCount != 1) {
                throw new HdfUnsupportedException("strided / multi-block region references are not yet supported");
            }
            offset[d] = start;
            shape[d] = block;
        }
        return dataset.select(offset, shape);
    }

    /** Joins a parent path and a child name into an absolute path. */
    static String childPath(String parentPath, String childName) {
        return parentPath.equals("/") ? "/" + childName : parentPath + "/" + childName;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + (path.isEmpty() ? "/" : path) + "]";
    }
}
