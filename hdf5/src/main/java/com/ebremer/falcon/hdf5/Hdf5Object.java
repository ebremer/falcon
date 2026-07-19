package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.btree.BTreeV2;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.heap.FractalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.message.AttributeInfoMessage;
import com.ebremer.falcon.hdf5.message.AttributeMessage;
import java.lang.foreign.MemorySegment;
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
            out[i] = address == HdfBuffer.UNDEFINED_ADDRESS ? null : classify(ctx, "", "", address);
        }
        return out;
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
