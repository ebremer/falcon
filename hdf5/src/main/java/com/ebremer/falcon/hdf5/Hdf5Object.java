package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.btree.BTreeV2;
import com.ebremer.falcon.hdf5.checksum.Lookup3;
import com.ebremer.falcon.hdf5.data.DataspaceSelection;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.header.SharedMessage;
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
    private volatile ObjectHeader header; // parsed lazily, then cached (safely published across threads)
    private volatile List<Attribute> attributes; // read on first attributes(), then cached (immutable)

    Hdf5Object(FileContext ctx, String name, String path, long objectHeaderAddress) {
        this.ctx = ctx;
        this.name = name;
        this.path = path;
        this.objectHeaderAddress = objectHeaderAddress;
    }

    /** This object's header, parsed on first use. */
    ObjectHeader header() {
        ctx.checkOpen();
        ObjectHeader result = header;
        if (result == null) {
            result = ObjectHeader.parse(ctx, objectHeaderAddress);
            header = result;
        }
        return result;
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

    /**
     * This object's attributes (compact header messages and/or dense fractal-heap storage), read on first
     * use and then kept. The list is unmodifiable.
     */
    public List<Attribute> attributes() {
        ctx.checkOpen();
        List<Attribute> result = attributes;
        if (result == null) {
            result = List.copyOf(loadAttributes());
            attributes = result;
        }
        return result;
    }

    private List<Attribute> loadAttributes() {
        ObjectHeader header = header();
        List<Attribute> out = new ArrayList<>();
        for (HeaderMessage message : header.messages()) {
            if (message.type() == MessageType.ATTRIBUTE) {
                out.add(AttributeMessage.parse(ctx, SharedMessage.resolve(ctx, message)));
            }
        }
        HeaderMessage attributeInfo = header.find(MessageType.ATTRIBUTE_INFO);
        if (attributeInfo != null) {
            long fractalHeap = AttributeInfoMessage.fractalHeapAddress(ctx, attributeInfo);
            if (fractalHeap != HdfBuffer.UNDEFINED_ADDRESS) {
                FractalHeap heap = FractalHeap.parse(ctx, fractalHeap);
                for (byte[] record : BTreeV2.readRecords(ctx, AttributeInfoMessage.nameBTreeAddress(ctx, attributeInfo))) {
                    out.add(denseAttribute(heap, record));
                }
            }
        }
        return out;
    }

    /**
     * The attribute an attribute-name-index record (v2 B-tree type 8) points at: the record is the heap ID,
     * the message flags, the creation order, and the name's hash. A shared attribute's ID names its copy
     * in the shared-message heap, not in this object's heap.
     */
    private Attribute denseAttribute(FractalHeap heap, byte[] record) {
        if (record.length < heap.idLength() + 1) {
            throw new HdfFormatException("attribute name index record of " + record.length + " bytes");
        }
        byte[] heapId = Arrays.copyOfRange(record, 0, heap.idLength());
        if ((record[heap.idLength()] & SharedMessage.SHARED_FLAG) != 0) {
            return AttributeMessage.parse(ctx, SharedMessage.heapMessage(ctx, heapId, MessageType.ATTRIBUTE));
        }
        FractalHeap.HeapObject object = heap.locate(heapId);
        return AttributeMessage.parse(ctx, object.address(), object.length());
    }

    /**
     * The attribute with the given name, if present. Once {@link #attributes()} has been read it is
     * searched; otherwise dense storage is searched through its name index, as libhdf5 does: the records
     * whose name hash matches, so one lookup reads a few nodes rather than every attribute.
     */
    public Optional<Attribute> attribute(String name) {
        ctx.checkOpen();
        List<Attribute> loaded = attributes;
        if (loaded != null) {
            return loaded.stream().filter(a -> a.name().equals(name)).findFirst();
        }
        ObjectHeader header = header();
        for (HeaderMessage message : header.messages()) {
            if (message.type() == MessageType.ATTRIBUTE) {
                Attribute attribute = AttributeMessage.parse(ctx, SharedMessage.resolve(ctx, message));
                if (attribute.name().equals(name)) {
                    return Optional.of(attribute);
                }
            }
        }
        HeaderMessage attributeInfo = header.find(MessageType.ATTRIBUTE_INFO);
        if (attributeInfo != null) {
            long fractalHeap = AttributeInfoMessage.fractalHeapAddress(ctx, attributeInfo);
            if (fractalHeap != HdfBuffer.UNDEFINED_ADDRESS) {
                FractalHeap heap = FractalHeap.parse(ctx, fractalHeap);
                int hash = nameHash(name);
                // The record ends with the name's hash, which orders the index.
                for (byte[] record : BTreeV2.find(ctx, AttributeInfoMessage.nameBTreeAddress(ctx, attributeInfo),
                        record -> Integer.compareUnsigned(hashAt(record, record.length - 4), hash))) {
                    Attribute attribute = denseAttribute(heap, record);
                    if (attribute.name().equals(name)) {
                        return Optional.of(attribute);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The hash libhdf5 indexes a link or attribute name by: lookup3 of its UTF-8 bytes. */
    static int nameHash(String name) {
        return Lookup3.hashLittle(name.getBytes(StandardCharsets.UTF_8));
    }

    /** The little-endian 32-bit name hash at {@code offset} in a name-index record. */
    static int hashAt(byte[] record, int offset) {
        if (offset < 0 || offset + 4 > record.length) {
            throw new HdfFormatException("name index record of " + record.length + " bytes has no hash");
        }
        return (record[offset] & 0xff) | (record[offset + 1] & 0xff) << 8 | (record[offset + 2] & 0xff) << 16
                | (record[offset + 3] & 0xff) << 24;
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

    /** True for a revised reference datatype ({@code H5R_ref_t}, HDF5 1.12+). */
    static boolean isRevisedReference(Datatype type) {
        return type instanceof Datatype.Reference reference && switch (reference.kind()) {
            case REVISED_OBJECT, REVISED_DATASET_REGION, REVISED_ATTRIBUTE -> true;
            default -> false;
        };
    }

    /** True for an original reference datatype of the given kind. */
    static boolean isReference(Datatype type, Datatype.ReferenceKind kind) {
        return type instanceof Datatype.Reference reference && reference.kind() == kind;
    }

    /**
     * Resolves an object-reference buffer: each {@code stride}-byte element is a target object-header
     * address (or, if {@code revised}, a {@link RevisedReference} of any kind), resolved to the object it
     * points at or into (or {@code null} for a null reference). Resolved objects carry no reconstructed
     * name/path; identify them via {@link #objectHeaderAddress()}.
     *
     * @throws HdfUnsupportedException for a revised reference into another file
     */
    static Hdf5Object[] resolveObjectReferences(FileContext ctx, MemorySegment data, int count, int stride,
                                                boolean revised) {
        HdfBuffer buffer = new HdfBuffer(data);
        int offsets = ctx.sizeOfOffsets();
        Hdf5Object[] out = new Hdf5Object[count];
        for (int i = 0; i < count; i++) {
            if (revised) {
                RevisedReference reference = RevisedReference.decode(ctx, buffer, (long) i * stride, stride);
                if (reference != null) {
                    reference.requireLocal();
                    out[i] = classify(ctx, "", "", reference.address());
                }
                continue;
            }
            long address = buffer.getAddress((long) i * stride, offsets);
            // A null object reference is stored as an all-zero (address 0, where the superblock lives,
            // never an object) or all-ones (undefined) address.
            out[i] = (address == HdfBuffer.UNDEFINED_ADDRESS || address == 0)
                    ? null : classify(ctx, "", "", address);
        }
        return out;
    }

    /**
     * Resolves revised attribute references ({@code H5R_ATTR}) to the attributes they name, or
     * {@code null} for a null reference.
     *
     * @throws HdfUnsupportedException for an element that is not an attribute reference, or one into
     *         another file
     * @throws HdfFormatException if the object has no attribute of the referenced name
     */
    static Attribute[] resolveAttributeReferences(FileContext ctx, MemorySegment data, int count, int stride) {
        HdfBuffer buffer = new HdfBuffer(data);
        Attribute[] out = new Attribute[count];
        for (int i = 0; i < count; i++) {
            RevisedReference reference = RevisedReference.decode(ctx, buffer, (long) i * stride, stride);
            if (reference == null) {
                continue;
            }
            if (reference.type() != RevisedReference.ATTRIBUTE) {
                throw new HdfUnsupportedException("element " + i + " is " + reference.kind()
                        + ", not an attribute reference (read it with readObjectReferences)");
            }
            reference.requireLocal();
            Hdf5Object object = classify(ctx, "", "", reference.address());
            out[i] = object.attribute(reference.attributeName()).orElseThrow(() -> new HdfFormatException(
                    "attribute reference names '" + reference.attributeName() + "', which the object at "
                    + reference.address() + " does not have"));
        }
        return out;
    }

    /**
     * Resolves a region-reference buffer: each {@code stride}-byte element is a global-heap ID whose
     * object holds a target dataset's address followed by a serialized dataspace selection (or, if
     * {@code revised}, a {@link RevisedReference}). Returns a {@link Selection} of the referenced dataset
     * per element, or {@code null} for a null reference (an all-zero or undefined heap address). An
     * element that cannot be resolved, or a revised reference that is not a region in this file, becomes
     * a selection that throws when used, so it does not fail the others.
     */
    static Selection[] resolveRegionReferences(FileContext ctx, MemorySegment data, int count, int stride,
                                               boolean revised) {
        HdfBuffer buffer = new HdfBuffer(data);
        int offsets = ctx.sizeOfOffsets();
        Selection[] out = new Selection[count];
        for (int i = 0; i < count; i++) {
            long base = (long) i * stride;
            if (revised) {
                try {
                    RevisedReference reference = RevisedReference.decode(ctx, buffer, base, stride);
                    if (reference == null) {
                        continue;
                    }
                    if (reference.type() != RevisedReference.REGION) {
                        throw new HdfUnsupportedException("element " + i + " is " + reference.kind()
                                + ", not a region reference");
                    }
                    reference.requireLocal();
                    out[i] = region(ctx, reference.address(), reference.region(), reference.rank());
                } catch (HdfException e) {
                    out[i] = Selection.unresolved(e);
                }
                continue;
            }
            long collection = buffer.getAddress(base, offsets);
            if (collection == HdfBuffer.UNDEFINED_ADDRESS || collection == 0) {
                continue; // null reference
            }
            int index = (int) buffer.getUnsignedInt(base + offsets);
            try {
                out[i] = parseRegion(ctx, GlobalHeap.readObject(ctx, collection, index), offsets);
            } catch (HdfException e) {
                out[i] = Selection.unresolved(e);
            }
        }
        return out;
    }

    /** Parses a serialized region reference (dataset address + dataspace selection) into a selection. */
    private static Selection parseRegion(FileContext ctx, byte[] object, int offsets) {
        HdfBuffer body = HdfBuffer.of(object);
        return region(ctx, body.getAddress(0, offsets), DataspaceSelection.parse(body, offsets), -1);
    }

    /** The {@code selection} of the dataset at {@code datasetHeader}; {@code rank} is checked unless -1. */
    private static Selection region(FileContext ctx, long datasetHeader, DataspaceSelection selection, int rank) {
        if (!(classify(ctx, "", "", datasetHeader) instanceof Dataset dataset)) {
            throw new HdfFormatException("region reference does not point at a dataset");
        }
        long[] dims = dataset.dataspace().dimensions();
        if (rank >= 0 && rank != dims.length) {
            throw new HdfFormatException("region reference of rank " + rank + " into a dataset of rank " + dims.length);
        }
        // The selection comes from the file, so an out-of-range one is corrupt data, not a caller error.
        long[][] block = selection.singleBlock(dims);
        return block != null ? dataset.select(block[0], block[1])
                : Selection.ofCoordinates(dataset, selection.coordinates(dims));
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
