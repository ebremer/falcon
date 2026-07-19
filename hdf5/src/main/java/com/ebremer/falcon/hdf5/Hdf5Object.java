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

    /** Joins a parent path and a child name into an absolute path. */
    static String childPath(String parentPath, String childName) {
        return parentPath.equals("/") ? "/" + childName : parentPath + "/" + childName;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + (path.isEmpty() ? "/" : path) + "]";
    }
}
